package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryItem;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryPage;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryRequest;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationExternalArchiveInventoryIntegrity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlaneTest {
    private static final String AUTHORITY = "archive-a";
    private static final String TRUST_DOMAIN = "archive.example";
    private static final String ARCHIVE_SET = "archive-set-a";
    private static final String FAILURE_DOMAIN = "region-a";

    private ObjectMapper objectMapper;
    private TestRuntimeDatabase database;
    private TestSuiteStabilityObservationExternalArchiveInventoryAuthority historicalAuthority;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:external-source-retention-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 6));
        historicalAuthority = authority(
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification
                        .VERIFIED);
        new DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                historicalAuthority,
                new DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .Settings("collector", Duration.ofSeconds(5), 10)).init();
        new DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                new DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Settings(10)).init();
        new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .Settings(10)).init();
        new DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper, "finding-retention",
                Duration.ofSeconds(5)).init();
        retention("replica-a", historicalAuthority).init();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void retiresProcessedSourceInOneBoundedSegmentPerCallAndKeepsMarker() throws Exception {
        SourceFixture fixture = insertProcessedSource(2, Instant.now().minus(Duration.ofDays(3)));
        var control = retention("replica-a", historicalAuthority);
        var classifier = new
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                new DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Settings(10));
        assertThat(classifier.classifications(fixture.comparisonId(), "", 10)).hasSize(2);
        var first = control.retain(Duration.ofDays(1), Duration.ofDays(1), 1).result();
        assertThatThrownBy(() -> classifier.classifications(fixture.comparisonId(), "", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("being retired");
        var activeSnapshot = control.operationalSnapshot(
                Duration.ofDays(1), Duration.ofDays(1));
        assertThat(activeSnapshot.activeRetirement()).isTrue();
        assertThat(activeSnapshot.activeMarkerCount()).isEqualTo(1);
        assertThat(activeSnapshot.activeRetirementUpdatedAt()).isNotNull()
                .isBeforeOrEqualTo(activeSnapshot.observedAt());
        List<DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                .RetentionResult> results = new ArrayList<>();
        results.add(first);
        results.addAll(complete(control, 19));

        assertThat(results).isNotEmpty().allSatisfy(result -> {
            int touchedSegments = (result.classificationsDeleted() > 0 ? 1 : 0)
                    + (result.expectedDeleted() > 0 ? 1 : 0)
                    + (result.itemsDeleted() > 0 ? 1 : 0)
                    + (result.pagesDeleted() > 0 ? 1 : 0);
            assertThat(touchedSegments).isLessThanOrEqualTo(1);
            assertThat(result.classificationsDeleted()).isLessThanOrEqualTo(1);
            assertThat(result.expectedDeleted()).isLessThanOrEqualTo(1);
            assertThat(result.itemsDeleted()).isLessThanOrEqualTo(1);
            assertThat(result.pagesDeleted()).isLessThanOrEqualTo(1);
        });
        assertThat(results.getLast().sourceRetired()).isTrue();
        assertThat(count("rg_test_suite_stability_observation_external_classifications")).isZero();
        assertThat(count("rg_test_suite_stability_observation_external_expected_snapshots")).isZero();
        assertThat(count("rg_test_suite_stability_observation_external_inventory_items")).isZero();
        assertThat(count("rg_test_suite_stability_observation_external_inventory_pages")).isZero();
        assertThat(rowExists("rg_test_suite_stability_observation_external_comparisons",
                "comparison_id", fixture.comparisonId())).isFalse();
        assertThat(rowExists("rg_test_suite_stability_observation_external_inventory_cycles",
                "cycle_id", fixture.cycleId())).isFalse();
        assertThat(database.jdbc().queryForObject("""
                SELECT progress_status
                FROM rg_test_suite_stability_observation_external_source_retention_progress
                WHERE cycle_id = ?
                """, String.class, fixture.cycleId())).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> classifier.classifications(fixture.comparisonId(), "", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired");
        var snapshot = control.operationalSnapshot(Duration.ofDays(1), Duration.ofDays(1));
        assertThat(snapshot.totalClassificationsDeleted()).isEqualTo(2);
        assertThat(snapshot.totalExpectedDeleted()).isEqualTo(2);
        assertThat(snapshot.totalItemsDeleted()).isEqualTo(2);
        assertThat(snapshot.totalPagesDeleted()).isEqualTo(1);
        assertThat(snapshot.totalSourcesRetired()).isEqualTo(1);
        assertThat(snapshot.activeRetirement()).isFalse();
    }

    @Test
    void retiresExpiredUncomparedSnapshotWithoutInventingComparisonEvidence() throws Exception {
        SourceFixture fixture = insertExpiredSource(1,
                Instant.now().minus(Duration.ofDays(3)));

        List<DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                .RetentionResult> results = complete(
                retention("replica-a", historicalAuthority), 10);

        assertThat(results.getLast().mode()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                        .RetirementMode.SNAPSHOT_EXPIRED);
        assertThat(results).allMatch(result -> result.classificationsDeleted() == 0
                && result.expectedDeleted() == 0);
        assertThat(rowExists("rg_test_suite_stability_observation_external_inventory_cycles",
                "cycle_id", fixture.cycleId())).isFalse();
        assertThat(count("rg_test_suite_stability_observation_external_comparisons")).isZero();
    }

    @Test
    void latestAuthorityPositionsAndUnretiredFindingEvidenceBlockEligibility() throws Exception {
        SourceFixture fixture = insertProcessedSource(1,
                Instant.now().minus(Duration.ofDays(3)));
        pointAuthoritiesAt(fixture.cycleId(), fixture.comparisonId());

        var blockedLatest = retention("replica-a", historicalAuthority).retain(
                Duration.ofDays(1), Duration.ofDays(1), 10);

        assertThat(blockedLatest.result().mode()).isNull();
        assertThat(count("rg_test_suite_stability_observation_external_source_retention_progress"))
                .isZero();

        pointAuthoritiesAt(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        database.jdbc().update("""
                UPDATE
                    rg_test_suite_stability_observation_external_finding_evidence_retirements
                SET retirement_status = 'ACTIVE'
                WHERE comparison_id = ?
                """, fixture.comparisonId());

        var blockedEvidence = retention("replica-a", historicalAuthority).retain(
                Duration.ofDays(1), Duration.ofDays(1), 10);
        assertThat(blockedEvidence.result().mode()).isNull();
        assertThat(count("rg_test_suite_stability_observation_external_source_retention_progress"))
                .isZero();
    }

    @Test
    void corruptClassificationRollsBackMarkerAndDoesNotDeleteSource() throws Exception {
        SourceFixture fixture = insertProcessedSource(2,
                Instant.now().minus(Duration.ofDays(3)));
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_classifications
                SET record_fingerprint = ?
                WHERE comparison_id = ? AND object_id = ?
                """, fingerprint("tampered-row"), fixture.comparisonId(), objectId(1));

        assertThatThrownBy(() -> retention("replica-a", historicalAuthority).retain(
                Duration.ofDays(1), Duration.ofDays(1), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classification row");
        assertThat(count("rg_test_suite_stability_observation_external_classifications"))
                .isEqualTo(2);
        assertThat(count("rg_test_suite_stability_observation_external_source_retention_progress"))
                .isZero();
    }

    @Test
    void missingTailAfterCommittedPageLeavesPermanentActiveQuarantine() throws Exception {
        SourceFixture fixture = insertProcessedSource(2,
                Instant.now().minus(Duration.ofDays(3)));
        var control = retention("replica-a", historicalAuthority);
        control.retain(Duration.ofDays(1), Duration.ofDays(1), 1);
        database.jdbc().update("""
                DELETE FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ? AND object_id = ?
                """, fixture.comparisonId(), objectId(2));

        assertThatThrownBy(() -> control.retain(Duration.ofDays(1), Duration.ofDays(1), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classification retirement replay failed");
        assertThat(database.jdbc().queryForObject("""
                SELECT progress_status
                FROM rg_test_suite_stability_observation_external_source_retention_progress
                WHERE cycle_id = ?
                """, String.class, fixture.cycleId())).isEqualTo("ACTIVE");
    }

    @Test
    void unavailableHistoricalTrustStopsPageDeletionAndParentRetirement() throws Exception {
        SourceFixture fixture = insertProcessedSource(1,
                Instant.now().minus(Duration.ofDays(3)));
        var unavailable = authority(
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification
                        .UNAVAILABLE);
        var control = retention("replica-b", unavailable);
        control.init();

        assertThatThrownBy(() -> complete(control, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory page row");
        assertThat(count("rg_test_suite_stability_observation_external_inventory_pages"))
                .isEqualTo(1);
        assertThat(rowExists("rg_test_suite_stability_observation_external_comparisons",
                "comparison_id", fixture.comparisonId())).isTrue();
        assertThat(database.jdbc().queryForObject("""
                SELECT progress_status
                FROM rg_test_suite_stability_observation_external_source_retention_progress
                WHERE cycle_id = ?
                """, String.class, fixture.cycleId())).isEqualTo("ACTIVE");
    }

    @Test
    void validatesPolicyAndRejectsProgressAndStateTampering() throws Exception {
        var control = retention("replica-a", historicalAuthority);
        assertThatThrownBy(() -> control.retain(Duration.ofHours(23), Duration.ofDays(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> control.retain(Duration.ofDays(1), Duration.ofDays(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        SourceFixture fixture = insertProcessedSource(2,
                Instant.now().minus(Duration.ofDays(3)));
        control.retain(Duration.ofDays(1), Duration.ofDays(1), 1);
        database.jdbc().update("""
                UPDATE
                    rg_test_suite_stability_observation_external_source_retention_progress
                SET revision = revision + 1
                WHERE cycle_id = ?
                """, fixture.cycleId());

        assertThatThrownBy(() -> control.retain(Duration.ofDays(1), Duration.ofDays(1), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("progress is corrupt");

        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_source_retention_state
                SET total_items_deleted = total_items_deleted + 1
                WHERE job_name = 'external-archive-source-retention'
                """);
        assertThatThrownBy(() -> control.operationalSnapshot(
                Duration.ofDays(1), Duration.ofDays(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("state is corrupt");
    }

    @Test
    void liveDatabaseLeaseReturnsBusyWithoutMutatingState() throws Exception {
        var holder = retention("replica-holder", historicalAuthority);
        Method acquire = holder.getClass().getDeclaredMethod("acquireLease");
        acquire.setAccessible(true);
        assertThat(acquire.invoke(holder)).asString().contains("Optional[");

        var attempt = retention("replica-waiter", historicalAuthority).retain(
                Duration.ofDays(1), Duration.ofDays(1), 10);

        assertThat(attempt.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                        .RetentionStatus.LEASE_BUSY);
        assertThat(attempt.result()).isNull();
        assertThat(count("rg_test_suite_stability_observation_external_source_retention_progress"))
                .isZero();
    }

    @Test
    void requiresNewDeletionAndMarkerSurviveSurroundingRollback() throws Exception {
        SourceFixture fixture = insertProcessedSource(2,
                Instant.now().minus(Duration.ofDays(3)));
        var control = retention("replica-a", historicalAuthority);
        TransactionTemplate outer = new TransactionTemplate(database.transactionManager());

        outer.executeWithoutResult(status -> {
            control.retain(Duration.ofDays(1), Duration.ofDays(1), 1);
            status.setRollbackOnly();
        });

        assertThat(count("rg_test_suite_stability_observation_external_classifications"))
                .isEqualTo(1);
        assertThat(database.jdbc().queryForObject("""
                SELECT retirement_status
                FROM rg_test_suite_stability_observation_external_source_retirements
                WHERE cycle_id = ?
                """, String.class, fixture.cycleId())).isEqualTo("ACTIVE");
    }

    @Test
    void markerTamperBlocksResumeAndClassificationExport() throws Exception {
        SourceFixture fixture = insertProcessedSource(2,
                Instant.now().minus(Duration.ofDays(3)));
        var control = retention("replica-a", historicalAuthority);
        control.retain(Duration.ofDays(1), Duration.ofDays(1), 1);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_source_retirements
                SET record_fingerprint = ?
                WHERE cycle_id = ?
                """, fingerprint("tampered-marker"), fixture.cycleId());
        var classifier = new
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                new DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Settings(10));

        assertThatThrownBy(() -> control.retain(Duration.ofDays(1), Duration.ofDays(1), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marker is corrupt");
        assertThatThrownBy(() -> classifier.classifications(fixture.comparisonId(), "", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marker is corrupt");
    }

    @Test
    void controlAuthorityAndGovernanceProjectionTamperCannotAdmitCandidate() throws Exception {
        SourceFixture authorityFixture = insertProcessedSource(1,
                Instant.now().minus(Duration.ofDays(3)));
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_inventory_authorities
                SET record_fingerprint = ?
                WHERE authority_id = ?
                """, fingerprint("tampered-authority"), AUTHORITY);

        assertThatThrownBy(() -> retention("replica-a", historicalAuthority).retain(
                Duration.ofDays(1), Duration.ofDays(1), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authority control is corrupt");
        assertThat(count("rg_test_suite_stability_observation_external_source_retirements"))
                .isZero();

        pointAuthoritiesAt(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_projections
                SET record_fingerprint = ?
                WHERE comparison_id = ?
                """, fingerprint("tampered-projection"), authorityFixture.comparisonId());
        assertThatThrownBy(() -> retention("replica-a", historicalAuthority).retain(
                Duration.ofDays(1), Duration.ofDays(1), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("governance projection is corrupt");
        assertThat(count("rg_test_suite_stability_observation_external_source_retirements"))
                .isZero();
    }

    private List<DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
            .RetentionResult> complete(
                    DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                            control,
                    int maximumCalls) {
        List<DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                .RetentionResult> results = new ArrayList<>();
        for (int call = 0; call < maximumCalls; call++) {
            var result = control.retain(Duration.ofDays(1), Duration.ofDays(1), 1).result();
            results.add(result);
            if (result.sourceRetired()) {
                return results;
            }
        }
        throw new AssertionError("Source retirement did not complete");
    }

    private SourceFixture insertProcessedSource(int itemCount, Instant completedAt)
            throws Exception {
        SourceFixture fixture = insertSource(itemCount, completedAt, "COMPLETED");
        String comparisonId = UUID.randomUUID().toString();
        Instant started = completedAt.minus(Duration.ofMinutes(2))
                .truncatedTo(ChronoUnit.MILLIS);
        Instant completed = completedAt.truncatedTo(ChronoUnit.MILLIS);
        String topology = ExternalArchiveComparisonStateIntegrity.topologyFingerprint(objectMapper,
                TRUST_DOMAIN, ARCHIVE_SET, AUTHORITY, FAILURE_DOMAIN);
        String expectedRoot =
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .EMPTY_EXPECTED_ROOT;
        String classificationRoot =
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .EMPTY_CLASSIFICATION_ROOT;
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items = fixture.items();
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            expectedRoot = ExternalArchiveComparisonStateIntegrity.appendExpectedRoot(objectMapper,
                    expectedRoot, item.itemFingerprint(), topology);
            database.jdbc().update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_expected_snapshots (
                        comparison_id, authority_id, object_id, trust_domain, archive_set_id,
                        failure_domain, item_fingerprint, object_commitment, retirement_id,
                        retirement_fingerprint, segment_id, segment_fingerprint,
                        retention_policy_fingerprint, retain_until, stored_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, comparisonId, AUTHORITY, item.objectId(), TRUST_DOMAIN, ARCHIVE_SET,
                    FAILURE_DOMAIN, item.itemFingerprint(), item.objectCommitment(),
                    item.retirementId(), item.retirementFingerprint(), item.segmentId(),
                    item.segmentFingerprint(), item.retentionPolicyFingerprint(),
                    Timestamp.from(item.retainUntil()), Timestamp.from(item.storedAt()));
            var material = new ClassificationMaterialFixture(
                    "bloge.testSuiteStabilityObservationExternalArchiveClassification.v1",
                    comparisonId, fixture.cycleId(), AUTHORITY, item.objectId(),
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .Outcome.MATCHED,
                    item.itemFingerprint(), item.itemFingerprint(), item.objectCommitment(),
                    item.objectCommitment(), topology, topology, item.retainUntil(),
                    item.retainUntil());
            String classificationFingerprint = ProtocolFingerprint.of(objectMapper, material);
            var classification = new
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .Classification(comparisonId, fixture.cycleId(), AUTHORITY,
                            item.objectId(), material.outcome(), material.expectedItemFingerprint(),
                            material.observedItemFingerprint(),
                            material.expectedObjectCommitment(),
                            material.observedObjectCommitment(),
                            material.expectedTopologyFingerprint(),
                            material.observedTopologyFingerprint(),
                            material.expectedRetainUntil(), material.observedRetainUntil(),
                            classificationFingerprint);
            classificationRoot = ExternalArchiveComparisonStateIntegrity.appendClassificationRoot(
                    objectMapper, classificationRoot, classificationFingerprint);
            Instant committed = completed.minusSeconds(itemCount - index);
            String rowFingerprint =
                    ExternalArchiveComparisonStateIntegrity.classificationRowFingerprint(
                            objectMapper, classification, index, committed);
            database.jdbc().update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_classifications (
                        comparison_id, cycle_id, authority_id, object_id, page_sequence, outcome,
                        expected_item_fingerprint, observed_item_fingerprint,
                        expected_object_commitment, observed_object_commitment,
                        expected_topology_fingerprint, observed_topology_fingerprint,
                        expected_retain_until, observed_retain_until,
                        classification_fingerprint, committed_at, record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, comparisonId, fixture.cycleId(), AUTHORITY, item.objectId(), index,
                    material.outcome().name(), item.itemFingerprint(), item.itemFingerprint(),
                    item.objectCommitment(), item.objectCommitment(), topology, topology,
                    Timestamp.from(item.retainUntil()), Timestamp.from(item.retainUntil()),
                    classificationFingerprint, Timestamp.from(committed), rowFingerprint);
        }
        String comparisonFingerprint =
                ExternalArchiveComparisonStateIntegrity.comparisonFingerprint(objectMapper,
                        comparisonId, fixture.cycleId(), AUTHORITY, "COMPLETED", TRUST_DOMAIN,
                        ARCHIVE_SET, FAILURE_DOMAIN, fixture.snapshotId(), itemCount,
                        fixture.itemRoot(), itemCount, expectedRoot,
                        itemCount == 0 ? "" : items.getLast().objectId(), 1, itemCount,
                        itemCount, 0, 0, 0, 0, 0, classificationRoot, 1, started, completed,
                        completed);
        database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_comparisons (
                    comparison_id, cycle_id, authority_id, comparison_status, trust_domain,
                    archive_set_id, failure_domain, remote_snapshot_id, remote_object_count,
                    remote_root, expected_object_count, expected_root, next_after_object_id,
                    next_page_sequence, classified_object_count, matched_count,
                    missing_remote_count, unexpected_remote_count, material_conflict_count,
                    retention_shortened_count, unknown_count, classification_root, revision,
                    started_at, completed_at, updated_at, record_fingerprint
                ) VALUES (?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 0, 0, 0,
                          0, 0, ?, 1, ?, ?, ?, ?)
                """, comparisonId, fixture.cycleId(), AUTHORITY, TRUST_DOMAIN, ARCHIVE_SET,
                FAILURE_DOMAIN, fixture.snapshotId(), itemCount, fixture.itemRoot(), itemCount,
                expectedRoot, itemCount == 0 ? "" : items.getLast().objectId(), itemCount,
                itemCount, classificationRoot, Timestamp.from(started), Timestamp.from(completed),
                Timestamp.from(completed), comparisonFingerprint);
        insertGovernanceCompletion(comparisonId, classificationRoot, itemCount, started, completed);
        pointAuthoritiesAt(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        return new SourceFixture(fixture.cycleId(), comparisonId, fixture.snapshotId(),
                fixture.itemRoot(), items);
    }

    private SourceFixture insertExpiredSource(int itemCount, Instant completedAt) throws Exception {
        SourceFixture fixture = insertSource(itemCount, completedAt, "SNAPSHOT_EXPIRED");
        pointAuthoritiesAt(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        return fixture;
    }

    private SourceFixture insertSource(int itemCount, Instant completedAt, String status)
            throws Exception {
        String cycleId = UUID.randomUUID().toString();
        Instant completed = completedAt.truncatedTo(ChronoUnit.MILLIS);
        Instant started = completed.minus(Duration.ofMinutes(2));
        Instant snapshotAt = completed.minus(Duration.ofMinutes(1))
                .truncatedTo(ChronoUnit.SECONDS);
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items = new ArrayList<>();
        for (int index = 1; index <= itemCount; index++) {
            items.add(item(index, snapshotAt));
        }
        String root = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.root(
                objectMapper, items);
        String snapshotId = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity
                .snapshotId(objectMapper, TRUST_DOMAIN, ARCHIVE_SET, AUTHORITY, FAILURE_DOMAIN,
                        snapshotAt, itemCount, root);
        String lastObject = items.isEmpty() ? "" : items.getLast().objectId();
        String nextCursor = "SNAPSHOT_EXPIRED".equals(status) ? lastObject : "";
        String cycleFingerprint = ExternalArchiveInventoryStateIntegrity.cycleFingerprint(
                objectMapper, cycleId, AUTHORITY, status, TRUST_DOMAIN, ARCHIVE_SET,
                FAILURE_DOMAIN, snapshotId, snapshotAt, itemCount, root, nextCursor, 1,
                itemCount, root, lastObject, 1, started, completed, completed);
        database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_inventory_cycles (
                    cycle_id, authority_id, cycle_status, trust_domain, archive_set_id,
                    failure_domain, snapshot_id, snapshot_at, snapshot_object_count,
                    snapshot_root, next_after_object_id, next_page_sequence,
                    accumulated_object_count, accumulated_root, last_object_id, revision,
                    started_at, completed_at, updated_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 1, ?, ?, ?, ?)
                """, cycleId, AUTHORITY, status, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN,
                snapshotId, Timestamp.from(snapshotAt), itemCount, root, nextCursor, itemCount,
                root, lastObject, Timestamp.from(started), Timestamp.from(completed),
                Timestamp.from(completed), cycleFingerprint);
        for (TestSuiteStabilityObservationExternalArchiveInventoryItem item : items) {
            Instant committed = completed.minusSeconds(1);
            String rowFingerprint = ExternalArchiveInventoryStagingIntegrity.itemFingerprint(
                    objectMapper, cycleId, 0, item, committed);
            database.jdbc().update("""
                    INSERT INTO rg_test_suite_stability_observation_external_inventory_items (
                        cycle_id, object_id, page_sequence, item_fingerprint, object_commitment,
                        retirement_id, retirement_fingerprint, segment_id, segment_fingerprint,
                        retention_policy_fingerprint, retain_until, stored_at, committed_at,
                        record_fingerprint
                    ) VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, cycleId, item.objectId(), item.itemFingerprint(), item.objectCommitment(),
                    item.retirementId(), item.retirementFingerprint(), item.segmentId(),
                    item.segmentFingerprint(), item.retentionPolicyFingerprint(),
                    Timestamp.from(item.retainUntil()), Timestamp.from(item.storedAt()),
                    Timestamp.from(committed), rowFingerprint);
        }
        insertPage(cycleId, snapshotId, snapshotAt, root, items, completed.minusSeconds(1));
        return new SourceFixture(cycleId, "", snapshotId, root, List.copyOf(items));
    }

    private void insertPage(
            String cycleId,
            String snapshotId,
            Instant snapshotAt,
            String root,
            List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items,
            Instant committedAt) throws Exception {
        Instant issuedAt = snapshotAt.plusSeconds(1);
        var request = TestSuiteStabilityObservationExternalArchiveInventoryRequest.create(
                objectMapper, TRUST_DOMAIN, ARCHIVE_SET, AUTHORITY, "", "", 0,
                Math.max(1, items.size()), Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new byte[32]), issuedAt, issuedAt.plusSeconds(10));
        var material = new TestSuiteStabilityObservationExternalArchiveInventoryPage.Material(
                TestSuiteStabilityObservationExternalArchiveInventoryPage.SCHEMA_VERSION,
                request.requestFingerprint(), AUTHORITY, FAILURE_DOMAIN, "key-a", snapshotId,
                snapshotAt, items.size(), root, items, "", true, issuedAt,
                request.expiresAt(), "Ed25519");
        String pageFingerprint = ProtocolFingerprint.of(objectMapper, material);
        var page = new TestSuiteStabilityObservationExternalArchiveInventoryPage(
                material.schemaVersion(), pageFingerprint, request, material.authorityId(),
                material.failureDomain(), material.keyId(), material.snapshotId(),
                material.snapshotAt(), material.snapshotObjectCount(), material.snapshotRoot(),
                material.items(), material.nextAfterObjectId(), material.complete(),
                material.issuedAt(), material.expiresAt(), material.algorithm(),
                Base64.getEncoder().encodeToString(new byte[64]));
        String json = objectMapper.writeValueAsString(page);
        String rowFingerprint = ExternalArchiveInventoryStagingIntegrity.pageFingerprint(
                objectMapper, cycleId, 0, AUTHORITY, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN,
                request.requestFingerprint(), pageFingerprint, snapshotId, snapshotAt,
                items.size(), root, "", "", items.size(), true, issuedAt,
                request.expiresAt(), committedAt, json);
        database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_inventory_pages (
                    cycle_id, page_sequence, authority_id, trust_domain, archive_set_id,
                    failure_domain, request_fingerprint, page_fingerprint, snapshot_id,
                    snapshot_at, snapshot_object_count, snapshot_root, after_object_id,
                    next_after_object_id, item_count, complete, issued_at, expires_at,
                    committed_at, page_json, record_fingerprint
                ) VALUES (?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', '', ?, TRUE, ?, ?, ?, ?, ?)
                """, cycleId, AUTHORITY, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN,
                request.requestFingerprint(), pageFingerprint, snapshotId,
                Timestamp.from(snapshotAt), items.size(), root, items.size(),
                Timestamp.from(issuedAt), Timestamp.from(request.expiresAt()),
                Timestamp.from(committedAt), json, rowFingerprint);
    }

    private void insertGovernanceCompletion(
            String comparisonId,
            String classificationRoot,
            int itemCount,
            Instant comparisonStarted,
            Instant comparisonCompleted) {
        String projectionId = UUID.randomUUID().toString();
        Instant completed = comparisonCompleted.plusSeconds(1);
        String snapshotRoot =
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .EMPTY_SNAPSHOT_ROOT;
        String eventRoot =
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .EMPTY_EVENT_ROOT;
        String projectionFingerprint = ExternalArchiveFindingStateIntegrity.projectionFingerprint(
                objectMapper, projectionId, comparisonId, AUTHORITY, "COMPLETED",
                comparisonStarted, comparisonCompleted, itemCount, classificationRoot, 0,
                snapshotRoot, "", 1, itemCount, 0, 0, 0, 0, 0, eventRoot, 1,
                comparisonCompleted, completed, completed);
        database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_finding_projections (
                    projection_id, comparison_id, authority_id, projection_status,
                    comparison_started_at, comparison_completed_at,
                    source_classification_count, source_classification_root,
                    snapshot_finding_count, snapshot_root, next_after_object_id,
                    next_page_sequence, processed_classification_count, opened_count,
                    observed_count, reopened_count, resolved_count, confirmed_count,
                    event_root, revision, started_at, completed_at, updated_at, record_fingerprint
                ) VALUES (?, ?, ?, 'COMPLETED', ?, ?, ?, ?, 0, ?, '', 1, ?, 0, 0, 0, 0, 0,
                          ?, 1, ?, ?, ?, ?)
                """, projectionId, comparisonId, AUTHORITY, Timestamp.from(comparisonStarted),
                Timestamp.from(comparisonCompleted), itemCount, classificationRoot,
                snapshotRoot, itemCount, eventRoot,
                Timestamp.from(comparisonCompleted), Timestamp.from(completed),
                Timestamp.from(completed), projectionFingerprint);
        String retirementFingerprint =
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .evidenceRetirementFingerprint(objectMapper, projectionId, comparisonId,
                                AUTHORITY, "COMPLETED", comparisonCompleted, completed);
        database.jdbc().update("""
                INSERT INTO
                    rg_test_suite_stability_observation_external_finding_evidence_retirements (
                    projection_id, comparison_id, authority_id, retirement_status,
                    started_at, completed_at, record_fingerprint
                ) VALUES (?, ?, ?, 'COMPLETED', ?, ?, ?)
                """, projectionId, comparisonId, AUTHORITY,
                Timestamp.from(comparisonCompleted), Timestamp.from(completed),
                retirementFingerprint);
    }

    private void pointAuthoritiesAt(String latestCycleId, String latestComparisonId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String inventoryFingerprint = ExternalArchiveInventoryStateIntegrity.authorityFingerprint(
                objectMapper, AUTHORITY, "", "", 0, now, 0, "", latestCycleId, now, now);
        database.jdbc().update("DELETE FROM "
                + "rg_test_suite_stability_observation_external_inventory_authorities");
        database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_inventory_authorities (
                    authority_id, lease_owner, lease_token, lease_epoch, lease_until, revision,
                    active_cycle_id, last_completed_cycle_id, last_success_at, updated_at,
                    record_fingerprint
                ) VALUES (?, '', '', 0, ?, 0, '', ?, ?, ?, ?)
                """, AUTHORITY, Timestamp.from(now), latestCycleId, Timestamp.from(now),
                Timestamp.from(now), inventoryFingerprint);
        String comparisonFingerprint =
                ExternalArchiveComparisonStateIntegrity.authorityFingerprint(
                        objectMapper, AUTHORITY, "", latestComparisonId, 0, now);
        database.jdbc().update("DELETE FROM "
                + "rg_test_suite_stability_observation_external_comparison_authorities");
        database.jdbc().update("""
                INSERT INTO
                    rg_test_suite_stability_observation_external_comparison_authorities (
                    authority_id, active_comparison_id, last_completed_comparison_id,
                    revision, updated_at, record_fingerprint
                ) VALUES (?, '', ?, 0, ?, ?)
                """, AUTHORITY, latestComparisonId, Timestamp.from(now), comparisonFingerprint);
        database.jdbc().update("DELETE FROM "
                + "rg_test_suite_stability_observation_external_finding_authorities");
        String latestProjectionId = UUID.randomUUID().toString();
        String findingAuthorityFingerprint =
                ExternalArchiveFindingStateIntegrity.authorityFingerprint(objectMapper,
                        AUTHORITY, "", latestProjectionId, latestComparisonId, now, 0, now);
        database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_finding_authorities (
                    authority_id, active_projection_id, last_completed_projection_id,
                    last_applied_comparison_id, last_applied_comparison_completed_at,
                    revision, updated_at, record_fingerprint
                ) VALUES (?, '', ?, ?, ?, 0, ?, ?)
                """, AUTHORITY, latestProjectionId, latestComparisonId,
                Timestamp.from(now), Timestamp.from(now), findingAuthorityFingerprint);
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryItem item(
            int index,
            Instant snapshotAt) {
        String suffix = "%064x".formatted(index);
        var material = new TestSuiteStabilityObservationExternalArchiveInventoryItem.Material(
                TestSuiteStabilityObservationExternalArchiveInventoryItem.SCHEMA_VERSION,
                objectId(index), fingerprint("commitment-" + index),
                "stability-observation-retirement-" + suffix,
                fingerprint("retirement-" + index),
                "stability-observation-archive-" + suffix,
                fingerprint("segment-" + index), fingerprint("policy-" + index),
                snapshotAt.plus(Duration.ofDays(30)), snapshotAt.minusSeconds(30 - index));
        return new TestSuiteStabilityObservationExternalArchiveInventoryItem(
                material.schemaVersion(), ProtocolFingerprint.of(objectMapper, material),
                material.objectId(), material.objectCommitment(), material.retirementId(),
                material.retirementFingerprint(), material.segmentId(),
                material.segmentFingerprint(), material.retentionPolicyFingerprint(),
                material.retainUntil(), material.storedAt());
    }

    private DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
            retention(
                    String owner,
                    TestSuiteStabilityObservationExternalArchiveInventoryAuthority authority) {
        return new
                DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper, authority, owner,
                Duration.ofSeconds(5));
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryAuthority authority(
            TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification result) {
        return new TestSuiteStabilityObservationExternalArchiveInventoryAuthority() {
            @Override
            public List<String> inventoryAuthorities() {
                return List.of(AUTHORITY);
            }

            @Override
            public TestSuiteStabilityObservationExternalArchiveInventoryPage inventoryPage(
                    String authorityId,
                    Cursor cursor,
                    int maximumItems) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Verification verifyInventoryPage(
                    TestSuiteStabilityObservationExternalArchiveInventoryPage page) {
                return result;
            }

            @Override
            public Verification verifyStoredInventoryPage(
                    TestSuiteStabilityObservationExternalArchiveInventoryPage page) {
                return result;
            }
        };
    }

    private int count(String table) {
        Integer count = database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private boolean rowExists(String table, String key, String value) {
        Integer count = database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + key + " = ?",
                Integer.class, value);
        return count != null && count == 1;
    }

    private static String objectId(int index) {
        return "stability-observation-worm-" + "%064x".formatted(index);
    }

    private static String fingerprint(String value) {
        return ProtocolFingerprint.ofText(value);
    }

    private record SourceFixture(
            String cycleId,
            String comparisonId,
            String snapshotId,
            String itemRoot,
            List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items) {
    }

    private record ClassificationMaterialFixture(
            String schemaVersion,
            String comparisonId,
            String cycleId,
            String authorityId,
            String objectId,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane.Outcome
                    outcome,
            String expectedItemFingerprint,
            String observedItemFingerprint,
            String expectedObjectCommitment,
            String observedObjectCommitment,
            String expectedTopologyFingerprint,
            String observedTopologyFingerprint,
            Instant expectedRetainUntil,
            Instant observedRetainUntil) {
    }
}
