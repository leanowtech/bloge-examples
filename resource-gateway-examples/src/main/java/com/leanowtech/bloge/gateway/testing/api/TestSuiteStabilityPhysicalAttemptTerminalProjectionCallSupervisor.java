package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-capacity, zero-queue boundary for one terminal-projection coordinator call.
 *
 * <p>A call either starts immediately or fails closed. Its caller supplies a dynamic timeout no
 * greater than {@link Policy#maximumProjectionTimeout()} so a worker can subtract durable claim
 * latency and completion reserve from the database lease. Local cancellation never proves that
 * projection did not commit: an interrupt-ignoring call remains visible as lingering and holds
 * its fixed slot until it actually returns.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor
        implements AutoCloseable {

    private static final AtomicLong POOL_SEQUENCE = new AtomicLong();
    private static final Duration MINIMUM_TIMEOUT = Duration.ofMillis(100);

    private final Policy policy;
    private final ThreadPoolExecutor executor;
    private final Set<CallState> runningCalls = ConcurrentHashMap.newKeySet();
    private final Object outcomeLock = new Object();
    private final Object occupancyLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong acceptedCalls = new AtomicLong();
    private final AtomicLong completedCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();
    private final AtomicLong timedOutCalls = new AtomicLong();
    private final AtomicLong saturatedCalls = new AtomicLong();
    private final AtomicLong interruptedCalls = new AtomicLong();
    private final AtomicLong closedCalls = new AtomicLong();
    private final AtomicLong activeCalls = new AtomicLong();
    private final AtomicLong lingeringCalls = new AtomicLong();

    /** Creates the conservative default terminal-projection boundary. */
    public TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor() {
        this(Policy.DEFAULT);
    }

    /**
     * Creates a terminal-projection boundary with explicit deadline and capacity.
     *
     * @param policy maximum call deadline and fixed process-local capacity
     */
    public TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor(Policy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        long poolId = POOL_SEQUENCE.incrementAndGet();
        AtomicLong threadSequence = new AtomicLong();
        ThreadFactory factory = task -> Thread.ofPlatform().daemon(true).name(
                "resource-gateway-physical-attempt-terminal-projection-" + poolId + '-'
                        + threadSequence.incrementAndGet()).unstarted(task);
        executor = new ThreadPoolExecutor(
                policy.maximumConcurrentCalls(), policy.maximumConcurrentCalls(),
                0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Returns the immutable local call policy used for lease-budget validation.
     *
     * @return fixed capacity and maximum projection deadline
     */
    public Policy policy() {
        return policy;
    }

    /**
     * Executes one coordinator attempt inside an already-derived lease budget.
     *
     * @param coordinator exact-source projection coordinator
     * @param tenantId exact work tenant
     * @param environmentId exact work environment
     * @param attemptId exact work attempt
     * @param queuePolicy active queue retry and retention policy
     * @param timeout caller-derived deadline no greater than the configured maximum
     * @return authoritative coordinator attempt returned before the deadline
     */
    public TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt project(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator coordinator,
            String tenantId,
            String environmentId,
            String attemptId,
            TestSuiteStabilityQueuePolicy queuePolicy,
            Duration timeout) {
        TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator exactCoordinator =
                Objects.requireNonNull(coordinator, "coordinator");
        String exactTenant = required(tenantId, "tenantId");
        String exactEnvironment = required(environmentId, "environmentId");
        String exactAttempt = required(attemptId, "attemptId");
        TestSuiteStabilityQueuePolicy exactPolicy = Objects.requireNonNull(
                queuePolicy, "queuePolicy");
        Duration exactTimeout = timeout(timeout);
        return invoke(exactTimeout, () -> exactCoordinator.project(
                exactTenant, exactEnvironment, exactAttempt, exactPolicy));
    }

    /**
     * Captures current process-local occupancy and closed outcome counters.
     *
     * @return payload-free monotonic capacity and liveness observation
     */
    public Snapshot snapshot() {
        synchronized (outcomeLock) {
            synchronized (occupancyLock) {
                return new Snapshot(Snapshot.SCHEMA_VERSION, policy,
                        acceptedCalls.get(), completedCalls.get(), failedCalls.get(),
                        timedOutCalls.get(), saturatedCalls.get(), interruptedCalls.get(),
                        closedCalls.get(), activeCalls.get(), lingeringCalls.get(), closed.get());
            }
        }
    }

    /** Rejects new calls and requests interruption of active coordinators without waiting. */
    @Override
    public void close() {
        boolean closing;
        synchronized (outcomeLock) {
            closing = closed.compareAndSet(false, true);
            if (closing) {
                executor.shutdownNow();
            }
        }
        if (closing) {
            runningCalls.forEach(CallState::requestCancellation);
        }
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt invoke(
            Duration timeout,
            Callable<TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt>
                    operation) {
        if (closed.get()) {
            closedCalls.incrementAndGet();
            throw failed(Disposition.CLOSED);
        }
        CallState call = new CallState(operation);
        Future<TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt> future;
        try {
            synchronized (outcomeLock) {
                if (closed.get()) {
                    closedCalls.incrementAndGet();
                    throw failed(Disposition.CLOSED);
                }
                future = executor.submit(call);
                acceptedCalls.incrementAndGet();
            }
        } catch (RejectedExecutionException saturated) {
            if (closed.get()) {
                closedCalls.incrementAndGet();
                throw failed(Disposition.CLOSED);
            }
            saturatedCalls.incrementAndGet();
            throw failed(Disposition.SATURATED);
        }
        try {
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt result =
                    Objects.requireNonNull(
                            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS),
                            "terminal projection result");
            increment(completedCalls);
            return result;
        } catch (TimeoutException timedOut) {
            increment(timedOutCalls);
            call.requestCancellation();
            future.cancel(true);
            throw failed(Disposition.TIMED_OUT);
        } catch (InterruptedException callerInterrupted) {
            increment(interruptedCalls);
            call.requestCancellation();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failed(Disposition.CALLER_INTERRUPTED);
        } catch (ExecutionException | CancellationException | NullPointerException unavailable) {
            increment(failedCalls);
            throw failed(Disposition.UNAVAILABLE);
        }
    }

    private Duration timeout(Duration value) {
        Duration required = Objects.requireNonNull(value, "timeout");
        if (required.compareTo(MINIMUM_TIMEOUT) < 0
                || required.compareTo(policy.maximumProjectionTimeout()) > 0
                || !required.equals(Duration.ofMillis(required.toMillis()))) {
            throw new IllegalArgumentException(
                    "Physical-attempt terminal projection timeout is invalid");
        }
        return required;
    }

    private void increment(AtomicLong counter) {
        synchronized (outcomeLock) {
            counter.incrementAndGet();
        }
    }

    private static InvocationException failed(Disposition disposition) {
        return new InvocationException(disposition);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    /** Closed local call outcome; none proves projection commit or non-commit. */
    public enum Disposition {
        /** Caller-owned deadline elapsed and local interruption was requested. */
        TIMED_OUT,
        /** Every fixed worker slot was occupied, so no coordinator call started. */
        SATURATED,
        /** Supervisor had already closed. */
        CLOSED,
        /** Waiting caller was interrupted and its interrupt flag restored. */
        CALLER_INTERRUPTED,
        /** Coordinator failed, returned null, or was cancelled locally. */
        UNAVAILABLE
    }

    /**
     * Fixed process-local terminal-projection call limits.
     *
     * @param maximumProjectionTimeout configured call ceiling from 100 ms through five minutes
     * @param maximumConcurrentCalls fixed process-local worker count from 1 through 32
     */
    public record Policy(Duration maximumProjectionTimeout, int maximumConcurrentCalls) {

        /** Default 20 s projection ceiling and four fixed workers. */
        public static final Policy DEFAULT = new Policy(Duration.ofSeconds(20), 4);

        /** Enforces millisecond-exact bounded deadline and capacity. */
        public Policy {
            maximumProjectionTimeout = Objects.requireNonNull(
                    maximumProjectionTimeout, "maximumProjectionTimeout");
            if (maximumProjectionTimeout.compareTo(MINIMUM_TIMEOUT) < 0
                    || maximumProjectionTimeout.compareTo(Duration.ofMinutes(5)) > 0
                    || !maximumProjectionTimeout.equals(
                    Duration.ofMillis(maximumProjectionTimeout.toMillis()))
                    || maximumConcurrentCalls < 1 || maximumConcurrentCalls > 32) {
                throw new IllegalArgumentException(
                        "Physical-attempt terminal projection call policy is invalid");
            }
        }
    }

    /**
     * Payload-free process-local supervisor observation.
     *
     * @param schemaVersion exact snapshot generation
     * @param policy immutable capacity/deadline policy
     * @param acceptedCalls calls admitted to a fixed worker
     * @param completedCalls calls returning normally before deadline
     * @param failedCalls calls failing or returning null before deadline
     * @param timedOutCalls callers observing a local timeout
     * @param saturatedCalls calls rejected because every worker was occupied
     * @param interruptedCalls calls abandoned after caller interruption
     * @param closedCalls calls rejected after shutdown
     * @param activeCalls coordinator methods currently executing
     * @param lingeringCalls active coordinators still running after cancellation request
     * @param closed whether new calls are rejected
     */
    public record Snapshot(
            String schemaVersion,
            Policy policy,
            long acceptedCalls,
            long completedCalls,
            long failedCalls,
            long timedOutCalls,
            long saturatedCalls,
            long interruptedCalls,
            long closedCalls,
            long activeCalls,
            long lingeringCalls,
            boolean closed) {

        /** Exact local terminal-projection supervisor snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionCallSnapshot.v1";

        /** Validates monotonic counters and physically possible occupancy. */
        public Snapshot {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            policy = Objects.requireNonNull(policy, "policy");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || acceptedCalls < 0 || completedCalls < 0 || failedCalls < 0
                    || timedOutCalls < 0 || saturatedCalls < 0 || interruptedCalls < 0
                    || closedCalls < 0 || activeCalls < 0 || lingeringCalls < 0
                    || activeCalls > policy.maximumConcurrentCalls()
                    || lingeringCalls > activeCalls
                    || completedCalls + failedCalls + timedOutCalls + interruptedCalls
                    > acceptedCalls) {
                throw new IllegalArgumentException(
                        "Physical-attempt terminal projection call snapshot is invalid");
            }
        }
    }

    /** Bounded local failure that excludes work identity and coordinator diagnostics. */
    public static final class InvocationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        /** Closed local outcome without coordinator diagnostics. */
        private final Disposition disposition;

        private InvocationException(Disposition disposition) {
            super("Suite-stability physical-attempt terminal projection call was "
                    + Objects.requireNonNull(disposition, "disposition")
                    .name().toLowerCase(java.util.Locale.ROOT));
            this.disposition = disposition;
        }

        /**
         * Identifies the local result without implying projection commit or non-commit.
         *
         * @return bounded local outcome
         */
        public Disposition disposition() {
            return disposition;
        }
    }

    private final class CallState implements Callable<
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt> {
        private final Callable<
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt>
                operation;
        private boolean started;
        private boolean completed;
        private boolean cancellationRequested;
        private boolean lingeringCounted;

        private CallState(Callable<
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt>
                operation) {
            this.operation = Objects.requireNonNull(operation, "operation");
        }

        @Override
        public TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt call()
                throws Exception {
            synchronized (this) {
                synchronized (outcomeLock) {
                    if (closed.get()) {
                        throw new CancellationException();
                    }
                    started = true;
                    synchronized (occupancyLock) {
                        activeCalls.incrementAndGet();
                    }
                    runningCalls.add(this);
                }
                countLingeringIfRequired();
            }
            try {
                return operation.call();
            } finally {
                synchronized (this) {
                    completed = true;
                    if (lingeringCounted) {
                        synchronized (occupancyLock) {
                            lingeringCalls.decrementAndGet();
                        }
                    }
                }
                synchronized (occupancyLock) {
                    activeCalls.decrementAndGet();
                }
                runningCalls.remove(this);
            }
        }

        private synchronized void requestCancellation() {
            cancellationRequested = true;
            countLingeringIfRequired();
        }

        private void countLingeringIfRequired() {
            if (started && !completed && cancellationRequested && !lingeringCounted) {
                lingeringCounted = true;
                synchronized (occupancyLock) {
                    lingeringCalls.incrementAndGet();
                }
            }
        }
    }
}
