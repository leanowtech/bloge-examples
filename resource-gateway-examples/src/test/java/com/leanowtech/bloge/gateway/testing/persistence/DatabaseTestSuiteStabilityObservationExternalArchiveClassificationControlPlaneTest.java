package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryItem;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryPage;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationExternalArchiveInventoryIntegrity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlaneTest {
    private static final String AUTHORITY = "archive-a";
    private static final String TRUST_DOMAIN = "archive.example";
    private static final String ARCHIVE_SET = "archive-set-a";
    private static final String FAILURE_DOMAIN = "region-a";
    private static final Instant STORED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private ObjectMapper objectMapper;
    private TestRuntimeDatabase database;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:external-classification-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1",
                "sa", "", 6));
        initializeInventoryTables();
        initializeExpectedObjectTable();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void classifiesEveryClosedOutcomeAcrossBoundedPagesAndExportsCompletedEvidence() {
        var matched = item(1, "matched", 3_600);
        var missing = item(2, "missing", 3_600);
        var expectedRetention = item(3, "retention", 7_200);
        var observedRetention = withRetention(expectedRetention, 1_800);
        var expectedConflict = item(4, "conflict-a", 3_600);
        var observedConflict = withSegmentFingerprint(expectedConflict, "conflict-b");
        var topologyDrift = item(5, "topology", 3_600);
        var unexpected = item(6, "unexpected", 3_600);
        insertExpected(matched, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        insertExpected(missing, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        insertExpected(expectedRetention, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        insertExpected(expectedConflict, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        insertExpected(topologyDrift, TRUST_DOMAIN, "archive-set-b", FAILURE_DOMAIN);
        installCompletedCycle(List.of(matched, observedRetention, observedConflict,
                topologyDrift, unexpected), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(2);

        List<DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .ComparisonPage> pages = complete(controlPlane);

        assertThat(pages).hasSizeGreaterThan(1);
        assertThat(pages.getFirst().status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .ComparisonStatus.STAGED);
        var terminal = pages.getLast();
        assertThat(terminal.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .ComparisonStatus.COMPLETED);
        assertThat(terminal.classifiedObjectCount()).isEqualTo(6);
        assertThat(terminal.findingObjectCount()).isEqualTo(5);
        List<DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .Classification> evidence = controlPlane.classifications(
                terminal.comparisonId(), "", 500);
        assertThat(evidence).extracting(
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Classification::outcome)
                .containsExactly(
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                                .Outcome.MATCHED,
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                                .Outcome.MISSING_REMOTE,
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                                .Outcome.RETENTION_SHORTENED,
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                                .Outcome.MATERIAL_CONFLICT,
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                                .Outcome.UNKNOWN,
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                                .Outcome.UNEXPECTED_REMOTE);
        assertThat(evidence).allMatch(value -> value.fingerprintVerified(objectMapper));
        assertThat(evidence).extracting(
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Classification::objectId)
                .isSorted();
        assertThat(database.jdbc().queryForMap("""
                SELECT matched_count, missing_remote_count, unexpected_remote_count,
                       material_conflict_count, retention_shortened_count, unknown_count
                FROM rg_test_suite_stability_observation_external_comparisons
                WHERE comparison_id = ?
                """, terminal.comparisonId()))
                .containsEntry("MATCHED_COUNT", 1L)
                .containsEntry("MISSING_REMOTE_COUNT", 1L)
                .containsEntry("UNEXPECTED_REMOTE_COUNT", 1L)
                .containsEntry("MATERIAL_CONFLICT_COUNT", 1L)
                .containsEntry("RETENTION_SHORTENED_COUNT", 1L)
                .containsEntry("UNKNOWN_COUNT", 1L);
        assertThat(controlPlane.compareNextPage(AUTHORITY).status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .ComparisonStatus.CURRENT);
    }

    @Test
    void exposesVerifiedIdentityFreeComparisonProgressBeforeDuringAndAfterCompletion() {
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items = List.of(
                item(1, "one", 3_600), item(2, "two", 3_600),
                item(3, "three", 3_600));
        items.forEach(value -> insertExpected(
                value, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN));
        installCompletedCycle(items, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(1);

        var before = controlPlane.operationalSnapshot(AUTHORITY);
        var staged = controlPlane.compareNextPage(AUTHORITY);
        var active = controlPlane.operationalSnapshot(AUTHORITY);
        var terminal = complete(controlPlane).getLast();
        var completed = controlPlane.operationalSnapshot(AUTHORITY);

        assertThat(before.initialized()).isFalse();
        assertThat(staged.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .ComparisonStatus.STAGED);
        assertThat(active.initialized()).isTrue();
        assertThat(active.activeComparison()).isTrue();
        assertThat(active.nextPageSequence()).isEqualTo(1);
        assertThat(active.classifiedObjectCount()).isEqualTo(1);
        assertThat(active.activeStartedAt()).isNotNull();
        assertThat(active.activeUpdatedAt()).isNotNull();
        assertThat(active.lastCompletedAt()).isNull();
        assertThat(completed.initialized()).isTrue();
        assertThat(completed.activeComparison()).isFalse();
        assertThat(completed.lastCompletedAt()).isNotNull();
        assertThat(completed.lastCompletedAt()).isEqualTo(
                database.jdbc().queryForObject("""
                        SELECT completed_at
                        FROM rg_test_suite_stability_observation_external_comparisons
                        WHERE comparison_id = ?
                        """, Timestamp.class, terminal.comparisonId()).toInstant());
        assertThat(completed.toString()).doesNotContain(
                terminal.comparisonId(), terminal.cycleId(), items.getFirst().objectId());
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_comparisons
                SET record_fingerprint = ?
                WHERE comparison_id = ?
                """, fingerprint("tampered-operational-comparison"), terminal.comparisonId());
        assertThatThrownBy(() -> controlPlane.operationalSnapshot(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("comparison state is corrupt");
    }

    @Test
    void comparisonRejectsTamperedInventoryAuthorityBeforeCreatingSourceState() {
        var item = item(1, "authority-tamper", 3_600);
        insertExpected(item, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        installCompletedCycle(List.of(item), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_inventory_authorities
                SET revision = revision + 1
                WHERE authority_id = ?
                """, AUTHORITY);

        assertThatThrownBy(() -> controlPlane(10).compareNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory authority is corrupt");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_comparisons
                """, Integer.class)).isZero();
    }

    @Test
    void comparisonRejectsStructurallyValidTamperedCycleBeforeFreezingExpectedState() {
        var item = item(1, "cycle-tamper", 3_600);
        insertExpected(item, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        String cycleId = installCompletedCycle(
                List.of(item), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_inventory_cycles
                SET updated_at = DATEADD('MILLISECOND', 1, updated_at)
                WHERE cycle_id = ?
                """, cycleId);

        assertThatThrownBy(() -> controlPlane(10).compareNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completed cycle is corrupt");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_expected_snapshots
                """, Integer.class)).isZero();
    }

    @Test
    void freezesExpectedObjectsBeforePagingAndUsesANewSnapshotForTheNextCycle() {
        var first = item(1, "first", 3_600);
        var middle = item(2, "middle", 3_600);
        var last = item(3, "last", 3_600);
        insertExpected(first, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        insertExpected(last, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        installCompletedCycle(List.of(first, last), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(1);

        var firstPage = controlPlane.compareNextPage(AUTHORITY);
        assertThat(firstPage.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .ComparisonStatus.STAGED);
        insertExpected(middle, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var firstTerminal = complete(controlPlane).getLast();

        assertThat(controlPlane.classifications(firstTerminal.comparisonId(), "", 10))
                .extracting(value -> value.objectId())
                .containsExactly(first.objectId(), last.objectId());
        assertThat(database.jdbc().queryForObject("""
                SELECT expected_object_count
                FROM rg_test_suite_stability_observation_external_comparisons
                WHERE comparison_id = ?
                """, Long.class, firstTerminal.comparisonId())).isEqualTo(2L);

        installCompletedCycle(List.of(first, middle, last),
                TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var secondTerminal = complete(controlPlane).getLast();
        assertThat(secondTerminal.comparisonId()).isNotEqualTo(firstTerminal.comparisonId());
        assertThat(controlPlane.classifications(secondTerminal.comparisonId(), "", 10))
                .extracting(value -> value.objectId())
                .containsExactly(first.objectId(), middle.objectId(), last.objectId());
        assertThat(secondTerminal.findingObjectCount()).isZero();
    }

    @Test
    void resumesOneComparisonAcrossReplicasWithoutDuplicateClassifications() {
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items = List.of(
                item(1, "one", 3_600), item(2, "two", 3_600),
                item(3, "three", 3_600), item(4, "four", 3_600));
        items.forEach(value -> insertExpected(
                value, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN));
        installCompletedCycle(items, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var firstReplica = controlPlane(1);
        var first = firstReplica.compareNextPage(AUTHORITY);

        var secondReplica = controlPlane(1);
        var terminal = complete(secondReplica).getLast();

        assertThat(first.comparisonId()).isEqualTo(terminal.comparisonId());
        assertThat(terminal.classifiedObjectCount()).isEqualTo(4);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ?
                """, Integer.class, terminal.comparisonId())).isEqualTo(4);
    }

    @Test
    void frozenExpectedSnapshotTamperRollsBackTheTerminalPageAndCursor() {
        var first = item(1, "one", 3_600);
        var second = item(2, "two", 3_600);
        insertExpected(first, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        insertExpected(second, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        installCompletedCycle(List.of(first, second), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(1);
        var staged = controlPlane.compareNextPage(AUTHORITY);
        String originalCommitment = database.jdbc().queryForObject("""
                SELECT object_commitment
                FROM rg_test_suite_stability_observation_external_expected_snapshots
                WHERE comparison_id = ? AND object_id = ?
                """, String.class, staged.comparisonId(), first.objectId());
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_expected_snapshots
                SET object_commitment = ?
                WHERE comparison_id = ? AND object_id = ?
                """, fingerprint("tampered-expected"), staged.comparisonId(), first.objectId());

        assertThatThrownBy(() -> controlPlane.compareNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected inventory item");
        assertProgressUnchanged(staged.comparisonId(), first.objectId(), 1);

        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_expected_snapshots
                SET object_commitment = ?
                WHERE comparison_id = ? AND object_id = ?
                """, originalCommitment, staged.comparisonId(), first.objectId());
        assertThat(controlPlane.compareNextPage(AUTHORITY).status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .ComparisonStatus.COMPLETED);
    }

    @Test
    void remoteSnapshotTamperFailsBeforeTheNextClassificationCommits() {
        var first = item(1, "one", 3_600);
        var second = item(2, "two", 3_600);
        insertExpected(first, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        insertExpected(second, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        String cycleId = installCompletedCycle(
                List.of(first, second), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(1);
        var staged = controlPlane.compareNextPage(AUTHORITY);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_inventory_items
                SET object_commitment = ?
                WHERE cycle_id = ? AND object_id = ?
                """, fingerprint("tampered-remote"), cycleId, second.objectId());

        assertThatThrownBy(() -> controlPlane.compareNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staged item record fingerprint");
        assertProgressUnchanged(staged.comparisonId(), first.objectId(), 1);
    }

    @Test
    void recordFingerprintRejectsCounterDriftBeforeAnotherPageMutatesState() {
        var first = item(1, "one", 3_600);
        var second = item(2, "two", 3_600);
        insertExpected(first, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        insertExpected(second, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        installCompletedCycle(List.of(first, second), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(1);
        var staged = controlPlane.compareNextPage(AUTHORITY);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_comparisons
                SET matched_count = matched_count + 1
                WHERE comparison_id = ?
                """, staged.comparisonId());

        assertThatThrownBy(() -> controlPlane.compareNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("comparison state is corrupt");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ?
                """, Integer.class, staged.comparisonId())).isEqualTo(1);
    }

    @Test
    void terminalReplayRejectsADeletedHistoricalClassification() {
        var first = item(1, "one", 3_600);
        var second = item(2, "two", 3_600);
        insertExpected(first, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        insertExpected(second, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        installCompletedCycle(List.of(first, second), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(1);
        var staged = controlPlane.compareNextPage(AUTHORITY);
        database.jdbc().update("""
                DELETE FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ? AND object_id = ?
                """, staged.comparisonId(), first.objectId());

        assertThatThrownBy(() -> controlPlane.compareNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classification replay failed");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ?
                """, Integer.class, staged.comparisonId())).isZero();
        assertThat(database.jdbc().queryForObject("""
                SELECT next_after_object_id
                FROM rg_test_suite_stability_observation_external_comparisons
                WHERE comparison_id = ?
                """, String.class, staged.comparisonId())).isEqualTo(first.objectId());
    }

    @Test
    void semanticReplayRejectsASelfConsistentButIncorrectOutcome() {
        var first = item(1, "one", 3_600);
        var second = item(2, "two", 3_600);
        insertExpected(first, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        insertExpected(second, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        installCompletedCycle(List.of(first, second), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(1);
        var staged = controlPlane.compareNextPage(AUTHORITY);
        rewriteFirstClassificationAsSelfConsistentConflict(staged.comparisonId(), first.objectId());

        assertThatThrownBy(() -> controlPlane.compareNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("semantic replay failed");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ?
                """, Integer.class, staged.comparisonId())).isEqualTo(1);
    }

    @Test
    void activeComparisonCannotBeExportedAsGovernanceEvidence() {
        var first = item(1, "one", 3_600);
        var second = item(2, "two", 3_600);
        insertExpected(first, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        insertExpected(second, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        installCompletedCycle(List.of(first, second), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(1);
        var staged = controlPlane.compareNextPage(AUTHORITY);

        assertThatThrownBy(() -> controlPlane.classifications(staged.comparisonId(), "", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not complete");
    }

    @Test
    void completedClassificationExportRejectsTamperedCommitMetadata() {
        var item = item(1, "one", 3_600);
        insertExpected(item, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        installCompletedCycle(List.of(item), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(10);
        var completed = controlPlane.compareNextPage(AUTHORITY);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_classifications
                SET committed_at = DATEADD('MILLISECOND', 1, committed_at)
                WHERE comparison_id = ?
                """, completed.comparisonId());

        assertThatThrownBy(() -> controlPlane.classifications(
                completed.comparisonId(), "", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classification row fingerprint");
    }

    @Test
    void operationalReadRejectsStructurallyValidComparisonAuthorityTamper() {
        var item = item(1, "one", 3_600);
        insertExpected(item, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        installCompletedCycle(List.of(item), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(10);
        controlPlane.compareNextPage(AUTHORITY);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_comparison_authorities
                SET revision = revision + 1
                WHERE authority_id = ?
                """, AUTHORITY);

        assertThatThrownBy(() -> controlPlane.operationalSnapshot(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authority record fingerprint");
    }

    @Test
    void emptyExpectedAndRemoteSnapshotsCompleteOnDomainSeparatedEmptyRoots() {
        installCompletedCycle(List.of(), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(5);

        var terminal = controlPlane.compareNextPage(AUTHORITY);

        assertThat(terminal.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .ComparisonStatus.COMPLETED);
        assertThat(terminal.pageObjectCount()).isZero();
        assertThat(terminal.classifiedObjectCount()).isZero();
        assertThat(database.jdbc().queryForMap("""
                SELECT expected_root, classification_root
                FROM rg_test_suite_stability_observation_external_comparisons
                WHERE comparison_id = ?
                """, terminal.comparisonId()))
                .containsEntry("EXPECTED_ROOT",
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                                .EMPTY_EXPECTED_ROOT)
                .containsEntry("CLASSIFICATION_ROOT",
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                                .EMPTY_CLASSIFICATION_ROOT);
        assertThat(controlPlane.classifications(terminal.comparisonId(), "", 10)).isEmpty();
    }

    @Test
    void committedComparisonSurvivesOuterCallerRollback() {
        var item = item(1, "one", 3_600);
        insertExpected(item, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        installCompletedCycle(List.of(item), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(10);
        TransactionTemplate outer = new TransactionTemplate(database.transactionManager());

        String comparisonId = outer.execute(status -> {
            var terminal = controlPlane.compareNextPage(AUTHORITY);
            status.setRollbackOnly();
            return terminal.comparisonId();
        });

        assertThat(database.jdbc().queryForObject("""
                SELECT comparison_status
                FROM rg_test_suite_stability_observation_external_comparisons
                WHERE comparison_id = ?
                """, String.class, comparisonId)).isEqualTo("COMPLETED");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ?
                """, Integer.class, comparisonId)).isEqualTo(1);
    }

    @Test
    void transactionDatabaseTimeNeverRegressesAfterWaitingForTheAuthorityRowLock()
            throws Exception {
        var item = item(1, "one", 3_600);
        insertExpected(item, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        installCompletedCycle(List.of(item), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN);
        var controlPlane = controlPlane(10);
        Instant initialUpdatedAt = Instant.now().minusSeconds(10);
        String initialAuthorityFingerprint =
                ExternalArchiveComparisonStateIntegrity.authorityFingerprint(
                        objectMapper, AUTHORITY, "", "", 0, initialUpdatedAt);
        database.jdbc().update("""
                INSERT INTO
                    rg_test_suite_stability_observation_external_comparison_authorities (
                    authority_id, active_comparison_id, last_completed_comparison_id,
                    revision, updated_at, record_fingerprint
                ) VALUES (?, '', '', 0, ?, ?)
                """, AUTHORITY, Timestamp.from(initialUpdatedAt),
                initialAuthorityFingerprint);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocker = pool.submit(() -> {
                TransactionTemplate transaction = new TransactionTemplate(
                        database.transactionManager());
                return transaction.execute(status -> {
                    database.jdbc().queryForObject("""
                            SELECT revision
                            FROM rg_test_suite_stability_observation_external_comparison_authorities
                            WHERE authority_id = ?
                            FOR UPDATE
                            """, Long.class, AUTHORITY);
                    locked.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out releasing authority lock");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Authority lock wait interrupted", interrupted);
                    }
                    Instant marker = Instant.now();
                    String markerFingerprint =
                            ExternalArchiveComparisonStateIntegrity.authorityFingerprint(
                                    objectMapper, AUTHORITY, "", "", 0, marker);
                    database.jdbc().update("""
                            UPDATE
                                rg_test_suite_stability_observation_external_comparison_authorities
                            SET updated_at = ?, record_fingerprint = ?
                            WHERE authority_id = ?
                            """, Timestamp.from(marker), markerFingerprint, AUTHORITY);
                    return marker;
                });
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var waiter = pool.submit(() -> controlPlane.compareNextPage(AUTHORITY));
            Thread.sleep(200);
            release.countDown();

            Instant marker = blocker.get(5, TimeUnit.SECONDS);
            assertThat(waiter.get(5, TimeUnit.SECONDS).status()).isEqualTo(
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .ComparisonStatus.COMPLETED);
            Instant updatedAt = database.jdbc().queryForObject("""
                    SELECT updated_at
                    FROM rg_test_suite_stability_observation_external_comparison_authorities
                    WHERE authority_id = ?
                    """, Timestamp.class, AUTHORITY).toInstant();
            assertThat(updatedAt).isAfter(marker);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void settingsIdentifiersAndPublicBoundaryRejectUnsafeShapes() {
        assertThatThrownBy(() -> new
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .Settings(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .Settings(501)).isInstanceOf(IllegalArgumentException.class);
        var controlPlane = controlPlane(10);
        assertThatThrownBy(() -> controlPlane.compareNextPage("../archive"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controlPlane.compareNextPage("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controlPlane.classifications(
                UUID.randomUUID().toString(), "bad-cursor", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Arrays.stream(
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane.class
                        .getMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .noneMatch(name -> name.matches(
                        "(?i).*(delete|purge|overwrite|shorten|remediate|releaseHold).*"));
    }

    private DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
            controlPlane(int maximumItems) {
        var value = new
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                new DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Settings(maximumItems));
        value.init();
        return value;
    }

    private List<DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
            .ComparisonPage> complete(
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    controlPlane) {
        ArrayList<DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .ComparisonPage> pages = new ArrayList<>();
        for (int attempt = 0; attempt < 20; attempt++) {
            var page = controlPlane.compareNextPage(AUTHORITY);
            pages.add(page);
            if (page.status()
                    != DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .ComparisonStatus.STAGED) {
                return List.copyOf(pages);
            }
        }
        throw new AssertionError("Comparison did not complete within its bounded test pages");
    }

    private void assertProgressUnchanged(String comparisonId, String cursor, int rows) {
        assertThat(database.jdbc().queryForObject("""
                SELECT next_after_object_id
                FROM rg_test_suite_stability_observation_external_comparisons
                WHERE comparison_id = ?
                """, String.class, comparisonId)).isEqualTo(cursor);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ?
                """, Integer.class, comparisonId)).isEqualTo(rows);
    }

    private void rewriteFirstClassificationAsSelfConsistentConflict(
            String comparisonId,
            String objectId) {
        var material = database.jdbc().queryForObject("""
                SELECT comparison_id, cycle_id, authority_id, object_id,
                       expected_item_fingerprint, observed_item_fingerprint,
                       expected_object_commitment, observed_object_commitment,
                       expected_topology_fingerprint, observed_topology_fingerprint,
                       expected_retain_until, observed_retain_until
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ? AND object_id = ?
                """, (result, row) -> new ClassificationMaterialFixture(
                "bloge.testSuiteStabilityObservationExternalArchiveClassification.v1",
                result.getString("comparison_id"), result.getString("cycle_id"),
                result.getString("authority_id"), result.getString("object_id"),
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Outcome.MATERIAL_CONFLICT,
                result.getString("expected_item_fingerprint"),
                result.getString("observed_item_fingerprint"),
                result.getString("expected_object_commitment"),
                result.getString("observed_object_commitment"),
                result.getString("expected_topology_fingerprint"),
                result.getString("observed_topology_fingerprint"),
                result.getTimestamp("expected_retain_until").toInstant(),
                result.getTimestamp("observed_retain_until").toInstant()),
                comparisonId, objectId);
        String classificationFingerprint = ProtocolFingerprint.of(objectMapper, material);
        var classification = new
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .Classification(material.comparisonId(), material.cycleId(),
                material.authorityId(), material.objectId(), material.outcome(),
                material.expectedItemFingerprint(), material.observedItemFingerprint(),
                material.expectedObjectCommitment(), material.observedObjectCommitment(),
                material.expectedTopologyFingerprint(), material.observedTopologyFingerprint(),
                material.expectedRetainUntil(), material.observedRetainUntil(),
                classificationFingerprint);
        Long pageSequence = database.jdbc().queryForObject("""
                SELECT page_sequence
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ? AND object_id = ?
                """, Long.class, comparisonId, objectId);
        Timestamp committedAt = database.jdbc().queryForObject("""
                SELECT committed_at
                FROM rg_test_suite_stability_observation_external_classifications
                WHERE comparison_id = ? AND object_id = ?
                """, Timestamp.class, comparisonId, objectId);
        String rowFingerprint =
                ExternalArchiveComparisonStateIntegrity.classificationRowFingerprint(
                        objectMapper, classification, pageSequence,
                        committedAt.toInstant());
        String root = ProtocolFingerprint.of(objectMapper, new ClassificationRootLinkFixture(
                "bloge.testSuiteStabilityObservationExternalArchiveClassificationRootLink.v1",
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .EMPTY_CLASSIFICATION_ROOT,
                classificationFingerprint));
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_classifications
                SET outcome = 'MATERIAL_CONFLICT', classification_fingerprint = ?,
                    record_fingerprint = ?
                WHERE comparison_id = ? AND object_id = ?
                """, classificationFingerprint, rowFingerprint, comparisonId, objectId);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_comparisons
                SET matched_count = 0, material_conflict_count = 1, classification_root = ?
                WHERE comparison_id = ?
                """, root, comparisonId);
        ComparisonStateMaterialFixture state = database.jdbc().queryForObject("""
                SELECT comparison_id, cycle_id, authority_id, comparison_status,
                       trust_domain, archive_set_id, failure_domain, remote_snapshot_id,
                       remote_object_count, remote_root, expected_object_count, expected_root,
                       next_after_object_id, next_page_sequence, classified_object_count,
                       matched_count, missing_remote_count, unexpected_remote_count,
                       material_conflict_count, retention_shortened_count, unknown_count,
                       classification_root, revision, started_at, completed_at, updated_at
                FROM rg_test_suite_stability_observation_external_comparisons
                WHERE comparison_id = ?
                """, (result, row) -> new ComparisonStateMaterialFixture(
                "bloge.testSuiteStabilityObservationExternalArchiveComparisonState.v1",
                result.getString("comparison_id"), result.getString("cycle_id"),
                result.getString("authority_id"), result.getString("comparison_status"),
                result.getString("trust_domain"), result.getString("archive_set_id"),
                result.getString("failure_domain"), result.getString("remote_snapshot_id"),
                result.getLong("remote_object_count"), result.getString("remote_root"),
                result.getLong("expected_object_count"), result.getString("expected_root"),
                result.getString("next_after_object_id"), result.getLong("next_page_sequence"),
                result.getLong("classified_object_count"), result.getLong("matched_count"),
                result.getLong("missing_remote_count"),
                result.getLong("unexpected_remote_count"),
                result.getLong("material_conflict_count"),
                result.getLong("retention_shortened_count"), result.getLong("unknown_count"),
                result.getString("classification_root"), result.getLong("revision"),
                result.getTimestamp("started_at").toInstant(), null,
                result.getTimestamp("updated_at").toInstant()), comparisonId);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_comparisons
                SET record_fingerprint = ?
                WHERE comparison_id = ?
                """, ProtocolFingerprint.of(objectMapper, state), comparisonId);
    }

    private void initializeInventoryTables() {
        TestSuiteStabilityObservationExternalArchiveInventoryAuthority authority =
                new TestSuiteStabilityObservationExternalArchiveInventoryAuthority() {
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
                        throw new UnsupportedOperationException();
                    }
                };
        new DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper, authority,
                new DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .Settings("fixture-replica", Duration.ofSeconds(30), 100)).init();
    }

    private void initializeExpectedObjectTable() {
        database.jdbc().execute("""
                CREATE TABLE
                    rg_test_suite_stability_observation_external_archive_objects (
                    authority_id VARCHAR(255) NOT NULL,
                    object_id VARCHAR(255) NOT NULL,
                    trust_domain VARCHAR(255) NOT NULL,
                    archive_set_id VARCHAR(255) NOT NULL,
                    failure_domain VARCHAR(255) NOT NULL,
                    retirement_id VARCHAR(255) NOT NULL,
                    retirement_fingerprint VARCHAR(71) NOT NULL,
                    segment_id VARCHAR(255) NOT NULL,
                    segment_fingerprint VARCHAR(71) NOT NULL,
                    retention_policy_fingerprint VARCHAR(71) NOT NULL,
                    retain_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    stored_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    object_commitment VARCHAR(71) NOT NULL,
                    expected_item_fingerprint VARCHAR(71) NOT NULL,
                    receipt_fingerprint VARCHAR(71) NOT NULL,
                    receipt_set_id VARCHAR(255) NOT NULL,
                    receipt_set_fingerprint VARCHAR(71) NOT NULL,
                    indexed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (authority_id, object_id)
                )
                """);
    }

    private void insertExpected(
            TestSuiteStabilityObservationExternalArchiveInventoryItem item,
            String trustDomain,
            String archiveSetId,
            String failureDomain) {
        database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_archive_objects (
                    authority_id, object_id, trust_domain, archive_set_id, failure_domain,
                    retirement_id, retirement_fingerprint, segment_id, segment_fingerprint,
                    retention_policy_fingerprint, retain_until, stored_at, object_commitment,
                    expected_item_fingerprint, receipt_fingerprint, receipt_set_id,
                    receipt_set_fingerprint, indexed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, AUTHORITY, item.objectId(), trustDomain, archiveSetId, failureDomain,
                item.retirementId(), item.retirementFingerprint(), item.segmentId(),
                item.segmentFingerprint(), item.retentionPolicyFingerprint(),
                Timestamp.from(item.retainUntil()), Timestamp.from(item.storedAt()),
                item.objectCommitment(), item.itemFingerprint(),
                fingerprint("receipt-" + item.objectId()),
                "receipt-set-" + item.objectId().substring(item.objectId().length() - 12),
                fingerprint("receipt-set-" + item.objectId()), Timestamp.from(Instant.now()));
    }

    private String installCompletedCycle(
            List<TestSuiteStabilityObservationExternalArchiveInventoryItem> source,
            String trustDomain,
            String archiveSetId,
            String failureDomain) {
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items = source.stream()
                .sorted((left, right) -> left.objectId().compareTo(right.objectId()))
                .toList();
        String cycleId = UUID.randomUUID().toString();
        Instant snapshotAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        String root = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.root(
                objectMapper, items);
        String snapshotId = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.snapshotId(
                objectMapper, trustDomain, archiveSetId, AUTHORITY, failureDomain, snapshotAt,
                items.size(), root);
        Instant now = Instant.now();
        Integer authorities = database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_inventory_authorities
                WHERE authority_id = ?
                """, Integer.class, AUTHORITY);
        if (authorities != null && authorities == 1) {
            Long currentRevision = database.jdbc().queryForObject("""
                    SELECT revision
                    FROM rg_test_suite_stability_observation_external_inventory_authorities
                    WHERE authority_id = ?
                    """, Long.class, AUTHORITY);
            long revision = Math.addExact(currentRevision == null ? 0 : currentRevision, 1);
            String authorityFingerprint = ExternalArchiveInventoryStateIntegrity
                    .authorityFingerprint(objectMapper, AUTHORITY, "", "", 0, now, revision,
                            "", cycleId, now, now);
            database.jdbc().update("""
                    UPDATE rg_test_suite_stability_observation_external_inventory_authorities
                    SET lease_owner = '', lease_token = '', lease_epoch = 0, lease_until = ?,
                        active_cycle_id = '', last_completed_cycle_id = ?, last_success_at = ?,
                        revision = ?, updated_at = ?, record_fingerprint = ?
                    WHERE authority_id = ?
                    """, Timestamp.from(now), cycleId, Timestamp.from(now), revision,
                    Timestamp.from(now), authorityFingerprint, AUTHORITY);
        } else {
            long revision = 1;
            String authorityFingerprint = ExternalArchiveInventoryStateIntegrity
                    .authorityFingerprint(objectMapper, AUTHORITY, "", "", 0, now, revision,
                            "", cycleId, now, now);
            database.jdbc().update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_inventory_authorities (
                        authority_id, lease_owner, lease_token, lease_epoch, lease_until,
                        revision, active_cycle_id, last_completed_cycle_id,
                        last_success_at, updated_at, record_fingerprint
                    ) VALUES (?, '', '', 0, ?, ?, '', ?, ?, ?, ?)
                    """, AUTHORITY, Timestamp.from(now), revision, cycleId,
                    Timestamp.from(now), Timestamp.from(now), authorityFingerprint);
        }
        String lastObjectId = items.isEmpty() ? "" : items.getLast().objectId();
        long cycleRevision = 1;
        String cycleFingerprint = ExternalArchiveInventoryStateIntegrity.cycleFingerprint(
                objectMapper, cycleId, AUTHORITY, "COMPLETED", trustDomain, archiveSetId,
                failureDomain, snapshotId, snapshotAt, items.size(), root, "", 1,
                items.size(), root, lastObjectId, cycleRevision, snapshotAt, now, now);
        database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_inventory_cycles (
                    cycle_id, authority_id, cycle_status, trust_domain, archive_set_id,
                    failure_domain, snapshot_id, snapshot_at, snapshot_object_count,
                    snapshot_root, next_after_object_id, next_page_sequence,
                    accumulated_object_count, accumulated_root, last_object_id, revision,
                    started_at, completed_at, updated_at, record_fingerprint
                ) VALUES (?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, '', 1, ?, ?, ?, ?, ?, ?, ?, ?)
                """, cycleId, AUTHORITY, trustDomain, archiveSetId, failureDomain, snapshotId,
                Timestamp.from(snapshotAt), (long) items.size(), root, (long) items.size(), root,
                lastObjectId, cycleRevision, Timestamp.from(snapshotAt), Timestamp.from(now),
                Timestamp.from(now), cycleFingerprint);
        for (TestSuiteStabilityObservationExternalArchiveInventoryItem item : items) {
            String recordFingerprint =
                    ExternalArchiveInventoryStagingIntegrity.itemFingerprint(
                            objectMapper, cycleId, 0, item, now);
            database.jdbc().update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_inventory_items (
                        cycle_id, object_id, page_sequence, item_fingerprint, object_commitment,
                        retirement_id, retirement_fingerprint, segment_id, segment_fingerprint,
                        retention_policy_fingerprint, retain_until, stored_at, committed_at,
                        record_fingerprint
                    ) VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, cycleId, item.objectId(), item.itemFingerprint(), item.objectCommitment(),
                    item.retirementId(), item.retirementFingerprint(), item.segmentId(),
                    item.segmentFingerprint(), item.retentionPolicyFingerprint(),
                    Timestamp.from(item.retainUntil()), Timestamp.from(item.storedAt()),
                    Timestamp.from(now), recordFingerprint);
        }
        return cycleId;
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryItem item(
            int object,
            String variant,
            long retentionSeconds) {
        String objectId = "stability-observation-worm-" + "%064x".formatted(object);
        Instant storedAt = STORED_AT.plusSeconds(object);
        Instant retainUntil = storedAt.plusSeconds(retentionSeconds);
        var material = new TestSuiteStabilityObservationExternalArchiveInventoryItem.Material(
                TestSuiteStabilityObservationExternalArchiveInventoryItem.SCHEMA_VERSION,
                objectId, fingerprint("commit-" + variant),
                "stability-observation-retirement-" + hex("retirement-" + object),
                fingerprint("retirement-" + variant),
                "stability-observation-archive-" + hex("segment-" + object),
                fingerprint("segment-" + variant), fingerprint("policy-a"),
                retainUntil, storedAt);
        return fromMaterial(material);
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryItem withRetention(
            TestSuiteStabilityObservationExternalArchiveInventoryItem source,
            long retentionSeconds) {
        var material = source.material();
        return fromMaterial(new TestSuiteStabilityObservationExternalArchiveInventoryItem.Material(
                material.schemaVersion(), material.objectId(),
                fingerprint("shortened-" + retentionSeconds), material.retirementId(),
                material.retirementFingerprint(), material.segmentId(),
                material.segmentFingerprint(), material.retentionPolicyFingerprint(),
                material.storedAt().plusSeconds(retentionSeconds), material.storedAt()));
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryItem withSegmentFingerprint(
            TestSuiteStabilityObservationExternalArchiveInventoryItem source,
            String variant) {
        var material = source.material();
        return fromMaterial(new TestSuiteStabilityObservationExternalArchiveInventoryItem.Material(
                material.schemaVersion(), material.objectId(), material.objectCommitment(),
                material.retirementId(), material.retirementFingerprint(), material.segmentId(),
                fingerprint("segment-" + variant), material.retentionPolicyFingerprint(),
                material.retainUntil(), material.storedAt()));
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryItem fromMaterial(
            TestSuiteStabilityObservationExternalArchiveInventoryItem.Material material) {
        return new TestSuiteStabilityObservationExternalArchiveInventoryItem(
                material.schemaVersion(), ProtocolFingerprint.of(objectMapper, material),
                material.objectId(), material.objectCommitment(), material.retirementId(),
                material.retirementFingerprint(), material.segmentId(),
                material.segmentFingerprint(), material.retentionPolicyFingerprint(),
                material.retainUntil(), material.storedAt());
    }

    private static String fingerprint(String value) {
        return ProtocolFingerprint.ofText(value);
    }

    private static String hex(String value) {
        return fingerprint(value).substring("sha256:".length());
    }

    private record ClassificationMaterialFixture(
            String schemaVersion,
            String comparisonId,
            String cycleId,
            String authorityId,
            String objectId,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome outcome,
            String expectedItemFingerprint,
            String observedItemFingerprint,
            String expectedObjectCommitment,
            String observedObjectCommitment,
            String expectedTopologyFingerprint,
            String observedTopologyFingerprint,
            Instant expectedRetainUntil,
            Instant observedRetainUntil) {
    }

    private record ClassificationRootLinkFixture(
            String schemaVersion,
            String previousRoot,
            String classificationFingerprint) {
    }

    private record ComparisonStateMaterialFixture(
            String schemaVersion,
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
            Instant updatedAt) {
    }
}
