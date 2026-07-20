package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryItem;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationExternalArchiveInventoryIntegrity;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Durable, bounded classifier for one completed external observation-archive inventory cycle.
 *
 * <p>A comparison first freezes the complete local expected-object set in the same transaction that
 * pins one verified remote cycle. Later calls merge the two immutable snapshots in object-id order,
 * committing a bounded page and an exact continuation cursor at a time. A terminal comparison is
 * published only after independent replays reproduce the local snapshot root, signed remote root,
 * classification root, outcome counters, and exact union coverage.</p>
 *
 * <p>The control plane stores identities, fingerprints, topology, and retention times only. It has
 * no external delete, purge, overwrite, legal-hold release, retention-shortening, or remediation
 * operation. Governed finding workflow consumes only completed comparisons in a separate layer.</p>
 */
public final class
        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane {
    /** Domain-separated root before the first ordered classification. */
    public static final String EMPTY_CLASSIFICATION_ROOT = ProtocolFingerprint.ofText(
            "bloge.testSuiteStabilityObservationExternalArchiveClassificationRoot.v1:empty");

    /** Domain-separated root before the first frozen local expected fact. */
    public static final String EMPTY_EXPECTED_ROOT = ProtocolFingerprint.ofText(
            "bloge.testSuiteStabilityObservationExternalArchiveExpectedRoot.v1:empty");

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern OBJECT_ID =
            Pattern.compile("stability-observation-worm-[a-f0-9]{64}");
    private static final Pattern SNAPSHOT_ID = Pattern.compile(
            "stability-observation-external-inventory-[a-f0-9]{64}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Settings settings;

    /**
     * Creates a classifier using a transaction manager derived from the JDBC datasource.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param settings bounded merge settings
     */
    public DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            Settings settings) {
        this(jdbc, localTransactionManager(jdbc), objectMapper, settings);
    }

    /**
     * Creates a classifier over the inventory and expected-object datasource.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param transactionManager transaction manager bound to the JDBC datasource
     * @param objectMapper canonical protocol mapper
     * @param settings bounded merge settings
     */
    public DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            Settings settings) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.settings = Objects.requireNonNull(settings, "settings");
        transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates comparison authority, frozen snapshot, run, and classification tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_comparison_authorities (
                    authority_id VARCHAR(255) PRIMARY KEY,
                    active_comparison_id VARCHAR(36) NOT NULL,
                    last_completed_comparison_id VARCHAR(36) NOT NULL,
                    revision BIGINT NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_comparisons (
                    comparison_id VARCHAR(36) PRIMARY KEY,
                    cycle_id VARCHAR(36) NOT NULL,
                    authority_id VARCHAR(255) NOT NULL,
                    comparison_status VARCHAR(32) NOT NULL,
                    trust_domain VARCHAR(255) NOT NULL,
                    archive_set_id VARCHAR(255) NOT NULL,
                    failure_domain VARCHAR(255) NOT NULL,
                    remote_snapshot_id VARCHAR(255) NOT NULL,
                    remote_object_count BIGINT NOT NULL,
                    remote_root VARCHAR(71) NOT NULL,
                    expected_object_count BIGINT NOT NULL,
                    expected_root VARCHAR(71) NOT NULL,
                    next_after_object_id VARCHAR(255) NOT NULL,
                    next_page_sequence BIGINT NOT NULL,
                    classified_object_count BIGINT NOT NULL,
                    matched_count BIGINT NOT NULL,
                    missing_remote_count BIGINT NOT NULL,
                    unexpected_remote_count BIGINT NOT NULL,
                    material_conflict_count BIGINT NOT NULL,
                    retention_shortened_count BIGINT NOT NULL,
                    unknown_count BIGINT NOT NULL,
                    classification_root VARCHAR(71) NOT NULL,
                    revision BIGINT NOT NULL,
                    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    completed_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    CONSTRAINT uq_rg_test_suite_stability_observation_external_comparison_cycle
                        UNIQUE (cycle_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_external_comparison_authority
                ON rg_test_suite_stability_observation_external_comparisons (
                    authority_id, comparison_status, started_at, comparison_id
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_expected_snapshots (
                    comparison_id VARCHAR(36) NOT NULL,
                    authority_id VARCHAR(255) NOT NULL,
                    object_id VARCHAR(255) NOT NULL,
                    trust_domain VARCHAR(255) NOT NULL,
                    archive_set_id VARCHAR(255) NOT NULL,
                    failure_domain VARCHAR(255) NOT NULL,
                    item_fingerprint VARCHAR(71) NOT NULL,
                    object_commitment VARCHAR(71) NOT NULL,
                    retirement_id VARCHAR(255) NOT NULL,
                    retirement_fingerprint VARCHAR(71) NOT NULL,
                    segment_id VARCHAR(255) NOT NULL,
                    segment_fingerprint VARCHAR(71) NOT NULL,
                    retention_policy_fingerprint VARCHAR(71) NOT NULL,
                    retain_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    stored_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (comparison_id, object_id),
                    CONSTRAINT uq_rg_test_suite_stability_observation_external_expected_item
                        UNIQUE (comparison_id, item_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_classifications (
                    comparison_id VARCHAR(36) NOT NULL,
                    cycle_id VARCHAR(36) NOT NULL,
                    authority_id VARCHAR(255) NOT NULL,
                    object_id VARCHAR(255) NOT NULL,
                    page_sequence BIGINT NOT NULL,
                    outcome VARCHAR(32) NOT NULL,
                    expected_item_fingerprint VARCHAR(71) NOT NULL,
                    observed_item_fingerprint VARCHAR(71) NOT NULL,
                    expected_object_commitment VARCHAR(71) NOT NULL,
                    observed_object_commitment VARCHAR(71) NOT NULL,
                    expected_topology_fingerprint VARCHAR(71) NOT NULL,
                    observed_topology_fingerprint VARCHAR(71) NOT NULL,
                    expected_retain_until TIMESTAMP WITH TIME ZONE,
                    observed_retain_until TIMESTAMP WITH TIME ZONE,
                    classification_fingerprint VARCHAR(71) NOT NULL,
                    committed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (comparison_id, object_id),
                    CONSTRAINT uq_rg_test_suite_stability_observation_external_classification
                        UNIQUE (comparison_id, classification_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_external_classification_page
                ON rg_test_suite_stability_observation_external_classifications (
                    comparison_id, page_sequence, object_id
                )
                """);
        migrateStorageFingerprints();
    }

    /**
     * Baselines legacy test/staging rows once, then rejects every subsequent whole-row drift.
     *
     * <p>This is deliberately not advertised as an online N/N-1 production migration. Existing
     * nonblank fingerprints are verified and are never silently regenerated.</p>
     */
    private void migrateStorageFingerprints() {
        jdbc.execute("""
                ALTER TABLE
                    rg_test_suite_stability_observation_external_comparison_authorities
                ADD COLUMN IF NOT EXISTS record_fingerprint VARCHAR(71)
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_suite_stability_observation_external_classifications
                ADD COLUMN IF NOT EXISTS record_fingerprint VARCHAR(71)
                """);
        transactions.executeWithoutResult(status -> {
            List<StoredComparisonAuthority> authorities = jdbc.query("""
                    SELECT authority_id, active_comparison_id, last_completed_comparison_id,
                           revision, updated_at, record_fingerprint
                    FROM rg_test_suite_stability_observation_external_comparison_authorities
                    ORDER BY authority_id
                    FOR UPDATE
                    """, (result, row) -> new StoredComparisonAuthority(
                    result.getString("authority_id"),
                    result.getString("active_comparison_id"),
                    result.getString("last_completed_comparison_id"),
                    result.getLong("revision"), instant(result, "updated_at"),
                    result.getString("record_fingerprint")));
            for (StoredComparisonAuthority authority : authorities) {
                if (normalized(authority.recordFingerprint()).isEmpty()) {
                    StoredComparisonAuthority migrated = fingerprintedAuthority(authority);
                    int updated = jdbc.update("""
                            UPDATE
                                rg_test_suite_stability_observation_external_comparison_authorities
                            SET record_fingerprint = ?
                            WHERE authority_id = ? AND revision = ?
                              AND (record_fingerprint IS NULL OR record_fingerprint = '')
                            """, migrated.recordFingerprint(), migrated.authorityId(),
                            migrated.revision());
                    if (updated != 1) {
                        throw new IllegalStateException(
                                "External comparison authority fingerprint migration lost its fence");
                    }
                } else {
                    authority.validate(objectMapper);
                }
            }
            List<StoredClassificationRow> classifications = jdbc.query("""
                    SELECT comparison_id, cycle_id, authority_id, object_id, page_sequence,
                           outcome, expected_item_fingerprint, observed_item_fingerprint,
                           expected_object_commitment, observed_object_commitment,
                           expected_topology_fingerprint, observed_topology_fingerprint,
                           expected_retain_until, observed_retain_until,
                           classification_fingerprint, committed_at, record_fingerprint
                    FROM rg_test_suite_stability_observation_external_classifications
                    ORDER BY comparison_id, object_id
                    FOR UPDATE
                    """, this::storedClassificationRow);
            for (StoredClassificationRow classification : classifications) {
                String fingerprint = classification.expectedFingerprint(objectMapper);
                if (normalized(classification.recordFingerprint()).isEmpty()) {
                    int updated = jdbc.update("""
                            UPDATE rg_test_suite_stability_observation_external_classifications
                            SET record_fingerprint = ?
                            WHERE comparison_id = ? AND object_id = ?
                              AND classification_fingerprint = ?
                              AND (record_fingerprint IS NULL OR record_fingerprint = '')
                            """, fingerprint, classification.classification().comparisonId(),
                            classification.classification().objectId(),
                            classification.classification().classificationFingerprint());
                    if (updated != 1) {
                        throw new IllegalStateException(
                                "External classification fingerprint migration lost its fence");
                    }
                } else {
                    classification.verify(objectMapper);
                }
            }
        });
        jdbc.execute("""
                ALTER TABLE
                    rg_test_suite_stability_observation_external_comparison_authorities
                ALTER COLUMN record_fingerprint SET NOT NULL
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_suite_stability_observation_external_classifications
                ALTER COLUMN record_fingerprint SET NOT NULL
                """);
    }

    /**
     * Starts or advances one bounded comparison page for an authority.
     *
     * <p>The first invocation freezes all current expected objects and pins the authority's latest
     * completed remote cycle. A {@link ComparisonStatus#CURRENT} result means that exact latest cycle
     * is already completely classified and no rows were changed.</p>
     *
     * @param authorityId exact inventory authority
     * @return payload-free bounded comparison progress
     */
    public ComparisonPage compareNextPage(String authorityId) {
        String exactAuthority = requiredIdentifier(authorityId, "inventory authority");
        ComparisonPage result = transactions.execute(status -> compareInTransaction(exactAuthority));
        if (result == null) {
            throw new IllegalStateException("External inventory comparison returned no result");
        }
        return result;
    }

    /**
     * Reads one stable page of a completed comparison for evidence export.
     *
     * @param comparisonId completed comparison identity
     * @param afterObjectId exclusive object-id cursor, or empty for the first page
     * @param limit maximum classifications to return, from 1 through 500
     * @return self-verifying classifications in strict object-id order
     */
    public List<Classification> classifications(
            String comparisonId,
            String afterObjectId,
            int limit) {
        String exactComparison = requiredUuid(comparisonId, "comparison ID");
        String cursor = normalized(afterObjectId);
        if (!cursor.isEmpty() && !OBJECT_ID.matcher(cursor).matches()) {
            throw new IllegalArgumentException("Invalid classification export cursor");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Classification export limit must be 1 through 500");
        }
        List<Classification> result = transactions.execute(status -> {
            StoredComparison comparison = readComparison(exactComparison);
            comparison.requireCompleted();
            List<StoredClassificationRow> rows = jdbc.query("""
                    SELECT comparison_id, cycle_id, authority_id, object_id, outcome,
                           page_sequence,
                           expected_item_fingerprint, observed_item_fingerprint,
                           expected_object_commitment, observed_object_commitment,
                           expected_topology_fingerprint, observed_topology_fingerprint,
                           expected_retain_until, observed_retain_until,
                           classification_fingerprint, committed_at, record_fingerprint
                    FROM rg_test_suite_stability_observation_external_classifications
                    WHERE comparison_id = ? AND object_id > ?
                    ORDER BY object_id
                    LIMIT ?
                    """, this::storedClassificationRow, exactComparison, cursor, limit);
            String previous = cursor;
            for (StoredClassificationRow stored : rows) {
                stored.verify(objectMapper);
                Classification row = stored.classification();
                if (previous.compareTo(row.objectId()) >= 0) {
                    throw new IllegalStateException(
                            "Stored external inventory classification is corrupt");
                }
                previous = row.objectId();
            }
            return rows.stream().map(StoredClassificationRow::classification).toList();
        });
        if (result == null) {
            throw new IllegalStateException("Classification export returned no result");
        }
        return result;
    }

    /**
     * Returns verified identity-free comparison progress for operational readiness.
     *
     * <p>The read holds the authority row while it verifies the active and latest completed
     * comparison fingerprints. Comparison, cycle, object, cursor, topology, and fingerprint
     * identities never leave the control plane.</p>
     *
     * @param authorityId exact configured inventory authority
     * @return database-clock stage initialization, progress, and completion freshness
     */
    public OperationalSnapshot operationalSnapshot(String authorityId) {
        String authority = requiredIdentifier(authorityId, "inventory authority");
        OperationalSnapshot result = transactions.execute(status -> {
            Integer inventoryAuthorities = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM rg_test_suite_stability_observation_external_inventory_authorities
                    WHERE authority_id = ?
                    """, Integer.class, authority);
            if (inventoryAuthorities == null || inventoryAuthorities != 1) {
                throw new IllegalArgumentException(
                        "External inventory authority is not initialized");
            }
            Instant observedAt = databaseNow();
            Integer comparisonAuthorities = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM rg_test_suite_stability_observation_external_comparison_authorities
                    WHERE authority_id = ?
                    """, Integer.class, authority);
            if (comparisonAuthorities == null || comparisonAuthorities == 0) {
                return OperationalSnapshot.uninitialized(observedAt);
            }
            if (comparisonAuthorities != 1) {
                throw new IllegalStateException(
                        "External comparison authority cardinality is corrupt");
            }
            StoredComparisonAuthority state = lockComparisonAuthority(authority);
            StoredComparison active = state.activeComparisonId().isEmpty()
                    ? null : lockComparison(state.activeComparisonId());
            StoredComparison completed = state.lastCompletedComparisonId().isEmpty()
                    ? null : lockComparison(state.lastCompletedComparisonId());
            if (active != null) {
                active.requireActiveFor(authority);
            }
            if (completed != null) {
                completed.requireCompleted();
                if (!completed.authorityId().equals(authority)) {
                    throw new IllegalStateException(
                            "External completed comparison authority is corrupt");
                }
            }
            return new OperationalSnapshot(observedAt, true, active != null,
                    active == null ? 0 : active.nextPageSequence(),
                    active == null ? 0 : active.classifiedObjectCount(),
                    active == null ? 0 : active.findingObjectCount(),
                    active == null ? null : active.startedAt(),
                    active == null ? null : active.updatedAt(),
                    completed == null ? null : completed.completedAt());
        });
        if (result == null) {
            throw new IllegalStateException(
                    "External comparison operational snapshot returned no result");
        }
        return result;
    }

    private ComparisonPage compareInTransaction(String authorityId) {
        initializeComparisonAuthority(authorityId, databaseNow());
        StoredComparisonAuthority state = lockComparisonAuthority(authorityId);
        Instant now = monotonicDatabaseTime(state.updatedAt());
        StoredComparison comparison;
        if (state.activeComparisonId().isEmpty()) {
            StoredInventoryAuthority inventory = lockInventoryAuthority(authorityId);
            StoredComparison existing = findComparisonByCycle(inventory.lastCompletedCycleId());
            if (existing != null) {
                existing.requireCompleted();
                if (!existing.authorityId().equals(authorityId)
                        || !state.lastCompletedComparisonId().equals(existing.comparisonId())) {
                    throw new IllegalStateException(
                            "External inventory comparison authority is corrupt");
                }
                return ComparisonPage.current(existing);
            }
            comparison = createComparison(authorityId, inventory.lastCompletedCycleId(), now);
            activateComparison(state, comparison.comparisonId(), now);
        } else {
            comparison = lockComparison(state.activeComparisonId());
            comparison.requireActiveFor(authorityId);
        }
        return stageComparisonPage(lockComparison(comparison.comparisonId()), now);
    }

    private StoredComparison createComparison(
            String authorityId,
            String cycleId,
            Instant now) {
        StoredCycle cycle = lockCompletedCycle(cycleId, authorityId);
        verifyRemoteSnapshot(cycle);
        String comparisonId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_expected_snapshots (
                        comparison_id, authority_id, object_id, trust_domain, archive_set_id,
                        failure_domain,
                        item_fingerprint, object_commitment, retirement_id,
                        retirement_fingerprint, segment_id, segment_fingerprint,
                        retention_policy_fingerprint, retain_until, stored_at
                    )
                    SELECT ?, authority_id, object_id, trust_domain, archive_set_id, failure_domain,
                           expected_item_fingerprint, object_commitment, retirement_id,
                           retirement_fingerprint, segment_id, segment_fingerprint,
                           retention_policy_fingerprint, retain_until, stored_at
                    FROM rg_test_suite_stability_observation_external_archive_objects
                    WHERE authority_id = ?
                    """, comparisonId, authorityId);
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException(
                    "External inventory expected snapshot is not unique", duplicate);
        }
        SnapshotReplay expected = replayExpectedSnapshot(comparisonId, authorityId);
        StoredComparison initial = StoredComparison.initial(
                comparisonId, cycle, expected.objectCount(), expected.root(), now, "");
        initial = initial.withRecordFingerprint(initial.fingerprint(objectMapper));
        try {
            int inserted = jdbc.update("""
                    INSERT INTO rg_test_suite_stability_observation_external_comparisons (
                        comparison_id, cycle_id, authority_id, comparison_status,
                        trust_domain, archive_set_id, failure_domain, remote_snapshot_id,
                        remote_object_count, remote_root, expected_object_count, expected_root,
                        next_after_object_id, next_page_sequence, classified_object_count,
                        matched_count, missing_remote_count, unexpected_remote_count,
                        material_conflict_count, retention_shortened_count, unknown_count,
                        classification_root, revision, started_at, completed_at, updated_at,
                        record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                              ?, ?, ?, ?, ?, ?)
                    """, initial.sqlArguments());
            if (inserted != 1) {
                throw new IllegalStateException("External inventory comparison insert was incomplete");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("External inventory cycle is already compared", duplicate);
        }
        return lockComparison(comparisonId);
    }

    private ComparisonPage stageComparisonPage(StoredComparison current, Instant now) {
        current.requireActiveFor(current.authorityId());
        Batch<ExpectedFact> expected = expectedPage(current);
        Batch<TestSuiteStabilityObservationExternalArchiveInventoryItem> observed =
                observedPage(current);
        if (expected.items().isEmpty() && observed.items().isEmpty()) {
            return completeComparison(current, List.of(), now, current.nextPageSequence());
        }
        String upperBound = mergeUpperBound(expected, observed);
        List<Classification> page = merge(current, expected.items(), observed.items(), upperBound);
        if (page.isEmpty()) {
            throw new IllegalStateException("External inventory comparison made no cursor progress");
        }
        String root = current.classificationRoot();
        OutcomeCounts counts = current.outcomeCounts();
        for (Classification classification : page) {
            root = appendClassificationRoot(root, classification);
            counts = counts.increment(classification.outcome());
            insertClassification(current, classification, now);
        }
        long classified = add(current.classifiedObjectCount(), page.size(),
                "classified object count");
        long nextSequence = increment(current.nextPageSequence(), "comparison page sequence");
        StoredComparison successor = current.progressed(
                upperBound, nextSequence, classified, counts, root, now);
        boolean complete = !hasExpectedAfter(current.comparisonId(), upperBound)
                && !hasObservedAfter(current.cycleId(), upperBound);
        if (complete) {
            return completeComparison(successor, page, now, current.nextPageSequence());
        }
        updateComparison(current, successor);
        return new ComparisonPage(ComparisonStatus.STAGED, current.authorityId(),
                current.comparisonId(), current.cycleId(), current.nextPageSequence(), page.size(),
                successor.classifiedObjectCount(), successor.findingObjectCount());
    }

    private ComparisonPage completeComparison(
            StoredComparison progressed,
            List<Classification> page,
            Instant now,
            long attemptedPageSequence) {
        SnapshotReplay expected = replayExpectedSnapshot(
                progressed.comparisonId(), progressed.authorityId());
        if (expected.objectCount() != progressed.expectedObjectCount()
                || !expected.root().equals(progressed.expectedRoot())) {
            throw new IllegalStateException("Frozen external expected snapshot replay failed");
        }
        StoredCycle cycle = lockCompletedCycle(
                progressed.cycleId(), progressed.authorityId());
        if (!cycle.matches(progressed)) {
            throw new IllegalStateException("Pinned external inventory cycle drifted");
        }
        verifyRemoteSnapshot(cycle);
        ClassificationReplay replay = replayClassifications(progressed.comparisonId());
        if (replay.objectCount() != progressed.classifiedObjectCount()
                || !replay.root().equals(progressed.classificationRoot())
                || !replay.counts().equals(progressed.outcomeCounts())) {
            throw new IllegalStateException("External inventory classification replay failed");
        }
        requireExactUnionCoverage(progressed);
        verifyClassificationSemantics(progressed);
        StoredComparison completed = progressed.completed(now);
        updateComparison(readComparison(progressed.comparisonId()), completed);
        StoredComparisonAuthority state = lockComparisonAuthority(progressed.authorityId());
        if (!state.activeComparisonId().equals(progressed.comparisonId())) {
            throw new IllegalStateException("External inventory comparison authority fence is stale");
        }
        updateComparisonAuthority(state, fingerprintedAuthority(new StoredComparisonAuthority(
                state.authorityId(), "", progressed.comparisonId(),
                increment(state.revision(), "comparison authority revision"), now, "")));
        return new ComparisonPage(ComparisonStatus.COMPLETED, progressed.authorityId(),
                progressed.comparisonId(), progressed.cycleId(), attemptedPageSequence,
                page.size(), completed.classifiedObjectCount(), completed.findingObjectCount());
    }

    private List<Classification> merge(
            StoredComparison comparison,
            List<ExpectedFact> expected,
            List<TestSuiteStabilityObservationExternalArchiveInventoryItem> observed,
            String upperBound) {
        ArrayList<Classification> result = new ArrayList<>();
        int expectedIndex = 0;
        int observedIndex = 0;
        String observedTopology = topologyFingerprint(comparison.trustDomain(),
                comparison.archiveSetId(), comparison.authorityId(), comparison.failureDomain());
        while (expectedIndex < expected.size() || observedIndex < observed.size()) {
            ExpectedFact local = expectedIndex < expected.size() ? expected.get(expectedIndex) : null;
            TestSuiteStabilityObservationExternalArchiveInventoryItem remote =
                    observedIndex < observed.size() ? observed.get(observedIndex) : null;
            String localId = local == null ? null : local.item().objectId();
            String remoteId = remote == null ? null : remote.objectId();
            String nextId = localId == null ? remoteId
                    : remoteId == null ? localId
                    : localId.compareTo(remoteId) <= 0 ? localId : remoteId;
            if (nextId == null || nextId.compareTo(upperBound) > 0) {
                break;
            }
            if (localId != null && localId.equals(nextId)
                    && remoteId != null && remoteId.equals(nextId)) {
                result.add(classify(comparison, local, remote, observedTopology));
                expectedIndex++;
                observedIndex++;
            } else if (localId != null && localId.equals(nextId)) {
                result.add(classify(comparison, local, null, observedTopology));
                expectedIndex++;
            } else {
                result.add(classify(comparison, null, remote, observedTopology));
                observedIndex++;
            }
        }
        if (result.size() > settings.maximumSourceItemsPerSide() * 2) {
            throw new IllegalStateException("External inventory comparison page exceeded its bound");
        }
        return List.copyOf(result);
    }

    private Classification classify(
            StoredComparison comparison,
            ExpectedFact expected,
            TestSuiteStabilityObservationExternalArchiveInventoryItem observed,
            String observedTopology) {
        Outcome outcome;
        if (expected == null) {
            outcome = Outcome.UNEXPECTED_REMOTE;
        } else if (observed == null) {
            outcome = Outcome.MISSING_REMOTE;
        } else if (!expected.topologyFingerprint().equals(observedTopology)) {
            outcome = Outcome.UNKNOWN;
        } else if (expected.item().itemFingerprint().equals(observed.itemFingerprint())) {
            outcome = Outcome.MATCHED;
        } else if (observed.retainUntil().isBefore(expected.item().retainUntil())) {
            outcome = Outcome.RETENTION_SHORTENED;
        } else {
            outcome = Outcome.MATERIAL_CONFLICT;
        }
        TestSuiteStabilityObservationExternalArchiveInventoryItem localItem =
                expected == null ? null : expected.item();
        String objectId = localItem == null ? observed.objectId() : localItem.objectId();
        Classification material = new Classification(
                comparison.comparisonId(), comparison.cycleId(), comparison.authorityId(), objectId,
                outcome, fingerprint(localItem), fingerprint(observed), commitment(localItem),
                commitment(observed), expected == null ? "" : expected.topologyFingerprint(),
                observedTopology, retainUntil(localItem), retainUntil(observed), "");
        return material.withFingerprint(ProtocolFingerprint.of(
                objectMapper, material.fingerprintMaterial()));
    }

    private void insertClassification(
            StoredComparison comparison,
            Classification classification,
            Instant now) {
        String recordFingerprint =
                ExternalArchiveComparisonStateIntegrity.classificationRowFingerprint(
                        objectMapper, classification, comparison.nextPageSequence(), now);
        try {
            int inserted = jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_classifications (
                        comparison_id, cycle_id, authority_id, object_id, page_sequence, outcome,
                        expected_item_fingerprint, observed_item_fingerprint,
                        expected_object_commitment, observed_object_commitment,
                        expected_topology_fingerprint, observed_topology_fingerprint,
                        expected_retain_until, observed_retain_until,
                        classification_fingerprint, committed_at, record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, comparison.comparisonId(), comparison.cycleId(),
                    comparison.authorityId(), classification.objectId(),
                    comparison.nextPageSequence(), classification.outcome().name(),
                    classification.expectedItemFingerprint(),
                    classification.observedItemFingerprint(),
                    classification.expectedObjectCommitment(),
                    classification.observedObjectCommitment(),
                    classification.expectedTopologyFingerprint(),
                    classification.observedTopologyFingerprint(),
                    timestamp(classification.expectedRetainUntil()),
                    timestamp(classification.observedRetainUntil()),
                    classification.classificationFingerprint(), Timestamp.from(now),
                    recordFingerprint);
            if (inserted != 1) {
                throw new IllegalStateException(
                        "External inventory classification insert was incomplete");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException(
                    "External inventory classification identity is duplicated", duplicate);
        }
    }

    private Batch<ExpectedFact> expectedPage(StoredComparison comparison) {
        List<ExpectedFact> rows = jdbc.query("""
                SELECT authority_id, object_id, trust_domain, archive_set_id, failure_domain,
                       item_fingerprint, object_commitment, retirement_id,
                       retirement_fingerprint, segment_id, segment_fingerprint,
                       retention_policy_fingerprint, retain_until, stored_at
                FROM rg_test_suite_stability_observation_external_expected_snapshots
                WHERE comparison_id = ? AND object_id > ?
                ORDER BY object_id
                LIMIT ?
                """, this::expectedFact, comparison.comparisonId(),
                comparison.nextAfterObjectId(), settings.maximumSourceItemsPerSide() + 1);
        for (ExpectedFact row : rows) {
            requireCanonical(row.item(), "expected inventory item");
        }
        return Batch.of(rows, settings.maximumSourceItemsPerSide());
    }

    private Batch<TestSuiteStabilityObservationExternalArchiveInventoryItem> observedPage(
            StoredComparison comparison) {
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> rows = jdbc.query("""
                SELECT cycle_id, object_id, page_sequence, item_fingerprint,
                       object_commitment, retirement_id,
                       retirement_fingerprint, segment_id, segment_fingerprint,
                       retention_policy_fingerprint, retain_until, stored_at, committed_at,
                       record_fingerprint
                FROM rg_test_suite_stability_observation_external_inventory_items
                WHERE cycle_id = ? AND object_id > ?
                ORDER BY object_id
                LIMIT ?
                """, (result, row) -> storedInventoryItem(result, "",
                        result.getString("object_id")), comparison.cycleId(),
                comparison.nextAfterObjectId(), settings.maximumSourceItemsPerSide() + 1);
        for (TestSuiteStabilityObservationExternalArchiveInventoryItem row : rows) {
            requireCanonical(row, "remote inventory item");
        }
        return Batch.of(rows, settings.maximumSourceItemsPerSide());
    }

    private void verifyClassificationSemantics(StoredComparison comparison) {
        long[] count = {0};
        String[] previous = {""};
        String observedTopology = topologyFingerprint(comparison.trustDomain(),
                comparison.archiveSetId(), comparison.authorityId(), comparison.failureDomain());
        jdbc.query("""
                SELECT source.object_id AS source_object_id,
                       expected.authority_id AS e_authority_id,
                       expected.trust_domain AS e_trust_domain,
                       expected.archive_set_id AS e_archive_set_id,
                       expected.failure_domain AS e_failure_domain,
                       expected.item_fingerprint AS e_item_fingerprint,
                       expected.object_commitment AS e_object_commitment,
                       expected.retirement_id AS e_retirement_id,
                       expected.retirement_fingerprint AS e_retirement_fingerprint,
                       expected.segment_id AS e_segment_id,
                       expected.segment_fingerprint AS e_segment_fingerprint,
                       expected.retention_policy_fingerprint AS e_retention_policy_fingerprint,
                       expected.retain_until AS e_retain_until,
                       expected.stored_at AS e_stored_at,
                       observed.item_fingerprint AS o_item_fingerprint,
                       observed.object_commitment AS o_object_commitment,
                       observed.retirement_id AS o_retirement_id,
                       observed.retirement_fingerprint AS o_retirement_fingerprint,
                       observed.segment_id AS o_segment_id,
                       observed.segment_fingerprint AS o_segment_fingerprint,
                       observed.retention_policy_fingerprint AS o_retention_policy_fingerprint,
                       observed.retain_until AS o_retain_until,
                       observed.stored_at AS o_stored_at,
                       observed.cycle_id AS o_cycle_id,
                       observed.page_sequence AS o_page_sequence,
                       observed.committed_at AS o_committed_at,
                       observed.record_fingerprint AS o_record_fingerprint,
                       classified.comparison_id AS c_comparison_id,
                       classified.cycle_id AS c_cycle_id,
                       classified.authority_id AS c_authority_id,
                       classified.outcome AS c_outcome,
                       classified.expected_item_fingerprint AS c_expected_item_fingerprint,
                       classified.observed_item_fingerprint AS c_observed_item_fingerprint,
                       classified.expected_object_commitment AS c_expected_object_commitment,
                       classified.observed_object_commitment AS c_observed_object_commitment,
                       classified.expected_topology_fingerprint AS c_expected_topology_fingerprint,
                       classified.observed_topology_fingerprint AS c_observed_topology_fingerprint,
                       classified.expected_retain_until AS c_expected_retain_until,
                       classified.observed_retain_until AS c_observed_retain_until,
                       classified.classification_fingerprint AS c_classification_fingerprint,
                       classified.page_sequence AS c_page_sequence,
                       classified.committed_at AS c_committed_at,
                       classified.record_fingerprint AS c_record_fingerprint
                FROM (
                    SELECT object_id
                    FROM rg_test_suite_stability_observation_external_expected_snapshots
                    WHERE comparison_id = ?
                    UNION
                    SELECT object_id
                    FROM rg_test_suite_stability_observation_external_inventory_items
                    WHERE cycle_id = ?
                ) source
                LEFT JOIN rg_test_suite_stability_observation_external_expected_snapshots expected
                  ON expected.comparison_id = ? AND expected.object_id = source.object_id
                LEFT JOIN rg_test_suite_stability_observation_external_inventory_items observed
                  ON observed.cycle_id = ? AND observed.object_id = source.object_id
                LEFT JOIN rg_test_suite_stability_observation_external_classifications classified
                  ON classified.comparison_id = ? AND classified.object_id = source.object_id
                ORDER BY source.object_id
                """, (RowCallbackHandler) result -> {
            String objectId = result.getString("source_object_id");
            if (previous[0].compareTo(objectId) >= 0
                    || result.getString("c_comparison_id") == null) {
                throw new IllegalStateException(
                        "External classification semantic coverage is incomplete");
            }
            ExpectedFact expected = null;
            if (result.getString("e_item_fingerprint") != null) {
                TestSuiteStabilityObservationExternalArchiveInventoryItem item =
                        inventoryItem(result, "e_", objectId);
                String authorityId = result.getString("e_authority_id");
                expected = new ExpectedFact(authorityId, result.getString("e_trust_domain"),
                        result.getString("e_archive_set_id"),
                        result.getString("e_failure_domain"), item,
                        topologyFingerprint(result.getString("e_trust_domain"),
                                result.getString("e_archive_set_id"), authorityId,
                                result.getString("e_failure_domain")));
                requireCanonical(item, "expected inventory item");
            }
            TestSuiteStabilityObservationExternalArchiveInventoryItem observed = null;
            if (result.getString("o_item_fingerprint") != null) {
                observed = storedInventoryItem(result, "o_", objectId);
                requireCanonical(observed, "remote inventory item");
            }
            StoredClassificationRow storedRow = new StoredClassificationRow(
                    classification(result, "c_", objectId),
                    result.getLong("c_page_sequence"), instant(result, "c_committed_at"),
                    result.getString("c_record_fingerprint"));
            storedRow.verify(objectMapper);
            Classification stored = storedRow.classification();
            Classification derived = classify(
                    comparison, expected, observed, observedTopology);
            if (!stored.equals(derived)) {
                throw new IllegalStateException(
                        "External inventory classification semantic replay failed");
            }
            count[0] = increment(count[0], "classification semantic replay count");
            previous[0] = objectId;
        }, comparison.comparisonId(), comparison.cycleId(), comparison.comparisonId(),
                comparison.cycleId(), comparison.comparisonId());
        if (count[0] != comparison.classifiedObjectCount()) {
            throw new IllegalStateException(
                    "External classification semantic coverage count is incomplete");
        }
    }

    private String mergeUpperBound(
            Batch<ExpectedFact> expected,
            Batch<TestSuiteStabilityObservationExternalArchiveInventoryItem> observed) {
        String localLast = expected.lastObjectId();
        String remoteLast = observed.lastObjectId();
        if (expected.hasMore() && observed.hasMore()) {
            return localLast.compareTo(remoteLast) <= 0 ? localLast : remoteLast;
        }
        if (expected.hasMore()) {
            return localLast;
        }
        if (observed.hasMore()) {
            return remoteLast;
        }
        if (localLast.isEmpty()) {
            return remoteLast;
        }
        if (remoteLast.isEmpty()) {
            return localLast;
        }
        return localLast.compareTo(remoteLast) >= 0 ? localLast : remoteLast;
    }

    private SnapshotReplay replayExpectedSnapshot(String comparisonId, String authorityId) {
        long[] count = {0};
        String[] root = {EMPTY_EXPECTED_ROOT};
        String[] previous = {""};
        jdbc.query("""
                SELECT authority_id, object_id, trust_domain, archive_set_id, failure_domain,
                       item_fingerprint, object_commitment, retirement_id,
                       retirement_fingerprint, segment_id, segment_fingerprint,
                       retention_policy_fingerprint, retain_until, stored_at
                FROM rg_test_suite_stability_observation_external_expected_snapshots
                WHERE comparison_id = ?
                ORDER BY object_id
                """, (RowCallbackHandler) result -> {
            ExpectedFact fact = expectedFact(result, 0);
            if (previous[0].compareTo(fact.item().objectId()) >= 0
                    || !fact.authorityId().equals(authorityId)) {
                throw new IllegalStateException("Frozen external expected snapshot is corrupt");
            }
            requireCanonical(fact.item(), "expected inventory item");
            root[0] = ExternalArchiveComparisonStateIntegrity.appendExpectedRoot(
                    objectMapper, root[0], fact.item().itemFingerprint(),
                    fact.topologyFingerprint());
            count[0] = increment(count[0], "expected snapshot object count");
            previous[0] = fact.item().objectId();
        }, comparisonId);
        return new SnapshotReplay(count[0], root[0]);
    }

    private void verifyRemoteSnapshot(StoredCycle cycle) {
        long[] count = {0};
        String[] root = {
                TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.EMPTY_ROOT};
        String[] previous = {""};
        jdbc.query("""
                SELECT cycle_id, object_id, page_sequence, item_fingerprint,
                       object_commitment, retirement_id,
                       retirement_fingerprint, segment_id, segment_fingerprint,
                       retention_policy_fingerprint, retain_until, stored_at, committed_at,
                       record_fingerprint
                FROM rg_test_suite_stability_observation_external_inventory_items
                WHERE cycle_id = ?
                ORDER BY object_id
                """, (RowCallbackHandler) result -> {
            TestSuiteStabilityObservationExternalArchiveInventoryItem item =
                    storedInventoryItem(result, "", result.getString("object_id"));
            if (previous[0].compareTo(item.objectId()) >= 0) {
                throw new IllegalStateException("Completed external inventory order is corrupt");
            }
            requireCanonical(item, "remote inventory item");
            root[0] = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.append(
                    objectMapper, root[0], item);
            count[0] = increment(count[0], "remote snapshot object count");
            previous[0] = item.objectId();
        }, cycle.cycleId());
        if (count[0] != cycle.objectCount() || !root[0].equals(cycle.root())) {
            throw new IllegalStateException("Completed external inventory replay failed");
        }
    }

    private ClassificationReplay replayClassifications(String comparisonId) {
        long[] count = {0};
        String[] root = {EMPTY_CLASSIFICATION_ROOT};
        String[] previous = {""};
        OutcomeCounts[] counts = {OutcomeCounts.empty()};
        jdbc.query("""
                SELECT comparison_id, cycle_id, authority_id, object_id, page_sequence, outcome,
                       expected_item_fingerprint, observed_item_fingerprint,
                       expected_object_commitment, observed_object_commitment,
                       expected_topology_fingerprint, observed_topology_fingerprint,
                       expected_retain_until, observed_retain_until,
                       classification_fingerprint, committed_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ?
                ORDER BY object_id
                """, (RowCallbackHandler) result -> {
            StoredClassificationRow stored = storedClassificationRow(result, 0);
            stored.verify(objectMapper);
            Classification row = stored.classification();
            if (previous[0].compareTo(row.objectId()) >= 0
                    || !row.fingerprintVerified(objectMapper)) {
                throw new IllegalStateException("Stored external classification is corrupt");
            }
            root[0] = appendClassificationRoot(root[0], row);
            count[0] = increment(count[0], "classification replay count");
            counts[0] = counts[0].increment(row.outcome());
            previous[0] = row.objectId();
        }, comparisonId);
        return new ClassificationReplay(count[0], root[0], counts[0]);
    }

    private void requireExactUnionCoverage(StoredComparison comparison) {
        Long missing = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT object_id
                    FROM rg_test_suite_stability_observation_external_expected_snapshots
                    WHERE comparison_id = ?
                    UNION
                    SELECT object_id
                    FROM rg_test_suite_stability_observation_external_inventory_items
                    WHERE cycle_id = ?
                ) source
                LEFT JOIN rg_test_suite_stability_observation_external_classifications classified
                  ON classified.comparison_id = ?
                 AND classified.object_id = source.object_id
                WHERE classified.object_id IS NULL
                """, Long.class, comparison.comparisonId(), comparison.cycleId(),
                comparison.comparisonId());
        Long extra = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_classifications classified
                WHERE classified.comparison_id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM rg_test_suite_stability_observation_external_expected_snapshots expected
                      WHERE expected.comparison_id = ?
                        AND expected.object_id = classified.object_id
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM rg_test_suite_stability_observation_external_inventory_items observed
                      WHERE observed.cycle_id = ?
                        AND observed.object_id = classified.object_id
                  )
                """, Long.class, comparison.comparisonId(), comparison.comparisonId(),
                comparison.cycleId());
        if (missing == null || extra == null || missing != 0 || extra != 0) {
            throw new IllegalStateException("External classification union coverage is incomplete");
        }
    }

    private boolean hasExpectedAfter(String comparisonId, String objectId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_expected_snapshots
                WHERE comparison_id = ? AND object_id > ?
                """, Integer.class, comparisonId, objectId);
        return count != null && count > 0;
    }

    private boolean hasObservedAfter(String cycleId, String objectId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_inventory_items
                WHERE cycle_id = ? AND object_id > ?
                """, Integer.class, cycleId, objectId);
        return count != null && count > 0;
    }

    private void updateComparison(StoredComparison expected, StoredComparison successor) {
        StoredComparison withFingerprint = successor.withRecordFingerprint(
                successor.fingerprint(objectMapper));
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_observation_external_comparisons
                SET comparison_status = ?, next_after_object_id = ?, next_page_sequence = ?,
                    classified_object_count = ?, matched_count = ?, missing_remote_count = ?,
                    unexpected_remote_count = ?, material_conflict_count = ?,
                    retention_shortened_count = ?, unknown_count = ?, classification_root = ?,
                    revision = ?, completed_at = ?, updated_at = ?, record_fingerprint = ?
                WHERE comparison_id = ? AND revision = ? AND record_fingerprint = ?
                """, withFingerprint.status(), withFingerprint.nextAfterObjectId(),
                withFingerprint.nextPageSequence(), withFingerprint.classifiedObjectCount(),
                withFingerprint.matchedCount(), withFingerprint.missingRemoteCount(),
                withFingerprint.unexpectedRemoteCount(),
                withFingerprint.materialConflictCount(),
                withFingerprint.retentionShortenedCount(), withFingerprint.unknownCount(),
                withFingerprint.classificationRoot(), withFingerprint.revision(),
                timestamp(withFingerprint.completedAt()), Timestamp.from(withFingerprint.updatedAt()),
                withFingerprint.recordFingerprint(), expected.comparisonId(), expected.revision(),
                expected.recordFingerprint());
        if (updated != 1) {
            throw new IllegalStateException("External inventory comparison state fence failed");
        }
    }

    private void initializeComparisonAuthority(String authorityId, Instant now) {
        Integer known = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_inventory_authorities
                WHERE authority_id = ?
                """, Integer.class, authorityId);
        if (known == null || known != 1) {
            throw new IllegalArgumentException("External inventory authority is not initialized");
        }
        StoredComparisonAuthority initial = fingerprintedAuthority(
                new StoredComparisonAuthority(authorityId, "", "", 0, now, ""));
        try {
            jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_comparison_authorities (
                        authority_id, active_comparison_id, last_completed_comparison_id,
                        revision, updated_at, record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, initial.sqlArguments());
        } catch (DuplicateKeyException ignored) {
            // Another replica already initialized the comparison authority row.
        }
    }

    private void activateComparison(
            StoredComparisonAuthority state,
            String comparisonId,
            Instant now) {
        updateComparisonAuthority(state, fingerprintedAuthority(new StoredComparisonAuthority(
                state.authorityId(), requiredUuid(comparisonId, "comparison ID"),
                state.lastCompletedComparisonId(),
                increment(state.revision(), "comparison authority revision"), now, "")));
    }

    private StoredComparisonAuthority lockComparisonAuthority(String authorityId) {
        List<StoredComparisonAuthority> rows = jdbc.query("""
                SELECT authority_id, active_comparison_id, last_completed_comparison_id,
                       revision, updated_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_comparison_authorities
                WHERE authority_id = ?
                FOR UPDATE
                """, (result, row) -> new StoredComparisonAuthority(
                result.getString("authority_id"), result.getString("active_comparison_id"),
                result.getString("last_completed_comparison_id"), result.getLong("revision"),
                instant(result, "updated_at"), result.getString("record_fingerprint")),
                authorityId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External comparison authority state is missing");
        }
        rows.getFirst().validate(objectMapper);
        return rows.getFirst();
    }

    private StoredComparisonAuthority fingerprintedAuthority(StoredComparisonAuthority state) {
        state.validateShape();
        return state.withFingerprint(
                ExternalArchiveComparisonStateIntegrity.authorityFingerprint(objectMapper,
                        state.authorityId(), state.activeComparisonId(),
                        state.lastCompletedComparisonId(), state.revision(), state.updatedAt()));
    }

    private void updateComparisonAuthority(
            StoredComparisonAuthority expected,
            StoredComparisonAuthority successor) {
        expected.validate(objectMapper);
        successor.validate(objectMapper);
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_observation_external_comparison_authorities
                SET active_comparison_id = ?, last_completed_comparison_id = ?,
                    revision = ?, updated_at = ?, record_fingerprint = ?
                WHERE authority_id = ? AND revision = ? AND record_fingerprint = ?
                """, successor.updateArguments(expected));
        if (updated != 1) {
            throw new IllegalStateException(
                    "External inventory comparison authority fence was rejected");
        }
    }

    private StoredInventoryAuthority lockInventoryAuthority(String authorityId) {
        List<StoredInventoryAuthority> rows = jdbc.query("""
                SELECT authority_id, lease_owner, lease_token, lease_epoch, lease_until,
                       revision, active_cycle_id, last_completed_cycle_id,
                       last_success_at, updated_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_inventory_authorities
                WHERE authority_id = ?
                FOR UPDATE
                """, this::storedInventoryAuthority, authorityId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External inventory authority state is missing");
        }
        rows.getFirst().validate(objectMapper);
        return rows.getFirst();
    }

    private StoredCycle lockCompletedCycle(String cycleId, String authorityId) {
        List<StoredCycle> rows = jdbc.query("""
                SELECT cycle_id, authority_id, cycle_status, trust_domain, archive_set_id,
                       failure_domain, snapshot_id, snapshot_at, snapshot_object_count,
                       snapshot_root, next_after_object_id, next_page_sequence,
                       accumulated_object_count, accumulated_root, last_object_id, revision,
                       started_at, completed_at, updated_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ?
                FOR UPDATE
                """, this::storedCycle, cycleId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Completed external inventory cycle is missing");
        }
        StoredCycle cycle = rows.getFirst();
        cycle.validate(objectMapper, authorityId);
        return cycle;
    }

    private StoredComparison lockComparison(String comparisonId) {
        List<StoredComparison> rows = jdbc.query(comparisonSelect() + " WHERE comparison_id = ? FOR UPDATE",
                this::storedComparison, comparisonId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External inventory comparison is missing");
        }
        return rows.getFirst();
    }

    private StoredComparison readComparison(String comparisonId) {
        List<StoredComparison> rows = jdbc.query(comparisonSelect() + " WHERE comparison_id = ?",
                this::storedComparison, comparisonId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External inventory comparison is missing");
        }
        return rows.getFirst();
    }

    private StoredComparison findComparisonByCycle(String cycleId) {
        List<StoredComparison> rows = jdbc.query(comparisonSelect() + " WHERE cycle_id = ? FOR UPDATE",
                this::storedComparison, cycleId);
        if (rows.size() > 1) {
            throw new IllegalStateException("External inventory cycle has duplicate comparisons");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String comparisonSelect() {
        return """
                SELECT comparison_id, cycle_id, authority_id, comparison_status,
                       trust_domain, archive_set_id, failure_domain, remote_snapshot_id,
                       remote_object_count, remote_root, expected_object_count, expected_root,
                       next_after_object_id, next_page_sequence, classified_object_count,
                       matched_count, missing_remote_count, unexpected_remote_count,
                       material_conflict_count, retention_shortened_count, unknown_count,
                       classification_root, revision, started_at, completed_at, updated_at,
                       record_fingerprint
                FROM rg_test_suite_stability_observation_external_comparisons
                """;
    }

    private StoredComparison storedComparison(ResultSet result, int row) throws SQLException {
        StoredComparison comparison = new StoredComparison(
                result.getString("comparison_id"), result.getString("cycle_id"),
                result.getString("authority_id"), result.getString("comparison_status"),
                result.getString("trust_domain"), result.getString("archive_set_id"),
                result.getString("failure_domain"), result.getString("remote_snapshot_id"),
                result.getLong("remote_object_count"), result.getString("remote_root"),
                result.getLong("expected_object_count"), result.getString("expected_root"),
                result.getString("next_after_object_id"), result.getLong("next_page_sequence"),
                result.getLong("classified_object_count"), result.getLong("matched_count"),
                result.getLong("missing_remote_count"), result.getLong("unexpected_remote_count"),
                result.getLong("material_conflict_count"),
                result.getLong("retention_shortened_count"), result.getLong("unknown_count"),
                result.getString("classification_root"), result.getLong("revision"),
                instant(result, "started_at"), nullableInstant(result, "completed_at"),
                instant(result, "updated_at"), result.getString("record_fingerprint"));
        comparison.validate(objectMapper);
        return comparison;
    }

    private StoredCycle storedCycle(ResultSet result, int row) throws SQLException {
        return new StoredCycle(result.getString("cycle_id"), result.getString("authority_id"),
                result.getString("cycle_status"), result.getString("trust_domain"),
                result.getString("archive_set_id"), result.getString("failure_domain"),
                result.getString("snapshot_id"), instant(result, "snapshot_at"),
                result.getLong("snapshot_object_count"), result.getString("snapshot_root"),
                result.getString("next_after_object_id"), result.getLong("next_page_sequence"),
                result.getLong("accumulated_object_count"), result.getString("accumulated_root"),
                result.getString("last_object_id"), result.getLong("revision"),
                instant(result, "started_at"), instant(result, "completed_at"),
                instant(result, "updated_at"), result.getString("record_fingerprint"));
    }

    private StoredInventoryAuthority storedInventoryAuthority(
            ResultSet result,
            int row) throws SQLException {
        return new StoredInventoryAuthority(result.getString("authority_id"),
                result.getString("lease_owner"), result.getString("lease_token"),
                result.getLong("lease_epoch"), instant(result, "lease_until"),
                result.getLong("revision"), result.getString("active_cycle_id"),
                result.getString("last_completed_cycle_id"),
                nullableInstant(result, "last_success_at"), instant(result, "updated_at"),
                result.getString("record_fingerprint"));
    }

    private ExpectedFact expectedFact(ResultSet result, int row) throws SQLException {
        TestSuiteStabilityObservationExternalArchiveInventoryItem item = inventoryItem(result, row,
                result.getString("item_fingerprint"));
        String authorityId = result.getString("authority_id");
        ExpectedFact fact = new ExpectedFact(authorityId, result.getString("trust_domain"),
                result.getString("archive_set_id"), result.getString("failure_domain"), item,
                topologyFingerprint(result.getString("trust_domain"),
                        result.getString("archive_set_id"), authorityId,
                        result.getString("failure_domain")));
        return fact;
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryItem inventoryItem(
            ResultSet result,
            int row) throws SQLException {
        return inventoryItem(result, row, result.getString("item_fingerprint"));
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryItem inventoryItem(
            ResultSet result,
            int row,
            String itemFingerprint) throws SQLException {
        return new TestSuiteStabilityObservationExternalArchiveInventoryItem(
                TestSuiteStabilityObservationExternalArchiveInventoryItem.SCHEMA_VERSION,
                itemFingerprint, result.getString("object_id"),
                result.getString("object_commitment"), result.getString("retirement_id"),
                result.getString("retirement_fingerprint"), result.getString("segment_id"),
                result.getString("segment_fingerprint"),
                result.getString("retention_policy_fingerprint"),
                instant(result, "retain_until"), instant(result, "stored_at"));
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryItem inventoryItem(
            ResultSet result,
            String prefix,
            String objectId) throws SQLException {
        return new TestSuiteStabilityObservationExternalArchiveInventoryItem(
                TestSuiteStabilityObservationExternalArchiveInventoryItem.SCHEMA_VERSION,
                result.getString(prefix + "item_fingerprint"), objectId,
                result.getString(prefix + "object_commitment"),
                result.getString(prefix + "retirement_id"),
                result.getString(prefix + "retirement_fingerprint"),
                result.getString(prefix + "segment_id"),
                result.getString(prefix + "segment_fingerprint"),
                result.getString(prefix + "retention_policy_fingerprint"),
                instant(result, prefix + "retain_until"), instant(result, prefix + "stored_at"));
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryItem storedInventoryItem(
            ResultSet result,
            String prefix,
            String objectId) throws SQLException {
        TestSuiteStabilityObservationExternalArchiveInventoryItem item =
                inventoryItem(result, prefix, objectId);
        String expected = ExternalArchiveInventoryStagingIntegrity.itemFingerprint(
                objectMapper, result.getString(prefix + "cycle_id"),
                result.getLong(prefix + "page_sequence"), item,
                instant(result, prefix + "committed_at"));
        if (!item.fingerprintVerified(objectMapper)
                || !expected.equals(result.getString(prefix + "record_fingerprint"))) {
            throw new IllegalStateException(
                    "External inventory staged item record fingerprint is corrupt");
        }
        return item;
    }

    private Classification classification(ResultSet result, int row) throws SQLException {
        return new Classification(result.getString("comparison_id"),
                result.getString("cycle_id"), result.getString("authority_id"),
                result.getString("object_id"), Outcome.valueOf(result.getString("outcome")),
                result.getString("expected_item_fingerprint"),
                result.getString("observed_item_fingerprint"),
                result.getString("expected_object_commitment"),
                result.getString("observed_object_commitment"),
                result.getString("expected_topology_fingerprint"),
                result.getString("observed_topology_fingerprint"),
                nullableInstant(result, "expected_retain_until"),
                nullableInstant(result, "observed_retain_until"),
                result.getString("classification_fingerprint"));
    }

    private StoredClassificationRow storedClassificationRow(ResultSet result, int row)
            throws SQLException {
        return new StoredClassificationRow(classification(result, row),
                result.getLong("page_sequence"), instant(result, "committed_at"),
                result.getString("record_fingerprint"));
    }

    private Classification classification(
            ResultSet result,
            String prefix,
            String objectId) throws SQLException {
        return new Classification(result.getString(prefix + "comparison_id"),
                result.getString(prefix + "cycle_id"),
                result.getString(prefix + "authority_id"), objectId,
                Outcome.valueOf(result.getString(prefix + "outcome")),
                result.getString(prefix + "expected_item_fingerprint"),
                result.getString(prefix + "observed_item_fingerprint"),
                result.getString(prefix + "expected_object_commitment"),
                result.getString(prefix + "observed_object_commitment"),
                result.getString(prefix + "expected_topology_fingerprint"),
                result.getString(prefix + "observed_topology_fingerprint"),
                nullableInstant(result, prefix + "expected_retain_until"),
                nullableInstant(result, prefix + "observed_retain_until"),
                result.getString(prefix + "classification_fingerprint"));
    }

    private String appendClassificationRoot(String root, Classification classification) {
        return ExternalArchiveComparisonStateIntegrity.appendClassificationRoot(
                objectMapper, root, classification.classificationFingerprint());
    }

    private String topologyFingerprint(
            String trustDomain,
            String archiveSetId,
            String authorityId,
            String failureDomain) {
        return ExternalArchiveComparisonStateIntegrity.topologyFingerprint(objectMapper,
                requiredIdentifier(trustDomain, "trust domain"),
                requiredIdentifier(archiveSetId, "archive set"),
                requiredIdentifier(authorityId, "inventory authority"),
                requiredIdentifier(failureDomain, "failure domain"));
    }

    private void requireCanonical(
            TestSuiteStabilityObservationExternalArchiveInventoryItem item,
            String name) {
        if (!item.fingerprintVerified(objectMapper)) {
            throw new IllegalStateException("Stored " + name + " is corrupt");
        }
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Database clock returned no timestamp");
        }
        return value.toInstant();
    }

    private Instant monotonicDatabaseTime(Instant persistedLowerBound) {
        Instant transactionTime = databaseNow();
        return transactionTime.isAfter(persistedLowerBound)
                ? transactionTime : persistedLowerBound.plusNanos(1_000);
    }

    private static PlatformTransactionManager localTransactionManager(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        if (jdbc.getDataSource() == null) {
            throw new IllegalArgumentException("External classification JDBC requires a datasource");
        }
        return new DataSourceTransactionManager(jdbc.getDataSource());
    }

    private static String fingerprint(
            TestSuiteStabilityObservationExternalArchiveInventoryItem item) {
        return item == null ? "" : item.itemFingerprint();
    }

    private static String commitment(
            TestSuiteStabilityObservationExternalArchiveInventoryItem item) {
        return item == null ? "" : item.objectCommitment();
    }

    private static Instant retainUntil(
            TestSuiteStabilityObservationExternalArchiveInventoryItem item) {
        return item == null ? null : item.retainUntil();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        if (value == null) {
            throw new IllegalStateException("Required timestamp column is null: " + column);
        }
        return value.toInstant();
    }

    private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String requiredIdentifier(String value, String name) {
        String exact = normalized(value);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException("Complete " + name + " is required");
        }
        return exact;
    }

    private static String requiredUuid(String value, String name) {
        String exact = normalized(value);
        if (!isUuid(exact)) {
            throw new IllegalArgumentException("Valid " + name + " is required");
        }
        return exact;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static long increment(long value, String name) {
        return add(value, 1, name);
    }

    private static long add(long value, long delta, String name) {
        try {
            return Math.addExact(value, delta);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(name + " overflow", overflow);
        }
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    /**
     * Identity-free bounded comparison state consumed by readiness policy.
     *
     * @param observedAt database observation time
     * @param initialized whether the authority has entered the comparison stage
     * @param activeComparison whether one comparison is partially committed
     * @param nextPageSequence next active comparison page
     * @param classifiedObjectCount active comparison outcomes already committed
     * @param findingObjectCount active non-matching outcomes already committed
     * @param activeStartedAt active comparison start, or absent
     * @param activeUpdatedAt latest active comparison progress, or absent
     * @param lastCompletedAt latest verified completed comparison, or absent
     */
    public record OperationalSnapshot(
            Instant observedAt,
            boolean initialized,
            boolean activeComparison,
            long nextPageSequence,
            long classifiedObjectCount,
            long findingObjectCount,
            Instant activeStartedAt,
            Instant activeUpdatedAt,
            Instant lastCompletedAt) {
        /** Validates closed active/inactive timestamp and counter relationships. */
        public OperationalSnapshot {
            if (observedAt == null || nextPageSequence < 0 || classifiedObjectCount < 0
                    || findingObjectCount < 0 || findingObjectCount > classifiedObjectCount
                    || !initialized && (activeComparison || lastCompletedAt != null)
                    || activeComparison != (activeStartedAt != null)
                    || activeComparison != (activeUpdatedAt != null)
                    || !activeComparison && (nextPageSequence != 0
                    || classifiedObjectCount != 0 || findingObjectCount != 0)
                    || activeStartedAt != null && activeUpdatedAt.isBefore(activeStartedAt)
                    || activeUpdatedAt != null && activeUpdatedAt.isAfter(observedAt)
                    || lastCompletedAt != null && lastCompletedAt.isAfter(observedAt)) {
                throw new IllegalArgumentException(
                        "Invalid external comparison operational snapshot");
            }
        }

        private static OperationalSnapshot uninitialized(Instant observedAt) {
            return new OperationalSnapshot(observedAt, false, false,
                    0, 0, 0, null, null, null);
        }
    }

    /** Bounded ordered-merge settings. */
    public record Settings(int maximumSourceItemsPerSide) {
        /** Enforces 1..500 source rows per side and at most twice that many union outcomes. */
        public Settings {
            if (maximumSourceItemsPerSide < 1 || maximumSourceItemsPerSide > 500) {
                throw new IllegalArgumentException(
                        "External comparison source page must be 1 through 500");
            }
        }
    }

    /** Closed outcome vocabulary for every object in the expected/observed union. */
    public enum Outcome {
        /** Expected and observed topology and canonical item are identical. */
        MATCHED,
        /** A locally acknowledged object is absent from the complete remote snapshot. */
        MISSING_REMOTE,
        /** A remote object has no locally acknowledged expected object. */
        UNEXPECTED_REMOTE,
        /** Both sides exist under the same topology but immutable material differs. */
        MATERIAL_CONFLICT,
        /** Both sides exist but the authority reports an earlier retention deadline. */
        RETENTION_SHORTENED,
        /** Both sides exist but topology drift prevents a safe material comparison. */
        UNKNOWN
    }

    /** Progress state returned by one bounded comparison invocation. */
    public enum ComparisonStatus {
        /** A bounded non-terminal union page committed. */
        STAGED,
        /** The terminal replay and union-coverage gate committed. */
        COMPLETED,
        /** The latest completed remote cycle already has a completed comparison. */
        CURRENT
    }

    /**
     * Payload-free progress from one comparison page.
     *
     * @param status bounded comparison result
     * @param authorityId exact inventory authority
     * @param comparisonId durable comparison identity
     * @param cycleId pinned completed remote cycle
     * @param pageSequence zero-based page attempted by this call
     * @param pageObjectCount union outcomes committed by this call
     * @param classifiedObjectCount total durable union outcomes
     * @param findingObjectCount total non-matching outcomes
     */
    public record ComparisonPage(
            ComparisonStatus status,
            String authorityId,
            String comparisonId,
            String cycleId,
            long pageSequence,
            int pageObjectCount,
            long classifiedObjectCount,
            long findingObjectCount) {
        /** Rejects malformed progress results before callers mistake them for evidence. */
        public ComparisonPage {
            Objects.requireNonNull(status, "status");
            authorityId = requiredIdentifier(authorityId, "inventory authority");
            comparisonId = requiredUuid(comparisonId, "comparison ID");
            cycleId = requiredUuid(cycleId, "cycle ID");
            if (pageSequence < 0 || pageObjectCount < 0 || pageObjectCount > 1000
                    || classifiedObjectCount < pageObjectCount || findingObjectCount < 0
                    || findingObjectCount > classifiedObjectCount
                    || (status == ComparisonStatus.STAGED && pageObjectCount == 0)
                    || (status == ComparisonStatus.CURRENT && pageObjectCount != 0)) {
                throw new IllegalArgumentException("Invalid external comparison page result");
            }
        }

        private static ComparisonPage current(StoredComparison comparison) {
            return new ComparisonPage(ComparisonStatus.CURRENT, comparison.authorityId(),
                    comparison.comparisonId(), comparison.cycleId(),
                    comparison.nextPageSequence(), 0, comparison.classifiedObjectCount(),
                    comparison.findingObjectCount());
        }
    }

    /**
     * Self-verifying payload-free classification evidence for one object.
     *
     * @param comparisonId durable comparison identity
     * @param cycleId pinned remote cycle; populated for newly produced values
     * @param authorityId inventory authority; populated for newly produced values
     * @param objectId exact object identity
     * @param outcome closed classification
     * @param expectedItemFingerprint local canonical item fingerprint, or empty when absent
     * @param observedItemFingerprint remote canonical item fingerprint, or empty when absent
     * @param expectedObjectCommitment local retention-bearing commitment, or empty when absent
     * @param observedObjectCommitment remote retention-bearing commitment, or empty when absent
     * @param expectedTopologyFingerprint local archive topology, or empty when absent
     * @param observedTopologyFingerprint pinned remote archive topology
     * @param expectedRetainUntil local retention deadline, or null when absent
     * @param observedRetainUntil remote retention deadline, or null when absent
     * @param classificationFingerprint canonical classification-material fingerprint
     */
    public record Classification(
            String comparisonId,
            String cycleId,
            String authorityId,
            String objectId,
            Outcome outcome,
            String expectedItemFingerprint,
            String observedItemFingerprint,
            String expectedObjectCommitment,
            String observedObjectCommitment,
            String expectedTopologyFingerprint,
            String observedTopologyFingerprint,
            Instant expectedRetainUntil,
            Instant observedRetainUntil,
            String classificationFingerprint) {
        /** Validates the closed presence rules and fingerprint vocabulary. */
        public Classification {
            comparisonId = requiredUuid(comparisonId, "comparison ID");
            cycleId = normalized(cycleId);
            authorityId = normalized(authorityId);
            objectId = normalized(objectId);
            Objects.requireNonNull(outcome, "outcome");
            expectedItemFingerprint = normalized(expectedItemFingerprint);
            observedItemFingerprint = normalized(observedItemFingerprint);
            expectedObjectCommitment = normalized(expectedObjectCommitment);
            observedObjectCommitment = normalized(observedObjectCommitment);
            expectedTopologyFingerprint = normalized(expectedTopologyFingerprint);
            observedTopologyFingerprint = normalized(observedTopologyFingerprint);
            classificationFingerprint = normalized(classificationFingerprint);
            boolean expected = !expectedItemFingerprint.isEmpty();
            boolean observed = !observedItemFingerprint.isEmpty();
            boolean expectedShape = expected
                    ? FINGERPRINT.matcher(expectedItemFingerprint).matches()
                    && FINGERPRINT.matcher(expectedObjectCommitment).matches()
                    && FINGERPRINT.matcher(expectedTopologyFingerprint).matches()
                    && expectedRetainUntil != null
                    : expectedObjectCommitment.isEmpty()
                    && expectedTopologyFingerprint.isEmpty() && expectedRetainUntil == null;
            boolean observedShape = observed
                    ? FINGERPRINT.matcher(observedItemFingerprint).matches()
                    && FINGERPRINT.matcher(observedObjectCommitment).matches()
                    && observedRetainUntil != null
                    : observedObjectCommitment.isEmpty() && observedRetainUntil == null;
            boolean presence = switch (outcome) {
                case MATCHED, MATERIAL_CONFLICT, RETENTION_SHORTENED, UNKNOWN ->
                        expected && observed;
                case MISSING_REMOTE -> expected && !observed;
                case UNEXPECTED_REMOTE -> !expected && observed;
            };
            if ((!cycleId.isEmpty() && !isUuid(cycleId))
                    || (!authorityId.isEmpty() && !IDENTIFIER.matcher(authorityId).matches())
                    || !OBJECT_ID.matcher(objectId).matches() || !expectedShape || !observedShape
                    || !presence || !FINGERPRINT.matcher(observedTopologyFingerprint).matches()
                    || (!classificationFingerprint.isEmpty()
                    && !FINGERPRINT.matcher(classificationFingerprint).matches())) {
                throw new IllegalArgumentException("Invalid external inventory classification");
            }
        }

        /** @return whether the claimed fingerprint covers every classification field */
        public boolean fingerprintVerified(ObjectMapper objectMapper) {
            return !cycleId.isEmpty() && !authorityId.isEmpty()
                    && classificationFingerprint.equals(ProtocolFingerprint.of(
                    Objects.requireNonNull(objectMapper, "objectMapper"), fingerprintMaterial()));
        }

        private Classification withFingerprint(String fingerprint) {
            return new Classification(comparisonId, cycleId, authorityId, objectId, outcome,
                    expectedItemFingerprint, observedItemFingerprint, expectedObjectCommitment,
                    observedObjectCommitment, expectedTopologyFingerprint,
                    observedTopologyFingerprint, expectedRetainUntil, observedRetainUntil,
                    fingerprint);
        }

        private ClassificationMaterial fingerprintMaterial() {
            return new ClassificationMaterial(ClassificationMaterial.SCHEMA_VERSION,
                    comparisonId, cycleId, authorityId, objectId, outcome,
                    expectedItemFingerprint, observedItemFingerprint, expectedObjectCommitment,
                    observedObjectCommitment, expectedTopologyFingerprint,
                    observedTopologyFingerprint, expectedRetainUntil, observedRetainUntil);
        }
    }

    private record ClassificationMaterial(
            String schemaVersion,
            String comparisonId,
            String cycleId,
            String authorityId,
            String objectId,
            Outcome outcome,
            String expectedItemFingerprint,
            String observedItemFingerprint,
            String expectedObjectCommitment,
            String observedObjectCommitment,
            String expectedTopologyFingerprint,
            String observedTopologyFingerprint,
            Instant expectedRetainUntil,
            Instant observedRetainUntil) {
        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveClassification.v1";
    }

    private record StoredComparisonAuthority(
            String authorityId,
            String activeComparisonId,
            String lastCompletedComparisonId,
            long revision,
            Instant updatedAt,
            String recordFingerprint) {
        private void validateShape() {
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || (!activeComparisonId.isEmpty() && !isUuid(activeComparisonId))
                    || (!lastCompletedComparisonId.isEmpty()
                    && !isUuid(lastCompletedComparisonId))
                    || (!activeComparisonId.isEmpty()
                    && activeComparisonId.equals(lastCompletedComparisonId))
                    || revision < 0 || updatedAt == null) {
                throw new IllegalStateException("External comparison authority state is corrupt");
            }
        }

        private void validate(ObjectMapper objectMapper) {
            validateShape();
            if (!FINGERPRINT.matcher(normalized(recordFingerprint)).matches()
                    || !recordFingerprint.equals(
                    ExternalArchiveComparisonStateIntegrity.authorityFingerprint(objectMapper,
                            authorityId, activeComparisonId, lastCompletedComparisonId,
                            revision, updatedAt))) {
                throw new IllegalStateException(
                        "External comparison authority record fingerprint is corrupt");
            }
        }

        private StoredComparisonAuthority withFingerprint(String fingerprint) {
            return new StoredComparisonAuthority(authorityId, activeComparisonId,
                    lastCompletedComparisonId, revision, updatedAt, fingerprint);
        }

        private Object[] sqlArguments() {
            return new Object[]{authorityId, activeComparisonId, lastCompletedComparisonId,
                    revision, Timestamp.from(updatedAt), recordFingerprint};
        }

        private Object[] updateArguments(StoredComparisonAuthority expected) {
            return new Object[]{activeComparisonId, lastCompletedComparisonId, revision,
                    Timestamp.from(updatedAt), recordFingerprint, expected.authorityId,
                    expected.revision, expected.recordFingerprint};
        }
    }

    private record StoredClassificationRow(
            Classification classification,
            long pageSequence,
            Instant committedAt,
            String recordFingerprint) {
        private String expectedFingerprint(ObjectMapper objectMapper) {
            if (classification == null || !classification.fingerprintVerified(objectMapper)
                    || pageSequence < 0 || committedAt == null) {
                throw new IllegalStateException(
                        "External inventory classification row shape is corrupt");
            }
            return ExternalArchiveComparisonStateIntegrity.classificationRowFingerprint(
                    objectMapper, classification, pageSequence, committedAt);
        }

        private void verify(ObjectMapper objectMapper) {
            if (!FINGERPRINT.matcher(normalized(recordFingerprint)).matches()
                    || !recordFingerprint.equals(expectedFingerprint(objectMapper))) {
                throw new IllegalStateException(
                        "External inventory classification row fingerprint is corrupt");
            }
        }
    }

    private record StoredInventoryAuthority(
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
        private void validate(ObjectMapper objectMapper) {
            String expected = ExternalArchiveInventoryStateIntegrity.authorityFingerprint(
                    objectMapper, authorityId, leaseOwner, leaseToken, leaseEpoch, leaseUntil,
                    revision, activeCycleId, lastCompletedCycleId, lastSuccessAt, updatedAt);
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || (leaseOwner.isEmpty() != leaseToken.isEmpty())
                    || leaseEpoch < 0 || leaseUntil == null || revision < 0
                    || !activeCycleId.isEmpty() || !isUuid(lastCompletedCycleId)
                    || lastSuccessAt == null || updatedAt == null
                    || updatedAt.isBefore(lastSuccessAt)
                    || !FINGERPRINT.matcher(recordFingerprint).matches()
                    || !recordFingerprint.equals(expected)) {
                throw new IllegalStateException(
                        "External inventory authority is corrupt or has no completed cycle to compare");
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
            long objectCount,
            String root,
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
        private void validate(ObjectMapper objectMapper, String expectedAuthority) {
            String expectedFingerprint = ExternalArchiveInventoryStateIntegrity.cycleFingerprint(
                    objectMapper, cycleId, authorityId, status, trustDomain, archiveSetId,
                    failureDomain, snapshotId, snapshotAt, objectCount, root, nextAfterObjectId,
                    nextPageSequence, accumulatedObjectCount, accumulatedRoot, lastObjectId,
                    revision, startedAt, completedAt, updatedAt);
            if (!isUuid(cycleId) || !authorityId.equals(expectedAuthority)
                    || !"COMPLETED".equals(status)
                    || !IDENTIFIER.matcher(trustDomain).matches()
                    || !IDENTIFIER.matcher(archiveSetId).matches()
                    || !IDENTIFIER.matcher(failureDomain).matches()
                    || !SNAPSHOT_ID.matcher(snapshotId).matches() || objectCount < 0
                    || !FINGERPRINT.matcher(root).matches()
                    || snapshotAt == null || !nextAfterObjectId.isEmpty()
                    || nextPageSequence < 1
                    || accumulatedObjectCount != objectCount || !accumulatedRoot.equals(root)
                    || (objectCount == 0) != lastObjectId.isEmpty()
                    || (!lastObjectId.isEmpty() && !OBJECT_ID.matcher(lastObjectId).matches())
                    || revision < 0 || startedAt == null || completedAt == null
                    || updatedAt == null || completedAt.isBefore(startedAt)
                    || updatedAt.isBefore(completedAt)
                    || !FINGERPRINT.matcher(recordFingerprint).matches()
                    || !recordFingerprint.equals(expectedFingerprint)) {
                throw new IllegalStateException("External inventory completed cycle is corrupt");
            }
        }

        private boolean matches(StoredComparison comparison) {
            return cycleId.equals(comparison.cycleId())
                    && authorityId.equals(comparison.authorityId())
                    && trustDomain.equals(comparison.trustDomain())
                    && archiveSetId.equals(comparison.archiveSetId())
                    && failureDomain.equals(comparison.failureDomain())
                    && snapshotId.equals(comparison.remoteSnapshotId())
                    && objectCount == comparison.remoteObjectCount()
                    && root.equals(comparison.remoteRoot());
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
        private static StoredComparison initial(
                String comparisonId,
                StoredCycle cycle,
                long expectedCount,
                String expectedRoot,
                Instant now,
                String recordFingerprint) {
            return new StoredComparison(comparisonId, cycle.cycleId(), cycle.authorityId(), "ACTIVE",
                    cycle.trustDomain(), cycle.archiveSetId(), cycle.failureDomain(),
                    cycle.snapshotId(), cycle.objectCount(), cycle.root(), expectedCount,
                    expectedRoot, "", 0, 0, 0, 0, 0, 0, 0, 0,
                    EMPTY_CLASSIFICATION_ROOT, 0, now, null, now, recordFingerprint);
        }

        private void validate(ObjectMapper objectMapper) {
            boolean knownStatus = "ACTIVE".equals(status) || "COMPLETED".equals(status);
            long sum = OutcomeCounts.sum(matchedCount, missingRemoteCount,
                    unexpectedRemoteCount, materialConflictCount,
                    retentionShortenedCount, unknownCount);
            if (!isUuid(comparisonId) || !isUuid(cycleId)
                    || !IDENTIFIER.matcher(authorityId).matches() || !knownStatus
                    || !IDENTIFIER.matcher(trustDomain).matches()
                    || !IDENTIFIER.matcher(archiveSetId).matches()
                    || !IDENTIFIER.matcher(failureDomain).matches()
                    || !SNAPSHOT_ID.matcher(remoteSnapshotId).matches()
                    || remoteObjectCount < 0 || !FINGERPRINT.matcher(remoteRoot).matches()
                    || expectedObjectCount < 0 || !FINGERPRINT.matcher(expectedRoot).matches()
                    || (!nextAfterObjectId.isEmpty()
                    && !OBJECT_ID.matcher(nextAfterObjectId).matches())
                    || nextPageSequence < 0 || classifiedObjectCount < 0
                    || sum != classifiedObjectCount
                    || !FINGERPRINT.matcher(classificationRoot).matches()
                    || revision < 0 || startedAt == null || updatedAt == null
                    || ("ACTIVE".equals(status) != (completedAt == null))
                    || updatedAt.isBefore(startedAt)
                    || (completedAt != null && (completedAt.isBefore(startedAt)
                    || updatedAt.isBefore(completedAt)))
                    || !FINGERPRINT.matcher(recordFingerprint).matches()
                    || !recordFingerprint.equals(fingerprint(objectMapper))) {
                throw new IllegalStateException("External inventory comparison state is corrupt");
            }
        }

        private void requireActiveFor(String expectedAuthority) {
            if (!"ACTIVE".equals(status) || !authorityId.equals(expectedAuthority)) {
                throw new IllegalStateException("External inventory comparison is not active");
            }
        }

        private void requireCompleted() {
            if (!"COMPLETED".equals(status)) {
                throw new IllegalStateException("External inventory comparison is not complete");
            }
        }

        private StoredComparison progressed(
                String cursor,
                long sequence,
                long classified,
                OutcomeCounts counts,
                String root,
                Instant now) {
            return new StoredComparison(comparisonId, cycleId, authorityId, status, trustDomain,
                    archiveSetId, failureDomain, remoteSnapshotId, remoteObjectCount, remoteRoot,
                    expectedObjectCount, expectedRoot, cursor, sequence, classified,
                    counts.matched(), counts.missingRemote(), counts.unexpectedRemote(),
                    counts.materialConflict(), counts.retentionShortened(), counts.unknown(), root,
                    increment(revision, "comparison revision"), startedAt, completedAt, now, "");
        }

        private StoredComparison completed(Instant now) {
            return new StoredComparison(comparisonId, cycleId, authorityId, "COMPLETED", trustDomain,
                    archiveSetId, failureDomain, remoteSnapshotId, remoteObjectCount, remoteRoot,
                    expectedObjectCount, expectedRoot, nextAfterObjectId, nextPageSequence,
                    classifiedObjectCount, matchedCount, missingRemoteCount, unexpectedRemoteCount,
                    materialConflictCount, retentionShortenedCount, unknownCount, classificationRoot,
                    increment(revision, "comparison completion revision"), startedAt, now, now, "");
        }

        private StoredComparison withRecordFingerprint(String fingerprint) {
            return new StoredComparison(comparisonId, cycleId, authorityId, status, trustDomain,
                    archiveSetId, failureDomain, remoteSnapshotId, remoteObjectCount, remoteRoot,
                    expectedObjectCount, expectedRoot, nextAfterObjectId, nextPageSequence,
                    classifiedObjectCount, matchedCount, missingRemoteCount, unexpectedRemoteCount,
                    materialConflictCount, retentionShortenedCount, unknownCount, classificationRoot,
                    revision, startedAt, completedAt, updatedAt, fingerprint);
        }

        private OutcomeCounts outcomeCounts() {
            return new OutcomeCounts(matchedCount, missingRemoteCount, unexpectedRemoteCount,
                    materialConflictCount, retentionShortenedCount, unknownCount);
        }

        private long findingObjectCount() {
            return classifiedObjectCount - matchedCount;
        }

        private String fingerprint(ObjectMapper objectMapper) {
            return ExternalArchiveComparisonStateIntegrity.comparisonFingerprint(objectMapper,
                    comparisonId, cycleId, authorityId, status, trustDomain, archiveSetId,
                    failureDomain, remoteSnapshotId, remoteObjectCount, remoteRoot,
                    expectedObjectCount, expectedRoot, nextAfterObjectId, nextPageSequence,
                    classifiedObjectCount, matchedCount, missingRemoteCount,
                    unexpectedRemoteCount, materialConflictCount, retentionShortenedCount,
                    unknownCount, classificationRoot, revision, startedAt, completedAt, updatedAt);
        }

        private Object[] sqlArguments() {
            return new Object[]{comparisonId, cycleId, authorityId, status, trustDomain, archiveSetId,
                    failureDomain, remoteSnapshotId, remoteObjectCount, remoteRoot,
                    expectedObjectCount, expectedRoot, nextAfterObjectId, nextPageSequence,
                    classifiedObjectCount, matchedCount, missingRemoteCount, unexpectedRemoteCount,
                    materialConflictCount, retentionShortenedCount, unknownCount, classificationRoot,
                    revision, Timestamp.from(startedAt), timestamp(completedAt),
                    Timestamp.from(updatedAt), recordFingerprint};
        }
    }

    private record ExpectedFact(
            String authorityId,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            TestSuiteStabilityObservationExternalArchiveInventoryItem item,
            String topologyFingerprint) {
        private ExpectedFact {
            authorityId = requiredIdentifier(authorityId, "inventory authority");
            trustDomain = requiredIdentifier(trustDomain, "trust domain");
            archiveSetId = requiredIdentifier(archiveSetId, "archive set");
            failureDomain = requiredIdentifier(failureDomain, "failure domain");
            Objects.requireNonNull(item, "item");
            if (!FINGERPRINT.matcher(topologyFingerprint).matches()) {
                throw new IllegalStateException("Expected inventory topology is corrupt");
            }
        }
    }

    private record SnapshotReplay(long objectCount, String root) {
    }

    private record ClassificationReplay(long objectCount, String root, OutcomeCounts counts) {
    }

    private record OutcomeCounts(
            long matched,
            long missingRemote,
            long unexpectedRemote,
            long materialConflict,
            long retentionShortened,
            long unknown) {
        private static OutcomeCounts empty() {
            return new OutcomeCounts(0, 0, 0, 0, 0, 0);
        }

        private OutcomeCounts increment(Outcome outcome) {
            return switch (outcome) {
                case MATCHED -> new OutcomeCounts(DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane.increment(matched, "matched count"),
                        missingRemote, unexpectedRemote, materialConflict, retentionShortened, unknown);
                case MISSING_REMOTE -> new OutcomeCounts(matched,
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane.increment(missingRemote, "missing remote count"), unexpectedRemote,
                        materialConflict, retentionShortened, unknown);
                case UNEXPECTED_REMOTE -> new OutcomeCounts(matched, missingRemote,
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane.increment(unexpectedRemote, "unexpected remote count"), materialConflict,
                        retentionShortened, unknown);
                case MATERIAL_CONFLICT -> new OutcomeCounts(matched, missingRemote, unexpectedRemote,
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane.increment(materialConflict, "material conflict count"), retentionShortened,
                        unknown);
                case RETENTION_SHORTENED -> new OutcomeCounts(matched, missingRemote,
                        unexpectedRemote, materialConflict,
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane.increment(retentionShortened, "retention shortened count"), unknown);
                case UNKNOWN -> new OutcomeCounts(matched, missingRemote, unexpectedRemote,
                        materialConflict, retentionShortened,
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane.increment(unknown, "unknown count"));
            };
        }

        private static long sum(long... values) {
            long result = 0;
            for (long value : values) {
                if (value < 0) {
                    return -1;
                }
                result = add(result, value, "classification outcome count");
            }
            return result;
        }
    }

    private record Batch<T>(List<T> items, boolean hasMore, String lastObjectId) {
        private static <T> Batch<T> of(List<T> source, int maximum) {
            boolean more = source.size() > maximum;
            List<T> items = List.copyOf(more ? source.subList(0, maximum) : source);
            String last = items.isEmpty() ? "" : objectId(items.getLast());
            return new Batch<>(items, more, last);
        }

        private static String objectId(Object value) {
            if (value instanceof ExpectedFact expected) {
                return expected.item().objectId();
            }
            return ((TestSuiteStabilityObservationExternalArchiveInventoryItem) value).objectId();
        }
    }
}
