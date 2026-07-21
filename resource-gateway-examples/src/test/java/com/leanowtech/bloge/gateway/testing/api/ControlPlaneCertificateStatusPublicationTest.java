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

class ControlPlaneCertificateStatusPublicationTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String OTHER_FINGERPRINT = "sha256:" + "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void verifiesIndependentQuorumAndExactDeploymentBinding() throws Exception {
        KeyPair first = keyPair();
        KeyPair second = keyPair();
        ControlPlaneCertificateStatusPublication.Material material = material(
                1, "", NOW.plusSeconds(3600), goodTargets());
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        var publication = publication(material, fingerprint,
                signature("authority-a", "key-a", first, fingerprint),
                signature("authority-b", "key-b", second, fingerprint));
        var trust = trust(first, second);

        var verified = trust.verify(publication,
                new ControlPlaneCertificateStatusTrustStore.ExpectedBinding("rg-staging-sg"),
                NOW);
        var wrongScope = trust.verify(publication,
                new ControlPlaneCertificateStatusTrustStore.ExpectedBinding("rg-other"), NOW);

        assertThat(verified.verified()).isTrue();
        assertThat(verified.publicationId()).isEqualTo("status-001");
        assertThat(verified.sequence()).isEqualTo(1);
        assertThat(verified.validSignatureCount()).isEqualTo(2);
        assertThat(wrongScope.status()).isEqualTo(
                ControlPlaneCertificateStatusTrustStore.VerificationStatus.BINDING_MISMATCH);
        assertThat(wrongScope.publicationFingerprint()).isEmpty();
    }

    @Test
    void rejectsTamperingInsufficientQuorumAndRevokedAuthority() throws Exception {
        KeyPair first = keyPair();
        KeyPair second = keyPair();
        ControlPlaneCertificateStatusPublication.Material material = material(
                1, "", NOW.plusSeconds(3600), goodTargets());
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        var onlyOne = publication(material, fingerprint,
                signature("authority-a", "key-a", first, fingerprint));
        var tampered = publication(material, OTHER_FINGERPRINT,
                signature("authority-a", "key-a", first, OTHER_FINGERPRINT),
                signature("authority-b", "key-b", second, OTHER_FINGERPRINT));
        var trust = trust(first, second);

        assertThat(trust.verify(onlyOne, binding(), NOW).status()).isEqualTo(
                ControlPlaneCertificateStatusTrustStore.VerificationStatus.QUORUM_NOT_MET);
        assertThat(trust.verify(tampered, binding(), NOW).status()).isEqualTo(
                ControlPlaneCertificateStatusTrustStore.VerificationStatus.MATERIAL_INVALID);

        var revokedTrust = new ConfiguredControlPlaneCertificateStatusTrustStore(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "enterprise-pki",
                Set.of(FINGERPRINT), 2, List.of(
                key("authority-a", "key-a", first, true),
                key("authority-b", "key-b", second, false)));
        assertThat(revokedTrust.verify(publication(material, fingerprint,
                        signature("authority-a", "key-a", first, fingerprint),
                        signature("authority-b", "key-b", second, fingerprint)),
                binding(), NOW).status()).isEqualTo(
                ControlPlaneCertificateStatusTrustStore.VerificationStatus.QUORUM_NOT_MET);
    }

    @Test
    void failsClosedForExpiredPublicationStaleEvidenceAndUnknownStatus() throws Exception {
        KeyPair first = keyPair();
        KeyPair second = keyPair();
        var trust = trust(first, second);
        var expiredMaterial = new ControlPlaneCertificateStatusPublication.Material(
                ControlPlaneCertificateStatusPublication.Material.SCHEMA_VERSION,
                "enterprise-pki", "status-expired", "rg-staging-sg", 1, "", FINGERPRINT,
                NOW.minusSeconds(3600), NOW.minusSeconds(1), goodTargets());
        var staleEvidence = evidence(ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT,
                ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                NOW.minusSeconds(8 * 24 * 3600L), NOW.plusSeconds(3600));
        var staleTargets = List.of(target("recovery-fleet.inventory", List.of(
                staleEvidence,
                evidence(ControlPlaneCertificateStatusPublication.CertificateRole.SERVER,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                        NOW, NOW.plusSeconds(3600)))));
        var staleMaterial = material(1, "", NOW.plusSeconds(3600), staleTargets);

        assertThat(trust.verify(signed(expiredMaterial, first, second), binding(), NOW).status())
                .isEqualTo(ControlPlaneCertificateStatusTrustStore.VerificationStatus.TIME_INVALID);
        assertThat(trust.verify(signed(staleMaterial, first, second), binding(), NOW).status())
                .isEqualTo(ControlPlaneCertificateStatusTrustStore.VerificationStatus.TIME_INVALID);

        var unknownTarget = target("recovery-fleet.inventory", List.of(
                evidence(ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.UNKNOWN,
                        NOW, NOW.plusSeconds(3600)),
                evidence(ControlPlaneCertificateStatusPublication.CertificateRole.SERVER,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                        NOW, NOW.plusSeconds(3600))));
        assertThat(unknownTarget.admitted()).isFalse();
    }

    @Test
    void enforcesContiguousPredecessorShapeAndCanonicalCompleteInventory() {
        assertThatThrownBy(() -> material(2, "", NOW.plusSeconds(3600), goodTargets()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> material(1, FINGERPRINT, NOW.plusSeconds(3600), goodTargets()))
                .isInstanceOf(IllegalArgumentException.class);
        List<ControlPlaneCertificateStatusPublication.TargetStatus> reversed = List.of(
                target("z-target", goodEvidence()), target("a-target", goodEvidence()));
        assertThatThrownBy(() -> material(1, "", NOW.plusSeconds(3600), reversed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> target("target", List.of(goodEvidence().getFirst())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ConfiguredControlPlaneCertificateStatusTrustStore trust(
            KeyPair first, KeyPair second) {
        return new ConfiguredControlPlaneCertificateStatusTrustStore(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "enterprise-pki",
                Set.of(FINGERPRINT), 2, List.of(
                key("authority-a", "key-a", first, false),
                key("authority-b", "key-b", second, false)));
    }

    private static ConfiguredControlPlaneCertificateStatusTrustStore.AuthorityKey key(
            String authority, String keyId, KeyPair pair, boolean revoked) {
        return new ConfiguredControlPlaneCertificateStatusTrustStore.AuthorityKey(
                authority, keyId, pair.getPublic(), NOW.minusSeconds(3600),
                NOW.plusSeconds(7200), true, revoked);
    }

    private ControlPlaneCertificateStatusPublication signed(
            ControlPlaneCertificateStatusPublication.Material material,
            KeyPair first, KeyPair second) throws Exception {
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return publication(material, fingerprint,
                signature("authority-a", "key-a", first, fingerprint),
                signature("authority-b", "key-b", second, fingerprint));
    }

    private static ControlPlaneCertificateStatusPublication publication(
            ControlPlaneCertificateStatusPublication.Material material,
            String fingerprint,
            ControlPlaneCertificateStatusPublication.AuthoritySignature... signatures) {
        return new ControlPlaneCertificateStatusPublication(
                ControlPlaneCertificateStatusPublication.SCHEMA_VERSION,
                material, fingerprint, List.of(signatures));
    }

    private static ControlPlaneCertificateStatusPublication.Material material(
            long sequence,
            String predecessor,
            Instant expiresAt,
            List<ControlPlaneCertificateStatusPublication.TargetStatus> targets) {
        return new ControlPlaneCertificateStatusPublication.Material(
                ControlPlaneCertificateStatusPublication.Material.SCHEMA_VERSION,
                "enterprise-pki", "status-001", "rg-staging-sg", sequence, predecessor,
                FINGERPRINT, NOW.minusSeconds(1), expiresAt, targets);
    }

    private static List<ControlPlaneCertificateStatusPublication.TargetStatus> goodTargets() {
        return List.of(target("recovery-fleet.inventory", goodEvidence()));
    }

    private static List<ControlPlaneCertificateStatusPublication.CertificateEvidence>
    goodEvidence() {
        return List.of(
                evidence(ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                        NOW, NOW.plusSeconds(3600)),
                evidence(ControlPlaneCertificateStatusPublication.CertificateRole.SERVER,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                        NOW, NOW.plusSeconds(3600)));
    }

    private static ControlPlaneCertificateStatusPublication.TargetStatus target(
            String targetId,
            List<ControlPlaneCertificateStatusPublication.CertificateEvidence> evidence) {
        return new ControlPlaneCertificateStatusPublication.TargetStatus(
                targetId, 1, FINGERPRINT, evidence);
    }

    private static ControlPlaneCertificateStatusPublication.CertificateEvidence evidence(
            ControlPlaneCertificateStatusPublication.CertificateRole role,
            ControlPlaneCertificateStatusPublication.CertificateStatus status,
            Instant thisUpdate,
            Instant nextUpdate) {
        return new ControlPlaneCertificateStatusPublication.CertificateEvidence(
                role, status, ControlPlaneCertificateStatusPublication.EvidenceType.OCSP,
                FINGERPRINT, OTHER_FINGERPRINT, FINGERPRINT,
                status == ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD
                        ? "CERTIFICATE_GOOD" : "STATUS_UNKNOWN",
                thisUpdate, thisUpdate, nextUpdate);
    }

    private static ControlPlaneCertificateStatusTrustStore.ExpectedBinding binding() {
        return new ControlPlaneCertificateStatusTrustStore.ExpectedBinding("rg-staging-sg");
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static ControlPlaneCertificateStatusPublication.AuthoritySignature signature(
            String authority, String keyId, KeyPair keyPair, String fingerprint) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ControlPlaneCertificateStatusPublication.AuthoritySignature(
                authority, keyId, "Ed25519", NOW,
                Base64.getEncoder().encodeToString(signer.sign()));
    }
}
