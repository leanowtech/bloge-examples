package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityObservationIntegrityTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
    private final CapabilityObservationIntegrity integrity =
            new CapabilityObservationIntegrity(mapper);

    @Test
    void sealsAndVerifiesExactPayloadFreeObservation() {
        CapabilitySnapshot capability = CapabilityObservationTestFixtures.capability(
                mapper, CapabilityObservationTestFixtures.scope("org-a"));
        CapabilityObservationEnvelope envelope =
                CapabilityObservationTestFixtures.envelope(
                        mapper, signer, capability, "observation-a");
        var key = CapabilityObservationTestFixtures.authorityKey(
                envelope, signer, CapabilityObservationIntegrity.KeyState.ACTIVE);

        CapabilityObservationIntegrity.VerificationResult result =
                integrity.verify(envelope, key);

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
        assertThat(envelope.artifactRef().kind())
                .isEqualTo(CapabilityObservationEnvelope.ARTIFACT_KIND);
    }

    @Test
    void rejectsMaterialDriftEvenWhenOuterFingerprintAndSignatureAreReused() {
        CapabilitySnapshot capability = CapabilityObservationTestFixtures.capability(
                mapper, CapabilityObservationTestFixtures.scope("org-a"));
        CapabilityObservationEnvelope envelope =
                CapabilityObservationTestFixtures.envelope(
                        mapper, signer, capability, "observation-b");
        CapabilityObservationEnvelope.Material original = envelope.material();
        CapabilityObservationEnvelope.Material drifted =
                new CapabilityObservationEnvelope.Material(
                        original.observationId(),
                        original.scope(),
                        original.capabilityRef(),
                        original.occurredAt(),
                        original.trace(),
                        original.request(),
                        original.response(),
                        original.error(),
                        original.latencyMillis() + 1,
                        original.stateCorrelation(),
                        original.outcomeCorrelationRef(),
                        original.dataUseGrant());
        CapabilityObservationEnvelope tampered =
                new CapabilityObservationEnvelope(
                        envelope.schemaVersion(),
                        envelope.observationFingerprint(),
                        drifted,
                        envelope.seal());
        var key = CapabilityObservationTestFixtures.authorityKey(
                envelope, signer, CapabilityObservationIntegrity.KeyState.ACTIVE);

        assertThat(integrity.verify(tampered, key).outcome())
                .isEqualTo(CapabilityObservationIntegrity.Outcome.INVALID);
    }

    @Test
    void rejectsRevokedProducerKey() {
        CapabilitySnapshot capability = CapabilityObservationTestFixtures.capability(
                mapper, CapabilityObservationTestFixtures.scope("org-a"));
        CapabilityObservationEnvelope envelope =
                CapabilityObservationTestFixtures.envelope(
                        mapper, signer, capability, "observation-c");
        var revoked = CapabilityObservationTestFixtures.authorityKey(
                envelope, signer, CapabilityObservationIntegrity.KeyState.REVOKED);

        assertThat(integrity.verify(envelope, revoked).outcome())
                .isEqualTo(CapabilityObservationIntegrity.Outcome.POLICY_REJECTED);
    }

    @Test
    void modelRejectsRawBusinessKeyAndAmbiguousSuccessErrorShape() {
        assertThatThrownBy(() ->
                new CapabilityObservationEnvelope.StateCorrelation(
                        "support-case", "customer-123",
                        CapabilityObservationTestFixtures.fingerprint('1'),
                        CapabilityObservationTestFixtures.fingerprint('2')))
                .isInstanceOf(IllegalArgumentException.class);

        CapabilitySnapshot capability = CapabilityObservationTestFixtures.capability(
                mapper, CapabilityObservationTestFixtures.scope("org-a"));
        CapabilityObservationEnvelope envelope =
                CapabilityObservationTestFixtures.envelope(
                        mapper, signer, capability, "observation-d");
        CapabilityObservationEnvelope.Material original = envelope.material();
        assertThatThrownBy(() -> new CapabilityObservationEnvelope.Material(
                original.observationId(),
                original.scope(),
                original.capabilityRef(),
                original.occurredAt(),
                original.trace(),
                original.request(),
                original.response(),
                new CapabilityObservationEnvelope.NormalizedError(
                        "UPSTREAM", "TIMEOUT", true,
                        CapabilityObservationTestFixtures.fingerprint('3')),
                original.latencyMillis(),
                original.stateCorrelation(),
                original.outcomeCorrelationRef(),
                original.dataUseGrant()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
