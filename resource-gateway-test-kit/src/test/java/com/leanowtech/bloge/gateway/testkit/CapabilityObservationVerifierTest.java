package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityObservationVerifierTest {
    private final CapabilityObservationVerifier verifier =
            new CapabilityObservationVerifier();
    private final CapabilityObservationCompatibilityFixture fixture =
            CapabilityMirrorProtocol.capabilityObservationCompatibilityFixture();

    @Test
    void packagedFixtureVerifiesWithoutServerSpringOrPayloadVault() {
        CapabilityObservationVerifier.VerificationResult result = verify(
                fixture.observation(),
                fixture.verificationKey(),
                fixture.expectedScope(),
                fixture.verificationTime());

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(result.observationId())
                .isEqualTo("support-refund-observation-0001");
        assertThat(result.observationFingerprint())
                .isEqualTo(
                        "sha256:1f3b78c8cc7112b6cf4f218b2709339c643b9cac4381ff1e3c4115c2083709b0");
    }

    @Test
    void rejectsUnknownFieldsAndNonCanonicalUseOrderingBeforeTrustEvaluation() {
        ObjectNode unknown = (ObjectNode) fixture.observation().deepCopy();
        unknown.put("trusted", true);
        assertThat(verify(
                unknown,
                fixture.verificationKey(),
                fixture.expectedScope(),
                fixture.verificationTime()).reasonCode())
                .isEqualTo("OBSERVATION_SCHEMA_INVALID");

        ObjectNode reordered = (ObjectNode) fixture.observation().deepCopy();
        ArrayNode uses = (ArrayNode) reordered.at(
                "/material/dataUseGrant/allowedUses");
        JsonNode first = uses.remove(0);
        uses.add(first);
        assertThat(verify(
                reordered,
                fixture.verificationKey(),
                fixture.expectedScope(),
                fixture.verificationTime()).reasonCode())
                .isEqualTo("OBSERVATION_ALLOWED_USES_ORDER_INVALID");
    }

    @Test
    void rejectsSignedMaterialTamperingAndLocalScopeDrift() {
        ObjectNode tampered = (ObjectNode) fixture.observation().deepCopy();
        ((ObjectNode) tampered.path("material")).put("latencyMillis", 88);
        assertThat(verify(
                tampered,
                fixture.verificationKey(),
                fixture.expectedScope(),
                fixture.verificationTime()).reasonCode())
                .isEqualTo("OBSERVATION_MATERIAL_FINGERPRINT_INVALID");

        CapabilityObservationScope drifted = new CapabilityObservationScope(
                fixture.expectedScope().tenantId(),
                "another-organization",
                fixture.expectedScope().projectId(),
                fixture.expectedScope().environmentId(),
                fixture.expectedScope().region());
        assertThat(verify(
                fixture.observation(),
                fixture.verificationKey(),
                drifted,
                fixture.verificationTime()))
                .extracting(
                        CapabilityObservationVerifier.VerificationResult::outcome,
                        CapabilityObservationVerifier.VerificationResult::reasonCode)
                .containsExactly(
                        CapabilityObservationVerifier.Outcome.SCOPE_MISMATCH,
                        "OBSERVATION_SCOPE_MISMATCH");
    }

    @Test
    void rejectsRevokedKeyExpiredGrantAndMissingKeySeparately() {
        CapabilityObservationVerificationKey revoked = key(
                fixture.verificationKey().encodedPublicKey(),
                CapabilityObservationVerificationKey.State.REVOKED);
        assertThat(verify(
                fixture.observation(),
                revoked,
                fixture.expectedScope(),
                fixture.verificationTime()).reasonCode())
                .isEqualTo("AUTHORITY_KEY_POLICY_REJECTED");

        assertThat(verify(
                fixture.observation(),
                fixture.verificationKey(),
                fixture.expectedScope(),
                Instant.parse("2030-02-02T00:00:00Z")).reasonCode())
                .isEqualTo("OBSERVATION_WINDOW_REJECTED");

        assertThat(verify(
                fixture.observation(),
                null,
                fixture.expectedScope(),
                fixture.verificationTime()))
                .extracting(
                        CapabilityObservationVerifier.VerificationResult::outcome,
                        CapabilityObservationVerifier.VerificationResult::reasonCode)
                .containsExactly(
                        CapabilityObservationVerifier.Outcome.KEY_UNAVAILABLE,
                        "AUTHORITY_KEY_UNAVAILABLE");
    }

    @Test
    void rejectsWrongPublicKeyAndReturnsDetachedFixtureCopies() throws Exception {
        String wrongPublicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair()
                        .getPublic()
                        .getEncoded());
        assertThat(verify(
                fixture.observation(),
                key(wrongPublicKey, CapabilityObservationVerificationKey.State.ACTIVE),
                fixture.expectedScope(),
                fixture.verificationTime()).reasonCode())
                .isEqualTo("OBSERVATION_SIGNATURE_INVALID");

        CapabilityObservationCompatibilityFixture first =
                CapabilityMirrorProtocol.capabilityObservationCompatibilityFixture();
        ((ObjectNode) first.observation()).put("mutated", true);
        CapabilityObservationCompatibilityFixture second =
                CapabilityMirrorProtocol.capabilityObservationCompatibilityFixture();
        assertThat(second.observation().has("mutated")).isFalse();
        assertThat(verify(
                second.observation(),
                second.verificationKey(),
                second.expectedScope(),
                second.verificationTime()).verified()).isTrue();
    }

    private CapabilityObservationVerifier.VerificationResult verify(
            JsonNode observation,
            CapabilityObservationVerificationKey key,
            CapabilityObservationScope scope,
            Instant verificationTime) {
        return verifier.verify(observation, key, scope, verificationTime);
    }

    private CapabilityObservationVerificationKey key(
            String publicKey, CapabilityObservationVerificationKey.State state) {
        CapabilityObservationVerificationKey source = fixture.verificationKey();
        return new CapabilityObservationVerificationKey(
                source.schemaVersion(),
                source.keyId(),
                source.algorithm(),
                publicKey,
                source.issuer(),
                source.notBefore(),
                source.notAfter(),
                state);
    }
}
