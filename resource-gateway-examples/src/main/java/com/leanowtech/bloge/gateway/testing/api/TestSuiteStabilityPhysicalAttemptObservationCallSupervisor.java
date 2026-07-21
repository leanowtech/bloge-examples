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
 * Fixed-capacity, zero-queue boundary for physical-attempt observation providers.
 *
 * <p>Descriptor and observation calls either begin immediately or fail closed. A local timeout
 * requests interruption but proves neither that the provider did not observe the attempt nor any
 * particular lifecycle state. An adapter that ignores interruption remains in
 * {@link Snapshot#lingeringCalls()} and occupies its fixed slot until the provider call actually
 * returns.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptObservationCallSupervisor
        implements AutoCloseable {

    private static final AtomicLong POOL_SEQUENCE = new AtomicLong();

    private final Policy policy;
    private final ThreadPoolExecutor executor;
    private final Set<CallState<?>> runningCalls = ConcurrentHashMap.newKeySet();
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

    /** Creates the conservative default physical-attempt observation boundary. */
    public TestSuiteStabilityPhysicalAttemptObservationCallSupervisor() {
        this(Policy.DEFAULT);
    }

    /**
     * Creates a provider boundary with explicit capacity and deadline policy.
     *
     * @param policy descriptor/observation deadlines and fixed process-local capacity
     */
    public TestSuiteStabilityPhysicalAttemptObservationCallSupervisor(Policy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        long poolId = POOL_SEQUENCE.incrementAndGet();
        AtomicLong threadSequence = new AtomicLong();
        ThreadFactory factory = task -> Thread.ofPlatform().daemon(true).name(
                "resource-gateway-physical-attempt-observation-" + poolId + '-'
                        + threadSequence.incrementAndGet()).unstarted(task);
        executor = new ThreadPoolExecutor(
                policy.maximumConcurrentCalls(), policy.maximumConcurrentCalls(),
                0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Obtains a provider descriptor within its dedicated deadline.
     *
     * @param authority opaque isolated-runtime observation adapter
     * @return untrusted descriptor for subsequent exact binding
     */
    public TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor(
            TestSuiteStabilityPhysicalAttemptObservationAuthority authority) {
        TestSuiteStabilityPhysicalAttemptObservationAuthority required =
                Objects.requireNonNull(authority, "authority");
        return invoke(CallType.DESCRIPTOR, policy.descriptorTimeout(), required::descriptor);
    }

    /**
     * Sends one idempotent observation command within the configured deadline.
     *
     * @param authority opaque isolated-runtime observation adapter
     * @param command exact content-addressed observation command
     * @return untrusted detached attestation for independent verification
     */
    public TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation observe(
            TestSuiteStabilityPhysicalAttemptObservationAuthority authority,
            TestSuiteStabilityPhysicalAttemptObservationCommand command) {
        TestSuiteStabilityPhysicalAttemptObservationAuthority required =
                Objects.requireNonNull(authority, "authority");
        TestSuiteStabilityPhysicalAttemptObservationCommand safeCommand =
                Objects.requireNonNull(command, "command");
        return invoke(CallType.OBSERVATION, policy.observationTimeout(),
                () -> required.observe(safeCommand));
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

    /** Rejects new operations and requests interruption of active adapters without waiting. */
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

    private <T> T invoke(CallType callType, Duration timeout, Callable<T> operation) {
        if (closed.get()) {
            closedCalls.incrementAndGet();
            throw failed(callType, Disposition.CLOSED);
        }
        CallState<T> call = new CallState<>(operation);
        Future<T> future;
        try {
            synchronized (outcomeLock) {
                if (closed.get()) {
                    closedCalls.incrementAndGet();
                    throw failed(callType, Disposition.CLOSED);
                }
                future = executor.submit(call);
                acceptedCalls.incrementAndGet();
            }
        } catch (RejectedExecutionException saturated) {
            if (closed.get()) {
                closedCalls.incrementAndGet();
                throw failed(callType, Disposition.CLOSED);
            }
            saturatedCalls.incrementAndGet();
            throw failed(callType, Disposition.SATURATED);
        }
        try {
            T result = Objects.requireNonNull(
                    future.get(timeout.toMillis(), TimeUnit.MILLISECONDS),
                    "provider result");
            increment(completedCalls);
            return result;
        } catch (TimeoutException timedOut) {
            increment(timedOutCalls);
            call.requestCancellation();
            future.cancel(true);
            throw failed(callType, Disposition.TIMED_OUT);
        } catch (InterruptedException callerInterrupted) {
            increment(interruptedCalls);
            call.requestCancellation();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failed(callType, Disposition.CALLER_INTERRUPTED);
        } catch (ExecutionException | CancellationException | NullPointerException unavailable) {
            increment(failedCalls);
            throw failed(callType, Disposition.UNAVAILABLE);
        }
    }

    private void increment(AtomicLong counter) {
        synchronized (outcomeLock) {
            counter.incrementAndGet();
        }
    }

    private static InvocationException failed(CallType type, Disposition disposition) {
        return new InvocationException(type, disposition);
    }

    /** Provider operation class used in bounded diagnostics. */
    public enum CallType {
        /** Provider identity/capability lookup. */
        DESCRIPTOR,
        /** Idempotent physical-attempt lifecycle observation. */
        OBSERVATION
    }

    /** Closed local call outcome; none proves any physical-attempt lifecycle state. */
    public enum Disposition {
        /** Wall-clock deadline elapsed and local interrupt was requested. */
        TIMED_OUT,
        /** Every fixed worker slot was occupied, so no call started. */
        SATURATED,
        /** Supervisor had already closed. */
        CLOSED,
        /** Waiting caller was interrupted and its interrupt flag restored. */
        CALLER_INTERRUPTED,
        /** Adapter failed, returned null, or was cancelled locally. */
        UNAVAILABLE
    }

    /**
     * Fixed process-local provider-call limits.
     *
     * @param descriptorTimeout provider descriptor timeout from 100 ms through 30 s
     * @param observationTimeout provider observation timeout from 100 ms through 5 min
     * @param maximumConcurrentCalls fixed process-local worker count from 1 through 32
     */
    public record Policy(
            Duration descriptorTimeout,
            Duration observationTimeout,
            int maximumConcurrentCalls) {

        /** Default 5 s descriptor, 30 s observation, and 8 fixed workers. */
        public static final Policy DEFAULT =
                new Policy(Duration.ofSeconds(5), Duration.ofSeconds(30), 8);

        /** Enforces millisecond-exact bounded deadlines and capacity. */
        public Policy {
            descriptorTimeout = timeout(
                    descriptorTimeout, Duration.ofSeconds(30), "descriptorTimeout");
            observationTimeout = timeout(
                    observationTimeout, Duration.ofMinutes(5), "observationTimeout");
            if (maximumConcurrentCalls < 1 || maximumConcurrentCalls > 32) {
                throw new IllegalArgumentException(
                        "Physical-attempt observation provider capacity is invalid");
            }
        }

        private static Duration timeout(Duration value, Duration maximum, String field) {
            Duration required = Objects.requireNonNull(value, field);
            if (required.compareTo(Duration.ofMillis(100)) < 0
                    || required.compareTo(maximum) > 0
                    || !required.equals(Duration.ofMillis(required.toMillis()))) {
                throw new IllegalArgumentException(
                        "Physical-attempt observation provider timeout is invalid");
            }
            return required;
        }
    }

    /**
     * Payload-free process-local supervisor observation.
     *
     * @param schemaVersion exact snapshot generation
     * @param policy immutable capacity/deadline policy
     * @param acceptedCalls calls admitted to a fixed worker
     * @param completedCalls calls returning normally before deadline
     * @param failedCalls adapters failing or returning null before deadline
     * @param timedOutCalls callers observing wall-clock timeout
     * @param saturatedCalls calls rejected because every worker was occupied
     * @param interruptedCalls calls abandoned after caller interruption
     * @param closedCalls calls rejected after shutdown
     * @param activeCalls adapter methods currently executing
     * @param lingeringCalls active adapters still running after local cancellation request
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

        /** Exact local observation-call supervisor snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptObservationCallSnapshot.v1";

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
                        "Physical-attempt observation provider snapshot is invalid");
            }
        }
    }

    /** Bounded local failure that excludes provider identity and diagnostics. */
    public static final class InvocationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        /** Provider operation that produced the bounded failure. */
        private final CallType callType;
        /** Closed local outcome without provider diagnostics. */
        private final Disposition disposition;

        private InvocationException(CallType callType, Disposition disposition) {
            super("Suite-stability physical-attempt observation provider call was "
                    + Objects.requireNonNull(disposition, "disposition")
                    .name().toLowerCase(java.util.Locale.ROOT));
            this.callType = Objects.requireNonNull(callType, "callType");
            this.disposition = disposition;
        }

        /**
         * Identifies which supervised provider operation failed.
         *
         * @return exact provider operation class
         */
        public CallType callType() {
            return callType;
        }

        /**
         * Identifies the local disposition without implying a physical-attempt state.
         *
         * @return bounded local outcome
         */
        public Disposition disposition() {
            return disposition;
        }
    }

    private final class CallState<T> implements Callable<T> {
        private final Callable<T> operation;
        private boolean started;
        private boolean completed;
        private boolean cancellationRequested;
        private boolean lingeringCounted;

        private CallState(Callable<T> operation) {
            this.operation = Objects.requireNonNull(operation, "operation");
        }

        @Override
        public T call() throws Exception {
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
