package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonySnapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.CompletionDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.FailureDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.RecoveryAcquisitionCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyLeaseCoordinator.LeaseGuard;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyLeaseCoordinator.LeaseLostException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Crash-recoverable maker/checker coordinator around the pure bootstrap-root producer.
 *
 * <p>The service preflights before proposal persistence, calls opaque signers only after an
 * independent database approval and automatically renewed execution lease, and commits an outcome
 * only under the latest exact live fence. Descriptor and signature calls pass through a fixed
 * capacity, zero-queue wall-clock supervisor, so an uncooperative adapter cannot create unbounded
 * local threads or backlog. A crash or timeout after signer side effects but before outcome commit
 * is recovered by lease takeover and exact deterministic request replay. Signing adapters used
 * here must therefore implement the idempotency contract on
 * {@link ExternalSequenceAnchorBootstrapRootSigningAuthority#sign}.</p>
 *
 * <p>Actor and worker ids are assumed to have been authenticated by the embedding boundary. This
 * coordinator does not replace enterprise IAM, HSM/KMS custody, mTLS, publisher non-equivocation,
 * or externally retained audit evidence.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootCeremonyService implements AutoCloseable {

    private final ExternalSequenceAnchorBootstrapRootCeremonyProducer producer;
    private final ExternalSequenceAnchorBootstrapRootCeremonyJournal journal;
    private final ExternalSequenceAnchorBootstrapRootCeremonyLeaseCoordinator leaseCoordinator;
    private final ExternalSequenceAnchorBootstrapRootSignerCallSupervisor signerCallSupervisor;

    /**
     * Creates a coordinator that refuses non-durable journal implementations.
     *
     * @param producer side-effect-free preflight and opaque signing kernel
     * @param journal durable maker/checker and execution-fence authority
     */
    public ExternalSequenceAnchorBootstrapRootCeremonyService(
            ExternalSequenceAnchorBootstrapRootCeremonyProducer producer,
            ExternalSequenceAnchorBootstrapRootCeremonyJournal journal) {
        this(producer, journal,
                ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Policy.DEFAULT);
    }

    /**
     * Creates a coordinator with explicit bounded signer-call deadlines and capacity.
     *
     * @param producer side-effect-free preflight and opaque signing kernel
     * @param journal durable maker/checker and execution-fence authority
     * @param signerCallPolicy local resolver/descriptor/signature deadlines and concurrency ceiling
     */
    public ExternalSequenceAnchorBootstrapRootCeremonyService(
            ExternalSequenceAnchorBootstrapRootCeremonyProducer producer,
            ExternalSequenceAnchorBootstrapRootCeremonyJournal journal,
            ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Policy signerCallPolicy) {
        this.producer = requiredProducer(producer);
        this.journal = requiredJournal(journal);
        ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Policy requiredPolicy =
                Objects.requireNonNull(signerCallPolicy, "signerCallPolicy");
        this.leaseCoordinator =
                new ExternalSequenceAnchorBootstrapRootCeremonyLeaseCoordinator(this.journal);
        this.signerCallSupervisor =
                new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor(requiredPolicy);
    }

    ExternalSequenceAnchorBootstrapRootCeremonyService(
            ExternalSequenceAnchorBootstrapRootCeremonyProducer producer,
            ExternalSequenceAnchorBootstrapRootCeremonyJournal journal,
            ExternalSequenceAnchorBootstrapRootCeremonyLeaseCoordinator leaseCoordinator) {
        this(requiredProducer(producer), requiredJournal(journal), leaseCoordinator,
                new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor());
    }

    ExternalSequenceAnchorBootstrapRootCeremonyService(
            ExternalSequenceAnchorBootstrapRootCeremonyProducer producer,
            ExternalSequenceAnchorBootstrapRootCeremonyJournal journal,
            ExternalSequenceAnchorBootstrapRootCeremonyLeaseCoordinator leaseCoordinator,
            ExternalSequenceAnchorBootstrapRootSignerCallSupervisor signerCallSupervisor) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.leaseCoordinator = Objects.requireNonNull(leaseCoordinator, "leaseCoordinator");
        this.signerCallSupervisor = Objects.requireNonNull(
                signerCallSupervisor, "signerCallSupervisor");
        if (!journal.durable()) {
            throw new IllegalArgumentException(
                    "Bootstrap-root ceremony service requires a durable journal");
        }
    }

    /**
     * Preflights and idempotently proposes a sequence-one ceremony without requesting signatures.
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
        var preflight = producer.preflight(request, supervised(authorizingAuthorities),
                supervised(incomingAuthorities));
        return journal.propose(new CeremonyProposal(CeremonyProposal.SCHEMA_VERSION,
                request, null, preflight, makerId, proposalDurationSeconds));
    }

    /**
     * Preflights and idempotently proposes one successor without requesting signatures.
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
        var preflight = producer.preflight(currentBundle, request,
                supervised(authorizingAuthorities), supervised(incomingAuthorities));
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
     * @param leaseDurationSeconds database-clock auto-renewed lease from 3 through 300 seconds
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
        requireLeaseDuration(leaseDurationSeconds);
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
        return executeAcquired(acquisition.claim(), acquisition.snapshot(),
                leaseDurationSeconds, proposal ->
                new ExternalSequenceAnchorBootstrapRootAuthorityResolver.AuthoritySet(
                        authorities(authorizingAuthorities), authorities(incomingAuthorities)));
    }

    /**
     * Atomically acquires and executes the current root-set ceremony under durable recovery policy.
     *
     * <p>The database selects the active ceremony, enforces failed-attempt backoff and the automatic
     * attempt budget, and issues the execution fence in one transaction. Runtime signer resolution
     * starts only after that fence exists. An operator-driven {@link #execute(String, String, long,
     * List, List)} remains a separate explicit path and is not silently constrained by the
     * automatic recovery budget.</p>
     *
     * @param workerId stable pre-authenticated recovery worker identity
     * @param leaseDurationSeconds database-clock auto-renewed lease from 3 through 300 seconds
     * @param authorityResolver runtime adapter resolver for the exact approved proposal
     * @return bounded poll or execution result without provider diagnostics
     */
    public RecoveryExecutionResult recover(
            String workerId,
            long leaseDurationSeconds,
            ExternalSequenceAnchorBootstrapRootAuthorityResolver authorityResolver) {
        requireLeaseDuration(leaseDurationSeconds);
        ExternalSequenceAnchorBootstrapRootAuthorityResolver safeResolver =
                Objects.requireNonNull(authorityResolver, "authorityResolver");
        var acquisition = journal.acquireRecovery(new RecoveryAcquisitionCommand(
                RecoveryAcquisitionCommand.SCHEMA_VERSION, workerId, leaseDurationSeconds));
        if (acquisition.disposition()
                != ExternalSequenceAnchorBootstrapRootCeremonyJournal
                .RecoveryAcquisitionDisposition.ACQUIRED) {
            RecoveryStatus status = switch (acquisition.disposition()) {
                case NO_ACTIVE_CEREMONY -> RecoveryStatus.NO_ACTIVE_CEREMONY;
                case AWAITING_APPROVAL -> RecoveryStatus.AWAITING_APPROVAL;
                case BUSY -> RecoveryStatus.BUSY;
                case RETRY_DELAYED -> RecoveryStatus.RETRY_DELAYED;
                case ATTEMPT_LIMIT_REACHED -> RecoveryStatus.ATTEMPT_LIMIT_REACHED;
                case ACQUIRED -> throw new IllegalStateException(
                        "Unreachable recovery acquisition state");
            };
            return new RecoveryExecutionResult(status, null, acquisition.snapshot(),
                    acquisition.eligibleAt());
        }
        ExecutionResult execution = executeAcquired(acquisition.claim(), acquisition.snapshot(),
                leaseDurationSeconds, safeResolver);
        return new RecoveryExecutionResult(RecoveryStatus.EXECUTED, execution,
                execution.snapshot(), null);
    }

    private ExecutionResult executeAcquired(
            ExternalSequenceAnchorBootstrapRootCeremonyJournal.ExecutionClaim claim,
            CeremonySnapshot acquiredSnapshot,
            long leaseDurationSeconds,
            ExternalSequenceAnchorBootstrapRootAuthorityResolver authorityResolver) {

        LeaseGuard guard;
        try {
            guard = leaseCoordinator.monitor(claim, acquiredSnapshot,
                    leaseDurationSeconds);
        } catch (RuntimeException schedulingFailure) {
            return new ExecutionResult(ExecutionStatus.FENCE_REJECTED,
                    acquiredSnapshot, null);
        }

        try (guard) {
            CeremonyProposal proposal = claim.proposal();
            ExternalSequenceAnchorBootstrapRootAuthorityResolver.AuthoritySet authoritySet;
            try {
                authoritySet = Objects.requireNonNull(
                        signerCallSupervisor.resolve(authorityResolver, proposal),
                        "resolved authorities");
            } catch (RuntimeException resolutionFailure) {
                return failed(guard,
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                                .SIGNER_BINDING_INVALID);
            }
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority> supervisedAuthorizers =
                    supervised(authoritySet.authorizingAuthorities());
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority> supervisedIncoming =
                    supervised(authoritySet.incomingAuthorities());
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome outcome;
            try {
                var currentPreflight = proposal.currentBundle() == null
                        ? producer.preflight(proposal.request(), supervisedAuthorizers,
                        supervisedIncoming)
                        : producer.preflight(proposal.currentBundle(), proposal.request(),
                        supervisedAuthorizers, supervisedIncoming);
                if (!proposal.preflight().equals(currentPreflight)) {
                    return failed(guard,
                            ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                                    .SIGNER_BINDING_INVALID);
                }
                outcome = proposal.currentBundle() == null
                        ? producer.begin(proposal.request(), supervisedAuthorizers,
                        supervisedIncoming)
                        : producer.append(proposal.currentBundle(), proposal.request(),
                        supervisedAuthorizers, supervisedIncoming);
            } catch (ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyException
                     failure) {
                return failed(guard, failure.reason());
            }

            ExternalSequenceAnchorBootstrapRootCeremonyJournal.ExecutionClaim terminalClaim;
            try {
                terminalClaim = guard.freeze();
            } catch (LeaseLostException leaseLost) {
                return fenceRejected(leaseLost);
            }
            var completion = journal.complete(terminalClaim, outcome);
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

    /**
     * Returns current process-local signer call capacity and bounded failure counters.
     *
     * @return payload-free signer call supervisor projection
     */
    public ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Snapshot
            signerCallSnapshot() {
        return signerCallSupervisor.snapshot();
    }

    /** Stops heartbeat and signer supervisors without waiting for interrupt-ignoring adapters. */
    @Override
    public void close() {
        try {
            leaseCoordinator.close();
        } finally {
            signerCallSupervisor.close();
        }
    }

    private static void requireLeaseDuration(long leaseDurationSeconds) {
        if (leaseDurationSeconds < 3L || leaseDurationSeconds > 300L) {
            throw new IllegalArgumentException(
                    "Ceremony auto-heartbeat lease must be from three through 300 seconds");
        }
    }

    private static List<ExternalSequenceAnchorBootstrapRootSigningAuthority> authorities(
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority> authorities) {
        return authorities == null ? List.of() : authorities;
    }

    private List<ExternalSequenceAnchorBootstrapRootSigningAuthority> supervised(
            List<ExternalSequenceAnchorBootstrapRootSigningAuthority> authorities) {
        return authorities(authorities)
                .stream()
                .<ExternalSequenceAnchorBootstrapRootSigningAuthority>map(
                        SupervisedSigningAuthority::new)
                .toList();
    }

    private static ExternalSequenceAnchorBootstrapRootCeremonyProducer requiredProducer(
            ExternalSequenceAnchorBootstrapRootCeremonyProducer producer) {
        return Objects.requireNonNull(producer, "producer");
    }

    private static ExternalSequenceAnchorBootstrapRootCeremonyJournal requiredJournal(
            ExternalSequenceAnchorBootstrapRootCeremonyJournal journal) {
        ExternalSequenceAnchorBootstrapRootCeremonyJournal required =
                Objects.requireNonNull(journal, "journal");
        if (!required.durable()) {
            throw new IllegalArgumentException(
                    "Bootstrap-root ceremony service requires a durable journal");
        }
        return required;
    }

    private ExecutionResult failed(
            LeaseGuard guard,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason reason) {
        ExternalSequenceAnchorBootstrapRootCeremonyJournal.ExecutionClaim terminalClaim;
        try {
            terminalClaim = guard.freeze();
        } catch (LeaseLostException leaseLost) {
            return fenceRejected(leaseLost);
        }
        var release = journal.release(terminalClaim, reason);
        if (release.disposition() == FailureDisposition.FENCE_REJECTED) {
            return new ExecutionResult(ExecutionStatus.FENCE_REJECTED,
                    release.snapshot(), reason);
        }
        return new ExecutionResult(release.disposition() == FailureDisposition.EXPIRED
                ? ExecutionStatus.EXPIRED : ExecutionStatus.FAILED,
                release.snapshot(), reason);
    }

    private static ExecutionResult fenceRejected(LeaseLostException failure) {
        return new ExecutionResult(ExecutionStatus.FENCE_REJECTED,
                failure.lastVerifiedSnapshot(), null);
    }

    private final class SupervisedSigningAuthority
            implements ExternalSequenceAnchorBootstrapRootSigningAuthority {
        private final ExternalSequenceAnchorBootstrapRootSigningAuthority delegate;

        private SupervisedSigningAuthority(
                ExternalSequenceAnchorBootstrapRootSigningAuthority delegate) {
            this.delegate = Objects.requireNonNull(delegate, "signing authority");
        }

        @Override
        public Descriptor descriptor() {
            return signerCallSupervisor.descriptor(delegate);
        }

        @Override
        public SignatureResponse sign(SignatureRequest request) {
            return signerCallSupervisor.sign(delegate, request);
        }
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

    /** Bounded unattended recovery poll status. */
    public enum RecoveryStatus {
        /** A database-acquired ceremony attempt ran and has an execution result. */
        EXECUTED,

        /** This root-set scope has no non-terminal ceremony. */
        NO_ACTIVE_CEREMONY,

        /** The current proposal still awaits an independent checker. */
        AWAITING_APPROVAL,

        /** Another worker owns a live execution lease. */
        BUSY,

        /** The durable failed-attempt retry instant has not arrived. */
        RETRY_DELAYED,

        /** The durable automatic execution budget is exhausted. */
        ATTEMPT_LIMIT_REACHED
    }

    /**
     * Payload-free unattended recovery result.
     *
     * @param status poll classification
     * @param execution exact execution result only when an acquired attempt ran
     * @param snapshot current durable projection, absent only when no active ceremony exists
     * @param eligibleAt database instant for busy or delayed work, otherwise {@code null}
     */
    public record RecoveryExecutionResult(
            RecoveryStatus status,
            ExecutionResult execution,
            CeremonySnapshot snapshot,
            Instant eligibleAt) {

        /** Enforces status-dependent execution, snapshot, and timing presence. */
        public RecoveryExecutionResult {
            status = Objects.requireNonNull(status, "status");
            boolean executed = status == RecoveryStatus.EXECUTED;
            boolean absent = status == RecoveryStatus.NO_ACTIVE_CEREMONY;
            boolean timed = status == RecoveryStatus.BUSY
                    || status == RecoveryStatus.RETRY_DELAYED;
            if (executed != (execution != null)
                    || absent != (snapshot == null)
                    || timed != (eligibleAt != null)
                    || executed && !Objects.equals(snapshot, execution.snapshot())) {
                throw new IllegalArgumentException(
                        "Bootstrap-root ceremony recovery result is invalid");
            }
        }
    }

    /**
     * Bounded durable execution result.
     *
     * @param status coordinator outcome
     * @param snapshot current integrity-verified projection, or {@code null} when not found
     * @param failureReason bounded producer failure for failed attempts, or an optional producer
     *                      failure observed before a terminal journal fence rejection
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
