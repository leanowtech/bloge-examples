package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Database-fenced retention for governed external-archive findings and projection evidence.
 *
 * <p>One database-clock lease serializes bounded retention pages across replicas. A page may copy
 * resolved current findings into a separately fingerprinted archive, purge expired archive rows,
 * and advance one completed projection's event/snapshot retirement. Every source mutation is
 * exact-fenced by its immutable fingerprint and commits with cumulative counters and roots.</p>
 *
 * <p>Projection summaries and permanent retirement markers are never deleted. Event export is
 * denied from the instant a marker becomes active, so bounded physical deletion cannot expose a
 * partial history. This authority never mutates WORM storage, source comparisons, or source
 * classifications.</p>
 */
public final class
        DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane {

    /** Domain-separated compact root before the first governed archive purge. */
    public static final String EMPTY_ARCHIVE_PURGE_ROOT = ProtocolFingerprint.ofText(
            "bloge.testSuiteStabilityObservationExternalArchiveFindingPurgeRoot.v1:empty");

    private static final String JOB_NAME = "external-archive-finding-retention";
    private static final int MAX_PAGE_SIZE = 500;
    private static final Duration MIN_LEASE = Duration.ofSeconds(1);
    private static final Duration MAX_LEASE = Duration.ofHours(1);
    private static final Duration MIN_RESOLVED_RETENTION = Duration.ofHours(1);
    private static final Duration MIN_ARCHIVE_RETENTION = Duration.ofDays(1);
    private static final Duration MIN_EVIDENCE_RETENTION = Duration.ofDays(1);
    private static final Duration MAX_RETENTION = Duration.ofDays(3650);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern OBJECT_ID =
            Pattern.compile("stability-observation-worm-[a-f0-9]{64}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final TransactionTemplate observations;
    private final String ownerId;
    private final Duration leaseDuration;

    /**
     * Creates a retention control plane with a transaction manager derived from the datasource.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param ownerId stable Resource Gateway replica identity
     * @param leaseDuration database-clock lease from one second through one hour
     */
    public DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String ownerId,
            Duration leaseDuration) {
        this(jdbc, localTransactionManager(jdbc), objectMapper, ownerId, leaseDuration);
    }

    /**
     * Creates a retention control plane over the finding datasource.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param transactionManager transaction manager bound to the JDBC datasource
     * @param objectMapper canonical protocol mapper
     * @param ownerId stable Resource Gateway replica identity
     * @param leaseDuration database-clock lease from one second through one hour
     */
    public DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            String ownerId,
            Duration leaseDuration) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.ownerId = requiredIdentifier(ownerId, "retention owner");
        this.leaseDuration = boundedDuration(
                leaseDuration, MIN_LEASE, MAX_LEASE, "retention lease");
        PlatformTransactionManager manager = Objects.requireNonNull(
                transactionManager, "transactionManager");
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        observations = new TransactionTemplate(manager);
        observations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        observations.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** Creates archive, lease, evidence-progress, and permanent retirement-marker tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_finding_archives (
                    archive_id VARCHAR(36) PRIMARY KEY,
                    authority_id VARCHAR(255) NOT NULL,
                    object_id VARCHAR(255) NOT NULL,
                    finding_status VARCHAR(32) NOT NULL,
                    finding_kind VARCHAR(32) NOT NULL,
                    latest_comparison_id VARCHAR(36) NOT NULL,
                    latest_outcome VARCHAR(32) NOT NULL,
                    latest_classification_fingerprint VARCHAR(71) NOT NULL,
                    occurrence_count BIGINT NOT NULL,
                    episode_count BIGINT NOT NULL,
                    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    last_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    last_evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    resolution VARCHAR(32) NOT NULL,
                    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    finding_version BIGINT NOT NULL,
                    source_record_fingerprint VARCHAR(71) NOT NULL,
                    archived_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    CONSTRAINT
                        uq_rg_test_suite_stability_observation_external_finding_archive_source
                        UNIQUE (authority_id, object_id, source_record_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_external_finding_archive_retention
                ON rg_test_suite_stability_observation_external_finding_archives (
                    archived_at, archive_id
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_external_finding_archive_authority
                ON rg_test_suite_stability_observation_external_finding_archives (
                    authority_id, archive_id
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_finding_retention_progress (
                    projection_id VARCHAR(36) PRIMARY KEY,
                    comparison_id VARCHAR(36) NOT NULL,
                    authority_id VARCHAR(255) NOT NULL,
                    progress_status VARCHAR(32) NOT NULL,
                    source_projection_fingerprint VARCHAR(71) NOT NULL,
                    expected_event_count BIGINT NOT NULL,
                    expected_event_root VARCHAR(71) NOT NULL,
                    expected_snapshot_count BIGINT NOT NULL,
                    expected_snapshot_root VARCHAR(71) NOT NULL,
                    event_after_object_id VARCHAR(255) NOT NULL,
                    deleted_event_count BIGINT NOT NULL,
                    deleted_event_root VARCHAR(71) NOT NULL,
                    event_complete BOOLEAN NOT NULL,
                    snapshot_after_object_id VARCHAR(255) NOT NULL,
                    deleted_snapshot_count BIGINT NOT NULL,
                    deleted_snapshot_root VARCHAR(71) NOT NULL,
                    snapshot_complete BOOLEAN NOT NULL,
                    revision BIGINT NOT NULL,
                    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    completed_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    CONSTRAINT
                        uq_rg_test_suite_stability_observation_external_retention_comparison
                        UNIQUE (comparison_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_external_retention_progress_status
                ON rg_test_suite_stability_observation_external_finding_retention_progress (
                    progress_status, started_at, projection_id
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_finding_evidence_retirements (
                    projection_id VARCHAR(36) PRIMARY KEY,
                    comparison_id VARCHAR(36) NOT NULL,
                    authority_id VARCHAR(255) NOT NULL,
                    retirement_status VARCHAR(32) NOT NULL,
                    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    completed_at TIMESTAMP WITH TIME ZONE,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    CONSTRAINT
                        uq_rg_test_suite_stability_observation_external_finding_retirement_source
                        UNIQUE (comparison_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_finding_retention_state (
                    job_name VARCHAR(128) PRIMARY KEY,
                    lease_owner VARCHAR(255) NOT NULL,
                    lease_token VARCHAR(36) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    active_retirement_projection_id VARCHAR(36) NOT NULL,
                    revision BIGINT NOT NULL,
                    total_findings_archived BIGINT NOT NULL,
                    total_archives_purged BIGINT NOT NULL,
                    total_events_deleted BIGINT NOT NULL,
                    total_snapshots_deleted BIGINT NOT NULL,
                    total_projections_retired BIGINT NOT NULL,
                    archive_purge_root VARCHAR(71) NOT NULL,
                    last_success_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        initializeState();
    }

    /**
     * Executes one leased and bounded retention page.
     *
     * <p>Eligibility is derived only from the database clock. The page archives at most
     * {@code pageSize} resolved findings, purges at most {@code pageSize} archive rows, and
     * deletes at most {@code pageSize} events plus snapshots from one evidence retirement.</p>
     *
     * @param resolvedRetention time a resolved finding remains in the current table
     * @param archiveRetention time an archived finding remains queryable
     * @param evidenceRetention time completed event/snapshot evidence remains exportable
     * @param pageSize independent row bound from 1 through 500
     * @return committed aggregate or lease-busy result
     */
    public RetentionAttempt retain(
            Duration resolvedRetention,
            Duration archiveRetention,
            Duration evidenceRetention,
            int pageSize) {
        Duration resolved = boundedDuration(resolvedRetention, MIN_RESOLVED_RETENTION,
                MAX_RETENTION, "resolved finding retention");
        Duration archive = boundedDuration(archiveRetention, MIN_ARCHIVE_RETENTION,
                MAX_RETENTION, "finding archive retention");
        Duration evidence = boundedDuration(evidenceRetention, MIN_EVIDENCE_RETENTION,
                MAX_RETENTION, "finding evidence retention");
        int page = boundedPageSize(pageSize);
        Optional<RetentionLease> lease = acquireLease();
        if (lease.isEmpty()) {
            return RetentionAttempt.busy();
        }
        try {
            return retainClaimed(lease.orElseThrow(), resolved, archive, evidence, page);
        } catch (RuntimeException failure) {
            try {
                releaseLease(lease.orElseThrow());
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    /**
     * Returns one verified page of payload-free resolved-finding archives.
     *
     * @param authorityId exact inventory authority
     * @param afterArchiveId exclusive UUID cursor, or empty for the first page
     * @param limit maximum archives to return, from 1 through 500
     * @return immutable archives in strict archive-id order
     */
    public List<ArchivedFinding> archives(
            String authorityId,
            String afterArchiveId,
            int limit) {
        String authority = requiredIdentifier(authorityId, "archive authority");
        String cursor = optionalUuid(afterArchiveId, "archive cursor");
        int bounded = boundedPageSize(limit);
        List<ArchivedFinding> result = observations.execute(status -> {
            RetentionState state = readState(false);
            verifyArchiveCardinality(state);
            List<ArchivedFinding> rows = jdbc.query("""
                    SELECT archive_id, authority_id, object_id, finding_status, finding_kind,
                           latest_comparison_id, latest_outcome,
                           latest_classification_fingerprint, occurrence_count, episode_count,
                           first_seen_at, last_observed_at, last_evaluated_at, resolution,
                           resolved_at, finding_version, source_record_fingerprint, archived_at,
                           record_fingerprint
                    FROM rg_test_suite_stability_observation_external_finding_archives
                    WHERE authority_id = ? AND archive_id > ?
                    ORDER BY archive_id
                    LIMIT ?
                    """, this::archivedFinding, authority, cursor, bounded);
            String previous = cursor;
            for (ArchivedFinding row : rows) {
                row.verify(objectMapper);
                if (!row.finding().authorityId().equals(authority)
                        || previous.compareTo(row.archiveId()) >= 0) {
                    throw new IllegalStateException("External finding archive order is corrupt");
                }
                previous = row.archiveId();
            }
            return List.copyOf(rows);
        });
        return requiredResult(result, "finding archive export");
    }

    /**
     * Returns one database-clock operational snapshot without lease tokens or object identities.
     *
     * @param resolvedRetention active resolved-finding policy
     * @param archiveRetention archive policy
     * @param evidenceRetention completed evidence policy
     * @return counters, backlog ages, and active-retirement state from one transaction
     */
    public OperationalSnapshot operationalSnapshot(
            Duration resolvedRetention,
            Duration archiveRetention,
            Duration evidenceRetention) {
        Duration resolved = boundedDuration(resolvedRetention, MIN_RESOLVED_RETENTION,
                MAX_RETENTION, "resolved finding retention");
        Duration archive = boundedDuration(archiveRetention, MIN_ARCHIVE_RETENTION,
                MAX_RETENTION, "finding archive retention");
        Duration evidence = boundedDuration(evidenceRetention, MIN_EVIDENCE_RETENTION,
                MAX_RETENTION, "finding evidence retention");
        OperationalSnapshot result = observations.execute(status -> {
            RetentionState state = readState(false);
            verifyArchiveCardinality(state);
            verifyProgressCardinality(state);
            Instant now = databaseNow();
            Long open = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM rg_test_suite_stability_observation_external_findings
                    WHERE finding_status = 'OPEN'
                    """, Long.class);
            Long overdueResolved = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM rg_test_suite_stability_observation_external_findings
                    WHERE finding_status = 'RESOLVED' AND resolved_at <= ?
                    """, Long.class, Timestamp.from(now.minus(resolved)));
            Long overdueArchives = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM rg_test_suite_stability_observation_external_finding_archives
                    WHERE archived_at <= ?
                    """, Long.class, Timestamp.from(now.minus(archive)));
            Long overdueEvidence = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM rg_test_suite_stability_observation_external_finding_projections p
                    WHERE p.projection_status = 'COMPLETED' AND p.completed_at <= ?
                      AND NOT EXISTS (
                          SELECT 1 FROM
                            rg_test_suite_stability_observation_external_finding_evidence_retirements r
                          WHERE r.projection_id = p.projection_id
                      )
                    """, Long.class, Timestamp.from(now.minus(evidence)));
            return state.snapshot(now, number(open), number(overdueResolved),
                    number(overdueArchives), number(overdueEvidence));
        });
        return requiredResult(result, "finding retention snapshot");
    }

    private RetentionAttempt retainClaimed(
            RetentionLease lease,
            Duration resolvedRetention,
            Duration archiveRetention,
            Duration evidenceRetention,
            int pageSize) {
        RetentionAttempt result = transactions.execute(status -> {
            RetentionState state = readState(true);
            Instant startedAt = databaseNow();
            requireLiveFence(state, lease, startedAt);
            verifyArchiveCardinality(state);
            verifyProgressCardinality(state);

            int archived = archiveResolvedFindings(
                    startedAt.minus(resolvedRetention), pageSize, startedAt);
            PurgeMutation purge = purgeArchives(
                    state.archivePurgeRoot(), startedAt.minus(archiveRetention), pageSize);
            EvidenceMutation evidence = advanceEvidence(
                    state, startedAt.minus(evidenceRetention), pageSize, startedAt);

            Instant completedAt = databaseNow();
            requireLiveFence(state, lease, completedAt);
            RetentionState successor = state.completed(
                    evidence.activeProjectionId(), archived, purge.purged(),
                    evidence.eventsDeleted(), evidence.snapshotsDeleted(),
                    evidence.projectionRetired() ? 1 : 0, purge.root(), completedAt);
            updateState(state, successor);
            verifyArchiveCardinality(successor);
            verifyProgressCardinality(successor);
            return RetentionAttempt.completed(new RetentionResult(
                    archived, purge.purged(), evidence.eventsDeleted(),
                    evidence.snapshotsDeleted(), evidence.projectionRetired(),
                    evidence.activeProjectionId(), completedAt));
        });
        return requiredResult(result, "claimed finding retention");
    }

    private int archiveResolvedFindings(Instant cutoff, int pageSize, Instant archivedAt) {
        List<String> candidates = jdbc.query("""
                SELECT f.authority_id
                FROM rg_test_suite_stability_observation_external_findings f
                JOIN rg_test_suite_stability_observation_external_finding_authorities a
                  ON a.authority_id = f.authority_id
                WHERE f.finding_status = 'RESOLVED' AND f.resolved_at IS NOT NULL
                  AND f.resolved_at <= ? AND a.active_projection_id = ''
                GROUP BY f.authority_id
                ORDER BY MIN(f.resolved_at), f.authority_id
                LIMIT 1
                """, (result, row) -> result.getString("authority_id"),
                Timestamp.from(cutoff));
        if (candidates.isEmpty()) {
            return 0;
        }
        StoredAuthority authority = readAuthority(candidates.getFirst(), true);
        if (!authority.activeProjectionId().isEmpty()) {
            return 0;
        }
        List<DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.Finding>
                eligible = jdbc.query("""
                SELECT authority_id, object_id, finding_status, finding_kind,
                       latest_comparison_id, latest_outcome,
                       latest_classification_fingerprint, occurrence_count, episode_count,
                       first_seen_at, last_observed_at, last_evaluated_at, resolution,
                       resolved_at, finding_version, record_fingerprint
                FROM rg_test_suite_stability_observation_external_findings
                WHERE authority_id = ? AND finding_status = 'RESOLVED'
                  AND resolved_at IS NOT NULL AND resolved_at <= ?
                ORDER BY resolved_at, object_id
                LIMIT ? FOR UPDATE
                """, this::finding, authority.authorityId(), Timestamp.from(cutoff), pageSize);
        int archived = 0;
        for (var finding : eligible) {
            finding.verify(objectMapper);
            if (!finding.authorityId().equals(authority.authorityId())
                    || finding.status()
                    != DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                    .FindingStatus.RESOLVED
                    || finding.resolvedAt() == null
                    || finding.resolvedAt().isAfter(cutoff)) {
                throw new IllegalStateException(
                        "External resolved finding archive source is corrupt");
            }
            archiveFinding(finding, archivedAt);
            archived = increment(archived, "archived finding count");
        }
        return archived;
    }

    private void archiveFinding(
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.Finding finding,
            Instant archivedAt) {
        String archiveId = UUID.randomUUID().toString();
        ArchivedFinding material = new ArchivedFinding(archiveId, finding, archivedAt, "");
        ArchivedFinding archive = material.withFingerprint(ProtocolFingerprint.of(
                objectMapper, material.fingerprintMaterial()));
        try {
            int inserted = jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_finding_archives (
                        archive_id, authority_id, object_id, finding_status, finding_kind,
                        latest_comparison_id, latest_outcome,
                        latest_classification_fingerprint, occurrence_count, episode_count,
                        first_seen_at, last_observed_at, last_evaluated_at, resolution,
                        resolved_at, finding_version, source_record_fingerprint, archived_at,
                        record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, archive.sqlArguments());
            if (inserted != 1) {
                throw new IllegalStateException("External finding archive insert was incomplete");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException(
                    "External finding archive source is not unique", duplicate);
        }
        int deleted = jdbc.update("""
                DELETE FROM rg_test_suite_stability_observation_external_findings
                WHERE authority_id = ? AND object_id = ? AND finding_status = 'RESOLVED'
                  AND finding_version = ? AND resolved_at = ? AND record_fingerprint = ?
                """, finding.authorityId(), finding.objectId(), finding.version(),
                Timestamp.from(finding.resolvedAt()), finding.recordFingerprint());
        if (deleted != 1) {
            throw new IllegalStateException("External resolved finding archive fence was rejected");
        }
    }

    private PurgeMutation purgeArchives(String initialRoot, Instant cutoff, int pageSize) {
        List<ArchivedFinding> expired = jdbc.query("""
                SELECT archive_id, authority_id, object_id, finding_status, finding_kind,
                       latest_comparison_id, latest_outcome,
                       latest_classification_fingerprint, occurrence_count, episode_count,
                       first_seen_at, last_observed_at, last_evaluated_at, resolution,
                       resolved_at, finding_version, source_record_fingerprint, archived_at,
                       record_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_archives
                WHERE archived_at <= ?
                ORDER BY archived_at, archive_id
                LIMIT ? FOR UPDATE
                """, this::archivedFinding, Timestamp.from(cutoff), pageSize);
        String root = initialRoot;
        int purged = 0;
        for (ArchivedFinding archive : expired) {
            archive.verify(objectMapper);
            root = ProtocolFingerprint.of(objectMapper, new ArchivePurgeRootLink(
                    ArchivePurgeRootLink.SCHEMA_VERSION, root, archive.recordFingerprint()));
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_suite_stability_observation_external_finding_archives
                    WHERE archive_id = ? AND archived_at = ? AND record_fingerprint = ?
                      AND archived_at <= ?
                    """, archive.archiveId(), Timestamp.from(archive.archivedAt()),
                    archive.recordFingerprint(), Timestamp.from(cutoff));
            if (deleted != 1) {
                throw new IllegalStateException(
                        "External finding archive purge fence was rejected");
            }
            purged = increment(purged, "purged finding archive count");
        }
        return new PurgeMutation(purged, root);
    }

    private Optional<RetentionLease> acquireLease() {
        Optional<RetentionLease> result = transactions.execute(status -> {
            RetentionState current = readState(true);
            verifyArchiveCardinality(current);
            verifyProgressCardinality(current);
            Instant now = databaseNow();
            if (current.leaseUntil().isAfter(now)) {
                return Optional.empty();
            }
            RetentionLease lease = new RetentionLease(ownerId, UUID.randomUUID().toString(),
                    increment(current.leaseEpoch(), "retention lease epoch"),
                    now.plus(leaseDuration));
            updateState(current, current.leased(lease, now));
            return Optional.of(lease);
        });
        return requiredResult(result, "finding retention lease acquisition");
    }

    private boolean releaseLease(RetentionLease lease) {
        Boolean result = transactions.execute(status -> {
            RetentionState current = readState(true);
            if (!current.matches(lease)) {
                return false;
            }
            Instant now = databaseNow();
            updateState(current, current.released(now));
            return true;
        });
        return Boolean.TRUE.equals(requiredResult(result, "finding retention lease release"));
    }

    private void requireLiveFence(
            RetentionState state,
            RetentionLease lease,
            Instant databaseTime) {
        if (!state.matches(lease) || state.leaseUntil().isBefore(databaseTime)) {
            throw new IllegalStateException(
                    "External finding retention lease is stale or expired");
        }
    }

    private void initializeState() {
        Long rows = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_finding_retention_state
                WHERE job_name = ?
                """, Long.class, JOB_NAME);
        if (number(rows) == 1) {
            readState(false);
            return;
        }
        Instant now = databaseNow();
        RetentionState initial = RetentionState.initial(now);
        initial = initial.withFingerprint(ProtocolFingerprint.of(
                objectMapper, initial.fingerprintMaterial()));
        try {
            jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_finding_retention_state (
                        job_name, lease_owner, lease_token, lease_epoch, lease_until,
                        active_retirement_projection_id, revision, total_findings_archived,
                        total_archives_purged, total_events_deleted, total_snapshots_deleted,
                        total_projections_retired, archive_purge_root, last_success_at, updated_at,
                        record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, initial.sqlArguments());
        } catch (DuplicateKeyException ignored) {
            // Another replica initialized the singleton retention authority.
        }
        readState(false);
    }

    private RetentionState readState(boolean lock) {
        List<RetentionState> rows = jdbc.query("""
                SELECT job_name, lease_owner, lease_token, lease_epoch, lease_until,
                       active_retirement_projection_id, revision, total_findings_archived,
                       total_archives_purged, total_events_deleted, total_snapshots_deleted,
                       total_projections_retired, archive_purge_root, last_success_at, updated_at,
                       record_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_retention_state
                WHERE job_name = ?
                """ + (lock ? " FOR UPDATE" : ""), this::retentionState, JOB_NAME);
        if (rows.size() != 1) {
            throw new IllegalStateException("External finding retention state is missing");
        }
        rows.getFirst().verify(objectMapper);
        return rows.getFirst();
    }

    private RetentionState retentionState(ResultSet result, int row) throws SQLException {
        return new RetentionState(result.getString("job_name"),
                result.getString("lease_owner"), result.getString("lease_token"),
                result.getLong("lease_epoch"), instant(result, "lease_until"),
                result.getString("active_retirement_projection_id"),
                result.getLong("revision"), result.getLong("total_findings_archived"),
                result.getLong("total_archives_purged"),
                result.getLong("total_events_deleted"),
                result.getLong("total_snapshots_deleted"),
                result.getLong("total_projections_retired"),
                result.getString("archive_purge_root"),
                nullableInstant(result, "last_success_at"), instant(result, "updated_at"),
                result.getString("record_fingerprint"));
    }

    private void updateState(RetentionState expected, RetentionState successor) {
        RetentionState material = successor.withFingerprint(ProtocolFingerprint.of(
                objectMapper, successor.fingerprintMaterial()));
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_observation_external_finding_retention_state
                SET lease_owner = ?, lease_token = ?, lease_epoch = ?, lease_until = ?,
                    active_retirement_projection_id = ?, revision = ?,
                    total_findings_archived = ?, total_archives_purged = ?,
                    total_events_deleted = ?, total_snapshots_deleted = ?,
                    total_projections_retired = ?, archive_purge_root = ?, last_success_at = ?,
                    updated_at = ?, record_fingerprint = ?
                WHERE job_name = ? AND revision = ? AND record_fingerprint = ?
                """, material.updateArguments(expected));
        if (updated != 1) {
            throw new IllegalStateException("External finding retention state fence was rejected");
        }
    }

    private void verifyArchiveCardinality(RetentionState state) {
        Long stored = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_finding_archives
                """, Long.class);
        long expected;
        try {
            expected = Math.subtractExact(
                    state.totalFindingsArchived(), state.totalArchivesPurged());
        } catch (ArithmeticException invalid) {
            throw new IllegalStateException("External finding archive counters are corrupt", invalid);
        }
        if (expected < 0 || number(stored) != expected) {
            throw new IllegalStateException("External finding archive cardinality is corrupt");
        }
    }

    private EvidenceMutation advanceEvidence(
            RetentionState state,
            Instant cutoff,
            int pageSize,
            Instant now) {
        RetentionProgress progress;
        if (state.activeRetirementProjectionId().isEmpty()) {
            progress = initiateEvidenceRetirement(cutoff, now);
            if (progress == null) {
                return EvidenceMutation.none();
            }
        } else {
            progress = readProgress(state.activeRetirementProjectionId(), true);
        }
        StoredProjection projection = readProjection(progress.projectionId(), true);
        progress.verifySource(projection);
        EvidenceRetirement marker = readMarker(progress.projectionId(), true);
        marker.verifySource(progress);
        if (!"ACTIVE".equals(marker.status()) || !"ACTIVE".equals(progress.status())) {
            throw new IllegalStateException(
                    "External finding evidence retirement is not active");
        }

        SegmentProgress events = progress.eventComplete()
                ? SegmentProgress.completed(progress.eventAfterObjectId(),
                progress.deletedEventCount(), progress.deletedEventRoot())
                : deleteEventPage(progress, projection, pageSize);
        SegmentProgress snapshots = progress.snapshotComplete()
                ? SegmentProgress.completed(progress.snapshotAfterObjectId(),
                progress.deletedSnapshotCount(), progress.deletedSnapshotRoot())
                : deleteSnapshotPage(progress, projection, pageSize);
        boolean complete = events.complete() && snapshots.complete();
        RetentionProgress successor = progress.progressed(events, snapshots, complete, now);
        updateProgress(progress, successor);
        if (complete) {
            completeMarker(marker, now);
            return new EvidenceMutation(events.deletedOnPage(), snapshots.deletedOnPage(),
                    true, "");
        }
        return new EvidenceMutation(events.deletedOnPage(), snapshots.deletedOnPage(),
                false, progress.projectionId());
    }

    private RetentionProgress initiateEvidenceRetirement(Instant cutoff, Instant now) {
        List<String> candidates = jdbc.query("""
                SELECT p.projection_id
                FROM rg_test_suite_stability_observation_external_finding_projections p
                WHERE p.projection_status = 'COMPLETED' AND p.completed_at <= ?
                  AND NOT EXISTS (
                      SELECT 1 FROM
                        rg_test_suite_stability_observation_external_finding_evidence_retirements r
                      WHERE r.projection_id = p.projection_id
                  )
                ORDER BY p.completed_at, p.projection_id
                LIMIT 1
                """, (result, row) -> result.getString("projection_id"), Timestamp.from(cutoff));
        if (candidates.isEmpty()) {
            return null;
        }
        StoredProjection source = readProjection(candidates.getFirst(), true);
        source.requireCompleted();
        if (source.completedAt().isAfter(cutoff)) {
            return null;
        }
        EvidenceRetirement marker = EvidenceRetirement.active(source, now);
        marker = marker.withFingerprint(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .evidenceRetirementFingerprint(objectMapper, marker.projectionId(),
                                marker.comparisonId(), marker.authorityId(), marker.status(),
                                marker.startedAt(), marker.completedAt()));
        RetentionProgress progress = RetentionProgress.active(source, now);
        progress = progress.withFingerprint(ProtocolFingerprint.of(
                objectMapper, progress.fingerprintMaterial()));
        try {
            jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_finding_evidence_retirements (
                        projection_id, comparison_id, authority_id, retirement_status,
                        started_at, completed_at, record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, marker.sqlArguments());
            jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_finding_retention_progress (
                        projection_id, comparison_id, authority_id, progress_status,
                        source_projection_fingerprint, expected_event_count, expected_event_root,
                        expected_snapshot_count, expected_snapshot_root, event_after_object_id,
                        deleted_event_count, deleted_event_root, event_complete,
                        snapshot_after_object_id, deleted_snapshot_count, deleted_snapshot_root,
                        snapshot_complete, revision, started_at, completed_at, updated_at,
                        record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, progress.sqlArguments());
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException(
                    "External finding evidence retirement is not unique", duplicate);
        }
        return progress;
    }

    private SegmentProgress deleteEventPage(
            RetentionProgress progress,
            StoredProjection projection,
            int pageSize) {
        List<DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.FindingEvent>
                rows = jdbc.query("""
                SELECT projection_id, comparison_id, authority_id, object_id,
                       classification_outcome, classification_fingerprint, transition,
                       previous_finding_fingerprint, resulting_finding_fingerprint,
                       resulting_finding_version, occurred_at, event_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_events
                WHERE projection_id = ? AND object_id > ?
                ORDER BY object_id
                LIMIT ? FOR UPDATE
                """, this::findingEvent, progress.projectionId(),
                progress.eventAfterObjectId(), pageSize);
        String cursor = progress.eventAfterObjectId();
        String root = progress.deletedEventRoot();
        long count = progress.deletedEventCount();
        int deletedOnPage = 0;
        for (var event : rows) {
            event.verify(objectMapper);
            if (!event.projectionId().equals(progress.projectionId())
                    || !event.comparisonId().equals(progress.comparisonId())
                    || !event.authorityId().equals(progress.authorityId())
                    || cursor.compareTo(event.objectId()) >= 0) {
                throw new IllegalStateException(
                        "External finding event retirement order is corrupt");
            }
            root = DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                    .appendEventRoot(objectMapper, root, event);
            count = increment(count, "deleted finding event count");
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_suite_stability_observation_external_finding_events
                    WHERE projection_id = ? AND object_id = ? AND comparison_id = ?
                      AND event_fingerprint = ?
                    """, event.projectionId(), event.objectId(), event.comparisonId(),
                    event.eventFingerprint());
            if (deleted != 1) {
                throw new IllegalStateException(
                        "External finding event retirement fence was rejected");
            }
            cursor = event.objectId();
            deletedOnPage = increment(deletedOnPage, "deleted event page count");
        }
        boolean complete = rows.size() < pageSize;
        if (complete) {
            requireCompletedSegment("event", count, root,
                    projection.processedCount(), projection.eventRoot());
        }
        return new SegmentProgress(cursor, count, root, complete, deletedOnPage);
    }

    private SegmentProgress deleteSnapshotPage(
            RetentionProgress progress,
            StoredProjection projection,
            int pageSize) {
        List<DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.Finding>
                rows = jdbc.query("""
                SELECT authority_id, object_id, finding_status, finding_kind,
                       latest_comparison_id, latest_outcome,
                       latest_classification_fingerprint, occurrence_count, episode_count,
                       first_seen_at, last_observed_at, last_evaluated_at, resolution,
                       resolved_at, finding_version, record_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_snapshots
                WHERE projection_id = ? AND object_id > ?
                ORDER BY object_id
                LIMIT ? FOR UPDATE
                """, this::finding, progress.projectionId(),
                progress.snapshotAfterObjectId(), pageSize);
        String cursor = progress.snapshotAfterObjectId();
        String root = progress.deletedSnapshotRoot();
        long count = progress.deletedSnapshotCount();
        int deletedOnPage = 0;
        for (var finding : rows) {
            finding.verify(objectMapper);
            if (!finding.authorityId().equals(progress.authorityId())
                    || cursor.compareTo(finding.objectId()) >= 0) {
                throw new IllegalStateException(
                        "External finding snapshot retirement order is corrupt");
            }
            root = DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                    .appendSnapshotRoot(objectMapper, root, finding);
            count = increment(count, "deleted finding snapshot count");
            int deleted = jdbc.update("""
                    DELETE FROM
                        rg_test_suite_stability_observation_external_finding_snapshots
                    WHERE projection_id = ? AND object_id = ? AND record_fingerprint = ?
                    """, progress.projectionId(), finding.objectId(),
                    finding.recordFingerprint());
            if (deleted != 1) {
                throw new IllegalStateException(
                        "External finding snapshot retirement fence was rejected");
            }
            cursor = finding.objectId();
            deletedOnPage = increment(deletedOnPage, "deleted snapshot page count");
        }
        boolean complete = rows.size() < pageSize;
        if (complete) {
            requireCompletedSegment("snapshot", count, root,
                    projection.snapshotCount(), projection.snapshotRoot());
        }
        return new SegmentProgress(cursor, count, root, complete, deletedOnPage);
    }

    private static void requireCompletedSegment(
            String name,
            long actualCount,
            String actualRoot,
            long expectedCount,
            String expectedRoot) {
        if (actualCount != expectedCount || !actualRoot.equals(expectedRoot)) {
            throw new IllegalStateException(
                    "External finding " + name + " retirement replay failed");
        }
    }

    private RetentionProgress readProgress(String projectionId, boolean lock) {
        List<RetentionProgress> rows = jdbc.query("""
                SELECT projection_id, comparison_id, authority_id, progress_status,
                       source_projection_fingerprint, expected_event_count, expected_event_root,
                       expected_snapshot_count, expected_snapshot_root, event_after_object_id,
                       deleted_event_count, deleted_event_root, event_complete,
                       snapshot_after_object_id, deleted_snapshot_count, deleted_snapshot_root,
                       snapshot_complete, revision, started_at, completed_at, updated_at,
                       record_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_retention_progress
                WHERE projection_id = ?
                """ + (lock ? " FOR UPDATE" : ""), this::retentionProgress, projectionId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "External finding evidence retirement progress is missing");
        }
        rows.getFirst().verify(objectMapper);
        return rows.getFirst();
    }

    private RetentionProgress retentionProgress(ResultSet result, int row) throws SQLException {
        return new RetentionProgress(result.getString("projection_id"),
                result.getString("comparison_id"), result.getString("authority_id"),
                result.getString("progress_status"),
                result.getString("source_projection_fingerprint"),
                result.getLong("expected_event_count"),
                result.getString("expected_event_root"),
                result.getLong("expected_snapshot_count"),
                result.getString("expected_snapshot_root"),
                result.getString("event_after_object_id"),
                result.getLong("deleted_event_count"),
                result.getString("deleted_event_root"), result.getBoolean("event_complete"),
                result.getString("snapshot_after_object_id"),
                result.getLong("deleted_snapshot_count"),
                result.getString("deleted_snapshot_root"),
                result.getBoolean("snapshot_complete"), result.getLong("revision"),
                instant(result, "started_at"), nullableInstant(result, "completed_at"),
                instant(result, "updated_at"), result.getString("record_fingerprint"));
    }

    private void updateProgress(RetentionProgress expected, RetentionProgress successor) {
        RetentionProgress material = successor.withFingerprint(ProtocolFingerprint.of(
                objectMapper, successor.fingerprintMaterial()));
        int updated = jdbc.update("""
                UPDATE
                    rg_test_suite_stability_observation_external_finding_retention_progress
                SET progress_status = ?, event_after_object_id = ?,
                    deleted_event_count = ?, deleted_event_root = ?, event_complete = ?,
                    snapshot_after_object_id = ?, deleted_snapshot_count = ?,
                    deleted_snapshot_root = ?, snapshot_complete = ?, revision = ?,
                    completed_at = ?, updated_at = ?, record_fingerprint = ?
                WHERE projection_id = ? AND revision = ? AND record_fingerprint = ?
                """, material.updateArguments(expected));
        if (updated != 1) {
            throw new IllegalStateException(
                    "External finding evidence retirement progress fence was rejected");
        }
    }

    private EvidenceRetirement readMarker(String projectionId, boolean lock) {
        List<EvidenceRetirement> rows = jdbc.query("""
                SELECT projection_id, comparison_id, authority_id, retirement_status,
                       started_at, completed_at, record_fingerprint
                FROM
                    rg_test_suite_stability_observation_external_finding_evidence_retirements
                WHERE projection_id = ?
                """ + (lock ? " FOR UPDATE" : ""), this::evidenceRetirement, projectionId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "External finding evidence retirement marker is missing");
        }
        rows.getFirst().verify(objectMapper);
        return rows.getFirst();
    }

    private EvidenceRetirement evidenceRetirement(ResultSet result, int row) throws SQLException {
        return new EvidenceRetirement(result.getString("projection_id"),
                result.getString("comparison_id"), result.getString("authority_id"),
                result.getString("retirement_status"), instant(result, "started_at"),
                nullableInstant(result, "completed_at"),
                result.getString("record_fingerprint"));
    }

    private void completeMarker(EvidenceRetirement expected, Instant now) {
        EvidenceRetirement successor = expected.completed(now);
        successor = successor.withFingerprint(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .evidenceRetirementFingerprint(objectMapper, successor.projectionId(),
                                successor.comparisonId(), successor.authorityId(),
                                successor.status(), successor.startedAt(),
                                successor.completedAt()));
        int updated = jdbc.update("""
                UPDATE
                    rg_test_suite_stability_observation_external_finding_evidence_retirements
                SET retirement_status = ?, completed_at = ?, record_fingerprint = ?
                WHERE projection_id = ? AND retirement_status = ?
                  AND record_fingerprint = ?
                """, successor.status(), Timestamp.from(successor.completedAt()),
                successor.recordFingerprint(), expected.projectionId(), expected.status(),
                expected.recordFingerprint());
        if (updated != 1) {
            throw new IllegalStateException(
                    "External finding evidence retirement marker fence was rejected");
        }
    }

    private void verifyProgressCardinality(RetentionState state) {
        List<String> active = jdbc.query("""
                SELECT projection_id
                FROM rg_test_suite_stability_observation_external_finding_retention_progress
                WHERE progress_status = 'ACTIVE'
                ORDER BY projection_id
                """, (result, row) -> result.getString("projection_id"));
        List<String> activeMarkers = jdbc.query("""
                SELECT projection_id
                FROM
                    rg_test_suite_stability_observation_external_finding_evidence_retirements
                WHERE retirement_status = 'ACTIVE'
                ORDER BY projection_id
                """, (result, row) -> result.getString("projection_id"));
        if (!active.equals(activeMarkers)
                || (state.activeRetirementProjectionId().isEmpty() && !active.isEmpty())
                || (!state.activeRetirementProjectionId().isEmpty()
                && (active.size() != 1
                || !active.getFirst().equals(state.activeRetirementProjectionId())))) {
            throw new IllegalStateException(
                    "External finding active evidence retirement cardinality is corrupt");
        }
        Long completedProgress = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_finding_retention_progress
                WHERE progress_status = 'COMPLETED'
                """, Long.class);
        Long completedMarkers = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM
                    rg_test_suite_stability_observation_external_finding_evidence_retirements
                WHERE retirement_status = 'COMPLETED'
                """, Long.class);
        if (number(completedProgress) != state.totalProjectionsRetired()
                || number(completedMarkers) != state.totalProjectionsRetired()) {
            throw new IllegalStateException(
                    "External finding completed evidence retirement cardinality is corrupt");
        }
        if (!state.activeRetirementProjectionId().isEmpty()) {
            readProgress(state.activeRetirementProjectionId(), false);
            EvidenceRetirement marker = readMarker(
                    state.activeRetirementProjectionId(), false);
            if (!"ACTIVE".equals(marker.status())) {
                throw new IllegalStateException(
                        "External finding active retirement marker is corrupt");
            }
        }
    }

    private StoredAuthority readAuthority(String authorityId, boolean lock) {
        List<StoredAuthority> rows = jdbc.query("""
                SELECT authority_id, active_projection_id, last_completed_projection_id,
                       last_applied_comparison_id, last_applied_comparison_completed_at,
                       revision, updated_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_authorities
                WHERE authority_id = ?
                """ + (lock ? " FOR UPDATE" : ""), this::storedAuthority, authorityId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External finding authority state is missing");
        }
        rows.getFirst().verify(objectMapper);
        return rows.getFirst();
    }

    private StoredAuthority storedAuthority(ResultSet result, int row) throws SQLException {
        return new StoredAuthority(result.getString("authority_id"),
                result.getString("active_projection_id"),
                result.getString("last_completed_projection_id"),
                result.getString("last_applied_comparison_id"),
                nullableInstant(result, "last_applied_comparison_completed_at"),
                result.getLong("revision"), instant(result, "updated_at"),
                result.getString("record_fingerprint"));
    }

    private StoredProjection readProjection(String projectionId, boolean lock) {
        List<StoredProjection> rows = jdbc.query("""
                SELECT projection_id, comparison_id, authority_id, projection_status,
                       comparison_started_at, comparison_completed_at,
                       source_classification_count, source_classification_root,
                       snapshot_finding_count, snapshot_root, next_after_object_id,
                       next_page_sequence, processed_classification_count, opened_count,
                       observed_count, reopened_count, resolved_count, confirmed_count,
                       event_root, revision, started_at, completed_at, updated_at,
                       record_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_projections
                WHERE projection_id = ?
                """ + (lock ? " FOR UPDATE" : ""), this::storedProjection, projectionId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External finding projection is missing");
        }
        rows.getFirst().verify(objectMapper);
        return rows.getFirst();
    }

    private StoredProjection storedProjection(ResultSet result, int row) throws SQLException {
        return new StoredProjection(result.getString("projection_id"),
                result.getString("comparison_id"), result.getString("authority_id"),
                result.getString("projection_status"),
                instant(result, "comparison_started_at"),
                instant(result, "comparison_completed_at"),
                result.getLong("source_classification_count"),
                result.getString("source_classification_root"),
                result.getLong("snapshot_finding_count"), result.getString("snapshot_root"),
                result.getString("next_after_object_id"),
                result.getLong("next_page_sequence"),
                result.getLong("processed_classification_count"),
                result.getLong("opened_count"), result.getLong("observed_count"),
                result.getLong("reopened_count"), result.getLong("resolved_count"),
                result.getLong("confirmed_count"), result.getString("event_root"),
                result.getLong("revision"), instant(result, "started_at"),
                nullableInstant(result, "completed_at"), instant(result, "updated_at"),
                result.getString("record_fingerprint"));
    }

    private DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.Finding finding(
            ResultSet result,
            int row) throws SQLException {
        return finding(result, "record_fingerprint");
    }

    private DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.Finding finding(
            ResultSet result,
            String fingerprintColumn) throws SQLException {
        try {
            return new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                    .Finding(result.getString("authority_id"), result.getString("object_id"),
                    DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                            .FindingStatus.valueOf(result.getString("finding_status")),
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .Outcome.valueOf(result.getString("finding_kind")),
                    result.getString("latest_comparison_id"),
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .Outcome.valueOf(result.getString("latest_outcome")),
                    result.getString("latest_classification_fingerprint"),
                    result.getLong("occurrence_count"), result.getLong("episode_count"),
                    instant(result, "first_seen_at"), instant(result, "last_observed_at"),
                    instant(result, "last_evaluated_at"),
                    DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                            .Resolution.valueOf(result.getString("resolution")),
                    nullableInstant(result, "resolved_at"), result.getLong("finding_version"),
                    result.getString(fingerprintColumn));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("External finding storage is corrupt", invalid);
        }
    }

    private DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.FindingEvent
            findingEvent(ResultSet result, int row) throws SQLException {
        try {
            return new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                    .FindingEvent(result.getString("projection_id"),
                    result.getString("comparison_id"), result.getString("authority_id"),
                    result.getString("object_id"),
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .Outcome.valueOf(result.getString("classification_outcome")),
                    result.getString("classification_fingerprint"),
                    DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                            .Transition.valueOf(result.getString("transition")),
                    result.getString("previous_finding_fingerprint"),
                    result.getString("resulting_finding_fingerprint"),
                    result.getLong("resulting_finding_version"),
                    instant(result, "occurred_at"), result.getString("event_fingerprint"));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("External finding event storage is corrupt", invalid);
        }
    }

    private ArchivedFinding archivedFinding(ResultSet result, int row) throws SQLException {
        var source = finding(result, "source_record_fingerprint");
        return new ArchivedFinding(result.getString("archive_id"), source,
                instant(result, "archived_at"), result.getString("record_fingerprint"));
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Database clock returned no timestamp");
        }
        return value.toInstant();
    }

    private static PlatformTransactionManager localTransactionManager(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        if (jdbc.getDataSource() == null) {
            throw new IllegalArgumentException(
                    "External finding retention JDBC requires a datasource");
        }
        return new DataSourceTransactionManager(jdbc.getDataSource());
    }

    private static Duration boundedDuration(
            Duration value,
            Duration minimum,
            Duration maximum,
            String name) {
        Duration safe = Objects.requireNonNull(value, name);
        if (safe.compareTo(minimum) < 0 || safe.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " must be between "
                    + minimum + " and " + maximum);
        }
        return safe;
    }

    private static int boundedPageSize(int pageSize) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("External finding retention page must be 1 through "
                    + MAX_PAGE_SIZE);
        }
        return pageSize;
    }

    private static String requiredIdentifier(String value, String name) {
        String normalized = normalized(value);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid external " + name);
        }
        return normalized;
    }

    private static String requiredUuid(String value, String name) {
        String normalized = normalized(value);
        if (!isUuid(normalized)) {
            throw new IllegalArgumentException("Invalid external " + name);
        }
        return normalized;
    }

    private static String optionalUuid(String value, String name) {
        String normalized = normalized(value);
        return normalized.isEmpty() ? "" : requiredUuid(normalized, name);
    }

    private static String requiredFingerprint(String value, String name) {
        String normalized = normalized(value);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid external " + name);
        }
        return normalized;
    }

    private static String optionalObjectId(String value, String name) {
        String normalized = normalized(value);
        if (!normalized.isEmpty() && !OBJECT_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid external " + name);
        }
        return normalized;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static long increment(long value, String name) {
        try {
            return Math.incrementExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(name + " overflow", overflow);
        }
    }

    private static int increment(int value, String name) {
        try {
            return Math.incrementExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(name + " overflow", overflow);
        }
    }

    private static long add(long value, long delta, String name) {
        try {
            return Math.addExact(value, delta);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(name + " overflow", overflow);
        }
    }

    private static long number(Number value) {
        return value == null ? 0 : value.longValue();
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        if (value == null) {
            throw new IllegalStateException("External finding retention time is missing: " + column);
        }
        return value.toInstant();
    }

    private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static <T> T requiredResult(T result, String operation) {
        if (result == null) {
            throw new IllegalStateException("External " + operation + " returned no result");
        }
        return result;
    }

    /** Result status for one scheduled retention attempt. */
    public enum RetentionStatus {
        /** One bounded page committed. */
        COMPLETED,
        /** Another live replica owns the database-clock lease. */
        LEASE_BUSY
    }

    /**
     * Aggregate of one committed bounded retention page.
     *
     * @param findingsArchived resolved current findings copied and exactly removed
     * @param archivesPurged expired archive rows removed
     * @param eventsDeleted immutable transition events retired on this page
     * @param snapshotsDeleted frozen finding rows retired on this page
     * @param projectionRetired whether one projection passed terminal retirement replay
     * @param activeRetirementProjectionId remaining active projection, or empty
     * @param completedAt database commit time
     */
    public record RetentionResult(
            int findingsArchived,
            int archivesPurged,
            int eventsDeleted,
            int snapshotsDeleted,
            boolean projectionRetired,
            String activeRetirementProjectionId,
            Instant completedAt) {
        /** Validates bounded aggregate shape without exposing row identities. */
        public RetentionResult {
            if (findingsArchived < 0 || findingsArchived > MAX_PAGE_SIZE
                    || archivesPurged < 0 || archivesPurged > MAX_PAGE_SIZE
                    || eventsDeleted < 0 || eventsDeleted > MAX_PAGE_SIZE
                    || snapshotsDeleted < 0 || snapshotsDeleted > MAX_PAGE_SIZE
                    || completedAt == null) {
                throw new IllegalArgumentException(
                        "Invalid external finding retention aggregate");
            }
            activeRetirementProjectionId = optionalUuid(
                    activeRetirementProjectionId, "active retirement projection");
            if (projectionRetired && !activeRetirementProjectionId.isEmpty()) {
                throw new IllegalArgumentException(
                        "A completed retirement cannot remain active");
            }
        }
    }

    /**
     * Result of one leased retention attempt.
     *
     * @param status completed or lease busy
     * @param result committed aggregate only when completed
     */
    public record RetentionAttempt(RetentionStatus status, RetentionResult result) {
        /** Enforces an exact status/result union. */
        public RetentionAttempt {
            status = Objects.requireNonNull(status, "status");
            if ((status == RetentionStatus.COMPLETED) != (result != null)) {
                throw new IllegalArgumentException(
                        "Completed retention attempts require exactly one result");
            }
        }

        /** @return one committed retention attempt */
        public static RetentionAttempt completed(RetentionResult result) {
            return new RetentionAttempt(RetentionStatus.COMPLETED,
                    Objects.requireNonNull(result, "result"));
        }

        /** @return an attempt rejected by another replica's live lease */
        public static RetentionAttempt busy() {
            return new RetentionAttempt(RetentionStatus.LEASE_BUSY, null);
        }
    }

    /**
     * Immutable payload-free archive of one resolved finding lifecycle.
     *
     * @param archiveId archive identity
     * @param finding exact resolved source finding
     * @param archivedAt database archive time
     * @param recordFingerprint fingerprint over archive identity, source fingerprint, and time
     */
    public record ArchivedFinding(
            String archiveId,
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.Finding finding,
            Instant archivedAt,
            String recordFingerprint) {
        /** Validates archive shape; {@link #verify(ObjectMapper)} verifies canonical integrity. */
        public ArchivedFinding {
            archiveId = requiredUuid(archiveId, "finding archive ID");
            finding = Objects.requireNonNull(finding, "finding");
            if (finding.status()
                    != DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                    .FindingStatus.RESOLVED || finding.resolvedAt() == null
                    || archivedAt == null || archivedAt.isBefore(finding.resolvedAt())) {
                throw new IllegalArgumentException("Invalid external finding archive");
            }
            recordFingerprint = normalized(recordFingerprint);
            if (!recordFingerprint.isEmpty()
                    && !FINGERPRINT.matcher(recordFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "Invalid external finding archive fingerprint");
            }
        }

        /** @param objectMapper canonical mapper used for source and archive verification */
        public void verify(ObjectMapper objectMapper) {
            finding.verify(objectMapper);
            if (recordFingerprint.isEmpty()
                    || !recordFingerprint.equals(ProtocolFingerprint.of(
                    Objects.requireNonNull(objectMapper, "objectMapper"),
                    fingerprintMaterial()))) {
                throw new IllegalStateException("External finding archive is corrupt");
            }
        }

        private ArchivedFinding withFingerprint(String fingerprint) {
            return new ArchivedFinding(archiveId, finding, archivedAt, fingerprint);
        }

        private ArchivedFindingMaterial fingerprintMaterial() {
            return new ArchivedFindingMaterial(ArchivedFindingMaterial.SCHEMA_VERSION,
                    archiveId, finding.recordFingerprint(), archivedAt);
        }

        private Object[] sqlArguments() {
            return new Object[]{archiveId, finding.authorityId(), finding.objectId(),
                    finding.status().name(), finding.kind().name(),
                    finding.latestComparisonId(), finding.latestOutcome().name(),
                    finding.latestClassificationFingerprint(), finding.occurrences(),
                    finding.episodes(), Timestamp.from(finding.firstSeenAt()),
                    Timestamp.from(finding.lastObservedAt()),
                    Timestamp.from(finding.lastEvaluatedAt()), finding.resolution().name(),
                    Timestamp.from(finding.resolvedAt()), finding.version(),
                    finding.recordFingerprint(), Timestamp.from(archivedAt), recordFingerprint};
        }
    }

    /**
     * Payload-free retention state for readiness and bounded-cardinality telemetry.
     *
     * @param observedAt database observation time
     * @param leaseActive whether any replica currently owns the retention lease
     * @param activeRetirement whether an evidence retirement is incomplete
     * @param totalFindingsArchived cumulative resolved findings archived
     * @param totalArchivesPurged cumulative archive rows purged
     * @param totalEventsDeleted cumulative transition events retired
     * @param totalSnapshotsDeleted cumulative frozen findings retired
     * @param totalProjectionsRetired cumulative projections terminally retired
     * @param archiveSize current verified archive cardinality
     * @param openFindings current unresolved findings
     * @param overdueResolvedFindings resolved findings past the configured active window
     * @param overdueArchives archives past their configured window
     * @param overdueEvidence completed projections past their configured evidence window
     * @param lastSuccessAt last committed retention page, or {@code null}
     */
    public record OperationalSnapshot(
            Instant observedAt,
            boolean leaseActive,
            boolean activeRetirement,
            long totalFindingsArchived,
            long totalArchivesPurged,
            long totalEventsDeleted,
            long totalSnapshotsDeleted,
            long totalProjectionsRetired,
            long archiveSize,
            long openFindings,
            long overdueResolvedFindings,
            long overdueArchives,
            long overdueEvidence,
            Instant lastSuccessAt) {
        /** Validates non-negative low-cardinality observations. */
        public OperationalSnapshot {
            if (observedAt == null || totalFindingsArchived < 0 || totalArchivesPurged < 0
                    || totalEventsDeleted < 0 || totalSnapshotsDeleted < 0
                    || totalProjectionsRetired < 0 || archiveSize < 0 || openFindings < 0
                    || overdueResolvedFindings < 0 || overdueArchives < 0
                    || overdueEvidence < 0 || totalArchivesPurged > totalFindingsArchived
                    || archiveSize != totalFindingsArchived - totalArchivesPurged
                    || (lastSuccessAt != null && lastSuccessAt.isAfter(observedAt))) {
                throw new IllegalArgumentException(
                        "Invalid external finding retention snapshot");
            }
        }
    }

    private record RetentionLease(
            String ownerId,
            String token,
            long epoch,
            Instant leaseUntil) {
        private RetentionLease {
            ownerId = requiredIdentifier(ownerId, "retention lease owner");
            token = requiredUuid(token, "retention lease token");
            if (epoch < 1 || leaseUntil == null) {
                throw new IllegalArgumentException("Invalid external finding retention lease");
            }
        }
    }

    private record PurgeMutation(int purged, String root) {
        private PurgeMutation {
            if (purged < 0 || purged > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("Invalid archive purge page");
            }
            root = requiredFingerprint(root, "archive purge root");
        }
    }

    private record EvidenceMutation(
            int eventsDeleted,
            int snapshotsDeleted,
            boolean projectionRetired,
            String activeProjectionId) {
        private EvidenceMutation {
            if (eventsDeleted < 0 || eventsDeleted > MAX_PAGE_SIZE
                    || snapshotsDeleted < 0 || snapshotsDeleted > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("Invalid evidence retirement page");
            }
            activeProjectionId = optionalUuid(
                    activeProjectionId, "active evidence retirement");
            if (projectionRetired && !activeProjectionId.isEmpty()) {
                throw new IllegalArgumentException("Completed evidence retirement remains active");
            }
        }

        private static EvidenceMutation none() {
            return new EvidenceMutation(0, 0, false, "");
        }
    }

    private record SegmentProgress(
            String cursor,
            long totalDeleted,
            String root,
            boolean complete,
            int deletedOnPage) {
        private SegmentProgress {
            cursor = optionalObjectId(cursor, "evidence retirement cursor");
            root = requiredFingerprint(root, "evidence retirement root");
            if (totalDeleted < 0 || deletedOnPage < 0 || deletedOnPage > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("Invalid evidence segment progress");
            }
        }

        private static SegmentProgress completed(String cursor, long count, String root) {
            return new SegmentProgress(cursor, count, root, true, 0);
        }
    }

    private record StoredAuthority(
            String authorityId,
            String activeProjectionId,
            String lastCompletedProjectionId,
            String lastAppliedComparisonId,
            Instant lastAppliedComparisonCompletedAt,
            long revision,
            Instant updatedAt,
            String recordFingerprint) {
        private void verify(ObjectMapper objectMapper) {
            boolean initial = lastCompletedProjectionId.isEmpty()
                    && lastAppliedComparisonId.isEmpty()
                    && lastAppliedComparisonCompletedAt == null;
            boolean completed = isUuid(lastCompletedProjectionId)
                    && isUuid(lastAppliedComparisonId)
                    && lastAppliedComparisonCompletedAt != null;
            if (!IDENTIFIER.matcher(normalized(authorityId)).matches()
                    || (!activeProjectionId.isEmpty() && !isUuid(activeProjectionId))
                    || (!initial && !completed) || revision < 0 || updatedAt == null
                    || !FINGERPRINT.matcher(normalized(recordFingerprint)).matches()
                    || !recordFingerprint.equals(ProtocolFingerprint.of(objectMapper,
                    new FindingAuthorityMaterial(FindingAuthorityMaterial.SCHEMA_VERSION,
                            authorityId, activeProjectionId, lastCompletedProjectionId,
                            lastAppliedComparisonId, lastAppliedComparisonCompletedAt,
                            revision, updatedAt)))) {
                throw new IllegalStateException("External finding authority state is corrupt");
            }
        }
    }

    private record StoredProjection(
            String projectionId,
            String comparisonId,
            String authorityId,
            String status,
            Instant comparisonStartedAt,
            Instant comparisonCompletedAt,
            long sourceCount,
            String sourceRoot,
            long snapshotCount,
            String snapshotRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long processedCount,
            long openedCount,
            long observedCount,
            long reopenedCount,
            long resolvedCount,
            long confirmedCount,
            String eventRoot,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt,
            String recordFingerprint) {
        private void verify(ObjectMapper objectMapper) {
            long sum;
            try {
                sum = Math.addExact(Math.addExact(Math.addExact(openedCount, observedCount),
                        Math.addExact(reopenedCount, resolvedCount)), confirmedCount);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(
                        "External finding projection counters are corrupt", overflow);
            }
            boolean active = "ACTIVE".equals(status);
            boolean completed = "COMPLETED".equals(status);
            if (!isUuid(projectionId) || !isUuid(comparisonId)
                    || !IDENTIFIER.matcher(normalized(authorityId)).matches()
                    || (!active && !completed) || comparisonStartedAt == null
                    || comparisonCompletedAt == null
                    || comparisonCompletedAt.isBefore(comparisonStartedAt) || sourceCount < 0
                    || !FINGERPRINT.matcher(normalized(sourceRoot)).matches()
                    || snapshotCount < 0
                    || !FINGERPRINT.matcher(normalized(snapshotRoot)).matches()
                    || (!normalized(nextAfterObjectId).isEmpty()
                    && !OBJECT_ID.matcher(nextAfterObjectId).matches())
                    || nextPageSequence < 0 || processedCount < 0 || sum != processedCount
                    || processedCount > sourceCount
                    || !FINGERPRINT.matcher(normalized(eventRoot)).matches()
                    || revision < 0 || startedAt == null || updatedAt == null
                    || (active != (completedAt == null)) || updatedAt.isBefore(startedAt)
                    || (completedAt != null && (completedAt.isBefore(startedAt)
                    || updatedAt.isBefore(completedAt)))
                    || !FINGERPRINT.matcher(normalized(recordFingerprint)).matches()
                    || !recordFingerprint.equals(ProtocolFingerprint.of(objectMapper,
                    fingerprintMaterial()))) {
                throw new IllegalStateException("External finding projection state is corrupt");
            }
        }

        private void requireCompleted() {
            if (!"COMPLETED".equals(status) || completedAt == null) {
                throw new IllegalStateException("External finding projection is not complete");
            }
        }

        private FindingProjectionMaterial fingerprintMaterial() {
            return new FindingProjectionMaterial(FindingProjectionMaterial.SCHEMA_VERSION,
                    projectionId, comparisonId, authorityId, status, comparisonStartedAt,
                    comparisonCompletedAt, sourceCount, sourceRoot, snapshotCount, snapshotRoot,
                    nextAfterObjectId, nextPageSequence, processedCount, openedCount,
                    observedCount, reopenedCount, resolvedCount, confirmedCount, eventRoot,
                    revision, startedAt, completedAt, updatedAt);
        }
    }

    private record RetentionState(
            String jobName,
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseUntil,
            String activeRetirementProjectionId,
            long revision,
            long totalFindingsArchived,
            long totalArchivesPurged,
            long totalEventsDeleted,
            long totalSnapshotsDeleted,
            long totalProjectionsRetired,
            String archivePurgeRoot,
            Instant lastSuccessAt,
            Instant updatedAt,
            String recordFingerprint) {
        private static RetentionState initial(Instant now) {
            return new RetentionState(JOB_NAME, "", "", 0, Instant.EPOCH, "", 0,
                    0, 0, 0, 0, 0, EMPTY_ARCHIVE_PURGE_ROOT, null, now, "");
        }

        private void verify(ObjectMapper objectMapper) {
            boolean leased = !normalized(leaseOwner).isEmpty();
            boolean tokenPresent = isUuid(normalized(leaseToken));
            if (!JOB_NAME.equals(jobName) || leased != tokenPresent || leaseEpoch < 0
                    || (leased && (!IDENTIFIER.matcher(leaseOwner).matches()
                    || leaseEpoch < 1 || leaseUntil == null
                    || !leaseUntil.isAfter(Instant.EPOCH)))
                    || (!leased && (leaseUntil == null
                    || !leaseUntil.equals(Instant.EPOCH)))
                    || (!activeRetirementProjectionId.isEmpty()
                    && !isUuid(activeRetirementProjectionId))
                    || revision < 0 || totalFindingsArchived < 0 || totalArchivesPurged < 0
                    || totalEventsDeleted < 0 || totalSnapshotsDeleted < 0
                    || totalProjectionsRetired < 0
                    || totalArchivesPurged > totalFindingsArchived
                    || !FINGERPRINT.matcher(normalized(archivePurgeRoot)).matches()
                    || updatedAt == null
                    || (lastSuccessAt != null && lastSuccessAt.isAfter(updatedAt))
                    || !FINGERPRINT.matcher(normalized(recordFingerprint)).matches()
                    || !recordFingerprint.equals(ProtocolFingerprint.of(
                    objectMapper, fingerprintMaterial()))) {
                throw new IllegalStateException("External finding retention state is corrupt");
            }
        }

        private boolean matches(RetentionLease lease) {
            return leaseOwner.equals(lease.ownerId()) && leaseToken.equals(lease.token())
                    && leaseEpoch == lease.epoch() && leaseUntil.equals(lease.leaseUntil());
        }

        private RetentionState leased(RetentionLease lease, Instant now) {
            return new RetentionState(jobName, lease.ownerId(), lease.token(), lease.epoch(),
                    lease.leaseUntil(), activeRetirementProjectionId,
                    increment(revision, "retention revision"), totalFindingsArchived,
                    totalArchivesPurged, totalEventsDeleted, totalSnapshotsDeleted,
                    totalProjectionsRetired, archivePurgeRoot, lastSuccessAt, now, "");
        }

        private RetentionState released(Instant now) {
            return new RetentionState(jobName, "", "", leaseEpoch, Instant.EPOCH,
                    activeRetirementProjectionId, increment(revision, "retention revision"),
                    totalFindingsArchived, totalArchivesPurged, totalEventsDeleted,
                    totalSnapshotsDeleted, totalProjectionsRetired, archivePurgeRoot,
                    lastSuccessAt, now, "");
        }

        private RetentionState completed(
                String activeProjectionId,
                long archived,
                long purged,
                long events,
                long snapshots,
                long retired,
                String purgeRoot,
                Instant now) {
            return new RetentionState(jobName, "", "", leaseEpoch, Instant.EPOCH,
                    optionalUuid(activeProjectionId, "active retirement projection"),
                    increment(revision, "retention revision"),
                    add(totalFindingsArchived, archived, "total archived findings"),
                    add(totalArchivesPurged, purged, "total purged archives"),
                    add(totalEventsDeleted, events, "total deleted events"),
                    add(totalSnapshotsDeleted, snapshots, "total deleted snapshots"),
                    add(totalProjectionsRetired, retired, "total retired projections"),
                    requiredFingerprint(purgeRoot, "archive purge root"), now, now, "");
        }

        private RetentionState withFingerprint(String fingerprint) {
            return new RetentionState(jobName, leaseOwner, leaseToken, leaseEpoch, leaseUntil,
                    activeRetirementProjectionId, revision, totalFindingsArchived,
                    totalArchivesPurged, totalEventsDeleted, totalSnapshotsDeleted,
                    totalProjectionsRetired, archivePurgeRoot, lastSuccessAt, updatedAt,
                    fingerprint);
        }

        private RetentionStateMaterial fingerprintMaterial() {
            return new RetentionStateMaterial(RetentionStateMaterial.SCHEMA_VERSION, jobName,
                    leaseOwner, leaseToken, leaseEpoch, leaseUntil,
                    activeRetirementProjectionId, revision, totalFindingsArchived,
                    totalArchivesPurged, totalEventsDeleted, totalSnapshotsDeleted,
                    totalProjectionsRetired, archivePurgeRoot, lastSuccessAt, updatedAt);
        }

        private Object[] sqlArguments() {
            return new Object[]{jobName, leaseOwner, leaseToken, leaseEpoch,
                    Timestamp.from(leaseUntil), activeRetirementProjectionId, revision,
                    totalFindingsArchived, totalArchivesPurged, totalEventsDeleted,
                    totalSnapshotsDeleted, totalProjectionsRetired, archivePurgeRoot,
                    timestamp(lastSuccessAt), Timestamp.from(updatedAt), recordFingerprint};
        }

        private Object[] updateArguments(RetentionState expected) {
            return new Object[]{leaseOwner, leaseToken, leaseEpoch, Timestamp.from(leaseUntil),
                    activeRetirementProjectionId, revision, totalFindingsArchived,
                    totalArchivesPurged, totalEventsDeleted, totalSnapshotsDeleted,
                    totalProjectionsRetired, archivePurgeRoot, timestamp(lastSuccessAt),
                    Timestamp.from(updatedAt), recordFingerprint, expected.jobName,
                    expected.revision, expected.recordFingerprint};
        }

        private OperationalSnapshot snapshot(
                Instant observedAt,
                long openFindings,
                long overdueResolved,
                long overdueArchives,
                long overdueEvidence) {
            return new OperationalSnapshot(observedAt, leaseUntil.isAfter(observedAt),
                    !activeRetirementProjectionId.isEmpty(), totalFindingsArchived,
                    totalArchivesPurged, totalEventsDeleted, totalSnapshotsDeleted,
                    totalProjectionsRetired,
                    Math.subtractExact(totalFindingsArchived, totalArchivesPurged),
                    openFindings, overdueResolved, overdueArchives, overdueEvidence,
                    lastSuccessAt);
        }
    }

    private record RetentionProgress(
            String projectionId,
            String comparisonId,
            String authorityId,
            String status,
            String sourceProjectionFingerprint,
            long expectedEventCount,
            String expectedEventRoot,
            long expectedSnapshotCount,
            String expectedSnapshotRoot,
            String eventAfterObjectId,
            long deletedEventCount,
            String deletedEventRoot,
            boolean eventComplete,
            String snapshotAfterObjectId,
            long deletedSnapshotCount,
            String deletedSnapshotRoot,
            boolean snapshotComplete,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt,
            String recordFingerprint) {
        private static RetentionProgress active(StoredProjection source, Instant now) {
            return new RetentionProgress(source.projectionId(), source.comparisonId(),
                    source.authorityId(), "ACTIVE", source.recordFingerprint(),
                    source.processedCount(), source.eventRoot(), source.snapshotCount(),
                    source.snapshotRoot(), "", 0,
                    DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                            .EMPTY_EVENT_ROOT,
                    false, "", 0,
                    DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                            .EMPTY_SNAPSHOT_ROOT,
                    false, 0, now, null, now, "");
        }

        private void verify(ObjectMapper objectMapper) {
            boolean active = "ACTIVE".equals(status);
            boolean completed = "COMPLETED".equals(status);
            if (!isUuid(projectionId) || !isUuid(comparisonId)
                    || !IDENTIFIER.matcher(normalized(authorityId)).matches()
                    || (!active && !completed)
                    || !FINGERPRINT.matcher(normalized(sourceProjectionFingerprint)).matches()
                    || expectedEventCount < 0
                    || !FINGERPRINT.matcher(normalized(expectedEventRoot)).matches()
                    || expectedSnapshotCount < 0
                    || !FINGERPRINT.matcher(normalized(expectedSnapshotRoot)).matches()
                    || !validCursor(eventAfterObjectId) || deletedEventCount < 0
                    || deletedEventCount > expectedEventCount
                    || !FINGERPRINT.matcher(normalized(deletedEventRoot)).matches()
                    || !validCursor(snapshotAfterObjectId) || deletedSnapshotCount < 0
                    || deletedSnapshotCount > expectedSnapshotCount
                    || !FINGERPRINT.matcher(normalized(deletedSnapshotRoot)).matches()
                    || active != (completedAt == null) || revision < 0 || startedAt == null
                    || updatedAt == null || updatedAt.isBefore(startedAt)
                    || (completedAt != null && (completedAt.isBefore(startedAt)
                    || updatedAt.isBefore(completedAt)))) {
                throw new IllegalStateException(
                        "External finding evidence retirement progress shape is corrupt");
            }
            boolean eventsAtTerminal = deletedEventCount == expectedEventCount
                    && deletedEventRoot.equals(expectedEventRoot);
            boolean snapshotsAtTerminal = deletedSnapshotCount == expectedSnapshotCount
                    && deletedSnapshotRoot.equals(expectedSnapshotRoot);
            if ((eventComplete && !eventsAtTerminal)
                    || (snapshotComplete && !snapshotsAtTerminal)
                    || completed != (eventComplete && snapshotComplete)) {
                throw new IllegalStateException(
                        "External finding evidence retirement completion state is corrupt");
            }
            if (!FINGERPRINT.matcher(normalized(recordFingerprint)).matches()
                    || !recordFingerprint.equals(ProtocolFingerprint.of(
                    objectMapper, fingerprintMaterial()))) {
                throw new IllegalStateException(
                        "External finding evidence retirement progress fingerprint is corrupt");
            }
        }

        private void verifySource(StoredProjection source) {
            source.requireCompleted();
            if (!projectionId.equals(source.projectionId())
                    || !comparisonId.equals(source.comparisonId())
                    || !authorityId.equals(source.authorityId())
                    || !sourceProjectionFingerprint.equals(source.recordFingerprint())
                    || expectedEventCount != source.processedCount()
                    || !expectedEventRoot.equals(source.eventRoot())
                    || expectedSnapshotCount != source.snapshotCount()
                    || !expectedSnapshotRoot.equals(source.snapshotRoot())) {
                throw new IllegalStateException(
                        "External finding evidence retirement source drifted");
            }
        }

        private RetentionProgress progressed(
                SegmentProgress events,
                SegmentProgress snapshots,
                boolean complete,
                Instant now) {
            return new RetentionProgress(projectionId, comparisonId, authorityId,
                    complete ? "COMPLETED" : "ACTIVE", sourceProjectionFingerprint,
                    expectedEventCount, expectedEventRoot, expectedSnapshotCount,
                    expectedSnapshotRoot, events.cursor(), events.totalDeleted(), events.root(),
                    events.complete(), snapshots.cursor(), snapshots.totalDeleted(),
                    snapshots.root(), snapshots.complete(),
                    increment(revision, "retirement progress revision"), startedAt,
                    complete ? now : null, now, "");
        }

        private RetentionProgress withFingerprint(String fingerprint) {
            return new RetentionProgress(projectionId, comparisonId, authorityId, status,
                    sourceProjectionFingerprint, expectedEventCount, expectedEventRoot,
                    expectedSnapshotCount, expectedSnapshotRoot, eventAfterObjectId,
                    deletedEventCount, deletedEventRoot, eventComplete, snapshotAfterObjectId,
                    deletedSnapshotCount, deletedSnapshotRoot, snapshotComplete, revision,
                    startedAt, completedAt, updatedAt, fingerprint);
        }

        private RetentionProgressMaterial fingerprintMaterial() {
            return new RetentionProgressMaterial(RetentionProgressMaterial.SCHEMA_VERSION,
                    projectionId, comparisonId, authorityId, status,
                    sourceProjectionFingerprint, expectedEventCount, expectedEventRoot,
                    expectedSnapshotCount, expectedSnapshotRoot, eventAfterObjectId,
                    deletedEventCount, deletedEventRoot, eventComplete, snapshotAfterObjectId,
                    deletedSnapshotCount, deletedSnapshotRoot, snapshotComplete, revision,
                    startedAt, completedAt, updatedAt);
        }

        private Object[] sqlArguments() {
            return new Object[]{projectionId, comparisonId, authorityId, status,
                    sourceProjectionFingerprint, expectedEventCount, expectedEventRoot,
                    expectedSnapshotCount, expectedSnapshotRoot, eventAfterObjectId,
                    deletedEventCount, deletedEventRoot, eventComplete, snapshotAfterObjectId,
                    deletedSnapshotCount, deletedSnapshotRoot, snapshotComplete, revision,
                    Timestamp.from(startedAt), timestamp(completedAt), Timestamp.from(updatedAt),
                    recordFingerprint};
        }

        private Object[] updateArguments(RetentionProgress expected) {
            return new Object[]{status, eventAfterObjectId, deletedEventCount,
                    deletedEventRoot, eventComplete, snapshotAfterObjectId,
                    deletedSnapshotCount, deletedSnapshotRoot, snapshotComplete, revision,
                    timestamp(completedAt), Timestamp.from(updatedAt), recordFingerprint,
                    expected.projectionId, expected.revision, expected.recordFingerprint};
        }

        private static boolean validCursor(String cursor) {
            String value = normalized(cursor);
            return value.isEmpty() || OBJECT_ID.matcher(value).matches();
        }
    }

    private record EvidenceRetirement(
            String projectionId,
            String comparisonId,
            String authorityId,
            String status,
            Instant startedAt,
            Instant completedAt,
            String recordFingerprint) {
        private static EvidenceRetirement active(StoredProjection source, Instant now) {
            return new EvidenceRetirement(source.projectionId(), source.comparisonId(),
                    source.authorityId(), "ACTIVE", now, null, "");
        }

        private void verify(ObjectMapper objectMapper) {
            boolean active = "ACTIVE".equals(status);
            boolean completed = "COMPLETED".equals(status);
            if (!isUuid(projectionId) || !isUuid(comparisonId)
                    || !IDENTIFIER.matcher(normalized(authorityId)).matches()
                    || (!active && !completed) || startedAt == null
                    || (active != (completedAt == null))
                    || (completedAt != null && completedAt.isBefore(startedAt))
                    || !FINGERPRINT.matcher(normalized(recordFingerprint)).matches()
                    || !recordFingerprint.equals(
                    DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                            .evidenceRetirementFingerprint(objectMapper, projectionId,
                                    comparisonId, authorityId, status, startedAt, completedAt))) {
                throw new IllegalStateException(
                        "External finding evidence retirement marker is corrupt");
            }
        }

        private void verifySource(RetentionProgress progress) {
            if (!projectionId.equals(progress.projectionId())
                    || !comparisonId.equals(progress.comparisonId())
                    || !authorityId.equals(progress.authorityId())) {
                throw new IllegalStateException(
                        "External finding evidence retirement marker source drifted");
            }
        }

        private EvidenceRetirement completed(Instant now) {
            return new EvidenceRetirement(projectionId, comparisonId, authorityId,
                    "COMPLETED", startedAt, now, "");
        }

        private EvidenceRetirement withFingerprint(String fingerprint) {
            return new EvidenceRetirement(projectionId, comparisonId, authorityId, status,
                    startedAt, completedAt, fingerprint);
        }

        private Object[] sqlArguments() {
            return new Object[]{projectionId, comparisonId, authorityId, status,
                    Timestamp.from(startedAt), timestamp(completedAt), recordFingerprint};
        }
    }

    private record ArchivedFindingMaterial(
            String schemaVersion,
            String archiveId,
            String sourceFindingFingerprint,
            Instant archivedAt) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveFindingArchive.v1";
    }

    private record ArchivePurgeRootLink(
            String schemaVersion,
            String previousRoot,
            String archiveFingerprint) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveFindingPurgeRootLink.v1";
    }

    private record FindingAuthorityMaterial(
            String schemaVersion,
            String authorityId,
            String activeProjectionId,
            String lastCompletedProjectionId,
            String lastAppliedComparisonId,
            Instant lastAppliedComparisonCompletedAt,
            long revision,
            Instant updatedAt) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveFindingAuthority.v1";
    }

    private record FindingProjectionMaterial(
            String schemaVersion,
            String projectionId,
            String comparisonId,
            String authorityId,
            String status,
            Instant comparisonStartedAt,
            Instant comparisonCompletedAt,
            long sourceClassificationCount,
            String sourceClassificationRoot,
            long snapshotFindingCount,
            String snapshotRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long processedClassificationCount,
            long openedCount,
            long observedCount,
            long reopenedCount,
            long resolvedCount,
            long confirmedCount,
            String eventRoot,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveFindingProjection.v1";
    }

    private record RetentionStateMaterial(
            String schemaVersion,
            String jobName,
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseUntil,
            String activeRetirementProjectionId,
            long revision,
            long totalFindingsArchived,
            long totalArchivesPurged,
            long totalEventsDeleted,
            long totalSnapshotsDeleted,
            long totalProjectionsRetired,
            String archivePurgeRoot,
            Instant lastSuccessAt,
            Instant updatedAt) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveFindingRetentionState.v1";
    }

    private record RetentionProgressMaterial(
            String schemaVersion,
            String projectionId,
            String comparisonId,
            String authorityId,
            String status,
            String sourceProjectionFingerprint,
            long expectedEventCount,
            String expectedEventRoot,
            long expectedSnapshotCount,
            String expectedSnapshotRoot,
            String eventAfterObjectId,
            long deletedEventCount,
            String deletedEventRoot,
            boolean eventComplete,
            String snapshotAfterObjectId,
            long deletedSnapshotCount,
            String deletedSnapshotRoot,
            boolean snapshotComplete,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveFindingRetentionProgress.v1";
    }
}
