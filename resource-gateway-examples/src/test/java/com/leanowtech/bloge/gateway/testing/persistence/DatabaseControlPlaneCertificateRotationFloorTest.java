package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationEvent;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationActivationAuthority;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationFloor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseControlPlaneCertificateRotationFloorTest {

    private static final String SCOPE = "resource-gateway-prod";
    private static final String TARGET = "external-notary";
    private static final String INITIAL_MATERIAL = "initial-a";
    private static final String INITIAL_FINGERPRINT = fingerprint('a');
    private static final String POLICY_FINGERPRINT = fingerprint('f');

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:certificate-rotation-floor-" + UUID.randomUUID()
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
    void bootstrapsStagesAndReconstructsTheExactDurableHead() {
        var floor = repository(initial(1, INITIAL_MATERIAL, INITIAL_FINGERPRINT));
        Instant now = now();
        var event = event("rotation-002", 2, INITIAL_FINGERPRINT,
                "candidate-b", fingerprint('b'), now.plusSeconds(60),
                now.plusSeconds(300));

        var staged = floor.accept(event);
        var replayed = floor.accept(event);
        var reconstructed = repository(initial(1, INITIAL_MATERIAL, INITIAL_FINGERPRINT));

        assertThat(staged.status()).isEqualTo(
                ControlPlaneCertificateRotationFloor.AcceptanceStatus.STAGED);
        assertThat(replayed.status()).isEqualTo(
                ControlPlaneCertificateRotationFloor.AcceptanceStatus.REPLAYED);
        assertThat(reconstructed.snapshot(TARGET)).satisfies(snapshot -> {
            assertThat(snapshot.activeGeneration()).isEqualTo(1);
            assertThat(snapshot.activeSettingsFingerprint())
                    .isEqualTo(INITIAL_FINGERPRINT);
            assertThat(snapshot.pendingGeneration()).isEqualTo(2);
            assertThat(snapshot.pendingMaterialId()).isEqualTo("candidate-b");
            assertThat(snapshot.pendingSettingsFingerprint()).isEqualTo(fingerprint('b'));
            assertThat(snapshot.pendingEventId()).isEqualTo("rotation-002");
        });
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_control_plane_certificate_rotation_events
                WHERE deployment_scope_id = ? AND target_id = ?
                """, Integer.class, SCOPE, TARGET)).isEqualTo(1);
        assertThat(reconstructed.durable()).isTrue();
    }

    @Test
    void dueSuccessorActivatesAndRestartVerifiesBaselineAncestryGapAndFork() {
        var floor = repository(initial(1, INITIAL_MATERIAL, INITIAL_FINGERPRINT));
        Instant now = now();
        var activated = floor.accept(event("rotation-002", 2, INITIAL_FINGERPRINT,
                "candidate-b", fingerprint('b'), now.minusSeconds(1),
                now.plusSeconds(300)));

        assertThat(activated.status()).isEqualTo(
                ControlPlaneCertificateRotationFloor.AcceptanceStatus.ACTIVATED);
        assertThat(activated.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.activeGeneration()).isEqualTo(2);
            assertThat(snapshot.activeMaterialId()).isEqualTo("candidate-b");
            assertThat(snapshot.activeEventId()).isEqualTo("rotation-002");
            assertThat(snapshot.pendingGeneration()).isZero();
        });
        assertThat(repository(initial(2, "candidate-b", fingerprint('b')))
                .snapshot(TARGET).activeGeneration()).isEqualTo(2);
        assertThat(repository(initial(1, INITIAL_MATERIAL, INITIAL_FINGERPRINT))
                .snapshot(TARGET).activeGeneration()).isEqualTo(2);
        assertThatThrownBy(() -> repository(initial(1, INITIAL_MATERIAL, fingerprint('9'))))
                .hasMessageContaining("ancestry");
        assertThatThrownBy(() -> repository(
                initial(3, "candidate-c", fingerprint('c'))))
                .hasMessageContaining("generation gap");
        assertThatThrownBy(() -> repository(
                initial(2, "candidate-x", fingerprint('b'))))
                .hasMessageContaining("fork");
    }

    @Test
    void snapshotPromotesAStagedSuccessorUsingDatabaseTime() throws Exception {
        var floor = repository(initial(1, INITIAL_MATERIAL, INITIAL_FINGERPRINT));
        Instant now = now();
        var event = event("rotation-002", 2, INITIAL_FINGERPRINT,
                "candidate-b", fingerprint('b'), now.plusMillis(700),
                now.plusSeconds(60));

        assertThat(floor.accept(event).status()).isEqualTo(
                ControlPlaneCertificateRotationFloor.AcceptanceStatus.STAGED);
        Thread.sleep(900);

        assertThat(floor.snapshot(TARGET)).satisfies(snapshot -> {
            assertThat(snapshot.activeGeneration()).isEqualTo(2);
            assertThat(snapshot.activeEventId()).isEqualTo("rotation-002");
            assertThat(snapshot.pendingGeneration()).isZero();
        });
        assertThat(database.jdbc().queryForMap("""
                SELECT activation_state, activated_at
                FROM rg_control_plane_certificate_rotation_events
                WHERE deployment_scope_id = ? AND event_id = ?
                """, SCOPE, "rotation-002"))
                .containsEntry("ACTIVATION_STATE", "ACTIVE")
                .containsKey("ACTIVATED_AT");
    }

    @Test
    void databaseTimeCannotAdvanceTheDurableFloorWithoutFleetAdmission() {
        AtomicBoolean admitted = new AtomicBoolean();
        var floor = repository(initial(1, INITIAL_MATERIAL, INITIAL_FINGERPRINT),
                ignored -> admitted.get());
        Instant now = now();
        var event = event("rotation-002", 2, INITIAL_FINGERPRINT,
                "candidate-b", fingerprint('b'), now.minusSeconds(1),
                now.plusSeconds(60));

        assertThat(floor.accept(event).status()).isEqualTo(
                ControlPlaneCertificateRotationFloor.AcceptanceStatus.STAGED);
        assertThat(floor.snapshot(TARGET).pendingGeneration()).isEqualTo(2);

        admitted.set(true);
        assertThat(floor.snapshot(TARGET)).satisfies(snapshot -> {
            assertThat(snapshot.activeGeneration()).isEqualTo(2);
            assertThat(snapshot.pendingGeneration()).isZero();
            assertThat(snapshot.activeEventFingerprint())
                    .isEqualTo(event.materialFingerprint());
        });
    }

    @Test
    void rejectsEventReuseGenerationForkScopeDriftAndInvalidPredecessor() {
        var floor = repository(initial(1, INITIAL_MATERIAL, INITIAL_FINGERPRINT));
        Instant now = now();
        var accepted = event("rotation-002", 2, INITIAL_FINGERPRINT,
                "candidate-b", fingerprint('b'), now.plusSeconds(60),
                now.plusSeconds(300));
        floor.accept(accepted);

        assertThatThrownBy(() -> floor.accept(event("rotation-002", 2,
                INITIAL_FINGERPRINT, "candidate-c", fingerprint('c'),
                now.plusSeconds(60), now.plusSeconds(300))))
                .hasMessageContaining("event id was reused");
        assertThatThrownBy(() -> floor.accept(event("rotation-other", 2,
                INITIAL_FINGERPRINT, "candidate-c", fingerprint('c'),
                now.plusSeconds(60), now.plusSeconds(300))))
                .hasMessageContaining("already has a successor");

        var clean = repository("other-scope", TARGET,
                initial(1, INITIAL_MATERIAL, INITIAL_FINGERPRINT));
        assertThatThrownBy(() -> clean.accept(accepted))
                .hasMessageContaining("binding does not match");

        var another = repository("third-scope", TARGET,
                initial(1, INITIAL_MATERIAL, INITIAL_FINGERPRINT));
        assertThatThrownBy(() -> another.accept(eventForScope("third-scope",
                "rotation-002", 2, fingerprint('9'), "candidate-b", fingerprint('b'),
                now.minusSeconds(1), now.plusSeconds(300))))
                .hasMessageContaining("predecessor");
    }

    @Test
    void floorAndEventJournalCorruptionFailClosed() {
        var floor = repository(initial(1, INITIAL_MATERIAL, INITIAL_FINGERPRINT));
        Instant now = now();
        var event = event("rotation-002", 2, INITIAL_FINGERPRINT,
                "candidate-b", fingerprint('b'), now.plusSeconds(60),
                now.plusSeconds(300));
        floor.accept(event);

        database.jdbc().update("""
                UPDATE rg_control_plane_certificate_rotation_events
                SET record_fingerprint = ?
                WHERE deployment_scope_id = ? AND event_id = ?
                """, fingerprint('9'), SCOPE, "rotation-002");
        assertThatThrownBy(() -> floor.accept(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("journal is corrupt");

        database.jdbc().update("""
                UPDATE rg_control_plane_certificate_rotation_floors
                SET record_fingerprint = ?
                WHERE deployment_scope_id = ? AND target_id = ?
                """, fingerprint('8'), SCOPE, TARGET);
        assertThatThrownBy(() -> floor.snapshot(TARGET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("floor is corrupt");
    }

    @Test
    void competingEventsForTheSameGenerationLinearizeToOneWinner() throws Exception {
        var left = repository(initial(1, INITIAL_MATERIAL, INITIAL_FINGERPRINT));
        var right = repository(initial(1, INITIAL_MATERIAL, INITIAL_FINGERPRINT));
        Instant now = now();
        var leftEvent = event("rotation-left", 2, INITIAL_FINGERPRINT,
                "candidate-b", fingerprint('b'), now.plusSeconds(60),
                now.plusSeconds(300));
        var rightEvent = event("rotation-right", 2, INITIAL_FINGERPRINT,
                "candidate-c", fingerprint('c'), now.plusSeconds(60),
                now.plusSeconds(300));
        CountDownLatch start = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<String> leftResult = workers.submit(() -> acceptAfter(start, left, leftEvent));
            Future<String> rightResult = workers.submit(() -> acceptAfter(
                    start, right, rightEvent));
            start.countDown();

            assertThat(List.of(leftResult.get(), rightResult.get()))
                    .satisfiesExactlyInAnyOrder(
                            result -> assertThat(result).isEqualTo("STAGED"),
                            result -> assertThat(result).contains("already has a successor"));
        }
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_control_plane_certificate_rotation_events
                WHERE deployment_scope_id = ? AND target_id = ? AND generation = 2
                """, Integer.class, SCOPE, TARGET)).isEqualTo(1);
    }

    private DatabaseControlPlaneCertificateRotationFloor repository(
            ControlPlaneCertificateRotationFloor.InitialTarget initial) {
        return repository(SCOPE, TARGET, initial);
    }

    private DatabaseControlPlaneCertificateRotationFloor repository(
            ControlPlaneCertificateRotationFloor.InitialTarget initial,
            ControlPlaneCertificateRotationActivationAuthority activationAuthority) {
        var result = new DatabaseControlPlaneCertificateRotationFloor(database.jdbc(),
                objectMapper, SCOPE, Map.of(TARGET, initial), database.transactionManager(),
                activationAuthority);
        result.init();
        return result;
    }

    private DatabaseControlPlaneCertificateRotationFloor repository(
            String scope,
            String target,
            ControlPlaneCertificateRotationFloor.InitialTarget initial) {
        var result = new DatabaseControlPlaneCertificateRotationFloor(database.jdbc(),
                objectMapper, scope, Map.of(target, initial), database.transactionManager());
        result.init();
        return result;
    }

    private static ControlPlaneCertificateRotationFloor.InitialTarget initial(
            long generation, String materialId, String fingerprint) {
        return new ControlPlaneCertificateRotationFloor.InitialTarget(
                generation, materialId, fingerprint);
    }

    private static String acceptAfter(
            CountDownLatch start,
            ControlPlaneCertificateRotationFloor floor,
            ControlPlaneCertificateRotationEvent event) throws InterruptedException {
        start.await();
        try {
            return floor.accept(event).status().name();
        } catch (IllegalArgumentException rejected) {
            return rejected.getMessage();
        }
    }

    private static ControlPlaneCertificateRotationEvent event(
            String eventId,
            long generation,
            String previousFingerprint,
            String materialId,
            String settingsFingerprint,
            Instant activateAt,
            Instant expiresAt) {
        return eventForScope(SCOPE, eventId, generation, previousFingerprint, materialId,
                settingsFingerprint, activateAt, expiresAt);
    }

    private static ControlPlaneCertificateRotationEvent eventForScope(
            String scope,
            String eventId,
            long generation,
            String previousFingerprint,
            String materialId,
            String settingsFingerprint,
            Instant activateAt,
            Instant expiresAt) {
        Instant issuedAt = activateAt.minusSeconds(300);
        Instant notBefore = activateAt.minusSeconds(299);
        var material = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "enterprise-pki-governance", eventId, scope, TARGET, generation,
                previousFingerprint, materialId, settingsFingerprint, POLICY_FINGERPRINT,
                issuedAt, notBefore, activateAt, expiresAt);
        return new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION, material,
                fingerprint(eventId.charAt(eventId.length() - 1)),
                List.of(new ControlPlaneCertificateRotationEvent.AuthoritySignature(
                        "pki-a", "key-a", "Ed25519", issuedAt,
                        Base64.getEncoder().encodeToString(new byte[64]))));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String fingerprint(char value) {
        char hexadecimal = Character.toLowerCase(value);
        if (Character.digit(hexadecimal, 16) < 0) {
            hexadecimal = 'e';
        }
        return "sha256:" + String.valueOf(hexadecimal).repeat(64);
    }
}
