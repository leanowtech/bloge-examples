package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorDeploymentIsolationAttestationVerifierTest {
    private final MirrorDeploymentIsolationAttestationVerifier verifier =
            new MirrorDeploymentIsolationAttestationVerifier();
    private final MirrorDeploymentIsolationCompatibilityFixture fixture =
            CapabilityMirrorProtocol.mirrorDeploymentIsolationCompatibilityFixture();

    @Test
    void packagedFixtureVerifiesWithoutServerOrSpringDependencies() {
        var result = verify(fixture.attestation(), fixture.verificationKey(),
                fixture.expectedDeployment(), fixture.executionStartedAt(),
                fixture.executionCompletedAt());

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(result.attestationId()).isEqualTo("mirror-staging-isolation");
        assertThat(result.attestationFingerprint())
                .isEqualTo("sha256:df9059a0cd8f2dcc498a0fede82f64ae76f5b13a0c097fc47611e8d4bd4ec098");
    }

    @Test
    void rejectsUnknownFieldsAndNonDeterministicPolicyOrderBeforeTrustEvaluation() {
        ObjectNode unknown = (ObjectNode) fixture.attestation().deepCopy();
        unknown.put("trusted", true);
        assertThat(verify(unknown, fixture.verificationKey(), fixture.expectedDeployment(),
                fixture.executionStartedAt(), fixture.executionCompletedAt()).reasonCode())
                .isEqualTo("ATTESTATION_SCHEMA_INVALID");

        ObjectNode reordered = (ObjectNode) fixture.attestation().deepCopy();
        ArrayNode layers = (ArrayNode) reordered.at("/material/enforcement/enforcementLayers");
        JsonNode first = layers.remove(0);
        layers.add(first);
        assertThat(verify(reordered, fixture.verificationKey(), fixture.expectedDeployment(),
                fixture.executionStartedAt(), fixture.executionCompletedAt()).reasonCode())
                .isEqualTo("ATTESTATION_ENFORCEMENT_ORDER_INVALID");

        ObjectNode conflictingProof = (ObjectNode) fixture.attestation().deepCopy();
        ArrayNode proofRefs = (ArrayNode) conflictingProof
                .at("/material/enforcement/proofRefs");
        ObjectNode duplicateCoordinate = proofRefs.get(0).deepCopy();
        duplicateCoordinate.put("fingerprint", fingerprint('6'));
        proofRefs.add(duplicateCoordinate);
        assertThat(verify(conflictingProof, fixture.verificationKey(),
                fixture.expectedDeployment(), fixture.executionStartedAt(),
                fixture.executionCompletedAt()).reasonCode())
                .isEqualTo("ATTESTATION_PROOF_ORDER_INVALID");

        ObjectNode offsetTime = (ObjectNode) fixture.attestation().deepCopy();
        ((ObjectNode) offsetTime.path("material"))
                .put("observedAt", "2026-07-23T00:00:00+00:00");
        assertThat(verify(offsetTime, fixture.verificationKey(), fixture.expectedDeployment(),
                fixture.executionStartedAt(), fixture.executionCompletedAt()).reasonCode())
                .isEqualTo("ATTESTATION_SCHEMA_INVALID");

        ObjectNode nonCanonicalSignature = (ObjectNode) fixture.attestation().deepCopy();
        ((ObjectNode) nonCanonicalSignature.path("seal")).put("signature", "AB==");
        assertThat(verify(nonCanonicalSignature, fixture.verificationKey(),
                fixture.expectedDeployment(), fixture.executionStartedAt(),
                fixture.executionCompletedAt()).reasonCode())
                .isEqualTo("ATTESTATION_SIGNATURE_ENCODING_INVALID");
        MirrorDeploymentIsolationVerificationKey source = fixture.verificationKey();
        assertThatThrownBy(() -> new MirrorDeploymentIsolationVerificationKey(
                source.schemaVersion(), source.keyId(), source.algorithm(), "AB==",
                source.issuer(), source.notBefore(), source.notAfter(), source.state()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical base64");
    }

    @Test
    void rejectsSignedMaterialTamperingAndLocalDeploymentDrift() {
        ObjectNode tampered = (ObjectNode) fixture.attestation().deepCopy();
        ((ObjectNode) tampered.at("/material/enforcement"))
                .put("networkPolicyFingerprint", fingerprint('9'));
        assertThat(verify(tampered, fixture.verificationKey(), fixture.expectedDeployment(),
                fixture.executionStartedAt(), fixture.executionCompletedAt()).reasonCode())
                .isEqualTo("ATTESTATION_MATERIAL_FINGERPRINT_INVALID");

        MirrorDeploymentIdentity drifted = new MirrorDeploymentIdentity(
                fixture.expectedDeployment().deploymentScopeId(), "cluster-b",
                fixture.expectedDeployment().namespace(),
                fixture.expectedDeployment().workloadName(),
                fixture.expectedDeployment().serviceAccount(),
                fixture.expectedDeployment().imageDigest());
        assertThat(verify(fixture.attestation(), fixture.verificationKey(), drifted,
                fixture.executionStartedAt(), fixture.executionCompletedAt()))
                .extracting(
                        MirrorDeploymentIsolationAttestationVerifier.VerificationResult::outcome,
                        MirrorDeploymentIsolationAttestationVerifier.VerificationResult::reasonCode)
                .containsExactly(
                        MirrorDeploymentIsolationAttestationVerifier.Outcome.IDENTITY_MISMATCH,
                        "DEPLOYMENT_IDENTITY_MISMATCH");
    }

    @Test
    void rejectsRevokedAuthorityAndExecutionOutsideTheSignedWindow() {
        MirrorDeploymentIsolationVerificationKey revoked = key(
                fixture.verificationKey().encodedPublicKey(),
                MirrorDeploymentIsolationVerificationKey.State.REVOKED);
        assertThat(verify(fixture.attestation(), revoked, fixture.expectedDeployment(),
                fixture.executionStartedAt(), fixture.executionCompletedAt()).reasonCode())
                .isEqualTo("AUTHORITY_KEY_POLICY_REJECTED");

        assertThat(verify(fixture.attestation(), fixture.verificationKey(),
                fixture.expectedDeployment(), Instant.parse("2026-07-23T00:00:09Z"),
                fixture.executionCompletedAt()).reasonCode())
                .isEqualTo("EXECUTION_OUTSIDE_ATTESTATION_WINDOW");
        assertThat(verify(fixture.attestation(), fixture.verificationKey(),
                fixture.expectedDeployment(), fixture.executionStartedAt(),
                Instant.parse("2026-07-23T00:10:00Z")).reasonCode())
                .isEqualTo("EXECUTION_OUTSIDE_ATTESTATION_WINDOW");
    }

    @Test
    void rejectsWrongAuthorityKeyMaterialAndMissingKeySeparately() throws Exception {
        String wrongPublicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPublic().getEncoded());
        assertThat(verify(fixture.attestation(), key(wrongPublicKey,
                MirrorDeploymentIsolationVerificationKey.State.ACTIVE),
                fixture.expectedDeployment(), fixture.executionStartedAt(),
                fixture.executionCompletedAt()).reasonCode())
                .isEqualTo("ATTESTATION_SIGNATURE_INVALID");
        assertThat(verify(fixture.attestation(), null, fixture.expectedDeployment(),
                fixture.executionStartedAt(), fixture.executionCompletedAt()))
                .extracting(
                        MirrorDeploymentIsolationAttestationVerifier.VerificationResult::outcome,
                        MirrorDeploymentIsolationAttestationVerifier.VerificationResult::reasonCode)
                .containsExactly(
                        MirrorDeploymentIsolationAttestationVerifier.Outcome.KEY_UNAVAILABLE,
                        "AUTHORITY_KEY_UNAVAILABLE");
    }

    @Test
    void compatibilityFixtureReturnsDetachedJsonCopies() {
        MirrorDeploymentIsolationCompatibilityFixture first =
                CapabilityMirrorProtocol.mirrorDeploymentIsolationCompatibilityFixture();
        ((ObjectNode) first.attestation()).put("mutated", true);
        MirrorDeploymentIsolationCompatibilityFixture second =
                CapabilityMirrorProtocol.mirrorDeploymentIsolationCompatibilityFixture();

        assertThat(second.attestation().has("mutated")).isFalse();
        assertThat(verify(second.attestation(), second.verificationKey(),
                second.expectedDeployment(), second.executionStartedAt(),
                second.executionCompletedAt()).verified()).isTrue();
    }

    private MirrorDeploymentIsolationAttestationVerifier.VerificationResult verify(
            JsonNode attestation,
            MirrorDeploymentIsolationVerificationKey key,
            MirrorDeploymentIdentity deployment,
            Instant startedAt,
            Instant completedAt) {
        return verifier.verify(attestation, key, deployment, startedAt, completedAt);
    }

    private MirrorDeploymentIsolationVerificationKey key(
            String publicKey, MirrorDeploymentIsolationVerificationKey.State state) {
        MirrorDeploymentIsolationVerificationKey source = fixture.verificationKey();
        return new MirrorDeploymentIsolationVerificationKey(source.schemaVersion(),
                source.keyId(), source.algorithm(), publicKey, source.issuer(),
                source.notBefore(), source.notAfter(), state);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
