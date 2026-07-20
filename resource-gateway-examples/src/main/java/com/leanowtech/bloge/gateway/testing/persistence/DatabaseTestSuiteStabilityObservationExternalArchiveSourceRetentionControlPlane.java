package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryItem;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryPage;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationExternalArchiveInventoryIntegrity;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Database-fenced bounded retirement for external reconciliation source history.
 *
 * <p>This authority owns only Resource Gateway's local inventory, expected-snapshot,
 * classification, and comparison staging history. It never mutates the external WORM archive or
 * the governed finding projection. Processed history becomes eligible only after finding evidence
 * has its own completed retirement marker and a newer inventory, comparison, and finding authority
 * position exists. Expired unprocessed snapshots have a separate eligibility path.</p>
 *
 * <p>One database-clock lease serializes replicas. A call deletes at most one bounded child-table
 * page, in dependency order, and commits cumulative count/root proof with the exact deletes. The
 * permanent progress row is also the retirement marker. Once active, a marker is never removed;
 * corruption therefore leaves an explicit quarantine instead of allowing a later retry to launder
 * incomplete history.</p>
 */
public final class
        DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane {
    /** Domain-separated proof root before the first retired signed page. */
    public static final String EMPTY_PAGE_ROOT = ProtocolFingerprint.ofText(
            "bloge.testSuiteStabilityObservationExternalArchivePageRetirementRoot.v1:empty");

    private static final String JOB_NAME = "external-archive-source-retention";
    private static final String STATE_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalArchiveSourceRetentionState.v1";
    private static final String PROGRESS_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalArchiveSourceRetentionProgress.v1";
    private static final String PAGE_ROOT_LINK_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalArchivePageRetirementRootLink.v1";
    private static final int MAX_PAGE_SIZE = 500;
    private static final Duration MIN_LEASE = Duration.ofSeconds(1);
    private static final Duration MAX_LEASE = Duration.ofHours(1);
    private static final Duration MIN_RETENTION = Duration.ofDays(1);
    private static final Duration MAX_RETENTION = Duration.ofDays(3650);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityObservationExternalArchiveInventoryAuthority inventoryAuthority;
    private final TransactionTemplate transactions;
    private final TransactionTemplate observations;
    private final String ownerId;
    private final Duration leaseDuration;

    /**
     * Creates a source-retention authority over the reconciliation datasource.
     *
     * @param jdbc isolated testing-control-plane JDBC facade
     * @param transactionManager transaction manager bound to the same datasource
     * @param objectMapper canonical protocol mapper
     * @param inventoryAuthority historical signed-page verification authority
     * @param ownerId stable Resource Gateway replica identity
     * @param leaseDuration database-clock lease from one second through one hour
     */
    public DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationExternalArchiveInventoryAuthority inventoryAuthority,
            String ownerId,
            Duration leaseDuration) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.inventoryAuthority = Objects.requireNonNull(inventoryAuthority, "inventoryAuthority");
        this.ownerId = requiredIdentifier(ownerId, "source-retention owner");
        this.leaseDuration = boundedDuration(
                leaseDuration, MIN_LEASE, MAX_LEASE, "source-retention lease");
        PlatformTransactionManager manager = Objects.requireNonNull(
                transactionManager, "transactionManager");
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        observations = new TransactionTemplate(manager);
        observations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        observations.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** Creates the singleton lease state and permanent source-retirement progress table. */
    @PostConstruct
    public void init() {
        ExternalArchiveSourceRetirementIntegrity.initializeMarkerTable(jdbc);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_source_retention_progress (
                    cycle_id VARCHAR(36) PRIMARY KEY,
                    comparison_id VARCHAR(36) NOT NULL,
                    authority_id VARCHAR(255) NOT NULL,
                    retirement_mode VARCHAR(32) NOT NULL,
                    progress_status VARCHAR(32) NOT NULL,
                    source_cycle_fingerprint VARCHAR(71) NOT NULL,
                    source_comparison_fingerprint VARCHAR(71) NOT NULL,
                    expected_classification_count BIGINT NOT NULL,
                    expected_classification_root VARCHAR(71) NOT NULL,
                    classification_after_object_id VARCHAR(255) NOT NULL,
                    deleted_classification_count BIGINT NOT NULL,
                    deleted_classification_root VARCHAR(71) NOT NULL,
                    classification_complete BOOLEAN NOT NULL,
                    expected_snapshot_count BIGINT NOT NULL,
                    expected_snapshot_root VARCHAR(71) NOT NULL,
                    expected_after_object_id VARCHAR(255) NOT NULL,
                    deleted_expected_count BIGINT NOT NULL,
                    deleted_expected_root VARCHAR(71) NOT NULL,
                    expected_complete BOOLEAN NOT NULL,
                    inventory_item_count BIGINT NOT NULL,
                    inventory_item_root VARCHAR(71) NOT NULL,
                    item_after_object_id VARCHAR(255) NOT NULL,
                    deleted_item_count BIGINT NOT NULL,
                    deleted_item_root VARCHAR(71) NOT NULL,
                    item_complete BOOLEAN NOT NULL,
                    inventory_page_count BIGINT NOT NULL,
                    page_after_sequence BIGINT NOT NULL,
                    deleted_page_count BIGINT NOT NULL,
                    deleted_page_root VARCHAR(71) NOT NULL,
                    page_complete BOOLEAN NOT NULL,
                    revision BIGINT NOT NULL,
                    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    completed_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_external_source_retention_status
                ON rg_test_suite_stability_observation_external_source_retention_progress (
                    progress_status, started_at, cycle_id
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_source_retention_state (
                    job_name VARCHAR(128) PRIMARY KEY,
                    lease_owner VARCHAR(255) NOT NULL,
                    lease_token VARCHAR(36) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    active_cycle_id VARCHAR(36) NOT NULL,
                    revision BIGINT NOT NULL,
                    total_classifications_deleted BIGINT NOT NULL,
                    total_expected_deleted BIGINT NOT NULL,
                    total_items_deleted BIGINT NOT NULL,
                    total_pages_deleted BIGINT NOT NULL,
                    total_sources_retired BIGINT NOT NULL,
                    last_success_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        initializeState();
    }

    /**
     * Executes one leased source-retirement step.
     *
     * @param processedRetention completed and fully governed source-history retention
     * @param expiredRetention terminal unprocessed snapshot retention
     * @param pageSize maximum rows deleted from one child table in this call
     * @return committed bounded result or a lease-busy response
     */
    public RetentionAttempt retain(
            Duration processedRetention,
            Duration expiredRetention,
            int pageSize) {
        Duration processed = boundedDuration(processedRetention, MIN_RETENTION,
                MAX_RETENTION, "processed source retention");
        Duration expired = boundedDuration(expiredRetention, MIN_RETENTION,
                MAX_RETENTION, "expired source retention");
        int page = boundedPageSize(pageSize);
        Optional<RetentionLease> lease = acquireLease();
        if (lease.isEmpty()) {
            return RetentionAttempt.busy();
        }
        try {
            return retainClaimed(lease.orElseThrow(), processed, expired, page);
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
     * Returns aggregate source-retention state without source, cursor, or fingerprint identities.
     *
     * @param processedRetention current processed-source policy
     * @param expiredRetention current expired-snapshot policy
     * @return verified database-clock backlog and cumulative counters
     */
    public OperationalSnapshot operationalSnapshot(
            Duration processedRetention,
            Duration expiredRetention) {
        Duration processed = boundedDuration(processedRetention, MIN_RETENTION,
                MAX_RETENTION, "processed source retention");
        Duration expired = boundedDuration(expiredRetention, MIN_RETENTION,
                MAX_RETENTION, "expired source retention");
        OperationalSnapshot result = observations.execute(status -> {
            RetentionState state = readState(false);
            verifyAggregateCounters(state);
            Instant now = databaseNow();
            Long active = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM rg_test_suite_stability_observation_external_source_retention_progress
                    WHERE progress_status = 'ACTIVE'
                    """, Long.class);
            Long processedBacklog = jdbc.queryForObject(processedCandidateCountSql(), Long.class,
                    Timestamp.from(now.minus(processed)));
            Long expiredBacklog = jdbc.queryForObject(expiredCandidateCountSql(), Long.class,
                    Timestamp.from(now.minus(expired)));
            return state.snapshot(now, number(active), number(processedBacklog),
                    number(expiredBacklog));
        });
        return requiredResult(result, "source-retention snapshot");
    }

    private RetentionAttempt retainClaimed(
            RetentionLease lease,
            Duration processedRetention,
            Duration expiredRetention,
            int pageSize) {
        RetentionAttempt result = transactions.execute(status -> {
            RetentionState state = readState(true);
            Instant now = databaseNow();
            requireLiveFence(state, lease, now);
            verifyAggregateCounters(state);
            SourceProgress progress = activeProgress(state);
            if (progress == null) {
                progress = startEligibleProgress(
                        now.minus(processedRetention), now.minus(expiredRetention), now);
                if (progress == null) {
                    RetentionState idle = state.succeeded(now);
                    updateState(state, idle);
                    return RetentionAttempt.completed(RetentionResult.idle(now));
                }
                RetentionState activated = state.activated(progress.cycleId(), now);
                updateState(state, activated);
                state = activated;
            }
            verifySourceFences(progress);
            SourceMutation mutation = advanceOneSegment(progress, pageSize, now);
            SourceProgress successor = mutation.progress();
            boolean retired = false;
            if (successor.allChildrenComplete()) {
                finalizeSource(successor);
                successor = successor.completed(now);
                updateProgress(progress, successor);
                retired = true;
            } else if (!successor.equals(progress)) {
                updateProgress(progress, successor);
            }
            RetentionState latest = readState(true);
            requireLiveFence(latest, lease, databaseNow());
            RetentionState completed = latest.recorded(mutation, retired, now);
            updateState(latest, completed);
            return RetentionAttempt.completed(new RetentionResult(
                    successor.mode(), mutation.classificationsDeleted(),
                    mutation.expectedDeleted(), mutation.itemsDeleted(),
                    mutation.pagesDeleted(), retired,
                    retired ? "" : successor.cycleId(), now));
        });
        return requiredResult(result, "source retention");
    }

    private SourceProgress startEligibleProgress(
            Instant processedCutoff,
            Instant expiredCutoff,
            Instant now) {
        List<Candidate> processed = jdbc.query(processedCandidateSql(),
                (result, row) -> new Candidate(result.getString("cycle_id"),
                        result.getString("comparison_id"), result.getString("authority_id"),
                        RetirementMode.PROCESSED), Timestamp.from(processedCutoff));
        Candidate candidate = processed.isEmpty() ? null : processed.getFirst();
        if (candidate == null) {
            List<Candidate> expired = jdbc.query(expiredCandidateSql(),
                    (result, row) -> new Candidate(result.getString("cycle_id"), "",
                            result.getString("authority_id"),
                            RetirementMode.SNAPSHOT_EXPIRED), Timestamp.from(expiredCutoff));
            candidate = expired.isEmpty() ? null : expired.getFirst();
        }
        if (candidate == null) {
            return null;
        }
        StoredCycle cycle = readCycle(candidate.cycleId(), true);
        StoredComparison comparison = candidate.mode() == RetirementMode.PROCESSED
                ? readComparison(candidate.comparisonId(), true) : null;
        verifyCandidateControls(candidate, cycle, comparison);
        verifyCandidate(candidate, cycle, comparison);
        SourceProgress initial = SourceProgress.initial(candidate, cycle, comparison, now);
        initial = initial.withFingerprint(progressFingerprint(initial));
        SourceRetirementMarker marker = SourceRetirementMarker.active(
                candidate, now, objectMapper);
        int markerInserted = jdbc.update("""
                INSERT INTO
                    rg_test_suite_stability_observation_external_source_retirements (
                    cycle_id, comparison_id, authority_id, retirement_mode,
                    retirement_status, started_at, completed_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, marker.sqlArguments());
        requireOne(markerInserted, "source-retirement marker");
        int inserted = jdbc.update("""
                INSERT INTO
                    rg_test_suite_stability_observation_external_source_retention_progress (
                    cycle_id, comparison_id, authority_id, retirement_mode, progress_status,
                    source_cycle_fingerprint, source_comparison_fingerprint,
                    expected_classification_count, expected_classification_root,
                    classification_after_object_id, deleted_classification_count,
                    deleted_classification_root, classification_complete,
                    expected_snapshot_count, expected_snapshot_root,
                    expected_after_object_id, deleted_expected_count, deleted_expected_root,
                    expected_complete, inventory_item_count, inventory_item_root,
                    item_after_object_id, deleted_item_count, deleted_item_root, item_complete,
                    inventory_page_count, page_after_sequence, deleted_page_count,
                    deleted_page_root, page_complete, revision, started_at, completed_at,
                    updated_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, initial.sqlArguments());
        if (inserted != 1) {
            throw new IllegalStateException("External source retirement start was incomplete");
        }
        return readProgress(candidate.cycleId(), true);
    }

    private SourceMutation advanceOneSegment(
            SourceProgress source,
            int pageSize,
            Instant now) {
        SourceProgress progress = source;
        if (!progress.classificationComplete()) {
            SourceMutation mutation = deleteClassificationPage(progress, pageSize, now);
            if (mutation.totalDeleted() > 0 || !mutation.progress().classificationComplete()) {
                return mutation;
            }
            progress = mutation.progress();
        }
        if (!progress.expectedComplete()) {
            SourceMutation mutation = deleteExpectedPage(progress, pageSize, now);
            if (mutation.totalDeleted() > 0 || !mutation.progress().expectedComplete()) {
                return mutation;
            }
            progress = mutation.progress();
        }
        if (!progress.itemComplete()) {
            SourceMutation mutation = deleteItemPage(progress, pageSize, now);
            if (mutation.totalDeleted() > 0 || !mutation.progress().itemComplete()) {
                return mutation;
            }
            progress = mutation.progress();
        }
        if (!progress.pageComplete()) {
            return deleteInventoryPagePage(progress, pageSize, now);
        }
        return SourceMutation.none(progress);
    }

    private SourceMutation deleteClassificationPage(
            SourceProgress progress,
            int pageSize,
            Instant now) {
        List<StoredClassification> rows = jdbc.query("""
                SELECT comparison_id, cycle_id, authority_id, object_id, page_sequence, outcome,
                       expected_item_fingerprint, observed_item_fingerprint,
                       expected_object_commitment, observed_object_commitment,
                       expected_topology_fingerprint, observed_topology_fingerprint,
                       expected_retain_until, observed_retain_until,
                       classification_fingerprint, committed_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ? AND object_id > ?
                ORDER BY object_id
                LIMIT ?
                FOR UPDATE
                """, this::storedClassification, progress.comparisonId(),
                progress.classificationAfterObjectId(), pageSize);
        String root = progress.deletedClassificationRoot();
        String cursor = progress.classificationAfterObjectId();
        long count = progress.deletedClassificationCount();
        for (StoredClassification row : rows) {
            row.verify(objectMapper, progress);
            if (cursor.compareTo(row.classification().objectId()) >= 0) {
                throw new IllegalStateException("External classification retirement order is corrupt");
            }
            root = ExternalArchiveComparisonStateIntegrity.appendClassificationRoot(objectMapper,
                    root, row.classification().classificationFingerprint());
            count = increment(count, "deleted classification count");
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_suite_stability_observation_external_classifications
                    WHERE comparison_id = ? AND object_id = ? AND record_fingerprint = ?
                    """, progress.comparisonId(), row.classification().objectId(),
                    row.recordFingerprint());
            requireOne(deleted, "classification");
            cursor = row.classification().objectId();
        }
        boolean complete = rows.size() < pageSize;
        if (complete) {
            requireNoRows("rg_test_suite_stability_observation_external_classifications",
                    "comparison_id", progress.comparisonId(), "classification");
            requireReplay(count, root, progress.expectedClassificationCount(),
                    progress.expectedClassificationRoot(), "classification");
        }
        SourceProgress successor = progress.classifications(cursor, count, root, complete, now);
        return new SourceMutation(successor, rows.size(), 0, 0, 0);
    }

    private SourceMutation deleteExpectedPage(
            SourceProgress progress,
            int pageSize,
            Instant now) {
        List<StoredExpected> rows = jdbc.query("""
                SELECT comparison_id, authority_id, object_id, trust_domain, archive_set_id,
                       failure_domain, item_fingerprint, object_commitment, retirement_id,
                       retirement_fingerprint, segment_id, segment_fingerprint,
                       retention_policy_fingerprint, retain_until, stored_at
                FROM rg_test_suite_stability_observation_external_expected_snapshots
                WHERE comparison_id = ? AND object_id > ?
                ORDER BY object_id
                LIMIT ?
                FOR UPDATE
                """, this::storedExpected, progress.comparisonId(),
                progress.expectedAfterObjectId(), pageSize);
        String root = progress.deletedExpectedRoot();
        String cursor = progress.expectedAfterObjectId();
        long count = progress.deletedExpectedCount();
        for (StoredExpected row : rows) {
            row.verify(objectMapper, progress, cursor);
            root = ExternalArchiveComparisonStateIntegrity.appendExpectedRoot(objectMapper, root,
                    row.item().itemFingerprint(), row.topologyFingerprint(objectMapper));
            count = increment(count, "deleted expected count");
            int deleted = jdbc.update("""
                    DELETE FROM
                        rg_test_suite_stability_observation_external_expected_snapshots
                    WHERE comparison_id = ? AND object_id = ? AND authority_id = ?
                      AND trust_domain = ? AND archive_set_id = ? AND failure_domain = ?
                      AND item_fingerprint = ? AND object_commitment = ?
                      AND retirement_id = ? AND retirement_fingerprint = ?
                      AND segment_id = ? AND segment_fingerprint = ?
                      AND retention_policy_fingerprint = ? AND retain_until = ? AND stored_at = ?
                    """, progress.comparisonId(), row.item().objectId(), row.authorityId(),
                    row.trustDomain(), row.archiveSetId(), row.failureDomain(),
                    row.item().itemFingerprint(), row.item().objectCommitment(),
                    row.item().retirementId(), row.item().retirementFingerprint(),
                    row.item().segmentId(), row.item().segmentFingerprint(),
                    row.item().retentionPolicyFingerprint(), Timestamp.from(row.item().retainUntil()),
                    Timestamp.from(row.item().storedAt()));
            requireOne(deleted, "expected snapshot");
            cursor = row.item().objectId();
        }
        boolean complete = rows.size() < pageSize;
        if (complete) {
            requireNoRows("rg_test_suite_stability_observation_external_expected_snapshots",
                    "comparison_id", progress.comparisonId(), "expected snapshot");
            requireReplay(count, root, progress.expectedSnapshotCount(),
                    progress.expectedSnapshotRoot(), "expected snapshot");
        }
        SourceProgress successor = progress.expected(cursor, count, root, complete, now);
        return new SourceMutation(successor, 0, rows.size(), 0, 0);
    }

    private SourceMutation deleteItemPage(
            SourceProgress progress,
            int pageSize,
            Instant now) {
        List<StoredItem> rows = jdbc.query("""
                SELECT cycle_id, object_id, page_sequence, item_fingerprint,
                       object_commitment, retirement_id, retirement_fingerprint, segment_id,
                       segment_fingerprint, retention_policy_fingerprint, retain_until, stored_at,
                       committed_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_inventory_items
                WHERE cycle_id = ? AND object_id > ?
                ORDER BY object_id
                LIMIT ?
                FOR UPDATE
                """, this::storedItem, progress.cycleId(), progress.itemAfterObjectId(), pageSize);
        String root = progress.deletedItemRoot();
        String cursor = progress.itemAfterObjectId();
        long count = progress.deletedItemCount();
        for (StoredItem row : rows) {
            row.verify(objectMapper, progress, cursor);
            root = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.append(
                    objectMapper, root, row.item());
            count = increment(count, "deleted inventory item count");
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_suite_stability_observation_external_inventory_items
                    WHERE cycle_id = ? AND object_id = ? AND record_fingerprint = ?
                    """, progress.cycleId(), row.item().objectId(), row.recordFingerprint());
            requireOne(deleted, "inventory item");
            cursor = row.item().objectId();
        }
        boolean complete = rows.size() < pageSize;
        if (complete) {
            requireNoRows("rg_test_suite_stability_observation_external_inventory_items",
                    "cycle_id", progress.cycleId(), "inventory item");
            requireReplay(count, root, progress.inventoryItemCount(),
                    progress.inventoryItemRoot(), "inventory item");
        }
        SourceProgress successor = progress.items(cursor, count, root, complete, now);
        return new SourceMutation(successor, 0, 0, rows.size(), 0);
    }

    private SourceMutation deleteInventoryPagePage(
            SourceProgress progress,
            int pageSize,
            Instant now) {
        List<StoredPage> rows = jdbc.query("""
                SELECT cycle_id, page_sequence, authority_id, trust_domain, archive_set_id,
                       failure_domain, request_fingerprint, page_fingerprint, snapshot_id,
                       snapshot_at, snapshot_object_count, snapshot_root, after_object_id,
                       next_after_object_id, item_count, complete, issued_at, expires_at,
                       committed_at, page_json, record_fingerprint
                FROM rg_test_suite_stability_observation_external_inventory_pages
                WHERE cycle_id = ? AND page_sequence >= ?
                ORDER BY page_sequence
                LIMIT ?
                FOR UPDATE
                """, this::storedPage, progress.cycleId(), progress.pageAfterSequence(), pageSize);
        String root = progress.deletedPageRoot();
        long sequence = progress.pageAfterSequence();
        long count = progress.deletedPageCount();
        for (StoredPage row : rows) {
            row.verify(objectMapper, inventoryAuthority, progress, sequence);
            root = ProtocolFingerprint.of(objectMapper, new PageRootLink(
                    PAGE_ROOT_LINK_SCHEMA, root, row.recordFingerprint()));
            count = increment(count, "deleted inventory page count");
            int deleted = jdbc.update("""
                    DELETE FROM rg_test_suite_stability_observation_external_inventory_pages
                    WHERE cycle_id = ? AND page_sequence = ? AND record_fingerprint = ?
                    """, progress.cycleId(), row.pageSequence(), row.recordFingerprint());
            requireOne(deleted, "inventory page");
            sequence = increment(sequence, "inventory page sequence");
        }
        boolean complete = rows.size() < pageSize;
        if (complete) {
            requireNoRows("rg_test_suite_stability_observation_external_inventory_pages",
                    "cycle_id", progress.cycleId(), "inventory page");
            if (count != progress.inventoryPageCount() || sequence != count) {
                throw new IllegalStateException("External inventory page retirement replay failed");
            }
        }
        SourceProgress successor = progress.pages(sequence, count, root, complete, now);
        return new SourceMutation(successor, 0, 0, 0, rows.size());
    }

    private void finalizeSource(SourceProgress progress) {
        verifySourceFences(progress);
        if (!progress.allChildrenComplete()) {
            throw new IllegalStateException("External source retirement is not complete");
        }
        if (progress.mode() == RetirementMode.PROCESSED) {
            int comparison = jdbc.update("""
                    DELETE FROM rg_test_suite_stability_observation_external_comparisons
                    WHERE comparison_id = ? AND cycle_id = ? AND record_fingerprint = ?
                    """, progress.comparisonId(), progress.cycleId(),
                    progress.sourceComparisonFingerprint());
            requireOne(comparison, "comparison parent");
        }
        int cycle = jdbc.update("""
                DELETE FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ? AND record_fingerprint = ?
                """, progress.cycleId(), progress.sourceCycleFingerprint());
        requireOne(cycle, "inventory cycle parent");
        SourceRetirementMarker marker = readMarker(progress.cycleId(), true);
        SourceRetirementMarker completed = marker.completed(progress.updatedAt(), objectMapper);
        int markerUpdated = jdbc.update("""
                UPDATE rg_test_suite_stability_observation_external_source_retirements
                SET retirement_status = ?, completed_at = ?, record_fingerprint = ?
                WHERE cycle_id = ? AND retirement_status = ? AND record_fingerprint = ?
                """, completed.status(), Timestamp.from(completed.completedAt()),
                completed.recordFingerprint(), marker.cycleId(), marker.status(),
                marker.recordFingerprint());
        requireOne(markerUpdated, "source-retirement marker completion");
    }

    private void verifyCandidate(
            Candidate candidate,
            StoredCycle cycle,
            StoredComparison comparison) {
        cycle.verify(objectMapper);
        if (!candidate.authorityId().equals(cycle.authorityId())) {
            throw new IllegalStateException("External source candidate authority drifted");
        }
        if (candidate.mode() == RetirementMode.SNAPSHOT_EXPIRED) {
            if (!"SNAPSHOT_EXPIRED".equals(cycle.status())) {
                throw new IllegalStateException("External expired source candidate drifted");
            }
            return;
        }
        Objects.requireNonNull(comparison, "comparison").verify(objectMapper);
        if (!"COMPLETED".equals(cycle.status()) || !"COMPLETED".equals(comparison.status())
                || !comparison.cycleId().equals(cycle.cycleId())
                || !comparison.authorityId().equals(cycle.authorityId())
                || comparison.remoteObjectCount() != cycle.snapshotObjectCount()
                || !comparison.remoteRoot().equals(cycle.snapshotRoot())) {
            throw new IllegalStateException("External processed source candidate drifted");
        }
    }

    private void verifyCandidateControls(
            Candidate candidate,
            StoredCycle cycle,
            StoredComparison comparison) {
        InventoryControlAuthority inventory = jdbc.queryForObject("""
                SELECT authority_id, lease_owner, lease_token, lease_epoch, lease_until,
                       revision, active_cycle_id, last_completed_cycle_id, last_success_at,
                       updated_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_inventory_authorities
                WHERE authority_id = ?
                FOR UPDATE
                """, (result, row) -> new InventoryControlAuthority(
                result.getString("authority_id"), result.getString("lease_owner"),
                result.getString("lease_token"), result.getLong("lease_epoch"),
                instant(result, "lease_until"), result.getLong("revision"),
                result.getString("active_cycle_id"),
                result.getString("last_completed_cycle_id"),
                nullableInstant(result, "last_success_at"), instant(result, "updated_at"),
                result.getString("record_fingerprint")), candidate.authorityId());
        if (inventory == null) {
            throw new IllegalStateException("External inventory authority control is missing");
        }
        inventory.verify(objectMapper, candidate);
        if (candidate.mode() == RetirementMode.SNAPSHOT_EXPIRED) {
            List<String> comparisons = jdbc.queryForList("""
                    SELECT comparison_id
                    FROM rg_test_suite_stability_observation_external_comparisons
                    WHERE cycle_id = ?
                    FOR UPDATE
                    """, String.class, cycle.cycleId());
            if (!comparisons.isEmpty()) {
                throw new IllegalStateException("External expired source acquired a comparison");
            }
            return;
        }
        ComparisonControlAuthority comparisonAuthority = jdbc.queryForObject("""
                SELECT authority_id, active_comparison_id, last_completed_comparison_id,
                       revision, updated_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_comparison_authorities
                WHERE authority_id = ?
                FOR UPDATE
                """, (result, row) -> new ComparisonControlAuthority(
                result.getString("authority_id"), result.getString("active_comparison_id"),
                result.getString("last_completed_comparison_id"), result.getLong("revision"),
                instant(result, "updated_at"), result.getString("record_fingerprint")),
                candidate.authorityId());
        FindingControlAuthority findingAuthority = jdbc.queryForObject("""
                SELECT authority_id, active_projection_id, last_completed_projection_id,
                       last_applied_comparison_id, last_applied_comparison_completed_at,
                       revision, updated_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_authorities
                WHERE authority_id = ?
                FOR UPDATE
                """, (result, row) -> new FindingControlAuthority(
                result.getString("authority_id"), result.getString("active_projection_id"),
                result.getString("last_completed_projection_id"),
                result.getString("last_applied_comparison_id"),
                nullableInstant(result, "last_applied_comparison_completed_at"),
                result.getLong("revision"), instant(result, "updated_at"),
                result.getString("record_fingerprint")), candidate.authorityId());
        if (comparisonAuthority == null || findingAuthority == null) {
            throw new IllegalStateException("External source governance authority is missing");
        }
        comparisonAuthority.verify(objectMapper, candidate);
        findingAuthority.verify(objectMapper, candidate);
        List<GovernanceProjection> projections = jdbc.query("""
                SELECT projection_id, comparison_id, authority_id, projection_status,
                       comparison_started_at, comparison_completed_at,
                       source_classification_count, source_classification_root,
                       snapshot_finding_count, snapshot_root, next_after_object_id,
                       next_page_sequence, processed_classification_count, opened_count,
                       observed_count, reopened_count, resolved_count, confirmed_count,
                       event_root, revision, started_at, completed_at, updated_at,
                       record_fingerprint
                FROM rg_test_suite_stability_observation_external_finding_projections
                WHERE comparison_id = ?
                FOR UPDATE
                """, this::governanceProjection, candidate.comparisonId());
        if (projections.size() != 1) {
            throw new IllegalStateException("External source governance projection is not unique");
        }
        GovernanceProjection projection = projections.getFirst();
        projection.verify(objectMapper, Objects.requireNonNull(comparison, "comparison"));
        GovernanceEvidenceRetirement retirement = jdbc.queryForObject("""
                SELECT projection_id, comparison_id, authority_id, retirement_status,
                       started_at, completed_at, record_fingerprint
                FROM
                    rg_test_suite_stability_observation_external_finding_evidence_retirements
                WHERE projection_id = ?
                FOR UPDATE
                """, (result, row) -> new GovernanceEvidenceRetirement(
                result.getString("projection_id"), result.getString("comparison_id"),
                result.getString("authority_id"), result.getString("retirement_status"),
                instant(result, "started_at"), nullableInstant(result, "completed_at"),
                result.getString("record_fingerprint")), projection.projectionId());
        if (retirement == null) {
            throw new IllegalStateException("External finding evidence retirement is missing");
        }
        retirement.verify(objectMapper, projection);
    }

    private void verifySourceFences(SourceProgress progress) {
        SourceRetirementMarker marker = readMarker(progress.cycleId(), true);
        marker.verify(objectMapper, progress);
        StoredCycle cycle = readCycle(progress.cycleId(), true);
        cycle.verify(objectMapper);
        if (!cycle.recordFingerprint().equals(progress.sourceCycleFingerprint())) {
            throw new IllegalStateException("External source inventory cycle fence drifted");
        }
        if (progress.mode() == RetirementMode.PROCESSED) {
            StoredComparison comparison = readComparison(progress.comparisonId(), true);
            comparison.verify(objectMapper);
            if (!comparison.recordFingerprint().equals(
                    progress.sourceComparisonFingerprint())) {
                throw new IllegalStateException("External source comparison fence drifted");
            }
        }
    }

    private SourceProgress activeProgress(RetentionState state) {
        List<SourceProgress> active = jdbc.query("""
                SELECT *
                FROM rg_test_suite_stability_observation_external_source_retention_progress
                WHERE progress_status = 'ACTIVE'
                ORDER BY started_at, cycle_id
                FOR UPDATE
                """, this::sourceProgress);
        if (active.size() > 1
                || active.isEmpty() != state.activeCycleId().isEmpty()
                || !active.isEmpty()
                && !active.getFirst().cycleId().equals(state.activeCycleId())) {
            throw new IllegalStateException("External source retention active cardinality is corrupt");
        }
        if (active.isEmpty()) {
            return null;
        }
        SourceProgress result = active.getFirst();
        result.verify(objectMapper);
        return result;
    }

    private Optional<RetentionLease> acquireLease() {
        Optional<RetentionLease> result = transactions.execute(status -> {
            RetentionState current = readState(true);
            verifyAggregateCounters(current);
            Instant now = databaseNow();
            if (current.leaseLiveAt(now)) {
                return Optional.empty();
            }
            String token = UUID.randomUUID().toString();
            long epoch = increment(current.leaseEpoch(), "source-retention lease epoch");
            Instant until = now.plus(leaseDuration);
            RetentionState claimed = current.claimed(ownerId, token, epoch, until, now);
            updateState(current, claimed);
            return Optional.of(new RetentionLease(ownerId, token, epoch, until));
        });
        return requiredResult(result, "source-retention lease");
    }

    private void releaseLease(RetentionLease lease) {
        transactions.executeWithoutResult(status -> {
            RetentionState state = readState(true);
            if (!state.matches(lease)) {
                return;
            }
            Instant now = databaseNow();
            updateState(state, state.released(now));
        });
    }

    private void requireLiveFence(
            RetentionState state,
            RetentionLease lease,
            Instant now) {
        if (!state.matches(lease) || !now.isBefore(state.leaseUntil())) {
            throw new IllegalStateException("External source-retention lease fence was lost");
        }
    }

    private void initializeState() {
        transactions.executeWithoutResult(status -> {
            Instant now = databaseNow();
            RetentionState initial = RetentionState.initial(now);
            initial = initial.withFingerprint(stateFingerprint(initial));
            jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_source_retention_state (
                        job_name, lease_owner, lease_token, lease_epoch, lease_until,
                        active_cycle_id, revision, total_classifications_deleted,
                        total_expected_deleted, total_items_deleted, total_pages_deleted,
                        total_sources_retired, last_success_at, updated_at, record_fingerprint
                    ) SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (
                        SELECT 1 FROM
                            rg_test_suite_stability_observation_external_source_retention_state
                        WHERE job_name = ?
                    )
                    """, append(initial.sqlArguments(), JOB_NAME));
            RetentionState stored = readState(true);
            verifyAggregateCounters(stored);
        });
    }

    private RetentionState readState(boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        List<RetentionState> rows = jdbc.query("""
                SELECT job_name, lease_owner, lease_token, lease_epoch, lease_until,
                       active_cycle_id, revision, total_classifications_deleted,
                       total_expected_deleted, total_items_deleted, total_pages_deleted,
                       total_sources_retired, last_success_at, updated_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_source_retention_state
                WHERE job_name = ?
                """ + suffix, this::retentionState, JOB_NAME);
        if (rows.size() != 1) {
            throw new IllegalStateException("External source-retention state cardinality is corrupt");
        }
        RetentionState state = rows.getFirst();
        state.verify(objectMapper);
        return state;
    }

    private void updateState(RetentionState expected, RetentionState successor) {
        RetentionState stored = successor.withFingerprint(stateFingerprint(successor));
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_observation_external_source_retention_state
                SET lease_owner = ?, lease_token = ?, lease_epoch = ?, lease_until = ?,
                    active_cycle_id = ?, revision = ?, total_classifications_deleted = ?,
                    total_expected_deleted = ?, total_items_deleted = ?, total_pages_deleted = ?,
                    total_sources_retired = ?, last_success_at = ?, updated_at = ?,
                    record_fingerprint = ?
                WHERE job_name = ? AND revision = ? AND record_fingerprint = ?
                """, append(stored.updateArguments(), expected.jobName(), expected.revision(),
                expected.recordFingerprint()));
        requireOne(updated, "source-retention state");
    }

    private SourceProgress readProgress(String cycleId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        List<SourceProgress> rows = jdbc.query("""
                SELECT *
                FROM rg_test_suite_stability_observation_external_source_retention_progress
                WHERE cycle_id = ?
                """ + suffix, this::sourceProgress, cycleId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External source-retention progress cardinality is corrupt");
        }
        SourceProgress progress = rows.getFirst();
        progress.verify(objectMapper);
        return progress;
    }

    private SourceRetirementMarker readMarker(String cycleId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        List<SourceRetirementMarker> rows = jdbc.query("""
                SELECT cycle_id, comparison_id, authority_id, retirement_mode,
                       retirement_status, started_at, completed_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_source_retirements
                WHERE cycle_id = ?
                """ + suffix, (result, row) -> new SourceRetirementMarker(
                result.getString("cycle_id"), result.getString("comparison_id"),
                result.getString("authority_id"),
                RetirementMode.valueOf(result.getString("retirement_mode")),
                result.getString("retirement_status"), instant(result, "started_at"),
                nullableInstant(result, "completed_at"),
                result.getString("record_fingerprint")), cycleId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External source-retirement marker cardinality is corrupt");
        }
        SourceRetirementMarker marker = rows.getFirst();
        marker.verify(objectMapper);
        return marker;
    }

    private void updateProgress(SourceProgress expected, SourceProgress successor) {
        SourceProgress stored = successor.withFingerprint(progressFingerprint(successor));
        int updated = jdbc.update("""
                UPDATE
                    rg_test_suite_stability_observation_external_source_retention_progress
                SET progress_status = ?, classification_after_object_id = ?,
                    deleted_classification_count = ?, deleted_classification_root = ?,
                    classification_complete = ?, expected_after_object_id = ?,
                    deleted_expected_count = ?, deleted_expected_root = ?, expected_complete = ?,
                    item_after_object_id = ?, deleted_item_count = ?, deleted_item_root = ?,
                    item_complete = ?, page_after_sequence = ?, deleted_page_count = ?,
                    deleted_page_root = ?, page_complete = ?, revision = ?, completed_at = ?,
                    updated_at = ?, record_fingerprint = ?
                WHERE cycle_id = ? AND revision = ? AND record_fingerprint = ?
                """, append(stored.progressUpdateArguments(), expected.cycleId(),
                expected.revision(), expected.recordFingerprint()));
        requireOne(updated, "source-retention progress");
    }

    private void verifyAggregateCounters(RetentionState state) {
        Aggregate aggregate = jdbc.queryForObject("""
                SELECT COALESCE(SUM(deleted_classification_count), 0) classifications,
                       COALESCE(SUM(deleted_expected_count), 0) expected_rows,
                       COALESCE(SUM(deleted_item_count), 0) items,
                       COALESCE(SUM(deleted_page_count), 0) pages,
                       COALESCE(SUM(CASE WHEN progress_status = 'COMPLETED'
                                         THEN 1 ELSE 0 END), 0) retired,
                       COALESCE(SUM(CASE WHEN progress_status = 'ACTIVE'
                                         THEN 1 ELSE 0 END), 0) active,
                       COUNT(*) marker_count
                FROM rg_test_suite_stability_observation_external_source_retention_progress
                """, (result, row) -> new Aggregate(result.getLong("classifications"),
                result.getLong("expected_rows"), result.getLong("items"),
                result.getLong("pages"), result.getLong("retired"),
                result.getLong("active"), result.getLong("marker_count")));
        MarkerAggregate markers = jdbc.queryForObject("""
                SELECT COUNT(*) marker_count,
                       COALESCE(SUM(CASE WHEN retirement_status = 'COMPLETED'
                                         THEN 1 ELSE 0 END), 0) retired,
                       COALESCE(SUM(CASE WHEN retirement_status = 'ACTIVE'
                                         THEN 1 ELSE 0 END), 0) active
                FROM rg_test_suite_stability_observation_external_source_retirements
                """, (result, row) -> new MarkerAggregate(result.getLong("marker_count"),
                result.getLong("retired"), result.getLong("active")));
        if (aggregate == null || aggregate.classifications() != state.classificationsDeleted()
                || aggregate.expected() != state.expectedDeleted()
                || aggregate.items() != state.itemsDeleted()
                || aggregate.pages() != state.pagesDeleted()
                || aggregate.retired() != state.sourcesRetired() || markers == null
                || markers.count() != aggregate.markerCount()
                || markers.retired() != aggregate.retired()
                || markers.active() != aggregate.active()) {
            throw new IllegalStateException("External source-retention aggregate is corrupt");
        }
    }

    private StoredCycle readCycle(String cycleId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        List<StoredCycle> rows = jdbc.query("""
                SELECT cycle_id, authority_id, cycle_status, trust_domain, archive_set_id,
                       failure_domain, snapshot_id, snapshot_at, snapshot_object_count,
                       snapshot_root, next_after_object_id, next_page_sequence,
                       accumulated_object_count, accumulated_root, last_object_id, revision,
                       started_at, completed_at, updated_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ?
                """ + suffix, this::storedCycle, cycleId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External source inventory cycle is missing");
        }
        return rows.getFirst();
    }

    private StoredComparison readComparison(String comparisonId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        List<StoredComparison> rows = jdbc.query("""
                SELECT comparison_id, cycle_id, authority_id, comparison_status, trust_domain,
                       archive_set_id, failure_domain, remote_snapshot_id, remote_object_count,
                       remote_root, expected_object_count, expected_root, next_after_object_id,
                       next_page_sequence, classified_object_count, matched_count,
                       missing_remote_count, unexpected_remote_count, material_conflict_count,
                       retention_shortened_count, unknown_count, classification_root, revision,
                       started_at, completed_at, updated_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_comparisons
                WHERE comparison_id = ?
                """ + suffix, this::storedComparison, comparisonId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External source comparison is missing");
        }
        return rows.getFirst();
    }

    private String processedCandidateSql() {
        return """
                SELECT c.cycle_id, c.comparison_id, c.authority_id
                FROM rg_test_suite_stability_observation_external_comparisons c
                JOIN rg_test_suite_stability_observation_external_inventory_authorities ia
                  ON ia.authority_id = c.authority_id
                JOIN rg_test_suite_stability_observation_external_comparison_authorities ca
                  ON ca.authority_id = c.authority_id
                JOIN rg_test_suite_stability_observation_external_finding_projections p
                  ON p.comparison_id = c.comparison_id AND p.projection_status = 'COMPLETED'
                JOIN
                    rg_test_suite_stability_observation_external_finding_evidence_retirements er
                  ON er.projection_id = p.projection_id AND er.retirement_status = 'COMPLETED'
                JOIN rg_test_suite_stability_observation_external_finding_authorities fa
                  ON fa.authority_id = c.authority_id
                WHERE c.comparison_status = 'COMPLETED' AND c.completed_at <= ?
                  AND c.cycle_id <> ia.active_cycle_id
                  AND c.cycle_id <> ia.last_completed_cycle_id
                  AND c.comparison_id <> ca.active_comparison_id
                  AND c.comparison_id <> ca.last_completed_comparison_id
                  AND c.comparison_id <> fa.last_applied_comparison_id
                  AND NOT EXISTS (
                      SELECT 1 FROM
                        rg_test_suite_stability_observation_external_source_retention_progress rp
                      WHERE rp.cycle_id = c.cycle_id
                  )
                ORDER BY c.completed_at, c.comparison_id
                LIMIT 1
                """;
    }

    private String processedCandidateCountSql() {
        return """
                SELECT COUNT(*) FROM (
                """ + processedCandidateSql().replace("LIMIT 1", "") + ") candidates";
    }

    private String expiredCandidateSql() {
        return """
                SELECT c.cycle_id, c.authority_id
                FROM rg_test_suite_stability_observation_external_inventory_cycles c
                JOIN rg_test_suite_stability_observation_external_inventory_authorities ia
                  ON ia.authority_id = c.authority_id
                WHERE c.cycle_status = 'SNAPSHOT_EXPIRED' AND c.completed_at <= ?
                  AND c.cycle_id <> ia.active_cycle_id
                  AND c.cycle_id <> ia.last_completed_cycle_id
                  AND NOT EXISTS (
                      SELECT 1 FROM rg_test_suite_stability_observation_external_comparisons cmp
                      WHERE cmp.cycle_id = c.cycle_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM
                        rg_test_suite_stability_observation_external_source_retention_progress rp
                      WHERE rp.cycle_id = c.cycle_id
                  )
                ORDER BY c.completed_at, c.cycle_id
                LIMIT 1
                """;
    }

    private String expiredCandidateCountSql() {
        return """
                SELECT COUNT(*) FROM (
                """ + expiredCandidateSql().replace("LIMIT 1", "") + ") candidates";
    }

    private RetentionState retentionState(ResultSet result, int row) throws SQLException {
        return new RetentionState(result.getString("job_name"),
                result.getString("lease_owner"), result.getString("lease_token"),
                result.getLong("lease_epoch"), instant(result, "lease_until"),
                result.getString("active_cycle_id"), result.getLong("revision"),
                result.getLong("total_classifications_deleted"),
                result.getLong("total_expected_deleted"),
                result.getLong("total_items_deleted"), result.getLong("total_pages_deleted"),
                result.getLong("total_sources_retired"), nullableInstant(result, "last_success_at"),
                instant(result, "updated_at"), result.getString("record_fingerprint"));
    }

    private SourceProgress sourceProgress(ResultSet result, int row) throws SQLException {
        return new SourceProgress(result.getString("cycle_id"),
                result.getString("comparison_id"), result.getString("authority_id"),
                RetirementMode.valueOf(result.getString("retirement_mode")),
                result.getString("progress_status"),
                result.getString("source_cycle_fingerprint"),
                result.getString("source_comparison_fingerprint"),
                result.getLong("expected_classification_count"),
                result.getString("expected_classification_root"),
                result.getString("classification_after_object_id"),
                result.getLong("deleted_classification_count"),
                result.getString("deleted_classification_root"),
                result.getBoolean("classification_complete"),
                result.getLong("expected_snapshot_count"),
                result.getString("expected_snapshot_root"),
                result.getString("expected_after_object_id"),
                result.getLong("deleted_expected_count"),
                result.getString("deleted_expected_root"),
                result.getBoolean("expected_complete"),
                result.getLong("inventory_item_count"),
                result.getString("inventory_item_root"),
                result.getString("item_after_object_id"),
                result.getLong("deleted_item_count"), result.getString("deleted_item_root"),
                result.getBoolean("item_complete"), result.getLong("inventory_page_count"),
                result.getLong("page_after_sequence"), result.getLong("deleted_page_count"),
                result.getString("deleted_page_root"), result.getBoolean("page_complete"),
                result.getLong("revision"), instant(result, "started_at"),
                nullableInstant(result, "completed_at"), instant(result, "updated_at"),
                result.getString("record_fingerprint"));
    }

    private StoredCycle storedCycle(ResultSet result, int row) throws SQLException {
        return new StoredCycle(result.getString("cycle_id"), result.getString("authority_id"),
                result.getString("cycle_status"), result.getString("trust_domain"),
                result.getString("archive_set_id"), result.getString("failure_domain"),
                result.getString("snapshot_id"), nullableInstant(result, "snapshot_at"),
                result.getLong("snapshot_object_count"), result.getString("snapshot_root"),
                result.getString("next_after_object_id"), result.getLong("next_page_sequence"),
                result.getLong("accumulated_object_count"), result.getString("accumulated_root"),
                result.getString("last_object_id"), result.getLong("revision"),
                instant(result, "started_at"), nullableInstant(result, "completed_at"),
                instant(result, "updated_at"), result.getString("record_fingerprint"));
    }

    private StoredComparison storedComparison(ResultSet result, int row) throws SQLException {
        return new StoredComparison(result.getString("comparison_id"),
                result.getString("cycle_id"), result.getString("authority_id"),
                result.getString("comparison_status"), result.getString("trust_domain"),
                result.getString("archive_set_id"), result.getString("failure_domain"),
                result.getString("remote_snapshot_id"), result.getLong("remote_object_count"),
                result.getString("remote_root"), result.getLong("expected_object_count"),
                result.getString("expected_root"), result.getString("next_after_object_id"),
                result.getLong("next_page_sequence"), result.getLong("classified_object_count"),
                result.getLong("matched_count"), result.getLong("missing_remote_count"),
                result.getLong("unexpected_remote_count"),
                result.getLong("material_conflict_count"),
                result.getLong("retention_shortened_count"), result.getLong("unknown_count"),
                result.getString("classification_root"), result.getLong("revision"),
                instant(result, "started_at"), nullableInstant(result, "completed_at"),
                instant(result, "updated_at"), result.getString("record_fingerprint"));
    }

    private StoredClassification storedClassification(ResultSet result, int row)
            throws SQLException {
        var classification = new
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Classification(result.getString("comparison_id"),
                        result.getString("cycle_id"), result.getString("authority_id"),
                        result.getString("object_id"),
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                                .Outcome.valueOf(result.getString("outcome")),
                        result.getString("expected_item_fingerprint"),
                        result.getString("observed_item_fingerprint"),
                        result.getString("expected_object_commitment"),
                        result.getString("observed_object_commitment"),
                        result.getString("expected_topology_fingerprint"),
                        result.getString("observed_topology_fingerprint"),
                        nullableInstant(result, "expected_retain_until"),
                        nullableInstant(result, "observed_retain_until"),
                        result.getString("classification_fingerprint"));
        return new StoredClassification(classification, result.getLong("page_sequence"),
                instant(result, "committed_at"), result.getString("record_fingerprint"));
    }

    private StoredExpected storedExpected(ResultSet result, int row) throws SQLException {
        return new StoredExpected(result.getString("authority_id"),
                result.getString("trust_domain"), result.getString("archive_set_id"),
                result.getString("failure_domain"), inventoryItem(result));
    }

    private StoredItem storedItem(ResultSet result, int row) throws SQLException {
        return new StoredItem(result.getString("cycle_id"), result.getLong("page_sequence"),
                inventoryItem(result), instant(result, "committed_at"),
                result.getString("record_fingerprint"));
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryItem inventoryItem(
            ResultSet result) throws SQLException {
        return new TestSuiteStabilityObservationExternalArchiveInventoryItem(
                TestSuiteStabilityObservationExternalArchiveInventoryItem.SCHEMA_VERSION,
                result.getString("item_fingerprint"), result.getString("object_id"),
                result.getString("object_commitment"), result.getString("retirement_id"),
                result.getString("retirement_fingerprint"), result.getString("segment_id"),
                result.getString("segment_fingerprint"),
                result.getString("retention_policy_fingerprint"),
                instant(result, "retain_until"), instant(result, "stored_at"));
    }

    private StoredPage storedPage(ResultSet result, int row) throws SQLException {
        return new StoredPage(result.getString("cycle_id"), result.getLong("page_sequence"),
                result.getString("authority_id"), result.getString("trust_domain"),
                result.getString("archive_set_id"), result.getString("failure_domain"),
                result.getString("request_fingerprint"), result.getString("page_fingerprint"),
                result.getString("snapshot_id"), instant(result, "snapshot_at"),
                result.getLong("snapshot_object_count"), result.getString("snapshot_root"),
                result.getString("after_object_id"), result.getString("next_after_object_id"),
                result.getInt("item_count"), result.getBoolean("complete"),
                instant(result, "issued_at"), instant(result, "expires_at"),
                instant(result, "committed_at"), result.getString("page_json"),
                result.getString("record_fingerprint"));
    }

    private GovernanceProjection governanceProjection(ResultSet result, int row)
            throws SQLException {
        return new GovernanceProjection(result.getString("projection_id"),
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

    private String stateFingerprint(RetentionState state) {
        return ProtocolFingerprint.of(objectMapper, state.material());
    }

    private String progressFingerprint(SourceProgress progress) {
        return ProtocolFingerprint.of(objectMapper, progress.material());
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Database clock returned no source-retention time");
        }
        return value.toInstant();
    }

    private void requireNoRows(String table, String key, String value, String label) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + key + " = ?", Long.class, value);
        if (number(count) != 0) {
            throw new IllegalStateException("External " + label + " retirement left source rows");
        }
    }

    private static void requireReplay(
            long actualCount,
            String actualRoot,
            long expectedCount,
            String expectedRoot,
            String label) {
        if (actualCount != expectedCount || !actualRoot.equals(expectedRoot)) {
            throw new IllegalStateException("External " + label + " retirement replay failed");
        }
    }

    private static void requireOne(int changed, String label) {
        if (changed != 1) {
            throw new IllegalStateException("External " + label + " exact mutation failed");
        }
    }

    private static int boundedPageSize(int value) {
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Source-retention page size must be 1 through 500");
        }
        return value;
    }

    private static Duration boundedDuration(
            Duration value,
            Duration minimum,
            Duration maximum,
            String label) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(label + " is outside its governed range");
        }
        return value;
    }

    private static String requiredIdentifier(String value, String label) {
        String normalized = normalized(value);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return normalized;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static Instant instant(ResultSet result, String field) throws SQLException {
        Timestamp value = result.getTimestamp(field);
        if (value == null) {
            throw new IllegalStateException("External source-retention timestamp is missing");
        }
        return value.toInstant();
    }

    private static Instant nullableInstant(ResultSet result, String field) throws SQLException {
        Timestamp value = result.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    private static Object timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static long increment(long value, String label) {
        try {
            return Math.addExact(value, 1);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(label + " overflow", overflow);
        }
    }

    private static long add(long left, long right, String label) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(label + " overflow", overflow);
        }
    }

    private static long number(Number value) {
        return value == null ? 0 : value.longValue();
    }

    private static <T> T requiredResult(T value, String label) {
        if (value == null) {
            throw new IllegalStateException("External " + label + " returned no result");
        }
        return value;
    }

    private static Object[] append(Object[] prefix, Object... suffix) {
        Object[] result = new Object[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }

    /** Closed source-history eligibility families. */
    public enum RetirementMode {
        /** Fully projected and independently retired governed comparison source. */
        PROCESSED,
        /** Terminal inventory snapshot that expired before a comparison existed. */
        SNAPSHOT_EXPIRED
    }

    /** Closed retention-attempt outcomes. */
    public enum RetentionStatus {
        /** One bounded transaction committed, including an idle result. */
        COMPLETED,
        /** Another replica owns a live database-clock lease. */
        LEASE_BUSY
    }

    /**
     * Payload-free mutation result for one bounded call.
     *
     * @param mode active mode, null only for an idle call
     * @param classificationsDeleted classification rows deleted in this call
     * @param expectedDeleted frozen expected rows deleted in this call
     * @param itemsDeleted normalized inventory items deleted in this call
     * @param pagesDeleted signed page envelopes deleted in this call
     * @param sourceRetired whether parent source rows and marker completed atomically
     * @param activeCycleId internal active cycle, empty after completion or idle
     * @param completedAt database completion time
     */
    public record RetentionResult(
            RetirementMode mode,
            int classificationsDeleted,
            int expectedDeleted,
            int itemsDeleted,
            int pagesDeleted,
            boolean sourceRetired,
            String activeCycleId,
            Instant completedAt) {
        /** Validates independent row bounds and completion shape. */
        public RetentionResult {
            activeCycleId = normalized(activeCycleId);
            if (classificationsDeleted < 0 || expectedDeleted < 0 || itemsDeleted < 0
                    || pagesDeleted < 0 || completedAt == null
                    || sourceRetired && !activeCycleId.isEmpty()
                    || mode == null && (classificationsDeleted != 0 || expectedDeleted != 0
                    || itemsDeleted != 0 || pagesDeleted != 0 || sourceRetired
                    || !activeCycleId.isEmpty())) {
                throw new IllegalArgumentException("Invalid source-retention result");
            }
        }

        private static RetentionResult idle(Instant now) {
            return new RetentionResult(null, 0, 0, 0, 0, false, "", now);
        }
    }

    /** Lease-busy or committed retention response. */
    public record RetentionAttempt(RetentionStatus status, RetentionResult result) {
        /** Enforces result presence only for committed attempts. */
        public RetentionAttempt {
            Objects.requireNonNull(status, "status");
            if ((status == RetentionStatus.COMPLETED) != (result != null)) {
                throw new IllegalArgumentException("Invalid source-retention attempt");
            }
        }

        private static RetentionAttempt completed(RetentionResult result) {
            return new RetentionAttempt(RetentionStatus.COMPLETED,
                    Objects.requireNonNull(result, "result"));
        }

        private static RetentionAttempt busy() {
            return new RetentionAttempt(RetentionStatus.LEASE_BUSY, null);
        }
    }

    /** Aggregate operational truth without source identities. */
    public record OperationalSnapshot(
            Instant observedAt,
            boolean activeRetirement,
            long activeMarkerCount,
            Instant activeRetirementUpdatedAt,
            long processedBacklog,
            long expiredBacklog,
            long totalClassificationsDeleted,
            long totalExpectedDeleted,
            long totalItemsDeleted,
            long totalPagesDeleted,
            long totalSourcesRetired,
            Instant lastSuccessAt) {
        /** Rejects negative counters and inconsistent active state. */
        public OperationalSnapshot {
            if (observedAt == null || activeMarkerCount < 0 || activeMarkerCount > 1
                    || processedBacklog < 0 || expiredBacklog < 0
                    || totalClassificationsDeleted < 0 || totalExpectedDeleted < 0
                    || totalItemsDeleted < 0 || totalPagesDeleted < 0
                    || totalSourcesRetired < 0 || activeRetirement != (activeMarkerCount == 1)
                    || activeRetirement != (activeRetirementUpdatedAt != null)
                    || activeRetirementUpdatedAt != null
                    && activeRetirementUpdatedAt.isAfter(observedAt)) {
                throw new IllegalArgumentException("Invalid source-retention snapshot");
            }
        }
    }

    private record Candidate(
            String cycleId,
            String comparisonId,
            String authorityId,
            RetirementMode mode) {
    }

    private record SourceRetirementMarker(
            String cycleId,
            String comparisonId,
            String authorityId,
            RetirementMode mode,
            String status,
            Instant startedAt,
            Instant completedAt,
            String recordFingerprint) {
        private static SourceRetirementMarker active(
                Candidate candidate,
                Instant now,
                ObjectMapper objectMapper) {
            SourceRetirementMarker marker = new SourceRetirementMarker(candidate.cycleId(),
                    candidate.comparisonId(), candidate.authorityId(), candidate.mode(), "ACTIVE",
                    now, null, "");
            return marker.withFingerprint(ExternalArchiveSourceRetirementIntegrity
                    .markerFingerprint(objectMapper, marker.cycleId(), marker.comparisonId(),
                            marker.authorityId(), marker.mode().name(), marker.status(),
                            marker.startedAt(), marker.completedAt()));
        }

        private void verify(ObjectMapper objectMapper) {
            boolean active = "ACTIVE".equals(status);
            boolean complete = "COMPLETED".equals(status);
            if (!isUuid(cycleId) || mode == null || !IDENTIFIER.matcher(authorityId).matches()
                    || (mode == RetirementMode.PROCESSED) != isUuid(comparisonId)
                    || (mode == RetirementMode.SNAPSHOT_EXPIRED) != comparisonId.isEmpty()
                    || (!active && !complete) || active != (completedAt == null)
                    || startedAt == null || !recordFingerprint.equals(
                    ExternalArchiveSourceRetirementIntegrity.markerFingerprint(objectMapper,
                            cycleId, comparisonId, authorityId, mode.name(), status, startedAt,
                            completedAt))) {
                throw new IllegalStateException("External source-retirement marker is corrupt");
            }
        }

        private void verify(ObjectMapper objectMapper, SourceProgress progress) {
            verify(objectMapper);
            if (!cycleId.equals(progress.cycleId())
                    || !comparisonId.equals(progress.comparisonId())
                    || !authorityId.equals(progress.authorityId()) || mode != progress.mode()
                    || !"ACTIVE".equals(status)) {
                throw new IllegalStateException("External source-retirement marker drifted");
            }
        }

        private SourceRetirementMarker completed(Instant now, ObjectMapper objectMapper) {
            SourceRetirementMarker marker = new SourceRetirementMarker(cycleId, comparisonId,
                    authorityId, mode, "COMPLETED", startedAt, now, "");
            return marker.withFingerprint(ExternalArchiveSourceRetirementIntegrity
                    .markerFingerprint(objectMapper, marker.cycleId(), marker.comparisonId(),
                            marker.authorityId(), marker.mode().name(), marker.status(),
                            marker.startedAt(), marker.completedAt()));
        }

        private SourceRetirementMarker withFingerprint(String fingerprint) {
            return new SourceRetirementMarker(cycleId, comparisonId, authorityId, mode, status,
                    startedAt, completedAt, fingerprint);
        }

        private Object[] sqlArguments() {
            return new Object[]{cycleId, comparisonId, authorityId, mode.name(), status,
                    Timestamp.from(startedAt), timestamp(completedAt), recordFingerprint};
        }

    }

    private record RetentionLease(
            String ownerId,
            String token,
            long epoch,
            Instant leaseUntil) {
    }

    private record Aggregate(
            long classifications,
            long expected,
            long items,
            long pages,
            long retired,
            long active,
            long markerCount) {
    }

    private record MarkerAggregate(long count, long retired, long active) {
    }

    private record SourceMutation(
            SourceProgress progress,
            int classificationsDeleted,
            int expectedDeleted,
            int itemsDeleted,
            int pagesDeleted) {
        private static SourceMutation none(SourceProgress progress) {
            return new SourceMutation(progress, 0, 0, 0, 0);
        }

        private int totalDeleted() {
            return classificationsDeleted + expectedDeleted + itemsDeleted + pagesDeleted;
        }
    }

    private record RetentionState(
            String jobName,
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseUntil,
            String activeCycleId,
            long revision,
            long classificationsDeleted,
            long expectedDeleted,
            long itemsDeleted,
            long pagesDeleted,
            long sourcesRetired,
            Instant lastSuccessAt,
            Instant updatedAt,
            String recordFingerprint) {
        private static RetentionState initial(Instant now) {
            return new RetentionState(JOB_NAME, "", "", 0, now, "", 0,
                    0, 0, 0, 0, 0, null, now, "");
        }

        private void verify(ObjectMapper objectMapper) {
            if (!JOB_NAME.equals(jobName) || leaseEpoch < 0 || revision < 0
                    || classificationsDeleted < 0 || expectedDeleted < 0 || itemsDeleted < 0
                    || pagesDeleted < 0 || sourcesRetired < 0 || leaseUntil == null
                    || updatedAt == null || (leaseOwner.isEmpty() != leaseToken.isEmpty())
                    || !leaseOwner.isEmpty() && !IDENTIFIER.matcher(leaseOwner).matches()
                    || !leaseToken.isEmpty() && !isUuid(leaseToken)
                    || !activeCycleId.isEmpty() && !isUuid(activeCycleId)
                    || !FINGERPRINT.matcher(recordFingerprint).matches()
                    || !recordFingerprint.equals(ProtocolFingerprint.of(objectMapper, material()))) {
                throw new IllegalStateException("External source-retention state is corrupt");
            }
        }

        private boolean leaseLiveAt(Instant now) {
            return !leaseToken.isEmpty() && now.isBefore(leaseUntil);
        }

        private boolean matches(RetentionLease lease) {
            return leaseOwner.equals(lease.ownerId()) && leaseToken.equals(lease.token())
                    && leaseEpoch == lease.epoch() && leaseUntil.equals(lease.leaseUntil());
        }

        private RetentionState claimed(
                String owner,
                String token,
                long epoch,
                Instant until,
                Instant now) {
            return new RetentionState(jobName, owner, token, epoch, until, activeCycleId,
                    increment(revision, "source-retention state revision"),
                    classificationsDeleted, expectedDeleted, itemsDeleted, pagesDeleted,
                    sourcesRetired, lastSuccessAt, now, "");
        }

        private RetentionState activated(String cycleId, Instant now) {
            return new RetentionState(jobName, leaseOwner, leaseToken, leaseEpoch, leaseUntil,
                    cycleId, increment(revision, "source-retention activation revision"),
                    classificationsDeleted, expectedDeleted, itemsDeleted, pagesDeleted,
                    sourcesRetired, lastSuccessAt, now, "");
        }

        private RetentionState recorded(
                SourceMutation mutation,
                boolean retired,
                Instant now) {
            return new RetentionState(jobName, "", "", leaseEpoch, now,
                    retired ? "" : activeCycleId,
                    increment(revision, "source-retention completion revision"),
                    add(classificationsDeleted, mutation.classificationsDeleted(),
                            "source-retention classification total"),
                    add(expectedDeleted, mutation.expectedDeleted(),
                            "source-retention expected total"),
                    add(itemsDeleted, mutation.itemsDeleted(), "source-retention item total"),
                    add(pagesDeleted, mutation.pagesDeleted(), "source-retention page total"),
                    retired ? increment(sourcesRetired, "source-retention source total")
                            : sourcesRetired,
                    now, now, "");
        }

        private RetentionState succeeded(Instant now) {
            return new RetentionState(jobName, "", "", leaseEpoch, now, activeCycleId,
                    increment(revision, "source-retention idle revision"),
                    classificationsDeleted, expectedDeleted, itemsDeleted, pagesDeleted,
                    sourcesRetired, now, now, "");
        }

        private RetentionState released(Instant now) {
            return new RetentionState(jobName, "", "", leaseEpoch, now, activeCycleId,
                    increment(revision, "source-retention release revision"),
                    classificationsDeleted, expectedDeleted, itemsDeleted, pagesDeleted,
                    sourcesRetired, lastSuccessAt, now, "");
        }

        private RetentionState withFingerprint(String fingerprint) {
            return new RetentionState(jobName, leaseOwner, leaseToken, leaseEpoch, leaseUntil,
                    activeCycleId, revision, classificationsDeleted, expectedDeleted,
                    itemsDeleted, pagesDeleted, sourcesRetired, lastSuccessAt, updatedAt,
                    fingerprint);
        }

        private StateMaterial material() {
            return new StateMaterial(STATE_SCHEMA, jobName, leaseOwner, leaseToken, leaseEpoch,
                    leaseUntil, activeCycleId, revision, classificationsDeleted, expectedDeleted,
                    itemsDeleted, pagesDeleted, sourcesRetired, lastSuccessAt, updatedAt);
        }

        private Object[] sqlArguments() {
            return new Object[]{jobName, leaseOwner, leaseToken, leaseEpoch,
                    Timestamp.from(leaseUntil), activeCycleId, revision, classificationsDeleted,
                    expectedDeleted, itemsDeleted, pagesDeleted, sourcesRetired,
                    timestamp(lastSuccessAt), Timestamp.from(updatedAt), recordFingerprint};
        }

        private Object[] updateArguments() {
            return new Object[]{leaseOwner, leaseToken, leaseEpoch, Timestamp.from(leaseUntil),
                    activeCycleId, revision, classificationsDeleted, expectedDeleted,
                    itemsDeleted, pagesDeleted, sourcesRetired, timestamp(lastSuccessAt),
                    Timestamp.from(updatedAt), recordFingerprint};
        }

        private OperationalSnapshot snapshot(
                Instant observedAt,
                long activeMarkers,
                long processedBacklog,
                long expiredBacklog) {
            return new OperationalSnapshot(observedAt, !activeCycleId.isEmpty(), activeMarkers,
                    activeCycleId.isEmpty() ? null : updatedAt,
                    processedBacklog, expiredBacklog, classificationsDeleted, expectedDeleted,
                    itemsDeleted, pagesDeleted, sourcesRetired, lastSuccessAt);
        }
    }

    private record StateMaterial(
            String schemaVersion,
            String jobName,
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseUntil,
            String activeCycleId,
            long revision,
            long classificationsDeleted,
            long expectedDeleted,
            long itemsDeleted,
            long pagesDeleted,
            long sourcesRetired,
            Instant lastSuccessAt,
            Instant updatedAt) {
    }

    private record SourceProgress(
            String cycleId,
            String comparisonId,
            String authorityId,
            RetirementMode mode,
            String status,
            String sourceCycleFingerprint,
            String sourceComparisonFingerprint,
            long expectedClassificationCount,
            String expectedClassificationRoot,
            String classificationAfterObjectId,
            long deletedClassificationCount,
            String deletedClassificationRoot,
            boolean classificationComplete,
            long expectedSnapshotCount,
            String expectedSnapshotRoot,
            String expectedAfterObjectId,
            long deletedExpectedCount,
            String deletedExpectedRoot,
            boolean expectedComplete,
            long inventoryItemCount,
            String inventoryItemRoot,
            String itemAfterObjectId,
            long deletedItemCount,
            String deletedItemRoot,
            boolean itemComplete,
            long inventoryPageCount,
            long pageAfterSequence,
            long deletedPageCount,
            String deletedPageRoot,
            boolean pageComplete,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt,
            String recordFingerprint) {
        private static SourceProgress initial(
                Candidate candidate,
                StoredCycle cycle,
                StoredComparison comparison,
                Instant now) {
            boolean expired = candidate.mode() == RetirementMode.SNAPSHOT_EXPIRED;
            return new SourceProgress(candidate.cycleId(), candidate.comparisonId(),
                    candidate.authorityId(), candidate.mode(), "ACTIVE",
                    cycle.recordFingerprint(), expired ? "" : comparison.recordFingerprint(),
                    expired ? 0 : comparison.classifiedObjectCount(),
                    expired
                            ? DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .EMPTY_CLASSIFICATION_ROOT : comparison.classificationRoot(),
                    "", 0,
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .EMPTY_CLASSIFICATION_ROOT,
                    expired, expired ? 0 : comparison.expectedObjectCount(),
                    expired
                            ? DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .EMPTY_EXPECTED_ROOT : comparison.expectedRoot(),
                    "", 0,
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .EMPTY_EXPECTED_ROOT,
                    expired, expired ? cycle.accumulatedObjectCount()
                    : cycle.snapshotObjectCount(),
                    expired ? cycle.accumulatedRoot() : cycle.snapshotRoot(), "", 0,
                    TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.EMPTY_ROOT,
                    false, cycle.nextPageSequence(), 0, 0, EMPTY_PAGE_ROOT, false, 0,
                    now, null, now, "");
        }

        private void verify(ObjectMapper objectMapper) {
            boolean active = "ACTIVE".equals(status);
            boolean complete = "COMPLETED".equals(status);
            boolean processed = mode == RetirementMode.PROCESSED;
            if (!isUuid(cycleId) || !IDENTIFIER.matcher(authorityId).matches()
                    || processed != isUuid(comparisonId) || !processed && !comparisonId.isEmpty()
                    || (!active && !complete)
                    || !FINGERPRINT.matcher(sourceCycleFingerprint).matches()
                    || processed != FINGERPRINT.matcher(sourceComparisonFingerprint).matches()
                    || expectedClassificationCount < 0 || expectedSnapshotCount < 0
                    || inventoryItemCount < 0 || inventoryPageCount < 0
                    || deletedClassificationCount < 0 || deletedExpectedCount < 0
                    || deletedItemCount < 0 || deletedPageCount < 0 || pageAfterSequence < 0
                    || !FINGERPRINT.matcher(expectedClassificationRoot).matches()
                    || !FINGERPRINT.matcher(deletedClassificationRoot).matches()
                    || !FINGERPRINT.matcher(expectedSnapshotRoot).matches()
                    || !FINGERPRINT.matcher(deletedExpectedRoot).matches()
                    || !FINGERPRINT.matcher(inventoryItemRoot).matches()
                    || !FINGERPRINT.matcher(deletedItemRoot).matches()
                    || !FINGERPRINT.matcher(deletedPageRoot).matches()
                    || revision < 0 || startedAt == null || updatedAt == null
                    || active != (completedAt == null)
                    || complete != allChildrenComplete()
                    || !FINGERPRINT.matcher(recordFingerprint).matches()
                    || !recordFingerprint.equals(ProtocolFingerprint.of(objectMapper, material()))) {
                throw new IllegalStateException("External source-retention progress is corrupt");
            }
        }

        private boolean allChildrenComplete() {
            return classificationComplete && expectedComplete && itemComplete && pageComplete;
        }

        private SourceProgress classifications(
                String cursor,
                long count,
                String root,
                boolean complete,
                Instant now) {
            return copy(status, cursor, count, root, complete,
                    expectedAfterObjectId, deletedExpectedCount, deletedExpectedRoot,
                    expectedComplete, itemAfterObjectId, deletedItemCount, deletedItemRoot,
                    itemComplete, pageAfterSequence, deletedPageCount, deletedPageRoot,
                    pageComplete, null, now);
        }

        private SourceProgress expected(
                String cursor,
                long count,
                String root,
                boolean complete,
                Instant now) {
            return copy(status, classificationAfterObjectId, deletedClassificationCount,
                    deletedClassificationRoot, classificationComplete, cursor, count, root,
                    complete, itemAfterObjectId, deletedItemCount, deletedItemRoot, itemComplete,
                    pageAfterSequence, deletedPageCount, deletedPageRoot, pageComplete, null, now);
        }

        private SourceProgress items(
                String cursor,
                long count,
                String root,
                boolean complete,
                Instant now) {
            return copy(status, classificationAfterObjectId, deletedClassificationCount,
                    deletedClassificationRoot, classificationComplete, expectedAfterObjectId,
                    deletedExpectedCount, deletedExpectedRoot, expectedComplete, cursor, count,
                    root, complete, pageAfterSequence, deletedPageCount, deletedPageRoot,
                    pageComplete, null, now);
        }

        private SourceProgress pages(
                long sequence,
                long count,
                String root,
                boolean complete,
                Instant now) {
            return copy(status, classificationAfterObjectId, deletedClassificationCount,
                    deletedClassificationRoot, classificationComplete, expectedAfterObjectId,
                    deletedExpectedCount, deletedExpectedRoot, expectedComplete,
                    itemAfterObjectId, deletedItemCount, deletedItemRoot, itemComplete, sequence,
                    count, root, complete, null, now);
        }

        private SourceProgress completed(Instant now) {
            return copy("COMPLETED", classificationAfterObjectId, deletedClassificationCount,
                    deletedClassificationRoot, true, expectedAfterObjectId, deletedExpectedCount,
                    deletedExpectedRoot, true, itemAfterObjectId, deletedItemCount,
                    deletedItemRoot, true, pageAfterSequence, deletedPageCount, deletedPageRoot,
                    true, now, now);
        }

        private SourceProgress copy(
                String nextStatus,
                String classificationCursor,
                long classificationCount,
                String classificationRoot,
                boolean classificationsDone,
                String expectedCursor,
                long expectedCount,
                String expectedRoot,
                boolean expectedDone,
                String itemCursor,
                long itemCount,
                String itemRoot,
                boolean itemsDone,
                long pageSequence,
                long pageCount,
                String pageRoot,
                boolean pagesDone,
                Instant completion,
                Instant now) {
            return new SourceProgress(cycleId, comparisonId, authorityId, mode, nextStatus,
                    sourceCycleFingerprint, sourceComparisonFingerprint,
                    expectedClassificationCount, expectedClassificationRoot,
                    classificationCursor, classificationCount, classificationRoot,
                    classificationsDone, expectedSnapshotCount, expectedSnapshotRoot,
                    expectedCursor, expectedCount, expectedRoot, expectedDone, inventoryItemCount,
                    inventoryItemRoot, itemCursor, itemCount, itemRoot, itemsDone,
                    inventoryPageCount, pageSequence, pageCount, pageRoot, pagesDone,
                    increment(revision, "source-retention progress revision"), startedAt,
                    completion, now, "");
        }

        private SourceProgress withFingerprint(String fingerprint) {
            return new SourceProgress(cycleId, comparisonId, authorityId, mode, status,
                    sourceCycleFingerprint, sourceComparisonFingerprint,
                    expectedClassificationCount, expectedClassificationRoot,
                    classificationAfterObjectId, deletedClassificationCount,
                    deletedClassificationRoot, classificationComplete, expectedSnapshotCount,
                    expectedSnapshotRoot, expectedAfterObjectId, deletedExpectedCount,
                    deletedExpectedRoot, expectedComplete, inventoryItemCount, inventoryItemRoot,
                    itemAfterObjectId, deletedItemCount, deletedItemRoot, itemComplete,
                    inventoryPageCount, pageAfterSequence, deletedPageCount, deletedPageRoot,
                    pageComplete, revision, startedAt, completedAt, updatedAt, fingerprint);
        }

        private ProgressMaterial material() {
            return new ProgressMaterial(PROGRESS_SCHEMA, cycleId, comparisonId, authorityId,
                    mode, status, sourceCycleFingerprint, sourceComparisonFingerprint,
                    expectedClassificationCount, expectedClassificationRoot,
                    classificationAfterObjectId, deletedClassificationCount,
                    deletedClassificationRoot, classificationComplete, expectedSnapshotCount,
                    expectedSnapshotRoot, expectedAfterObjectId, deletedExpectedCount,
                    deletedExpectedRoot, expectedComplete, inventoryItemCount, inventoryItemRoot,
                    itemAfterObjectId, deletedItemCount, deletedItemRoot, itemComplete,
                    inventoryPageCount, pageAfterSequence, deletedPageCount, deletedPageRoot,
                    pageComplete, revision, startedAt, completedAt, updatedAt);
        }

        private Object[] sqlArguments() {
            return new Object[]{cycleId, comparisonId, authorityId, mode.name(), status,
                    sourceCycleFingerprint, sourceComparisonFingerprint,
                    expectedClassificationCount, expectedClassificationRoot,
                    classificationAfterObjectId, deletedClassificationCount,
                    deletedClassificationRoot, classificationComplete, expectedSnapshotCount,
                    expectedSnapshotRoot, expectedAfterObjectId, deletedExpectedCount,
                    deletedExpectedRoot, expectedComplete, inventoryItemCount, inventoryItemRoot,
                    itemAfterObjectId, deletedItemCount, deletedItemRoot, itemComplete,
                    inventoryPageCount, pageAfterSequence, deletedPageCount, deletedPageRoot,
                    pageComplete, revision, Timestamp.from(startedAt), timestamp(completedAt),
                    Timestamp.from(updatedAt), recordFingerprint};
        }

        private Object[] progressUpdateArguments() {
            return new Object[]{status, classificationAfterObjectId,
                    deletedClassificationCount, deletedClassificationRoot,
                    classificationComplete, expectedAfterObjectId, deletedExpectedCount,
                    deletedExpectedRoot, expectedComplete, itemAfterObjectId, deletedItemCount,
                    deletedItemRoot, itemComplete, pageAfterSequence, deletedPageCount,
                    deletedPageRoot, pageComplete, revision, timestamp(completedAt),
                    Timestamp.from(updatedAt), recordFingerprint};
        }
    }

    private record ProgressMaterial(
            String schemaVersion,
            String cycleId,
            String comparisonId,
            String authorityId,
            RetirementMode mode,
            String status,
            String sourceCycleFingerprint,
            String sourceComparisonFingerprint,
            long expectedClassificationCount,
            String expectedClassificationRoot,
            String classificationAfterObjectId,
            long deletedClassificationCount,
            String deletedClassificationRoot,
            boolean classificationComplete,
            long expectedSnapshotCount,
            String expectedSnapshotRoot,
            String expectedAfterObjectId,
            long deletedExpectedCount,
            String deletedExpectedRoot,
            boolean expectedComplete,
            long inventoryItemCount,
            String inventoryItemRoot,
            String itemAfterObjectId,
            long deletedItemCount,
            String deletedItemRoot,
            boolean itemComplete,
            long inventoryPageCount,
            long pageAfterSequence,
            long deletedPageCount,
            String deletedPageRoot,
            boolean pageComplete,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt) {
    }

    private record InventoryControlAuthority(
            String authorityId,
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseUntil,
            long revision,
            String activeCycleId,
            String lastCompletedCycleId,
            Instant lastSuccessAt,
            Instant updatedAt,
            String recordFingerprint) {
        private void verify(ObjectMapper objectMapper, Candidate candidate) {
            String expected = ExternalArchiveInventoryStateIntegrity.authorityFingerprint(
                    objectMapper, authorityId, leaseOwner, leaseToken, leaseEpoch, leaseUntil,
                    revision, activeCycleId, lastCompletedCycleId, lastSuccessAt, updatedAt);
            if (!authorityId.equals(candidate.authorityId())
                    || candidate.cycleId().equals(activeCycleId)
                    || candidate.cycleId().equals(lastCompletedCycleId)
                    || !recordFingerprint.equals(expected)) {
                throw new IllegalStateException("External inventory authority control is corrupt");
            }
        }
    }

    private record ComparisonControlAuthority(
            String authorityId,
            String activeComparisonId,
            String lastCompletedComparisonId,
            long revision,
            Instant updatedAt,
            String recordFingerprint) {
        private void verify(ObjectMapper objectMapper, Candidate candidate) {
            String expected = ExternalArchiveComparisonStateIntegrity.authorityFingerprint(
                    objectMapper, authorityId, activeComparisonId, lastCompletedComparisonId,
                    revision, updatedAt);
            if (!authorityId.equals(candidate.authorityId())
                    || candidate.comparisonId().equals(activeComparisonId)
                    || candidate.comparisonId().equals(lastCompletedComparisonId)
                    || !recordFingerprint.equals(expected)) {
                throw new IllegalStateException("External comparison authority control is corrupt");
            }
        }
    }

    private record FindingControlAuthority(
            String authorityId,
            String activeProjectionId,
            String lastCompletedProjectionId,
            String lastAppliedComparisonId,
            Instant lastAppliedComparisonCompletedAt,
            long revision,
            Instant updatedAt,
            String recordFingerprint) {
        private void verify(ObjectMapper objectMapper, Candidate candidate) {
            String expected = ExternalArchiveFindingStateIntegrity.authorityFingerprint(
                    objectMapper, authorityId, activeProjectionId, lastCompletedProjectionId,
                    lastAppliedComparisonId, lastAppliedComparisonCompletedAt, revision,
                    updatedAt);
            if (!authorityId.equals(candidate.authorityId())
                    || candidate.comparisonId().equals(lastAppliedComparisonId)
                    || !recordFingerprint.equals(expected)) {
                throw new IllegalStateException("External finding authority control is corrupt");
            }
        }
    }

    private record GovernanceProjection(
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
            Instant updatedAt,
            String recordFingerprint) {
        private void verify(ObjectMapper objectMapper, StoredComparison comparison) {
            String expected = ExternalArchiveFindingStateIntegrity.projectionFingerprint(
                    objectMapper, projectionId, comparisonId, authorityId, status,
                    comparisonStartedAt, comparisonCompletedAt, sourceClassificationCount,
                    sourceClassificationRoot, snapshotFindingCount, snapshotRoot,
                    nextAfterObjectId, nextPageSequence, processedClassificationCount,
                    openedCount, observedCount, reopenedCount, resolvedCount, confirmedCount,
                    eventRoot, revision, startedAt, completedAt, updatedAt);
            if (!"COMPLETED".equals(status)
                    || !comparisonId.equals(comparison.comparisonId())
                    || !authorityId.equals(comparison.authorityId())
                    || !comparisonStartedAt.equals(comparison.startedAt())
                    || !comparisonCompletedAt.equals(comparison.completedAt())
                    || sourceClassificationCount != comparison.classifiedObjectCount()
                    || !sourceClassificationRoot.equals(comparison.classificationRoot())
                    || processedClassificationCount != sourceClassificationCount
                    || completedAt == null || !recordFingerprint.equals(expected)) {
                throw new IllegalStateException("External source governance projection is corrupt");
            }
        }
    }

    private record GovernanceEvidenceRetirement(
            String projectionId,
            String comparisonId,
            String authorityId,
            String status,
            Instant startedAt,
            Instant completedAt,
            String recordFingerprint) {
        private void verify(ObjectMapper objectMapper, GovernanceProjection projection) {
            String expected =
                    DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                            .evidenceRetirementFingerprint(objectMapper, projectionId,
                                    comparisonId, authorityId, status, startedAt, completedAt);
            if (!"COMPLETED".equals(status) || completedAt == null
                    || !projectionId.equals(projection.projectionId())
                    || !comparisonId.equals(projection.comparisonId())
                    || !authorityId.equals(projection.authorityId())
                    || !recordFingerprint.equals(expected)) {
                throw new IllegalStateException(
                        "External source finding evidence retirement is corrupt");
            }
        }
    }

    private record StoredCycle(
            String cycleId,
            String authorityId,
            String status,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            String snapshotId,
            Instant snapshotAt,
            long snapshotObjectCount,
            String snapshotRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long accumulatedObjectCount,
            String accumulatedRoot,
            String lastObjectId,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt,
            String recordFingerprint) {
        private void verify(ObjectMapper objectMapper) {
            String expected = ExternalArchiveInventoryStateIntegrity.cycleFingerprint(objectMapper,
                    cycleId, authorityId, status, trustDomain, archiveSetId, failureDomain,
                    snapshotId, snapshotAt, snapshotObjectCount, snapshotRoot, nextAfterObjectId,
                    nextPageSequence, accumulatedObjectCount, accumulatedRoot, lastObjectId,
                    revision, startedAt, completedAt, updatedAt);
            if (!FINGERPRINT.matcher(recordFingerprint).matches()
                    || !recordFingerprint.equals(expected)) {
                throw new IllegalStateException("External source inventory cycle is corrupt");
            }
        }
    }

    private record StoredComparison(
            String comparisonId,
            String cycleId,
            String authorityId,
            String status,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            String remoteSnapshotId,
            long remoteObjectCount,
            String remoteRoot,
            long expectedObjectCount,
            String expectedRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long classifiedObjectCount,
            long matchedCount,
            long missingRemoteCount,
            long unexpectedRemoteCount,
            long materialConflictCount,
            long retentionShortenedCount,
            long unknownCount,
            String classificationRoot,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt,
            String recordFingerprint) {
        private void verify(ObjectMapper objectMapper) {
            String expected = ExternalArchiveComparisonStateIntegrity.comparisonFingerprint(
                    objectMapper, comparisonId, cycleId, authorityId, status, trustDomain,
                    archiveSetId, failureDomain, remoteSnapshotId, remoteObjectCount, remoteRoot,
                    expectedObjectCount, expectedRoot, nextAfterObjectId, nextPageSequence,
                    classifiedObjectCount, matchedCount, missingRemoteCount,
                    unexpectedRemoteCount, materialConflictCount, retentionShortenedCount,
                    unknownCount, classificationRoot, revision, startedAt, completedAt, updatedAt);
            if (!FINGERPRINT.matcher(recordFingerprint).matches()
                    || !recordFingerprint.equals(expected)) {
                throw new IllegalStateException("External source comparison is corrupt");
            }
        }
    }

    private record StoredClassification(
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Classification classification,
            long pageSequence,
            Instant committedAt,
            String recordFingerprint) {
        private void verify(ObjectMapper objectMapper, SourceProgress progress) {
            String expected = ExternalArchiveComparisonStateIntegrity.classificationRowFingerprint(
                    objectMapper, classification, pageSequence, committedAt);
            if (!classification.fingerprintVerified(objectMapper)
                    || !classification.comparisonId().equals(progress.comparisonId())
                    || !classification.cycleId().equals(progress.cycleId())
                    || !classification.authorityId().equals(progress.authorityId())
                    || !expected.equals(recordFingerprint)) {
                throw new IllegalStateException("External source classification row is corrupt");
            }
        }
    }

    private record StoredExpected(
            String authorityId,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            TestSuiteStabilityObservationExternalArchiveInventoryItem item) {
        private void verify(
                ObjectMapper objectMapper,
                SourceProgress progress,
                String previousObjectId) {
            if (!item.fingerprintVerified(objectMapper)
                    || previousObjectId.compareTo(item.objectId()) >= 0
                    || !authorityId.equals(progress.authorityId())) {
                throw new IllegalStateException("External frozen expected row is corrupt");
            }
        }

        private String topologyFingerprint(ObjectMapper objectMapper) {
            return ExternalArchiveComparisonStateIntegrity.topologyFingerprint(objectMapper,
                    trustDomain, archiveSetId, authorityId, failureDomain);
        }
    }

    private record StoredItem(
            String cycleId,
            long pageSequence,
            TestSuiteStabilityObservationExternalArchiveInventoryItem item,
            Instant committedAt,
            String recordFingerprint) {
        private void verify(
                ObjectMapper objectMapper,
                SourceProgress progress,
                String previousObjectId) {
            String expected = ExternalArchiveInventoryStagingIntegrity.itemFingerprint(
                    objectMapper, cycleId, pageSequence, item, committedAt);
            if (!cycleId.equals(progress.cycleId()) || !item.fingerprintVerified(objectMapper)
                    || previousObjectId.compareTo(item.objectId()) >= 0
                    || !expected.equals(recordFingerprint)) {
                throw new IllegalStateException("External source inventory item row is corrupt");
            }
        }
    }

    private record StoredPage(
            String cycleId,
            long pageSequence,
            String authorityId,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            String requestFingerprint,
            String pageFingerprint,
            String snapshotId,
            Instant snapshotAt,
            long snapshotObjectCount,
            String snapshotRoot,
            String afterObjectId,
            String nextAfterObjectId,
            int itemCount,
            boolean complete,
            Instant issuedAt,
            Instant expiresAt,
            Instant committedAt,
            String pageJson,
            String recordFingerprint) {
        private void verify(
                ObjectMapper objectMapper,
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority authority,
                SourceProgress progress,
                long expectedSequence) {
            TestSuiteStabilityObservationExternalArchiveInventoryPage page;
            try {
                page = objectMapper.readValue(pageJson,
                        TestSuiteStabilityObservationExternalArchiveInventoryPage.class);
            } catch (JsonProcessingException invalid) {
                throw new IllegalStateException("External stored inventory page JSON is corrupt");
            }
            String expected = ExternalArchiveInventoryStagingIntegrity.pageFingerprint(objectMapper,
                    cycleId, pageSequence, authorityId, trustDomain, archiveSetId, failureDomain,
                    requestFingerprint, pageFingerprint, snapshotId, snapshotAt,
                    snapshotObjectCount, snapshotRoot, afterObjectId, nextAfterObjectId, itemCount,
                    complete, issuedAt, expiresAt, committedAt, pageJson);
            var verification = authority.verifyStoredInventoryPage(page);
            if (!cycleId.equals(progress.cycleId()) || pageSequence != expectedSequence
                    || !authorityId.equals(progress.authorityId())
                    || !page.fingerprintVerified(objectMapper)
                    || page.request().pageSequence() != pageSequence
                    || !authorityId.equals(page.authorityId())
                    || !trustDomain.equals(page.request().trustDomain())
                    || !archiveSetId.equals(page.request().archiveSetId())
                    || !failureDomain.equals(page.failureDomain())
                    || !requestFingerprint.equals(page.request().requestFingerprint())
                    || !pageFingerprint.equals(page.pageFingerprint())
                    || !snapshotId.equals(page.snapshotId())
                    || !snapshotAt.equals(page.snapshotAt())
                    || snapshotObjectCount != page.snapshotObjectCount()
                    || !snapshotRoot.equals(page.snapshotRoot())
                    || !afterObjectId.equals(page.request().afterObjectId())
                    || !nextAfterObjectId.equals(page.nextAfterObjectId())
                    || itemCount != page.items().size() || complete != page.complete()
                    || !issuedAt.equals(page.issuedAt()) || !expiresAt.equals(page.expiresAt())
                    || verification
                    != TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification
                    .VERIFIED || !expected.equals(recordFingerprint)) {
                throw new IllegalStateException("External source inventory page row is corrupt");
            }
        }
    }

    private record PageRootLink(
            String schemaVersion,
            String previousRoot,
            String pageRecordFingerprint) {
    }

    private static boolean isUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
