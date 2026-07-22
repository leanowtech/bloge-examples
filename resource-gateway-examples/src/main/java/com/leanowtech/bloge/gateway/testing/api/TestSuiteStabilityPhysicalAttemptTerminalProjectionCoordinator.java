package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Optional;

/**
 * Assembles and projects one exact provider-confirmed physical-attempt terminal source chain.
 *
 * <p>The coordinator reads the immutable reservation, latest positive terminal floor, exact
 * observation command, and original start command before deriving the content-addressed
 * projection command. Cancellation and success additionally pass through the proof resolver.
 * The terminal-projection journal remains the only authority allowed to revalidate the sources,
 * choose the queue winner, mutate the queue, append the projection, and release or transfer the
 * slot in one transaction.</p>
 *
 * <p>This class performs no provider I/O and retains no business payload. Missing or incoherent
 * facts are permanent source conflicts, not successful closure. A proof that has not become
 * authoritative is explicitly retryable, while storage/authority outages remain unavailable.
 * Journal integrity conflicts preserve their exact closed reason and are never collapsed into a
 * transient retry.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator {

    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityPhysicalAttemptRegistry attempts;
    private final TestSuiteStabilityPhysicalAttemptStartJournal starts;
    private final TestSuiteStabilityPhysicalAttemptObservationJournal observations;
    private final TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver proofs;
    private final TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal projections;

    /**
     * Creates an exact-source terminal projection coordinator.
     *
     * @param objectMapper canonical protocol mapper
     * @param attempts integrity-verifying physical-attempt registry
     * @param starts integrity-verifying retained start journal
     * @param observations integrity-verifying lifecycle observation journal
     * @param proofs cancellation and parent-success proof resolver
     * @param projections database-authoritative terminal projection transaction
     */
    public TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator(
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptRegistry attempts,
            TestSuiteStabilityPhysicalAttemptStartJournal starts,
            TestSuiteStabilityPhysicalAttemptObservationJournal observations,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver proofs,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal projections) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.starts = Objects.requireNonNull(starts, "starts");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.proofs = Objects.requireNonNull(proofs, "proofs");
        this.projections = Objects.requireNonNull(projections, "projections");
    }

    /**
     * Projects one exact retained terminal fact into its durable queue job.
     *
     * @param tenantId exact caller tenant scope
     * @param environmentId exact {@code test} or {@code staging} environment
     * @param attemptId exact content-addressed physical attempt
     * @param policy active queue retry and retention policy
     * @return payload-free projected, replayed, pending, conflict, or unavailable result
     */
    public Attempt project(
            String tenantId,
            String environmentId,
            String attemptId,
            TestSuiteStabilityQueuePolicy policy) {
        String exactTenant = required(tenantId, "tenantId");
        String exactEnvironment = required(environmentId, "environmentId");
        String exactAttempt = required(attemptId, "attemptId");
        TestSuiteStabilityQueuePolicy exactPolicy = Objects.requireNonNull(policy, "policy");

        SourceChain source;
        try {
            Optional<TestSuiteStabilityPhysicalAttemptRegistry.Entry> retainedAttempt =
                    requireOptional(attempts.find(
                            exactTenant, exactEnvironment, exactAttempt));
            if (retainedAttempt.isEmpty()) {
                return Attempt.permanent(FailureReason.SOURCE_NOT_RETAINED);
            }
            TestSuiteStabilityPhysicalAttemptRegistry.Entry reservation =
                    retainedAttempt.orElseThrow();
            if (!reservation.identity().tenantId().equals(exactTenant)
                    || !reservation.identity().environmentId().equals(exactEnvironment)
                    || !reservation.identity().attemptId().equals(exactAttempt)) {
                return Attempt.permanent(FailureReason.SOURCE_CHAIN_CONFLICT);
            }

            Optional<TestSuiteStabilityPhysicalAttemptObservationJournal.PositiveState>
                    retainedState = requireOptional(observations.latestPositive(
                    exactTenant, exactEnvironment, exactAttempt));
            if (retainedState.isEmpty()) {
                return Attempt.permanent(FailureReason.SOURCE_NOT_RETAINED);
            }
            TestSuiteStabilityPhysicalAttemptObservationJournal.PositiveState state =
                    retainedState.orElseThrow();
            if (!state.receipt().terminalConfirmed()) {
                return Attempt.permanent(FailureReason.TERMINAL_NOT_CONFIRMED);
            }

            Optional<TestSuiteStabilityPhysicalAttemptObservationJournal.Entry>
                    retainedObservation = requireOptional(observations.find(
                    exactTenant, exactEnvironment, state.observationCommandId()));
            Optional<TestSuiteStabilityPhysicalAttemptStartJournal.Entry> retainedStart =
                    requireOptional(starts.find(
                            exactTenant, exactEnvironment, state.startCommandId()));
            if (retainedObservation.isEmpty() || retainedStart.isEmpty()) {
                return Attempt.permanent(FailureReason.SOURCE_NOT_RETAINED);
            }
            source = new SourceChain(reservation, retainedStart.orElseThrow(),
                    retainedObservation.orElseThrow(), state);
        } catch (RuntimeException unavailable) {
            return Attempt.unavailable(FailureReason.SOURCE_UNAVAILABLE);
        }

        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition =
                source.state().receipt().terminalDisposition();
        ResolvedProof resolvedProof = resolveProof(source.reservation().identity(), disposition);
        if (resolvedProof.result().isPresent()) {
            return resolvedProof.result().orElseThrow();
        }

        TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand command;
        try {
            command = TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand.create(
                    objectMapper, source.reservation(), source.start(), source.observation(),
                    source.state(), resolvedProof.cancellation(),
                    resolvedProof.parentStabilityRunId(),
                    resolvedProof.parentEvidenceFingerprint());
        } catch (IllegalArgumentException sourceConflict) {
            return Attempt.permanent(FailureReason.SOURCE_CHAIN_CONFLICT);
        } catch (RuntimeException unavailable) {
            return Attempt.unavailable(FailureReason.SOURCE_UNAVAILABLE);
        }

        try {
            TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.Projection projected =
                    Objects.requireNonNull(
                            projections.project(command, exactPolicy), "terminal projection");
            if (!projected.entry().command().equals(command)) {
                return Attempt.unavailable(FailureReason.PROJECTION_CONTRACT_VIOLATION);
            }
            return projected.status()
                    == TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                    .ProjectionStatus.PROJECTED
                    ? Attempt.projected(projected)
                    : Attempt.replayed(projected);
        } catch (TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictException
                conflict) {
            return Attempt.projectionConflict(conflict.reason());
        } catch (RuntimeException unavailable) {
            return Attempt.unavailable(FailureReason.PROJECTION_UNAVAILABLE);
        }
    }

    private ResolvedProof resolveProof(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition) {
        if (disposition
                != TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.CANCELLED
                && disposition
                != TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.SUCCEEDED) {
            return ResolvedProof.none();
        }

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution resolution;
        try {
            resolution = Objects.requireNonNull(
                    proofs.resolve(identity, disposition), "terminal projection proof resolution");
        } catch (RuntimeException unavailable) {
            return ResolvedProof.result(
                    Attempt.unavailable(FailureReason.PROOF_RESOLUTION_UNAVAILABLE));
        }
        if (resolution.status()
                == TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver
                .ResolutionStatus.PENDING) {
            return ResolvedProof.result(Attempt.proofPending(resolution.reason()));
        }
        if (resolution.status()
                == TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver
                .ResolutionStatus.CONFLICT) {
            return ResolvedProof.result(Attempt.proofConflict(resolution.reason()));
        }

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Proof proof =
                resolution.proof().orElseThrow();
        boolean expectedCancellation = disposition
                == TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.CANCELLED;
        boolean matchingKind = expectedCancellation
                == (proof.kind()
                == TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver
                .ProofKind.CANCELLATION);
        if (!matchingKind) {
            return ResolvedProof.result(Attempt.proofConflict(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                            .PROOF_CONFLICT));
        }
        return new ResolvedProof(proof.cancellation(), proof.parentStabilityRunId(),
                proof.parentEvidenceFingerprint(), Optional.empty());
    }

    private static <T> Optional<T> requireOptional(Optional<T> value) {
        return Objects.requireNonNull(value, "journal lookup");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private record SourceChain(
            TestSuiteStabilityPhysicalAttemptRegistry.Entry reservation,
            TestSuiteStabilityPhysicalAttemptStartJournal.Entry start,
            TestSuiteStabilityPhysicalAttemptObservationJournal.Entry observation,
            TestSuiteStabilityPhysicalAttemptObservationJournal.PositiveState state) {
    }

    private record ResolvedProof(
            Optional<TestSuiteStabilityAttemptCancellationJournal.Entry> cancellation,
            String parentStabilityRunId,
            String parentEvidenceFingerprint,
            Optional<Attempt> result) {

        private static ResolvedProof none() {
            return new ResolvedProof(Optional.empty(), "", "", Optional.empty());
        }

        private static ResolvedProof result(Attempt result) {
            return new ResolvedProof(Optional.empty(), "", "", Optional.of(
                    Objects.requireNonNull(result, "result")));
        }
    }

    /** Closed terminal-projection coordinator disposition. */
    public enum Stage {
        /** A new terminal projection and queue transition committed. */
        PROJECTED,
        /** The exact content-addressed projection was already committed. */
        REPLAYED,
        /** Required cancellation or parent-success proof is not authoritative yet. */
        PROOF_PENDING,
        /** A source, proof, or transactional authority permanently rejected this attempt. */
        PERMANENT_CONFLICT,
        /** A storage, authority, or adapter contract boundary is temporarily unavailable. */
        UNAVAILABLE
    }

    /** Fixed-cardinality coordinator failure reason. */
    public enum FailureReason {
        /** No failure applies to a projected or replayed result. */
        NONE,
        /** A reservation, start, observation, or positive floor was not retained. */
        SOURCE_NOT_RETAINED,
        /** The latest positive floor is not a provider-confirmed terminal fact. */
        TERMINAL_NOT_CONFIRMED,
        /** Retained source facts do not form one exact immutable attempt chain. */
        SOURCE_CHAIN_CONFLICT,
        /** Required cancellation or parent-success proof is not authoritative yet. */
        PROOF_NOT_READY,
        /** Additional proof candidates are ambiguous, contradictory, or wrong-shaped. */
        PROOF_CONFLICT,
        /** One or more source journals could not be read reliably. */
        SOURCE_UNAVAILABLE,
        /** The additional proof authority could not answer reliably. */
        PROOF_RESOLUTION_UNAVAILABLE,
        /** The terminal projection transaction could not complete reliably. */
        PROJECTION_UNAVAILABLE,
        /** The terminal projection transaction permanently rejected the exact command. */
        PROJECTION_CONFLICT,
        /** The projection adapter returned a result that contradicted its command. */
        PROJECTION_CONTRACT_VIOLATION
    }

    /**
     * Payload-free result of one terminal projection attempt.
     *
     * @param stage exact closed coordinator outcome
     * @param failureReason fixed-cardinality local classification
     * @param proofReason present only for proof pending or conflict
     * @param projectionConflictReason present only for transactional projection conflict
     * @param projection present only for projected or replayed success
     */
    public record Attempt(
            Stage stage,
            FailureReason failureReason,
            Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason>
                    proofReason,
            Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictReason>
                    projectionConflictReason,
            Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.Projection>
                    projection) {

        /** Enforces one unambiguous fixed-cardinality result shape. */
        public Attempt {
            stage = Objects.requireNonNull(stage, "stage");
            failureReason = Objects.requireNonNull(failureReason, "failureReason");
            proofReason = Objects.requireNonNull(proofReason, "proofReason");
            projectionConflictReason = Objects.requireNonNull(
                    projectionConflictReason, "projectionConflictReason");
            projection = Objects.requireNonNull(projection, "projection");
            boolean success = stage == Stage.PROJECTED || stage == Stage.REPLAYED;
            boolean proofOutcome = stage == Stage.PROOF_PENDING
                    || failureReason == FailureReason.PROOF_CONFLICT;
            boolean projectionConflict = failureReason == FailureReason.PROJECTION_CONFLICT;
            if (success != (failureReason == FailureReason.NONE && projection.isPresent())
                    || stage == Stage.PROJECTED && projection.isPresent()
                    && projection.orElseThrow().status()
                    != TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                    .ProjectionStatus.PROJECTED
                    || stage == Stage.REPLAYED && projection.isPresent()
                    && projection.orElseThrow().status()
                    != TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                    .ProjectionStatus.REPLAYED
                    || success && (!proofReason.isEmpty()
                    || !projectionConflictReason.isEmpty())
                    || !success && projection.isPresent()
                    || proofOutcome != proofReason.isPresent()
                    || projectionConflict != projectionConflictReason.isPresent()
                    || stage == Stage.PROOF_PENDING
                    && failureReason != FailureReason.PROOF_NOT_READY
                    || stage == Stage.PERMANENT_CONFLICT
                    && failureReason != FailureReason.SOURCE_NOT_RETAINED
                    && failureReason != FailureReason.TERMINAL_NOT_CONFIRMED
                    && failureReason != FailureReason.SOURCE_CHAIN_CONFLICT
                    && failureReason != FailureReason.PROOF_CONFLICT
                    && failureReason != FailureReason.PROJECTION_CONFLICT
                    || stage == Stage.UNAVAILABLE
                    && failureReason != FailureReason.SOURCE_UNAVAILABLE
                    && failureReason != FailureReason.PROOF_RESOLUTION_UNAVAILABLE
                    && failureReason != FailureReason.PROJECTION_UNAVAILABLE
                    && failureReason != FailureReason.PROJECTION_CONTRACT_VIOLATION) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal-projection attempt");
            }
        }

        private static Attempt projected(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.Projection value) {
            return success(Stage.PROJECTED, value);
        }

        private static Attempt replayed(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.Projection value) {
            return success(Stage.REPLAYED, value);
        }

        private static Attempt success(
                Stage stage,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.Projection value) {
            return new Attempt(stage, FailureReason.NONE, Optional.empty(), Optional.empty(),
                    Optional.of(Objects.requireNonNull(value, "projection")));
        }

        private static Attempt permanent(FailureReason reason) {
            return new Attempt(Stage.PERMANENT_CONFLICT, reason, Optional.empty(),
                    Optional.empty(), Optional.empty());
        }

        private static Attempt proofPending(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason reason) {
            return new Attempt(Stage.PROOF_PENDING, FailureReason.PROOF_NOT_READY,
                    Optional.of(reason), Optional.empty(), Optional.empty());
        }

        private static Attempt proofConflict(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason reason) {
            return new Attempt(Stage.PERMANENT_CONFLICT, FailureReason.PROOF_CONFLICT,
                    Optional.of(reason), Optional.empty(), Optional.empty());
        }

        private static Attempt projectionConflict(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictReason
                        reason) {
            return new Attempt(Stage.PERMANENT_CONFLICT, FailureReason.PROJECTION_CONFLICT,
                    Optional.empty(), Optional.of(reason), Optional.empty());
        }

        private static Attempt unavailable(FailureReason reason) {
            return new Attempt(Stage.UNAVAILABLE, reason, Optional.empty(), Optional.empty(),
                    Optional.empty());
        }
    }
}
