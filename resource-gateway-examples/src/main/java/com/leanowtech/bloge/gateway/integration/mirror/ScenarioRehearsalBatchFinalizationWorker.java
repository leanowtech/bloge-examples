package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Performs one bounded asynchronous terminal-evidence finalization turn.
 *
 * <p>The worker first acquires a database-fenced immutable intent, then performs child evidence
 * verification and both KMS signatures without a database transaction. The final database call
 * atomically publishes evidence, retention, the terminal job, and lifecycle audit. Response loss
 * is safe because every takeover reuses the intent's signing request id and first-claim database
 * time.</p>
 */
public final class ScenarioRehearsalBatchFinalizationWorker {
    private final ScenarioRehearsalBatchRepository repository;
    private final ScenarioRehearsalBatchEvidencePublisher publisher;
    private final ScenarioRehearsalBatchFinalizationPolicy policy;

    /** Creates one worker over the durable finalization outbox. */
    public ScenarioRehearsalBatchFinalizationWorker(
            ScenarioRehearsalBatchRepository repository,
            ScenarioRehearsalBatchEvidencePublisher publisher,
            ScenarioRehearsalBatchFinalizationPolicy policy) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.publisher = Objects.requireNonNull(
                publisher, "publisher");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Claims, prepares, and commits at most one terminal evidence bundle.
     *
     * @param region exact server-owned regional partition
     * @param environmentId exact non-production environment partition
     * @param ownerId opaque stable worker-lane identity
     * @return payload-free bounded turn result
     */
    public Turn runOnce(
            String region,
            String environmentId,
            String ownerId) {
        ScenarioRehearsalBatchRepository.FinalizationAcquisition
                acquisition;
        try {
            acquisition = repository.claimFinalization(
                    region,
                    environmentId,
                    ownerId,
                    policy);
        } catch (RuntimeException unavailable) {
            return Turn.unavailable();
        }
        if (acquisition.outcome()
                != ScenarioRehearsalBatchRepository
                .FinalizationClaimOutcome.ACQUIRED) {
            return Turn.observed(acquisition);
        }
        ScenarioRehearsalBatchRepository.FinalizationClaim claim =
                acquisition.claim();
        try {
            ScenarioRehearsalBatchRepository.FinalizationIntent intent =
                    claim.intent();
            ScenarioRehearsalBatchEvidencePublisher.PreparedFinalization
                    prepared = publisher.prepare(
                    intent.request(),
                    intent.manifest(),
                    intent.terminalJob(),
                    intent.items(),
                    intent.retainUntil(),
                    claim.signingStartedAt(),
                    intent.signingRequestId());
            ScenarioRehearsalBatchJob terminal =
                    repository.completeFinalization(
                            claim, prepared);
            return new Turn(
                    Disposition.FINALIZED,
                    terminal.jobId(),
                    claim.attemptCount(),
                    "",
                    terminal,
                    null);
        } catch (ScenarioRehearsalBatchFinalizationException
                classified) {
            return release(claim, classified.reason());
        } catch (IllegalArgumentException invalidMaterial) {
            return release(
                    claim,
                    ScenarioRehearsalBatchFinalizationException
                            .Reason.MATERIAL_INVALID);
        } catch (RuntimeException unavailable) {
            return release(
                    claim,
                    ScenarioRehearsalBatchFinalizationException
                            .Reason.CONTROL_UNAVAILABLE);
        }
    }

    private Turn release(
            ScenarioRehearsalBatchRepository.FinalizationClaim claim,
            ScenarioRehearsalBatchFinalizationException.Reason reason) {
        try {
            ScenarioRehearsalBatchRepository.FinalizationSnapshot
                    snapshot = repository.releaseFinalization(
                    claim, reason, policy);
            Disposition disposition = snapshot.state()
                    == ScenarioRehearsalBatchRepository
                    .FinalizationState.QUARANTINED
                    ? Disposition.QUARANTINED
                    : Disposition.RETRY_SCHEDULED;
            return new Turn(
                    disposition,
                    snapshot.jobId(),
                    snapshot.attemptCount(),
                    reason.failureCode(),
                    null,
                    snapshot);
        } catch (RuntimeException staleOrUnavailable) {
            ScenarioRehearsalBatchRepository.FinalizationSnapshot
                    observed = observe(claim);
            if (observed != null
                    && (observed.state()
                    != ScenarioRehearsalBatchRepository
                    .FinalizationState.SIGNING
                    || observed.leaseEpoch()
                    != claim.leaseEpoch()
                    || !observed.leaseOwner().equals(
                    claim.ownerId()))) {
                return new Turn(
                        Disposition.LEASE_LOST,
                        claim.intent().terminalJob().jobId(),
                        claim.attemptCount(),
                        reason.failureCode(),
                        null,
                        observed);
            }
            return new Turn(
                    Disposition.CONTROL_UNAVAILABLE,
                    claim.intent().terminalJob().jobId(),
                    claim.attemptCount(),
                    ScenarioRehearsalBatchFinalizationException
                            .Reason.CONTROL_UNAVAILABLE
                            .failureCode(),
                    null,
                    observed);
        }
    }

    private ScenarioRehearsalBatchRepository.FinalizationSnapshot
    observe(
            ScenarioRehearsalBatchRepository.FinalizationClaim claim) {
        try {
            return repository.findFinalization(
                    claim.intent().terminalJob().scope(),
                    claim.intent().terminalJob().jobId())
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** Payload-free outcome of one bounded finalization turn. */
    public record Turn(
            Disposition disposition,
            String jobId,
            int attemptCount,
            String failureCode,
            ScenarioRehearsalBatchJob terminalJob,
            ScenarioRehearsalBatchRepository.FinalizationSnapshot
                    snapshot
    ) {
        /** Enforces a closed result shape without evidence or provider diagnostics. */
        public Turn {
            disposition = Objects.requireNonNull(
                    disposition, "disposition");
            jobId = normalized(jobId);
            failureCode = normalized(failureCode);
            if (attemptCount < 0
                    || disposition == Disposition.NO_WORK
                    && !jobId.isBlank()
                    || jobId.isBlank()
                    && disposition != Disposition.NO_WORK
                    && disposition
                    != Disposition.CONTROL_UNAVAILABLE
                    || disposition == Disposition.FINALIZED
                    != (terminalJob != null)
                    || terminalJob != null
                    && (!terminalJob.status().terminal()
                    || !terminalJob.jobId().equals(jobId))
                    || snapshot != null
                    && !snapshot.jobId().equals(jobId)) {
                throw new IllegalArgumentException(
                        "Scenario batch finalization turn is inconsistent");
            }
        }

        private static Turn unavailable() {
            return new Turn(
                    Disposition.CONTROL_UNAVAILABLE,
                    "",
                    0,
                    ScenarioRehearsalBatchFinalizationException
                            .Reason.CONTROL_UNAVAILABLE
                            .failureCode(),
                    null,
                    null);
        }

        private static Turn observed(
                ScenarioRehearsalBatchRepository
                        .FinalizationAcquisition acquisition) {
            ScenarioRehearsalBatchRepository.FinalizationSnapshot
                    snapshot = acquisition.snapshot();
            Disposition disposition = switch (
                    acquisition.outcome()) {
                case NO_WORK -> Disposition.NO_WORK;
                case BUSY -> Disposition.BUSY;
                case RETRY_DELAYED -> Disposition.RETRY_DELAYED;
                case QUARANTINED -> Disposition.QUARANTINED;
                case ACQUIRED -> throw new IllegalArgumentException(
                        "An acquired finalization requires execution");
            };
            return new Turn(
                    disposition,
                    snapshot == null ? "" : snapshot.jobId(),
                    snapshot == null ? 0
                            : snapshot.attemptCount(),
                    snapshot == null ? ""
                            : snapshot.lastFailureCode(),
                    null,
                    snapshot);
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /** Closed worker-turn vocabulary. */
    public enum Disposition {
        NO_WORK,
        BUSY,
        RETRY_DELAYED,
        FINALIZED,
        RETRY_SCHEDULED,
        QUARANTINED,
        LEASE_LOST,
        CONTROL_UNAVAILABLE
    }
}
