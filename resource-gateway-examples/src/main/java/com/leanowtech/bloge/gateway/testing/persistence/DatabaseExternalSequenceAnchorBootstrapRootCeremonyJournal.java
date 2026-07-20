package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyProducer;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.Acquisition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonySnapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.CompletionDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.CompletionResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.ExecutionClaim;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.FailureDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.FailureResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalDisposition;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal.State;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Database-clock implementation of the bootstrap-root ceremony maker/checker journal.
 *
 * <p>A root-set lock serializes proposal creation across replicas and prevents parallel active
 * ceremonies under different ids. Every mutation locks and integrity-verifies the exact journal
 * row before applying an approval, lease takeover, heartbeat successor, failure release, or
 * terminal outcome. External signer calls never execute inside these transactions. Initialization
 * migrates only an exact heartbeat-empty v1 fingerprint into the v2 record shape.</p>
 */
public final class DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal
        implements ExternalSequenceAnchorBootstrapRootCeremonyJournal {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final String LEGACY_RECORD_SCHEMA =
            "bloge.externalSequenceAnchorBootstrapRootCeremonyJournalRecord.v1";
    private static final String RECORD_SCHEMA =
            "bloge.externalSequenceAnchorBootstrapRootCeremonyJournalRecord.v2";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String scopeId;
    private final String rootSetId;
    private final TransactionTemplate transactions;

    /**
     * Creates one durable journal for an exact fleet and bootstrap-root chain.
     *
     * @param jdbc isolated control-plane JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param scopeId stable Resource Gateway fleet scope
     * @param rootSetId exact managed bootstrap-root chain identity
     * @param transactionManager manager for the same datasource
     */
    public DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String scopeId,
            String rootSetId,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.scopeId = identifier(scopeId, "scopeId");
        this.rootSetId = identifier(rootSetId, "rootSetId");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates root-set serialization and whole-record-fingerprinted journal tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_external_sequence_anchor_bootstrap_root_ceremony_locks (
                    scope_id VARCHAR(255) NOT NULL,
                    root_set_id VARCHAR(255) NOT NULL,
                    PRIMARY KEY (scope_id, root_set_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_external_sequence_anchor_bootstrap_root_ceremonies (
                    scope_id VARCHAR(255) NOT NULL,
                    root_set_id VARCHAR(255) NOT NULL,
                    ceremony_id VARCHAR(255) NOT NULL,
                    state VARCHAR(32) NOT NULL,
                    proposal_json CLOB NOT NULL,
                    proposal_fingerprint VARCHAR(71) NOT NULL,
                    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    proposal_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    approval_request_id VARCHAR(255),
                    approval_fingerprint VARCHAR(71),
                    checker_id VARCHAR(255),
                    approved_at TIMESTAMP WITH TIME ZONE,
                    approval_until TIMESTAMP WITH TIME ZONE,
                    claim_owner VARCHAR(255),
                    claim_version BIGINT NOT NULL,
                    claim_until TIMESTAMP WITH TIME ZONE,
                    heartbeat_request_id VARCHAR(255),
                    heartbeat_fingerprint VARCHAR(71),
                    heartbeat_at TIMESTAMP WITH TIME ZONE,
                    heartbeat_count BIGINT NOT NULL,
                    attempt_count BIGINT NOT NULL,
                    last_failure_reason VARCHAR(64),
                    last_failed_at TIMESTAMP WITH TIME ZONE,
                    outcome_json CLOB,
                    outcome_fingerprint VARCHAR(71),
                    completed_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (scope_id, root_set_id, ceremony_id)
                )
                """);
        jdbc.execute("""
                ALTER TABLE rg_external_sequence_anchor_bootstrap_root_ceremonies
                ADD COLUMN IF NOT EXISTS heartbeat_request_id VARCHAR(255)
                """);
        jdbc.execute("""
                ALTER TABLE rg_external_sequence_anchor_bootstrap_root_ceremonies
                ADD COLUMN IF NOT EXISTS heartbeat_fingerprint VARCHAR(71)
                """);
        jdbc.execute("""
                ALTER TABLE rg_external_sequence_anchor_bootstrap_root_ceremonies
                ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP WITH TIME ZONE
                """);
        jdbc.execute("""
                ALTER TABLE rg_external_sequence_anchor_bootstrap_root_ceremonies
                ADD COLUMN IF NOT EXISTS heartbeat_count BIGINT DEFAULT 0 NOT NULL
                """);
        Boolean migrated = transactions.execute(status -> {
            lockRootSet();
            migrateLegacyRecordFingerprints();
            return Boolean.TRUE;
        });
        Objects.requireNonNull(migrated, "ceremony journal migration result");
    }

    /** {@inheritDoc} */
    @Override
    public ProposalResult propose(CeremonyProposal proposal) {
        CeremonyProposal safeProposal = Objects.requireNonNull(proposal, "proposal");
        String proposalFingerprint = ProtocolFingerprint.of(objectMapper, safeProposal);
        ProposalResult result = transactions.execute(status -> {
            lockRootSet();
            Instant now = databaseNow();
            StoredCeremony existing = find(safeProposal.ceremonyId(), true).orElse(null);
            if (existing != null) {
                requireValid(existing);
                existing = expireIfRequired(existing, now);
                return new ProposalResult(existing.proposalFingerprint().equals(
                        proposalFingerprint)
                        ? ProposalDisposition.IDEMPOTENT_REPLAY
                        : ProposalDisposition.IDEMPOTENCY_CONFLICT,
                        snapshot(existing));
            }
            StoredCeremony active = activeCeremony(now);
            if (active != null) {
                return new ProposalResult(ProposalDisposition.ACTIVE_CEREMONY_EXISTS,
                        snapshot(active));
            }
            StoredCeremony latest = latestProduced();
            if (latest != null && !exactSuccessor(latest, safeProposal)) {
                return new ProposalResult(ProposalDisposition.CHAIN_CONFLICT,
                        snapshot(latest));
            }
            Instant proposalUntil = earlier(
                    now.plusSeconds(safeProposal.proposalDurationSeconds()),
                    safeProposal.preflight().executionDeadline());
            if (!proposalUntil.isAfter(now)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root ceremony execution deadline has elapsed");
            }
            StoredCeremony created = fingerprinted(new StoredCeremony(
                    scopeId, rootSetId, safeProposal.ceremonyId(), State.PENDING_APPROVAL,
                    safeProposal, proposalFingerprint, now, proposalUntil,
                    "", "", "", null, null, "", 0L, null,
                    "", "", null, 0L, 0L, null, null,
                    null, "", null, now, ""));
            insert(created);
            return new ProposalResult(ProposalDisposition.CREATED, snapshot(created));
        });
        return Objects.requireNonNull(result, "ceremony proposal result");
    }

    /** {@inheritDoc} */
    @Override
    public ApprovalResult approve(ApprovalCommand command) {
        ApprovalCommand safeCommand = Objects.requireNonNull(command, "command");
        String approvalFingerprint = ProtocolFingerprint.of(objectMapper, safeCommand);
        ApprovalResult result = transactions.execute(status -> {
            StoredCeremony current = find(safeCommand.ceremonyId(), true).orElse(null);
            if (current == null) {
                return new ApprovalResult(ApprovalDisposition.NOT_FOUND, null);
            }
            requireValid(current);
            Instant now = databaseNow();
            current = expireIfRequired(current, now);
            if (!current.approvalRequestId().isEmpty()
                    && current.approvalRequestId().equals(
                    safeCommand.approvalRequestId())) {
                return new ApprovalResult(current.approvalFingerprint().equals(
                        approvalFingerprint)
                        ? ApprovalDisposition.IDEMPOTENT_REPLAY
                        : ApprovalDisposition.IDEMPOTENCY_CONFLICT,
                        snapshot(current));
            }
            if (current.state() != State.PENDING_APPROVAL) {
                return new ApprovalResult(ApprovalDisposition.NOT_PENDING,
                        snapshot(current));
            }
            if (current.proposal().makerId().equals(safeCommand.checkerId())) {
                return new ApprovalResult(ApprovalDisposition.SELF_APPROVAL,
                        snapshot(current));
            }
            Instant approvalUntil = earlier(
                    now.plusSeconds(safeCommand.approvalDurationSeconds()),
                    current.proposal().preflight().executionDeadline());
            if (!approvalUntil.isAfter(now)) {
                StoredCeremony expired = expire(current, now, State.PROPOSAL_EXPIRED);
                return new ApprovalResult(ApprovalDisposition.NOT_PENDING,
                        snapshot(expired));
            }
            StoredCeremony approved = fingerprinted(copy(current, State.APPROVED,
                    safeCommand.approvalRequestId(), approvalFingerprint,
                    safeCommand.checkerId(), now, approvalUntil,
                    current.claimOwner(), current.claimVersion(), current.claimUntil(),
                    current.heartbeatRequestId(), current.heartbeatFingerprint(),
                    current.heartbeatAt(), current.heartbeatCount(),
                    current.attemptCount(), current.lastFailure(), current.lastFailedAt(),
                    current.outcome(), current.outcomeFingerprint(), current.completedAt(), now));
            update(approved);
            return new ApprovalResult(ApprovalDisposition.APPROVED, snapshot(approved));
        });
        return Objects.requireNonNull(result, "ceremony approval result");
    }

    /** {@inheritDoc} */
    @Override
    public Acquisition acquire(AcquisitionCommand command) {
        AcquisitionCommand safeCommand = Objects.requireNonNull(command, "command");
        Acquisition result = transactions.execute(status -> {
            StoredCeremony current = find(safeCommand.ceremonyId(), true).orElse(null);
            if (current == null) {
                return new Acquisition(AcquisitionDisposition.NOT_FOUND, null, null);
            }
            requireValid(current);
            Instant now = databaseNow();
            current = expireIfRequired(current, now);
            if (current.state() == State.PROPOSAL_EXPIRED
                    || current.state() == State.APPROVAL_EXPIRED) {
                return new Acquisition(AcquisitionDisposition.EXPIRED, null,
                        snapshot(current));
            }
            if (current.state() == State.PRODUCED) {
                return new Acquisition(AcquisitionDisposition.PRODUCED, null,
                        snapshot(current));
            }
            if (current.state() == State.PENDING_APPROVAL) {
                return new Acquisition(AcquisitionDisposition.NOT_APPROVED, null,
                        snapshot(current));
            }
            if (current.state() == State.EXECUTING
                    && current.claimUntil().isAfter(now)) {
                return new Acquisition(AcquisitionDisposition.BUSY, null,
                        snapshot(current));
            }
            Instant requestedUntil = now.plusSeconds(safeCommand.leaseDurationSeconds());
            Instant claimUntil = requestedUntil.isBefore(current.approvalUntil())
                    ? requestedUntil : current.approvalUntil();
            if (!claimUntil.isAfter(now)) {
                StoredCeremony expired = expire(current, now, State.APPROVAL_EXPIRED);
                return new Acquisition(AcquisitionDisposition.EXPIRED, null,
                        snapshot(expired));
            }
            long nextVersion = Math.addExact(current.claimVersion(), 1L);
            long nextAttempt = Math.addExact(current.attemptCount(), 1L);
            StoredCeremony acquired = fingerprinted(copy(current, State.EXECUTING,
                    current.approvalRequestId(), current.approvalFingerprint(),
                    current.checkerId(), current.approvedAt(), current.approvalUntil(),
                    safeCommand.workerId(), nextVersion, claimUntil,
                    "", "", null, 0L, nextAttempt,
                    current.lastFailure(), current.lastFailedAt(), current.outcome(),
                    current.outcomeFingerprint(), current.completedAt(), now));
            update(acquired);
            ExecutionClaim claim = new ExecutionClaim(ExecutionClaim.SCHEMA_VERSION,
                    acquired.ceremonyId(), acquired.claimOwner(), acquired.claimVersion(),
                    acquired.claimUntil(), acquired.proposal());
            return new Acquisition(AcquisitionDisposition.ACQUIRED, claim,
                    snapshot(acquired));
        });
        return Objects.requireNonNull(result, "ceremony acquisition result");
    }

    /** {@inheritDoc} */
    @Override
    public HeartbeatResult heartbeat(HeartbeatCommand command) {
        HeartbeatCommand safeCommand = Objects.requireNonNull(command, "command");
        String heartbeatFingerprint = ProtocolFingerprint.of(objectMapper, safeCommand);
        HeartbeatResult result = transactions.execute(status -> {
            StoredCeremony current = find(safeCommand.ceremonyId(), true).orElseThrow(() ->
                    new IllegalStateException("Bootstrap-root ceremony journal row is missing"));
            requireValid(current);
            if (current.state() == State.PRODUCED) {
                return new HeartbeatResult(HeartbeatDisposition.PRODUCED, null,
                        snapshot(current));
            }
            Instant now = databaseNow();
            current = expireIfRequired(current, now);
            if (current.state() == State.PROPOSAL_EXPIRED
                    || current.state() == State.APPROVAL_EXPIRED) {
                return new HeartbeatResult(HeartbeatDisposition.EXPIRED, null,
                        snapshot(current));
            }
            if (current.heartbeatRequestId().equals(safeCommand.heartbeatRequestId())) {
                if (!current.heartbeatFingerprint().equals(heartbeatFingerprint)) {
                    return new HeartbeatResult(HeartbeatDisposition.IDEMPOTENCY_CONFLICT,
                            null, snapshot(current));
                }
                if (current.state() == State.EXECUTING) {
                    return new HeartbeatResult(HeartbeatDisposition.IDEMPOTENT_REPLAY,
                            currentClaim(current), snapshot(current));
                }
                return new HeartbeatResult(HeartbeatDisposition.FENCE_REJECTED, null,
                        snapshot(current));
            }
            if (!matchesLiveFence(current, safeCommand.claim(), now)) {
                return new HeartbeatResult(HeartbeatDisposition.FENCE_REJECTED, null,
                        snapshot(current));
            }
            if (current.heartbeatCount() >= MAXIMUM_HEARTBEATS_PER_ATTEMPT) {
                return new HeartbeatResult(HeartbeatDisposition.LIMIT_REACHED, null,
                        snapshot(current));
            }
            Instant requestedUntil = now.plusSeconds(safeCommand.leaseDurationSeconds());
            Instant claimUntil = earlier(requestedUntil, current.approvalUntil());
            if (!claimUntil.isAfter(current.claimUntil())) {
                return new HeartbeatResult(HeartbeatDisposition.NOT_EXTENDED, null,
                        snapshot(current));
            }
            long nextVersion = Math.addExact(current.claimVersion(), 1L);
            long nextHeartbeat = Math.addExact(current.heartbeatCount(), 1L);
            StoredCeremony renewed = fingerprinted(copy(current, State.EXECUTING,
                    current.approvalRequestId(), current.approvalFingerprint(),
                    current.checkerId(), current.approvedAt(), current.approvalUntil(),
                    current.claimOwner(), nextVersion, claimUntil,
                    safeCommand.heartbeatRequestId(), heartbeatFingerprint, now,
                    nextHeartbeat, current.attemptCount(), current.lastFailure(),
                    current.lastFailedAt(), current.outcome(), current.outcomeFingerprint(),
                    current.completedAt(), now));
            update(renewed);
            return new HeartbeatResult(HeartbeatDisposition.RENEWED,
                    currentClaim(renewed), snapshot(renewed));
        });
        return Objects.requireNonNull(result, "ceremony heartbeat result");
    }

    /** {@inheritDoc} */
    @Override
    public CompletionResult complete(
            ExecutionClaim claim,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome outcome) {
        ExecutionClaim safeClaim = Objects.requireNonNull(claim, "claim");
        ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome safeOutcome =
                Objects.requireNonNull(outcome, "outcome");
        String outcomeFingerprint = ProtocolFingerprint.of(objectMapper, safeOutcome);
        CompletionResult result = transactions.execute(status -> {
            StoredCeremony current = find(safeClaim.ceremonyId(), true).orElseThrow(() ->
                    new IllegalStateException("Bootstrap-root ceremony journal row is missing"));
            requireValid(current);
            if (current.state() == State.PRODUCED) {
                return new CompletionResult(current.outcomeFingerprint().equals(
                        outcomeFingerprint)
                        ? CompletionDisposition.IDEMPOTENT_REPLAY
                        : CompletionDisposition.OUTCOME_CONFLICT,
                        snapshot(current));
            }
            Instant now = databaseNow();
            current = expireIfRequired(current, now);
            if (!matchesLiveFence(current, safeClaim, now)) {
                return new CompletionResult(CompletionDisposition.FENCE_REJECTED,
                        snapshot(current));
            }
            requireBoundOutcome(current, safeOutcome);
            StoredCeremony produced = fingerprinted(copy(current, State.PRODUCED,
                    current.approvalRequestId(), current.approvalFingerprint(),
                    current.checkerId(), current.approvedAt(), current.approvalUntil(),
                    current.claimOwner(), current.claimVersion(), current.claimUntil(),
                    current.heartbeatRequestId(), current.heartbeatFingerprint(),
                    current.heartbeatAt(), current.heartbeatCount(),
                    current.attemptCount(), current.lastFailure(), current.lastFailedAt(),
                    safeOutcome, outcomeFingerprint, now, now));
            update(produced);
            return new CompletionResult(CompletionDisposition.PRODUCED,
                    snapshot(produced));
        });
        return Objects.requireNonNull(result, "ceremony completion result");
    }

    /** {@inheritDoc} */
    @Override
    public FailureResult release(
            ExecutionClaim claim,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason reason) {
        ExecutionClaim safeClaim = Objects.requireNonNull(claim, "claim");
        ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason safeReason =
                Objects.requireNonNull(reason, "reason");
        FailureResult result = transactions.execute(status -> {
            StoredCeremony current = find(safeClaim.ceremonyId(), true).orElseThrow(() ->
                    new IllegalStateException("Bootstrap-root ceremony journal row is missing"));
            requireValid(current);
            Instant now = databaseNow();
            if (!matchesFenceIdentity(current, safeClaim)) {
                return new FailureResult(FailureDisposition.FENCE_REJECTED,
                        snapshot(current));
            }
            if (!current.approvalUntil().isAfter(now)) {
                return new FailureResult(FailureDisposition.EXPIRED,
                        snapshot(expire(current, now, State.APPROVAL_EXPIRED)));
            }
            if (!current.claimUntil().isAfter(now)) {
                return new FailureResult(FailureDisposition.FENCE_REJECTED,
                        snapshot(current));
            }
            StoredCeremony released = fingerprinted(copy(current, State.APPROVED,
                    current.approvalRequestId(), current.approvalFingerprint(),
                    current.checkerId(), current.approvedAt(), current.approvalUntil(),
                    current.claimOwner(), current.claimVersion(), current.claimUntil(),
                    current.heartbeatRequestId(), current.heartbeatFingerprint(),
                    current.heartbeatAt(), current.heartbeatCount(),
                    current.attemptCount(), safeReason, now, current.outcome(),
                    current.outcomeFingerprint(), current.completedAt(), now));
            update(released);
            return new FailureResult(FailureDisposition.RELEASED,
                    snapshot(released));
        });
        return Objects.requireNonNull(result, "ceremony failure result");
    }

    /** {@inheritDoc} */
    @Override
    public Optional<CeremonySnapshot> snapshot(String ceremonyId) {
        String safeCeremonyId = identifier(ceremonyId, "ceremonyId");
        Optional<CeremonySnapshot> result = transactions.execute(status -> {
            StoredCeremony current = find(safeCeremonyId, true).orElse(null);
            if (current == null) {
                return Optional.empty();
            }
            requireValid(current);
            return Optional.of(snapshot(expireIfRequired(current, databaseNow())));
        });
        return Objects.requireNonNull(result, "ceremony snapshot result");
    }

    /** {@inheritDoc} */
    @Override
    public boolean durable() {
        return true;
    }

    private StoredCeremony activeCeremony(Instant now) {
        List<StoredCeremony> rows = jdbc.query("""
                SELECT *
                FROM rg_external_sequence_anchor_bootstrap_root_ceremonies
                WHERE scope_id = ? AND root_set_id = ?
                  AND state IN ('PENDING_APPROVAL', 'APPROVED', 'EXECUTING')
                ORDER BY submitted_at, ceremony_id
                FOR UPDATE
                """, this::row, scopeId, rootSetId);
        StoredCeremony active = null;
        for (StoredCeremony row : rows) {
            requireValid(row);
            StoredCeremony candidate = expireIfRequired(row, now);
            if (candidate.state() != State.PROPOSAL_EXPIRED
                    && candidate.state() != State.APPROVAL_EXPIRED) {
                if (active != null) {
                    throw new IllegalStateException(
                            "Multiple active bootstrap-root ceremonies are corrupt");
                }
                active = candidate;
            }
        }
        return active;
    }

    private StoredCeremony latestProduced() {
        List<StoredCeremony> rows = jdbc.query("""
                SELECT *
                FROM rg_external_sequence_anchor_bootstrap_root_ceremonies
                WHERE scope_id = ? AND root_set_id = ? AND state = 'PRODUCED'
                """, this::row, scopeId, rootSetId);
        if (rows.isEmpty()) {
            return null;
        }
        rows.forEach(this::requireValid);
        StoredCeremony latest = rows.stream().max(java.util.Comparator.comparingLong(
                value -> value.proposal().preflight().sequence())).orElseThrow();
        long duplicateHeads = rows.stream().filter(value -> value.proposal().preflight()
                .sequence() == latest.proposal().preflight().sequence()).count();
        if (duplicateHeads != 1L) {
            throw new IllegalStateException(
                    "Duplicate produced bootstrap-root ceremony sequence is corrupt");
        }
        return latest;
    }

    private static boolean exactSuccessor(
            StoredCeremony latest, CeremonyProposal proposal) {
        return proposal.currentBundle() != null
                && latest.outcome() != null
                && latest.outcome().bundle().equals(proposal.currentBundle())
                && latest.outcome().bundle().headMaterialFingerprint().equals(
                proposal.request().expectedPreviousMaterialFingerprint())
                && proposal.preflight().sequence()
                == latest.outcome().bundle().transitions().getLast().material().sequence() + 1L;
    }

    private StoredCeremony expireIfRequired(StoredCeremony current, Instant now) {
        if (current.state() == State.PENDING_APPROVAL
                && !current.proposalUntil().isAfter(now)) {
            return expire(current, now, State.PROPOSAL_EXPIRED);
        }
        if ((current.state() == State.APPROVED || current.state() == State.EXECUTING)
                && !current.approvalUntil().isAfter(now)) {
            return expire(current, now, State.APPROVAL_EXPIRED);
        }
        return current;
    }

    private StoredCeremony expire(StoredCeremony current, Instant now, State expiredState) {
        StoredCeremony expired = fingerprinted(copy(current, expiredState,
                current.approvalRequestId(), current.approvalFingerprint(),
                current.checkerId(), current.approvedAt(), current.approvalUntil(),
                current.claimOwner(), current.claimVersion(), current.claimUntil(),
                current.heartbeatRequestId(), current.heartbeatFingerprint(),
                current.heartbeatAt(), current.heartbeatCount(),
                current.attemptCount(), current.lastFailure(), current.lastFailedAt(),
                current.outcome(), current.outcomeFingerprint(), current.completedAt(), now));
        update(expired);
        return expired;
    }

    private boolean matchesLiveFence(
            StoredCeremony current, ExecutionClaim claim, Instant now) {
        return matchesFenceIdentity(current, claim)
                && current.claimUntil().isAfter(now)
                && current.approvalUntil().isAfter(now);
    }

    private static boolean matchesFenceIdentity(
            StoredCeremony current, ExecutionClaim claim) {
        return current.state() == State.EXECUTING
                && current.ceremonyId().equals(claim.ceremonyId())
                && current.claimOwner().equals(claim.workerId())
                && current.claimVersion() == claim.claimVersion()
                && current.claimUntil().equals(claim.claimUntil())
                && current.proposal().equals(claim.proposal());
    }

    private static ExecutionClaim currentClaim(StoredCeremony current) {
        return new ExecutionClaim(ExecutionClaim.SCHEMA_VERSION,
                current.ceremonyId(), current.claimOwner(), current.claimVersion(),
                current.claimUntil(), current.proposal());
    }

    private static void requireBoundOutcome(
            StoredCeremony current,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome outcome) {
        ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyPreflight preflight =
                current.proposal().preflight();
        if (!current.ceremonyId().equals(outcome.ceremonyId())
                || !preflight.materialFingerprint().equals(
                outcome.bundle().headMaterialFingerprint())
                || outcome.bundle().transitions().getLast().material().sequence()
                != preflight.sequence()) {
            throw new IllegalArgumentException(
                    "Ceremony outcome does not match its approved preflight");
        }
    }

    private void lockRootSet() {
        jdbc.update("""
                MERGE INTO rg_external_sequence_anchor_bootstrap_root_ceremony_locks
                    (scope_id, root_set_id) KEY (scope_id, root_set_id)
                    VALUES (?, ?)
                """, scopeId, rootSetId);
        jdbc.queryForObject("""
                SELECT scope_id
                FROM rg_external_sequence_anchor_bootstrap_root_ceremony_locks
                WHERE scope_id = ? AND root_set_id = ? FOR UPDATE
                """, String.class, scopeId, rootSetId);
    }

    private Optional<StoredCeremony> find(String ceremonyId, boolean forUpdate) {
        List<StoredCeremony> rows = jdbc.query("""
                SELECT *
                FROM rg_external_sequence_anchor_bootstrap_root_ceremonies
                WHERE scope_id = ? AND root_set_id = ? AND ceremony_id = ?
                """ + (forUpdate ? " FOR UPDATE" : ""), this::row,
                scopeId, rootSetId, ceremonyId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Duplicate bootstrap-root ceremony journal row");
        }
        return rows.stream().findFirst();
    }

    private void insert(StoredCeremony value) {
        jdbc.update("""
                INSERT INTO rg_external_sequence_anchor_bootstrap_root_ceremonies (
                    scope_id, root_set_id, ceremony_id, state, proposal_json,
                    proposal_fingerprint, submitted_at, proposal_until, approval_request_id,
                    approval_fingerprint, checker_id, approved_at, approval_until,
                    claim_owner, claim_version, claim_until, heartbeat_request_id,
                    heartbeat_fingerprint, heartbeat_at, heartbeat_count, attempt_count,
                    last_failure_reason, last_failed_at, outcome_json,
                    outcome_fingerprint, completed_at, updated_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, parameters(value));
    }

    private void update(StoredCeremony value) {
        int changed = jdbc.update("""
                UPDATE rg_external_sequence_anchor_bootstrap_root_ceremonies
                SET state = ?, proposal_json = ?, proposal_fingerprint = ?, submitted_at = ?,
                    proposal_until = ?, approval_request_id = ?, approval_fingerprint = ?, checker_id = ?,
                    approved_at = ?, approval_until = ?, claim_owner = ?, claim_version = ?,
                    claim_until = ?, heartbeat_request_id = ?, heartbeat_fingerprint = ?,
                    heartbeat_at = ?, heartbeat_count = ?, attempt_count = ?,
                    last_failure_reason = ?, last_failed_at = ?, outcome_json = ?,
                    outcome_fingerprint = ?, completed_at = ?, updated_at = ?,
                    record_fingerprint = ?
                WHERE scope_id = ? AND root_set_id = ? AND ceremony_id = ?
                """, value.state().name(), write(value.proposal()),
                value.proposalFingerprint(), timestamp(value.submittedAt()),
                timestamp(value.proposalUntil()),
                nullable(value.approvalRequestId()), nullable(value.approvalFingerprint()),
                nullable(value.checkerId()), timestamp(value.approvedAt()),
                timestamp(value.approvalUntil()), nullable(value.claimOwner()),
                value.claimVersion(), timestamp(value.claimUntil()),
                nullable(value.heartbeatRequestId()), nullable(value.heartbeatFingerprint()),
                timestamp(value.heartbeatAt()), value.heartbeatCount(), value.attemptCount(),
                value.lastFailure() == null ? null : value.lastFailure().name(),
                timestamp(value.lastFailedAt()), writeNullable(value.outcome()),
                nullable(value.outcomeFingerprint()), timestamp(value.completedAt()),
                timestamp(value.updatedAt()), value.recordFingerprint(), scopeId, rootSetId,
                value.ceremonyId());
        if (changed != 1) {
            throw new IllegalStateException("Bootstrap-root ceremony update lost its row");
        }
    }

    private Object[] parameters(StoredCeremony value) {
        return new Object[]{value.scopeId(), value.rootSetId(), value.ceremonyId(),
                value.state().name(), write(value.proposal()), value.proposalFingerprint(),
                timestamp(value.submittedAt()), timestamp(value.proposalUntil()),
                nullable(value.approvalRequestId()),
                nullable(value.approvalFingerprint()), nullable(value.checkerId()),
                timestamp(value.approvedAt()), timestamp(value.approvalUntil()),
                nullable(value.claimOwner()), value.claimVersion(),
                timestamp(value.claimUntil()), nullable(value.heartbeatRequestId()),
                nullable(value.heartbeatFingerprint()), timestamp(value.heartbeatAt()),
                value.heartbeatCount(), value.attemptCount(),
                value.lastFailure() == null ? null : value.lastFailure().name(),
                timestamp(value.lastFailedAt()), writeNullable(value.outcome()),
                nullable(value.outcomeFingerprint()), timestamp(value.completedAt()),
                timestamp(value.updatedAt()), value.recordFingerprint()};
    }

    private StoredCeremony row(ResultSet result, int rowNumber) throws SQLException {
        return new StoredCeremony(result.getString("scope_id"),
                result.getString("root_set_id"), result.getString("ceremony_id"),
                State.valueOf(result.getString("state")),
                read(result.getString("proposal_json"), CeremonyProposal.class),
                result.getString("proposal_fingerprint"), instant(result, "submitted_at"),
                instant(result, "proposal_until"),
                normalized(result.getString("approval_request_id")),
                normalized(result.getString("approval_fingerprint")),
                normalized(result.getString("checker_id")), instant(result, "approved_at"),
                instant(result, "approval_until"),
                normalized(result.getString("claim_owner")),
                result.getLong("claim_version"), instant(result, "claim_until"),
                normalized(result.getString("heartbeat_request_id")),
                normalized(result.getString("heartbeat_fingerprint")),
                instant(result, "heartbeat_at"), result.getLong("heartbeat_count"),
                result.getLong("attempt_count"), failure(result.getString(
                "last_failure_reason")), instant(result, "last_failed_at"),
                readNullable(result.getString("outcome_json"),
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer
                                .CeremonyOutcome.class),
                normalized(result.getString("outcome_fingerprint")),
                instant(result, "completed_at"), instant(result, "updated_at"),
                result.getString("record_fingerprint"));
    }

    private void requireValid(StoredCeremony value) {
        try {
            if (!contentFingerprintsValid(value)
                    || !FINGERPRINT.matcher(value.recordFingerprint()).matches()
                    || !value.recordFingerprint().equals(integrityFingerprint(value))) {
                throw new IllegalStateException(
                        "Bootstrap-root ceremony journal row is corrupt");
            }
            snapshot(value);
        } catch (IllegalStateException corrupt) {
            throw corrupt;
        } catch (RuntimeException corrupt) {
            throw new IllegalStateException(
                    "Bootstrap-root ceremony journal row is corrupt", corrupt);
        }
    }

    private void migrateLegacyRecordFingerprints() {
        List<StoredCeremony> rows = jdbc.query("""
                SELECT *
                FROM rg_external_sequence_anchor_bootstrap_root_ceremonies
                WHERE scope_id = ? AND root_set_id = ?
                ORDER BY submitted_at, ceremony_id
                FOR UPDATE
                """, this::row, scopeId, rootSetId);
        for (StoredCeremony row : rows) {
            if (row.recordFingerprint().equals(integrityFingerprint(row))) {
                requireValid(row);
                continue;
            }
            boolean emptyHeartbeat = row.heartbeatRequestId().isEmpty()
                    && row.heartbeatFingerprint().isEmpty()
                    && row.heartbeatAt() == null && row.heartbeatCount() == 0L;
            if (!emptyHeartbeat || !contentFingerprintsValid(row)
                    || !row.recordFingerprint().equals(legacyIntegrityFingerprint(row))) {
                throw new IllegalStateException(
                        "Bootstrap-root ceremony journal row is corrupt during migration");
            }
            snapshot(row);
            update(fingerprinted(row));
        }
    }

    private boolean contentFingerprintsValid(StoredCeremony value) {
        return scopeId.equals(value.scopeId()) && rootSetId.equals(value.rootSetId())
                && value.ceremonyId().equals(value.proposal().ceremonyId())
                && value.proposalFingerprint().equals(
                ProtocolFingerprint.of(objectMapper, value.proposal()))
                && (value.outcome() == null && value.outcomeFingerprint().isEmpty()
                || value.outcome() != null && value.outcomeFingerprint().equals(
                ProtocolFingerprint.of(objectMapper, value.outcome())));
    }

    private StoredCeremony fingerprinted(StoredCeremony value) {
        return new StoredCeremony(value.scopeId(), value.rootSetId(), value.ceremonyId(),
                value.state(), value.proposal(), value.proposalFingerprint(),
                value.submittedAt(), value.proposalUntil(), value.approvalRequestId(),
                value.approvalFingerprint(),
                value.checkerId(), value.approvedAt(), value.approvalUntil(),
                value.claimOwner(), value.claimVersion(), value.claimUntil(),
                value.heartbeatRequestId(), value.heartbeatFingerprint(),
                value.heartbeatAt(), value.heartbeatCount(),
                value.attemptCount(), value.lastFailure(), value.lastFailedAt(),
                value.outcome(), value.outcomeFingerprint(), value.completedAt(),
                value.updatedAt(), integrityFingerprint(value));
    }

    private String integrityFingerprint(StoredCeremony value) {
        return ProtocolFingerprint.of(objectMapper, new IntegrityMaterial(
                RECORD_SCHEMA, value.scopeId(), value.rootSetId(), value.ceremonyId(),
                value.state(), value.proposal(), value.proposalFingerprint(),
                value.submittedAt(), value.proposalUntil(), value.approvalRequestId(),
                value.approvalFingerprint(),
                value.checkerId(), value.approvedAt(), value.approvalUntil(),
                value.claimOwner(), value.claimVersion(), value.claimUntil(),
                value.heartbeatRequestId(), value.heartbeatFingerprint(),
                value.heartbeatAt(), value.heartbeatCount(),
                value.attemptCount(), value.lastFailure(), value.lastFailedAt(),
                value.outcome(), value.outcomeFingerprint(), value.completedAt(),
                value.updatedAt()));
    }

    private String legacyIntegrityFingerprint(StoredCeremony value) {
        return ProtocolFingerprint.of(objectMapper, new LegacyIntegrityMaterial(
                LEGACY_RECORD_SCHEMA, value.scopeId(), value.rootSetId(), value.ceremonyId(),
                value.state(), value.proposal(), value.proposalFingerprint(),
                value.submittedAt(), value.proposalUntil(), value.approvalRequestId(),
                value.approvalFingerprint(), value.checkerId(), value.approvedAt(),
                value.approvalUntil(), value.claimOwner(), value.claimVersion(),
                value.claimUntil(), value.attemptCount(), value.lastFailure(),
                value.lastFailedAt(), value.outcome(), value.outcomeFingerprint(),
                value.completedAt(), value.updatedAt()));
    }

    private CeremonySnapshot snapshot(StoredCeremony value) {
        return new CeremonySnapshot(CeremonySnapshot.SCHEMA_VERSION, value.state(),
                value.proposal(), value.proposalFingerprint(), value.submittedAt(),
                value.proposalUntil(), value.approvalRequestId(),
                value.approvalFingerprint(), value.checkerId(),
                value.approvedAt(), value.approvalUntil(), value.claimOwner(),
                value.claimVersion(), value.claimUntil(), value.heartbeatRequestId(),
                value.heartbeatFingerprint(), value.heartbeatAt(), value.heartbeatCount(),
                value.attemptCount(),
                value.lastFailure(), value.lastFailedAt(), value.outcome(),
                value.outcomeFingerprint(), value.completedAt(), value.updatedAt(),
                value.recordFingerprint());
    }

    private static StoredCeremony copy(
            StoredCeremony value,
            State state,
            String approvalRequestId,
            String approvalFingerprint,
            String checkerId,
            Instant approvedAt,
            Instant approvalUntil,
            String claimOwner,
            long claimVersion,
            Instant claimUntil,
            String heartbeatRequestId,
            String heartbeatFingerprint,
            Instant heartbeatAt,
            long heartbeatCount,
            long attemptCount,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason lastFailure,
            Instant lastFailedAt,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome outcome,
            String outcomeFingerprint,
            Instant completedAt,
            Instant updatedAt) {
        return new StoredCeremony(value.scopeId(), value.rootSetId(), value.ceremonyId(), state,
                value.proposal(), value.proposalFingerprint(), value.submittedAt(),
                value.proposalUntil(),
                approvalRequestId, approvalFingerprint, checkerId, approvedAt, approvalUntil,
                claimOwner, claimVersion, claimUntil, heartbeatRequestId,
                heartbeatFingerprint, heartbeatAt, heartbeatCount, attemptCount,
                lastFailure, lastFailedAt,
                outcome, outcomeFingerprint, completedAt, updatedAt, "");
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return Objects.requireNonNull(value, "database time").toInstant();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Ceremony protocol value cannot be encoded", invalid);
        }
    }

    private String writeNullable(Object value) {
        return value == null ? null : write(value);
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException(
                    "Bootstrap-root ceremony journal JSON is corrupt", invalid);
        }
    }

    private <T> T readNullable(String value, Class<T> type) {
        return value == null ? null : read(value, type);
    }

    private static ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason failure(
            String value) {
        return value == null ? null
                : ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                .valueOf(value);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String nullable(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static String identifier(String value, String field) {
        String result = normalized(value);
        if (!IDENTIFIER.matcher(result).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return result;
    }

    private record StoredCeremony(
            String scopeId,
            String rootSetId,
            String ceremonyId,
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
            String heartbeatRequestId,
            String heartbeatFingerprint,
            Instant heartbeatAt,
            long heartbeatCount,
            long attemptCount,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason lastFailure,
            Instant lastFailedAt,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome outcome,
            String outcomeFingerprint,
            Instant completedAt,
            Instant updatedAt,
            String recordFingerprint) {
    }

    private record IntegrityMaterial(
            String schemaVersion,
            String scopeId,
            String rootSetId,
            String ceremonyId,
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
            String heartbeatRequestId,
            String heartbeatFingerprint,
            Instant heartbeatAt,
            long heartbeatCount,
            long attemptCount,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason lastFailure,
            Instant lastFailedAt,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome outcome,
            String outcomeFingerprint,
            Instant completedAt,
            Instant updatedAt) {
    }

    private record LegacyIntegrityMaterial(
            String schemaVersion,
            String scopeId,
            String rootSetId,
            String ceremonyId,
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
            Instant updatedAt) {
    }
}
