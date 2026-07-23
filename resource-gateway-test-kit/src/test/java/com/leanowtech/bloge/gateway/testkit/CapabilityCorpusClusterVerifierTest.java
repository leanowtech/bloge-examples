package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityCorpusClusterVerifierTest {
    private final CapabilityCorpusClusterVerifier verifier =
            new CapabilityCorpusClusterVerifier();

    @Test
    void packagedFixtureVerifiesWithExplicitOnlineLimitations() {
        CapabilityCorpusClusterCompatibilityFixture fixture =
                CapabilityMirrorProtocol
                        .capabilityCorpusClusterCompatibilityFixture();

        CapabilityCorpusClusterVerifier.VerificationResult result =
                verifier.verify(fixture);

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("STRUCTURALLY_VERIFIED");
        assertThat(result.limitations()).containsExactlyInAnyOrder(
                "ONLINE_CLUSTER_POLICY_REQUIRED",
                "ONLINE_VALIDATION_AUTHORITY_REQUIRED",
                "ONLINE_GRANT_AND_RETENTION_REQUIRED",
                "ONLINE_SOURCE_AND_PAYLOAD_AUTHORITY_REQUIRED");
    }

    @Test
    void forgedWilsonConfidenceFailsAfterAllFingerprintsAreRecomputed() {
        CapabilityCorpusClusterCompatibilityFixture fixture =
                mutableFixture();
        ObjectNode validation = object(fixture.validation());
        ObjectNode publication = object(fixture.publication());
        ObjectNode command = object(fixture.publishRequest());
        ObjectNode validationConfidence = object(
                validation.path("confidence"));
        validationConfidence.put("lowerBound", 0.98d);
        object(publication.path("confidence")).put("lowerBound", 0.98d);
        reseal(validation, command, publication);

        CapabilityCorpusClusterVerifier.VerificationResult result =
                verifier.verify(
                        fixture.corpusRevision(),
                        fixture.corpusPublication(),
                        validation,
                        command,
                        publication,
                        fixture.expectedScope(),
                        fixture.verificationTime());

        assertThat(result.outcome()).isEqualTo(
                CapabilityCorpusClusterVerifier.Outcome.CONFIDENCE_INVALID);
        assertThat(result.reasonCode())
                .isEqualTo("CLUSTER_CONFIDENCE_INVALID");
    }

    @Test
    void overlappingIdentityResponsePointersFailAfterResealing() {
        CapabilityCorpusClusterCompatibilityFixture fixture =
                mutableFixture();
        ObjectNode validation = object(fixture.validation());
        ObjectNode publication = object(fixture.publication());
        ObjectNode command = object(fixture.publishRequest());
        ArrayNode validationResponses = (ArrayNode) validation.at(
                "/identityProjections/0/responsePointers");
        validationResponses.add("/customer/id/value");
        ArrayNode publicationResponses = (ArrayNode) publication.at(
                "/identityProjections/0/responsePointers");
        publicationResponses.add("/customer/id/value");
        reseal(validation, command, publication);

        CapabilityCorpusClusterVerifier.VerificationResult result =
                verifier.verify(
                        fixture.corpusRevision(),
                        fixture.corpusPublication(),
                        validation,
                        command,
                        publication,
                        fixture.expectedScope(),
                        fixture.verificationTime());

        assertThat(result.outcome()).isEqualTo(
                CapabilityCorpusClusterVerifier.Outcome.IDENTITY_UNSAFE);
        assertThat(result.reasonCode())
                .isEqualTo("CLUSTER_RESPONSE_POINTER_OVERLAP");
    }

    @Test
    void inventedMemberAndUnknownFieldFailClosed() {
        CapabilityCorpusClusterCompatibilityFixture fixture =
                mutableFixture();
        ObjectNode validation = object(fixture.validation());
        ObjectNode publication = object(fixture.publication());
        ObjectNode command = object(fixture.publishRequest());
        object(validation.at("/members/2/observationRef"))
                .put("fingerprint", "sha256:" + "f".repeat(64));
        object(publication.at("/members/2/observationRef"))
                .put("fingerprint", "sha256:" + "f".repeat(64));
        reseal(validation, command, publication);

        CapabilityCorpusClusterVerifier.VerificationResult membership =
                verifier.verify(
                        fixture.corpusRevision(),
                        fixture.corpusPublication(),
                        validation,
                        command,
                        publication,
                        fixture.expectedScope(),
                        fixture.verificationTime());
        assertThat(membership.outcome()).isEqualTo(
                CapabilityCorpusClusterVerifier.Outcome.MEMBERSHIP_INVALID);

        ObjectNode malformed = object(fixture.validation());
        malformed.put("requestBody", "must-not-enter");
        CapabilityCorpusClusterVerifier.VerificationResult schema =
                verifier.verify(
                        fixture.corpusRevision(),
                        fixture.corpusPublication(),
                        malformed,
                        fixture.publishRequest(),
                        fixture.publication(),
                        fixture.expectedScope(),
                        fixture.verificationTime());
        assertThat(schema.outcome()).isEqualTo(
                CapabilityCorpusClusterVerifier.Outcome.SCHEMA_INVALID);
    }

    @Test
    void verificationAfterPublicationHorizonIsRejected() {
        CapabilityCorpusClusterCompatibilityFixture fixture =
                mutableFixture();

        CapabilityCorpusClusterVerifier.VerificationResult result =
                verifier.verify(
                        fixture.corpusRevision(),
                        fixture.corpusPublication(),
                        fixture.validation(),
                        fixture.publishRequest(),
                        fixture.publication(),
                        fixture.expectedScope(),
                        Instant.parse("2030-02-02T00:00:00Z"));

        assertThat(result.outcome()).isEqualTo(
                CapabilityCorpusClusterVerifier.Outcome.WINDOW_REJECTED);
        assertThat(result.reasonCode()).isEqualTo("CLUSTER_WINDOW_INVALID");
    }

    private static void reseal(
            ObjectNode validation,
            ObjectNode command,
            ObjectNode publication) {
        validation.put(
                "validationFingerprint",
                "sha256:" + "0".repeat(64));
        String validationFingerprint =
                EvidenceVerificationSupport.sha256(validation);
        validation.put("validationFingerprint", validationFingerprint);
        object(command.path("validationRef"))
                .put("fingerprint", validationFingerprint);
        object(publication.path("validationRef"))
                .put("fingerprint", validationFingerprint);
        String commandFingerprint =
                EvidenceVerificationSupport.sha256(command);
        publication.put(
                "sourceCommandFingerprint",
                commandFingerprint);
        publication.put(
                "clusterFingerprint",
                "sha256:" + "0".repeat(64));
        publication.put(
                "clusterFingerprint",
                EvidenceVerificationSupport.sha256(publication));
    }

    private static CapabilityCorpusClusterCompatibilityFixture
            mutableFixture() {
        return CapabilityMirrorProtocol
                .capabilityCorpusClusterCompatibilityFixture();
    }

    private static ObjectNode object(JsonNode value) {
        return (ObjectNode) value;
    }
}
