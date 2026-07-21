package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredControlPlaneCertificateStatusTrustStore;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateStatusFloor;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateStatusPublication;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseControlPlaneCertificateStatusFloorTest {

    private static final String SCOPE = "resource-gateway-staging";
    private static final String TARGET_A = "recovery-fleet.inventory";
    private static final String TARGET_B = "stability.serving-inventory";
    private static final String BASELINE = fingerprint('0');
    private static final String POLICY = fingerprint('f');
    private static final String SETTINGS = fingerprint('a');

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;
    private KeyPair first;
    private KeyPair second;

    @BeforeEach
    void setUp() throws Exception {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:certificate-status-floor-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
        objectMapper = new ObjectMapper().findAndRegisterModules();
        first = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        second = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void appliesReplaysAndReconstructsAnExactCompleteHead() throws Exception {
        Instant now = now();
        var floor = repository(now);
        var firstPublication = publication(1, "status-001", "", now,
                targets(1, SETTINGS, goodEvidence(now)));

        var applied = floor.accept(firstPublication);
        var replayed = floor.accept(firstPublication);
        var reconstructed = repository(now.plusSeconds(1));

        assertThat(applied.status()).isEqualTo(
                ControlPlaneCertificateStatusFloor.AcceptanceStatus.APPLIED);
        assertThat(replayed.status()).isEqualTo(
                ControlPlaneCertificateStatusFloor.AcceptanceStatus.REPLAYED);
        assertThat(reconstructed.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.initialized()).isTrue();
            assertThat(snapshot.sequence()).isEqualTo(1);
            assertThat(snapshot.publicationId()).isEqualTo("status-001");
            assertThat(snapshot.publicationFingerprint())
                    .isEqualTo(firstPublication.materialFingerprint());
            assertThat(snapshot.targets()).extracting(
                    ControlPlaneCertificateStatusPublication.TargetStatus::targetId)
                    .containsExactly(TARGET_A, TARGET_B);
            assertThat(snapshot.targets()).allMatch(
                    ControlPlaneCertificateStatusPublication.TargetStatus::admitted);
        });
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_cp_cert_status_targets", Integer.class)).isEqualTo(2);
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_cp_cert_status_journal", Integer.class)).isEqualTo(1);
        assertThat(reconstructed.durable()).isTrue();
    }

    @Test
    void rejectsCursorGapForkIdentityReuseAndIncompleteInventory() throws Exception {
        Instant now = now();
        var floor = repository(now);
        var head = publication(1, "status-001", "", now,
                targets(1, SETTINGS, goodEvidence(now)));
        floor.accept(head);

        assertThatThrownBy(() -> floor.accept(publication(3, "status-003",
                head.materialFingerprint(), now.plusSeconds(2),
                targets(1, SETTINGS, goodEvidence(now)))))
                .hasMessageContaining("cursor conflicts");
        assertThatThrownBy(() -> floor.accept(publication(2, "status-002",
                fingerprint('9'), now.plusSeconds(2),
                targets(1, SETTINGS, goodEvidence(now)))))
                .hasMessageContaining("cursor conflicts");
        assertThatThrownBy(() -> floor.accept(publication(2, "status-001",
                head.materialFingerprint(), now.plusSeconds(2),
                targets(1, SETTINGS, goodEvidence(now)))))
                .hasMessageContaining("identity was already used");
        assertThatThrownBy(() -> floor.accept(publication(2, "status-002",
                head.materialFingerprint(), now.plusSeconds(2),
                List.of(target(TARGET_A, 1, SETTINGS, goodEvidence(now))))))
                .hasMessageContaining("target inventory conflicts");
    }

    @Test
    void revocationIsIrreversibleAndGenerationIdentityCannotDrift() throws Exception {
        Instant now = now();
        var floor = repository(now);
        var firstPublication = publication(1, "status-001", "", now,
                targets(1, SETTINGS, goodEvidence(now)));
        floor.accept(firstPublication);
        var revokedEvidence = List.of(
                evidence(ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.REVOKED,
                        "KEY_COMPROMISE", now, now.plusSeconds(3600)),
                evidence(ControlPlaneCertificateStatusPublication.CertificateRole.SERVER,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                        "CERTIFICATE_GOOD", now, now.plusSeconds(3600)));
        var revoked = publication(2, "status-002", firstPublication.materialFingerprint(),
                now.plusSeconds(2), targets(1, SETTINGS, revokedEvidence));
        floor.accept(revoked);

        assertThat(floor.snapshot().targets()).allMatch(target -> !target.admitted());
        assertThatThrownBy(() -> floor.accept(publication(3, "status-003",
                revoked.materialFingerprint(), now.plusSeconds(4),
                targets(1, SETTINGS, goodEvidence(now)))))
                .hasMessageContaining("revocation is not monotonic");
        assertThatThrownBy(() -> floor.accept(publication(3, "status-003b",
                revoked.materialFingerprint(), now.plusSeconds(4),
                targets(1, fingerprint('b'), revokedEvidence))))
                .hasMessageContaining("generation is not monotonic");
        assertThatThrownBy(() -> floor.accept(publication(3, "status-003c",
                revoked.materialFingerprint(), now.plusSeconds(4),
                targets(0, SETTINGS, revokedEvidence))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void laterCertificateGenerationCanReplaceRevokedIdentityButCannotRollBack() throws Exception {
        Instant now = now();
        var floor = repository(now);
        var revokedEvidence = List.of(
                evidence(ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.REVOKED,
                        "KEY_COMPROMISE", now, now.plusSeconds(3600)),
                evidence(ControlPlaneCertificateStatusPublication.CertificateRole.SERVER,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                        "CERTIFICATE_GOOD", now, now.plusSeconds(3600)));
        var revoked = publication(1, "status-001", "", now,
                targets(1, SETTINGS, revokedEvidence));
        floor.accept(revoked);
        var rotated = publication(2, "status-002", revoked.materialFingerprint(),
                now.plusSeconds(2), targets(2, fingerprint('b'), goodEvidence(now)));

        assertThat(floor.accept(rotated).snapshot().targets())
                .allMatch(ControlPlaneCertificateStatusPublication.TargetStatus::admitted);
        assertThatThrownBy(() -> floor.accept(publication(3, "status-003",
                rotated.materialFingerprint(), now.plusSeconds(4),
                targets(1, SETTINGS, revokedEvidence))))
                .hasMessageContaining("generation is not monotonic");
    }

    @Test
    void databaseTimeAndTrustVerificationFailClosedBeforeMutation() throws Exception {
        Instant now = now();
        var floor = repository(now);
        var expired = publication(1, "status-expired", "", now.minusSeconds(7200),
                now.minusSeconds(3600), targets(1, SETTINGS, evidenceUntil(
                        now.minusSeconds(1800))));

        assertThatThrownBy(() -> floor.accept(expired))
                .hasMessageContaining("not authorized");

        var valid = publication(1, "status-001", "", now,
                targets(1, SETTINGS, goodEvidence(now)));
        var unsignedByPolicy = new ControlPlaneCertificateStatusPublication(
                valid.schemaVersion(), valid.material(), valid.materialFingerprint(),
                List.of(valid.signatures().getFirst()));
        assertThatThrownBy(() -> floor.accept(unsignedByPolicy))
                .hasMessageContaining("not authorized");
        assertThat(floor.snapshot().initialized()).isFalse();
    }

    @Test
    void headTargetAndJournalMutationEachFailClosed() throws Exception {
        Instant now = now();
        var floor = repository(now);
        floor.accept(publication(1, "status-001", "", now,
                targets(1, SETTINGS, goodEvidence(now))));

        String targetFingerprint = database.jdbc().queryForObject("""
                SELECT record_fingerprint FROM rg_cp_cert_status_targets
                WHERE deployment_scope_id = ? AND target_id = ?
                """, String.class, SCOPE, TARGET_A);
        database.jdbc().update("""
                UPDATE rg_cp_cert_status_targets SET record_fingerprint = ?
                WHERE deployment_scope_id = ? AND target_id = ?
                """, fingerprint('9'), SCOPE, TARGET_A);
        assertThatThrownBy(floor::snapshot).hasMessageContaining("target row is corrupt");
        database.jdbc().update("""
                UPDATE rg_cp_cert_status_targets SET record_fingerprint = ?
                WHERE deployment_scope_id = ? AND target_id = ?
                """, targetFingerprint, SCOPE, TARGET_A);

        String journalFingerprint = database.jdbc().queryForObject("""
                SELECT record_fingerprint FROM rg_cp_cert_status_journal
                WHERE deployment_scope_id = ? AND sequence = 1
                """, String.class, SCOPE);
        database.jdbc().update("""
                UPDATE rg_cp_cert_status_journal SET record_fingerprint = ?
                WHERE deployment_scope_id = ? AND sequence = 1
                """, fingerprint('8'), SCOPE);
        assertThatThrownBy(floor::snapshot).hasMessageContaining("journal head is corrupt");
        database.jdbc().update("""
                UPDATE rg_cp_cert_status_journal SET record_fingerprint = ?
                WHERE deployment_scope_id = ? AND sequence = 1
                """, journalFingerprint, SCOPE);

        database.jdbc().update("""
                UPDATE rg_cp_cert_status_heads SET record_fingerprint = ?
                WHERE deployment_scope_id = ?
                """, fingerprint('7'), SCOPE);
        assertThatThrownBy(floor::snapshot).hasMessageContaining("head is corrupt");
    }

    @Test
    void competingSuccessorsLinearizeToOneCompleteWinner() throws Exception {
        Instant now = now();
        var left = repository(now);
        var right = repository(now);
        var initial = publication(1, "status-001", "", now,
                targets(1, SETTINGS, goodEvidence(now)));
        left.accept(initial);
        var leftNext = publication(2, "status-left", initial.materialFingerprint(),
                now.plusSeconds(2), targets(1, SETTINGS, goodEvidence(now)));
        var rightNext = publication(2, "status-right", initial.materialFingerprint(),
                now.plusSeconds(2), targets(1, SETTINGS, goodEvidence(now)));
        CountDownLatch start = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<String> leftResult = workers.submit(() -> acceptAfter(start, left, leftNext));
            Future<String> rightResult = workers.submit(() -> acceptAfter(
                    start, right, rightNext));
            start.countDown();
            assertThat(List.of(leftResult.get(), rightResult.get()))
                    .satisfiesExactlyInAnyOrder(
                            result -> assertThat(result).isEqualTo("APPLIED"),
                            result -> assertThat(result).contains("cursor conflicts"));
        }
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM rg_cp_cert_status_journal
                WHERE deployment_scope_id = ? AND sequence = 2
                """, Integer.class, SCOPE)).isEqualTo(1);
        assertThat(left.snapshot().targets()).hasSize(2);
    }

    private DatabaseControlPlaneCertificateStatusFloor repository(Instant trustClock) {
        var trust = new ConfiguredControlPlaneCertificateStatusTrustStore(
                objectMapper, Clock.fixed(trustClock, ZoneOffset.UTC), "enterprise-pki",
                Set.of(POLICY), 2, List.of(key("authority-a", "key-a", first),
                key("authority-b", "key-b", second)));
        var floor = new DatabaseControlPlaneCertificateStatusFloor(
                database.jdbc(), objectMapper, trust, SCOPE, 0, BASELINE,
                List.of(new ControlPlaneCertificateStatusFloor.ExpectedTarget(TARGET_A),
                        new ControlPlaneCertificateStatusFloor.ExpectedTarget(TARGET_B)),
                database.transactionManager());
        floor.init();
        return floor;
    }

    private static ConfiguredControlPlaneCertificateStatusTrustStore.AuthorityKey key(
            String authorityId, String keyId, KeyPair keyPair) {
        Instant now = now();
        return new ConfiguredControlPlaneCertificateStatusTrustStore.AuthorityKey(
                authorityId, keyId, keyPair.getPublic(), now.minusSeconds(24 * 3600),
                now.plusSeconds(24 * 3600), true, false);
    }

    private ControlPlaneCertificateStatusPublication publication(
            long sequence,
            String publicationId,
            String predecessor,
            Instant issuedAt,
            List<ControlPlaneCertificateStatusPublication.TargetStatus> targets)
            throws Exception {
        return publication(sequence, publicationId, predecessor, issuedAt,
                issuedAt.plusSeconds(1800), targets);
    }

    private ControlPlaneCertificateStatusPublication publication(
            long sequence,
            String publicationId,
            String predecessor,
            Instant issuedAt,
            Instant expiresAt,
            List<ControlPlaneCertificateStatusPublication.TargetStatus> targets)
            throws Exception {
        var material = new ControlPlaneCertificateStatusPublication.Material(
                ControlPlaneCertificateStatusPublication.Material.SCHEMA_VERSION,
                "enterprise-pki", publicationId, SCOPE, sequence, predecessor, POLICY,
                issuedAt, expiresAt, targets);
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new ControlPlaneCertificateStatusPublication(
                ControlPlaneCertificateStatusPublication.SCHEMA_VERSION,
                material, fingerprint, List.of(
                signature("authority-a", "key-a", first, fingerprint, issuedAt),
                signature("authority-b", "key-b", second, fingerprint, issuedAt)));
    }

    private static List<ControlPlaneCertificateStatusPublication.TargetStatus> targets(
            long generation,
            String settingsFingerprint,
            List<ControlPlaneCertificateStatusPublication.CertificateEvidence> evidence) {
        return List.of(target(TARGET_A, generation, settingsFingerprint, evidence),
                target(TARGET_B, generation, settingsFingerprint, evidence));
    }

    private static ControlPlaneCertificateStatusPublication.TargetStatus target(
            String targetId,
            long generation,
            String settingsFingerprint,
            List<ControlPlaneCertificateStatusPublication.CertificateEvidence> evidence) {
        return new ControlPlaneCertificateStatusPublication.TargetStatus(
                targetId, generation, settingsFingerprint, evidence);
    }

    private static List<ControlPlaneCertificateStatusPublication.CertificateEvidence>
    goodEvidence(Instant now) {
        return List.of(
                evidence(ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                        "CERTIFICATE_GOOD", now, now.plusSeconds(3600)),
                evidence(ControlPlaneCertificateStatusPublication.CertificateRole.SERVER,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                        "CERTIFICATE_GOOD", now, now.plusSeconds(3600)));
    }

    private static List<ControlPlaneCertificateStatusPublication.CertificateEvidence>
    evidenceUntil(Instant nextUpdate) {
        Instant thisUpdate = nextUpdate.minusSeconds(3600);
        return goodEvidence(thisUpdate).stream().map(evidence ->
                new ControlPlaneCertificateStatusPublication.CertificateEvidence(
                        evidence.role(), evidence.status(), evidence.evidenceType(),
                        evidence.certificateFingerprint(), evidence.issuerSpkiFingerprint(),
                        evidence.evidenceFingerprint(), evidence.reasonCode(), thisUpdate,
                        thisUpdate, nextUpdate)).toList();
    }

    private static ControlPlaneCertificateStatusPublication.CertificateEvidence evidence(
            ControlPlaneCertificateStatusPublication.CertificateRole role,
            ControlPlaneCertificateStatusPublication.CertificateStatus status,
            String reason,
            Instant thisUpdate,
            Instant nextUpdate) {
        return new ControlPlaneCertificateStatusPublication.CertificateEvidence(
                role, status, ControlPlaneCertificateStatusPublication.EvidenceType.OCSP,
                role == ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT
                        ? fingerprint('c') : fingerprint('d'),
                fingerprint('e'), fingerprint('6'), reason,
                thisUpdate, thisUpdate, nextUpdate);
    }

    private static ControlPlaneCertificateStatusPublication.AuthoritySignature signature(
            String authorityId,
            String keyId,
            KeyPair keyPair,
            String fingerprint,
            Instant signedAt) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ControlPlaneCertificateStatusPublication.AuthoritySignature(
                authorityId, keyId, "Ed25519", signedAt,
                Base64.getEncoder().encodeToString(signer.sign()));
    }

    private static String acceptAfter(
            CountDownLatch start,
            DatabaseControlPlaneCertificateStatusFloor floor,
            ControlPlaneCertificateStatusPublication publication) throws Exception {
        start.await();
        try {
            return floor.accept(publication).status().name();
        } catch (RuntimeException failure) {
            return failure.getMessage();
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
