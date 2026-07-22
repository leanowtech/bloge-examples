package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorDeploymentIsolationAttestationIntegrityTest {
    private static final Instant OBSERVED = Instant.now().minusSeconds(2);
    private static final Instant EXPIRES = OBSERVED.plusSeconds(600);
    private static final String ISSUER = "sre:mirror-isolation";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
    private final MirrorDeploymentIsolationAttestationIntegrity integrity =
            new MirrorDeploymentIsolationAttestationIntegrity(mapper);

    private MirrorDeploymentIsolationAttestation attestation;
    private MirrorDeploymentIsolationAttestationIntegrity.AuthorityKey authorityKey;

    @BeforeEach
    void setUp() {
        attestation = integrity.seal(material(deployment()), signer);
        VisualEvidenceSigner.VerificationKey key = signer.key(attestation.seal().keyId())
                .orElseThrow();
        authorityKey = new MirrorDeploymentIsolationAttestationIntegrity.AuthorityKey(
                key.keyId(), key.algorithm(), key.encodedPublicKey(), ISSUER,
                key.createdAt().minusSeconds(1), EXPIRES.plusSeconds(3600),
                MirrorDeploymentIsolationAttestationIntegrity.KeyState.ACTIVE);
    }

    @Test
    void verifiesExactExternalAuthorityIdentityAndCompleteExecutionWindow() {
        Instant started = attestation.seal().signedAt().plusMillis(1);
        var result = integrity.verify(attestation, authorityKey, deployment(),
                started, started.plusSeconds(2));

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(result.attestationFingerprint())
                .isEqualTo(attestation.attestationFingerprint());
        assertThat(attestation.artifactRef()).isEqualTo(new MirrorArtifactRef(
                MirrorDeploymentIsolationAttestation.ARTIFACT_KIND,
                "mirror-staging-isolation", 7, attestation.attestationFingerprint()));
    }

    @Test
    void rejectsLocalDeploymentIdentityDriftWithoutTrustingTheSignedLabel() {
        MirrorDeploymentIsolationAttestation.DeploymentIdentity drifted =
                new MirrorDeploymentIsolationAttestation.DeploymentIdentity(
                        "deployment:staging", "cluster-b", "rg-mirror", "resource-gateway",
                        "rg-mirror", fingerprint('1'));
        Instant started = attestation.seal().signedAt().plusMillis(1);

        assertThat(integrity.verify(attestation, authorityKey, drifted,
                started, started.plusSeconds(1)))
                .extracting(MirrorDeploymentIsolationAttestationIntegrity.VerificationResult::outcome,
                        MirrorDeploymentIsolationAttestationIntegrity.VerificationResult::reasonCode)
                .containsExactly(
                        MirrorDeploymentIsolationAttestationIntegrity.Outcome.IDENTITY_MISMATCH,
                        "DEPLOYMENT_IDENTITY_MISMATCH");
    }

    @Test
    void rejectsAnyExecutionThatStartsBeforeSigningOrCompletesAtExpiry() {
        assertThat(integrity.verify(attestation, authorityKey, deployment(),
                attestation.material().validFrom(), attestation.seal().signedAt()))
                .extracting(MirrorDeploymentIsolationAttestationIntegrity.VerificationResult::outcome,
                        MirrorDeploymentIsolationAttestationIntegrity.VerificationResult::reasonCode)
                .containsExactly(
                        MirrorDeploymentIsolationAttestationIntegrity.Outcome.WINDOW_REJECTED,
                        "EXECUTION_OUTSIDE_ATTESTATION_WINDOW");

        assertThat(integrity.verify(attestation, authorityKey, deployment(),
                attestation.seal().signedAt(), attestation.material().expiresAt()))
                .extracting(MirrorDeploymentIsolationAttestationIntegrity.VerificationResult::outcome,
                        MirrorDeploymentIsolationAttestationIntegrity.VerificationResult::reasonCode)
                .containsExactly(
                        MirrorDeploymentIsolationAttestationIntegrity.Outcome.WINDOW_REJECTED,
                        "EXECUTION_OUTSIDE_ATTESTATION_WINDOW");
    }

    @Test
    void rejectsRevokedWrongIssuerAndWrongPublicKeyPolicies() {
        Instant started = attestation.seal().signedAt().plusMillis(1);
        var revoked = new MirrorDeploymentIsolationAttestationIntegrity.AuthorityKey(
                authorityKey.keyId(), authorityKey.algorithm(), authorityKey.encodedPublicKey(),
                authorityKey.issuer(), authorityKey.notBefore(), authorityKey.notAfter(),
                MirrorDeploymentIsolationAttestationIntegrity.KeyState.REVOKED);
        var wrongIssuer = new MirrorDeploymentIsolationAttestationIntegrity.AuthorityKey(
                authorityKey.keyId(), authorityKey.algorithm(), authorityKey.encodedPublicKey(),
                "sre:other", authorityKey.notBefore(), authorityKey.notAfter(),
                MirrorDeploymentIsolationAttestationIntegrity.KeyState.ACTIVE);

        assertThat(integrity.verify(attestation, revoked, deployment(), started,
                started.plusSeconds(1)).reasonCode())
                .isEqualTo("AUTHORITY_KEY_POLICY_REJECTED");
        assertThat(integrity.verify(attestation, wrongIssuer, deployment(), started,
                started.plusSeconds(1)).reasonCode())
                .isEqualTo("AUTHORITY_IDENTITY_MISMATCH");

        InMemoryVisualEvidenceSigner other = new InMemoryVisualEvidenceSigner();
        VisualEvidenceSigner.VerificationKey otherKey = other.key(other.descriptor().activeKeyId())
                .orElseThrow();
        var wrongMaterial = new MirrorDeploymentIsolationAttestationIntegrity.AuthorityKey(
                authorityKey.keyId(), authorityKey.algorithm(), otherKey.encodedPublicKey(),
                authorityKey.issuer(), authorityKey.notBefore(), authorityKey.notAfter(),
                MirrorDeploymentIsolationAttestationIntegrity.KeyState.ACTIVE);
        assertThat(integrity.verify(attestation, wrongMaterial, deployment(), started,
                started.plusSeconds(1)).reasonCode())
                .isEqualTo("ATTESTATION_SIGNATURE_INVALID");
    }

    @Test
    void rejectsMaterialTamperingEvenWhenTheOriginalSignatureAndFingerprintAreRetained() {
        MirrorDeploymentIsolationAttestation.Material altered = new
                MirrorDeploymentIsolationAttestation.Material(
                attestation.material().attestationId(), attestation.material().revision(),
                new MirrorDeploymentIsolationAttestation.DeploymentIdentity(
                        "deployment:staging", "cluster-a", "rg-mirror", "resource-gateway",
                        "production-service-account", fingerprint('1')),
                attestation.material().enforcement(), attestation.material().observedAt(),
                attestation.material().validFrom(), attestation.material().expiresAt(), ISSUER);
        MirrorDeploymentIsolationAttestation tampered = new
                MirrorDeploymentIsolationAttestation("",
                attestation.attestationFingerprint(), altered, attestation.seal());
        Instant started = attestation.seal().signedAt().plusMillis(1);

        assertThat(integrity.verify(tampered, authorityKey, altered.deployment(),
                started, started.plusSeconds(1)).reasonCode())
                .isEqualTo("ATTESTATION_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsPermissiveControlsMutableImagesAndNonProofReferences() {
        assertThatThrownBy(() -> new MirrorDeploymentIsolationAttestation.EnforcementFacts(
                List.of(MirrorDeploymentIsolationAttestation.EnforcementLayer
                        .KUBERNETES_NETWORK_POLICY), true, false, true, true, true, true,
                fingerprint('2'), fingerprint('3'), fingerprint('4'), List.of(), proofRefs()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fail closed");
        assertThatThrownBy(() -> new MirrorDeploymentIsolationAttestation.DeploymentIdentity(
                "deployment:staging", "cluster-a", "rg-mirror", "resource-gateway",
                "rg-mirror", "resource-gateway:latest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imageDigest");
        assertThatThrownBy(() -> new MirrorDeploymentIsolationAttestation.EnforcementFacts(
                List.of(MirrorDeploymentIsolationAttestation.EnforcementLayer
                        .KUBERNETES_NETWORK_POLICY), true, true, true, true, true, true,
                fingerprint('2'), fingerprint('3'), fingerprint('4'), List.of(),
                List.of(new MirrorArtifactRef("CAPABILITY", "not-a-proof", 1,
                        fingerprint('5')))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DEPLOYMENT_POLICY_PROOF");
        assertThatThrownBy(() -> new MirrorDeploymentIsolationAttestation.EnforcementFacts(
                List.of(MirrorDeploymentIsolationAttestation.EnforcementLayer.WORKLOAD_SANDBOX,
                        MirrorDeploymentIsolationAttestation.EnforcementLayer
                                .KUBERNETES_NETWORK_POLICY),
                true, true, true, true, true, true,
                fingerprint('2'), fingerprint('3'), fingerprint('4'), List.of(), proofRefs()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical order");
        assertThatThrownBy(() -> new MirrorDeploymentIsolationAttestation.EnforcementFacts(
                List.of(MirrorDeploymentIsolationAttestation.EnforcementLayer
                        .KUBERNETES_NETWORK_POLICY),
                true, true, true, true, true, true,
                fingerprint('2'), fingerprint('3'), fingerprint('4'), List.of(),
                List.of(new MirrorArtifactRef("DEPLOYMENT_POLICY_PROOF",
                                "policy-evaluation:staging", 19, fingerprint('5')),
                        new MirrorArtifactRef("DEPLOYMENT_POLICY_PROOF",
                                "policy-evaluation:staging", 19, fingerprint('6')))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique artifact coordinates");
        assertThatThrownBy(() -> new MirrorDeploymentIsolationAttestation.Seal(
                fingerprint('6'), "Ed25519", "authority-key", Instant.now(), "AB=="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical base64");
        assertThatThrownBy(() -> new MirrorDeploymentIsolationAttestationIntegrity.AuthorityKey(
                "authority-key", "Ed25519", "AB==", ISSUER, OBSERVED, EXPIRES,
                MirrorDeploymentIsolationAttestationIntegrity.KeyState.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical base64");
    }

    private static MirrorDeploymentIsolationAttestation.Material material(
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment) {
        return new MirrorDeploymentIsolationAttestation.Material(
                "mirror-staging-isolation", 7, deployment,
                new MirrorDeploymentIsolationAttestation.EnforcementFacts(
                        List.of(MirrorDeploymentIsolationAttestation.EnforcementLayer
                                        .KUBERNETES_NETWORK_POLICY,
                                MirrorDeploymentIsolationAttestation.EnforcementLayer
                                        .WORKLOAD_SANDBOX),
                        true, true, true, true, true, true,
                        fingerprint('2'), fingerprint('3'), fingerprint('4'),
                        List.of(MirrorDeploymentIsolationAttestation.AllowedEgressClass.DNS,
                                MirrorDeploymentIsolationAttestation.AllowedEgressClass
                                        .EVIDENCE_SIGNER,
                                MirrorDeploymentIsolationAttestation.AllowedEgressClass
                                        .PAYLOAD_FREE_DATABASE),
                        proofRefs()), OBSERVED, OBSERVED, EXPIRES, ISSUER);
    }

    private static MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment() {
        return new MirrorDeploymentIsolationAttestation.DeploymentIdentity(
                "deployment:staging", "cluster-a", "rg-mirror", "resource-gateway",
                "rg-mirror", fingerprint('1'));
    }

    private static List<MirrorArtifactRef> proofRefs() {
        return List.of(new MirrorArtifactRef("DEPLOYMENT_POLICY_PROOF",
                "policy-evaluation:staging", 19, fingerprint('5')));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
