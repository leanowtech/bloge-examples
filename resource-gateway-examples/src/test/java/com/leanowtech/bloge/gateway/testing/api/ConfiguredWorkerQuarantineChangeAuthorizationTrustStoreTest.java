package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredWorkerQuarantineChangeAuthorizationTrustStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");
    private static final String SCOPE = "sha256:" + "a".repeat(64);
    private static final String SUBJECT = "sha256:" + "b".repeat(64);
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
    void verifiesExactBindingWithDistinctAuthorityQuorum() throws Exception {
        var store = store(2, List.of(key("authority-a", "key-a", authorityA),
                key("authority-b", "key-b", authorityB)));
        WorkerQuarantineChangeAuthorization authorization = authorization(
                material(NOW.minusSeconds(30), NOW.minusSeconds(30), NOW.plusSeconds(300)),
                signer("authority-a", "key-a", authorityA, NOW.minusSeconds(20)),
                signer("authority-b", "key-b", authorityB, NOW.minusSeconds(10)));

        var result = store.verify(authorization, binding(), NOW);

        assertThat(result.verified()).isTrue();
        assertThat(result.status()).isEqualTo(
                WorkerQuarantineChangeAuthorizationTrustStore.VerificationStatus.VERIFIED);
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(result.authorizationId()).isEqualTo("change-approval-123");
        assertThat(result.materialFingerprint()).isEqualTo(authorization.materialFingerprint());
        assertThat(result.validSignatureCount()).isEqualTo(2);
        assertThat(result.requiredSignatureCount()).isEqualTo(2);
        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.trustDomain()).isEqualTo("aneke-change-governance");
            assertThat(descriptor.authorityCount()).isEqualTo(2);
            assertThat(descriptor.keyCount()).isEqualTo(2);
            assertThat(descriptor.acceptedPolicyCount()).isEqualTo(1);
            assertThat(descriptor.properties()).containsEntry("privateMaterialPresent", false);
        });
    }

    @Test
    void unavailableStoreFailsClosedWithoutLeakingAuthorizationIdentity() throws Exception {
        WorkerQuarantineChangeAuthorization authorization = authorization(
                material(NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(60)),
                signer("authority-a", "key-a", authorityA, NOW));

        var result = WorkerQuarantineChangeAuthorizationTrustStore.unavailable()
                .verify(authorization, binding(), NOW);

        assertThat(result.status()).isEqualTo(
                WorkerQuarantineChangeAuthorizationTrustStore.VerificationStatus.UNAVAILABLE);
        assertThat(result.authorizationId()).isEmpty();
        assertThat(result.materialFingerprint()).isEmpty();
        assertThat(WorkerQuarantineChangeAuthorizationTrustStore.unavailable()
                .descriptor().available()).isFalse();
    }

    @Test
    void rejectsTrustDomainScopeSubjectAndPolicyDriftBeforeSignatureClaims() throws Exception {
        var store = store(1, List.of(key("authority-a", "key-a", authorityA)));
        WorkerQuarantineChangeAuthorization exact = authorization(
                material(NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(60)),
                signer("authority-a", "key-a", authorityA, NOW));

        assertThat(store.verify(exact,
                new WorkerQuarantineChangeAuthorizationTrustStore.ExpectedBinding(
                        "sha256:" + "d".repeat(64), SUBJECT), NOW).status()).isEqualTo(
                WorkerQuarantineChangeAuthorizationTrustStore.VerificationStatus.BINDING_MISMATCH);
        assertThat(store.verify(exact,
                new WorkerQuarantineChangeAuthorizationTrustStore.ExpectedBinding(
                        SCOPE, "sha256:" + "e".repeat(64)), NOW).status()).isEqualTo(
                WorkerQuarantineChangeAuthorizationTrustStore.VerificationStatus.BINDING_MISMATCH);

        WorkerQuarantineChangeAuthorization.Material wrongDomain = newMaterial(
                "other-governance", POLICY, NOW.minusSeconds(1),
                NOW.minusSeconds(1), NOW.plusSeconds(60));
        assertThat(store.verify(authorization(wrongDomain,
                        signer("authority-a", "key-a", authorityA, NOW)), binding(), NOW).status())
                .isEqualTo(WorkerQuarantineChangeAuthorizationTrustStore
                        .VerificationStatus.BINDING_MISMATCH);

        WorkerQuarantineChangeAuthorization.Material wrongPolicy = newMaterial(
                "aneke-change-governance", "sha256:" + "f".repeat(64),
                NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(60));
        assertThat(store.verify(authorization(wrongPolicy,
                        signer("authority-a", "key-a", authorityA, NOW)), binding(), NOW).status())
                .isEqualTo(WorkerQuarantineChangeAuthorizationTrustStore
                        .VerificationStatus.POLICY_REJECTED);
    }

    @Test
    void rejectsPrematureExpiredFutureAndExcessiveAuthorizationWindows() throws Exception {
        var store = store(1, List.of(key("authority-a", "key-a", authorityA)));

        assertTimeRejected(store, material(NOW.minusSeconds(10), NOW.plusSeconds(1),
                NOW.plusSeconds(60)), NOW);
        assertTimeRejected(store, material(NOW.minusSeconds(60), NOW.minusSeconds(60), NOW), NOW);
        assertTimeRejected(store, material(NOW.plus(Duration.ofMinutes(6)),
                NOW.plus(Duration.ofMinutes(6)), NOW.plus(Duration.ofMinutes(7))), NOW);
        assertTimeRejected(store, material(NOW.minusSeconds(1), NOW.minusSeconds(1),
                NOW.plus(Duration.ofHours(24)).plusSeconds(1)), NOW);
    }

    @Test
    void rejectsTamperedMaterialAndTrustedBadSignature() throws Exception {
        var store = store(1, List.of(key("authority-a", "key-a", authorityA)));
        WorkerQuarantineChangeAuthorization valid = authorization(
                material(NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(60)),
                signer("authority-a", "key-a", authorityA, NOW));
        WorkerQuarantineChangeAuthorization tampered = new WorkerQuarantineChangeAuthorization(
                WorkerQuarantineChangeAuthorization.SCHEMA_VERSION,
                newMaterial("aneke-change-governance", POLICY, NOW.minusSeconds(2),
                        NOW.minusSeconds(1), NOW.plusSeconds(60)),
                valid.materialFingerprint(), valid.signatures());

        assertThat(store.verify(tampered, binding(), NOW).status()).isEqualTo(
                WorkerQuarantineChangeAuthorizationTrustStore.VerificationStatus.MATERIAL_INVALID);

        WorkerQuarantineChangeAuthorization wrongSigner = authorization(
                valid.material(), signer("authority-a", "key-a", authorityB, NOW));
        assertThat(store.verify(wrongSigner, binding(), NOW).status()).isEqualTo(
                WorkerQuarantineChangeAuthorizationTrustStore.VerificationStatus.SIGNATURE_INVALID);
    }

    @Test
    void ignoresUnknownOrInactiveKeysButRequiresConfiguredAuthorityQuorum() throws Exception {
        var revoked = new ConfiguredWorkerQuarantineChangeAuthorizationTrustStore.AuthorityKey(
                "authority-b", "key-b", authorityB.getPublic(), Instant.MIN, Instant.MAX,
                true, true);
        var store = store(2, List.of(key("authority-a", "key-a", authorityA), revoked));
        WorkerQuarantineChangeAuthorization authorization = authorization(
                material(NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(60)),
                signer("authority-a", "key-a", authorityA, NOW),
                signer("authority-b", "key-b", authorityB, NOW),
                signer("unknown-authority", "unknown-key", authorityC, NOW));

        var result = store.verify(authorization, binding(), NOW);

        assertThat(result.status()).isEqualTo(
                WorkerQuarantineChangeAuthorizationTrustStore.VerificationStatus.QUORUM_NOT_MET);
        assertThat(result.validSignatureCount()).isEqualTo(1);
        assertThat(result.authorizationId()).isEmpty();
    }

    @Test
    void parsesPublicKeyConfigurationWithoutPrivateMaterial() {
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

        var store = ConfiguredWorkerQuarantineChangeAuthorizationTrustStore.fromJson(
                objectMapper, "aneke-change-governance", POLICY, 1, json);

        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.keyCount()).isEqualTo(1);
            assertThat(descriptor.signatureThreshold()).isEqualTo(1);
            assertThat(descriptor.properties()).doesNotContainKeys(
                    "publicKey", "privateKey", "publicKeyBase64");
        });
        assertThatThrownBy(() ->
                ConfiguredWorkerQuarantineChangeAuthorizationTrustStore.fromJson(
                        objectMapper, "aneke-change-governance", "not-a-fingerprint", 1, json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust configuration");
    }

    @Test
    void protocolModelsRejectDuplicateAuthoritiesAndMalformedBindings() throws Exception {
        WorkerQuarantineChangeAuthorization.Material material = material(
                NOW.minusSeconds(1), NOW.minusSeconds(1), NOW.plusSeconds(60));
        var first = signer("authority-a", "key-a", authorityA, NOW);
        var second = signer("authority-a", "key-a-rotated", authorityB, NOW);

        assertThatThrownBy(() -> authorization(material, first, second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeats an authority");
        assertThatThrownBy(() ->
                new WorkerQuarantineChangeAuthorizationTrustStore.ExpectedBinding(
                        "sha256:ABC", SUBJECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding is invalid");
        assertThatThrownBy(() ->
                new WorkerQuarantineChangeAuthorizationTrustStore.Verification(
                        WorkerQuarantineChangeAuthorizationTrustStore.VerificationStatus
                                .SIGNATURE_INVALID,
                        "CHANGE_AUTHORIZATION_SIGNATURE_INVALID", "change-approval-123", "",
                        0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verification identity is invalid");
        assertThatThrownBy(() -> new WorkerQuarantineChangeAuthorization.AuthoritySignature(
                "authority-a", "key-a", "Ed25519", NOW,
                Base64.getEncoder().encodeToString(new byte[63])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature is invalid");
    }

    private void assertTimeRejected(
            ConfiguredWorkerQuarantineChangeAuthorizationTrustStore store,
            WorkerQuarantineChangeAuthorization.Material material,
            Instant observedAt) throws Exception {
        WorkerQuarantineChangeAuthorization authorization = authorization(material,
                signer("authority-a", "key-a", authorityA, observedAt));
        assertThat(store.verify(authorization, binding(), observedAt).status()).isEqualTo(
                WorkerQuarantineChangeAuthorizationTrustStore.VerificationStatus.TIME_INVALID);
    }

    private ConfiguredWorkerQuarantineChangeAuthorizationTrustStore store(
            int threshold,
            List<ConfiguredWorkerQuarantineChangeAuthorizationTrustStore.AuthorityKey> keys) {
        return new ConfiguredWorkerQuarantineChangeAuthorizationTrustStore(
                objectMapper, "aneke-change-governance", Set.of(POLICY), threshold, keys);
    }

    private static ConfiguredWorkerQuarantineChangeAuthorizationTrustStore.AuthorityKey key(
            String authorityId, String keyId, KeyPair pair) {
        return new ConfiguredWorkerQuarantineChangeAuthorizationTrustStore.AuthorityKey(
                authorityId, keyId, pair.getPublic(), Instant.MIN, Instant.MAX, true, false);
    }

    private WorkerQuarantineChangeAuthorization authorization(
            WorkerQuarantineChangeAuthorization.Material material,
            Signer... signers) throws Exception {
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        List<WorkerQuarantineChangeAuthorization.AuthoritySignature> signatures =
                java.util.Arrays.stream(signers)
                        .map(signer -> signer.sign(fingerprint))
                        .toList();
        return new WorkerQuarantineChangeAuthorization(
                WorkerQuarantineChangeAuthorization.SCHEMA_VERSION,
                material, fingerprint, signatures);
    }

    private static WorkerQuarantineChangeAuthorization.Material material(
            Instant issuedAt, Instant notBefore, Instant expiresAt) {
        return newMaterial("aneke-change-governance", POLICY,
                issuedAt, notBefore, expiresAt);
    }

    private static WorkerQuarantineChangeAuthorization.Material newMaterial(
            String trustDomain,
            String policyFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {
        return new WorkerQuarantineChangeAuthorization.Material(
                WorkerQuarantineChangeAuthorization.Material.SCHEMA_VERSION,
                trustDomain, "change-approval-123",
                WorkerQuarantineChangeAuthorization.Material.DISCARD_ACTION,
                SCOPE, SUBJECT, policyFingerprint, issuedAt, notBefore, expiresAt);
    }

    private static WorkerQuarantineChangeAuthorizationTrustStore.ExpectedBinding binding() {
        return new WorkerQuarantineChangeAuthorizationTrustStore.ExpectedBinding(SCOPE, SUBJECT);
    }

    private static Signer signer(
            String authorityId, String keyId, KeyPair keyPair, Instant signedAt) {
        return new Signer(authorityId, keyId, keyPair, signedAt);
    }

    private record Signer(
            String authorityId,
            String keyId,
            KeyPair keyPair,
            Instant signedAt) {
        private WorkerQuarantineChangeAuthorization.AuthoritySignature sign(String fingerprint) {
            try {
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(keyPair.getPrivate());
                signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                return new WorkerQuarantineChangeAuthorization.AuthoritySignature(
                        authorityId, keyId, "Ed25519", signedAt,
                        Base64.getEncoder().encodeToString(signer.sign()));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
