package com.leanowtech.bloge.gateway.example;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.core.operator.ExecutionBudget;
import com.leanowtech.bloge.core.spi.OperatorInterceptor;
import com.leanowtech.bloge.core.spi.OperatorInvocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread owner for controlled dynamic runs backed by a durable lifecycle repository.
 *
 * <p>The repository is authoritative for state, fencing and leases. This class only retains JVM-local
 * thread handles so the current owner can deliver cooperative interruption. Another instance can persist
 * a cancel command; the owner observes it while renewing its lease and interrupts the local threads.</p>
 */
final class DynamicRunControlManager implements OperatorInterceptor {

    static final String REQUEST_ID_CONTEXT_KEY = "_resourceGatewayRunRequestId";
    private static final Duration TERMINAL_RETENTION = Duration.ofHours(1);
    private static final Duration OWNER_LEASE = Duration.ofSeconds(5);
    private static final Duration DEFAULT_FINALIZATION_RESERVE = Duration.ofMillis(100);

    private final ConcurrentHashMap<String, ActiveRun> runs = new ConcurrentHashMap<>();
    private final DynamicRunControlRepository repository;
    private final String ownerId;
    private final Duration finalizationReserve;

    DynamicRunControlManager() {
        this(new InMemoryDynamicRunControlRepository(), DEFAULT_FINALIZATION_RESERVE);
    }

    DynamicRunControlManager(DynamicRunControlRepository repository) {
        this(repository, DEFAULT_FINALIZATION_RESERVE);
    }

    DynamicRunControlManager(DynamicRunControlRepository repository, Duration finalizationReserve) {
        this(repository, UUID.randomUUID().toString(), finalizationReserve);
    }

    DynamicRunControlManager(DynamicRunControlRepository repository, String ownerId) {
        this(repository, ownerId, DEFAULT_FINALIZATION_RESERVE);
    }

    DynamicRunControlManager(DynamicRunControlRepository repository,
                             String ownerId,
                             Duration finalizationReserve) {
        this.repository = repository == null ? new InMemoryDynamicRunControlRepository() : repository;
        this.ownerId = ownerId == null || ownerId.isBlank() ? UUID.randomUUID().toString() : ownerId;
        this.finalizationReserve = finalizationReserve == null ? DEFAULT_FINALIZATION_RESERVE : finalizationReserve;
        if (this.finalizationReserve.isNegative()) {
            throw new IllegalArgumentException("finalizationReserve must not be negative");
        }
    }

    Registration begin(DynamicRunIntent intent) {
        DynamicRunIntent effective = intent == null ? DynamicRunIntent.unmanaged() : intent;
        if (!effective.requested()) {
            return Registration.unmanaged();
        }
        String invalid = invalid(effective);
        if (!invalid.isBlank()) {
            DynamicRunControlView rejected = new DynamicRunControlView("", effective.requestId(), "", "REJECTED",
                    "INVALID_RUN_INTENT", 1, effective.deadlineAt(), null, null, Instant.now(), true, false);
            return Registration.rejected(rejected, invalid);
        }
        purgeExpired();
        Instant now = Instant.now();
        DynamicRunControlRepository.Claim claim = repository.claim(effective, ownerId, lease(now));
        if (!claim.accepted()) {
            DynamicRunControlView view = claim.state() == null
                    ? DynamicRunControlView.unmanaged()
                    : claim.state().view();
            return Registration.rejected(view, claim.message());
        }
        ActiveRun candidate = new ActiveRun(effective, claim.state().owner());
        ActiveRun existing = runs.putIfAbsent(effective.requestId(), candidate);
        if (existing != null) {
            return Registration.rejected(existing.view(), "Run request id is already registered in this owner.");
        }
        return Registration.managed(candidate);
    }

    void attach(Registration registration, Thread owner, GraphContext context) {
        if (!registration.managed()) {
            return;
        }
        registration.run().attach(owner);
        if (context != null) {
            context.put(REQUEST_ID_CONTEXT_KEY, registration.run().intent.requestId());
            if (registration.run().intent.deadlineAt() != null) {
                context.bindExecutionBudget(ExecutionBudget.until(
                        registration.run().intent.deadlineAt(), finalizationReserve));
            }
        }
    }

    void observeExecutionId(Registration registration, GraphContext context) {
        if (!registration.managed() || context == null) {
            return;
        }
        Object executionId = context.get(ReservedKeys.EXECUTION_ID);
        if (executionId != null) {
            registration.run().observeExecutionId(String.valueOf(executionId));
        }
    }

    void complete(Registration registration, GraphResult result, Throwable failure) {
        if (registration.managed()) {
            registration.run().complete(result, failure);
        }
    }

    DynamicRunControlView deadline(Registration registration) {
        return registration.managed()
                ? registration.run().requestStop("TIMING_OUT", "GRAPH_DEADLINE_EXCEEDED")
                : DynamicRunControlView.unmanaged();
    }

    DynamicRunControlView terminationUnconfirmed(Registration registration) {
        return registration.managed()
                ? registration.run().terminationUnconfirmed()
                : DynamicRunControlView.unmanaged();
    }

    DynamicRunControlView view(Registration registration) {
        if (registration.rejected()) {
            return registration.rejectionView();
        }
        return registration.managed() ? registration.run().view() : DynamicRunControlView.unmanaged();
    }

    long cancellationGraceMs(Registration registration) {
        return registration.managed()
                ? registration.run().intent.cancellationGraceMs()
                : DynamicRunIntent.DEFAULT_CANCELLATION_GRACE_MS;
    }

    DynamicRunControlResult find(String requestId, String fencingToken) {
        String normalized = requestId == null ? "" : requestId.trim();
        DynamicRunControlRepository.State state = repository.find(normalized, Instant.now()).orElse(null);
        if (state == null) {
            return new DynamicRunControlResult(false, "RG.RUN_CONTROL.NOT_FOUND", "Controlled run was not found.",
                    DynamicRunControlView.unmanaged());
        }
        if (!digestMatches(state.fenceDigest(), fencingToken)) {
            return new DynamicRunControlResult(false, "RG.RUN_CONTROL.FENCE_MISMATCH",
                    "Control lookup fencing token does not match the run intent.", DynamicRunControlView.unmanaged());
        }
        ActiveRun local = runs.get(normalized);
        if (local != null) {
            local.synchronize(state);
        }
        return new DynamicRunControlResult(true, "RG.RUN_CONTROL.FOUND", "", state.view());
    }

    DynamicRunControlResult cancel(DynamicRunControlCommand command) {
        if (command == null || command.requestId().isBlank()) {
            return new DynamicRunControlResult(false, "RG.RUN_CONTROL.INVALID_COMMAND",
                    "requestId is required.", DynamicRunControlView.unmanaged());
        }
        DynamicRunControlRepository.CommandResult result = repository.requestCallerCancel(command, Instant.now());
        DynamicRunControlRepository.State state = result.state();
        ActiveRun local = runs.get(command.requestId());
        if (local != null && state != null) {
            local.synchronize(state);
        }
        return new DynamicRunControlResult(result.accepted(), result.code(), result.message(),
                state == null ? DynamicRunControlView.unmanaged() : state.view());
    }

    @Override
    public Object intercept(OperatorInvocation invocation) throws Exception {
        Object requestId = invocation.operatorContext().graphContext().get(REQUEST_ID_CONTEXT_KEY);
        ActiveRun run = runs.get(requestId == null ? "" : String.valueOf(requestId));
        if (run == null) {
            return invocation.proceed();
        }
        if (!run.enterOperator()) {
            throw new ControlledRunCancellationException(run.view().reasonCode());
        }
        try {
            return invocation.proceed();
        } finally {
            run.exitOperator();
        }
    }

    private void purgeExpired() {
        repository.purgeTerminalBefore(Instant.now().minus(TERMINAL_RETENTION));
        runs.entrySet().removeIf(entry -> entry.getValue().terminalBefore(
                Instant.now().minus(TERMINAL_RETENTION)));
    }

    private static String invalid(DynamicRunIntent intent) {
        if (!DynamicRunIntent.SCHEMA_VERSION.equals(intent.schemaVersion())) {
            return "Unsupported run intent schemaVersion.";
        }
        if (intent.requestId().length() > 128) {
            return "requestId exceeds 128 characters.";
        }
        if (intent.requestId().isBlank()) {
            return "requestId is required when deadline or cancellation control is requested.";
        }
        if (intent.fencingToken().isBlank() || intent.fencingToken().length() > 256) {
            return "A fencingToken of at most 256 characters is required.";
        }
        return "";
    }

    private static Instant lease(Instant now) {
        return now.plus(OWNER_LEASE);
    }

    record Registration(ActiveRun run, DynamicRunControlView rejectionView, String rejection) {
        static Registration unmanaged() {
            return new Registration(null, DynamicRunControlView.unmanaged(), "");
        }

        static Registration managed(ActiveRun run) {
            return new Registration(run, DynamicRunControlView.unmanaged(), "");
        }

        static Registration rejected(DynamicRunControlView view, String rejection) {
            return new Registration(null, view, rejection == null ? "Run request was rejected." : rejection);
        }

        boolean managed() {
            return run != null;
        }

        boolean rejected() {
            return !rejection.isBlank();
        }
    }

    private final class ActiveRun {
        private final DynamicRunIntent intent;
        private final DynamicRunControlRepository.Owner ownerKey;
        private Thread ownerThread;
        private int activeOperators;
        private boolean ownerExited;
        private boolean resultSucceeded;
        private boolean resultFailed;
        private String stopCause = "";
        private final Set<Thread> operatorThreads = new HashSet<>();

        private ActiveRun(DynamicRunIntent intent, DynamicRunControlRepository.Owner ownerKey) {
            this.intent = intent;
            this.ownerKey = ownerKey;
        }

        private synchronized void attach(Thread owner) {
            this.ownerThread = owner;
            DynamicRunControlRepository.State state = repository.start(intent.requestId(), ownerKey, Instant.now(),
                    lease(Instant.now())).orElseThrow(() -> new IllegalStateException("Run control owner lease was lost"));
            synchronize(state);
        }

        private synchronized void observeExecutionId(String executionId) {
            repository.observeExecutionId(intent.requestId(), ownerKey, executionId, lease(Instant.now()))
                    .ifPresent(this::synchronize);
        }

        private synchronized DynamicRunControlView requestStop(String status, String reason) {
            DynamicRunControlRepository.State state = repository.requestOwnerStop(intent.requestId(), ownerKey,
                    status, reason, Instant.now(), lease(Instant.now())).orElse(null);
            if (state == null) {
                return view();
            }
            synchronize(state);
            return state.view();
        }

        private synchronized DynamicRunControlView terminationUnconfirmed() {
            DynamicRunControlRepository.State state = repository.markUnconfirmed(intent.requestId(), ownerKey,
                    "CANCELLATION_GRACE_EXCEEDED", Instant.now()).orElse(null);
            if (state != null) {
                synchronize(state);
                return state.view();
            }
            return view();
        }

        private synchronized void complete(GraphResult result, Throwable failure) {
            if (result != null && result.executionId() != null) {
                observeExecutionId(result.executionId());
            }
            ownerExited = true;
            resultSucceeded = failure == null && result != null && result.isSuccess();
            resultFailed = !resultSucceeded;
            if (activeOperators > 0) {
                repository.markUnconfirmed(intent.requestId(), ownerKey,
                        "OWNER_EXITED_WITH_ACTIVE_OPERATORS", Instant.now()).ifPresent(this::synchronize);
                return;
            }
            finalizeTermination();
        }

        private synchronized boolean enterOperator() {
            DynamicRunControlView current = view();
            if (InMemoryDynamicRunControlRepository.stopRequested(current)) {
                return false;
            }
            activeOperators++;
            operatorThreads.add(Thread.currentThread());
            return true;
        }

        private synchronized void exitOperator() {
            if (activeOperators > 0) {
                activeOperators--;
            }
            operatorThreads.remove(Thread.currentThread());
            if (ownerExited && activeOperators == 0) {
                finalizeTermination();
            }
        }

        private void finalizeTermination() {
            DynamicRunControlView current = repository.find(intent.requestId(), Instant.now())
                    .map(DynamicRunControlRepository.State::view).orElse(DynamicRunControlView.unmanaged());
            boolean deadline = "TIMING_OUT".equals(current.status()) || "GRAPH_DEADLINE_EXCEEDED".equals(current.reasonCode())
                    || "DEADLINE".equals(stopCause);
            boolean cancellation = "CANCEL_REQUESTED".equals(current.status())
                    || "USER_CANCEL_REQUESTED".equals(current.reasonCode()) || "USER".equals(stopCause);
            String status;
            String reason;
            if (deadline) {
                status = "TIMED_OUT";
                reason = "GRAPH_DEADLINE_TERMINATED";
            } else if (cancellation) {
                status = "CANCELLED";
                reason = "USER_CANCEL_TERMINATED";
            } else if (resultSucceeded) {
                status = "SUCCEEDED";
                reason = "EXECUTION_COMPLETED";
            } else if (resultFailed) {
                status = "FAILED";
                reason = "EXECUTION_FAILED";
            } else {
                return;
            }
            repository.finish(intent.requestId(), ownerKey, status, reason, Instant.now()).ifPresent(this::synchronize);
        }

        private synchronized DynamicRunControlView view() {
            DynamicRunControlRepository.State state = repository.find(intent.requestId(), Instant.now()).orElse(null);
            if (state == null) {
                return DynamicRunControlView.unmanaged();
            }
            synchronize(state);
            if (!InMemoryDynamicRunControlRepository.terminal(state.view())) {
                state = repository.renew(intent.requestId(), ownerKey, lease(Instant.now())).orElse(state);
            }
            return state.view();
        }

        private synchronized void synchronize(DynamicRunControlRepository.State state) {
            if (state == null) {
                return;
            }
            String status = state.view().status();
            if ("TIMING_OUT".equals(status)) {
                stopCause = "DEADLINE";
            } else if ("CANCEL_REQUESTED".equals(status)) {
                stopCause = "USER";
            }
            if (InMemoryDynamicRunControlRepository.stopRequested(state.view())) {
                if (ownerThread != null) {
                    ownerThread.interrupt();
                }
                operatorThreads.forEach(Thread::interrupt);
            }
        }

        private synchronized boolean terminalBefore(Instant cutoff) {
            return repository.find(intent.requestId(), Instant.now())
                    .map(DynamicRunControlRepository.State::view)
                    .map(DynamicRunControlView::terminalAt)
                    .filter(value -> value.isBefore(cutoff))
                    .isPresent();
        }
    }

    private static boolean digestMatches(String expectedDigest, String token) {
        return MessageDigest.isEqual(
                (expectedDigest == null ? "" : expectedDigest).getBytes(StandardCharsets.UTF_8),
                InMemoryDynamicRunControlRepository.digest(token).getBytes(StandardCharsets.UTF_8));
    }

    private static final class ControlledRunCancellationException extends RuntimeException {
        private ControlledRunCancellationException(String reasonCode) {
            super(reasonCode == null || reasonCode.isBlank() ? "Controlled run cancelled" : reasonCode);
        }
    }
}
