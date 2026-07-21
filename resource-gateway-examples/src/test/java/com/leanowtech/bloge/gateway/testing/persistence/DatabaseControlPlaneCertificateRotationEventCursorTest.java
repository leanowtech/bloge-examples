package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationEvent;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationEventCursor;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationEventPage;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseControlPlaneCertificateRotationEventCursorTest {

    private static final String SCOPE = "resource-gateway-prod";
    private static final String INSTANCE = "replica-a";
    private static final String BASELINE = fingerprint('0');

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:certificate-rotation-event-cursor-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void stagesCommitsAndReconstructsTheExactCursorHead() {
        var cursor = cursor(0, BASELINE);
        var page = page(1, BASELINE, "rotation-002", "target-a");

        var staged = cursor.stage(page);
        var replayedStage = cursor.stage(page);
        var committed = cursor.commit(page.pageFingerprint());
        var replayedCommit = cursor.commit(page.pageFingerprint());
        var reconstructed = cursor(0, BASELINE).snapshot();

        assertThat(staged.status()).isEqualTo(
                ControlPlaneCertificateRotationEventCursor.StageStatus.STAGED);
        assertThat(replayedStage.status()).isEqualTo(
                ControlPlaneCertificateRotationEventCursor.StageStatus.REPLAYED);
        assertThat(committed.status()).isEqualTo(
                ControlPlaneCertificateRotationEventCursor.CommitStatus.COMMITTED);
        assertThat(replayedCommit.status()).isEqualTo(
                ControlPlaneCertificateRotationEventCursor.CommitStatus.REPLAYED);
        assertThat(reconstructed.committedSequence()).isEqualTo(1);
        assertThat(reconstructed.committedPageFingerprint())
                .isEqualTo(page.pageFingerprint());
        assertThat(reconstructed.hasStagedPage()).isFalse();
        assertThat(cursor.durable()).isTrue();
    }

    @Test
    void crashBeforeCommitRetainsOnlyTheExactReplayableStage() {
        var cursor = cursor(0, BASELINE);
        var page = page(1, BASELINE, "rotation-002", "target-a");
        cursor.stage(page);

        var reconstructed = cursor(0, BASELINE);

        assertThat(reconstructed.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.committedSequence()).isZero();
            assertThat(snapshot.stagedSequence()).isEqualTo(1);
            assertThat(snapshot.stagedPreviousPageFingerprint()).isEqualTo(BASELINE);
            assertThat(snapshot.stagedPageFingerprint()).isEqualTo(page.pageFingerprint());
        });
        assertThat(reconstructed.stage(page).status()).isEqualTo(
                ControlPlaneCertificateRotationEventCursor.StageStatus.REPLAYED);
    }

    @Test
    void rejectsSequenceGapPredecessorForkAndCompetingInFlightPage() {
        var cursor = cursor(0, BASELINE);
        var accepted = page(1, BASELINE, "rotation-002", "target-a");
        var fork = page(1, BASELINE, "rotation-003", "target-a");

        assertThat(cursor.stage(page(2, BASELINE,
                "rotation-004", "target-b")).status()).isEqualTo(
                ControlPlaneCertificateRotationEventCursor.StageStatus.CONFLICT);
        assertThat(cursor.stage(page(1, fingerprint('9'),
                "rotation-004", "target-b")).status()).isEqualTo(
                ControlPlaneCertificateRotationEventCursor.StageStatus.CONFLICT);
        assertThat(cursor.stage(accepted).status()).isEqualTo(
                ControlPlaneCertificateRotationEventCursor.StageStatus.STAGED);
        assertThat(cursor.stage(fork).status()).isEqualTo(
                ControlPlaneCertificateRotationEventCursor.StageStatus.CONFLICT);
        assertThat(cursor.commit(fork.pageFingerprint()).status()).isEqualTo(
                ControlPlaneCertificateRotationEventCursor.CommitStatus.CONFLICT);
    }

    @Test
    void alreadyCommittedPageCannotReopenAStage() {
        var cursor = cursor(0, BASELINE);
        var page = page(1, BASELINE, "rotation-002", "target-a");
        cursor.stage(page);
        cursor.commit(page.pageFingerprint());

        assertThat(cursor.stage(page).status()).isEqualTo(
                ControlPlaneCertificateRotationEventCursor.StageStatus.ALREADY_COMMITTED);
        assertThat(cursor.snapshot().hasStagedPage()).isFalse();
    }

    @Test
    void exactConcurrentStageHasOneWriterAndOneReplay() throws Exception {
        var first = cursor(0, BASELINE);
        var second = cursor(0, BASELINE);
        var page = page(1, BASELINE, "rotation-002", "target-a");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> {
                start.await();
                return first.stage(page).status();
            });
            var right = executor.submit(() -> {
                start.await();
                return second.stage(page).status();
            });
            start.countDown();

            assertThat(List.of(left.get(), right.get()))
                    .containsExactlyInAnyOrder(
                            ControlPlaneCertificateRotationEventCursor.StageStatus.STAGED,
                            ControlPlaneCertificateRotationEventCursor.StageStatus.REPLAYED);
        }
    }

    @Test
    void deploymentBaselineDriftAndWholeRecordMutationFailClosed() {
        var cursor = cursor(0, BASELINE);
        var page = page(1, BASELINE, "rotation-002", "target-a");
        cursor.stage(page);

        assertThatThrownBy(() -> cursor(1, fingerprint('8')))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseline drifted");

        database.jdbc().update("""
                UPDATE rg_cp_cert_rotation_event_cursors
                SET staged_page_fingerprint = ?
                WHERE deployment_scope_id = ? AND instance_id = ?
                """, fingerprint('7'), SCOPE, INSTANCE);

        assertThatThrownBy(cursor::snapshot)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }

    @Test
    void cursorStateIsIsolatedByStableServingSlot() {
        var first = cursor(0, BASELINE);
        var second = cursor("replica-b", 0, BASELINE);
        var page = page(1, BASELINE, "rotation-002", "target-a");

        first.stage(page);
        first.commit(page.pageFingerprint());

        assertThat(first.snapshot().committedSequence()).isEqualTo(1);
        assertThat(second.snapshot().committedSequence()).isZero();
    }

    private DatabaseControlPlaneCertificateRotationEventCursor cursor(
            long baselineSequence,
            String baselineFingerprint) {
        return cursor(INSTANCE, baselineSequence, baselineFingerprint);
    }

    private DatabaseControlPlaneCertificateRotationEventCursor cursor(
            String instanceId,
            long baselineSequence,
            String baselineFingerprint) {
        var cursor = new DatabaseControlPlaneCertificateRotationEventCursor(
                database.jdbc(), objectMapper, SCOPE, instanceId,
                baselineSequence, baselineFingerprint, database.transactionManager());
        cursor.init();
        return cursor;
    }

    private ControlPlaneCertificateRotationEventPage page(
            long sequence,
            String predecessor,
            String eventId,
            String targetId) {
        var material = new ControlPlaneCertificateRotationEventPage.Material(
                ControlPlaneCertificateRotationEventPage.Material.SCHEMA_VERSION,
                SCOPE, sequence, predecessor, now(), now().plusSeconds(60),
                List.of(event(eventId, targetId)));
        return new ControlPlaneCertificateRotationEventPage(
                ControlPlaneCertificateRotationEventPage.SCHEMA_VERSION,
                material, ProtocolFingerprint.of(objectMapper, material));
    }

    private ControlPlaneCertificateRotationEvent event(String eventId, String targetId) {
        Instant now = now();
        var material = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "certificate-authority", eventId, SCOPE, targetId, 2,
                fingerprint('a'), "candidate-b", fingerprint('b'), fingerprint('f'),
                now, now, now.plusSeconds(10), now.plusSeconds(120));
        return new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION, material,
                ProtocolFingerprint.of(objectMapper, material),
                List.of(new ControlPlaneCertificateRotationEvent.AuthoritySignature(
                        "authority-a", "key-a", "Ed25519", now,
                        Base64.getEncoder().encodeToString(new byte[64]))));
    }

    private static Instant now() {
        return Instant.parse("2026-07-21T12:00:00Z");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
