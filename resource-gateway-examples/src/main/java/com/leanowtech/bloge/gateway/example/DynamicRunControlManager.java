package com.leanowtech.bloge.gateway.example;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.core.spi.OperatorInterceptor;
import com.leanowtech.bloge.core.spi.OperatorInvocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local owner for controlled dynamic runs.
 *
 * <p>Cancellation is deliberately cooperative: the owner thread is interrupted and every later
 * operator boundary rejects execution. A run only becomes CANCELLED/TIMED_OUT after that owner
 * thread actually exits; otherwise it remains TERMINATION_UNCONFIRMED.</p>
 */
final class DynamicRunControlManager implements OperatorInterceptor {

    static final String REQUEST_ID_CONTEXT_KEY = "_resourceGatewayRunRequestId";
    private static final Duration TERMINAL_RETENTION = Duration.ofHours(1);

    private final ConcurrentHashMap<String, ActiveRun> runs = new ConcurrentHashMap<>();

    Registration begin(DynamicRunIntent intent) {
        DynamicRunIntent effective = intent == null ? DynamicRunIntent.unmanaged() : intent;
        if (!effective.requested()) {
            return Registration.unmanaged();
        }
        String invalid = invalid(effective);
        if (!invalid.isBlank()) {
            return Registration.rejected(new DynamicRunControlView("", effective.requestId(), "", "REJECTED",
                    "INVALID_RUN_INTENT", 1, effective.deadlineAt(), null, null, Instant.now(), true, false), invalid);
        }
        purgeExpired();
        ActiveRun candidate = new ActiveRun(effective);
        ActiveRun existing = runs.putIfAbsent(effective.requestId(), candidate);
        if (existing != null) {
            return Registration.rejected(existing.view(), "Run request id is already registered.");
        }
        return new Registration(candidate, "");
    }

    void attach(Registration registration, Thread owner, GraphContext context) {
        if (!registration.managed()) {
            return;
        }
        registration.run().attach(owner);
        if (context != null) {
            context.put(REQUEST_ID_CONTEXT_KEY, registration.run().intent.requestId());
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
                ? registration.run().requestStop("TIMING_OUT", "GRAPH_DEADLINE_EXCEEDED", "")
                : DynamicRunControlView.unmanaged();
    }

    DynamicRunControlView terminationUnconfirmed(Registration registration) {
        return registration.managed()
                ? registration.run().terminationUnconfirmed()
                : DynamicRunControlView.unmanaged();
    }

    DynamicRunControlView view(Registration registration) {
        return registration.managed() ? registration.run().view() : DynamicRunControlView.unmanaged();
    }

    long cancellationGraceMs(Registration registration) {
        return registration.managed()
                ? registration.run().intent.cancellationGraceMs()
                : DynamicRunIntent.DEFAULT_CANCELLATION_GRACE_MS;
    }

    DynamicRunControlResult find(String requestId, String fencingToken) {
        ActiveRun run = runs.get(requestId == null ? "" : requestId.trim());
        if (run == null) {
            return new DynamicRunControlResult(false, "RG.RUN_CONTROL.NOT_FOUND", "Controlled run was not found.",
                    DynamicRunControlView.unmanaged());
        }
        if (!run.fenceMatches(fencingToken)) {
            return new DynamicRunControlResult(false, "RG.RUN_CONTROL.FENCE_MISMATCH",
                    "Control lookup fencing token does not match the run intent.", DynamicRunControlView.unmanaged());
        }
        return new DynamicRunControlResult(true, "RG.RUN_CONTROL.FOUND", "", run.view());
    }

    DynamicRunControlResult cancel(DynamicRunControlCommand command) {
        if (command == null || command.requestId().isBlank()) {
            return new DynamicRunControlResult(false, "RG.RUN_CONTROL.INVALID_COMMAND",
                    "requestId is required.", DynamicRunControlView.unmanaged());
        }
        ActiveRun run = runs.get(command.requestId());
        if (run == null) {
            return new DynamicRunControlResult(false, "RG.RUN_CONTROL.NOT_FOUND",
                    "Controlled run was not found.", DynamicRunControlView.unmanaged());
        }
        return run.cancel(command);
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
        Instant cutoff = Instant.now().minus(TERMINAL_RETENTION);
        runs.entrySet().removeIf(entry -> entry.getValue().terminalBefore(cutoff));
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

    record Registration(ActiveRun run, String rejection) {
        static Registration unmanaged() {
            return new Registration(null, "");
        }

        static Registration rejected(DynamicRunControlView view, String rejection) {
            return new Registration(ActiveRun.rejected(view), rejection);
        }

        boolean managed() {
            return run != null;
        }

        boolean rejected() {
            return !rejection.isBlank();
        }
    }

    private static final class ActiveRun {
        private final DynamicRunIntent intent;
        private Thread owner;
        private String engineExecutionId = "";
        private String status = "QUEUED";
        private String reasonCode = "ACCEPTED";
        private long revision = 1;
        private Instant startedAt;
        private Instant cancelRequestedAt;
        private Instant terminalAt;
        private boolean terminationConfirmed;
        private boolean sideEffectsMayBeInFlight;
        private String stopCause = "";
        private int activeOperators;
        private boolean ownerExited;
        private boolean resultSucceeded;
        private boolean resultFailed;
        private final Set<Thread> operatorThreads = new HashSet<>();

        private ActiveRun(DynamicRunIntent intent) {
            this.intent = intent;
        }

        private static ActiveRun rejected(DynamicRunControlView view) {
            ActiveRun run = new ActiveRun(new DynamicRunIntent("", view.requestId(), view.deadlineAt(), "", 2_000));
            run.status = view.status();
            run.reasonCode = view.reasonCode();
            run.revision = view.revision();
            run.terminalAt = view.terminalAt();
            run.terminationConfirmed = true;
            return run;
        }

        private synchronized void attach(Thread owner) {
            this.owner = owner;
            if (stopRequested()) {
                owner.interrupt();
                return;
            }
            status = "RUNNING";
            reasonCode = "EXECUTION_STARTED";
            startedAt = Instant.now();
            revision++;
        }

        private synchronized void observeExecutionId(String executionId) {
            if (engineExecutionId.isBlank() && executionId != null && !executionId.isBlank()) {
                engineExecutionId = executionId;
                revision++;
            }
        }

        private synchronized DynamicRunControlResult cancel(DynamicRunControlCommand command) {
            if (!constantTimeEquals(intent.fencingToken(), command.fencingToken())) {
                return new DynamicRunControlResult(false, "RG.RUN_CONTROL.FENCE_MISMATCH",
                        "Control command fencing token does not match the run intent.", view());
            }
            if (command.expectedRevision() > 0 && command.expectedRevision() != revision) {
                return new DynamicRunControlResult(false, "RG.RUN_CONTROL.REVISION_CONFLICT",
                        "Control command expectedRevision is stale.", view());
            }
            if (terminal()) {
                return new DynamicRunControlResult(false, "RG.RUN_CONTROL.ALREADY_TERMINAL",
                        "Controlled run has already reached a terminal state.", view());
            }
            DynamicRunControlView changed = requestStop("CANCEL_REQUESTED", "USER_CANCEL_REQUESTED", command.reason());
            return new DynamicRunControlResult(true, "RG.RUN_CONTROL.CANCEL_ACCEPTED", "", changed);
        }

        private synchronized DynamicRunControlView requestStop(String requestedStatus, String reason, String detail) {
            if (terminal() || stopRequested()) {
                return view();
            }
            status = requestedStatus;
            reasonCode = reason;
            stopCause = "TIMING_OUT".equals(requestedStatus) ? "DEADLINE" : "USER";
            cancelRequestedAt = Instant.now();
            sideEffectsMayBeInFlight = true;
            revision++;
            if (owner != null) {
                owner.interrupt();
            }
            operatorThreads.forEach(Thread::interrupt);
            return view();
        }

        private synchronized DynamicRunControlView terminationUnconfirmed() {
            if (!terminationConfirmed && stopRequested()) {
                status = "TERMINATION_UNCONFIRMED";
                reasonCode = "CANCELLATION_GRACE_EXCEEDED";
                sideEffectsMayBeInFlight = true;
                revision++;
            }
            return view();
        }

        private synchronized void complete(GraphResult result, Throwable failure) {
            if (result != null && result.executionId() != null) {
                engineExecutionId = result.executionId();
            }
            ownerExited = true;
            resultSucceeded = failure == null && result != null && result.isSuccess();
            resultFailed = !resultSucceeded;
            if (activeOperators > 0) {
                status = "TERMINATION_UNCONFIRMED";
                reasonCode = "OWNER_EXITED_WITH_ACTIVE_OPERATORS";
                sideEffectsMayBeInFlight = true;
                revision++;
                return;
            }
            finalizeTermination();
        }

        private synchronized boolean enterOperator() {
            if (stopRequested()) {
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
            if (ownerExited && activeOperators == 0 && !terminationConfirmed) {
                finalizeTermination();
            }
        }

        private void finalizeTermination() {
            boolean deadline = "DEADLINE".equals(stopCause);
            boolean cancellation = "USER".equals(stopCause);
            if (deadline) {
                status = "TIMED_OUT";
                reasonCode = "GRAPH_DEADLINE_TERMINATED";
            } else if (cancellation) {
                status = "CANCELLED";
                reasonCode = "USER_CANCEL_TERMINATED";
            } else if (resultSucceeded) {
                status = "SUCCEEDED";
                reasonCode = "EXECUTION_COMPLETED";
            } else if (resultFailed) {
                status = "FAILED";
                reasonCode = "EXECUTION_FAILED";
            }
            terminationConfirmed = true;
            sideEffectsMayBeInFlight = false;
            terminalAt = Instant.now();
            revision++;
        }

        private synchronized boolean stopRequested() {
            return "CANCEL_REQUESTED".equals(status)
                    || "TIMING_OUT".equals(status)
                    || "TERMINATION_UNCONFIRMED".equals(status);
        }

        private synchronized boolean terminal() {
            return terminationConfirmed || "REJECTED".equals(status);
        }

        private synchronized boolean terminalBefore(Instant cutoff) {
            return terminalAt != null && terminalAt.isBefore(cutoff);
        }

        private boolean fenceMatches(String fencingToken) {
            return constantTimeEquals(intent.fencingToken(), fencingToken);
        }

        private synchronized DynamicRunControlView view() {
            return new DynamicRunControlView("", intent.requestId(), engineExecutionId, status, reasonCode,
                    revision, intent.deadlineAt(), startedAt, cancelRequestedAt, terminalAt,
                    terminationConfirmed, sideEffectsMayBeInFlight);
        }

        private static boolean constantTimeEquals(String expected, String actual) {
            return MessageDigest.isEqual(
                    (expected == null ? "" : expected).getBytes(StandardCharsets.UTF_8),
                    (actual == null ? "" : actual).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class ControlledRunCancellationException extends RuntimeException {
        private ControlledRunCancellationException(String reasonCode) {
            super(reasonCode == null || reasonCode.isBlank() ? "Controlled run cancelled" : reasonCode);
        }
    }
}
