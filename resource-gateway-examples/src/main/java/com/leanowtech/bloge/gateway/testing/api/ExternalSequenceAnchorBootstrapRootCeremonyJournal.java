package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Durable maker/checker journal for bootstrap-root signing ceremonies.
 *
 * <p>The journal is the authority for proposal idempotency, independent approval, execution lease
 * fencing, crash recovery, and atomic publication of a produced bundle. Signer calls deliberately
 * remain outside its database transactions. A recovery worker may repeat only the immutable
 * proposal, whose signer requests are content-addressed and must be idempotently replayed by every
 * durable signing adapter.</p>
 */
public interface ExternalSequenceAnchorBootstrapRootCeremonyJournal {

    /**
     * Creates or exactly replays one immutable proposal.
     *
     * @param proposal public-only proposal that already passed producer preflight
     * @return durable disposition and integrity-verified current snapshot
     */
    ProposalResult propose(CeremonyProposal proposal);

    /**
     * Approves one pending proposal as a checker distinct from its maker.
     *
     * @param command idempotent independent-checker command
     * @return approval disposition and current snapshot, or not-found result
     */
    ApprovalResult approve(ApprovalCommand command);

    /**
     * Acquires or takes over one approved execution using a database-clock lease fence.
     *
     * @param command worker identity and bounded lease request
     * @return acquisition disposition, exact claim when acquired, and current snapshot
     */
    Acquisition acquire(AcquisitionCommand command);

    /**
     * Atomically commits one complete producer outcome under the exact live execution fence.
     *
     * @param claim exact claim returned by a successful acquisition
     * @param outcome complete locally verified producer outcome
     * @return terminal commit disposition and current snapshot
     */
    CompletionResult complete(
            ExecutionClaim claim,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome outcome);

    /**
     * Records a bounded producer failure and releases a live exact fence for retry.
     *
     * <p>When the exact claim observes that approval has already elapsed, the journal persists the
     * deterministic expiry but does not attribute a failure reported after the fence deadline.</p>
     *
     * @param claim exact claim returned by a successful acquisition
     * @param reason bounded producer failure without provider diagnostics
     * @return release, expiry, or fence-rejection disposition and current snapshot
     */
    FailureResult release(
            ExecutionClaim claim,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason reason);

    /**
     * Returns the integrity-verified current journal projection when it exists.
     *
     * @param ceremonyId exact ceremony identity
     * @return current snapshot, or empty when no proposal has used the identity
     */
    Optional<CeremonySnapshot> snapshot(String ceremonyId);

    /**
     * Reports whether proposals and outcomes survive process restart.
     *
     * @return {@code true} only for durable implementations
     */
    boolean durable();

    /** Durable ceremony lifecycle. */
    enum State {
        /** Immutable proposal awaits an independent checker. */
        PENDING_APPROVAL,

        /** Live checker approval exists and execution may be acquired. */
        APPROVED,

        /** One database-fenced worker owns the current execution attempt. */
        EXECUTING,

        /** Complete self-verified bundle and bounded signer attempts are committed. */
        PRODUCED,

        /** Maker proposal elapsed before any checker approval was committed. */
        PROPOSAL_EXPIRED,

        /** Checker approval elapsed before a complete outcome was committed. */
        APPROVAL_EXPIRED
    }

    /** Proposal command disposition. */
    enum ProposalDisposition {
        /** New immutable proposal was committed. */
        CREATED,

        /** Exact proposal already exists. */
        IDEMPOTENT_REPLAY,

        /** Ceremony identity was reused with changed immutable intent. */
        IDEMPOTENCY_CONFLICT,

        /** Proposal does not exactly extend the latest produced journal head. */
        CHAIN_CONFLICT,

        /** Another non-terminal ceremony already owns this root-set workflow. */
        ACTIVE_CEREMONY_EXISTS
    }

    /** Checker command disposition. */
    enum ApprovalDisposition {
        /** Independent checker approval was committed. */
        APPROVED,

        /** Exact checker command already exists. */
        IDEMPOTENT_REPLAY,

        /** Ceremony does not exist. */
        NOT_FOUND,

        /** Maker attempted to approve its own proposal. */
        SELF_APPROVAL,

        /** Approval request identity was reused with changed intent. */
        IDEMPOTENCY_CONFLICT,

        /** Ceremony is no longer pending approval. */
        NOT_PENDING
    }

    /** Execution acquisition disposition. */
    enum AcquisitionDisposition {
        /** Caller owns a new execution fence. */
        ACQUIRED,

        /** Ceremony does not exist. */
        NOT_FOUND,

        /** Ceremony still awaits checker approval. */
        NOT_APPROVED,

        /** Another live worker fence exists. */
        BUSY,

        /** Approval elapsed before acquisition or recovery. */
        EXPIRED,

        /** Ceremony already has an immutable complete outcome. */
        PRODUCED
    }

    /** Outcome commit disposition. */
    enum CompletionDisposition {
        /** Complete outcome was committed. */
        PRODUCED,

        /** Exact outcome was already committed. */
        IDEMPOTENT_REPLAY,

        /** Worker lease is missing, stale, expired, or superseded. */
        FENCE_REJECTED,

        /** A different outcome was presented after terminal commit. */
        OUTCOME_CONFLICT
    }

    /** Failed-attempt release disposition. */
    enum FailureDisposition {
        /** Failure was recorded and the live approval was reopened for retry. */
        RELEASED,

        /** Exact claim observed and persisted approval expiry without post-fence attribution. */
        EXPIRED,

        /** Worker lease is missing, stale, or superseded. */
        FENCE_REJECTED
    }

    /**
     * Immutable public-only proposal approved by maker/checker workflow.
     *
     * @param schemaVersion proposal protocol generation
     * @param request exact rotation command
     * @param currentBundle complete current chain, or {@code null} for sequence one
     * @param preflight side-effect-free material and signer-cohort closure
     * @param makerId verified maker identity
     * @param proposalDurationSeconds database-clock checker window from 1 through 86,400
     */
    record CeremonyProposal(
            String schemaVersion,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest request,
            ExternalSequenceAnchorBootstrapRootBundle currentBundle,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyPreflight preflight,
            String makerId,
            long proposalDurationSeconds) {

        /** Current immutable durable proposal protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootCeremonyProposal.v1";

        /** Enforces exact request, chain-head, preflight, and maker binding. */
        public CeremonyProposal {
            schemaVersion = normalized(schemaVersion);
            request = Objects.requireNonNull(request, "request");
            preflight = Objects.requireNonNull(preflight, "preflight");
            makerId = identifier(makerId, "makerId");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !request.ceremonyId().equals(preflight.ceremonyId())
                    || proposalDurationSeconds < 1 || proposalDurationSeconds > 86_400) {
                throw new IllegalArgumentException(
                        "External bootstrap-root ceremony proposal is invalid");
            }
            if (currentBundle == null && preflight.sequence() != 1L) {
                throw new IllegalArgumentException(
                        "Sequence-one ceremony proposal requires sequence one preflight");
            }
            if (currentBundle != null) {
                ExternalSequenceAnchorBootstrapRootTransition head =
                        currentBundle.transitions().getLast();
                if (preflight.sequence() != head.material().sequence() + 1L
                        || !request.expectedPreviousMaterialFingerprint().equals(
                        currentBundle.headMaterialFingerprint())) {
                    throw new IllegalArgumentException(
                            "Successor ceremony proposal does not bind its current head");
                }
            }
        }

        /**
         * Returns the exact ceremony identity.
         *
         * @return request ceremony identity
         */
        public String ceremonyId() {
            return request.ceremonyId();
        }
    }

    /**
     * Idempotent independent checker command.
     *
     * @param schemaVersion approval command generation
     * @param ceremonyId exact proposal identity
     * @param approvalRequestId caller-stable checker idempotency identity
     * @param checkerId verified checker identity
     * @param approvalDurationSeconds database-clock approval lifetime from 1 through 86,400
     */
    record ApprovalCommand(
            String schemaVersion,
            String ceremonyId,
            String approvalRequestId,
            String checkerId,
            long approvalDurationSeconds) {

        /** Current checker command protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootCeremonyApprovalCommand.v1";

        /** Enforces bounded checker command identity and duration. */
        public ApprovalCommand {
            schemaVersion = normalized(schemaVersion);
            ceremonyId = identifier(ceremonyId, "ceremonyId");
            approvalRequestId = identifier(approvalRequestId, "approvalRequestId");
            checkerId = identifier(checkerId, "checkerId");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || approvalDurationSeconds < 1 || approvalDurationSeconds > 86_400) {
                throw new IllegalArgumentException(
                        "External bootstrap-root ceremony approval command is invalid");
            }
        }
    }

    /**
     * Database-fenced execution acquisition command.
     *
     * @param schemaVersion acquisition protocol generation
     * @param ceremonyId exact approved ceremony identity
     * @param workerId stable worker identity
     * @param leaseDurationSeconds database-clock lease from 1 through 300 seconds
     */
    record AcquisitionCommand(
            String schemaVersion,
            String ceremonyId,
            String workerId,
            long leaseDurationSeconds) {

        /** Current execution acquisition protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootCeremonyAcquisition.v1";

        /** Enforces bounded execution owner and lease duration. */
        public AcquisitionCommand {
            schemaVersion = normalized(schemaVersion);
            ceremonyId = identifier(ceremonyId, "ceremonyId");
            workerId = identifier(workerId, "workerId");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || leaseDurationSeconds < 1 || leaseDurationSeconds > 300) {
                throw new IllegalArgumentException(
                        "External bootstrap-root ceremony acquisition is invalid");
            }
        }
    }

    /**
     * Exact execution fencing token returned only after database acquisition.
     *
     * @param schemaVersion claim protocol generation
     * @param ceremonyId exact ceremony identity
     * @param workerId owning worker identity
     * @param claimVersion monotonically increasing takeover fence
     * @param claimUntil exclusive database-clock lease deadline
     * @param proposal immutable approved ceremony input
     */
    record ExecutionClaim(
            String schemaVersion,
            String ceremonyId,
            String workerId,
            long claimVersion,
            Instant claimUntil,
            CeremonyProposal proposal) {

        /** Current execution-claim protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootCeremonyClaim.v1";

        /** Enforces complete claim and proposal identity. */
        public ExecutionClaim {
            schemaVersion = normalized(schemaVersion);
            ceremonyId = identifier(ceremonyId, "ceremonyId");
            workerId = identifier(workerId, "workerId");
            claimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
            proposal = Objects.requireNonNull(proposal, "proposal");
            if (!SCHEMA_VERSION.equals(schemaVersion) || claimVersion < 1
                    || !ceremonyId.equals(proposal.ceremonyId())) {
                throw new IllegalArgumentException(
                        "External bootstrap-root ceremony claim is invalid");
            }
        }
    }

    /**
     * Integrity-verified durable workflow projection.
     *
     * @param schemaVersion snapshot protocol generation
     * @param state current durable state
     * @param proposal immutable proposal
     * @param proposalFingerprint canonical proposal identity
     * @param submittedAt database-clock proposal commit time
     * @param proposalUntil exclusive database-clock checker deadline
     * @param approvalRequestId checker request identity, or empty before approval
     * @param approvalFingerprint checker intent identity, or empty before approval
     * @param checkerId checker identity, or empty before approval
     * @param approvedAt database-clock approval time, or {@code null}
     * @param approvalUntil exclusive approval deadline, or {@code null}
     * @param claimOwner latest worker owner, or empty before execution
     * @param claimVersion latest monotonically increasing execution fence
     * @param claimUntil latest exclusive execution deadline, or {@code null}
     * @param attemptCount number of acquired execution attempts
     * @param lastFailure latest bounded producer failure, or {@code null}
     * @param lastFailedAt latest failure observation time, or {@code null}
     * @param outcome complete producer outcome, or {@code null}
     * @param outcomeFingerprint canonical outcome identity, or empty before completion
     * @param completedAt database-clock terminal commit time, or {@code null}
     * @param updatedAt latest database-clock mutation time
     * @param recordFingerprint independent whole-record integrity identity
     */
    record CeremonySnapshot(
            String schemaVersion,
            State state,
            CeremonyProposal proposal,
            String proposalFingerprint,
            Instant submittedAt,
            Instant proposalUntil,
            String approvalRequestId,
            String approvalFingerprint,
            String checkerId,
            Instant approvedAt,
            Instant approvalUntil,
            String claimOwner,
            long claimVersion,
            Instant claimUntil,
            long attemptCount,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason lastFailure,
            Instant lastFailedAt,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome outcome,
            String outcomeFingerprint,
            Instant completedAt,
            Instant updatedAt,
            String recordFingerprint) {

        /** Current durable journal projection protocol. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootCeremonySnapshot.v1";

        /** Enforces canonical state-dependent projection shape. */
        public CeremonySnapshot {
            schemaVersion = normalized(schemaVersion);
            state = Objects.requireNonNull(state, "state");
            proposal = Objects.requireNonNull(proposal, "proposal");
            proposalFingerprint = fingerprint(proposalFingerprint, "proposalFingerprint");
            submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
            proposalUntil = Objects.requireNonNull(proposalUntil, "proposalUntil");
            approvalRequestId = normalized(approvalRequestId);
            approvalFingerprint = normalized(approvalFingerprint);
            checkerId = normalized(checkerId);
            claimOwner = normalized(claimOwner);
            outcomeFingerprint = normalized(outcomeFingerprint);
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            recordFingerprint = fingerprint(recordFingerprint, "recordFingerprint");
            boolean approved = state == State.APPROVED || state == State.EXECUTING
                    || state == State.PRODUCED || state == State.APPROVAL_EXPIRED;
            boolean executing = state == State.EXECUTING;
            boolean produced = state == State.PRODUCED;
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || attemptCount < 0 || claimVersion < 0
                    || !proposalUntil.isAfter(submittedAt)
                    || approved && (!validIdentifier(approvalRequestId)
                    || !validFingerprint(approvalFingerprint)
                    || !validIdentifier(checkerId)
                    || approvedAt == null || approvalUntil == null)
                    || !approved && (!approvalRequestId.isEmpty()
                    || !approvalFingerprint.isEmpty() || !checkerId.isEmpty()
                    || approvedAt != null || approvalUntil != null)
                    || executing && (!validIdentifier(claimOwner)
                    || claimVersion < 1 || claimUntil == null)
                    || produced && (outcome == null
                    || !validFingerprint(outcomeFingerprint) || completedAt == null)
                    || !produced && (outcome != null || !outcomeFingerprint.isEmpty()
                    || completedAt != null)
                    || lastFailure == null && lastFailedAt != null
                    || lastFailure != null && lastFailedAt == null) {
                throw new IllegalArgumentException(
                        "External bootstrap-root ceremony snapshot is invalid");
            }
        }

        /**
         * Returns the exact ceremony identity.
         *
         * @return proposal ceremony identity
         */
        public String ceremonyId() {
            return proposal.ceremonyId();
        }
    }

    /**
     * Immutable proposal result.
     *
     * @param disposition proposal creation or conflict classification
     * @param snapshot current proposal, active workflow, or produced-head projection
     */
    record ProposalResult(ProposalDisposition disposition, CeremonySnapshot snapshot) {
        /** Enforces complete proposal result. */
        public ProposalResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /**
     * Immutable approval result; snapshot is absent only when no ceremony exists.
     *
     * @param disposition checker command classification
     * @param snapshot current projection, or {@code null} only for not found
     */
    record ApprovalResult(ApprovalDisposition disposition, CeremonySnapshot snapshot) {
        /** Enforces approval result presence semantics. */
        public ApprovalResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            if ((disposition == ApprovalDisposition.NOT_FOUND) != (snapshot == null)) {
                throw new IllegalArgumentException("Ceremony approval result is invalid");
            }
        }
    }

    /**
     * Immutable acquisition result.
     *
     * @param disposition worker acquisition classification
     * @param claim exact execution fence, present only when acquired
     * @param snapshot current projection, absent only when not found
     */
    record Acquisition(
            AcquisitionDisposition disposition,
            ExecutionClaim claim,
            CeremonySnapshot snapshot) {
        /** Enforces acquisition claim presence semantics. */
        public Acquisition {
            disposition = Objects.requireNonNull(disposition, "disposition");
            if ((disposition == AcquisitionDisposition.ACQUIRED) != (claim != null)
                    || disposition == AcquisitionDisposition.NOT_FOUND && snapshot != null
                    || disposition != AcquisitionDisposition.NOT_FOUND && snapshot == null) {
                throw new IllegalArgumentException("Ceremony acquisition result is invalid");
            }
        }
    }

    /**
     * Immutable completion result.
     *
     * @param disposition terminal commit, replay, fence, or conflict classification
     * @param snapshot current durable projection
     */
    record CompletionResult(
            CompletionDisposition disposition,
            CeremonySnapshot snapshot) {
        /** Enforces complete outcome-commit projection. */
        public CompletionResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /**
     * Immutable failed-attempt release result.
     *
     * @param disposition retry release, deterministic expiry, or fence classification
     * @param snapshot current durable projection
     */
    record FailureResult(FailureDisposition disposition, CeremonySnapshot snapshot) {
        /** Enforces complete release projection. */
        public FailureResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    private static String identifier(String value, String field) {
        String result = normalized(value);
        if (!validIdentifier(result)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return result;
    }

    private static String fingerprint(String value, String field) {
        String result = normalized(value);
        if (!validFingerprint(result)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return result;
    }

    private static boolean validIdentifier(String value) {
        return IDENTIFIER.matcher(value).matches();
    }

    private static boolean validFingerprint(String value) {
        return FINGERPRINT.matcher(value).matches();
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    /** Shared bounded actor and workflow identity grammar. */
    Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    /** Shared lowercase SHA-256 protocol fingerprint grammar. */
    Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
}
