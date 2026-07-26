package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
