package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Durable multi-replica control plane for BLOGE scheduling-projection anti-entropy.
 *
 * <p>A short database-clock lease chooses one replica. The claimed replica then locks the durable
 * cursor and runs projection repair, finding lifecycle updates, and cursor checkpointing in one
 * transaction over the same isolated test-runtime datasource. A process crash before that commit
 * therefore leaves authority projections, findings, and cursors at the previous consistent point;
 * the committed lease expires and permits takeover.</p>
 *
 * <p>Finding rows deliberately contain only entity type, internal row ID, mismatched column names,
 * classifications, counters, and ownership fences. Authority JSON, business field values, and
 * credentials never enter this owner queue.</p>
 */
public final class DatabaseDurableStateProjectionControlPlane {

    private static final String JOB_NAME = "bloge-scheduling-projection";
    private static final String RETENTION_JOB_NAME = "bloge-projection-finding-retention";
    private static final int MAX_PAGE_SIZE = 1000;
    private static final int MAX_FINDING_PAGE_SIZE = 1000;
    private static final Duration MIN_LEASE = Duration.ofSeconds(1);
    private static final Duration MAX_LEASE = Duration.ofHours(1);
    private static final Duration MIN_RESOLVED_RETENTION = Duration.ofHours(1);
    private static final Duration MIN_ARCHIVE_RETENTION = Duration.ofDays(1);
    private static final Duration MAX_RETENTION = Duration.ofDays(3650);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DurableStateProjectionReconciler reconciler;
    private final TransactionTemplate transactions;
    private final String ownerId;
    private final Duration sweepLeaseDuration;

    /**
     * Creates a durable control plane over the same datasource as the BLOGE authority tables.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param transactionManager transaction manager bound to the JDBC datasource
     * @param objectMapper mapper used only for column-name arrays and authority decoding
     * @param ownerId stable identity of this Resource Gateway replica
     * @param sweepLeaseDuration maximum wall time of one atomic sweep page
     */
    public DatabaseDurableStateProjectionControlPlane(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            String ownerId,
            Duration sweepLeaseDuration) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.reconciler = new DurableStateProjectionReconciler(jdbc, objectMapper);
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ownerId = required(ownerId, "Projection reconciliation owner ID", 255);
        this.sweepLeaseDuration = boundedLease(
                sweepLeaseDuration, "Projection sweep lease duration");
    }

    /** Creates durable sweep state, finding storage, and queue indexes when absent. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_bloge_projection_sweep (
                    job_name VARCHAR(128) PRIMARY KEY,
                    after_execution_id VARCHAR(512) NOT NULL,
                    after_work_item_id VARCHAR(512) NOT NULL,
                    lease_owner VARCHAR(255) NOT NULL,
                    lease_token VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    revision BIGINT NOT NULL,
                    last_success_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_bloge_projection_findings (
                    entity_type VARCHAR(32) NOT NULL,
                    row_id VARCHAR(512) NOT NULL,
                    finding_kind VARCHAR(64) NOT NULL,
                    columns_json CLOB NOT NULL,
                    repairable BOOLEAN NOT NULL,
                    last_outcome VARCHAR(32) NOT NULL,
                    finding_status VARCHAR(32) NOT NULL,
                    occurrence_count BIGINT NOT NULL,
                    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    resolution VARCHAR(64) NOT NULL,
                    resolved_at TIMESTAMP WITH TIME ZONE,
                    claim_owner VARCHAR(255) NOT NULL,
                    claim_token VARCHAR(255) NOT NULL,
                    claim_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    claim_request_id VARCHAR(255) NOT NULL DEFAULT '',
                    claim_request_fingerprint VARCHAR(128) NOT NULL DEFAULT '',
                    resolution_request_id VARCHAR(255) NOT NULL DEFAULT '',
                    resolution_request_fingerprint VARCHAR(128) NOT NULL DEFAULT '',
                    resolution_owner VARCHAR(255) NOT NULL DEFAULT '',
                    resolution_claim_version BIGINT NOT NULL DEFAULT 0,
                    finding_version BIGINT NOT NULL,
                    PRIMARY KEY (entity_type, row_id)
                )
                """);
        migrateFindingActionReceipts();
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_projection_finding_queue
                ON rg_test_bloge_projection_findings
                    (finding_status, claim_until, last_seen_at, entity_type, row_id)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_projection_finding_retention
                ON rg_test_bloge_projection_findings
                    (finding_status, resolved_at, entity_type, row_id)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_bloge_projection_finding_archive (
                    archive_id VARCHAR(36) PRIMARY KEY,
                    entity_type VARCHAR(32) NOT NULL,
                    row_id VARCHAR(512) NOT NULL,
                    finding_kind VARCHAR(64) NOT NULL,
                    columns_json CLOB NOT NULL,
                    repairable BOOLEAN NOT NULL,
                    last_outcome VARCHAR(32) NOT NULL,
                    occurrence_count BIGINT NOT NULL,
                    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    resolution VARCHAR(64) NOT NULL,
                    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    source_version BIGINT NOT NULL,
                    record_fingerprint VARCHAR(128) NOT NULL,
                    archived_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_projection_finding_archive_retention
                ON rg_test_bloge_projection_finding_archive (archived_at, archive_id)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_projection_finding_archive_key
                ON rg_test_bloge_projection_finding_archive
                    (entity_type, row_id, archived_at)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_bloge_projection_retention (
                    job_name VARCHAR(128) PRIMARY KEY,
                    lease_owner VARCHAR(255) NOT NULL,
                    lease_token VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    revision BIGINT NOT NULL,
                    total_archived BIGINT NOT NULL,
                    total_purged BIGINT NOT NULL,
                    last_success_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        initializeSweepState();
        initializeRetentionState();
    }

    /**
     * Claims and processes one bounded page, or reports that another replica owns the sweep.
     *
     * @param pageSizePerEntity requested execution and work-item rows, normalized to 1..1000
     * @param repairMode audit-only or safe derived-projection repair
     * @return completed aggregate or lease-busy result
     */
    public SweepAttempt reconcilePage(
            int pageSizePerEntity,
            DurableStateProjectionReconciler.RepairMode repairMode) {
        Optional<SweepLease> lease = acquireSweepLease();
        if (lease.isEmpty()) {
            return SweepAttempt.busy();
        }
        try {
            return reconcileClaimedPage(lease.orElseThrow(), pageSizePerEntity, repairMode);
        } catch (RuntimeException failure) {
            try {
                releaseSweepLease(lease.orElseThrow());
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    /**
     * Returns a payload-free snapshot of the durable cursor and current sweep ownership.
     *
     * @return current durable control state
     */
    public ControlSnapshot snapshot() {
        return requireSweepState(false).snapshot();
    }

    /**
     * Lists durable findings, including currently resolved rows, without claim tokens or payloads.
     *
     * @param limit requested result bound, normalized to 1..1000
     * @return newest findings first
     */
    public List<FindingRecord> findings(int limit) {
        return jdbc.query("""
                        SELECT entity_type, row_id, finding_kind, columns_json, repairable,
                               last_outcome, finding_status, occurrence_count, first_seen_at,
                               last_seen_at, resolution, resolved_at, claim_owner, claim_token,
                               claim_until, claim_request_id, claim_request_fingerprint,
                               resolution_request_id, resolution_request_fingerprint,
                               resolution_owner, resolution_claim_version, finding_version
                        FROM rg_test_bloge_projection_findings
                        ORDER BY last_seen_at DESC, entity_type, row_id
                        LIMIT ?
                        """, this::mapFinding, bounded(limit, MAX_FINDING_PAGE_SIZE));
    }

    /**
     * Lists open or database-clock-expired findings that an operational worker may claim.
     *
     * @param limit requested result bound, normalized to 1..1000
     * @return payload-free actionable queue page
     */
    public List<FindingRecord> actionableFindings(int limit) {
        return jdbc.query("""
                        SELECT entity_type, row_id, finding_kind, columns_json, repairable,
                               last_outcome, finding_status, occurrence_count, first_seen_at,
                               last_seen_at, resolution, resolved_at, claim_owner, claim_token,
                               claim_until, claim_request_id, claim_request_fingerprint,
                               resolution_request_id, resolution_request_fingerprint,
                               resolution_owner, resolution_claim_version, finding_version
                        FROM rg_test_bloge_projection_findings
                        WHERE finding_status = 'OPEN'
                           OR (finding_status = 'CLAIMED' AND claim_until <= CURRENT_TIMESTAMP)
                        ORDER BY last_seen_at, entity_type, row_id
                        LIMIT ?
                        """, this::mapFinding, bounded(limit, MAX_FINDING_PAGE_SIZE));
    }

    /**
     * Lists payload-free archived finding lifecycles without operational claim or request secrets.
     *
     * @param limit requested result bound, normalized to 1..1000
     * @return newest archive snapshots first
     */
    public List<ArchivedFindingRecord> archivedFindings(int limit) {
        return jdbc.query("""
                        SELECT archive_id, entity_type, row_id, finding_kind, columns_json,
                               repairable, last_outcome, occurrence_count, first_seen_at,
                               last_seen_at, resolution, resolved_at, source_version,
                               record_fingerprint, archived_at
                        FROM rg_test_bloge_projection_finding_archive
                        ORDER BY archived_at DESC, archive_id
                        LIMIT ?
                        """, this::mapArchivedFinding,
                bounded(limit, MAX_FINDING_PAGE_SIZE));
    }

    /**
     * Returns one transactionally consistent retention-control and archive-size snapshot.
     *
     * <p>The snapshot is payload-free and suitable for a later metrics adapter. It does not expose
     * retention lease tokens, finding claim tokens, caller request IDs, or authority values.</p>
     *
     * @return current retention ownership, cumulative counters, and oldest retained timestamps
     */
    public RetentionSnapshot retentionSnapshot() {
        RetentionSnapshot result = transactions.execute(status -> {
            RetentionState state = requireRetentionState(false);
            Long archiveSize = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM rg_test_bloge_projection_finding_archive
                    """, Long.class);
            Timestamp oldestResolved = jdbc.queryForObject("""
                    SELECT MIN(resolved_at) FROM rg_test_bloge_projection_findings
                    WHERE finding_status = 'RESOLVED'
                    """, Timestamp.class);
            Timestamp oldestArchived = jdbc.queryForObject("""
                    SELECT MIN(archived_at) FROM rg_test_bloge_projection_finding_archive
                    """, Timestamp.class);
            return state.snapshot(
                    archiveSize == null ? 0L : archiveSize,
                    oldestResolved == null ? null : oldestResolved.toInstant(),
                    oldestArchived == null ? null : oldestArchived.toInstant());
        });
        return requiredTransactionResult(result, "finding retention snapshot");
    }

    /**
     * Claims and applies one bounded resolved-finding archive and archive-purge page.
     *
     * <p>Eligibility uses the database clock. Each source snapshot insert and exact source-row
     * delete share one transaction with archive purges and cumulative counters. A failed archive
     * write therefore leaves the source finding and all counters unchanged. Another replica may
     * take over only after the committed retention lease expires.</p>
     *
     * @param resolvedRetention time a resolved finding remains in the active owner queue
     * @param archiveRetention time an archived snapshot remains before bounded deletion
     * @param pageSize maximum source archives and maximum archive purges in this attempt
     * @return completed aggregate or lease-busy result
     */
    public RetentionAttempt retainFindings(
            Duration resolvedRetention,
            Duration archiveRetention,
            int pageSize) {
        Duration safeResolvedRetention = boundedRetention(
                resolvedRetention, MIN_RESOLVED_RETENTION, "Resolved finding retention");
        Duration safeArchiveRetention = boundedRetention(
                archiveRetention, MIN_ARCHIVE_RETENTION, "Finding archive retention");
        int safePageSize = bounded(pageSize, MAX_FINDING_PAGE_SIZE);
        Optional<RetentionLease> lease = acquireRetentionLease();
        if (lease.isEmpty()) {
            return RetentionAttempt.busy();
        }
        try {
            return retainClaimedFindings(lease.orElseThrow(), safeResolvedRetention,
                    safeArchiveRetention, safePageSize);
        } catch (RuntimeException failure) {
            try {
                releaseRetentionLease(lease.orElseThrow());
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    /**
     * Claims one actionable finding with a server-minted token and database-clock lease.
     *
     * @param key payload-free row identity
     * @param claimOwner operational worker identity
     * @param claimDuration requested claim lease, bounded to one second through one hour
     * @return exact claim fence, or empty when resolved or actively owned
     */
    public Optional<FindingClaim> claimFinding(
            DurableStateProjectionReconciler.EntityKey key,
            String claimOwner,
            Duration claimDuration) {
        FindingClaimResult result = claimFinding(key, claimOwner, UUID.randomUUID().toString(),
                claimDuration, ignored -> TestRuntimeTransactionMutation.noop());
        return Optional.ofNullable(result.claim());
    }

    /**
     * Idempotently claims one actionable finding and commits its companion audit mutation.
     *
     * <p>The request ID and canonical fingerprint are retained with the claim. An exact retry
     * receives the original server-minted fence without another audit write; reuse of the request
     * ID with changed facts is rejected. The supplied audit mutation executes after the fenced
     * state update on the same transaction-bound JDBC connection.</p>
     *
     * @param key payload-free row identity
     * @param claimOwner verified operational identity, never a client-selected owner
     * @param clientRequestId caller-generated idempotency key
     * @param claimDuration requested database-clock lease, from one second through one hour
     * @param committedAudit creates the append-only mutation for a newly committed claim
     * @return explicit claim, replay, conflict, or non-actionable disposition
     */
    public FindingClaimResult claimFinding(
            DurableStateProjectionReconciler.EntityKey key,
            String claimOwner,
            String clientRequestId,
            Duration claimDuration,
            Function<FindingClaim, TestRuntimeTransactionMutation> committedAudit) {
        DurableStateProjectionReconciler.EntityKey safeKey = Objects.requireNonNull(key, "key");
        String safeOwner = required(claimOwner, "Finding claim owner", 255);
        String safeRequestId = required(clientRequestId, "Finding claim request ID", 255);
        Duration safeDuration = boundedLease(claimDuration, "Finding claim duration");
        Function<FindingClaim, TestRuntimeTransactionMutation> safeAudit =
                Objects.requireNonNull(committedAudit, "committedAudit");
        String fingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                "entityType", safeKey.entityType().name(),
                "rowId", safeKey.rowId(),
                "claimOwner", safeOwner,
                "claimDurationMillis", safeDuration.toMillis()));
        FindingClaimResult result = transactions.execute(status -> {
            FindingRow current = findFinding(safeKey, true).orElse(null);
            if (current == null || current.status() == FindingStatus.RESOLVED) {
                return FindingClaimResult.notActionable();
            }
            Instant now = databaseNow();
            if (current.claimRequestId().equals(safeRequestId)) {
                if (!current.claimRequestFingerprint().equals(fingerprint)) {
                    return FindingClaimResult.conflict();
                }
                if (current.status() == FindingStatus.CLAIMED
                        && current.claimOwner().equals(safeOwner)) {
                    return FindingClaimResult.replay(current.claim());
                }
            }
            if (current.status() == FindingStatus.CLAIMED
                    && current.claimUntil().isAfter(now)) {
                return FindingClaimResult.notActionable();
            }
            String token = UUID.randomUUID().toString();
            Instant claimUntil = now.plus(safeDuration);
            long version = Math.addExact(current.version(), 1);
            int updated = jdbc.update("""
                    UPDATE rg_test_bloge_projection_findings
                    SET finding_status = 'CLAIMED', claim_owner = ?, claim_token = ?,
                        claim_until = ?, claim_request_id = ?, claim_request_fingerprint = ?,
                        resolution_request_id = '', resolution_request_fingerprint = '',
                        resolution_owner = '', resolution_claim_version = 0,
                        finding_version = ?
                    WHERE entity_type = ? AND row_id = ? AND finding_version = ?
                    """, safeOwner, token, Timestamp.from(claimUntil), safeRequestId, fingerprint,
                    version,
                    safeKey.entityType().name(), safeKey.rowId(), current.version());
            if (updated != 1) {
                throw new IllegalStateException("Projection finding claim fence was rejected");
            }
            FindingClaim claim = new FindingClaim(
                    safeKey, safeOwner, token, version, claimUntil);
            Objects.requireNonNull(safeAudit.apply(claim), "committedAudit result").apply(jdbc);
            return FindingClaimResult.claimed(claim);
        });
        return requiredTransactionResult(result, "finding claim");
    }

    /**
     * Resolves a finding only when the exact owner, token, version, and live database lease match.
     *
     * @param claim exact claim returned by {@link #claimFinding}
     * @param resolution manual operational resolution
     * @return true when resolved; false for stale, forged, expired, or already-consumed fences
     */
    public boolean resolveFinding(FindingClaim claim, Resolution resolution) {
        FindingResolutionResult result = resolveFinding(claim, UUID.randomUUID().toString(),
                resolution, ignored -> TestRuntimeTransactionMutation.noop());
        return result.disposition() == ResolutionDisposition.RESOLVED;
    }

    /**
     * Idempotently resolves an exact live claim and commits its companion audit mutation.
     *
     * <p>An exact retry returns the persisted resolution receipt. A reused request ID with changed
     * claim or resolution facts is rejected independently of fencing. Claim tokens are consumed by
     * the comparison but only a canonical fingerprint is retained as the idempotency receipt.</p>
     *
     * @param claim exact server-issued owner, token, version, and lease fence
     * @param clientRequestId caller-generated idempotency key
     * @param resolution manual operational resolution
     * @param committedAudit creates the append-only mutation for a newly committed resolution
     * @return explicit resolved, replay, conflict, or fence-rejected disposition
     */
    public FindingResolutionResult resolveFinding(
            FindingClaim claim,
            String clientRequestId,
            Resolution resolution,
            Function<FindingResolution, TestRuntimeTransactionMutation> committedAudit) {
        FindingClaim safeClaim = Objects.requireNonNull(claim, "claim");
        String safeRequestId = required(clientRequestId, "Finding resolution request ID", 255);
        Resolution safeResolution = Objects.requireNonNull(resolution, "resolution");
        Function<FindingResolution, TestRuntimeTransactionMutation> safeAudit =
                Objects.requireNonNull(committedAudit, "committedAudit");
        if (!safeResolution.manual()) {
            throw new IllegalArgumentException("A manual finding resolution is required");
        }
        String fingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                "entityType", safeClaim.key().entityType().name(),
                "rowId", safeClaim.key().rowId(),
                "ownerId", safeClaim.ownerId(),
                "claimToken", safeClaim.claimToken(),
                "claimVersion", safeClaim.version(),
                "claimUntil", safeClaim.claimUntil(),
                "resolution", safeResolution.name()));
        FindingResolutionResult result = transactions.execute(status -> {
            FindingRow current = findFinding(safeClaim.key(), true).orElse(null);
            Instant now = databaseNow();
            if (current != null && current.resolutionRequestId().equals(safeRequestId)) {
                if (!current.resolutionRequestFingerprint().equals(fingerprint)) {
                    return FindingResolutionResult.conflict();
                }
                if (current.status() == FindingStatus.RESOLVED
                        && current.resolutionOwner().equals(safeClaim.ownerId())
                        && current.resolutionClaimVersion() == safeClaim.version()
                        && current.resolution() == safeResolution) {
                    return FindingResolutionResult.replay(current.findingResolution());
                }
            }
            if (current == null
                    || current.status() != FindingStatus.CLAIMED
                    || !current.claimOwner().equals(safeClaim.ownerId())
                    || !current.claimToken().equals(safeClaim.claimToken())
                    || current.version() != safeClaim.version()
                    || !current.claimUntil().equals(safeClaim.claimUntil())
                    || !current.claimUntil().isAfter(now)) {
                return FindingResolutionResult.fenceRejected();
            }
            long version = Math.addExact(current.version(), 1);
            int updated = jdbc.update("""
                    UPDATE rg_test_bloge_projection_findings
                    SET finding_status = 'RESOLVED', resolution = ?, resolved_at = ?,
                        claim_owner = '', claim_token = '', claim_until = ?,
                        resolution_request_id = ?, resolution_request_fingerprint = ?,
                        resolution_owner = ?, resolution_claim_version = ?,
                        finding_version = ?
                    WHERE entity_type = ? AND row_id = ? AND finding_status = 'CLAIMED'
                      AND claim_owner = ? AND claim_token = ? AND finding_version = ?
                      AND claim_until > CURRENT_TIMESTAMP
                    """, safeResolution.name(), Timestamp.from(now), Timestamp.from(Instant.EPOCH),
                    safeRequestId, fingerprint, safeClaim.ownerId(), safeClaim.version(), version,
                    safeClaim.key().entityType().name(),
                    safeClaim.key().rowId(), safeClaim.ownerId(), safeClaim.claimToken(),
                    safeClaim.version());
            if (updated != 1) {
                return FindingResolutionResult.fenceRejected();
            }
            FindingResolution committed = new FindingResolution(safeClaim.key(),
                    safeClaim.ownerId(), safeResolution, version, now);
            Objects.requireNonNull(safeAudit.apply(committed), "committedAudit result").apply(jdbc);
            return FindingResolutionResult.resolved(committed);
        });
        return requiredTransactionResult(result, "finding resolution");
    }

    Optional<RetentionLease> acquireRetentionLease() {
        Optional<RetentionLease> result = transactions.execute(status -> {
            RetentionState current = requireRetentionState(true);
            Instant now = databaseNow();
            if (current.leaseUntil().isAfter(now)) {
                return Optional.empty();
            }
            long epoch = Math.addExact(current.leaseEpoch(), 1);
            long revision = Math.addExact(current.revision(), 1);
            String token = UUID.randomUUID().toString();
            Instant leaseUntil = now.plus(sweepLeaseDuration);
            int updated = jdbc.update("""
                    UPDATE rg_test_bloge_projection_retention
                    SET lease_owner = ?, lease_token = ?, lease_epoch = ?, lease_until = ?,
                        revision = ?, updated_at = ?
                    WHERE job_name = ? AND revision = ?
                    """, ownerId, token, epoch, Timestamp.from(leaseUntil), revision,
                    Timestamp.from(now), RETENTION_JOB_NAME, current.revision());
            if (updated != 1) {
                throw new IllegalStateException("Projection retention lease fence was rejected");
            }
            return Optional.of(new RetentionLease(ownerId, token, epoch, leaseUntil));
        });
        return requiredTransactionResult(result, "finding retention lease acquisition");
    }

    RetentionAttempt retainClaimedFindings(
            RetentionLease lease,
            Duration resolvedRetention,
            Duration archiveRetention,
            int pageSize) {
        RetentionLease safeLease = Objects.requireNonNull(lease, "lease");
        Duration safeResolvedRetention = boundedRetention(
                resolvedRetention, MIN_RESOLVED_RETENTION, "Resolved finding retention");
        Duration safeArchiveRetention = boundedRetention(
                archiveRetention, MIN_ARCHIVE_RETENTION, "Finding archive retention");
        int safePageSize = bounded(pageSize, MAX_FINDING_PAGE_SIZE);
        RetentionAttempt result = transactions.execute(status -> {
            RetentionState state = requireRetentionState(true);
            Instant startedAt = databaseNow();
            requireLiveRetentionFence(state, safeLease, startedAt);
            Instant resolvedCutoff = startedAt.minus(safeResolvedRetention);
            Instant archiveCutoff = startedAt.minus(safeArchiveRetention);
            List<FindingRow> eligible = jdbc.query("""
                            SELECT entity_type, row_id, finding_kind, columns_json, repairable,
                                   last_outcome, finding_status, occurrence_count, first_seen_at,
                                   last_seen_at, resolution, resolved_at, claim_owner, claim_token,
                                   claim_until, claim_request_id, claim_request_fingerprint,
                                   resolution_request_id, resolution_request_fingerprint,
                                   resolution_owner, resolution_claim_version, finding_version
                            FROM rg_test_bloge_projection_findings
                            WHERE finding_status = 'RESOLVED' AND resolved_at IS NOT NULL
                              AND resolved_at <= ?
                            ORDER BY resolved_at, entity_type, row_id
                            LIMIT ? FOR UPDATE
                            """, this::mapFindingRow,
                    Timestamp.from(resolvedCutoff), safePageSize);
            int archived = 0;
            for (FindingRow finding : eligible) {
                archiveFinding(finding, startedAt);
                archived = Math.addExact(archived, 1);
            }
            List<ArchivedFindingRecord> expiredArchives = jdbc.query("""
                    SELECT archive_id, entity_type, row_id, finding_kind, columns_json,
                           repairable, last_outcome, occurrence_count, first_seen_at,
                           last_seen_at, resolution, resolved_at, source_version,
                           record_fingerprint, archived_at
                    FROM rg_test_bloge_projection_finding_archive
                    WHERE archived_at <= ?
                    ORDER BY archived_at, archive_id
                    LIMIT ? FOR UPDATE
                    """, this::mapArchivedFinding,
                    Timestamp.from(archiveCutoff), safePageSize);
            int purged = 0;
            for (ArchivedFindingRecord archive : expiredArchives) {
                int deleted = jdbc.update("""
                        DELETE FROM rg_test_bloge_projection_finding_archive
                        WHERE archive_id = ? AND archived_at = ? AND record_fingerprint = ?
                          AND archived_at <= ?
                        """, archive.archiveId(), Timestamp.from(archive.archivedAt()),
                        archive.recordFingerprint(), Timestamp.from(archiveCutoff));
                if (deleted != 1) {
                    throw new IllegalStateException(
                            "Projection finding archive purge fence was rejected");
                }
                purged = Math.addExact(purged, 1);
            }
            Instant completedAt = databaseNow();
            requireLiveRetentionFence(state, safeLease, completedAt);
            long revision = Math.addExact(state.revision(), 1);
            long totalArchived = Math.addExact(state.totalArchived(), archived);
            long totalPurged = Math.addExact(state.totalPurged(), purged);
            int updated = jdbc.update("""
                    UPDATE rg_test_bloge_projection_retention
                    SET lease_owner = '', lease_token = '', lease_until = ?, revision = ?,
                        total_archived = ?, total_purged = ?, last_success_at = ?, updated_at = ?
                    WHERE job_name = ? AND lease_owner = ? AND lease_token = ?
                      AND lease_epoch = ? AND revision = ?
                    """, Timestamp.from(Instant.EPOCH), revision, totalArchived, totalPurged,
                    Timestamp.from(completedAt), Timestamp.from(completedAt), RETENTION_JOB_NAME,
                    safeLease.ownerId(), safeLease.token(), safeLease.epoch(), state.revision());
            if (updated != 1) {
                throw new IllegalStateException(
                        "Projection finding retention checkpoint fence was rejected");
            }
            return RetentionAttempt.completed(
                    new RetentionResult(archived, purged, completedAt));
        });
        return requiredTransactionResult(result, "claimed finding retention");
    }

    private void archiveFinding(FindingRow finding, Instant archivedAt) {
        if (finding.status() != FindingStatus.RESOLVED
                || finding.resolvedAt() == null
                || finding.resolution() == Resolution.NONE
                || !finding.claimOwner().isBlank()
                || !finding.claimToken().isBlank()) {
            throw new IllegalStateException(
                    "Only token-free resolved projection findings may be archived");
        }
        String archiveId = UUID.randomUUID().toString();
        String recordFingerprint = ProtocolFingerprint.of(
                objectMapper, archiveFingerprintMaterial(finding, archiveId, archivedAt));
        int inserted = jdbc.update("""
                INSERT INTO rg_test_bloge_projection_finding_archive (
                    archive_id, entity_type, row_id, finding_kind, columns_json, repairable,
                    last_outcome, occurrence_count, first_seen_at, last_seen_at, resolution,
                    resolved_at, source_version, record_fingerprint, archived_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, archiveId, finding.key().entityType().name(), finding.key().rowId(),
                finding.kind().name(), writeColumns(finding.columns()), finding.repairable(),
                finding.outcome().name(), finding.occurrences(),
                Timestamp.from(finding.firstSeenAt()), Timestamp.from(finding.lastSeenAt()),
                finding.resolution().name(), Timestamp.from(finding.resolvedAt()),
                finding.version(), recordFingerprint, Timestamp.from(archivedAt));
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Projection finding archive insert was not acknowledged");
        }
        int deleted = jdbc.update("""
                DELETE FROM rg_test_bloge_projection_findings
                WHERE entity_type = ? AND row_id = ? AND finding_status = 'RESOLVED'
                  AND resolved_at = ? AND finding_version = ?
                """, finding.key().entityType().name(), finding.key().rowId(),
                Timestamp.from(finding.resolvedAt()), finding.version());
        if (deleted != 1) {
            throw new IllegalStateException(
                    "Projection finding archive source fence was rejected");
        }
    }

    private Map<String, Object> archiveFingerprintMaterial(
            FindingRow finding,
            String archiveId,
            Instant archivedAt) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", "bloge.projectionFindingArchive.v1");
        material.put("archiveId", archiveId);
        material.put("entityType", finding.key().entityType().name());
        material.put("rowId", finding.key().rowId());
        material.put("findingKind", finding.kind().name());
        material.put("columns", finding.columns());
        material.put("repairable", finding.repairable());
        material.put("lastOutcome", finding.outcome().name());
        material.put("occurrenceCount", finding.occurrences());
        material.put("firstSeenAt", finding.firstSeenAt());
        material.put("lastSeenAt", finding.lastSeenAt());
        material.put("resolution", finding.resolution().name());
        material.put("resolvedAt", finding.resolvedAt());
        material.put("sourceVersion", finding.version());
        material.put("archivedAt", archivedAt);
        return material;
    }

    private Map<String, Object> archiveFingerprintMaterial(ArchivedFindingRecord finding) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", "bloge.projectionFindingArchive.v1");
        material.put("archiveId", finding.archiveId());
        material.put("entityType", finding.key().entityType().name());
        material.put("rowId", finding.key().rowId());
        material.put("findingKind", finding.kind().name());
        material.put("columns", finding.columns());
        material.put("repairable", finding.repairable());
        material.put("lastOutcome", finding.outcome().name());
        material.put("occurrenceCount", finding.occurrences());
        material.put("firstSeenAt", finding.firstSeenAt());
        material.put("lastSeenAt", finding.lastSeenAt());
        material.put("resolution", finding.resolution().name());
        material.put("resolvedAt", finding.resolvedAt());
        material.put("sourceVersion", finding.sourceVersion());
        material.put("archivedAt", finding.archivedAt());
        return material;
    }

    String archiveRecordFingerprint(ArchivedFindingRecord finding) {
        return ProtocolFingerprint.of(
                objectMapper,
                archiveFingerprintMaterial(Objects.requireNonNull(finding, "finding")));
    }

    Optional<SweepLease> acquireSweepLease() {
        Optional<SweepLease> result = transactions.execute(status -> {
            SweepState current = requireSweepState(true);
            Instant now = databaseNow();
            if (current.leaseUntil().isAfter(now)) {
                return Optional.empty();
            }
            long epoch = Math.addExact(current.leaseEpoch(), 1);
            long revision = Math.addExact(current.revision(), 1);
            String token = UUID.randomUUID().toString();
            Instant leaseUntil = now.plus(sweepLeaseDuration);
            int updated = jdbc.update("""
                    UPDATE rg_test_bloge_projection_sweep
                    SET lease_owner = ?, lease_token = ?, lease_epoch = ?, lease_until = ?,
                        revision = ?, updated_at = ?
                    WHERE job_name = ? AND revision = ?
                    """, ownerId, token, epoch, Timestamp.from(leaseUntil), revision,
                    Timestamp.from(now), JOB_NAME, current.revision());
            if (updated != 1) {
                throw new IllegalStateException("Projection sweep lease fence was rejected");
            }
            return Optional.of(new SweepLease(ownerId, token, epoch, leaseUntil));
        });
        return requiredTransactionResult(result, "sweep lease acquisition");
    }

    SweepAttempt reconcileClaimedPage(
            SweepLease lease,
            int pageSizePerEntity,
            DurableStateProjectionReconciler.RepairMode repairMode) {
        SweepLease safeLease = Objects.requireNonNull(lease, "lease");
        DurableStateProjectionReconciler.RepairMode safeMode =
                Objects.requireNonNull(repairMode, "repairMode");
        SweepAttempt result = transactions.execute(status -> {
            SweepState state = requireSweepState(true);
            requireLiveFence(state, safeLease, databaseNow());
            DurableStateProjectionReconciler.SweepResult sweep = reconciler.sweep(
                    state.cursor(), bounded(pageSizePerEntity, MAX_PAGE_SIZE), safeMode);
            Instant completedAt = databaseNow();
            requireLiveFence(state, safeLease, completedAt);
            reconcileFindings(sweep, completedAt);
            long revision = Math.addExact(state.revision(), 1);
            int updated = jdbc.update("""
                    UPDATE rg_test_bloge_projection_sweep
                    SET after_execution_id = ?, after_work_item_id = ?,
                        lease_owner = '', lease_token = '', lease_until = ?, revision = ?,
                        last_success_at = ?, updated_at = ?
                    WHERE job_name = ? AND lease_owner = ? AND lease_token = ?
                      AND lease_epoch = ? AND revision = ?
                    """, sweep.nextCursor().afterExecutionId(),
                    sweep.nextCursor().afterWorkItemId(), Timestamp.from(Instant.EPOCH), revision,
                    Timestamp.from(completedAt), Timestamp.from(completedAt), JOB_NAME,
                    safeLease.ownerId(), safeLease.token(), safeLease.epoch(), state.revision());
            if (updated != 1) {
                throw new IllegalStateException("Projection sweep checkpoint fence was rejected");
            }
            return SweepAttempt.completed(sweep);
        });
        return requiredTransactionResult(result, "claimed projection sweep");
    }

    private void reconcileFindings(
            DurableStateProjectionReconciler.SweepResult sweep,
            Instant now) {
        Map<DurableStateProjectionReconciler.EntityKey,
                DurableStateProjectionReconciler.Finding> findingByKey = new LinkedHashMap<>();
        for (DurableStateProjectionReconciler.Finding finding : sweep.findings()) {
            DurableStateProjectionReconciler.EntityKey key =
                    new DurableStateProjectionReconciler.EntityKey(
                            finding.entityType(), finding.rowId());
            if (findingByKey.put(key, finding) != null) {
                throw new IllegalStateException("Projection sweep emitted a duplicate finding key");
            }
        }
        LinkedHashSet<DurableStateProjectionReconciler.EntityKey> inspected =
                new LinkedHashSet<>();
        for (DurableStateProjectionReconciler.EntityKey key : sweep.inspectedRows()) {
            if (!inspected.add(key)) {
                throw new IllegalStateException(
                        "Projection sweep emitted a duplicate inspected row key");
            }
            DurableStateProjectionReconciler.Finding finding = findingByKey.get(key);
            if (finding == null) {
                resolveConsistent(key, now);
            } else {
                upsertFinding(key, finding, now);
            }
        }
        if (!inspected.containsAll(findingByKey.keySet())) {
            throw new IllegalStateException(
                    "Projection sweep emitted a finding outside its inspected row set");
        }
    }

    private void upsertFinding(
            DurableStateProjectionReconciler.EntityKey key,
            DurableStateProjectionReconciler.Finding finding,
            Instant now) {
        FindingRow current = findFinding(key, true).orElse(null);
        boolean repaired = finding.outcome() == DurableStateProjectionReconciler.Outcome.REPAIRED;
        if (current == null) {
            try {
                jdbc.update("""
                        INSERT INTO rg_test_bloge_projection_findings (
                            entity_type, row_id, finding_kind, columns_json, repairable,
                            last_outcome, finding_status, occurrence_count, first_seen_at,
                            last_seen_at, resolution, resolved_at, claim_owner, claim_token,
                            claim_until, claim_request_id, claim_request_fingerprint,
                            resolution_request_id, resolution_request_fingerprint,
                            resolution_owner, resolution_claim_version, finding_version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', '', ?, '', '',
                                  '', '', '', 0, 0)
                        """, key.entityType().name(), key.rowId(), finding.kind().name(),
                        writeColumns(finding.columns()), finding.repairable(),
                        finding.outcome().name(), repaired ? FindingStatus.RESOLVED.name()
                                : FindingStatus.OPEN.name(),
                        1L, Timestamp.from(now), Timestamp.from(now),
                        repaired ? Resolution.AUTO_REPAIRED.name() : "",
                        repaired ? Timestamp.from(now) : null, Timestamp.from(Instant.EPOCH));
                return;
            } catch (DuplicateKeyException race) {
                current = findFinding(key, true).orElseThrow(() ->
                        new IllegalStateException("Projection finding insert race lost its row"));
            }
        }

        FindingStatus nextStatus = current.status();
        Resolution resolution = current.resolution();
        Instant resolvedAt = current.resolvedAt();
        String claimOwner = current.claimOwner();
        String claimToken = current.claimToken();
        Instant claimUntil = current.claimUntil();
        String claimRequestId = current.claimRequestId();
        String claimRequestFingerprint = current.claimRequestFingerprint();
        String resolutionRequestId = current.resolutionRequestId();
        String resolutionRequestFingerprint = current.resolutionRequestFingerprint();
        String resolutionOwner = current.resolutionOwner();
        long resolutionClaimVersion = current.resolutionClaimVersion();
        long version = current.version();
        boolean findingChanged = current.kind() != finding.kind()
                || !current.columns().equals(finding.columns())
                || current.repairable() != finding.repairable()
                || current.outcome() != finding.outcome();
        if (repaired) {
            nextStatus = FindingStatus.RESOLVED;
            resolution = Resolution.AUTO_REPAIRED;
            resolvedAt = now;
            claimOwner = "";
            claimToken = "";
            claimUntil = Instant.EPOCH;
            claimRequestId = "";
            claimRequestFingerprint = "";
            resolutionRequestId = "";
            resolutionRequestFingerprint = "";
            resolutionOwner = "";
            resolutionClaimVersion = 0;
            version = Math.addExact(version, 1);
        } else if (current.status() == FindingStatus.RESOLVED
                || current.status() == FindingStatus.CLAIMED
                && (!current.claimUntil().isAfter(now) || findingChanged)) {
            nextStatus = FindingStatus.OPEN;
            resolution = Resolution.NONE;
            resolvedAt = null;
            claimOwner = "";
            claimToken = "";
            claimUntil = Instant.EPOCH;
            claimRequestId = "";
            claimRequestFingerprint = "";
            resolutionRequestId = "";
            resolutionRequestFingerprint = "";
            resolutionOwner = "";
            resolutionClaimVersion = 0;
            version = Math.addExact(version, 1);
        }
        int updated = jdbc.update("""
                UPDATE rg_test_bloge_projection_findings
                SET finding_kind = ?, columns_json = ?, repairable = ?, last_outcome = ?,
                    finding_status = ?, occurrence_count = ?, last_seen_at = ?, resolution = ?,
                    resolved_at = ?, claim_owner = ?, claim_token = ?, claim_until = ?,
                    claim_request_id = ?, claim_request_fingerprint = ?,
                    resolution_request_id = ?, resolution_request_fingerprint = ?,
                    resolution_owner = ?, resolution_claim_version = ?,
                    finding_version = ?
                WHERE entity_type = ? AND row_id = ? AND finding_version = ?
                """, finding.kind().name(), writeColumns(finding.columns()), finding.repairable(),
                finding.outcome().name(), nextStatus.name(),
                Math.addExact(current.occurrences(), 1), Timestamp.from(now),
                resolution == Resolution.NONE ? "" : resolution.name(),
                timestamp(resolvedAt), claimOwner, claimToken, Timestamp.from(claimUntil),
                claimRequestId, claimRequestFingerprint, resolutionRequestId,
                resolutionRequestFingerprint, resolutionOwner, resolutionClaimVersion, version,
                key.entityType().name(), key.rowId(), current.version());
        if (updated != 1) {
            throw new IllegalStateException("Projection finding update fence was rejected");
        }
    }

    private void resolveConsistent(
            DurableStateProjectionReconciler.EntityKey key,
            Instant now) {
        FindingRow current = findFinding(key, true).orElse(null);
        if (current == null || current.status() == FindingStatus.RESOLVED) {
            return;
        }
        int updated = jdbc.update("""
                UPDATE rg_test_bloge_projection_findings
                SET finding_status = 'RESOLVED', resolution = ?, resolved_at = ?,
                    claim_owner = '', claim_token = '', claim_until = ?,
                    claim_request_id = '', claim_request_fingerprint = '',
                    resolution_request_id = '', resolution_request_fingerprint = '',
                    resolution_owner = '', resolution_claim_version = 0, finding_version = ?
                WHERE entity_type = ? AND row_id = ? AND finding_version = ?
                """, Resolution.CONSISTENT_ON_RECHECK.name(), Timestamp.from(now),
                Timestamp.from(Instant.EPOCH), Math.addExact(current.version(), 1),
                key.entityType().name(), key.rowId(), current.version());
        if (updated != 1) {
            throw new IllegalStateException("Projection finding recheck fence was rejected");
        }
    }

    private boolean releaseSweepLease(SweepLease lease) {
        Boolean result = transactions.execute(status -> {
            Instant now = databaseNow();
            int updated = jdbc.update("""
                    UPDATE rg_test_bloge_projection_sweep
                    SET lease_owner = '', lease_token = '', lease_until = ?,
                        revision = revision + 1, updated_at = ?
                    WHERE job_name = ? AND lease_owner = ? AND lease_token = ? AND lease_epoch = ?
                    """, Timestamp.from(Instant.EPOCH), Timestamp.from(now), JOB_NAME,
                    lease.ownerId(), lease.token(), lease.epoch());
            return updated == 1;
        });
        return Boolean.TRUE.equals(requiredTransactionResult(result, "sweep lease release"));
    }

    private boolean releaseRetentionLease(RetentionLease lease) {
        Boolean result = transactions.execute(status -> {
            Instant now = databaseNow();
            int updated = jdbc.update("""
                    UPDATE rg_test_bloge_projection_retention
                    SET lease_owner = '', lease_token = '', lease_until = ?,
                        revision = revision + 1, updated_at = ?
                    WHERE job_name = ? AND lease_owner = ? AND lease_token = ? AND lease_epoch = ?
                    """, Timestamp.from(Instant.EPOCH), Timestamp.from(now), RETENTION_JOB_NAME,
                    lease.ownerId(), lease.token(), lease.epoch());
            return updated == 1;
        });
        return Boolean.TRUE.equals(requiredTransactionResult(
                result, "finding retention lease release"));
    }

    private void requireLiveFence(SweepState state, SweepLease lease, Instant now) {
        if (!state.leaseOwner().equals(lease.ownerId())
                || !state.leaseToken().equals(lease.token())
                || state.leaseEpoch() != lease.epoch()
                || !state.leaseUntil().isAfter(now)) {
            throw new IllegalStateException("Projection sweep fence is stale or expired");
        }
    }

    private void requireLiveRetentionFence(
            RetentionState state,
            RetentionLease lease,
            Instant now) {
        if (!state.leaseOwner().equals(lease.ownerId())
                || !state.leaseToken().equals(lease.token())
                || state.leaseEpoch() != lease.epoch()
                || !state.leaseUntil().isAfter(now)) {
            throw new IllegalStateException(
                    "Projection finding retention fence is stale or expired");
        }
    }

    private void initializeSweepState() {
        try {
            Instant now = databaseNow();
            jdbc.update("""
                    INSERT INTO rg_test_bloge_projection_sweep (
                        job_name, after_execution_id, after_work_item_id, lease_owner,
                        lease_token, lease_epoch, lease_until, revision, last_success_at, updated_at
                    ) VALUES (?, '', '', '', '', 0, ?, 0, NULL, ?)
                    """, JOB_NAME, Timestamp.from(Instant.EPOCH), Timestamp.from(now));
        } catch (DuplicateKeyException alreadyInitialized) {
            // Another replica created the singleton row first.
        }
    }

    private void initializeRetentionState() {
        try {
            Instant now = databaseNow();
            jdbc.update("""
                    INSERT INTO rg_test_bloge_projection_retention (
                        job_name, lease_owner, lease_token, lease_epoch, lease_until, revision,
                        total_archived, total_purged, last_success_at, updated_at
                    ) VALUES (?, '', '', 0, ?, 0, 0, 0, NULL, ?)
                    """, RETENTION_JOB_NAME, Timestamp.from(Instant.EPOCH), Timestamp.from(now));
        } catch (DuplicateKeyException alreadyInitialized) {
            // Another replica created the singleton row first.
        }
    }

    private void migrateFindingActionReceipts() {
        jdbc.execute("""
                ALTER TABLE rg_test_bloge_projection_findings
                ADD COLUMN IF NOT EXISTS claim_request_id VARCHAR(255) NOT NULL DEFAULT ''
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_bloge_projection_findings
                ADD COLUMN IF NOT EXISTS claim_request_fingerprint VARCHAR(128) NOT NULL DEFAULT ''
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_bloge_projection_findings
                ADD COLUMN IF NOT EXISTS resolution_request_id VARCHAR(255) NOT NULL DEFAULT ''
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_bloge_projection_findings
                ADD COLUMN IF NOT EXISTS resolution_request_fingerprint VARCHAR(128)
                    NOT NULL DEFAULT ''
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_bloge_projection_findings
                ADD COLUMN IF NOT EXISTS resolution_owner VARCHAR(255) NOT NULL DEFAULT ''
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_bloge_projection_findings
                ADD COLUMN IF NOT EXISTS resolution_claim_version BIGINT NOT NULL DEFAULT 0
                """);
    }

    private SweepState requireSweepState(boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("""
                        SELECT after_execution_id, after_work_item_id, lease_owner, lease_token,
                               lease_epoch, lease_until, revision, last_success_at, updated_at
                        FROM rg_test_bloge_projection_sweep WHERE job_name = ?
                        """ + suffix, this::mapSweepState, JOB_NAME).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Projection sweep state is not initialized"));
    }

    private RetentionState requireRetentionState(boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("""
                        SELECT lease_owner, lease_token, lease_epoch, lease_until, revision,
                               total_archived, total_purged, last_success_at, updated_at
                        FROM rg_test_bloge_projection_retention WHERE job_name = ?
                        """ + suffix, this::mapRetentionState, RETENTION_JOB_NAME).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Projection finding retention state is not initialized"));
    }

    private SweepState mapSweepState(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SweepState(
                new DurableStateProjectionReconciler.ScanCursor(
                        resultSet.getString("after_execution_id"),
                        resultSet.getString("after_work_item_id")),
                resultSet.getString("lease_owner"), resultSet.getString("lease_token"),
                resultSet.getLong("lease_epoch"), instant(resultSet, "lease_until"),
                resultSet.getLong("revision"), nullableInstant(resultSet, "last_success_at"),
                instant(resultSet, "updated_at"));
    }

    private RetentionState mapRetentionState(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        return new RetentionState(
                resultSet.getString("lease_owner"), resultSet.getString("lease_token"),
                resultSet.getLong("lease_epoch"), instant(resultSet, "lease_until"),
                resultSet.getLong("revision"), resultSet.getLong("total_archived"),
                resultSet.getLong("total_purged"),
                nullableInstant(resultSet, "last_success_at"),
                instant(resultSet, "updated_at"));
    }

    private Optional<FindingRow> findFinding(
            DurableStateProjectionReconciler.EntityKey key,
            boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("""
                        SELECT entity_type, row_id, finding_kind, columns_json, repairable,
                               last_outcome, finding_status, occurrence_count, first_seen_at,
                               last_seen_at, resolution, resolved_at, claim_owner, claim_token,
                               claim_until, claim_request_id, claim_request_fingerprint,
                               resolution_request_id, resolution_request_fingerprint,
                               resolution_owner, resolution_claim_version, finding_version
                        FROM rg_test_bloge_projection_findings
                        WHERE entity_type = ? AND row_id = ?
                        """ + suffix, this::mapFindingRow,
                key.entityType().name(), key.rowId()).stream().findFirst();
    }

    private FindingRecord mapFinding(ResultSet resultSet, int rowNumber) throws SQLException {
        return mapFindingRow(resultSet, rowNumber).external();
    }

    private ArchivedFindingRecord mapArchivedFinding(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        ArchivedFindingRecord archive = new ArchivedFindingRecord(
                resultSet.getString("archive_id"),
                new DurableStateProjectionReconciler.EntityKey(
                        DurableStateProjectionReconciler.EntityType.valueOf(
                                resultSet.getString("entity_type")),
                        resultSet.getString("row_id")),
                DurableStateProjectionReconciler.FindingKind.valueOf(
                        resultSet.getString("finding_kind")),
                readColumns(resultSet.getString("columns_json")),
                resultSet.getBoolean("repairable"),
                DurableStateProjectionReconciler.Outcome.valueOf(
                        resultSet.getString("last_outcome")),
                resultSet.getLong("occurrence_count"),
                instant(resultSet, "first_seen_at"), instant(resultSet, "last_seen_at"),
                Resolution.valueOf(resultSet.getString("resolution")),
                instant(resultSet, "resolved_at"), resultSet.getLong("source_version"),
                resultSet.getString("record_fingerprint"), instant(resultSet, "archived_at"));
        String expected = archiveRecordFingerprint(archive);
        if (!expected.equals(archive.recordFingerprint())) {
            throw new IllegalStateException(
                    "Projection finding archive fingerprint verification failed");
        }
        return archive;
    }

    private FindingRow mapFindingRow(ResultSet resultSet, int rowNumber) throws SQLException {
        String resolution = resultSet.getString("resolution");
        return new FindingRow(
                new DurableStateProjectionReconciler.EntityKey(
                        DurableStateProjectionReconciler.EntityType.valueOf(
                                resultSet.getString("entity_type")),
                        resultSet.getString("row_id")),
                DurableStateProjectionReconciler.FindingKind.valueOf(
                        resultSet.getString("finding_kind")),
                readColumns(resultSet.getString("columns_json")),
                resultSet.getBoolean("repairable"),
                DurableStateProjectionReconciler.Outcome.valueOf(
                        resultSet.getString("last_outcome")),
                FindingStatus.valueOf(resultSet.getString("finding_status")),
                resultSet.getLong("occurrence_count"), instant(resultSet, "first_seen_at"),
                instant(resultSet, "last_seen_at"),
                resolution == null || resolution.isBlank()
                        ? Resolution.NONE : Resolution.valueOf(resolution),
                nullableInstant(resultSet, "resolved_at"), resultSet.getString("claim_owner"),
                resultSet.getString("claim_token"), instant(resultSet, "claim_until"),
                resultSet.getString("claim_request_id"),
                resultSet.getString("claim_request_fingerprint"),
                resultSet.getString("resolution_request_id"),
                resultSet.getString("resolution_request_fingerprint"),
                resultSet.getString("resolution_owner"),
                resultSet.getLong("resolution_claim_version"),
                resultSet.getLong("finding_version"));
    }

    private String writeColumns(List<String> columns) {
        try {
            return objectMapper.writeValueAsString(columns == null ? List.of() : columns);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to serialize projection finding columns", failure);
        }
    }

    private List<String> readColumns(String json) {
        try {
            return List.copyOf(objectMapper.readValue(json, STRING_LIST));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to deserialize projection finding columns", failure);
        }
    }

    private Instant databaseNow() {
        Timestamp timestamp = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (timestamp == null) {
            throw new IllegalStateException("Database clock returned no timestamp");
        }
        return timestamp.toInstant();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        if (value == null) {
            throw new IllegalStateException("Required timestamp column is null: " + column);
        }
        return value.toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static int bounded(int value, int maximum) {
        return Math.max(1, Math.min(value, maximum));
    }

    private static Duration boundedLease(Duration value, String name) {
        Duration safe = Objects.requireNonNull(value, name);
        if (safe.compareTo(MIN_LEASE) < 0 || safe.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException(name + " must be between 1 second and 1 hour");
        }
        return safe;
    }

    private static Duration boundedRetention(
            Duration value,
            Duration minimum,
            String name) {
        Duration safe = Objects.requireNonNull(value, name);
        if (safe.compareTo(minimum) < 0 || safe.compareTo(MAX_RETENTION) > 0) {
            throw new IllegalArgumentException(name + " must be between " + minimum
                    + " and " + MAX_RETENTION);
        }
        return safe;
    }

    private static String required(String value, String name, int maximumLength) {
        String safe = value == null ? "" : value.trim();
        if (safe.isBlank() || safe.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must contain 1.." + maximumLength + " characters");
        }
        return safe;
    }

    private static <T> T requiredTransactionResult(T result, String operation) {
        if (result == null) {
            throw new IllegalStateException("Projection " + operation + " returned no result");
        }
        return result;
    }

    /** Result status for one scheduled anti-entropy attempt. */
    public enum SweepStatus {
        /** The replica acquired the lease and atomically committed one page. */
        COMPLETED,
        /** Another live replica owns the database-clock sweep lease. */
        LEASE_BUSY
    }

    /** Result status for one scheduled finding-retention attempt. */
    public enum RetentionStatus {
        /** The replica archived and purged one bounded page atomically. */
        COMPLETED,
        /** Another live replica owns the database-clock retention lease. */
        LEASE_BUSY
    }

    /** Durable finding lifecycle state. */
    public enum FindingStatus {
        /** Awaiting an operational owner. */
        OPEN,
        /** Temporarily owned under a claim token, version, and database-clock lease. */
        CLAIMED,
        /** Closed by automatic repair, consistent recheck, or an exact manual claim. */
        RESOLVED
    }

    /** Stable, payload-free finding resolution classification. */
    public enum Resolution {
        /** No resolution has been recorded. */
        NONE(false),
        /** The sweep rebuilt safe derived columns from unchanged authority JSON. */
        AUTO_REPAIRED(false),
        /** A later authority scan found the row fully consistent. */
        CONSISTENT_ON_RECHECK(false),
        /** An operational owner repaired the authority or projection manually. */
        MANUALLY_REPAIRED(true),
        /** The affected row was quarantined from all execution and dispatch paths. */
        QUARANTINED(true);

        private final boolean manual;

        Resolution(boolean manual) {
            this.manual = manual;
        }

        private boolean manual() {
            return manual;
        }
    }

    /** Stable outcome of an idempotent owner-queue claim command. */
    public enum ClaimDisposition {
        /** A new claim and its bound audit mutation committed atomically. */
        CLAIMED,
        /** The original claim was returned without another state or audit write. */
        IDEMPOTENT_REPLAY,
        /** The finding is resolved or has another live owner. */
        NOT_ACTIONABLE,
        /** The request ID was reused with changed command facts. */
        IDEMPOTENCY_CONFLICT
    }

    /** Stable outcome of an idempotent owner-queue resolution command. */
    public enum ResolutionDisposition {
        /** A new resolution and its bound audit mutation committed atomically. */
        RESOLVED,
        /** The original resolution receipt was returned without another write. */
        IDEMPOTENT_REPLAY,
        /** The owner, token, version, or database-clock lease fence was rejected. */
        FENCE_REJECTED,
        /** The request ID was reused with changed command facts. */
        IDEMPOTENCY_CONFLICT
    }

    /**
     * Result of one idempotent finding claim.
     *
     * @param disposition claim command outcome
     * @param claim exact fence for claimed and replayed outcomes, otherwise {@code null}
     */
    public record FindingClaimResult(ClaimDisposition disposition, FindingClaim claim) {
        /** Enforces that only successful and replayed outcomes expose a claim fence. */
        public FindingClaimResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            boolean carriesClaim = disposition == ClaimDisposition.CLAIMED
                    || disposition == ClaimDisposition.IDEMPOTENT_REPLAY;
            if (carriesClaim != (claim != null)) {
                throw new IllegalArgumentException(
                        "Claimed and replayed outcomes require exactly one claim");
            }
        }

        private static FindingClaimResult claimed(FindingClaim claim) {
            return new FindingClaimResult(ClaimDisposition.CLAIMED,
                    Objects.requireNonNull(claim, "claim"));
        }

        private static FindingClaimResult replay(FindingClaim claim) {
            return new FindingClaimResult(ClaimDisposition.IDEMPOTENT_REPLAY,
                    Objects.requireNonNull(claim, "claim"));
        }

        private static FindingClaimResult notActionable() {
            return new FindingClaimResult(ClaimDisposition.NOT_ACTIONABLE, null);
        }

        private static FindingClaimResult conflict() {
            return new FindingClaimResult(ClaimDisposition.IDEMPOTENCY_CONFLICT, null);
        }
    }

    /**
     * Durable payload-free receipt for a committed manual resolution.
     *
     * @param key resolved authority row identity
     * @param ownerId verified operational owner
     * @param resolution committed manual classification
     * @param version resulting finding revision
     * @param resolvedAt database-clock commit time
     */
    public record FindingResolution(
            DurableStateProjectionReconciler.EntityKey key,
            String ownerId,
            Resolution resolution,
            long version,
            Instant resolvedAt) {
        /** Validates the complete manual resolution receipt. */
        public FindingResolution {
            key = Objects.requireNonNull(key, "key");
            ownerId = required(ownerId, "Finding resolution owner", 255);
            resolution = Objects.requireNonNull(resolution, "resolution");
            resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
            if (!resolution.manual() || version <= 0) {
                throw new IllegalArgumentException("Manual resolution receipt is invalid");
            }
        }
    }

    /**
     * Result of one idempotent finding resolution.
     *
     * @param disposition resolution command outcome
     * @param resolution durable receipt for resolved and replayed outcomes, otherwise {@code null}
     */
    public record FindingResolutionResult(
            ResolutionDisposition disposition,
            FindingResolution resolution) {
        /** Enforces that only successful and replayed outcomes expose a resolution receipt. */
        public FindingResolutionResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            boolean carriesResolution = disposition == ResolutionDisposition.RESOLVED
                    || disposition == ResolutionDisposition.IDEMPOTENT_REPLAY;
            if (carriesResolution != (resolution != null)) {
                throw new IllegalArgumentException(
                        "Resolved and replayed outcomes require exactly one resolution receipt");
            }
        }

        private static FindingResolutionResult resolved(FindingResolution resolution) {
            return new FindingResolutionResult(ResolutionDisposition.RESOLVED,
                    Objects.requireNonNull(resolution, "resolution"));
        }

        private static FindingResolutionResult replay(FindingResolution resolution) {
            return new FindingResolutionResult(ResolutionDisposition.IDEMPOTENT_REPLAY,
                    Objects.requireNonNull(resolution, "resolution"));
        }

        private static FindingResolutionResult fenceRejected() {
            return new FindingResolutionResult(ResolutionDisposition.FENCE_REJECTED, null);
        }

        private static FindingResolutionResult conflict() {
            return new FindingResolutionResult(
                    ResolutionDisposition.IDEMPOTENCY_CONFLICT, null);
        }
    }

    /**
     * Result of one scheduled control-plane attempt.
     *
     * @param status completed or lease-busy
     * @param result scanner aggregate when completed, otherwise {@code null}
     */
    public record SweepAttempt(
            SweepStatus status,
            DurableStateProjectionReconciler.SweepResult result) {
        /** Requires a result exactly for completed attempts. */
        public SweepAttempt {
            status = Objects.requireNonNull(status, "status");
            if ((status == SweepStatus.COMPLETED) != (result != null)) {
                throw new IllegalArgumentException(
                        "Completed projection attempts require exactly one sweep result");
            }
        }

        private static SweepAttempt completed(
                DurableStateProjectionReconciler.SweepResult result) {
            return new SweepAttempt(SweepStatus.COMPLETED,
                    Objects.requireNonNull(result, "result"));
        }

        /**
         * Creates a result indicating that another replica owns the live sweep lease.
         *
         * @return lease-busy attempt without a scanner result
         */
        public static SweepAttempt busy() {
            return new SweepAttempt(SweepStatus.LEASE_BUSY, null);
        }
    }

    /**
     * Aggregate of one committed finding-retention page.
     *
     * @param archived resolved source rows copied to the payload-free archive and deleted
     * @param purged archive rows deleted after their independent archive retention elapsed
     * @param completedAt database-clock transaction completion time
     */
    public record RetentionResult(int archived, int purged, Instant completedAt) {
        /** Validates non-negative bounded-page counters and a database-clock completion time. */
        public RetentionResult {
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
            if (archived < 0 || purged < 0) {
                throw new IllegalArgumentException("Retention counters cannot be negative");
            }
        }
    }

    /**
     * Result of one scheduled finding-retention attempt.
     *
     * @param status completed or lease-busy
     * @param result retention aggregate when completed, otherwise {@code null}
     */
    public record RetentionAttempt(RetentionStatus status, RetentionResult result) {
        /** Requires a result exactly for completed attempts. */
        public RetentionAttempt {
            status = Objects.requireNonNull(status, "status");
            if ((status == RetentionStatus.COMPLETED) != (result != null)) {
                throw new IllegalArgumentException(
                        "Completed retention attempts require exactly one result");
            }
        }

        /**
         * Creates a committed retention attempt.
         *
         * @param result committed bounded-page aggregate
         * @return completed attempt
         */
        public static RetentionAttempt completed(RetentionResult result) {
            return new RetentionAttempt(
                    RetentionStatus.COMPLETED, Objects.requireNonNull(result, "result"));
        }

        /**
         * Creates an attempt indicating that another replica owns the live retention lease.
         *
         * @return lease-busy attempt without a result
         */
        public static RetentionAttempt busy() {
            return new RetentionAttempt(RetentionStatus.LEASE_BUSY, null);
        }
    }

    /**
     * Payload-free durable sweep state.
     *
     * @param cursor independent execution and work-item keyset positions
     * @param leaseOwner current replica owner, blank when idle
     * @param leaseEpoch monotonically increasing fencing generation
     * @param leaseUntil database-clock lease deadline
     * @param revision optimistic state revision
     * @param lastSuccessAt last atomic page commit, or {@code null}
     */
    public record ControlSnapshot(
            DurableStateProjectionReconciler.ScanCursor cursor,
            String leaseOwner,
            long leaseEpoch,
            Instant leaseUntil,
            long revision,
            Instant lastSuccessAt) {
    }

    /**
     * Payload-free retention-control snapshot for metrics and operational readiness adapters.
     *
     * @param leaseOwner current replica owner, blank when idle
     * @param leaseEpoch monotonically increasing fencing generation
     * @param leaseUntil database-clock lease deadline
     * @param revision optimistic state revision
     * @param totalArchived cumulative source lifecycles archived
     * @param totalPurged cumulative archive snapshots purged
     * @param archiveSize current archive row count
     * @param oldestResolvedAt oldest active resolved finding, or {@code null}
     * @param oldestArchivedAt oldest retained archive snapshot, or {@code null}
     * @param lastSuccessAt last atomic retention page commit, or {@code null}
     */
    public record RetentionSnapshot(
            String leaseOwner,
            long leaseEpoch,
            Instant leaseUntil,
            long revision,
            long totalArchived,
            long totalPurged,
            long archiveSize,
            Instant oldestResolvedAt,
            Instant oldestArchivedAt,
            Instant lastSuccessAt) {
        /** Validates payload-free ownership and monotonic counters. */
        public RetentionSnapshot {
            leaseOwner = leaseOwner == null ? "" : leaseOwner;
            leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
            if (leaseEpoch < 0 || revision < 0 || totalArchived < 0
                    || totalPurged < 0 || archiveSize < 0) {
                throw new IllegalArgumentException("Retention snapshot counters are invalid");
            }
        }
    }

    /**
     * Payload-free owner-queue row. Claim tokens are intentionally omitted.
     *
     * @param key internal authority row identity
     * @param kind stable discrepancy classification
     * @param columns mismatched column names, never field values
     * @param repairable whether safe automatic repair is structurally possible
     * @param outcome latest scanner outcome
     * @param status durable owner-queue state
     * @param occurrences number of discrepant scans
     * @param firstSeenAt first database observation
     * @param lastSeenAt latest discrepant database observation
     * @param resolution current resolution classification
     * @param resolvedAt resolution time, or {@code null}
     * @param claimOwner current owner identity, blank when not claimed
     * @param claimUntil current database-clock claim deadline
     * @param version exact claim/resolve fencing revision
     */
    public record FindingRecord(
            DurableStateProjectionReconciler.EntityKey key,
            DurableStateProjectionReconciler.FindingKind kind,
            List<String> columns,
            boolean repairable,
            DurableStateProjectionReconciler.Outcome outcome,
            FindingStatus status,
            long occurrences,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Resolution resolution,
            Instant resolvedAt,
            String claimOwner,
            Instant claimUntil,
            long version) {
        /** Copies columns and validates complete payload-free metadata. */
        public FindingRecord {
            key = Objects.requireNonNull(key, "key");
            kind = Objects.requireNonNull(kind, "kind");
            columns = columns == null ? List.of() : List.copyOf(columns);
            outcome = Objects.requireNonNull(outcome, "outcome");
            status = Objects.requireNonNull(status, "status");
            firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt");
            lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
            resolution = Objects.requireNonNull(resolution, "resolution");
            claimOwner = claimOwner == null ? "" : claimOwner;
            claimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
            if (occurrences <= 0 || version < 0) {
                throw new IllegalArgumentException("Finding counters are invalid");
            }
        }
    }

    /**
     * Immutable payload-free archive snapshot of one resolved finding lifecycle.
     *
     * <p>Operational owner, claim token, idempotency request IDs, request fingerprints, and
     * authority JSON are deliberately absent. The record fingerprint binds the retained
     * classification, lifecycle, archive identity, and archive time but is not an external WORM
     * attestation.</p>
     *
     * @param archiveId opaque archive-row identity
     * @param key internal authority row identity
     * @param kind stable discrepancy classification
     * @param columns mismatched column names, never field values
     * @param repairable whether automatic repair was structurally possible
     * @param outcome last scanner outcome before resolution
     * @param occurrences number of discrepant scans in the archived lifecycle
     * @param firstSeenAt first database observation
     * @param lastSeenAt latest discrepant database observation
     * @param resolution committed non-empty resolution classification
     * @param resolvedAt database-clock resolution time
     * @param sourceVersion exact finding revision removed from the active queue
     * @param recordFingerprint canonical fingerprint of retained source and archive facts
     * @param archivedAt database-clock archive transaction time
     */
    public record ArchivedFindingRecord(
            String archiveId,
            DurableStateProjectionReconciler.EntityKey key,
            DurableStateProjectionReconciler.FindingKind kind,
            List<String> columns,
            boolean repairable,
            DurableStateProjectionReconciler.Outcome outcome,
            long occurrences,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Resolution resolution,
            Instant resolvedAt,
            long sourceVersion,
            String recordFingerprint,
            Instant archivedAt) {
        /** Copies collection state and validates the complete token-free archive snapshot. */
        public ArchivedFindingRecord {
            archiveId = required(archiveId, "Projection finding archive ID", 36);
            key = Objects.requireNonNull(key, "key");
            kind = Objects.requireNonNull(kind, "kind");
            columns = columns == null ? List.of() : List.copyOf(columns);
            outcome = Objects.requireNonNull(outcome, "outcome");
            firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt");
            lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
            resolution = Objects.requireNonNull(resolution, "resolution");
            resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
            recordFingerprint = required(
                    recordFingerprint, "Projection finding archive record fingerprint", 128);
            archivedAt = Objects.requireNonNull(archivedAt, "archivedAt");
            if (resolution == Resolution.NONE || occurrences <= 0 || sourceVersion < 0) {
                throw new IllegalArgumentException("Archived finding lifecycle is invalid");
            }
        }
    }

    /**
     * Exact server-issued fence required to resolve one finding.
     *
     * @param key claimed row identity
     * @param ownerId operational owner identity
     * @param claimToken unguessable server-minted token
     * @param version exact positive finding revision
     * @param claimUntil database-clock lease deadline
     */
    public record FindingClaim(
            DurableStateProjectionReconciler.EntityKey key,
            String ownerId,
            String claimToken,
            long version,
            Instant claimUntil) {
        /** Validates the complete owner fence. */
        public FindingClaim {
            key = Objects.requireNonNull(key, "key");
            ownerId = required(ownerId, "Finding claim owner", 255);
            claimToken = required(claimToken, "Finding claim token", 255);
            claimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
            if (version <= 0) {
                throw new IllegalArgumentException("Finding claim version must be positive");
            }
        }
    }

    record SweepLease(String ownerId, String token, long epoch, Instant leaseUntil) {
    }

    record RetentionLease(String ownerId, String token, long epoch, Instant leaseUntil) {
    }

    private record SweepState(
            DurableStateProjectionReconciler.ScanCursor cursor,
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseUntil,
            long revision,
            Instant lastSuccessAt,
            Instant updatedAt) {
        private ControlSnapshot snapshot() {
            return new ControlSnapshot(
                    cursor, leaseOwner, leaseEpoch, leaseUntil, revision, lastSuccessAt);
        }
    }

    private record RetentionState(
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseUntil,
            long revision,
            long totalArchived,
            long totalPurged,
            Instant lastSuccessAt,
            Instant updatedAt) {
        private RetentionSnapshot snapshot(
                long archiveSize,
                Instant oldestResolvedAt,
                Instant oldestArchivedAt) {
            return new RetentionSnapshot(
                    leaseOwner, leaseEpoch, leaseUntil, revision, totalArchived, totalPurged,
                    archiveSize, oldestResolvedAt, oldestArchivedAt, lastSuccessAt);
        }
    }

    private record FindingRow(
            DurableStateProjectionReconciler.EntityKey key,
            DurableStateProjectionReconciler.FindingKind kind,
            List<String> columns,
            boolean repairable,
            DurableStateProjectionReconciler.Outcome outcome,
            FindingStatus status,
            long occurrences,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Resolution resolution,
            Instant resolvedAt,
            String claimOwner,
            String claimToken,
            Instant claimUntil,
            String claimRequestId,
            String claimRequestFingerprint,
            String resolutionRequestId,
            String resolutionRequestFingerprint,
            String resolutionOwner,
            long resolutionClaimVersion,
            long version) {
        private FindingClaim claim() {
            return new FindingClaim(key, claimOwner, claimToken, version, claimUntil);
        }

        private FindingResolution findingResolution() {
            return new FindingResolution(
                    key, resolutionOwner, resolution, version, resolvedAt);
        }

        private FindingRecord external() {
            return new FindingRecord(key, kind, columns, repairable, outcome, status,
                    occurrences, firstSeenAt, lastSeenAt, resolution, resolvedAt,
                    claimOwner, claimUntil, version);
        }
    }
}
