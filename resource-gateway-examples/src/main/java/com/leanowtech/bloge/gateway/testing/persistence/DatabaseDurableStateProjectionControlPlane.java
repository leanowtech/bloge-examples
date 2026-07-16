package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final int MAX_PAGE_SIZE = 1000;
    private static final int MAX_FINDING_PAGE_SIZE = 1000;
    private static final Duration MIN_LEASE = Duration.ofSeconds(1);
    private static final Duration MAX_LEASE = Duration.ofHours(1);
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
                    finding_version BIGINT NOT NULL,
                    PRIMARY KEY (entity_type, row_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_projection_finding_queue
                ON rg_test_bloge_projection_findings
                    (finding_status, claim_until, last_seen_at, entity_type, row_id)
                """);
        initializeSweepState();
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
                               claim_until, finding_version
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
                               claim_until, finding_version
                        FROM rg_test_bloge_projection_findings
                        WHERE finding_status = 'OPEN'
                           OR (finding_status = 'CLAIMED' AND claim_until <= CURRENT_TIMESTAMP)
                        ORDER BY last_seen_at, entity_type, row_id
                        LIMIT ?
                        """, this::mapFinding, bounded(limit, MAX_FINDING_PAGE_SIZE));
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
        DurableStateProjectionReconciler.EntityKey safeKey = Objects.requireNonNull(key, "key");
        String safeOwner = required(claimOwner, "Finding claim owner", 255);
        Duration safeDuration = boundedLease(claimDuration, "Finding claim duration");
        Optional<FindingClaim> result = transactions.execute(status -> {
            FindingRow current = findFinding(safeKey, true).orElse(null);
            if (current == null || current.status() == FindingStatus.RESOLVED) {
                return Optional.empty();
            }
            Instant now = databaseNow();
            if (current.status() == FindingStatus.CLAIMED
                    && current.claimUntil().isAfter(now)) {
                return Optional.empty();
            }
            String token = UUID.randomUUID().toString();
            Instant claimUntil = now.plus(safeDuration);
            long version = Math.addExact(current.version(), 1);
            int updated = jdbc.update("""
                    UPDATE rg_test_bloge_projection_findings
                    SET finding_status = 'CLAIMED', claim_owner = ?, claim_token = ?,
                        claim_until = ?, finding_version = ?
                    WHERE entity_type = ? AND row_id = ? AND finding_version = ?
                    """, safeOwner, token, Timestamp.from(claimUntil), version,
                    safeKey.entityType().name(), safeKey.rowId(), current.version());
            if (updated != 1) {
                throw new IllegalStateException("Projection finding claim fence was rejected");
            }
            return Optional.of(new FindingClaim(
                    safeKey, safeOwner, token, version, claimUntil));
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
        FindingClaim safeClaim = Objects.requireNonNull(claim, "claim");
        Resolution safeResolution = Objects.requireNonNull(resolution, "resolution");
        if (!safeResolution.manual()) {
            throw new IllegalArgumentException("A manual finding resolution is required");
        }
        Boolean result = transactions.execute(status -> {
            FindingRow current = findFinding(safeClaim.key(), true).orElse(null);
            Instant now = databaseNow();
            if (current == null
                    || current.status() != FindingStatus.CLAIMED
                    || !current.claimOwner().equals(safeClaim.ownerId())
                    || !current.claimToken().equals(safeClaim.claimToken())
                    || current.version() != safeClaim.version()
                    || !current.claimUntil().isAfter(now)) {
                return false;
            }
            int updated = jdbc.update("""
                    UPDATE rg_test_bloge_projection_findings
                    SET finding_status = 'RESOLVED', resolution = ?, resolved_at = ?,
                        claim_owner = '', claim_token = '', claim_until = ?,
                        finding_version = ?
                    WHERE entity_type = ? AND row_id = ? AND finding_status = 'CLAIMED'
                      AND claim_owner = ? AND claim_token = ? AND finding_version = ?
                      AND claim_until > CURRENT_TIMESTAMP
                    """, safeResolution.name(), Timestamp.from(now), Timestamp.from(Instant.EPOCH),
                    Math.addExact(current.version(), 1), safeClaim.key().entityType().name(),
                    safeClaim.key().rowId(), safeClaim.ownerId(), safeClaim.claimToken(),
                    safeClaim.version());
            return updated == 1;
        });
        return Boolean.TRUE.equals(requiredTransactionResult(result, "finding resolution"));
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
                            claim_until, finding_version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', '', ?, 0)
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
            version = Math.addExact(version, 1);
        }
        int updated = jdbc.update("""
                UPDATE rg_test_bloge_projection_findings
                SET finding_kind = ?, columns_json = ?, repairable = ?, last_outcome = ?,
                    finding_status = ?, occurrence_count = ?, last_seen_at = ?, resolution = ?,
                    resolved_at = ?, claim_owner = ?, claim_token = ?, claim_until = ?,
                    finding_version = ?
                WHERE entity_type = ? AND row_id = ? AND finding_version = ?
                """, finding.kind().name(), writeColumns(finding.columns()), finding.repairable(),
                finding.outcome().name(), nextStatus.name(),
                Math.addExact(current.occurrences(), 1), Timestamp.from(now),
                resolution == Resolution.NONE ? "" : resolution.name(),
                timestamp(resolvedAt), claimOwner, claimToken, Timestamp.from(claimUntil), version,
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
                    claim_owner = '', claim_token = '', claim_until = ?, finding_version = ?
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

    private void requireLiveFence(SweepState state, SweepLease lease, Instant now) {
        if (!state.leaseOwner().equals(lease.ownerId())
                || !state.leaseToken().equals(lease.token())
                || state.leaseEpoch() != lease.epoch()
                || !state.leaseUntil().isAfter(now)) {
            throw new IllegalStateException("Projection sweep fence is stale or expired");
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

    private Optional<FindingRow> findFinding(
            DurableStateProjectionReconciler.EntityKey key,
            boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("""
                        SELECT entity_type, row_id, finding_kind, columns_json, repairable,
                               last_outcome, finding_status, occurrence_count, first_seen_at,
                               last_seen_at, resolution, resolved_at, claim_owner, claim_token,
                               claim_until, finding_version
                        FROM rg_test_bloge_projection_findings
                        WHERE entity_type = ? AND row_id = ?
                        """ + suffix, this::mapFindingRow,
                key.entityType().name(), key.rowId()).stream().findFirst();
    }

    private FindingRecord mapFinding(ResultSet resultSet, int rowNumber) throws SQLException {
        return mapFindingRow(resultSet, rowNumber).external();
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
            long version) {
        private FindingRecord external() {
            return new FindingRecord(key, kind, columns, repairable, outcome, status,
                    occurrences, firstSeenAt, lastSeenAt, resolution, resolvedAt,
                    claimOwner, claimUntil, version);
        }
    }
}
