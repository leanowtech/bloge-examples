package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateStatusSourceHeadTest {

    private static final String POLICY = "sha256:" + "a".repeat(64);
    private static final String HEAD = "sha256:" + "b".repeat(64);
    private static final String TAMPERED = "sha256:" + "c".repeat(64);
    private static final String OTHER_POLICY = "sha256:" + "d".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void verifiesExactZeroBaselineHeadWithIndependentAuthorityQuorum() throws Exception {
        KeyPair first = keyPair();
        KeyPair second = keyPair();
        ControlPlaneCertificateStatusSourceHead head = signed(
                material("head-001", "rg-staging", 0, HEAD,
                        NOW.minusSeconds(1), NOW.plusSeconds(60)), first, second);

        var verified = trust(first, second).verifySourceHead(head, binding(), NOW);

        assertThat(verified.verified()).isTrue();
        assertThat(verified.attestationId()).isEqualTo("head-001");
        assertThat(verified.attestationFingerprint()).isEqualTo(head.materialFingerprint());
        assertThat(verified.headSequence()).isZero();
        assertThat(verified.headPublicationFingerprint()).isEqualTo(HEAD);
        assertThat(verified.validSignatureCount()).isEqualTo(2);
    }

    @Test
    void rejectsBindingPolicyFingerprintAndQuorumFailuresWithoutIdentityLeak()
            throws Exception {
        KeyPair first = keyPair();
        KeyPair second = keyPair();
        ControlPlaneCertificateStatusSourceHead head = signed(
                material("head-002", "rg-staging", 9, HEAD,
                        NOW.minusSeconds(1), NOW.plusSeconds(60)), first, second);
        var trust = trust(first, second);

        var wrongBinding = trust.verifySourceHead(head,
                new ControlPlaneCertificateStatusTrustStore.ExpectedBinding("rg-other"), NOW);
        var tampered = new ControlPlaneCertificateStatusSourceHead(
                ControlPlaneCertificateStatusSourceHead.SCHEMA_VERSION,
                head.material(), TAMPERED, head.signatures());
        var onlyOne = sourceHead(head.material(), head.materialFingerprint(),
                signature("authority-a", "key-a", first, head.materialFingerprint()));
        var wrongPolicy = signed(new ControlPlaneCertificateStatusSourceHead.Material(
                ControlPlaneCertificateStatusSourceHead.Material.SCHEMA_VERSION,
                "enterprise-pki", "head-wrong-policy", "rg-staging", 9, HEAD,
                OTHER_POLICY, NOW.minusSeconds(1), NOW.plusSeconds(60)), first, second);
        var invalidSignature = sourceHead(head.material(), head.materialFingerprint(),
                signature("authority-a", "key-a", first, TAMPERED),
                signature("authority-b", "key-b", second, head.materialFingerprint()));

        assertThat(wrongBinding.status()).isEqualTo(
                ControlPlaneCertificateStatusTrustStore.VerificationStatus.BINDING_MISMATCH);
        assertThat(wrongBinding.attestationId()).isEmpty();
        assertThat(wrongBinding.headPublicationFingerprint()).isEmpty();
        assertThat(trust.verifySourceHead(tampered, binding(), NOW).status()).isEqualTo(
                ControlPlaneCertificateStatusTrustStore.VerificationStatus.MATERIAL_INVALID);
        assertThat(trust.verifySourceHead(wrongPolicy, binding(), NOW).status()).isEqualTo(
                ControlPlaneCertificateStatusTrustStore.VerificationStatus.POLICY_REJECTED);
        assertThat(trust.verifySourceHead(onlyOne, binding(), NOW).status()).isEqualTo(
                ControlPlaneCertificateStatusTrustStore.VerificationStatus.QUORUM_NOT_MET);
        assertThat(trust.verifySourceHead(invalidSignature, binding(), NOW).status()).isEqualTo(
                ControlPlaneCertificateStatusTrustStore.VerificationStatus.SIGNATURE_INVALID);
    }

    @Test
    void rejectsExpiredFutureAndExcessivelyLongHeadAttestations() throws Exception {
        KeyPair first = keyPair();
        KeyPair second = keyPair();
        var trust = trust(first, second);

        assertThat(trust.verifySourceHead(signed(material("expired", "rg-staging", 1,
                        HEAD, NOW.minusSeconds(60), NOW.minusSeconds(1)), first, second),
                binding(), NOW).status()).isEqualTo(
                ControlPlaneCertificateStatusTrustStore.VerificationStatus.TIME_INVALID);
        assertThat(trust.verifySourceHead(signed(material("future", "rg-staging", 1,
                        HEAD, NOW.plusSeconds(301), NOW.plusSeconds(360)), first, second),
                binding(), NOW).status()).isEqualTo(
                ControlPlaneCertificateStatusTrustStore.VerificationStatus.TIME_INVALID);
        assertThat(trust.verifySourceHead(signed(material("long-lived", "rg-staging", 1,
                        HEAD, NOW, NOW.plusSeconds(86_401)), first, second),
                binding(), NOW).status()).isEqualTo(
                ControlPlaneCertificateStatusTrustStore.VerificationStatus.TIME_INVALID);
    }

    @Test
    void enforcesBoundedCanonicalHeadShapeAndDistinctAuthorities() throws Exception {
        assertThatThrownBy(() -> material("head", "rg-staging", -1, HEAD,
                NOW, NOW.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> material("head", "rg-staging", 1, "", NOW,
                NOW.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        KeyPair key = keyPair();
        var material = material("head", "rg-staging", 1, HEAD,
                NOW, NOW.plusSeconds(60));
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        var duplicate = signature("authority-a", "key-a", key, fingerprint);
        assertThatThrownBy(() -> sourceHead(material, fingerprint, duplicate, duplicate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ConfiguredControlPlaneCertificateStatusTrustStore trust(
            KeyPair first, KeyPair second) {
        return new ConfiguredControlPlaneCertificateStatusTrustStore(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "enterprise-pki",
                Set.of(POLICY), 2, List.of(
                key("authority-a", "key-a", first),
                key("authority-b", "key-b", second)));
    }

    private static ConfiguredControlPlaneCertificateStatusTrustStore.AuthorityKey key(
            String authority, String keyId, KeyPair pair) {
        return new ConfiguredControlPlaneCertificateStatusTrustStore.AuthorityKey(
                authority, keyId, pair.getPublic(), NOW.minusSeconds(3600),
                NOW.plusSeconds(7200), true, false);
    }

    private ControlPlaneCertificateStatusSourceHead signed(
            ControlPlaneCertificateStatusSourceHead.Material material,
            KeyPair first,
            KeyPair second) throws Exception {
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return sourceHead(material, fingerprint,
                signature("authority-a", "key-a", first, fingerprint),
                signature("authority-b", "key-b", second, fingerprint));
    }

    private static ControlPlaneCertificateStatusSourceHead sourceHead(
            ControlPlaneCertificateStatusSourceHead.Material material,
            String fingerprint,
            ControlPlaneCertificateStatusPublication.AuthoritySignature... signatures) {
        return new ControlPlaneCertificateStatusSourceHead(
                ControlPlaneCertificateStatusSourceHead.SCHEMA_VERSION,
                material, fingerprint, List.of(signatures));
    }

    private static ControlPlaneCertificateStatusSourceHead.Material material(
            String id,
            String scope,
            long sequence,
            String fingerprint,
            Instant issuedAt,
            Instant expiresAt) {
        return new ControlPlaneCertificateStatusSourceHead.Material(
                ControlPlaneCertificateStatusSourceHead.Material.SCHEMA_VERSION,
                "enterprise-pki", id, scope, sequence, fingerprint, POLICY,
                issuedAt, expiresAt);
    }

    private static ControlPlaneCertificateStatusTrustStore.ExpectedBinding binding() {
        return new ControlPlaneCertificateStatusTrustStore.ExpectedBinding("rg-staging");
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static ControlPlaneCertificateStatusPublication.AuthoritySignature signature(
            String authority,
            String keyId,
            KeyPair keyPair,
            String fingerprint) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ControlPlaneCertificateStatusPublication.AuthoritySignature(
                authority, keyId, "Ed25519", NOW,
                Base64.getEncoder().encodeToString(signer.sign()));
    }
}
