package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredControlPlaneCertificateStatusTrustStore;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateStatusPublication;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateStatusSourceHead;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateStatusSourceHeadFloor;
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

class DatabaseControlPlaneCertificateStatusSourceHeadFloorTest {

    private static final String SCOPE = "resource-gateway-staging";
    private static final String BASELINE = fingerprint('0');
    private static final String POLICY = fingerprint('f');

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;
    private KeyPair first;
    private KeyPair second;

    @BeforeEach
    void setUp() throws Exception {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:certificate-status-source-head-" + UUID.randomUUID()
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
    void initializesReplaysAndReconstructsTheExactDurableHead() throws Exception {
        Instant now = now();
        var floor = repository(now);
        var head = sourceHead("head-002", 2, fingerprint('2'), now,
                now.plusSeconds(1800));

        var initialized = floor.accept(head);
        var replayed = floor.accept(head);
        var reconstructed = repository(now.plusSeconds(1));

        assertThat(initialized.status()).isEqualTo(
                ControlPlaneCertificateStatusSourceHeadFloor.AcceptanceStatus.INITIALIZED);
        assertThat(replayed.status()).isEqualTo(
                ControlPlaneCertificateStatusSourceHeadFloor.AcceptanceStatus.REPLAYED);
        assertThat(reconstructed.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.initialized()).isTrue();
            assertThat(snapshot.headSequence()).isEqualTo(2);
            assertThat(snapshot.headPublicationFingerprint()).isEqualTo(fingerprint('2'));
            assertThat(snapshot.attestationId()).isEqualTo("head-002");
            assertThat(snapshot.attestationFingerprint())
                    .isEqualTo(head.materialFingerprint());
            assertThat(snapshot.exactLagFrom(0, now.plusSeconds(1))).isEqualTo(2);
            assertThat(snapshot.exactLagFrom(2, now.plusSeconds(1))).isZero();
            assertThat(snapshot.exactLagFrom(0, now.plusSeconds(1800))).isEqualTo(-1);
            assertThat(snapshot.freshAt(now.plusSeconds(1))).isTrue();
        });
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_cp_cert_status_source_head_journal",
                Integer.class)).isEqualTo(1);
        assertThat(reconstructed.durable()).isTrue();
    }

    @Test
    void renewsTheSameHeadAndAdvancesWithoutRequiringEveryIntermediateSequence()
            throws Exception {
        Instant now = now();
        var floor = repository(now);
        floor.accept(sourceHead("head-002-a", 2, fingerprint('2'), now,
                now.plusSeconds(1800)));
        var renewed = floor.accept(sourceHead("head-002-b", 2, fingerprint('2'),
                now.plusSeconds(1), now.plusSeconds(1801)));
        var advanced = floor.accept(sourceHead("head-009", 9, fingerprint('9'),
                now.plusSeconds(2), now.plusSeconds(1802)));

        assertThat(renewed.status()).isEqualTo(
                ControlPlaneCertificateStatusSourceHeadFloor.AcceptanceStatus.RENEWED);
        assertThat(advanced.status()).isEqualTo(
                ControlPlaneCertificateStatusSourceHeadFloor.AcceptanceStatus.ADVANCED);
        assertThat(floor.snapshot().headSequence()).isEqualTo(9);
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_cp_cert_status_source_head_journal",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void rejectsRollbackForkStaleRenewalAndAttestationIdentityReuse() throws Exception {
        Instant now = now();
        var floor = repository(now);
        floor.accept(sourceHead("head-current", 4, fingerprint('4'), now,
                now.plusSeconds(1800)));

        assertThatThrownBy(() -> floor.accept(sourceHead("head-rollback", 3,
                fingerprint('3'), now.plusSeconds(1), now.plusSeconds(1801))))
                .hasMessageContaining("rolled back");
        assertThatThrownBy(() -> floor.accept(sourceHead("head-fork", 4,
                fingerprint('8'), now.plusSeconds(1), now.plusSeconds(1801))))
                .hasMessageContaining("forked");
        assertThatThrownBy(() -> floor.accept(sourceHead("head-stale", 4,
                fingerprint('4'), now.minusSeconds(1), now.plusSeconds(1801))))
                .hasMessageContaining("renewal is not newer");
        assertThatThrownBy(() -> floor.accept(sourceHead("head-current", 5,
                fingerprint('5'), now.plusSeconds(1), now.plusSeconds(1801))))
                .hasMessageContaining("identity was already used");
        assertThat(floor.snapshot().headSequence()).isEqualTo(4);
    }

    @Test
    void rejectsUnauthorizedExpiredFutureAndBaselineConflictingHeadsBeforeMutation()
            throws Exception {
        Instant now = now();
        var floor = repository(now);
        var valid = sourceHead("head-valid", 1, fingerprint('1'), now,
                now.plusSeconds(1800));
        var insufficientQuorum = new ControlPlaneCertificateStatusSourceHead(
                valid.schemaVersion(), valid.material(), valid.materialFingerprint(),
                List.of(valid.signatures().getFirst()));

        assertThatThrownBy(() -> floor.accept(insufficientQuorum))
                .hasMessageContaining("not authorized");
        assertThatThrownBy(() -> floor.accept(sourceHead("head-expired", 1,
                fingerprint('1'), now.minusSeconds(3600), now.minusSeconds(1))))
                .hasMessageContaining("not authorized");
        assertThatThrownBy(() -> floor.accept(sourceHead("head-future", 1,
                fingerprint('1'), now.plusSeconds(301), now.plusSeconds(1800))))
                .hasMessageContaining("not authorized");
        assertThatThrownBy(() -> floor.accept(sourceHead("head-baseline-fork", 0,
                fingerprint('8'), now, now.plusSeconds(1800))))
                .hasMessageContaining("baseline");
        assertThat(floor.snapshot().initialized()).isFalse();
    }

    @Test
    void headAndJournalMutationFailClosedAcrossRestart() throws Exception {
        Instant now = now();
        var floor = repository(now);
        floor.accept(sourceHead("head-002", 2, fingerprint('2'), now,
                now.plusSeconds(1800)));

        String journalFingerprint = database.jdbc().queryForObject("""
                SELECT record_fingerprint FROM rg_cp_cert_status_source_head_journal
                WHERE deployment_scope_id = ? AND attestation_id = ?
                """, String.class, SCOPE, "head-002");
        database.jdbc().update("""
                UPDATE rg_cp_cert_status_source_head_journal SET record_fingerprint = ?
                WHERE deployment_scope_id = ? AND attestation_id = ?
                """, fingerprint('8'), SCOPE, "head-002");
        assertThatThrownBy(floor::snapshot).hasMessageContaining("journal head is corrupt");
        database.jdbc().update("""
                UPDATE rg_cp_cert_status_source_head_journal SET record_fingerprint = ?
                WHERE deployment_scope_id = ? AND attestation_id = ?
                """, journalFingerprint, SCOPE, "head-002");

        database.jdbc().update("""
                UPDATE rg_cp_cert_status_source_heads SET record_fingerprint = ?
                WHERE deployment_scope_id = ?
                """, fingerprint('9'), SCOPE);
        assertThatThrownBy(floor::snapshot).hasMessageContaining("corrupt or drifted");
        assertThatThrownBy(() -> repository(now.plusSeconds(1)))
                .hasMessageContaining("corrupt or drifted");
    }

    @Test
    void competingSameSequenceHeadsLinearizeToOneWinner() throws Exception {
        Instant now = now();
        var left = repository(now);
        var right = repository(now);
        var leftHead = sourceHead("head-left", 3, fingerprint('3'), now,
                now.plusSeconds(1800));
        var rightHead = sourceHead("head-right", 3, fingerprint('7'), now,
                now.plusSeconds(1800));
        CountDownLatch start = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<String> leftResult = workers.submit(() -> acceptAfter(start, left, leftHead));
            Future<String> rightResult = workers.submit(() -> acceptAfter(
                    start, right, rightHead));
            start.countDown();
            assertThat(List.of(leftResult.get(), rightResult.get()))
                    .satisfiesExactlyInAnyOrder(
                            result -> assertThat(result).isEqualTo("INITIALIZED"),
                            result -> assertThat(result).contains("forked"));
        }
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_cp_cert_status_source_head_journal",
                Integer.class)).isEqualTo(1);
    }

    private DatabaseControlPlaneCertificateStatusSourceHeadFloor repository(
            Instant trustClock) {
        var trust = new ConfiguredControlPlaneCertificateStatusTrustStore(
                objectMapper, Clock.fixed(trustClock, ZoneOffset.UTC), "enterprise-pki",
                Set.of(POLICY), 2, List.of(key("authority-a", "key-a", first),
                key("authority-b", "key-b", second)));
        var floor = new DatabaseControlPlaneCertificateStatusSourceHeadFloor(
                database.jdbc(), objectMapper, trust, SCOPE, 0, BASELINE,
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

    private ControlPlaneCertificateStatusSourceHead sourceHead(
            String attestationId,
            long sequence,
            String publicationFingerprint,
            Instant issuedAt,
            Instant expiresAt) throws Exception {
        var material = new ControlPlaneCertificateStatusSourceHead.Material(
                ControlPlaneCertificateStatusSourceHead.Material.SCHEMA_VERSION,
                "enterprise-pki", attestationId, SCOPE, sequence,
                publicationFingerprint, POLICY, issuedAt, expiresAt);
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new ControlPlaneCertificateStatusSourceHead(
                ControlPlaneCertificateStatusSourceHead.SCHEMA_VERSION, material,
                fingerprint, List.of(
                signature("authority-a", "key-a", first, fingerprint, issuedAt),
                signature("authority-b", "key-b", second, fingerprint, issuedAt)));
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
            DatabaseControlPlaneCertificateStatusSourceHeadFloor floor,
            ControlPlaneCertificateStatusSourceHead sourceHead) throws Exception {
        start.await();
        try {
            return floor.accept(sourceHead).status().name();
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
