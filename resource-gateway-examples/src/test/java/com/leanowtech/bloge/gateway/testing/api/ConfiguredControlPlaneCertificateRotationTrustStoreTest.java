package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredControlPlaneCertificateRotationTrustStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");
    private static final String PREVIOUS = "sha256:" + "a".repeat(64);
    private static final String SETTINGS = "sha256:" + "b".repeat(64);
    private static final String POLICY = "sha256:" + "c".repeat(64);

    private ObjectMapper objectMapper;
    private KeyPair authorityA;
    private KeyPair authorityB;
    private KeyPair authorityC;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        authorityA = generator.generateKeyPair();
        authorityB = generator.generateKeyPair();
        authorityC = generator.generateKeyPair();
    }

    @Test
    void verifiesExactTransportBindingWithDistinctAuthorityQuorum() throws Exception {
        var store = store(2, List.of(key("authority-a", "key-a", authorityA),
                key("authority-b", "key-b", authorityB)));
        ControlPlaneCertificateRotationEvent event = event(material(
                        NOW.minusSeconds(30), NOW.minusSeconds(20),
                        NOW.plusSeconds(300), NOW.plusSeconds(3_600)),
                signer("authority-a", "key-a", authorityA, NOW.minusSeconds(10)),
                signer("authority-b", "key-b", authorityB, NOW.minusSeconds(5)));

        var result = store.verify(event, binding(), NOW);

        assertThat(result.verified()).isTrue();
        assertThat(result.status()).isEqualTo(
                ControlPlaneCertificateRotationTrustStore.VerificationStatus.VERIFIED);
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(result.eventId()).isEqualTo("rotation-change-42");
        assertThat(result.eventFingerprint()).isEqualTo(event.materialFingerprint());
        assertThat(result.materialFingerprint()).isEqualTo(SETTINGS);
        assertThat(result.validSignatureCount()).isEqualTo(2);
        assertThat(result.requiredSignatureCount()).isEqualTo(2);
        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.trustDomain()).isEqualTo("enterprise-certificate-rotation");
            assertThat(descriptor.authorityCount()).isEqualTo(2);
            assertThat(descriptor.keyCount()).isEqualTo(2);
            assertThat(descriptor.signatureThreshold()).isEqualTo(2);
            assertThat(descriptor.acceptedPolicyCount()).isEqualTo(1);
            assertThat(descriptor.properties())
                    .containsEntry("algorithm", "Ed25519")
                    .containsEntry("privateMaterialPresent", false)
                    .doesNotContainKeys("publicKey", "materialId", "settingsFingerprint");
        });
    }

    @Test
    void unavailableTrustFailsClosedWithoutExposingRotationIdentity() throws Exception {
        ControlPlaneCertificateRotationEvent event = event(material(
                        NOW.minusSeconds(1), NOW.minusSeconds(1),
                        NOW.plusSeconds(60), NOW.plusSeconds(600)),
                signer("authority-a", "key-a", authorityA, NOW));

        var result = ControlPlaneCertificateRotationTrustStore.unavailable()
                .verify(event, binding(), NOW);

        assertThat(result.status()).isEqualTo(
                ControlPlaneCertificateRotationTrustStore.VerificationStatus.UNAVAILABLE);
        assertThat(result.eventId()).isEmpty();
        assertThat(result.materialFingerprint()).isEmpty();
        assertThat(ControlPlaneCertificateRotationTrustStore.unavailable()
                .descriptor().available()).isFalse();
    }

    @Test
    void rejectsDeploymentTransportTrustDomainAndPolicyDrift() throws Exception {
        var store = store(1, List.of(key("authority-a", "key-a", authorityA)));
        ControlPlaneCertificateRotationEvent exact = event(material(
                        NOW.minusSeconds(1), NOW.minusSeconds(1),
                        NOW.plusSeconds(60), NOW.plusSeconds(600)),
                signer("authority-a", "key-a", authorityA, NOW));

        assertThat(store.verify(exact,
                new ControlPlaneCertificateRotationTrustStore.ExpectedBinding(
                        "other-deployment", "recovery-fleet.publisher"), NOW).status())
                .isEqualTo(ControlPlaneCertificateRotationTrustStore.VerificationStatus
                        .BINDING_MISMATCH);
        assertThat(store.verify(exact,
                new ControlPlaneCertificateRotationTrustStore.ExpectedBinding(
                        "rg-staging-sg", "other.transport"), NOW).status())
                .isEqualTo(ControlPlaneCertificateRotationTrustStore.VerificationStatus
                        .BINDING_MISMATCH);

        var wrongDomain = newMaterial("other-domain", POLICY,
                NOW.minusSeconds(1), NOW.minusSeconds(1),
                NOW.plusSeconds(60), NOW.plusSeconds(600));
        assertThat(store.verify(event(wrongDomain,
                        signer("authority-a", "key-a", authorityA, NOW)), binding(), NOW).status())
                .isEqualTo(ControlPlaneCertificateRotationTrustStore.VerificationStatus
                        .BINDING_MISMATCH);

        var wrongPolicy = newMaterial("enterprise-certificate-rotation",
                "sha256:" + "d".repeat(64), NOW.minusSeconds(1), NOW.minusSeconds(1),
                NOW.plusSeconds(60), NOW.plusSeconds(600));
        assertThat(store.verify(event(wrongPolicy,
                        signer("authority-a", "key-a", authorityA, NOW)), binding(), NOW).status())
                .isEqualTo(ControlPlaneCertificateRotationTrustStore.VerificationStatus
                        .POLICY_REJECTED);
    }

    @Test
    void rejectsPrematureExpiredFutureAndExcessiveManifestWindows() throws Exception {
        var store = store(1, List.of(key("authority-a", "key-a", authorityA)));

        assertTimeRejected(store, material(NOW.minusSeconds(10), NOW.plusSeconds(301),
                NOW.plusSeconds(302), NOW.plusSeconds(600)), NOW);
        assertTimeRejected(store, material(NOW.minusSeconds(10), NOW.plusSeconds(1),
                NOW.plusSeconds(60), NOW.plusSeconds(600)), NOW);
        assertTimeRejected(store, material(NOW.minusSeconds(600), NOW.minusSeconds(590),
                NOW.minusSeconds(300), NOW), NOW);
        assertTimeRejected(store, material(NOW.plus(Duration.ofMinutes(6)),
                NOW.plus(Duration.ofMinutes(6)), NOW.plus(Duration.ofMinutes(7)),
                NOW.plus(Duration.ofMinutes(8))), NOW);
        assertTimeRejected(store, material(NOW.minusSeconds(1), NOW,
                NOW.plusSeconds(60), NOW.plus(Duration.ofDays(7)).plusSeconds(1)), NOW);
    }

    @Test
    void rejectsSignatureOutsideManifestAndObservationWindows() throws Exception {
        var store = store(1, List.of(key("authority-a", "key-a", authorityA)));
        var material = material(NOW.minusSeconds(30), NOW.minusSeconds(20),
                NOW.plusSeconds(300), NOW.plusSeconds(600));

        assertThat(store.verify(event(material,
                        signer("authority-a", "key-a", authorityA,
                                NOW.minus(Duration.ofMinutes(6)))), binding(), NOW).status())
                .isEqualTo(ControlPlaneCertificateRotationTrustStore.VerificationStatus
                        .TIME_INVALID);
        assertThat(store.verify(event(material,
                        signer("authority-a", "key-a", authorityA,
                                NOW.plus(Duration.ofMinutes(6)))), binding(), NOW).status())
                .isEqualTo(ControlPlaneCertificateRotationTrustStore.VerificationStatus
                        .TIME_INVALID);
    }

    @Test
    void rejectsTamperedMaterialAndTrustedBadSignature() throws Exception {
        var store = store(1, List.of(key("authority-a", "key-a", authorityA)));
        ControlPlaneCertificateRotationEvent valid = event(material(
                        NOW.minusSeconds(1), NOW.minusSeconds(1),
                        NOW.plusSeconds(60), NOW.plusSeconds(600)),
                signer("authority-a", "key-a", authorityA, NOW));
        var tamperedMaterial = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                valid.material().trustDomain(), valid.material().eventId(),
                valid.material().deploymentScopeId(), valid.material().targetId(),
                valid.material().generation(), valid.material().previousMaterialFingerprint(),
                "certificate-material-c", valid.material().settingsFingerprint(),
                valid.material().policyFingerprint(), valid.material().issuedAt(),
                valid.material().notBefore(), valid.material().activateAt(),
                valid.material().expiresAt());
        var tampered = new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION, tamperedMaterial,
                valid.materialFingerprint(), valid.signatures());

        assertThat(store.verify(tampered, binding(), NOW).status()).isEqualTo(
                ControlPlaneCertificateRotationTrustStore.VerificationStatus.MATERIAL_INVALID);

        ControlPlaneCertificateRotationEvent wrongSigner = event(valid.material(),
                signer("authority-a", "key-a", authorityB, NOW));
        assertThat(store.verify(wrongSigner, binding(), NOW).status()).isEqualTo(
                ControlPlaneCertificateRotationTrustStore.VerificationStatus.SIGNATURE_INVALID);
    }

    @Test
    void ignoresUnknownAndRevokedKeysButStillRequiresQuorum() throws Exception {
        var revoked = new ConfiguredControlPlaneCertificateRotationTrustStore.AuthorityKey(
                "authority-b", "key-b", authorityB.getPublic(), Instant.MIN, Instant.MAX,
                true, true);
        var store = store(2, List.of(key("authority-a", "key-a", authorityA), revoked));
        ControlPlaneCertificateRotationEvent event = event(material(
                        NOW.minusSeconds(1), NOW.minusSeconds(1),
                        NOW.plusSeconds(60), NOW.plusSeconds(600)),
                signer("authority-a", "key-a", authorityA, NOW),
                signer("authority-b", "key-b", authorityB, NOW),
                signer("unknown-authority", "unknown-key", authorityC, NOW));

        var result = store.verify(event, binding(), NOW);

        assertThat(result.status()).isEqualTo(
                ControlPlaneCertificateRotationTrustStore.VerificationStatus.QUORUM_NOT_MET);
        assertThat(result.validSignatureCount()).isEqualTo(1);
        assertThat(result.eventId()).isEmpty();
    }

    @Test
    void parsesStrictPublicKeyConfigurationWithoutPrivateMaterial() {
        String json = """
                [{
                  "authorityId":"authority-a",
                  "keyId":"key-a",
                  "publicKeyBase64":"%s",
                  "notBefore":"2026-01-01T00:00:00Z",
                  "expiresAt":"2027-01-01T00:00:00Z",
                  "enabled":true,
                  "revoked":false
                }]
                """.formatted(Base64.getEncoder().encodeToString(
                authorityA.getPublic().getEncoded()));

        var store = ConfiguredControlPlaneCertificateRotationTrustStore.fromJson(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC),
                "enterprise-certificate-rotation", POLICY, 1, json);

        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.keyCount()).isEqualTo(1);
            assertThat(descriptor.signatureThreshold()).isEqualTo(1);
            assertThat(descriptor.properties()).doesNotContainKeys(
                    "publicKey", "privateKey", "publicKeyBase64");
        });
        assertThatThrownBy(() ->
                ConfiguredControlPlaneCertificateRotationTrustStore.fromJson(
                        objectMapper, "enterprise-certificate-rotation", POLICY, 1,
                        json.replace("\"revoked\":false", "\"revoked\":false,\"extra\":1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust configuration is invalid");
    }

    @Test
    void protocolModelsRejectNonSuccessorsDuplicateAuthoritiesAndUnsafeMaterialIds()
            throws Exception {
        var material = material(NOW.minusSeconds(1), NOW,
                NOW.plusSeconds(60), NOW.plusSeconds(600));
        var first = signer("authority-a", "key-a", authorityA, NOW);
        var second = signer("authority-a", "key-a-next", authorityB, NOW);

        assertThatThrownBy(() -> event(material, first, second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeats an authority");
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "enterprise-certificate-rotation", "rotation-change-42", "rg-staging-sg",
                "recovery-fleet.publisher", 1, PREVIOUS, "certificate-material-b",
                SETTINGS, POLICY, NOW.minusSeconds(1), NOW, NOW.plusSeconds(60),
                NOW.plusSeconds(600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("material is invalid");
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "enterprise-certificate-rotation", "rotation-change-42", "rg-staging-sg",
                "recovery-fleet.publisher", 2, PREVIOUS, "vault://secret/path",
                SETTINGS, POLICY, NOW.minusSeconds(1), NOW, NOW.plusSeconds(60),
                NOW.plusSeconds(600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("material is invalid");
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationTrustStore.ExpectedBinding(
                "", "recovery-fleet.publisher"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding is invalid");
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationEvent.AuthoritySignature(
                "authority-a", "key-a", "Ed25519", NOW,
                Base64.getEncoder().encodeToString(new byte[63])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature is invalid");
    }

    private void assertTimeRejected(
            ConfiguredControlPlaneCertificateRotationTrustStore store,
            ControlPlaneCertificateRotationEvent.Material material,
            Instant observedAt) throws Exception {
        ControlPlaneCertificateRotationEvent event = event(material,
                signer("authority-a", "key-a", authorityA, observedAt));
        assertThat(store.verify(event, binding(), observedAt).status()).isEqualTo(
                ControlPlaneCertificateRotationTrustStore.VerificationStatus.TIME_INVALID);
    }

    private ConfiguredControlPlaneCertificateRotationTrustStore store(
            int threshold,
            List<ConfiguredControlPlaneCertificateRotationTrustStore.AuthorityKey> keys) {
        return new ConfiguredControlPlaneCertificateRotationTrustStore(objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), "enterprise-certificate-rotation",
                Set.of(POLICY), threshold, keys);
    }

    private static ConfiguredControlPlaneCertificateRotationTrustStore.AuthorityKey key(
            String authorityId, String keyId, KeyPair pair) {
        return new ConfiguredControlPlaneCertificateRotationTrustStore.AuthorityKey(
                authorityId, keyId, pair.getPublic(), Instant.MIN, Instant.MAX, true, false);
    }

    private ControlPlaneCertificateRotationEvent event(
            ControlPlaneCertificateRotationEvent.Material material,
            Signer... signers) {
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        List<ControlPlaneCertificateRotationEvent.AuthoritySignature> signatures =
                Arrays.stream(signers).map(signer -> signer.sign(fingerprint)).toList();
        return new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION,
                material, fingerprint, signatures);
    }

    private static ControlPlaneCertificateRotationEvent.Material material(
            Instant issuedAt,
            Instant notBefore,
            Instant activateAt,
            Instant expiresAt) {
        return newMaterial("enterprise-certificate-rotation", POLICY,
                issuedAt, notBefore, activateAt, expiresAt);
    }

    private static ControlPlaneCertificateRotationEvent.Material newMaterial(
            String trustDomain,
            String policyFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant activateAt,
            Instant expiresAt) {
        return new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                trustDomain, "rotation-change-42", "rg-staging-sg",
                "recovery-fleet.publisher", 2, PREVIOUS, "certificate-material-b",
                SETTINGS, policyFingerprint, issuedAt, notBefore, activateAt, expiresAt);
    }

    private static ControlPlaneCertificateRotationTrustStore.ExpectedBinding binding() {
        return new ControlPlaneCertificateRotationTrustStore.ExpectedBinding(
                "rg-staging-sg", "recovery-fleet.publisher");
    }

    private static Signer signer(
            String authorityId, String keyId, KeyPair pair, Instant signedAt) {
        return new Signer(authorityId, keyId, pair, signedAt);
    }

    private record Signer(
            String authorityId,
            String keyId,
            KeyPair keyPair,
            Instant signedAt) {
        private ControlPlaneCertificateRotationEvent.AuthoritySignature sign(
                String fingerprint) {
            try {
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(keyPair.getPrivate());
                signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                return new ControlPlaneCertificateRotationEvent.AuthoritySignature(
                        authorityId, keyId, "Ed25519", signedAt,
                        Base64.getEncoder().encodeToString(signer.sign()));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
