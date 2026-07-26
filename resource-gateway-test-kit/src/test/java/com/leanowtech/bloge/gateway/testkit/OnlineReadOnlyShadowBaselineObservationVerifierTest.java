package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineReadOnlyShadowBaselineObservationVerifierTest {
    private OnlineReadOnlyShadowBaselineCompatibilityFixture
            fixture;
    private OnlineReadOnlyShadowBaselineObservationVerifier
            verifier;

    @BeforeEach
    void setUp() {
        fixture = CapabilityMirrorProtocol
                .onlineReadOnlyShadowBaselineCompatibilityFixture();
        verifier =
                new OnlineReadOnlyShadowBaselineObservationVerifier();
    }

    @Test
    void verifiesServerProducedCommandObservationAndZeroWriteClosure() {
        var result = fixture.verify();

        assertThat(result.verified())
                .as(result.reasonCode())
                .isTrue();
        assertThat(result.zeroWrite()).isTrue();
        assertThat(result.observationId())
                .startsWith("online-baseline-");
        assertThat(result.executionId())
                .isEqualTo("execution-online-command");
        assertThat(result.requestId())
                .isEqualTo("online-command");
        assertThat(result.keyId())
                .isEqualTo(
                        fixture.verificationKey()
                                .keyId());
        assertThat(fixture.command().toString())
                .doesNotContain("requestPayload")
                .doesNotContain("responsePayload")
                .doesNotContain("credential")
                .doesNotContain("privateKey");
        assertThat(fixture.observation().toString())
                .doesNotContain("requestPayload")
                .doesNotContain("responsePayload")
                .doesNotContain("privateKey");
    }

    @Test
    void rejectsStrictSchemaAndContentAddressDriftWithoutLeakingPayload() {
        ObjectNode payloadBearing =
                (ObjectNode) fixture.observation();
        payloadBearing.put(
                "requestPayload",
                "customer-secret");

        var schemaInvalid = verifier.verify(
                payloadBearing,
                fixture.verificationKey(),
                context(fixture.command()));

        assertThat(schemaInvalid.outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservationVerifier
                                .Outcome.INVALID);
        assertThat(schemaInvalid.reasonCode())
                .isEqualTo(
                        "ONLINE_BASELINE_SCHEMA_INVALID");
        assertThat(schemaInvalid.toString())
                .doesNotContain("customer-secret");

        ObjectNode fingerprintDrift =
                (ObjectNode) fixture.observation();
        fingerprintDrift.put(
                "semanticResultFingerprint",
                fingerprint('a'));
        assertThat(verifier.verify(
                fingerprintDrift,
                fixture.verificationKey(),
                context(fixture.command()))
                .reasonCode())
                .isEqualTo(
                        "ONLINE_BASELINE_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsAuthenticatedCommandAndExpectedReferenceDrift() {
        ObjectNode changedCommand =
                (ObjectNode) fixture.command();
        changedCommand.put(
                "unitId", "other-unit");
        var commandMismatch = verifier.verify(
                fixture.observation(),
                fixture.verificationKey(),
                context(changedCommand));

        assertThat(commandMismatch.outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservationVerifier
                                .Outcome.EXPECTATION_MISMATCH);
        assertThat(commandMismatch.reasonCode())
                .isEqualTo(
                        "ONLINE_BASELINE_COMMAND_MISMATCH");

        ObjectNode changedReference =
                (ObjectNode) fixture
                        .expectedObservationRef();
        changedReference.put(
                "fingerprint", fingerprint('b'));
        var referenceMismatch = verifier.verify(
                fixture.observation(),
                fixture.verificationKey(),
                new OnlineReadOnlyShadowBaselineObservationVerifier
                        .VerificationContext(
                        fixture.command(),
                        changedReference,
                        fixture.verificationTime()));

        assertThat(referenceMismatch.outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservationVerifier
                                .Outcome.EXPECTATION_MISMATCH);
        assertThat(referenceMismatch.reasonCode())
                .isEqualTo(
                        "ONLINE_BASELINE_REFERENCE_MISMATCH");
    }

    @Test
    void distinguishesUnavailableRejectedFutureAndInvalidSignatures() {
        assertThat(verifier.verify(
                fixture.observation(),
                null,
                context(fixture.command()))
                .outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservationVerifier
                                .Outcome.KEY_UNAVAILABLE);

        EvidenceVerificationKey wrongKey =
                new EvidenceVerificationKey(
                        fixture.verificationKey()
                                .schemaVersion(),
                        "other-online-authority",
                        fixture.verificationKey()
                                .algorithm(),
                        fixture.verificationKey()
                                .encodedPublicKey(),
                        fixture.verificationKey()
                                .createdAt(),
                        fixture.verificationKey().state(),
                        fixture.verificationKey()
                                .provider());
        assertThat(verifier.verify(
                fixture.observation(),
                wrongKey,
                context(fixture.command()))
                .outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservationVerifier
                                .Outcome.POLICY_REJECTED);

        var futureContext =
                new OnlineReadOnlyShadowBaselineObservationVerifier
                        .VerificationContext(
                        fixture.command(),
                        fixture.expectedObservationRef(),
                        Instant.parse(
                                "2026-07-25T23:58:00Z"));
        assertThat(verifier.verify(
                fixture.observation(),
                fixture.verificationKey(),
                futureContext).outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowBaselineObservationVerifier
                                .Outcome.WINDOW_REJECTED);

        ObjectNode badSignature =
                (ObjectNode) fixture.observation();
        badSignature.withObject(
                        "/observationSeal")
                .put(
                        "signature",
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==");
        assertThat(verifier.verify(
                badSignature,
                fixture.verificationKey(),
                context(fixture.command()))
                .reasonCode())
                .isEqualTo(
                        "ONLINE_BASELINE_SIGNATURE_INVALID");
    }

    @Test
    void fixtureAccessorsReturnDetachedProtocolDocuments() {
        ObjectNode command =
                (ObjectNode) fixture.command();
        command.put("unitId", "mutated");
        ObjectNode observation =
                (ObjectNode) fixture.observation();
        observation.put(
                "observationId", "mutated");
        ObjectNode reference =
                (ObjectNode) fixture
                        .expectedObservationRef();
        reference.put("id", "mutated");

        var reloaded = CapabilityMirrorProtocol
                .onlineReadOnlyShadowBaselineCompatibilityFixture();

        assertThat(reloaded.command()
                .path("unitId").asText())
                .isEqualTo("refund-golden");
        assertThat(reloaded.observation()
                .path("observationId").asText())
                .startsWith("online-baseline-");
        assertThat(reloaded.expectedObservationRef()
                .path("id").asText())
                .startsWith("online-baseline-");
        assertThat(reloaded.verify().verified())
                .isTrue();
    }

    private OnlineReadOnlyShadowBaselineObservationVerifier
            .VerificationContext context(
            com.fasterxml.jackson.databind.JsonNode command) {
        return new OnlineReadOnlyShadowBaselineObservationVerifier
                .VerificationContext(
                command,
                fixture.expectedObservationRef(),
                fixture.verificationTime());
    }

    private static String fingerprint(char value) {
        return "sha256:"
                + String.valueOf(value).repeat(64);
    }
}
