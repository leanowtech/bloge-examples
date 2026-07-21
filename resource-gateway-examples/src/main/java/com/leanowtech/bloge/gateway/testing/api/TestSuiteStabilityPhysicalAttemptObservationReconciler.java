package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded worker step for reconciling one possibly orphaned physical attempt.
 *
 * <p>The durable reconciliation journal claims one target before this service inspects the retained
 * start and latest positive observation. A retained terminal floor closes the target with no
 * provider I/O. Otherwise the service creates a fresh challenge-bound command, resolves the exact
 * provider/deployment authority, and delegates the only provider call ordering to
 * {@link TestSuiteStabilityPhysicalAttemptObservationCoordinator}.</p>
 *
 * <p>Every exit attempts a fenced durable completion. Descriptor failures and local saturation are
 * backpressure, while observation timeout/failure remains remote uncertainty. Neither becomes
 * non-start or terminal evidence. Permanent integrity/binding conflicts quarantine only the
 * reconciliation target; this service never mutates the stability queue, slot permit,
 * cancellation journal, or natural execution terminal.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptObservationReconciler {

    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal work;
    private final TestSuiteStabilityPhysicalAttemptStartJournal starts;
    private final TestSuiteStabilityPhysicalAttemptObservationJournal observations;
    private final TestSuiteStabilityPhysicalAttemptObservationCoordinator coordinator;
    private final AuthorityResolver authorities;
    private final Policy policy;
    private final SecureRandom secureRandom;

    /**
     * Creates one reconciler with a process-local cryptographic challenge source.
     *
     * @param objectMapper canonical protocol mapper
     * @param work database-clock target/lease/retry authority
     * @param starts integrity-verifying retained start journal
     * @param observations durable lifecycle observation journal
     * @param coordinator bounded provider-call ordering boundary
     * @param authorities exact provider/deployment authority resolver
     * @param policy observation command window and lease safety policy
     */
    public TestSuiteStabilityPhysicalAttemptObservationReconciler(
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal work,
            TestSuiteStabilityPhysicalAttemptStartJournal starts,
            TestSuiteStabilityPhysicalAttemptObservationJournal observations,
            TestSuiteStabilityPhysicalAttemptObservationCoordinator coordinator,
            AuthorityResolver authorities,
            Policy policy) {
        this(objectMapper, work, starts, observations, coordinator, authorities, policy,
                new SecureRandom());
    }

    /**
     * Creates one reconciler with an explicit challenge source for deterministic protocol tests.
     *
     * @param objectMapper canonical protocol mapper
     * @param work database-clock target/lease/retry authority
     * @param starts integrity-verifying retained start journal
     * @param observations durable lifecycle observation journal
     * @param coordinator bounded provider-call ordering boundary
     * @param authorities exact provider/deployment authority resolver
     * @param policy observation command window and lease safety policy
     * @param secureRandom cryptographically strong 32-byte challenge source
     */
    TestSuiteStabilityPhysicalAttemptObservationReconciler(
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal work,
            TestSuiteStabilityPhysicalAttemptStartJournal starts,
            TestSuiteStabilityPhysicalAttemptObservationJournal observations,
            TestSuiteStabilityPhysicalAttemptObservationCoordinator coordinator,
            AuthorityResolver authorities,
            Policy policy,
            SecureRandom secureRandom) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.work = Objects.requireNonNull(work, "work");
        this.starts = Objects.requireNonNull(starts, "starts");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.authorities = Objects.requireNonNull(authorities, "authorities");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        Duration requiredLease = safePlus(
                policy.confirmationWindow(), policy.leaseSafetyMargin());
        if (work.policy().leaseDuration().compareTo(requiredLease) < 0) {
            throw new IllegalArgumentException(
                    "Physical-attempt reconciliation lease cannot contain observation window");
        }
    }

    /**
     * Claims and advances at most one reconciliation target.
     *
     * @param ownerId stable replica/worker identity
     * @return identity-free closed step outcome
     */
    public Attempt reconcileNext(String ownerId) {
        Optional<TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Claim>
                claimed = work.claimNext(ownerId);
        if (claimed.isEmpty()) {
            return Attempt.noWork();
        }
        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Claim claim =
                claimed.orElseThrow();
        TestSuiteStabilityPhysicalAttemptStartCommand startCommand = claim.startCommand();
        TestSuiteStabilityPhysicalAttemptIdentity identity = startCommand.identity();

        TestSuiteStabilityPhysicalAttemptStartJournal.Entry start;
        try {
            start = starts.find(identity.tenantId(), identity.environmentId(),
                    startCommand.commandId()).orElse(null);
        } catch (RuntimeException unavailable) {
            return finish(claim, localBackpressure());
        }
        if (start == null || !start.command().equals(startCommand)) {
            return finish(claim, permanentFailure());
        }

        TestSuiteStabilityPhysicalAttemptObservationJournal.PositiveState floor;
        try {
            floor = observations.latestPositive(
                    identity.tenantId(), identity.environmentId(), identity.attemptId())
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            return finish(claim, localBackpressure());
        }
        if (floor != null && floor.receipt().terminalConfirmed()) {
            return finish(claim, newResult(
                    TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                            .ResultKind.RETAINED_TERMINAL,
                    floor.observationCommandId()));
        }

        String expectedProcess = expectedProcess(start, floor);
        long minimumRevision = floor == null ? 0 : floor.receipt().attemptRevision();
        TestSuiteStabilityPhysicalAttemptObservationCommand command =
                TestSuiteStabilityPhysicalAttemptObservationCommand.create(
                        objectMapper, startCommand, expectedProcess, minimumRevision,
                        claim.lease().claimedAt(),
                        claim.lease().claimedAt().plus(policy.confirmationWindow()),
                        challenge());
        TestSuiteStabilityPhysicalAttemptObservationAuthority authority;
        try {
            authority = Objects.requireNonNull(
                    authorities.resolve(identity.providerId(), identity.deploymentId()),
                    "physical-attempt observation authority");
        } catch (RuntimeException unavailable) {
            return finish(claim, localBackpressure());
        }

        try {
            TestSuiteStabilityPhysicalAttemptObservationJournal.Acceptance accepted =
                    coordinator.observe(authority, command);
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt = accepted.entry()
                    .attestation().orElseThrow().receipt();
            TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.ResultKind kind;
            if (receipt.terminalConfirmed()) {
                kind = TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .ResultKind.POSITIVE_TERMINAL;
            } else if (accepted.entry().status()
                    == TestSuiteStabilityPhysicalAttemptObservationJournal.Status.POSITIVE) {
                kind = TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .ResultKind.POSITIVE_ACTIVE;
            } else {
                kind = TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .ResultKind.NON_CONFIRMING;
            }
            return finish(claim, newResult(kind, command.commandId()));
        } catch (TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.InvocationException
                unavailable) {
            boolean providerMayHaveObserved = unavailable.callType()
                    == TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.CallType
                    .OBSERVATION
                    && unavailable.disposition()
                    != TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Disposition
                    .SATURATED
                    && unavailable.disposition()
                    != TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Disposition
                    .CLOSED;
            return finish(claim, providerMayHaveObserved
                    ? newResult(
                    TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                            .ResultKind.REMOTE_UNCERTAIN,
                    command.commandId())
                    : localBackpressure());
        } catch (TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictException conflict) {
            return finish(claim, resultFor(command, conflict.reason()));
        } catch (RuntimeException invalid) {
            return finish(claim, permanentFailure(command.commandId()));
        }
    }

    private Attempt finish(
            TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Claim claim,
            TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result result) {
        try {
            var completion = work.complete(claim.lease(), result);
            Stage stage = switch (completion.targetStatus()) {
                case READY -> Stage.RESCHEDULED;
                case TERMINAL -> Stage.TERMINAL;
                case QUARANTINED -> Stage.QUARANTINED;
                case LEASED -> throw new IllegalStateException(
                        "Reconciliation completion retained a live lease");
            };
            return new Attempt(stage, completion.automaticAttempts(),
                    completion.consecutiveUncertainty());
        } catch (TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                .ConflictException conflict) {
            if (conflict.reason()
                    == TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                    .ConflictException.Reason.LEASE_LOST) {
                return new Attempt(Stage.LEASE_LOST, claim.automaticAttempts(),
                        claim.consecutiveUncertainty());
            }
            throw conflict;
        }
    }

    private TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result resultFor(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason reason) {
        return switch (reason) {
            case IDEMPOTENCY_CONFLICT, START_COMMAND_NOT_RETAINED -> permanentFailure();
            case PROCESS_IDENTITY_CONFLICT, TERMINAL_STATE_CONFLICT ->
                    permanentFailure(command.commandId());
            case OBSERVATION_PRECEDES_PREPARATION, PROVIDER_SEQUENCE_ROLLBACK,
                    ATTEMPT_REVISION_ROLLBACK, LIFECYCLE_STATE_ROLLBACK -> newResult(
                    TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.ResultKind
                            .REMOTE_UNCERTAIN,
                    command.commandId());
            case OBSERVATION_IN_FLIGHT, COMMAND_NOT_PREPARED, COMMAND_EXPIRED,
                    PROVIDER_INCOMPATIBLE, STATE_FENCE_CHANGED -> localBackpressure();
        };
    }

    private static String expectedProcess(
            TestSuiteStabilityPhysicalAttemptStartJournal.Entry start,
            TestSuiteStabilityPhysicalAttemptObservationJournal.PositiveState floor) {
        if (floor != null && floor.receipt().processIdentityConfirmed()) {
            return floor.receipt().processIdentityFingerprint();
        }
        if (start.status() == TestSuiteStabilityPhysicalAttemptStartJournal.Status.CONFIRMED) {
            return start.attestation().orElseThrow().receipt()
                    .processIdentityFingerprint();
        }
        return "";
    }

    private String challenge() {
        byte[] value = new byte[32];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result
            newResult(
                    TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.ResultKind
                            kind,
                    String commandId) {
        return new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                kind, commandId);
    }

    private static TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result
            localBackpressure() {
        return newResult(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.ResultKind
                        .LOCAL_BACKPRESSURE,
                "");
    }

    private static TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result
            permanentFailure() {
        return permanentFailure("");
    }

    private static TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result
            permanentFailure(String commandId) {
        return newResult(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.ResultKind
                        .PERMANENT_FAILURE,
                commandId);
    }

    private static Duration safePlus(Duration first, Duration second) {
        try {
            return first.plus(second);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "Physical-attempt reconciliation duration overflow");
        }
    }

    /** Resolves one authority only for the exact retained provider/deployment generation. */
    @FunctionalInterface
    public interface AuthorityResolver {
        /**
         * Resolves a bounded observation adapter for an exact provider generation.
         *
         * @param providerId immutable retained provider identity
         * @param deploymentId immutable retained deployment generation
         * @return matching lifecycle observation authority
         */
        TestSuiteStabilityPhysicalAttemptObservationAuthority resolve(
                String providerId, String deploymentId);
    }

    /**
     * Reconciler command-window policy.
     *
     * @param confirmationWindow provider confirmation window from 100 ms through five minutes
     * @param leaseSafetyMargin lease headroom from 100 ms through one minute
     */
    public record Policy(Duration confirmationWindow, Duration leaseSafetyMargin) {
        /** Enforces millisecond-exact protocol bounds. */
        public Policy {
            confirmationWindow = exact(confirmationWindow, "confirmationWindow");
            leaseSafetyMargin = exact(leaseSafetyMargin, "leaseSafetyMargin");
            if (confirmationWindow.compareTo(Duration.ofMillis(100)) < 0
                    || confirmationWindow.compareTo(Duration.ofMinutes(5)) > 0
                    || leaseSafetyMargin.compareTo(Duration.ofMillis(100)) < 0
                    || leaseSafetyMargin.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt observation reconciler policy");
            }
        }

        private static Duration exact(Duration value, String field) {
            Duration required = Objects.requireNonNull(value, field);
            if (required.isNegative() || required.isZero()
                    || required.toNanos() % 1_000_000 != 0) {
                throw new IllegalArgumentException(field + " must be positive millisecond exact");
            }
            return required;
        }
    }

    /** Identity-free reconciler step disposition suitable for fixed-cardinality metrics. */
    public enum Stage {
        /** No due target existed after bounded source discovery. */
        NO_WORK,
        /** Target returned to database-clock retry or steady polling. */
        RESCHEDULED,
        /** A verified provider terminal state closed the reconciliation target. */
        TERMINAL,
        /** Policy or a permanent conflict stopped automatic reconciliation. */
        QUARANTINED,
        /** Provider evidence may be durable, but this worker lost the target completion lease. */
        LEASE_LOST
    }

    /**
     * Payload-free result of one bounded worker step.
     *
     * @param stage exact closed outcome
     * @param automaticAttempts provider-facing attempts retained after this step
     * @param consecutiveUncertainty current uncertainty streak
     */
    public record Attempt(
            Stage stage, long automaticAttempts, int consecutiveUncertainty) {
        /** Enforces non-negative fixed-cardinality observations. */
        public Attempt {
            stage = Objects.requireNonNull(stage, "stage");
            if (automaticAttempts < 0 || consecutiveUncertainty < 0
                    || stage == Stage.NO_WORK
                    && (automaticAttempts != 0 || consecutiveUncertainty != 0)) {
                throw new IllegalArgumentException("Invalid reconciliation worker result");
            }
        }

        private static Attempt noWork() {
            return new Attempt(Stage.NO_WORK, 0, 0);
        }
    }
}
