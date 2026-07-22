package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Bounded one-shot worker for one durable physical-attempt terminal projection.
 *
 * <p>The worker claims at most one database-due item, subtracts claim latency and a mandatory
 * completion reserve from the durable lease, invokes the exact-source coordinator through a
 * fixed-capacity zero-queue supervisor, and submits the result through the same lease fence.
 * Coordinator execution never occurs inside a database transaction.</p>
 *
 * <p>A timeout does not prove that projection did not commit. The worker records a retryable
 * unavailable result while its lease is live; if the lingering call later commits, a future
 * attempt converges through the content-addressed projection replay. Caller interruption is
 * different: the interrupt flag is preserved and no further database I/O is attempted, leaving
 * the lease for normal expiry and takeover.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker {

    private static final Pattern OWNER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    private final TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal works;
    private final TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator coordinator;
    private final TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor supervisor;
    private final TestSuiteStabilityQueuePolicy queuePolicy;
    private final Policy policy;
    private final String ownerId;
    private final LongSupplier monotonicNanos;
    private final long leaseMillis;

    /**
     * Creates a one-shot worker using the process monotonic clock.
     *
     * @param works database-authoritative projection-work journal
     * @param coordinator exact-source terminal projection coordinator
     * @param supervisor fixed-capacity local coordinator boundary
     * @param queuePolicy active queue retry and retention policy
     * @param policy completion-reserve policy
     * @param ownerId stable replica and worker identity
     */
    public TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal works,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator coordinator,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor supervisor,
            TestSuiteStabilityQueuePolicy queuePolicy,
            Policy policy,
            String ownerId) {
        this(works, coordinator, supervisor, queuePolicy, policy, ownerId, System::nanoTime);
    }

    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal works,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator coordinator,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor supervisor,
            TestSuiteStabilityQueuePolicy queuePolicy,
            Policy policy,
            String ownerId,
            LongSupplier monotonicNanos) {
        this.works = Objects.requireNonNull(works, "works");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.queuePolicy = Objects.requireNonNull(queuePolicy, "queuePolicy");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.ownerId = normalized(ownerId);
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        if (!OWNER.matcher(this.ownerId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid physical-attempt terminal projection worker owner id");
        }
        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Policy workPolicy =
                Objects.requireNonNull(works.policy(), "work policy");
        leaseMillis = workPolicy.leaseDuration().toMillis();
        long reservedMillis = Math.addExact(
                supervisor.policy().maximumProjectionTimeout().toMillis(),
                policy.completionReserve().toMillis());
        if (reservedMillis >= leaseMillis) {
            throw new IllegalArgumentException(
                    "Terminal projection call deadline and completion reserve must be shorter "
                            + "than the work lease");
        }
    }

    /**
     * Claims and handles at most one due terminal-projection item.
     *
     * <p>The method blocks only on journal claim/completion and one supervised coordinator call.
     * JDBC deadlines remain the journal datasource owner's responsibility. This worker never
     * loops, sleeps, or waits for local call capacity.</p>
     *
     * @return payload-free execution and local-boundary disposition
     */
    public Execution processNext() {
        long claimStarted = monotonicNanos.getAsLong();
        Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Claim> selected;
        try {
            selected = Objects.requireNonNull(
                    works.claimNext(ownerId), "terminal projection work claim");
        } catch (RuntimeException unavailable) {
            return Execution.workUnavailable(LocalDisposition.NONE);
        }
        if (selected.isEmpty()) {
            return Execution.noWork();
        }

        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Claim claim =
                selected.orElseThrow();
        long availableCallMillis = availableCallMillis(claimStarted);
        if (availableCallMillis < 100L) {
            return completeUnavailable(claim, LocalDisposition.BUDGET_EXHAUSTED);
        }

        Duration callTimeout = Duration.ofMillis(Math.min(
                availableCallMillis,
                supervisor.policy().maximumProjectionTimeout().toMillis()));
        TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt attempt;
        try {
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Trigger trigger =
                    claim.trigger();
            attempt = supervisor.project(coordinator, trigger.tenantId(),
                    trigger.environmentId(), trigger.attemptId(), queuePolicy, callTimeout);
        } catch (TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor
                .InvocationException failure) {
            LocalDisposition local = switch (failure.disposition()) {
                case TIMED_OUT -> LocalDisposition.TIMED_OUT;
                case SATURATED -> LocalDisposition.SATURATED;
                case CLOSED -> LocalDisposition.CLOSED;
                case CALLER_INTERRUPTED -> LocalDisposition.CALLER_INTERRUPTED;
                case UNAVAILABLE -> LocalDisposition.UNAVAILABLE;
            };
            if (local == LocalDisposition.CALLER_INTERRUPTED) {
                return Execution.callerInterrupted();
            }
            return completeUnavailable(claim, local);
        }
        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result result;
        try {
            result = TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result.from(
                    attempt);
        } catch (RuntimeException contractViolation) {
            return completeUnavailable(claim, LocalDisposition.UNAVAILABLE,
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                            .PROJECTION_CONTRACT_VIOLATION);
        }
        return complete(claim.lease(), result, LocalDisposition.NONE);
    }

    private long availableCallMillis(long claimStarted) {
        long elapsedNanos = monotonicNanos.getAsLong() - claimStarted;
        long safeElapsedNanos = Math.max(0L, elapsedNanos);
        long elapsedMillis = safeElapsedNanos / NANOS_PER_MILLISECOND;
        if (safeElapsedNanos % NANOS_PER_MILLISECOND != 0L) {
            elapsedMillis = Math.addExact(elapsedMillis, 1L);
        }
        return leaseMillis - elapsedMillis - policy.completionReserve().toMillis();
    }

    private Execution completeUnavailable(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Claim claim,
            LocalDisposition local) {
        return completeUnavailable(claim, local,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROJECTION_UNAVAILABLE);
    }

    private Execution completeUnavailable(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Claim claim,
            LocalDisposition local,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason reason) {
        return complete(claim.lease(),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result
                        .temporarilyUnavailable(reason),
                local);
    }

    private Execution complete(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Lease lease,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result result,
            LocalDisposition local) {
        try {
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Completion completion =
                    Objects.requireNonNull(
                            works.complete(lease, result), "terminal projection work completion");
            return Execution.completed(completion.status(), local);
        } catch (TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                .ConflictException conflict) {
            return conflict.reason()
                    == TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                    .ConflictReason.LEASE_LOST
                    ? Execution.leaseLost(local)
                    : Execution.workConflict(local, conflict.reason());
        } catch (RuntimeException unavailable) {
            return Execution.workUnavailable(local);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    /** Local pre-coordinator or supervised-call observation. */
    public enum LocalDisposition {
        /** The coordinator returned an authoritative attempt. */
        NONE,
        /** Lease budget remaining after claim was too small to start safely. */
        BUDGET_EXHAUSTED,
        /** Coordinator call exceeded its caller-owned deadline. */
        TIMED_OUT,
        /** Every fixed local call slot was occupied. */
        SATURATED,
        /** Call supervisor was closed. */
        CLOSED,
        /** Calling thread was interrupted and no completion I/O was attempted. */
        CALLER_INTERRUPTED,
        /** Coordinator failed or returned no result before its deadline. */
        UNAVAILABLE
    }

    /** Closed one-shot worker outcome. */
    public enum Outcome {
        /** No database-due work was available. */
        NO_WORK,
        /** A new or replayed exact projection completed durable work. */
        COMPLETED,
        /** Proof or infrastructure availability rescheduled durable work. */
        RESCHEDULED,
        /** A permanent source, proof, or projection conflict quarantined work. */
        QUARANTINED,
        /** The exact lease and result had already been committed. */
        REPLAYED,
        /** Calling thread was interrupted; lease expiry remains the recovery path. */
        CALLER_INTERRUPTED,
        /** Another worker owns or completed a newer lease generation. */
        LEASE_LOST,
        /** Durable work rejected a non-lease invariant. */
        WORK_CONFLICT,
        /** Durable work claim or completion authority was unavailable. */
        WORK_UNAVAILABLE
    }

    /**
     * Mandatory reserve for fenced completion after a local call returns.
     *
     * @param completionReserve reserved lease time from 100 ms through one minute
     */
    public record Policy(Duration completionReserve) {

        /** Default five-second fenced-completion reserve. */
        public static final Policy DEFAULT = new Policy(Duration.ofSeconds(5));

        /** Enforces a positive millisecond-exact reserve. */
        public Policy {
            completionReserve = Objects.requireNonNull(
                    completionReserve, "completionReserve");
            if (completionReserve.compareTo(Duration.ofMillis(100)) < 0
                    || completionReserve.compareTo(Duration.ofMinutes(1)) > 0
                    || !completionReserve.equals(
                    Duration.ofMillis(completionReserve.toMillis()))) {
                throw new IllegalArgumentException(
                        "Physical-attempt terminal projection completion reserve is invalid");
            }
        }
    }

    /**
     * Payload-free result of one bounded worker invocation.
     *
     * @param schemaVersion exact execution-result generation
     * @param outcome durable or caller-control outcome
     * @param localDisposition local budget or supervisor result
     * @param conflictReason present only for a durable work conflict
     */
    public record Execution(
            String schemaVersion,
            Outcome outcome,
            LocalDisposition localDisposition,
            Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                    .ConflictReason> conflictReason) {

        /** Exact one-shot terminal-projection worker result generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionWorkerExecution.v1";

        /** Enforces one unambiguous payload-free execution shape. */
        public Execution {
            schemaVersion = normalized(schemaVersion);
            outcome = Objects.requireNonNull(outcome, "outcome");
            localDisposition = Objects.requireNonNull(localDisposition, "localDisposition");
            conflictReason = Objects.requireNonNull(conflictReason, "conflictReason");
            boolean interrupted = outcome == Outcome.CALLER_INTERRUPTED;
            boolean conflict = outcome == Outcome.LEASE_LOST
                    || outcome == Outcome.WORK_CONFLICT;
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || interrupted
                    != (localDisposition == LocalDisposition.CALLER_INTERRUPTED)
                    || conflict != conflictReason.isPresent()
                    || outcome == Outcome.LEASE_LOST && conflictReason.orElse(null)
                    != TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                    .ConflictReason.LEASE_LOST
                    || outcome == Outcome.WORK_CONFLICT && conflictReason.orElse(null)
                    == TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                    .ConflictReason.LEASE_LOST
                    || outcome == Outcome.NO_WORK
                    && localDisposition != LocalDisposition.NONE
                    || outcome == Outcome.COMPLETED && localDisposition
                    != LocalDisposition.NONE
                    || outcome == Outcome.QUARANTINED && localDisposition
                    != LocalDisposition.NONE
                    || outcome == Outcome.REPLAYED && localDisposition
                    != LocalDisposition.NONE) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal projection worker execution");
            }
        }

        private static Execution noWork() {
            return execution(Outcome.NO_WORK, LocalDisposition.NONE, Optional.empty());
        }

        private static Execution completed(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.CompletionStatus
                        status,
                LocalDisposition local) {
            Outcome outcome = switch (Objects.requireNonNull(status, "status")) {
                case COMPLETED -> Outcome.COMPLETED;
                case RESCHEDULED -> Outcome.RESCHEDULED;
                case QUARANTINED -> Outcome.QUARANTINED;
                case REPLAYED -> Outcome.REPLAYED;
            };
            return execution(outcome, local, Optional.empty());
        }

        private static Execution callerInterrupted() {
            return execution(Outcome.CALLER_INTERRUPTED,
                    LocalDisposition.CALLER_INTERRUPTED, Optional.empty());
        }

        private static Execution leaseLost(LocalDisposition local) {
            return execution(Outcome.LEASE_LOST, local, Optional.of(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                            .ConflictReason.LEASE_LOST));
        }

        private static Execution workConflict(
                LocalDisposition local,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.ConflictReason
                        reason) {
            return execution(Outcome.WORK_CONFLICT, local, Optional.of(reason));
        }

        private static Execution workUnavailable(LocalDisposition local) {
            return execution(Outcome.WORK_UNAVAILABLE, local, Optional.empty());
        }

        private static Execution execution(
                Outcome outcome,
                LocalDisposition local,
                Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .ConflictReason> conflict) {
            return new Execution(SCHEMA_VERSION, outcome, local, conflict);
        }
    }
}
