package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Maintains a queue lease and exposes it as the stability algorithm's cooperative control.
 *
 * <p>One process-wide daemon scheduler renews guards during long child suite attempts. Every
 * algorithm checkpoint performs an additional synchronous database decision, and terminal
 * preparation consumes the repository's typed cancel/deadline result. Store ambiguity is never
 * interpreted as permission to continue.</p>
 */
public final class TestSuiteStabilityJobExecutionCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityJobExecutionCoordinator.class);

    private final TestSuiteStabilityJobRepository repository;
    private final ObjectMapper objectMapper;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService scheduler;
    private final Object lifecycleMonitor = new Object();
    private final Set<ExecutionGuard> activeGuards =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /**
     * Creates an active process-wide queue heartbeat coordinator.
     *
     * @param repository database-authoritative stability job queue
     * @param objectMapper canonical protocol mapper used for parent identity derivation
     * @param heartbeatInterval whole-second interval, checked against each active queue policy
     */
    public TestSuiteStabilityJobExecutionCoordinator(
            TestSuiteStabilityJobRepository repository,
            ObjectMapper objectMapper,
            Duration heartbeatInterval) {
        this(repository, objectMapper, heartbeatInterval, true);
    }

    private TestSuiteStabilityJobExecutionCoordinator(
            TestSuiteStabilityJobRepository repository,
            ObjectMapper objectMapper,
            Duration heartbeatInterval,
            boolean active) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.heartbeatInterval = boundedHeartbeat(heartbeatInterval);
        scheduler = active ? Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(
                    task, "resource-gateway-stability-job-heartbeat");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    /**
     * Creates a coordinator without periodic scheduling for deterministic focused tests.
     * Synchronous checkpoints still use the real repository contract.
     *
     * @param repository queue repository under test
     * @param objectMapper canonical protocol mapper
     * @param heartbeatInterval policy-compatible heartbeat interval
     * @return passive coordinator
     */
    public static TestSuiteStabilityJobExecutionCoordinator passive(
            TestSuiteStabilityJobRepository repository,
            ObjectMapper objectMapper,
            Duration heartbeatInterval) {
        return new TestSuiteStabilityJobExecutionCoordinator(
                repository, objectMapper, heartbeatInterval, false);
    }

    /**
     * Binds one integrity-verified claim to a closeable cooperative execution guard.
     *
     * @param job claimed durable job
     * @param lease exact queue owner/epoch/expiry fence
     * @param policy active cross-replica queue policy
     * @return monitored guard implementing the stability control contract
     */
    public ExecutionGuard monitor(
            TestSuiteStabilityJobRecord job,
            TestSuiteStabilityJobLease lease,
            TestSuiteStabilityQueuePolicy policy) {
        TestSuiteStabilityJobRecord claimed = Objects.requireNonNull(job, "job");
        TestSuiteStabilityJobLease fence = Objects.requireNonNull(lease, "lease");
        TestSuiteStabilityQueuePolicy activePolicy = Objects.requireNonNull(policy, "policy");
        requireBinding(claimed, fence);
        if (heartbeatInterval.multipliedBy(3)
                .compareTo(activePolicy.leaseDuration()) > 0) {
            throw new IllegalArgumentException(
                    "Stability job heartbeat must be at most one-third of its lease");
        }
        synchronized (lifecycleMonitor) {
            if (closed) {
                throw ControlException.closed();
            }
            ExecutionGuard guard = new ExecutionGuard(
                    claimed, fence, activePolicy,
                    TestSuiteStabilityExecutionIdentity.descriptor(objectMapper, claimed));
            activeGuards.add(guard);
            return guard;
        }
    }

    /** Stops future renewals and fail-closes every local guard. */
    @Override
    public void close() {
        List<ExecutionGuard> guards;
        synchronized (lifecycleMonitor) {
            if (closed) {
                return;
            }
            closed = true;
            guards = List.copyOf(activeGuards);
        }
        guards.forEach(ExecutionGuard::shutdown);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Exact local queue ownership guard used by one stability execution.
     *
     * <p>Closing a guard only cancels local heartbeat work. It never releases or mutates durable
     * queue state; the worker must complete, retry, or fail through the repository, while an
     * ambiguous owner is recovered by database expiry.</p>
     */
    public final class ExecutionGuard
            implements TestSuiteStabilityExecutionControl, AutoCloseable {

        private final TestSuiteStabilityJobRecord job;
        private final TestSuiteStabilityQueuePolicy policy;
        private final TestSuiteStabilityExecutionDescriptor expectedExecution;
        private final ScheduledFuture<?> heartbeat;
        private TestSuiteStabilityJobLease lease;
        private ControlException failure;
        private boolean bound;
        private boolean prepared;
        private boolean completed;
        private boolean guardClosed;

        private ExecutionGuard(
                TestSuiteStabilityJobRecord job,
                TestSuiteStabilityJobLease lease,
                TestSuiteStabilityQueuePolicy policy,
                TestSuiteStabilityExecutionDescriptor expectedExecution) {
            this.job = job;
            this.lease = lease;
            this.policy = policy;
            this.expectedExecution = expectedExecution;
            heartbeat = scheduler == null ? null : scheduler.scheduleWithFixedDelay(
                    this::heartbeat, heartbeatInterval.toMillis(),
                    heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public synchronized void executionStarted(
                TestSuiteStabilityExecutionDescriptor execution) {
            requireUsable();
            TestSuiteStabilityExecutionDescriptor actual =
                    Objects.requireNonNull(execution, "execution");
            if (bound || !expectedExecution.equals(actual)) {
                throw recordFailure(new ControlException(
                        ControlException.Reason.DESCRIPTOR_MISMATCH,
                        "RG.TEST.STABILITY_JOB_DESCRIPTOR_MISMATCH",
                        "Stability execution does not match its durable queue job"));
            }
            bound = true;
        }

        @Override
        public synchronized void checkpoint(Phase phase, int attempt) {
            validateCheckpoint(phase, attempt);
            requireBound();
            renewNow();
        }

        @Override
        public synchronized void prepareTerminal() {
            requireBound();
            if (prepared) {
                renewNow();
                return;
            }
            TestSuiteStabilityJobCompletionPreparation preparation;
            try {
                preparation = repository.prepareCompletion(lease, policy);
            } catch (RuntimeException unavailable) {
                throw recordFailure(ControlException.unavailable(unavailable));
            }
            if (preparation == null) {
                throw recordFailure(ControlException.unavailable(null));
            }
            if (preparation.decision()
                    != TestSuiteStabilityJobCompletionPreparation.Decision.PREPARED) {
                throw recordFailure(ControlException.from(preparation));
            }
            lease = preparation.lease();
            prepared = true;
        }

        /**
         * Synchronously renews and returns the exact fence for worker retry/failure mutation.
         *
         * @return current database-confirmed queue lease
         */
        public synchronized TestSuiteStabilityJobLease leaseForMutation() {
            requireBound();
            renewNow();
            return lease;
        }

        /**
         * Synchronously renews the irrevocable fence immediately before queue success.
         *
         * @return current database-confirmed {@code COMMITTING} lease
         */
        public synchronized TestSuiteStabilityJobLease leaseForCompletion() {
            requireBound();
            if (!prepared) {
                throw new IllegalStateException(
                        "Stability queue publication was not prepared");
            }
            renewNow();
            return lease;
        }

        /** @return whether cancellation/deadline publication was irrevocably linearized */
        public synchronized boolean publicationPrepared() {
            return prepared;
        }

        /** Marks successful queue consumption so no later local call can mutate this guard. */
        public synchronized void completed() {
            requireBound();
            if (!prepared) {
                throw new IllegalStateException(
                        "Unprepared stability queue execution cannot be completed");
            }
            completed = true;
            cancelHeartbeat();
        }

        @Override
        public synchronized void close() {
            if (guardClosed) {
                return;
            }
            guardClosed = true;
            cancelHeartbeat();
            activeGuards.remove(this);
        }

        private synchronized void heartbeat() {
            if (guardClosed || completed || failure != null) {
                return;
            }
            try {
                apply(repository.checkAndRenew(lease, policy));
            } catch (ControlException stopped) {
                // The exact terminal/fenced decision is retained for the execution thread.
            } catch (RuntimeException unavailable) {
                recordFailure(ControlException.unavailable(unavailable));
                log.warn("Suite-stability job heartbeat failed; local execution is fenced");
            }
        }

        private synchronized void shutdown() {
            if (failure == null && !completed) {
                recordFailure(ControlException.closed());
            }
            close();
        }

        private void renewNow() {
            requireUsable();
            try {
                apply(repository.checkAndRenew(lease, policy));
            } catch (ControlException stopped) {
                throw stopped;
            } catch (RuntimeException unavailable) {
                throw recordFailure(ControlException.unavailable(unavailable));
            }
        }

        private void apply(TestSuiteStabilityJobLeaseCheck check) {
            if (check == null) {
                throw recordFailure(ControlException.unavailable(null));
            }
            if (check.decision() != TestSuiteStabilityJobLeaseCheck.Decision.CONTINUE) {
                throw recordFailure(ControlException.from(check));
            }
            lease = check.lease();
        }

        private void requireBound() {
            requireUsable();
            if (!bound) {
                throw recordFailure(new ControlException(
                        ControlException.Reason.DESCRIPTOR_MISMATCH,
                        "RG.TEST.STABILITY_JOB_DESCRIPTOR_MISMATCH",
                        "Stability execution was not bound to its durable queue job"));
            }
        }

        private void requireUsable() {
            if (failure != null) {
                throw failure;
            }
            if (guardClosed || completed || closed) {
                throw recordFailure(ControlException.closed());
            }
        }

        private ControlException recordFailure(ControlException value) {
            if (failure == null) {
                failure = value;
            }
            return failure;
        }

        private void cancelHeartbeat() {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }

        /** @return immutable queue job bound to this guard */
        public TestSuiteStabilityJobRecord job() {
            return job;
        }
    }

    /** Fail-closed cooperative stop exposed to the worker without business payload. */
    public static final class ControlException extends RuntimeException {

        /** Closed control failure vocabulary used for worker classification. */
        public enum Reason {
            CANCELLED,
            DEADLINE_EXCEEDED,
            PARENT_COMPLETED,
            LEASE_LOST,
            STORE_UNAVAILABLE,
            DESCRIPTOR_MISMATCH,
            COORDINATOR_CLOSED
        }

        private final Reason reason;
        private final String failureCode;

        private ControlException(Reason reason, String failureCode, String message) {
            this(reason, failureCode, message, null);
        }

        private ControlException(
                Reason reason,
                String failureCode,
                String message,
                Throwable cause) {
            super(message, cause);
            this.reason = Objects.requireNonNull(reason, "reason");
            this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
        }

        /** @return exact machine-stable stop reason */
        public Reason reason() {
            return reason;
        }

        /** @return bounded payload-free queue diagnostic */
        public String failureCode() {
            return failureCode;
        }

        private static ControlException from(TestSuiteStabilityJobLeaseCheck check) {
            return new ControlException(reason(check.decision()), check.failureCode(),
                    "Suite-stability queue lease no longer permits execution");
        }

        private static ControlException from(
                TestSuiteStabilityJobCompletionPreparation preparation) {
            return new ControlException(reason(preparation.decision()),
                    preparation.failureCode(),
                    "Suite-stability queue no longer permits terminal publication");
        }

        private static ControlException unavailable(Throwable cause) {
            return new ControlException(Reason.STORE_UNAVAILABLE,
                    "RG.TEST.STABILITY_JOB_CONTROL_UNAVAILABLE",
                    "Suite-stability queue control is unavailable", cause);
        }

        private static ControlException closed() {
            return new ControlException(Reason.COORDINATOR_CLOSED,
                    "RG.TEST.STABILITY_JOB_COORDINATOR_CLOSED",
                    "Suite-stability queue coordinator is closed");
        }

        private static Reason reason(TestSuiteStabilityJobLeaseCheck.Decision decision) {
            return switch (decision) {
                case CANCELLED -> Reason.CANCELLED;
                case DEADLINE_EXCEEDED -> Reason.DEADLINE_EXCEEDED;
                case PARENT_COMPLETED -> Reason.PARENT_COMPLETED;
                case LEASE_LOST -> Reason.LEASE_LOST;
                case CONTINUE -> throw new IllegalArgumentException(
                        "Continue is not a control failure");
            };
        }

        private static Reason reason(
                TestSuiteStabilityJobCompletionPreparation.Decision decision) {
            return switch (decision) {
                case CANCELLED -> Reason.CANCELLED;
                case DEADLINE_EXCEEDED -> Reason.DEADLINE_EXCEEDED;
                case PARENT_COMPLETED -> Reason.PARENT_COMPLETED;
                case LEASE_LOST -> Reason.LEASE_LOST;
                case PREPARED -> throw new IllegalArgumentException(
                        "Prepared is not a control failure");
            };
        }
    }

    private static void requireBinding(
            TestSuiteStabilityJobRecord job,
            TestSuiteStabilityJobLease lease) {
        if (!job.jobId().equals(lease.jobId())
                || !job.tenantId().equals(lease.tenantId())
                || !job.environmentId().equals(lease.environmentId())
                || !job.requestFingerprint().equals(lease.requestFingerprint())
                || !Set.of(TestSuiteStabilityJobRecord.Status.RUNNING,
                TestSuiteStabilityJobRecord.Status.COMMITTING).contains(job.status())) {
            throw new IllegalArgumentException(
                    "Stability queue job and lease do not describe one exact claim");
        }
    }

    private static void validateCheckpoint(
            TestSuiteStabilityExecutionControl.Phase phase,
            int attempt) {
        TestSuiteStabilityExecutionControl.Phase checkpoint =
                Objects.requireNonNull(phase, "phase");
        boolean attemptBound = Set.of(
                TestSuiteStabilityExecutionControl.Phase.BEFORE_ATTEMPT,
                TestSuiteStabilityExecutionControl.Phase.AFTER_SOURCE_VERIFICATION)
                .contains(checkpoint);
        if (attemptBound != (attempt > 0)) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability control checkpoint coordinate");
        }
    }

    private static Duration boundedHeartbeat(Duration value) {
        Duration heartbeat = Objects.requireNonNull(value, "heartbeatInterval");
        if (heartbeat.toMillis() % 1_000 != 0
                || heartbeat.compareTo(Duration.ofSeconds(1)) < 0
                || heartbeat.compareTo(Duration.ofMinutes(20)) > 0) {
            throw new IllegalArgumentException(
                    "Stability job heartbeat must be a whole 1 to 1200 seconds");
        }
        return heartbeat;
    }
}
