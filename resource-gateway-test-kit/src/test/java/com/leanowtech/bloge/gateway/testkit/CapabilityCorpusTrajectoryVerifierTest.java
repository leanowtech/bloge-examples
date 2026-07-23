package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityCorpusTrajectoryVerifierTest {
    private static final String ZERO_FINGERPRINT =
            "sha256:" + "0".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CapabilityCorpusTrajectoryVerifier verifier =
            new CapabilityCorpusTrajectoryVerifier();

    @Test
    void verifiesContentAddressesAndCorpusMembershipWithoutOverclaiming()
            throws Exception {
        Material material = material();

        CapabilityCorpusTrajectoryVerifier.VerificationResult result =
                verifier.verify(
                        material.command(),
                        material.trajectory(),
                        material.corpusPublication(),
                        material.corpusRevision(),
                        material.verificationTime());

        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("STRUCTURALLY_VERIFIED");
        assertThat(result.trajectoryId())
                .isEqualTo("support-timeout-trajectory");
        assertThat(result.onlineLimitations()).containsExactlyInAnyOrder(
                "ONLINE_RETRY_POLICY_REQUIRED",
                "ONLINE_TRACE_AND_OUTCOME_REQUIRED",
                "ONLINE_GRANT_AND_RETENTION_REQUIRED",
                "ONLINE_SOURCE_AND_PAYLOAD_AUTHORITY_REQUIRED");
    }

    @Test
    void rejectsSelfConsistentAttemptThatIsNotInReferencedCorpus()
            throws Exception {
        Material material = material();
        ObjectNode unknownObservation = (ObjectNode) material.command()
                .at("/attempts/1/observationRef");
        unknownObservation.put("id", "not-in-corpus");
        unknownObservation.put("fingerprint", fingerprint('e'));
        ObjectNode unknownAdmission = (ObjectNode) material.command()
                .at("/attempts/1/admissionRef");
        unknownAdmission.put("id", "not-in-corpus:admission");
        unknownAdmission.put("fingerprint", fingerprint('f'));
        material.trajectory().set(
                "attempts",
                material.command().path("attempts").deepCopy());
        resealCommandAndTrajectory(
                material.command(), material.trajectory());

        assertThat(verifier.verify(
                material.command(),
                material.trajectory(),
                material.corpusPublication(),
                material.corpusRevision(),
                material.verificationTime()))
                .extracting(
                        CapabilityCorpusTrajectoryVerifier.VerificationResult
                                ::outcome,
                        CapabilityCorpusTrajectoryVerifier.VerificationResult
                                ::reasonCode)
                .containsExactly(
                        CapabilityCorpusTrajectoryVerifier.Outcome
                                .LINEAGE_INVALID,
                        "TRAJECTORY_ATTEMPT_BINDING_INVALID");
    }

    @Test
    void rejectsUnknownFieldsAtStrictSchemaBoundary() throws Exception {
        Material material = material();
        material.trajectory().put(
                "responseBody", "must-never-enter-control-plane");

        assertThat(verifier.verify(
                material.command(),
                material.trajectory(),
                material.corpusPublication(),
                material.corpusRevision(),
                material.verificationTime()).reasonCode())
                .isEqualTo("TRAJECTORY_PUBLICATION_SCHEMA_INVALID");
    }

    private Material material() throws Exception {
        CapabilityCorpusCompatibilityFixture fixture =
                CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();
        ObjectNode corpusRevision =
                (ObjectNode) fixture.revision().deepCopy();
        ArrayNode sources = (ArrayNode) corpusRevision.path("sources");
        ObjectNode second = (ObjectNode) sources.get(0).deepCopy();
        ObjectNode observation =
                (ObjectNode) second.path("observationRef");
        observation.put("id", "support-refund-observation-2");
        observation.put("fingerprint", fingerprint('a'));
        ObjectNode admission = (ObjectNode) second.path("admissionRef");
        admission.put("id", "support-refund-observation-2:admission");
        admission.put("fingerprint", fingerprint('b'));
        second.put("traceFingerprint", fingerprint('c'));
        second.put("occurredAt", "2026-07-23T00:00:00.250Z");
        sources.add(second);
        ObjectNode risk = (ObjectNode) corpusRevision.path("riskSummary");
        risk.put("sampleCount", 2);
        risk.put("uniqueRequestCount", 1);
        risk.put("duplicateRequestCount", 1);
        risk.put("maximumRequestMultiplicity", 2);
        risk.put("duplicateBasisPoints", 5000);
        reseal(corpusRevision, "revisionFingerprint");

        ObjectNode corpusPublication =
                (ObjectNode) fixture.publication().deepCopy();
        corpusPublication.set(
                "corpusRevisionRef",
                artifactRef(
                        corpusRevision,
                        "CAPABILITY_CORPUS_REVISION",
                        "revisionFingerprint"));
        reseal(corpusPublication, "publicationFingerprint");

        ObjectNode command = mapper.createObjectNode();
        command.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .CAPABILITY_CORPUS_TRAJECTORY_PUBLISH_REQUEST_V1);
        command.put("trajectoryId", "support-timeout-trajectory");
        command.put("revision", 1);
        command.putNull("expectedPredecessorRef");
        command.set(
                "capabilityRef",
                corpusRevision.path("capabilityRef").deepCopy());
        command.set(
                "corpusPublicationRef",
                artifactRef(
                        corpusPublication,
                        "CAPABILITY_CORPUS_PUBLICATION",
                        "publicationFingerprint"));
        command.set(
                "retryPolicyRef",
                artifactRef(
                        "RETRY_POLICY",
                        "support-retry-policy",
                        3,
                        fingerprint('7')));
        ArrayNode attempts = command.putArray("attempts");
        for (int index = 0; index < sources.size(); index++) {
            ObjectNode attempt = attempts.addObject();
            attempt.put("attempt", index + 1);
            attempt.set(
                    "observationRef",
                    sources.path(index).path("observationRef").deepCopy());
            attempt.set(
                    "admissionRef",
                    sources.path(index).path("admissionRef").deepCopy());
        }
        command.set(
                "reviewTicketRef",
                artifactRef(
                        "GOVERNANCE_REVIEW_TICKET",
                        "trajectory-review",
                        1,
                        fingerprint('6')));
        command.put("reasonCode", "OWNER_APPROVED_RETRY_TRAJECTORY");

        ObjectNode trajectory = mapper.createObjectNode();
        trajectory.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION_V1);
        trajectory.put("trajectoryFingerprint", ZERO_FINGERPRINT);
        trajectory.put(
                "sourceCommandFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        command,
                        CapabilityCorpusTrajectoryVerifier
                                .MAXIMUM_CANONICAL_BYTES));
        trajectory.set("scope", corpusRevision.path("scope").deepCopy());
        trajectory.put("trajectoryId", command.path("trajectoryId").asText());
        trajectory.put("revision", 1);
        trajectory.putNull("predecessorRef");
        trajectory.set(
                "capabilityRef",
                command.path("capabilityRef").deepCopy());
        trajectory.set(
                "corpusPublicationRef",
                command.path("corpusPublicationRef").deepCopy());
        trajectory.set(
                "corpusRevisionRef",
                artifactRef(
                        corpusRevision,
                        "CAPABILITY_CORPUS_REVISION",
                        "revisionFingerprint"));
        trajectory.set(
                "publicationPolicyRef",
                corpusPublication.path("publicationPolicyRef").deepCopy());
        trajectory.set(
                "retryPolicyRef",
                command.path("retryPolicyRef").deepCopy());
        trajectory.put(
                "requestFingerprint",
                sources.get(0).at(
                        "/requestPayloadRef/fingerprint").asText());
        trajectory.set("attempts", command.path("attempts").deepCopy());
        trajectory.set(
                "reviewTicketRef",
                command.path("reviewTicketRef").deepCopy());
        trajectory.put(
                "reasonCode", command.path("reasonCode").asText());
        trajectory.put("reviewedBy", "corpus-publisher");
        trajectory.put("publishedAt", "2026-07-23T00:00:03Z");
        trajectory.put(
                "usableUntil",
                corpusPublication.path("usableUntil").asText());
        reseal(trajectory, "trajectoryFingerprint");
        return new Material(
                command,
                trajectory,
                corpusPublication,
                corpusRevision,
                fixture.verificationTime());
    }

    private static void resealCommandAndTrajectory(
            ObjectNode command,
            ObjectNode trajectory) {
        trajectory.put(
                "sourceCommandFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        command,
                        CapabilityCorpusTrajectoryVerifier
                                .MAXIMUM_CANONICAL_BYTES));
        reseal(trajectory, "trajectoryFingerprint");
    }

    private static void reseal(ObjectNode value, String field) {
        value.put(field, ZERO_FINGERPRINT);
        value.put(
                field,
                EvidenceVerificationSupport.sha256Bounded(
                        value,
                        CapabilityCorpusTrajectoryVerifier
                                .MAXIMUM_CANONICAL_BYTES));
    }

    private static ObjectNode artifactRef(
            JsonNode artifact,
            String kind,
            String fingerprintField) {
        return artifactRef(
                kind,
                artifact.path("corpusId").asText(),
                artifact.path("revision").asLong(),
                artifact.path(fingerprintField).asText());
    }

    private static ObjectNode artifactRef(
            String kind,
            String id,
            long revision,
            String fingerprint) {
        return new ObjectMapper().createObjectNode()
                .put("kind", kind)
                .put("id", id)
                .put("revision", revision)
                .put("fingerprint", fingerprint);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Material(
            ObjectNode command,
            ObjectNode trajectory,
            ObjectNode corpusPublication,
            ObjectNode corpusRevision,
            Instant verificationTime
    ) {
    }
}
