package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonySnapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.CompletionDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.FailureDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Crash-recoverable maker/checker coordinator around the pure bootstrap-root producer.
 *
 * <p>The service preflights before proposal persistence, calls opaque signers only after an
 * independent database approval and execution lease, and commits an outcome only under the exact
 * live fence. A crash after signer side effects but before outcome commit is recovered by lease
 * takeover and exact deterministic request replay. Signing adapters used here must therefore
 * implement the idempotency contract on
 * {@link ExternalSequenceAnchorBootstrapRootSigningAuthority#sign}.</p>
 *
 * <p>Actor and worker ids are assumed to have been authenticated by the embedding boundary. This
 * coordinator does not replace enterprise IAM, HSM/KMS custody, mTLS, publisher non-equivocation,
 * or externally retained audit evidence.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootCeremonyService {

    private final ExternalSequenceAnchorBootstrapRootCeremonyProducer producer;
    private final ExternalSequenceAnchorBootstrapRootCeremonyJournal journal;

    /**
     * Creates a coordinator that refuses non-durable journal implementations.
     *
     * @param producer side-effect-free preflight and opaque signing kernel
     * @param journal durable maker/checker and execution-fence authority
     */
    public ExternalSequenceAnchorBootstrapRootCeremonyService(
            ExternalSequenceAnchorBootstrapRootCeremonyProducer producer,
            ExternalSequenceAnchorBootstrapRootCeremonyJournal journal) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.journal = Objects.requireNonNull(journal, "journal");
        if (!journal.durable()) {
            throw new IllegalArgumentException(
                    "Bootstrap-root ceremony service requires a durable journal");
        }
    }

    /**
     * Preflights and idempotently proposes a sequence-one ceremony without calling signers.
     *
     * @param request immutable rotation command
     * @param makerId pre-authenticated maker identity
     * @param proposalDurationSeconds database-clock checker window
     * @param authorizingAuthorities configured genesis-root authorities
     * @param incomingAuthorities configured successor-root authorities
     * @return durable proposal disposition and integrity-verified snapshot
     */
    public ProposalResult propose(
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest request,
            String makerId,
            long proposalDurationSeconds,
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority>
                    authorizingAuthorities,
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority> incomingAuthorities) {
        var preflight = producer.preflight(request, authorizingAuthorities,
                incomingAuthorities);
        return journal.propose(new CeremonyProposal(CeremonyProposal.SCHEMA_VERSION,
                request, null, preflight, makerId, proposalDurationSeconds));
    }

    /**
     * Preflights and idempotently proposes one successor without calling signers.
     *
     * @param currentBundle complete untrusted current chain
     * @param request immutable rotation command
     * @param makerId pre-authenticated maker identity
     * @param proposalDurationSeconds database-clock checker window
     * @param authorizingAuthorities configured current-root authorities
     * @param incomingAuthorities configured successor-root authorities
     * @return durable proposal disposition and integrity-verified snapshot
     */
    public ProposalResult propose(
            ExternalSequenceAnchorBootstrapRootBundle currentBundle,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest request,
            String makerId,
            long proposalDurationSeconds,
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority>
                    authorizingAuthorities,
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority> incomingAuthorities) {
        var preflight = producer.preflight(currentBundle, request, authorizingAuthorities,
                incomingAuthorities);
        return journal.propose(new CeremonyProposal(CeremonyProposal.SCHEMA_VERSION,
                request, currentBundle, preflight, makerId, proposalDurationSeconds));
    }

    /**
     * Delegates one pre-authenticated independent checker command to the durable journal.
     *
     * @param command bounded checker identity, idempotency key, and approval window
     * @return durable approval disposition and current projection
     */
    public ApprovalResult approve(ApprovalCommand command) {
        return journal.approve(Objects.requireNonNull(command, "command"));
    }

    /**
     * Acquires, executes, and conditionally commits one approved ceremony attempt.
     *
     * @param ceremonyId exact durable proposal identity
     * @param workerId stable pre-authenticated worker identity
     * @param leaseDurationSeconds database-clock lease from 1 through 300 seconds
     * @param authorizingAuthorities runtime old-root authorities
     * @param incomingAuthorities runtime successor-root authorities
     * @return bounded execution disposition without provider diagnostics or partial artifacts
     */
    public ExecutionResult execute(
            String ceremonyId,
            String workerId,
            long leaseDurationSeconds,
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority>
                    authorizingAuthorities,
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority> incomingAuthorities) {
        var acquisition = journal.acquire(new AcquisitionCommand(
                AcquisitionCommand.SCHEMA_VERSION, ceremonyId, workerId,
                leaseDurationSeconds));
        if (acquisition.disposition()
                != ExternalSequenceAnchorBootstrapRootCeremonyJournal
                .AcquisitionDisposition.ACQUIRED) {
            return switch (acquisition.disposition()) {
                case NOT_FOUND -> new ExecutionResult(ExecutionStatus.NOT_FOUND, null, null);
                case NOT_APPROVED -> new ExecutionResult(ExecutionStatus.NOT_APPROVED,
                        acquisition.snapshot(), null);
                case BUSY -> new ExecutionResult(ExecutionStatus.BUSY,
                        acquisition.snapshot(), null);
                case EXPIRED -> new ExecutionResult(ExecutionStatus.EXPIRED,
                        acquisition.snapshot(), null);
                case PRODUCED -> new ExecutionResult(ExecutionStatus.IDEMPOTENT_REPLAY,
                        acquisition.snapshot(), null);
                case ACQUIRED -> throw new IllegalStateException("Unreachable acquisition state");
            };
        }

        var claim = acquisition.claim();
        CeremonyProposal proposal = claim.proposal();
        ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome outcome;
        try {
            var currentPreflight = proposal.currentBundle() == null
                    ? producer.preflight(proposal.request(), authorizingAuthorities,
                    incomingAuthorities)
                    : producer.preflight(proposal.currentBundle(), proposal.request(),
                    authorizingAuthorities, incomingAuthorities);
            if (!proposal.preflight().equals(currentPreflight)) {
                return failed(claim,
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                                .SIGNER_BINDING_INVALID);
            }
            outcome = proposal.currentBundle() == null
                    ? producer.begin(proposal.request(), authorizingAuthorities,
                    incomingAuthorities)
                    : producer.append(proposal.currentBundle(), proposal.request(),
                    authorizingAuthorities, incomingAuthorities);
        } catch (ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyException failure) {
            return failed(claim, failure.reason());
        }

        var completion = journal.complete(claim, outcome);
        return switch (completion.disposition()) {
            case PRODUCED -> new ExecutionResult(ExecutionStatus.PRODUCED,
                    completion.snapshot(), null);
            case IDEMPOTENT_REPLAY -> new ExecutionResult(ExecutionStatus.IDEMPOTENT_REPLAY,
                    completion.snapshot(), null);
            case FENCE_REJECTED -> new ExecutionResult(ExecutionStatus.FENCE_REJECTED,
                    completion.snapshot(), null);
            case OUTCOME_CONFLICT -> throw new IllegalStateException(
                    "Bootstrap-root ceremony terminal outcome conflicts with replay");
        };
    }

    /**
     * Returns the current integrity-verified durable workflow projection.
     *
     * @param ceremonyId exact ceremony identity
     * @return current projection, or empty when the identity has never been proposed
     */
    public Optional<CeremonySnapshot> snapshot(String ceremonyId) {
        return journal.snapshot(ceremonyId);
    }

    private ExecutionResult failed(
            ExternalSequenceAnchorBootstrapRootCeremonyJournal.ExecutionClaim claim,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason reason) {
        var release = journal.release(claim, reason);
        if (release.disposition() == FailureDisposition.FENCE_REJECTED) {
            return new ExecutionResult(ExecutionStatus.FENCE_REJECTED,
                    release.snapshot(), reason);
        }
        return new ExecutionResult(release.disposition() == FailureDisposition.EXPIRED
                ? ExecutionStatus.EXPIRED : ExecutionStatus.FAILED,
                release.snapshot(), reason);
    }

    /** Bounded coordinator result status. */
    public enum ExecutionStatus {
        /** Complete self-verified outcome was committed by this call. */
        PRODUCED,

        /** Complete outcome had already been committed. */
        IDEMPOTENT_REPLAY,

        /** Ceremony still awaits independent checker approval. */
        NOT_APPROVED,

        /** Another worker holds a live execution lease. */
        BUSY,

        /** Approval elapsed before a complete outcome commit. */
        EXPIRED,

        /** Ceremony identity does not exist. */
        NOT_FOUND,

        /** Producer failed and the live approval was reopened for retry. */
        FAILED,

        /** Attempt lost its execution fence and no generated artifact was exposed. */
        FENCE_REJECTED
    }

    /**
     * Bounded durable execution result.
     *
     * @param status coordinator outcome
     * @param snapshot current integrity-verified projection, or {@code null} when not found
     * @param failureReason bounded producer failure for failed or fence-lost attempts
     */
    public record ExecutionResult(
            ExecutionStatus status,
            CeremonySnapshot snapshot,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason failureReason) {

        /** Enforces status-dependent snapshot and failure presence. */
        public ExecutionResult {
            status = Objects.requireNonNull(status, "status");
            if ((status == ExecutionStatus.NOT_FOUND) != (snapshot == null)
                    || status == ExecutionStatus.FAILED && failureReason == null
                    || status != ExecutionStatus.FAILED
                    && status != ExecutionStatus.FENCE_REJECTED
                    && failureReason != null) {
                throw new IllegalArgumentException(
                        "Bootstrap-root ceremony execution result is invalid");
            }
        }
    }
}
