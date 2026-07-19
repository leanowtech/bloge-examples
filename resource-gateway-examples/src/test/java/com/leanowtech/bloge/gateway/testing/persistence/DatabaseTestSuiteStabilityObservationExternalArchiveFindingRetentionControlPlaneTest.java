package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlaneTest {
    private static final String AUTHORITY = "archive-a";

    private ObjectMapper objectMapper;
    private TestRuntimeDatabase database;
    private DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane findingControl;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:external-finding-retention-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 6));
        findingControl = new
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .Settings(10));
        findingControl.init();
        retention("replica-a").init();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void archivesOnlyEligibleResolvedFindingsAndPurgesExactArchive() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        insertAuthority("");
        var expired = finding(1, true, now.minus(Duration.ofDays(3)));
        var recent = finding(2, true, now.minus(Duration.ofMinutes(30)));
        var open = finding(3, false, now.minus(Duration.ofDays(5)));
        insertFinding(expired);
        insertFinding(recent);
        insertFinding(open);
        var control = retention("replica-a");

        var archived = control.retain(Duration.ofHours(1), Duration.ofDays(1),
                Duration.ofDays(30), 10);

        assertThat(archived.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                        .RetentionStatus.COMPLETED);
        assertThat(archived.result().findingsArchived()).isEqualTo(1);
        assertThat(control.archives(AUTHORITY, "", 10)).singleElement().satisfies(row -> {
            assertThat(row.finding()).isEqualTo(expired);
            row.verify(objectMapper);
        });
        assertThat(currentObjectIds()).containsExactly(recent.objectId(), open.objectId());

        ageOnlyArchive(now.minus(Duration.ofDays(2)));
        var purged = control.retain(Duration.ofHours(1), Duration.ofDays(1),
                Duration.ofDays(30), 10);

        assertThat(purged.result().archivesPurged()).isEqualTo(1);
        assertThat(control.archives(AUTHORITY, "", 10)).isEmpty();
        var snapshot = control.operationalSnapshot(Duration.ofHours(1), Duration.ofDays(1),
                Duration.ofDays(30));
        assertThat(snapshot.totalFindingsArchived()).isEqualTo(1);
        assertThat(snapshot.totalArchivesPurged()).isEqualTo(1);
        assertThat(snapshot.archiveSize()).isZero();
        assertThat(snapshot.openFindings()).isEqualTo(1);
    }

    @Test
    void retiresEvidenceInBoundedPagesAcrossReplicasAndGatesExport() {
        ProjectionFixture projection = insertCompletedProjection(3, 2,
                Instant.now().minus(Duration.ofDays(3)));
        var first = retention("replica-a").retain(Duration.ofDays(30), Duration.ofDays(30),
                Duration.ofDays(1), 1);

        assertThat(first.result().eventsDeleted()).isEqualTo(1);
        assertThat(first.result().snapshotsDeleted()).isEqualTo(1);
        assertThat(first.result().projectionRetired()).isFalse();
        assertThat(first.result().activeRetirementProjectionId())
                .isEqualTo(projection.projectionId());
        assertThatThrownBy(() -> findingControl.events(projection.projectionId(), "", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("being retired");

        var secondReplica = retention("replica-b");
        secondReplica.init();
        DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                .RetentionResult terminal = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            var page = secondReplica.retain(Duration.ofDays(30), Duration.ofDays(30),
                    Duration.ofDays(1), 1);
            if (page.result().projectionRetired()) {
                terminal = page.result();
                break;
            }
        }

        assertThat(terminal).isNotNull();
        assertThat(terminal.activeRetirementProjectionId()).isEmpty();
        assertThat(count("rg_test_suite_stability_observation_external_finding_events"))
                .isZero();
        assertThat(count("rg_test_suite_stability_observation_external_finding_snapshots"))
                .isZero();
        assertThat(count("rg_test_suite_stability_observation_external_finding_projections"))
                .isEqualTo(1);
        assertThat(database.jdbc().queryForObject("""
                SELECT retirement_status
                FROM rg_test_suite_stability_observation_external_finding_evidence_retirements
                WHERE projection_id = ?
                """, String.class, projection.projectionId())).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> findingControl.events(projection.projectionId(), "", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is retired");
        var snapshot = secondReplica.operationalSnapshot(Duration.ofDays(30),
                Duration.ofDays(30), Duration.ofDays(1));
        assertThat(snapshot.totalEventsDeleted()).isEqualTo(3);
        assertThat(snapshot.totalSnapshotsDeleted()).isEqualTo(2);
        assertThat(snapshot.totalProjectionsRetired()).isEqualTo(1);
        assertThat(snapshot.activeRetirement()).isFalse();
    }

    @Test
    void activeProjectionPreventsResolvedFindingArchive() {
        String activeProjection = UUID.randomUUID().toString();
        insertAuthority(activeProjection);
        insertFinding(finding(1, true, Instant.now().minus(Duration.ofDays(3))));

        var result = retention("replica-a").retain(Duration.ofHours(1), Duration.ofDays(1),
                Duration.ofDays(30), 10);

        assertThat(result.result().findingsArchived()).isZero();
        assertThat(count("rg_test_suite_stability_observation_external_findings")).isEqualTo(1);
        assertThat(count("rg_test_suite_stability_observation_external_finding_archives")).isZero();
    }

    @Test
    void corruptEventRollsBackRetirementStartWithoutLaunderingEvidence() {
        ProjectionFixture projection = insertCompletedProjection(2, 0,
                Instant.now().minus(Duration.ofDays(3)));
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_events
                SET event_fingerprint = ?
                WHERE projection_id = ? AND object_id = ?
                """, fingerprint("tampered-event"), projection.projectionId(), objectId(1));

        assertThatThrownBy(() -> retention("replica-a").retain(
                Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(1), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event fingerprint");
        assertThat(count("rg_test_suite_stability_observation_external_finding_events"))
                .isEqualTo(2);
        assertThat(count("rg_test_suite_stability_observation_external_finding_evidence_retirements"))
                .isZero();
        assertThat(retentionTotals()).containsExactly(0L, 0L, 0L);
    }

    @Test
    void missingHistoricalEventCannotBeLaunderedIntoCompletedRetirement() {
        ProjectionFixture projection = insertCompletedProjection(2, 0,
                Instant.now().minus(Duration.ofDays(3)));
        database.jdbc().update("""
                DELETE FROM rg_test_suite_stability_observation_external_finding_events
                WHERE projection_id = ? AND object_id = ?
                """, projection.projectionId(), objectId(1));

        assertThatThrownBy(() -> retention("replica-a").retain(
                Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(1), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event retirement replay failed");
        assertThat(count("rg_test_suite_stability_observation_external_finding_events"))
                .isEqualTo(1);
        assertThat(count("rg_test_suite_stability_observation_external_finding_evidence_retirements"))
                .isZero();
    }

    @Test
    void projectionDriftStopsCrossReplicaResumeAtTheFrozenSourceFence() {
        ProjectionFixture projection = insertCompletedProjection(3, 0,
                Instant.now().minus(Duration.ofDays(3)));
        retention("replica-a").retain(Duration.ofDays(30), Duration.ofDays(30),
                Duration.ofDays(1), 1);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_projections
                SET record_fingerprint = ?
                WHERE projection_id = ?
                """, fingerprint("tampered-projection"), projection.projectionId());

        assertThatThrownBy(() -> retention("replica-b").retain(
                Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(1), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projection state");
        assertThat(database.jdbc().queryForObject("""
                SELECT progress_status
                FROM rg_test_suite_stability_observation_external_finding_retention_progress
                WHERE projection_id = ?
                """, String.class, projection.projectionId())).isEqualTo("ACTIVE");
        assertThat(count("rg_test_suite_stability_observation_external_finding_events"))
                .isEqualTo(2);
    }

    @Test
    void archiveCardinalityTamperBlocksObservationAndFurtherMutation() {
        insertAuthority("");
        insertFinding(finding(1, true, Instant.now().minus(Duration.ofDays(3))));
        var control = retention("replica-a");
        control.retain(Duration.ofHours(1), Duration.ofDays(30), Duration.ofDays(30), 10);
        database.jdbc().update("""
                DELETE FROM rg_test_suite_stability_observation_external_finding_archives
                """);

        assertThatThrownBy(() -> control.operationalSnapshot(
                Duration.ofHours(1), Duration.ofDays(30), Duration.ofDays(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archive cardinality");
        assertThatThrownBy(() -> control.retain(
                Duration.ofHours(1), Duration.ofDays(30), Duration.ofDays(30), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archive cardinality");
    }

    @Test
    void liveDatabaseLeaseReturnsBusyWithoutChangingCounters() {
        installLiveLease("other-replica", Duration.ofMinutes(5));

        var attempt = retention("replica-a").retain(Duration.ofHours(1), Duration.ofDays(1),
                Duration.ofDays(1), 10);

        assertThat(attempt.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                        .RetentionStatus.LEASE_BUSY);
        assertThat(attempt.result()).isNull();
        assertThat(retentionTotals()).containsExactly(0L, 0L, 0L);
    }

    @Test
    void requiresNewCommitSurvivesSurroundingRollback() {
        insertAuthority("");
        insertFinding(finding(1, true, Instant.now().minus(Duration.ofDays(3))));
        var control = retention("replica-a");
        TransactionTemplate outer = new TransactionTemplate(database.transactionManager());

        outer.executeWithoutResult(status -> {
            control.retain(Duration.ofHours(1), Duration.ofDays(30),
                    Duration.ofDays(30), 10);
            status.setRollbackOnly();
        });

        assertThat(count("rg_test_suite_stability_observation_external_findings")).isZero();
        assertThat(control.archives(AUTHORITY, "", 10)).hasSize(1);
    }

    @Test
    void validatesPoliciesFingerprintsAndControlledPublicBoundary() {
        var control = retention("replica-a");
        assertThatThrownBy(() -> control.retain(Duration.ofMinutes(59), Duration.ofDays(1),
                Duration.ofDays(1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> control.retain(Duration.ofHours(1), Duration.ofHours(23),
                Duration.ofDays(1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> control.retain(Duration.ofHours(1), Duration.ofDays(1),
                Duration.ofHours(23), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> control.retain(Duration.ofHours(1), Duration.ofDays(1),
                Duration.ofDays(1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> control.archives(AUTHORITY, "not-a-uuid", 10))
                .isInstanceOf(IllegalArgumentException.class);

        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_retention_state
                SET total_events_deleted = 99
                WHERE job_name = 'external-archive-finding-retention'
                """);
        assertThatThrownBy(() -> control.operationalSnapshot(
                Duration.ofHours(1), Duration.ofDays(1), Duration.ofDays(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention state");
        assertThat(Arrays.stream(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                        .class.getMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .noneMatch(name -> name.matches(
                        "(?i).*(overwrite|shorten|remediate|releaseHold|deleteWorm).*"));
    }

    @Test
    void retiresEmptyProjectionWithoutInventingEvidenceRows() {
        ProjectionFixture projection = insertCompletedProjection(0, 0,
                Instant.now().minus(Duration.ofDays(3)));

        var result = retention("replica-a").retain(Duration.ofDays(30),
                Duration.ofDays(30), Duration.ofDays(1), 10);

        assertThat(result.result().projectionRetired()).isTrue();
        assertThat(result.result().eventsDeleted()).isZero();
        assertThat(result.result().snapshotsDeleted()).isZero();
        assertThat(count("rg_test_suite_stability_observation_external_finding_projections"))
                .isEqualTo(1);
        assertThat(database.jdbc().queryForObject("""
                SELECT retirement_status
                FROM rg_test_suite_stability_observation_external_finding_evidence_retirements
                WHERE projection_id = ?
                """, String.class, projection.projectionId())).isEqualTo("COMPLETED");
    }

    @Test
    void consumesEligibleEvidenceBacklogOldestFirst() {
        ProjectionFixture oldest = insertCompletedProjection(1, 0,
                Instant.now().minus(Duration.ofDays(5)));
        ProjectionFixture newer = insertCompletedProjection(1, 0,
                Instant.now().minus(Duration.ofDays(3)));
        var control = retention("replica-a");

        control.retain(Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(1), 10);

        assertThat(database.jdbc().queryForList("""
                SELECT projection_id
                FROM rg_test_suite_stability_observation_external_finding_evidence_retirements
                ORDER BY started_at, projection_id
                """, String.class)).containsExactly(oldest.projectionId());
        control.retain(Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(1), 10);
        assertThat(database.jdbc().queryForList("""
                SELECT projection_id
                FROM rg_test_suite_stability_observation_external_finding_evidence_retirements
                ORDER BY started_at, projection_id
                """, String.class)).containsExactly(oldest.projectionId(), newer.projectionId());
    }

    @Test
    void missingTailAfterCommittedPagesLeavesPermanentActiveQuarantine() {
        ProjectionFixture projection = insertCompletedProjection(3, 0,
                Instant.now().minus(Duration.ofDays(3)));
        database.jdbc().update("""
                DELETE FROM rg_test_suite_stability_observation_external_finding_events
                WHERE projection_id = ? AND object_id = ?
                """, projection.projectionId(), objectId(3));
        var control = retention("replica-a");
        control.retain(Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(1), 1);
        control.retain(Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(1), 1);

        assertThatThrownBy(() -> control.retain(Duration.ofDays(30), Duration.ofDays(30),
                Duration.ofDays(1), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event retirement replay failed");
        assertThat(database.jdbc().queryForObject("""
                SELECT progress_status
                FROM rg_test_suite_stability_observation_external_finding_retention_progress
                WHERE projection_id = ?
                """, String.class, projection.projectionId())).isEqualTo("ACTIVE");
        assertThat(database.jdbc().queryForObject("""
                SELECT retirement_status
                FROM rg_test_suite_stability_observation_external_finding_evidence_retirements
                WHERE projection_id = ?
                """, String.class, projection.projectionId())).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> findingControl.events(projection.projectionId(), "", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("being retired");
    }

    @Test
    void progressAndMarkerTamperAreRejectedIndependently() {
        ProjectionFixture projection = insertCompletedProjection(2, 0,
                Instant.now().minus(Duration.ofDays(3)));
        var control = retention("replica-a");
        control.retain(Duration.ofDays(30), Duration.ofDays(30), Duration.ofDays(1), 1);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_retention_progress
                SET revision = revision + 1
                WHERE projection_id = ?
                """, projection.projectionId());

        assertThatThrownBy(() -> control.retain(Duration.ofDays(30), Duration.ofDays(30),
                Duration.ofDays(1), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("progress fingerprint");

        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_evidence_retirements
                SET record_fingerprint = ?
                WHERE projection_id = ?
                """, fingerprint("tampered-marker"), projection.projectionId());
        assertThatThrownBy(() -> findingControl.events(projection.projectionId(), "", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retirement is corrupt");
    }

    private DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
            retention(String ownerId) {
        return new
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper, ownerId,
                Duration.ofSeconds(30));
    }

    private void insertAuthority(String activeProjectionId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var material = new FindingAuthorityMaterialFixture(
                "bloge.testSuiteStabilityObservationExternalArchiveFindingAuthority.v1",
                AUTHORITY, activeProjectionId, "", "", null, 0, now);
        database.jdbc().update("""
                INSERT INTO
                    rg_test_suite_stability_observation_external_finding_authorities (
                    authority_id, active_projection_id, last_completed_projection_id,
                    last_applied_comparison_id, last_applied_comparison_completed_at,
                    revision, updated_at, record_fingerprint
                ) VALUES (?, ?, '', '', NULL, 0, ?, ?)
                """, AUTHORITY, activeProjectionId, Timestamp.from(now),
                ProtocolFingerprint.of(objectMapper, material));
    }

    private DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.Finding finding(
            int object,
            boolean resolved,
            Instant evaluatedAt) {
        Instant evaluated = evaluatedAt.truncatedTo(ChronoUnit.MILLIS);
        Instant firstSeen = evaluated.minus(Duration.ofHours(2));
        Instant observed = resolved ? evaluated.minus(Duration.ofHours(1)) : evaluated;
        var status = resolved
                ? DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                .FindingStatus.RESOLVED
                : DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                .FindingStatus.OPEN;
        var outcome = resolved
                ? DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .Outcome.MATCHED
                : DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .Outcome.MISSING_REMOTE;
        var resolution = resolved
                ? DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                .Resolution.MATCHED_ON_RECHECK
                : DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                .Resolution.NONE;
        Instant resolvedAt = resolved ? evaluated : null;
        String comparisonId = UUID.randomUUID().toString();
        String classificationFingerprint = fingerprint("classification-" + object + "-" + resolved);
        var material = new FindingMaterialFixture(
                "bloge.testSuiteStabilityObservationExternalArchiveFinding.v1", AUTHORITY,
                objectId(object), status,
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Outcome.MISSING_REMOTE,
                comparisonId, outcome, classificationFingerprint, 2, 1, firstSeen, observed,
                evaluated, resolution, resolvedAt, 2);
        return new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.Finding(
                material.authorityId(), material.objectId(), material.status(), material.kind(),
                material.latestComparisonId(), material.latestOutcome(),
                material.latestClassificationFingerprint(), material.occurrences(),
                material.episodes(), material.firstSeenAt(), material.lastObservedAt(),
                material.lastEvaluatedAt(), material.resolution(), material.resolvedAt(),
                material.version(), ProtocolFingerprint.of(objectMapper, material));
    }

    private void insertFinding(
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.Finding finding) {
        database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_findings (
                    authority_id, object_id, finding_status, finding_kind,
                    latest_comparison_id, latest_outcome,
                    latest_classification_fingerprint, occurrence_count, episode_count,
                    first_seen_at, last_observed_at, last_evaluated_at, resolution,
                    resolved_at, finding_version, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, findingSqlArguments(finding));
    }

    private ProjectionFixture insertCompletedProjection(
            int eventCount,
            int snapshotCount,
            Instant completedAt) {
        String projectionId = UUID.randomUUID().toString();
        String comparisonId = UUID.randomUUID().toString();
        Instant completed = completedAt.truncatedTo(ChronoUnit.MILLIS);
        String eventRoot = DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                .EMPTY_EVENT_ROOT;
        for (int object = 1; object <= eventCount; object++) {
            var event = event(projectionId, comparisonId, object, completed.minusSeconds(1));
            eventRoot = DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                    .appendEventRoot(objectMapper, eventRoot, event);
            database.jdbc().update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_finding_events (
                        projection_id, comparison_id, authority_id, object_id, page_sequence,
                        classification_outcome, classification_fingerprint, transition,
                        previous_finding_fingerprint, resulting_finding_fingerprint,
                        resulting_finding_version, occurred_at, event_fingerprint
                    ) VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, event.projectionId(), event.comparisonId(), event.authorityId(),
                    event.objectId(), event.classificationOutcome().name(),
                    event.classificationFingerprint(), event.transition().name(),
                    event.previousFindingFingerprint(), event.resultingFindingFingerprint(),
                    event.resultingFindingVersion(), Timestamp.from(event.occurredAt()),
                    event.eventFingerprint());
        }
        String snapshotRoot = DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                .EMPTY_SNAPSHOT_ROOT;
        for (int object = 1; object <= snapshotCount; object++) {
            var snapshot = finding(100 + object, true, completed.minus(Duration.ofDays(1)));
            snapshotRoot = DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                    .appendSnapshotRoot(objectMapper, snapshotRoot, snapshot);
            Object[] findingArguments = findingSqlArguments(snapshot);
            database.jdbc().update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_finding_snapshots (
                        projection_id, authority_id, object_id, finding_status, finding_kind,
                        latest_comparison_id, latest_outcome,
                        latest_classification_fingerprint, occurrence_count, episode_count,
                        first_seen_at, last_observed_at, last_evaluated_at, resolution,
                        resolved_at, finding_version, record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, prepend(projectionId, findingArguments));
        }
        Instant comparisonStarted = completed.minus(Duration.ofMinutes(3));
        Instant comparisonCompleted = completed.minus(Duration.ofMinutes(2));
        Instant started = completed.minus(Duration.ofMinutes(1));
        String sourceRoot = fingerprint("source-root-" + comparisonId);
        String cursor = eventCount == 0 ? "" : objectId(eventCount);
        var material = new FindingProjectionMaterialFixture(
                "bloge.testSuiteStabilityObservationExternalArchiveFindingProjection.v1",
                projectionId, comparisonId, AUTHORITY, "COMPLETED", comparisonStarted,
                comparisonCompleted, eventCount, sourceRoot, snapshotCount, snapshotRoot,
                cursor, 1, eventCount, eventCount, 0, 0, 0, 0, eventRoot, 1, started,
                completed, completed);
        String projectionFingerprint = ProtocolFingerprint.of(objectMapper, material);
        database.jdbc().update("""
                INSERT INTO
                    rg_test_suite_stability_observation_external_finding_projections (
                    projection_id, comparison_id, authority_id, projection_status,
                    comparison_started_at, comparison_completed_at,
                    source_classification_count, source_classification_root,
                    snapshot_finding_count, snapshot_root, next_after_object_id,
                    next_page_sequence, processed_classification_count, opened_count,
                    observed_count, reopened_count, resolved_count, confirmed_count,
                    event_root, revision, started_at, completed_at, updated_at,
                    record_fingerprint
                ) VALUES (?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0,
                          ?, 1, ?, ?, ?, ?)
                """, projectionId, comparisonId, AUTHORITY, Timestamp.from(comparisonStarted),
                Timestamp.from(comparisonCompleted), eventCount, sourceRoot, snapshotCount,
                snapshotRoot, cursor, 1, eventCount, eventCount, eventRoot,
                Timestamp.from(started), Timestamp.from(completed), Timestamp.from(completed),
                projectionFingerprint);
        return new ProjectionFixture(projectionId, comparisonId, eventRoot, snapshotRoot,
                projectionFingerprint);
    }

    private DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.FindingEvent
            event(String projectionId, String comparisonId, int object, Instant occurredAt) {
        String classificationFingerprint = fingerprint("event-classification-" + object);
        String resultingFingerprint = fingerprint("event-result-" + object);
        var material = new FindingEventMaterialFixture(
                "bloge.testSuiteStabilityObservationExternalArchiveFindingEvent.v1",
                projectionId, comparisonId, AUTHORITY, objectId(object),
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .Outcome.MISSING_REMOTE,
                classificationFingerprint,
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .Transition.OPENED,
                "", resultingFingerprint, 1, occurredAt);
        return new
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.FindingEvent(
                material.projectionId(), material.comparisonId(), material.authorityId(),
                material.objectId(), material.classificationOutcome(),
                material.classificationFingerprint(), material.transition(),
                material.previousFindingFingerprint(), material.resultingFindingFingerprint(),
                material.resultingFindingVersion(), material.occurredAt(),
                ProtocolFingerprint.of(objectMapper, material));
    }

    private void ageOnlyArchive(Instant archivedAt) {
        var archive = retention("replica-a").archives(AUTHORITY, "", 10).getFirst();
        var material = new ArchivedFindingMaterialFixture(
                "bloge.testSuiteStabilityObservationExternalArchiveFindingArchive.v1",
                archive.archiveId(), archive.finding().recordFingerprint(), archivedAt);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_archives
                SET archived_at = ?, record_fingerprint = ?
                WHERE archive_id = ?
                """, Timestamp.from(archivedAt), ProtocolFingerprint.of(objectMapper, material),
                archive.archiveId());
    }

    private void installLiveLease(String owner, Duration duration) {
        StateRow current = database.jdbc().queryForObject("""
                SELECT active_retirement_projection_id, revision,
                       total_findings_archived, total_archives_purged,
                       total_events_deleted, total_snapshots_deleted,
                       total_projections_retired, archive_purge_root, last_success_at
                FROM rg_test_suite_stability_observation_external_finding_retention_state
                WHERE job_name = 'external-archive-finding-retention'
                """, (result, row) -> new StateRow(
                result.getString("active_retirement_projection_id"),
                result.getLong("revision"), result.getLong("total_findings_archived"),
                result.getLong("total_archives_purged"),
                result.getLong("total_events_deleted"),
                result.getLong("total_snapshots_deleted"),
                result.getLong("total_projections_retired"),
                result.getString("archive_purge_root"),
                result.getTimestamp("last_success_at") == null ? null
                        : result.getTimestamp("last_success_at").toInstant()));
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant until = now.plus(duration);
        long epoch = 1;
        long revision = current.revision() + 1;
        var material = new RetentionStateMaterialFixture(
                "bloge.testSuiteStabilityObservationExternalArchiveFindingRetentionState.v1",
                "external-archive-finding-retention", owner, token, epoch, until,
                current.activeProjectionId(), revision, current.archived(), current.purged(),
                current.events(), current.snapshots(), current.projections(), current.purgeRoot(),
                current.lastSuccessAt(), now);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_finding_retention_state
                SET lease_owner = ?, lease_token = ?, lease_epoch = ?, lease_until = ?,
                    revision = ?, updated_at = ?, record_fingerprint = ?
                WHERE job_name = 'external-archive-finding-retention'
                """, owner, token, epoch, Timestamp.from(until), revision, Timestamp.from(now),
                ProtocolFingerprint.of(objectMapper, material));
    }

    private List<String> currentObjectIds() {
        return database.jdbc().queryForList("""
                SELECT object_id
                FROM rg_test_suite_stability_observation_external_findings
                ORDER BY object_id
                """, String.class);
    }

    private List<Long> retentionTotals() {
        return database.jdbc().queryForObject("""
                SELECT total_events_deleted, total_snapshots_deleted,
                       total_projections_retired
                FROM rg_test_suite_stability_observation_external_finding_retention_state
                WHERE job_name = 'external-archive-finding-retention'
                """, (result, row) -> List.of(result.getLong("total_events_deleted"),
                result.getLong("total_snapshots_deleted"),
                result.getLong("total_projections_retired")));
    }

    private int count(String table) {
        Integer value = database.jdbc().queryForObject("SELECT COUNT(*) FROM " + table,
                Integer.class);
        return value == null ? 0 : value;
    }

    private static Object[] findingSqlArguments(
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.Finding value) {
        return new Object[]{value.authorityId(), value.objectId(), value.status().name(),
                value.kind().name(), value.latestComparisonId(), value.latestOutcome().name(),
                value.latestClassificationFingerprint(), value.occurrences(), value.episodes(),
                Timestamp.from(value.firstSeenAt()), Timestamp.from(value.lastObservedAt()),
                Timestamp.from(value.lastEvaluatedAt()), value.resolution().name(),
                value.resolvedAt() == null ? null : Timestamp.from(value.resolvedAt()),
                value.version(), value.recordFingerprint()};
    }

    private static Object[] prepend(Object first, Object[] remaining) {
        Object[] result = new Object[remaining.length + 1];
        result[0] = first;
        System.arraycopy(remaining, 0, result, 1, remaining.length);
        return result;
    }

    private static String objectId(int object) {
        return "stability-observation-worm-" + "%064x".formatted(object);
    }

    private static String fingerprint(String value) {
        return ProtocolFingerprint.ofText(value);
    }

    private record ProjectionFixture(
            String projectionId,
            String comparisonId,
            String eventRoot,
            String snapshotRoot,
            String projectionFingerprint) {
    }

    private record StateRow(
            String activeProjectionId,
            long revision,
            long archived,
            long purged,
            long events,
            long snapshots,
            long projections,
            String purgeRoot,
            Instant lastSuccessAt) {
    }

    private record FindingMaterialFixture(
            String schemaVersion,
            String authorityId,
            String objectId,
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.FindingStatus
                    status,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane.Outcome
                    kind,
            String latestComparisonId,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane.Outcome
                    latestOutcome,
            String latestClassificationFingerprint,
            long occurrences,
            long episodes,
            Instant firstSeenAt,
            Instant lastObservedAt,
            Instant lastEvaluatedAt,
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.Resolution
                    resolution,
            Instant resolvedAt,
            long version) {
    }

    private record FindingEventMaterialFixture(
            String schemaVersion,
            String projectionId,
            String comparisonId,
            String authorityId,
            String objectId,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane.Outcome
                    classificationOutcome,
            String classificationFingerprint,
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.Transition
                    transition,
            String previousFindingFingerprint,
            String resultingFindingFingerprint,
            long resultingFindingVersion,
            Instant occurredAt) {
    }

    private record FindingAuthorityMaterialFixture(
            String schemaVersion,
            String authorityId,
            String activeProjectionId,
            String lastCompletedProjectionId,
            String lastAppliedComparisonId,
            Instant lastAppliedComparisonCompletedAt,
            long revision,
            Instant updatedAt) {
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

    private record ArchivedFindingMaterialFixture(
            String schemaVersion,
            String archiveId,
            String sourceFindingFingerprint,
            Instant archivedAt) {
    }

    private record RetentionStateMaterialFixture(
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
    }
}
