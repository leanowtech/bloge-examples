package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeObservationCompatibilityFixtureTest {
    @Test
    void standaloneCanonicalAddressMatchesTheServerProducedAddress()
            throws Exception {
        JsonNode envelope = new ObjectMapper()
                .readTree(getClass().getResourceAsStream(
                        CapabilityMirrorProtocol
                                .AUTHORITATIVE_OUTCOME_OBSERVATION_FIXTURE_RESOURCE));
        JsonNode observation = envelope.path("observation");

        assertThat(EvidenceVerificationSupport
                .sha256Bounded(
                        AuthoritativeOutcomeObservationVerifier
                                .producerFingerprintMaterial(
                                        observation),
                        AuthoritativeOutcomeObservationVerifier
                                .MAXIMUM_OBSERVATION_BYTES))
                .isEqualTo(
                        observation.path(
                                "observationFingerprint")
                                .asText());
    }

    @Test
    void independentlyVerifiesTheServerProducedPublicFixture() {
        AuthoritativeOutcomeObservationCompatibilityFixture fixture =
                CapabilityMirrorProtocol
                        .authoritativeOutcomeObservationCompatibilityFixture();

        AuthoritativeOutcomeObservationVerifier.VerificationResult result =
                fixture.verify();

        assertThat(result.verified())
                .as(result.reasonCode())
                .isTrue();
        assertThat(result.observationId())
                .isEqualTo("outcome-refund-boundary");
        assertThat(result.unitId())
                .isEqualTo("refund-boundary");
        assertThat(result.reconciliation())
                .isEqualTo("MATCH");
    }

    @Test
    void returnsDefensiveCopiesAndRejectsFixtureTamper() {
        AuthoritativeOutcomeObservationCompatibilityFixture fixture =
                CapabilityMirrorProtocol
                        .authoritativeOutcomeObservationCompatibilityFixture();
        ObjectNode tampered =
                (ObjectNode) fixture.observation();
        tampered.put("reconciliation", "MISMATCH");

        assertThat(new AuthoritativeOutcomeObservationVerifier()
                .verify(
                        tampered,
                        fixture.verificationKey(),
                        ignored -> true,
                        fixture.verificationTime())
                .verified())
                .isFalse();
        assertThat(CapabilityMirrorProtocol
                .authoritativeOutcomeObservationCompatibilityFixture()
                .verify()
                .verified())
                .isTrue();
    }

    @Test
    void strictAdmissionSchemaAcceptsSignedAndExactUnsignedForms() {
        AuthoritativeOutcomeObservationCompatibilityFixture fixture =
                CapabilityMirrorProtocol
                        .authoritativeOutcomeObservationCompatibilityFixture();
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules();
        ObjectNode request = mapper.createObjectNode();
        request.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_OBSERVATION_ADMISSION_REQUEST_V1);
        request.put("expectedPredecessorFingerprint", "");
        request.set("observation", fixture.observation());

        CapabilityMirrorSchemaValidator.require(
                request,
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_OBSERVATION_ADMISSION_REQUEST_SCHEMA_RESOURCE,
                "SIGNED_REQUEST_INVALID");

        ObjectNode unsigned = request.deepCopy();
        ObjectNode observation =
                (ObjectNode) unsigned.path("observation");
        observation.put("observationFingerprint", "");
        ObjectNode seal =
                (ObjectNode) observation.path("observationSeal");
        seal.put("materialFingerprint", "");
        seal.put("algorithm", "");
        seal.put("keyId", "");
        seal.put("signedAt", "1970-01-01T00:00:00Z");
        seal.put("signature", "");
        CapabilityMirrorSchemaValidator.require(
                unsigned,
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_OBSERVATION_ADMISSION_REQUEST_SCHEMA_RESOURCE,
                "UNSIGNED_REQUEST_INVALID");

        unsigned.put("callerClaimedOutcome", "MATCH");
        assertThatThrownBy(() ->
                CapabilityMirrorSchemaValidator.require(
                        unsigned,
                        CapabilityMirrorProtocol
                                .AUTHORITATIVE_OUTCOME_OBSERVATION_ADMISSION_REQUEST_SCHEMA_RESOURCE,
                        "RG.MIRROR.CLIENT.UNKNOWN_FIELD_ACCEPTED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
