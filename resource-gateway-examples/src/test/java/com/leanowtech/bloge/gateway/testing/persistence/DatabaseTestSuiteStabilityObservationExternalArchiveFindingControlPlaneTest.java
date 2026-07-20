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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlaneTest {
    private static final String AUTHORITY = "archive-a";
    private static final String TRUST_DOMAIN = "archive.example";
    private static final String ARCHIVE_SET = "archive-set-a";
    private static final String FAILURE_DOMAIN = "region-a";
    private static final Instant STORED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private ObjectMapper objectMapper;
    private TestRuntimeDatabase database;
    private DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
            classificationControlPlane;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:external-findings-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "", 6));
        initializeInventoryTables();
        initializeExpectedObjectTable();
        classificationControlPlane = new
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                new DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Settings(2));
        classificationControlPlane.init();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void projectsEveryLifecycleTransitionAcrossStrictlyOrderedComparisons() {
        var missingThenMatched = item(1, "a", 3_600);
        var alwaysMatched = item(2, "b", 3_600);
        var unexpectedThenMatchedThenMissing = item(3, "c", 3_600);
        insertExpected(missingThenMatched);
        insertExpected(alwaysMatched);
        String firstComparison = completedComparison(
                List.of(alwaysMatched, unexpectedThenMatchedThenMissing));
        var first = completeProjection(findingControlPlane(1));

        insertExpected(unexpectedThenMatchedThenMissing);
        String secondComparison = completedComparison(
                List.of(alwaysMatched, unexpectedThenMatchedThenMissing));
        var second = completeProjection(findingControlPlane(2));

        String thirdComparison = completedComparison(
                List.of(missingThenMatched, alwaysMatched));
        var third = completeProjection(findingControlPlane(1));

        assertThat(List.of(first.comparisonId(), second.comparisonId(), third.comparisonId()))
                .containsExactly(firstComparison, secondComparison, thirdComparison);
        List<Timestamp> starts = database.jdbc().queryForList("""
                SELECT comparison_started_at
                FROM rg_test_suite_stability_observation_external_finding_projections
                ORDER BY comparison_started_at
                """, Timestamp.class);
        assertThat(starts).hasSize(3);
        assertThat(starts.get(0)).isBefore(starts.get(1));
        assertThat(starts.get(1)).isBefore(starts.get(2));

        var controlPlane = findingControlPlane(10);
        assertThat(controlPlane.events(first.projectionId(), "", 10))
                .extracting(DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .FindingEvent::transition)
                .containsExactly(
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .Transition.OPENED,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .Transition.CONFIRMED,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .Transition.OPENED);
        assertThat(controlPlane.events(second.projectionId(), "", 10))
                .extracting(DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .FindingEvent::transition)
                .containsExactly(
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .Transition.OBSERVED,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .Transition.CONFIRMED,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .Transition.RESOLVED);
        assertThat(controlPlane.events(third.projectionId(), "", 10))
                .extracting(DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .FindingEvent::transition)
                .containsExactly(
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .Transition.RESOLVED,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .Transition.CONFIRMED,
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .Transition.REOPENED);

        assertThat(controlPlane.findings(AUTHORITY, "", 10)).satisfiesExactly(
                finding -> {
                    assertThat(finding.objectId()).isEqualTo(missingThenMatched.objectId());
                    assertThat(finding.status()).isEqualTo(
                            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                    .FindingStatus.RESOLVED);
                    assertThat(finding.occurrences()).isEqualTo(2);
                    assertThat(finding.episodes()).isEqualTo(1);
                    assertThat(finding.resolution()).isEqualTo(
                            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                    .Resolution.MATCHED_ON_RECHECK);
                },
                finding -> {
                    assertThat(finding.objectId())
                            .isEqualTo(unexpectedThenMatchedThenMissing.objectId());
                    assertThat(finding.status()).isEqualTo(
                            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                    .FindingStatus.OPEN);
                    assertThat(finding.occurrences()).isEqualTo(2);
                    assertThat(finding.episodes()).isEqualTo(2);
                    assertThat(finding.resolution()).isEqualTo(
                            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                    .Resolution.NONE);
                });
        assertThat(controlPlane.projectNextPage(AUTHORITY).status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .ProjectionStatus.CURRENT);
    }

    @Test
    void exposesVerifiedIdentityFreeFindingProgressBeforeDuringAndAfterCompletion() {
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> expected = List.of(
                item(1, "a", 3_600), item(2, "b", 3_600), item(3, "c", 3_600));
        expected.forEach(this::insertExpected);
        completedComparison(List.of());
        var controlPlane = findingControlPlane(1);

        var before = controlPlane.operationalSnapshot(AUTHORITY);
        var staged = controlPlane.projectNextPage(AUTHORITY);
        var active = controlPlane.operationalSnapshot(AUTHORITY);
        var terminal = completeProjection(controlPlane);
        var completed = controlPlane.operationalSnapshot(AUTHORITY);

        assertThat(before.initialized()).isFalse();
        assertThat(staged.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .ProjectionStatus.STAGED);
        assertThat(active.initialized()).isTrue();
        assertThat(active.activeProjection()).isTrue();
        assertThat(active.nextPageSequence()).isEqualTo(1);
        assertThat(active.processedClassificationCount()).isEqualTo(1);
        assertThat(active.actionableTransitionCount()).isEqualTo(1);
        assertThat(active.activeStartedAt()).isNotNull();
        assertThat(active.activeUpdatedAt()).isNotNull();
        assertThat(active.lastCompletedAt()).isNull();
        assertThat(completed.initialized()).isTrue();
        assertThat(completed.activeProjection()).isFalse();
        assertThat(completed.lastCompletedAt()).isNotNull();
        assertThat(completed.lastCompletedAt()).isEqualTo(
                database.jdbc().queryForObject("""
                        SELECT completed_at
                        FROM rg_test_suite_stability_observation_external_finding_projections
                        WHERE projection_id = ?
                        """, Timestamp.class, terminal.projectionId()).toInstant());
        assertThat(completed.toString()).doesNotContain(
                terminal.projectionId(), terminal.comparisonId(), expected.getFirst().objectId());
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_projections
                SET record_fingerprint = ?
                WHERE projection_id = ?
                """, fingerprint("tampered-operational-projection"), terminal.projectionId());
        assertThatThrownBy(() -> controlPlane.operationalSnapshot(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projection state is corrupt");
    }

    @Test
    void resumesAcrossReplicasAndHidesPartiallyAppliedFindingState() {
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> expected = List.of(
                item(1, "a", 3_600), item(2, "b", 3_600), item(3, "c", 3_600));
        expected.forEach(this::insertExpected);
        completedComparison(List.of());
        var firstReplica = findingControlPlane(1);

        var staged = firstReplica.projectNextPage(AUTHORITY);

        assertThat(staged.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .ProjectionStatus.STAGED);
        assertThatThrownBy(() -> firstReplica.findings(AUTHORITY, "", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active projection");
        assertThatThrownBy(() -> firstReplica.events(staged.projectionId(), "", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not complete");

        var terminal = completeProjection(findingControlPlane(1));

        assertThat(terminal.projectionId()).isEqualTo(staged.projectionId());
        assertThat(terminal.totalProcessed()).isEqualTo(3);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_finding_events
                WHERE projection_id = ?
                """, Integer.class, terminal.projectionId())).isEqualTo(3);
        assertThat(firstReplica.findings(AUTHORITY, "", 10)).hasSize(3);
    }

    @Test
    void consumesAnAccumulatedComparisonBacklogOldestFirst() {
        var object = item(1, "a", 3_600);
        insertExpected(object);
        String missingComparison = completedComparison(List.of());
        String matchedComparison = completedComparison(List.of(object));
        var controlPlane = findingControlPlane(10);

        var opened = completeProjection(controlPlane);
        var resolved = completeProjection(controlPlane);

        assertThat(opened.comparisonId()).isEqualTo(missingComparison);
        assertThat(resolved.comparisonId()).isEqualTo(matchedComparison);
        assertThat(controlPlane.findings(AUTHORITY, "", 10)).singleElement().satisfies(finding -> {
            assertThat(finding.status()).isEqualTo(
                    DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                            .FindingStatus.RESOLVED);
            assertThat(finding.occurrences()).isEqualTo(1);
            assertThat(finding.episodes()).isEqualTo(1);
        });
    }

    @Test
    void rejectsSourceTamperBeforeOpeningAProjection() {
        var missing = item(1, "a", 3_600);
        insertExpected(missing);
        String comparisonId = completedComparison(List.of());
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_classifications
                SET outcome = 'UNKNOWN'
                WHERE comparison_id = ?
                """, comparisonId);
        var controlPlane = findingControlPlane(10);

        assertThatThrownBy(() -> controlPlane.projectNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classification");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_finding_projections
                """, Integer.class)).isZero();
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_findings
                """, Integer.class)).isZero();
    }

    @Test
    void semanticOracleRejectsSelfConsistentButIncorrectSourceOutcome() {
        var matched = item(1, "a", 3_600);
        insertExpected(matched);
        String comparisonId = completedComparison(List.of(matched));
        rewriteMatchedClassificationAsSelfConsistentConflict(comparisonId, matched.objectId());
        var controlPlane = findingControlPlane(10);

        assertThatThrownBy(() -> controlPlane.projectNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("semantic classification drifted");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_finding_projections
                """, Integer.class)).isZero();
    }

    @Test
    void terminalReplayRejectsTamperedFrozenSnapshotAndRollsBackThePage() {
        var first = item(1, "a", 3_600);
        var second = item(2, "b", 3_600);
        insertExpected(first);
        completedComparison(List.of());
        completeProjection(findingControlPlane(10));
        insertExpected(second);
        completedComparison(List.of());
        var controlPlane = findingControlPlane(1);
        var staged = controlPlane.projectNextPage(AUTHORITY);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_snapshots
                SET record_fingerprint = ?
                WHERE projection_id = ? AND object_id = ?
                """, fingerprint("tampered-snapshot"), staged.projectionId(), first.objectId());

        assertThatThrownBy(() -> controlPlane.projectNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fingerprint");
        assertProjectionProgress(staged.projectionId(), first.objectId(), 1);
    }

    @Test
    void terminalReplayRejectsTamperedHistoricalEvent() {
        var first = item(1, "a", 3_600);
        var second = item(2, "b", 3_600);
        insertExpected(first);
        insertExpected(second);
        completedComparison(List.of());
        var controlPlane = findingControlPlane(1);
        var staged = controlPlane.projectNextPage(AUTHORITY);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_events
                SET event_fingerprint = ?
                WHERE projection_id = ? AND object_id = ?
                """, fingerprint("tampered-event"), staged.projectionId(), first.objectId());

        assertThatThrownBy(() -> controlPlane.projectNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event fingerprint");
        assertProjectionProgress(staged.projectionId(), first.objectId(), 1);
    }

    @Test
    void terminalReplayRejectsMissingHistoricalEvent() {
        var first = item(1, "a", 3_600);
        var second = item(2, "b", 3_600);
        insertExpected(first);
        insertExpected(second);
        completedComparison(List.of());
        var controlPlane = findingControlPlane(1);
        var staged = controlPlane.projectNextPage(AUTHORITY);
        database.jdbc().update("""
                DELETE FROM rg_test_suite_stability_observation_external_finding_events
                WHERE projection_id = ? AND object_id = ?
                """, staged.projectionId(), first.objectId());

        assertThatThrownBy(() -> controlPlane.projectNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event replay");
        assertProjectionProgress(staged.projectionId(), first.objectId(), 0);
    }

    @Test
    void projectionFingerprintRejectsCursorAndCounterDrift() {
        var first = item(1, "a", 3_600);
        var second = item(2, "b", 3_600);
        insertExpected(first);
        insertExpected(second);
        completedComparison(List.of());
        var controlPlane = findingControlPlane(1);
        var staged = controlPlane.projectNextPage(AUTHORITY);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_projections
                SET processed_classification_count = 99
                WHERE projection_id = ?
                """, staged.projectionId());

        assertThatThrownBy(() -> controlPlane.projectNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projection state");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_finding_events
                WHERE projection_id = ?
                """, Integer.class, staged.projectionId())).isEqualTo(1);
    }

    @Test
    void semanticReplayRejectsSelfConsistentButIncorrectTransition() {
        var first = item(1, "a", 3_600);
        var second = item(2, "b", 3_600);
        insertExpected(first);
        insertExpected(second);
        completedComparison(List.of());
        var controlPlane = findingControlPlane(1);
        var staged = controlPlane.projectNextPage(AUTHORITY);
        rewriteOpenedEventAsSelfConsistentObserved(staged, first.objectId());

        assertThatThrownBy(() -> controlPlane.projectNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transition semantic replay failed");
        assertProjectionProgress(staged.projectionId(), first.objectId(), 1);
    }

    @Test
    void terminalSemanticReplayRejectsTamperedCurrentFinding() {
        var first = item(1, "a", 3_600);
        var second = item(2, "b", 3_600);
        insertExpected(first);
        insertExpected(second);
        completedComparison(List.of());
        var controlPlane = findingControlPlane(1);
        var staged = controlPlane.projectNextPage(AUTHORITY);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_findings
                SET occurrence_count = 99
                WHERE authority_id = ? AND object_id = ?
                """, AUTHORITY, first.objectId());

        assertThatThrownBy(() -> controlPlane.projectNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fingerprint");
        assertProjectionProgress(staged.projectionId(), first.objectId(), 1);
    }

    @Test
    void completedEventExportRejectsDeletedHistoricalEvidence() {
        var first = item(1, "a", 3_600);
        var second = item(2, "b", 3_600);
        insertExpected(first);
        insertExpected(second);
        completedComparison(List.of());
        var controlPlane = findingControlPlane(10);
        var terminal = completeProjection(controlPlane);
        database.jdbc().update("""
                DELETE FROM rg_test_suite_stability_observation_external_finding_events
                WHERE projection_id = ? AND object_id = ?
                """, terminal.projectionId(), first.objectId());

        assertThatThrownBy(() -> controlPlane.events(
                terminal.projectionId(), second.objectId(), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("export replay");
    }

    @Test
    void emptyComparisonCompletesAndCommittedProjectionSurvivesOuterRollback() {
        completedComparison(List.of());
        var controlPlane = findingControlPlane(10);
        TransactionTemplate outer = new TransactionTemplate(database.transactionManager());
        String projectionId = outer.execute(status -> {
            var page = controlPlane.projectNextPage(AUTHORITY);
            status.setRollbackOnly();
            return page.projectionId();
        });

        assertThat(database.jdbc().queryForObject("""
                SELECT projection_status
                FROM rg_test_suite_stability_observation_external_finding_projections
                WHERE projection_id = ?
                """, String.class, projectionId)).isEqualTo("COMPLETED");
        assertThat(controlPlane.events(projectionId, "", 10)).isEmpty();
        assertThat(controlPlane.findings(AUTHORITY, "", 10)).isEmpty();
    }

    @Test
    void stateFingerprintsAndPublicBoundaryFailClosed() {
        var missing = item(1, "a", 3_600);
        insertExpected(missing);
        completedComparison(List.of());
        var controlPlane = findingControlPlane(1);
        var terminal = completeProjection(controlPlane);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_authorities
                SET revision = revision + 1
                WHERE authority_id = ?
                """, AUTHORITY);

        assertThatThrownBy(() -> controlPlane.findings(AUTHORITY, "", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authority state");
        assertThat(controlPlane.events(terminal.projectionId(), "", 10)).hasSize(1);
        assertThatThrownBy(() -> new
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                .Settings(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                .Settings(501)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controlPlane.projectNextPage("../archive"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controlPlane.events(
                terminal.projectionId(), "bad-cursor", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Arrays.stream(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.class
                        .getMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .noneMatch(name -> name.matches(
                        "(?i).*(delete|purge|overwrite|shorten|remediate|releaseHold).*"));
    }

    private void assertProjectionProgress(String projectionId, String cursor, int events) {
        assertThat(database.jdbc().queryForObject("""
                SELECT next_after_object_id
                FROM rg_test_suite_stability_observation_external_finding_projections
                WHERE projection_id = ?
                """, String.class, projectionId)).isEqualTo(cursor);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_finding_events
                WHERE projection_id = ?
                """, Integer.class, projectionId)).isEqualTo(events);
    }

    private void rewriteMatchedClassificationAsSelfConsistentConflict(
            String comparisonId,
            String objectId) {
        ClassificationMaterialFixture classification = database.jdbc().queryForObject("""
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
        String classificationFingerprint = ProtocolFingerprint.of(objectMapper, classification);
        var storedClassification = new
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .Classification(classification.comparisonId(), classification.cycleId(),
                classification.authorityId(), classification.objectId(),
                classification.outcome(), classification.expectedItemFingerprint(),
                classification.observedItemFingerprint(),
                classification.expectedObjectCommitment(),
                classification.observedObjectCommitment(),
                classification.expectedTopologyFingerprint(),
                classification.observedTopologyFingerprint(),
                classification.expectedRetainUntil(), classification.observedRetainUntil(),
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
                        objectMapper, storedClassification, pageSequence,
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
                result.getTimestamp("started_at").toInstant(),
                result.getTimestamp("completed_at").toInstant(),
                result.getTimestamp("updated_at").toInstant()), comparisonId);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_comparisons
                SET record_fingerprint = ?
                WHERE comparison_id = ?
                """, ProtocolFingerprint.of(objectMapper, state), comparisonId);
    }

    private void rewriteOpenedEventAsSelfConsistentObserved(
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.ProjectionPage
                    projection,
            String objectId) {
        FindingEventMaterialFixture event = database.jdbc().queryForObject("""
                SELECT projection_id, comparison_id, authority_id, object_id,
                       classification_outcome, classification_fingerprint,
                       previous_finding_fingerprint, resulting_finding_fingerprint,
                       resulting_finding_version, occurred_at
                FROM rg_test_suite_stability_observation_external_finding_events
                WHERE projection_id = ? AND object_id = ?
                """, (result, row) -> new FindingEventMaterialFixture(
                "bloge.testSuiteStabilityObservationExternalArchiveFindingEvent.v1",
                result.getString("projection_id"), result.getString("comparison_id"),
                result.getString("authority_id"), result.getString("object_id"),
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Outcome.valueOf(result.getString("classification_outcome")),
                result.getString("classification_fingerprint"),
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .Transition.OBSERVED,
                result.getString("previous_finding_fingerprint"),
                result.getString("resulting_finding_fingerprint"),
                result.getLong("resulting_finding_version"),
                result.getTimestamp("occurred_at").toInstant()),
                projection.projectionId(), objectId);
        String eventFingerprint = ProtocolFingerprint.of(objectMapper, event);
        String eventRoot = ProtocolFingerprint.of(objectMapper, new FindingEventRootLinkFixture(
                "bloge.testSuiteStabilityObservationExternalArchiveFindingEventRootLink.v1",
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .EMPTY_EVENT_ROOT,
                eventFingerprint));
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_events
                SET transition = 'OBSERVED', event_fingerprint = ?
                WHERE projection_id = ? AND object_id = ?
                """, eventFingerprint, projection.projectionId(), objectId);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_projections
                SET opened_count = 0, observed_count = 1, event_root = ?
                WHERE projection_id = ?
                """, eventRoot, projection.projectionId());
        FindingProjectionMaterialFixture state = database.jdbc().queryForObject("""
                SELECT projection_id, comparison_id, authority_id, projection_status,
                       comparison_started_at, comparison_completed_at,
                       source_classification_count, source_classification_root,
                       snapshot_finding_count, snapshot_root, next_after_object_id,
                       next_page_sequence, processed_classification_count, opened_count,
                       observed_count, reopened_count, resolved_count, confirmed_count,
                       event_root, revision, started_at, completed_at, updated_at
                FROM rg_test_suite_stability_observation_external_finding_projections
                WHERE projection_id = ?
                """, (result, row) -> new FindingProjectionMaterialFixture(
                "bloge.testSuiteStabilityObservationExternalArchiveFindingProjection.v1",
                result.getString("projection_id"), result.getString("comparison_id"),
                result.getString("authority_id"), result.getString("projection_status"),
                result.getTimestamp("comparison_started_at").toInstant(),
                result.getTimestamp("comparison_completed_at").toInstant(),
                result.getLong("source_classification_count"),
                result.getString("source_classification_root"),
                result.getLong("snapshot_finding_count"), result.getString("snapshot_root"),
                result.getString("next_after_object_id"),
                result.getLong("next_page_sequence"),
                result.getLong("processed_classification_count"),
                result.getLong("opened_count"), result.getLong("observed_count"),
                result.getLong("reopened_count"), result.getLong("resolved_count"),
                result.getLong("confirmed_count"), result.getString("event_root"),
                result.getLong("revision"), result.getTimestamp("started_at").toInstant(),
                null, result.getTimestamp("updated_at").toInstant()), projection.projectionId());
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_projections
                SET record_fingerprint = ?
                WHERE projection_id = ?
                """, ProtocolFingerprint.of(objectMapper, state), projection.projectionId());
    }

    private DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
            findingControlPlane(int maximumItems) {
        var value = new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .Settings(maximumItems));
        value.init();
        return value;
    }

    private DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.ProjectionPage
            completeProjection(
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane controlPlane) {
        for (int attempt = 0; attempt < 30; attempt++) {
            var page = controlPlane.projectNextPage(AUTHORITY);
            if (page.status()
                    != DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                    .ProjectionStatus.STAGED) {
                assertThat(page.status()).isEqualTo(
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .ProjectionStatus.COMPLETED);
                return page;
            }
        }
        throw new AssertionError("Finding projection did not complete within bounded test pages");
    }

    private String completedComparison(
            List<TestSuiteStabilityObservationExternalArchiveInventoryItem> observed) {
        installCompletedCycle(observed);
        ArrayList<DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .ComparisonPage> pages = new ArrayList<>();
        for (int attempt = 0; attempt < 30; attempt++) {
            var page = classificationControlPlane.compareNextPage(AUTHORITY);
            pages.add(page);
            if (page.status()
                    != DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .ComparisonStatus.STAGED) {
                assertThat(page.status()).isEqualTo(
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                                .ComparisonStatus.COMPLETED);
                return page.comparisonId();
            }
        }
        throw new AssertionError("Comparison did not complete within bounded test pages");
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

    private void insertExpected(TestSuiteStabilityObservationExternalArchiveInventoryItem item) {
        database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_archive_objects (
                    authority_id, object_id, trust_domain, archive_set_id, failure_domain,
                    retirement_id, retirement_fingerprint, segment_id, segment_fingerprint,
                    retention_policy_fingerprint, retain_until, stored_at, object_commitment,
                    expected_item_fingerprint, receipt_fingerprint, receipt_set_id,
                    receipt_set_fingerprint, indexed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, AUTHORITY, item.objectId(), TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN,
                item.retirementId(), item.retirementFingerprint(), item.segmentId(),
                item.segmentFingerprint(), item.retentionPolicyFingerprint(),
                Timestamp.from(item.retainUntil()), Timestamp.from(item.storedAt()),
                item.objectCommitment(), item.itemFingerprint(),
                fingerprint("receipt-" + item.objectId()),
                "receipt-set-" + item.objectId().substring(item.objectId().length() - 12),
                fingerprint("receipt-set-" + item.objectId()), Timestamp.from(Instant.now()));
    }

    private String installCompletedCycle(
            List<TestSuiteStabilityObservationExternalArchiveInventoryItem> source) {
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items = source.stream()
                .sorted((left, right) -> left.objectId().compareTo(right.objectId()))
                .toList();
        String cycleId = UUID.randomUUID().toString();
        Instant snapshotAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        String root = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.root(
                objectMapper, items);
        String snapshotId = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.snapshotId(
                objectMapper, TRUST_DOMAIN, ARCHIVE_SET, AUTHORITY, FAILURE_DOMAIN, snapshotAt,
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
                objectMapper, cycleId, AUTHORITY, "COMPLETED", TRUST_DOMAIN, ARCHIVE_SET,
                FAILURE_DOMAIN, snapshotId, snapshotAt, items.size(), root, "", 1,
                items.size(), root, lastObjectId, cycleRevision, snapshotAt, now, now);
        database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_inventory_cycles (
                    cycle_id, authority_id, cycle_status, trust_domain, archive_set_id,
                    failure_domain, snapshot_id, snapshot_at, snapshot_object_count,
                    snapshot_root, next_after_object_id, next_page_sequence,
                    accumulated_object_count, accumulated_root, last_object_id, revision,
                    started_at, completed_at, updated_at, record_fingerprint
                ) VALUES (?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, '', 1, ?, ?, ?, ?, ?, ?, ?, ?)
                """, cycleId, AUTHORITY, TRUST_DOMAIN, ARCHIVE_SET, FAILURE_DOMAIN, snapshotId,
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

    private record FindingEventMaterialFixture(
            String schemaVersion,
            String projectionId,
            String comparisonId,
            String authorityId,
            String objectId,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .Outcome classificationOutcome,
            String classificationFingerprint,
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                    .Transition transition,
            String previousFindingFingerprint,
            String resultingFindingFingerprint,
            long resultingFindingVersion,
            Instant occurredAt) {
    }

    private record FindingEventRootLinkFixture(
            String schemaVersion,
            String previousRoot,
            String eventFingerprint) {
    }

    private record FindingProjectionMaterialFixture(
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
    }
}
