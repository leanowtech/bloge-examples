package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Independent offline verifier for one recorded trajectory publication.
 *
 * <p>The verifier closes all four strict schemas, recomputes command and artifact fingerprints,
 * checks exact corpus publication/revision content-address bindings, proves every ordered attempt
 * belongs to the revision, and checks the common request fingerprint and time horizons. It cannot
 * replace online retry-policy, observation trace, grant, retention, tombstone, or payload-authority
 * checks; successful results expose that limitation explicitly.</p>
 */
public final class CapabilityCorpusTrajectoryVerifier {
    /** Maximum canonical command or artifact bytes. */
    public static final int MAXIMUM_CANONICAL_BYTES = 4 * 1024 * 1024;
    private static final String ZERO_FINGERPRINT =
            "sha256:" + "0".repeat(64);
    private static final Set<String> ONLINE_LIMITATIONS = Set.of(
            "ONLINE_RETRY_POLICY_REQUIRED",
            "ONLINE_TRACE_AND_OUTCOME_REQUIRED",
            "ONLINE_GRANT_AND_RETENTION_REQUIRED",
            "ONLINE_SOURCE_AND_PAYLOAD_AUTHORITY_REQUIRED");

    /** Creates a dependency-free trajectory verifier. */
    public CapabilityCorpusTrajectoryVerifier() {
    }

    /**
     * Verifies one detached trajectory chain without server implementation classes.
     *
     * @param command exact trajectory publication command
     * @param publication immutable trajectory publication
     * @param corpusPublication exact referenced corpus publication
     * @param corpusRevision exact referenced corpus revision
     * @param verificationTime trusted local verification instant
     * @return stable payload-free result
     */
    public VerificationResult verify(
            JsonNode command,
            JsonNode publication,
            JsonNode corpusPublication,
            JsonNode corpusRevision,
            Instant verificationTime) {
        String trajectoryId = text(publication, "trajectoryId");
        String fingerprint = text(publication, "trajectoryFingerprint");
        try {
            if (verificationTime == null) {
                fail(Outcome.WINDOW_REJECTED, "VERIFICATION_TIME_MISSING");
            }
            schema(
                    command,
                    CapabilityMirrorProtocol
                            .CAPABILITY_CORPUS_TRAJECTORY_PUBLISH_REQUEST_SCHEMA_RESOURCE,
                    "TRAJECTORY_COMMAND_SCHEMA_INVALID");
            schema(
                    publication,
                    CapabilityMirrorProtocol
                            .CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION_SCHEMA_RESOURCE,
                    "TRAJECTORY_PUBLICATION_SCHEMA_INVALID");
            schema(
                    corpusPublication,
                    CapabilityMirrorProtocol
                            .CAPABILITY_CORPUS_PUBLICATION_SCHEMA_RESOURCE,
                    "TRAJECTORY_CORPUS_PUBLICATION_SCHEMA_INVALID");
            schema(
                    corpusRevision,
                    CapabilityMirrorProtocol
                            .CAPABILITY_CORPUS_REVISION_SCHEMA_RESOURCE,
                    "TRAJECTORY_CORPUS_REVISION_SCHEMA_INVALID");
            requireFingerprint(
                    command,
                    publication.path("sourceCommandFingerprint").asText(),
                    "TRAJECTORY_COMMAND_FINGERPRINT_INVALID");
            requireArtifactFingerprint(
                    publication,
                    "trajectoryFingerprint",
                    "TRAJECTORY_PUBLICATION_FINGERPRINT_INVALID");
            requireArtifactFingerprint(
                    corpusPublication,
                    "publicationFingerprint",
                    "TRAJECTORY_CORPUS_PUBLICATION_FINGERPRINT_INVALID");
            requireArtifactFingerprint(
                    corpusRevision,
                    "revisionFingerprint",
                    "TRAJECTORY_CORPUS_REVISION_FINGERPRINT_INVALID");
            requireCommandBinding(command, publication);
            requireCorpusBinding(
                    publication, corpusPublication, corpusRevision);
            requireAttempts(
                    publication, corpusRevision);
            requireWindows(
                    publication,
                    corpusPublication,
                    corpusRevision,
                    verificationTime);
            return new VerificationResult(
                    Outcome.VERIFIED,
                    "STRUCTURALLY_VERIFIED",
                    trajectoryId,
                    fingerprint,
                    ONLINE_LIMITATIONS);
        } catch (VerificationFailure failure) {
            return new VerificationResult(
                    failure.outcome,
                    failure.reasonCode,
                    trajectoryId,
                    fingerprint,
                    Set.of());
        } catch (RuntimeException malformed) {
            return new VerificationResult(
                    Outcome.INTEGRITY_INVALID,
                    "TRAJECTORY_VERIFICATION_FAILED",
                    trajectoryId,
                    fingerprint,
                    Set.of());
        }
    }

    private static void requireCommandBinding(
            JsonNode command,
            JsonNode publication) {
        for (String field : Set.of(
                "trajectoryId",
                "revision",
                "capabilityRef",
                "corpusPublicationRef",
                "retryPolicyRef",
                "attempts",
                "reviewTicketRef",
                "reasonCode")) {
            if (!command.path(field).equals(publication.path(field))) {
                fail(
                        Outcome.LINEAGE_INVALID,
                        "TRAJECTORY_COMMAND_BINDING_INVALID");
            }
        }
        if (!command.path("expectedPredecessorRef").equals(
                publication.path("predecessorRef"))) {
            fail(
                    Outcome.LINEAGE_INVALID,
                    "TRAJECTORY_PREDECESSOR_BINDING_INVALID");
        }
        requireLineage(publication);
    }

    private static void requireLineage(JsonNode publication) {
        long revision = publication.path("revision").asLong();
        JsonNode predecessor = publication.path("predecessorRef");
        if (revision == 1) {
            if (!predecessor.isNull()) {
                fail(
                        Outcome.LINEAGE_INVALID,
                        "TRAJECTORY_LINEAGE_INVALID");
            }
            return;
        }
        if (predecessor.isNull()
                || !"CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION".equals(
                predecessor.path("kind").asText())
                || !publication.path("trajectoryId").asText().equals(
                predecessor.path("id").asText())
                || predecessor.path("revision").asLong() != revision - 1) {
            fail(Outcome.LINEAGE_INVALID, "TRAJECTORY_LINEAGE_INVALID");
        }
    }

    private static void requireCorpusBinding(
            JsonNode publication,
            JsonNode corpusPublication,
            JsonNode corpusRevision) {
        JsonNode expectedPublication = artifactRef(
                corpusPublication,
                "CAPABILITY_CORPUS_PUBLICATION",
                "publicationFingerprint");
        JsonNode expectedRevision = artifactRef(
                corpusRevision,
                "CAPABILITY_CORPUS_REVISION",
                "revisionFingerprint");
        if (!expectedPublication.equals(
                publication.path("corpusPublicationRef"))
                || !expectedRevision.equals(
                publication.path("corpusRevisionRef"))
                || !expectedRevision.equals(
                corpusPublication.path("corpusRevisionRef"))
                || !corpusRevision.path("capabilityRef").equals(
                publication.path("capabilityRef"))
                || !corpusPublication.path("scope").equals(
                publication.path("scope"))
                || !corpusRevision.path("scope").equals(
                publication.path("scope"))
                || !corpusPublication.path("publicationPolicyRef").equals(
                publication.path("publicationPolicyRef"))) {
            fail(
                    Outcome.LINEAGE_INVALID,
                    "TRAJECTORY_CORPUS_BINDING_INVALID");
        }
    }

    private static void requireAttempts(
            JsonNode publication,
            JsonNode corpusRevision) {
        Map<String, JsonNode> members = new HashMap<>();
        for (JsonNode source : corpusRevision.path("sources")) {
            members.put(
                    source.at("/observationRef/fingerprint").asText(),
                    source);
        }
        Set<String> observations = new HashSet<>();
        String requestFingerprint =
                publication.path("requestFingerprint").asText();
        JsonNode attempts = publication.path("attempts");
        for (int index = 0; index < attempts.size(); index++) {
            JsonNode attempt = attempts.path(index);
            String observationFingerprint =
                    attempt.at("/observationRef/fingerprint").asText();
            JsonNode member = members.get(observationFingerprint);
            if (attempt.path("attempt").asInt() != index + 1
                    || !observations.add(observationFingerprint)
                    || member == null
                    || !attempt.path("observationRef").equals(
                    member.path("observationRef"))
                    || !attempt.path("admissionRef").equals(
                    member.path("admissionRef"))
                    || !requestFingerprint.equals(
                    member.at("/requestPayloadRef/fingerprint").asText())) {
                fail(
                        Outcome.LINEAGE_INVALID,
                        "TRAJECTORY_ATTEMPT_BINDING_INVALID");
            }
        }
    }

    private static void requireWindows(
            JsonNode publication,
            JsonNode corpusPublication,
            JsonNode corpusRevision,
            Instant verificationTime) {
        Instant publishedAt = instant(publication.path("publishedAt"));
        Instant usableUntil = instant(publication.path("usableUntil"));
        Instant corpusPublicationUntil =
                instant(corpusPublication.path("usableUntil"));
        Instant corpusRevisionUntil =
                instant(corpusRevision.path("usableUntil"));
        if (publishedAt.isAfter(verificationTime)
                || !usableUntil.isAfter(verificationTime)
                || !usableUntil.isAfter(publishedAt)
                || usableUntil.isAfter(corpusPublicationUntil)
                || usableUntil.isAfter(corpusRevisionUntil)) {
            fail(
                    Outcome.WINDOW_REJECTED,
                    "TRAJECTORY_WINDOW_INVALID");
        }
    }

    private static void schema(
            JsonNode value,
            String resource,
            String reason) {
        try {
            CapabilityMirrorSchemaValidator.require(value, resource, reason);
        } catch (IllegalArgumentException invalid) {
            fail(Outcome.SCHEMA_INVALID, reason);
        }
    }

    private static void requireFingerprint(
            JsonNode value,
            String expected,
            String reason) {
        try {
            if (!EvidenceVerificationSupport.sha256Bounded(
                    value, MAXIMUM_CANONICAL_BYTES).equals(expected)) {
                fail(Outcome.INTEGRITY_INVALID, reason);
            }
        } catch (IllegalArgumentException invalid) {
            fail(Outcome.INTEGRITY_INVALID, reason);
        }
    }

    private static void requireArtifactFingerprint(
            JsonNode value,
            String field,
            String reason) {
        ObjectNode blank = (ObjectNode) value.deepCopy();
        String expected = blank.path(field).asText();
        blank.put(field, ZERO_FINGERPRINT);
        requireFingerprint(blank, expected, reason);
    }

    private static ObjectNode artifactRef(
            JsonNode artifact,
            String kind,
            String fingerprintField) {
        return JsonNodeFactory.instance.objectNode()
                .put("kind", kind)
                .put(
                        "id",
                        artifact.has("trajectoryId")
                                ? artifact.path("trajectoryId").asText()
                                : artifact.path("corpusId").asText())
                .put("revision", artifact.path("revision").asLong())
                .put(
                        "fingerprint",
                        artifact.path(fingerprintField).asText());
    }

    private static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException malformed) {
            fail(Outcome.WINDOW_REJECTED, "TRAJECTORY_TIME_INVALID");
            throw new IllegalStateException("unreachable");
        }
    }

    private static String text(JsonNode value, String field) {
        return value == null ? "" : value.path(field).asText("");
    }

    private static void fail(Outcome outcome, String reasonCode) {
        throw new VerificationFailure(outcome, reasonCode);
    }

    /** Closed offline verification outcome. */
    public enum Outcome {
        /** Structure, content addresses, source membership, and windows verify. */
        VERIFIED,
        /** At least one closed wire schema rejected. */
        SCHEMA_INVALID,
        /** A command or artifact content address is invalid. */
        INTEGRITY_INVALID,
        /** Artifact lineage, corpus binding, or attempt membership is invalid. */
        LINEAGE_INVALID,
        /** Publication or corpus time horizon is invalid. */
        WINDOW_REJECTED
    }

    /**
     * Stable offline result that never overstates online readiness.
     *
     * @param outcome closed verification outcome
     * @param reasonCode stable payload-free reason
     * @param trajectoryId trajectory identity when available
     * @param trajectoryFingerprint trajectory content address when available
     * @param onlineLimitations authority checks still required online
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String trajectoryId,
            String trajectoryFingerprint,
            Set<String> onlineLimitations
    ) {
        /** Freezes limitations and validates the result shape. */
        public VerificationResult {
            outcome = java.util.Objects.requireNonNull(outcome, "outcome");
            reasonCode = java.util.Objects.requireNonNull(
                    reasonCode, "reasonCode");
            trajectoryId = trajectoryId == null ? "" : trajectoryId;
            trajectoryFingerprint =
                    trajectoryFingerprint == null
                            ? "" : trajectoryFingerprint;
            onlineLimitations = onlineLimitations == null
                    ? Set.of() : Set.copyOf(onlineLimitations);
        }

        /**
         * Reports whether all offline structural checks succeeded.
         *
         * @return true only for structurally verified offline material
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final Outcome outcome;
        private final String reasonCode;

        private VerificationFailure(
                Outcome outcome,
                String reasonCode) {
            super(reasonCode);
            this.outcome = outcome;
            this.reasonCode = reasonCode;
        }
    }
}
