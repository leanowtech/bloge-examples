package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent offline verifier for one recorded-cluster publication lifecycle.
 *
 * <p>The verifier closes all five strict schemas, recomputes corpus, validation, command, and
 * cluster fingerprints, proves exact corpus membership, validates identity-safe JSON Pointer
 * topology, recomputes holdout arithmetic and the v1 Wilson precision interval, and checks all
 * time horizons. It cannot replace online policy, validation-authority, grant, retention,
 * source-lifecycle, or payload-content checks; successful results expose those limitations
 * explicitly.</p>
 */
public final class CapabilityCorpusClusterVerifier {
    /** Maximum canonical command or artifact bytes. */
    public static final int MAXIMUM_CANONICAL_BYTES = 4 * 1024 * 1024;
    private static final String ZERO_FINGERPRINT =
            "sha256:" + "0".repeat(64);
    private static final String CONFIDENCE_METHOD =
            "WILSON_PRECISION_95_V1";
    private static final double CONFIDENCE_TOLERANCE = 1.0e-9d;
    private static final Set<String> ONLINE_LIMITATIONS = Set.of(
            "ONLINE_CLUSTER_POLICY_REQUIRED",
            "ONLINE_VALIDATION_AUTHORITY_REQUIRED",
            "ONLINE_GRANT_AND_RETENTION_REQUIRED",
            "ONLINE_SOURCE_AND_PAYLOAD_AUTHORITY_REQUIRED");

    /** Creates a dependency-free recorded-cluster verifier. */
    public CapabilityCorpusClusterVerifier() {
    }

    /**
     * Verifies one detached cluster lifecycle without server implementation classes.
     *
     * @param fixture exact payload-free compatibility fixture
     * @return stable verification result
     */
    public VerificationResult verify(
            CapabilityCorpusClusterCompatibilityFixture fixture) {
        if (fixture == null) {
            return rejected(
                    Outcome.INTEGRITY_INVALID,
                    "CLUSTER_FIXTURE_MISSING",
                    "",
                    "");
        }
        return verify(
                fixture.corpusRevision(),
                fixture.corpusPublication(),
                fixture.validation(),
                fixture.publishRequest(),
                fixture.publication(),
                fixture.expectedScope(),
                fixture.verificationTime());
    }

    /**
     * Verifies explicit detached cluster artifacts.
     *
     * @param corpusRevision exact corpus revision
     * @param corpusPublication exact corpus publication
     * @param validation exact data-plane validation
     * @param command exact cluster publication command
     * @param publication immutable cluster publication
     * @param expectedScope local exact enterprise scope
     * @param verificationTime trusted local verification time
     * @return stable payload-free result
     */
    public VerificationResult verify(
            JsonNode corpusRevision,
            JsonNode corpusPublication,
            JsonNode validation,
            JsonNode command,
            JsonNode publication,
            CapabilityObservationScope expectedScope,
            Instant verificationTime) {
        String clusterId = text(publication, "clusterId");
        String fingerprint = text(publication, "clusterFingerprint");
        try {
            if (expectedScope == null || verificationTime == null) {
                fail(Outcome.WINDOW_REJECTED, "CLUSTER_EXPECTATION_MISSING");
            }
            schema(
                    corpusRevision,
                    CapabilityMirrorProtocol
                            .CAPABILITY_CORPUS_REVISION_SCHEMA_RESOURCE,
                    "CLUSTER_CORPUS_REVISION_SCHEMA_INVALID");
            schema(
                    corpusPublication,
                    CapabilityMirrorProtocol
                            .CAPABILITY_CORPUS_PUBLICATION_SCHEMA_RESOURCE,
                    "CLUSTER_CORPUS_PUBLICATION_SCHEMA_INVALID");
            schema(
                    validation,
                    CapabilityMirrorProtocol
                            .CAPABILITY_CORPUS_CLUSTER_VALIDATION_SCHEMA_RESOURCE,
                    "CLUSTER_VALIDATION_SCHEMA_INVALID");
            schema(
                    command,
                    CapabilityMirrorProtocol
                            .CAPABILITY_CORPUS_CLUSTER_PUBLISH_REQUEST_SCHEMA_RESOURCE,
                    "CLUSTER_COMMAND_SCHEMA_INVALID");
            schema(
                    publication,
                    CapabilityMirrorProtocol
                            .CAPABILITY_CORPUS_CLUSTER_PUBLICATION_SCHEMA_RESOURCE,
                    "CLUSTER_PUBLICATION_SCHEMA_INVALID");
            requireArtifactFingerprint(
                    corpusRevision,
                    "revisionFingerprint",
                    "CLUSTER_CORPUS_REVISION_FINGERPRINT_INVALID");
            requireArtifactFingerprint(
                    corpusPublication,
                    "publicationFingerprint",
                    "CLUSTER_CORPUS_PUBLICATION_FINGERPRINT_INVALID");
            requireArtifactFingerprint(
                    validation,
                    "validationFingerprint",
                    "CLUSTER_VALIDATION_FINGERPRINT_INVALID");
            requireFingerprint(
                    command,
                    publication.path("sourceCommandFingerprint").asText(),
                    "CLUSTER_COMMAND_FINGERPRINT_INVALID");
            requireArtifactFingerprint(
                    publication,
                    "clusterFingerprint",
                    "CLUSTER_PUBLICATION_FINGERPRINT_INVALID");
            requireScope(
                    corpusRevision,
                    corpusPublication,
                    validation,
                    publication,
                    expectedScope);
            requireCorpusBinding(
                    corpusRevision, corpusPublication, validation, publication);
            requireCommandBinding(command, publication);
            requireValidationBinding(validation, publication);
            requireMembership(corpusRevision, validation, publication);
            requireIdentitySafety(validation, publication);
            requireConfidence(validation, publication);
            requireWindows(
                    corpusRevision,
                    corpusPublication,
                    validation,
                    publication,
                    verificationTime);
            return new VerificationResult(
                    Outcome.VERIFIED,
                    "STRUCTURALLY_VERIFIED",
                    clusterId,
                    fingerprint,
                    ONLINE_LIMITATIONS);
        } catch (VerificationFailure failure) {
            return rejected(
                    failure.outcome,
                    failure.reasonCode,
                    clusterId,
                    fingerprint);
        } catch (RuntimeException malformed) {
            return rejected(
                    Outcome.INTEGRITY_INVALID,
                    "CLUSTER_VERIFICATION_FAILED",
                    clusterId,
                    fingerprint);
        }
    }

    private static void requireScope(
            JsonNode corpusRevision,
            JsonNode corpusPublication,
            JsonNode validation,
            JsonNode publication,
            CapabilityObservationScope expectedScope) {
        JsonNode scope = corpusRevision.path("scope");
        if (!expectedScope.matches(scope)
                || !scope.equals(corpusPublication.path("scope"))
                || !scope.equals(validation.path("scope"))
                || !scope.equals(publication.path("scope"))) {
            fail(Outcome.LINEAGE_INVALID, "CLUSTER_SCOPE_INVALID");
        }
    }

    private static void requireCorpusBinding(
            JsonNode corpusRevision,
            JsonNode corpusPublication,
            JsonNode validation,
            JsonNode publication) {
        JsonNode revisionRef = artifactRef(
                corpusRevision,
                "CAPABILITY_CORPUS_REVISION",
                "revisionFingerprint",
                "corpusId");
        JsonNode corpusPublicationRef = artifactRef(
                corpusPublication,
                "CAPABILITY_CORPUS_PUBLICATION",
                "publicationFingerprint",
                "corpusId");
        if (!revisionRef.equals(
                corpusPublication.path("corpusRevisionRef"))
                || !revisionRef.equals(validation.path("corpusRevisionRef"))
                || !revisionRef.equals(publication.path("corpusRevisionRef"))) {
            fail(
                    Outcome.LINEAGE_INVALID,
                    "CLUSTER_CORPUS_REVISION_BINDING_INVALID");
        }
        if (!corpusPublicationRef.equals(
                validation.path("corpusPublicationRef"))
                || !corpusPublicationRef.equals(
                publication.path("corpusPublicationRef"))) {
            fail(
                    Outcome.LINEAGE_INVALID,
                    "CLUSTER_CORPUS_PUBLICATION_BINDING_INVALID");
        }
        if (!corpusRevision.path("capabilityRef").equals(
                validation.path("capabilityRef"))
                || !corpusRevision.path("capabilityRef").equals(
                publication.path("capabilityRef"))) {
            fail(
                    Outcome.LINEAGE_INVALID,
                    "CLUSTER_CAPABILITY_BINDING_INVALID");
        }
        if (!"ELIGIBLE".equals(corpusRevision.at(
                "/riskSummary/eligibility").asText())) {
            fail(
                    Outcome.LINEAGE_INVALID,
                    "CLUSTER_CORPUS_NOT_ELIGIBLE");
        }
    }

    private static void requireCommandBinding(
            JsonNode command,
            JsonNode publication) {
        for (String field : Set.of(
                "clusterId",
                "revision",
                "capabilityRef",
                "corpusPublicationRef",
                "clusterPolicyRef",
                "validationRef",
                "reviewTicketRef",
                "reasonCode")) {
            if (!command.path(field).equals(publication.path(field))) {
                fail(
                        Outcome.LINEAGE_INVALID,
                        "CLUSTER_COMMAND_BINDING_INVALID");
            }
        }
        if (!command.path("expectedPredecessorRef").equals(
                publication.path("predecessorRef"))) {
            fail(
                    Outcome.LINEAGE_INVALID,
                    "CLUSTER_PREDECESSOR_BINDING_INVALID");
        }
        requireLineage(publication);
    }

    private static void requireLineage(JsonNode publication) {
        long revision = publication.path("revision").asLong();
        JsonNode predecessor = publication.path("predecessorRef");
        if (revision == 1) {
            if (!predecessor.isNull()) {
                fail(Outcome.LINEAGE_INVALID, "CLUSTER_LINEAGE_INVALID");
            }
            return;
        }
        if (predecessor.isNull()
                || !"CAPABILITY_CORPUS_CLUSTER_PUBLICATION".equals(
                predecessor.path("kind").asText())
                || !publication.path("clusterId").asText().equals(
                predecessor.path("id").asText())
                || predecessor.path("revision").asLong() != revision - 1) {
            fail(Outcome.LINEAGE_INVALID, "CLUSTER_LINEAGE_INVALID");
        }
    }

    private static void requireValidationBinding(
            JsonNode validation,
            JsonNode publication) {
        JsonNode validationRef = artifactRef(
                validation,
                "CAPABILITY_CORPUS_CLUSTER_VALIDATION",
                "validationFingerprint",
                "validationId");
        if (!validationRef.equals(publication.path("validationRef"))
                || !validation.path("representativeSource").equals(
                publication.path("representativeSource"))
                || !validation.path("members").equals(
                publication.path("members"))
                || !validation.path("matchRequestPointers").equals(
                publication.path("matchRequestPointers"))
                || !validation.path("identityMode").equals(
                publication.path("identityMode"))
                || !validation.path("identityProjections").equals(
                publication.path("identityProjections"))
                || !validation.path("distinctIdentityCount").equals(
                publication.path("distinctIdentityCount"))
                || !validation.path("holdout").equals(
                publication.path("holdout"))
                || !validation.path("confidence").equals(
                publication.path("confidence"))
                || !validation.path("identityCoverageComplete").asBoolean()) {
            fail(
                    Outcome.LINEAGE_INVALID,
                    "CLUSTER_VALIDATION_BINDING_INVALID");
        }
    }

    private static void requireMembership(
            JsonNode corpusRevision,
            JsonNode validation,
            JsonNode publication) {
        Map<String, JsonNode> corpusSources = new HashMap<>();
        JsonNode expectedSchema = null;
        for (JsonNode source : corpusRevision.path("sources")) {
            String observationFingerprint = source.at(
                    "/observationRef/fingerprint").asText();
            corpusSources.put(observationFingerprint, source);
        }
        Set<String> seen = new HashSet<>();
        String previousId = "";
        for (JsonNode member : validation.path("members")) {
            String id = member.at("/observationRef/id").asText();
            String fingerprint = member.at(
                    "/observationRef/fingerprint").asText();
            JsonNode source = corpusSources.get(fingerprint);
            if (source == null
                    || !source.path("observationRef").equals(
                    member.path("observationRef"))
                    || !source.path("admissionRef").equals(
                    member.path("admissionRef"))
                    || !seen.add(fingerprint)
                    || id.compareTo(previousId) <= 0
                    || source.path("responsePayloadRef").isNull()
                    || source.path("responseSchemaRef").isNull()
                    || !source.path("normalizedErrorCode").asText().isEmpty()) {
                fail(
                        Outcome.MEMBERSHIP_INVALID,
                        "CLUSTER_MEMBER_NOT_IN_CORPUS");
            }
            if (expectedSchema == null) {
                expectedSchema = source.path("responseSchemaRef");
            } else if (!expectedSchema.equals(
                    source.path("responseSchemaRef"))) {
                fail(
                        Outcome.MEMBERSHIP_INVALID,
                        "CLUSTER_MEMBER_SCHEMA_MISMATCH");
            }
            previousId = id;
        }
        if (seen.size() < 2
                || validation.path("distinctIdentityCount").asInt()
                < 1
                || validation.path("distinctIdentityCount").asInt()
                > seen.size()
                || !contains(
                validation.path("members"),
                validation.path("representativeSource"))
                || !validation.path("members").equals(
                publication.path("members"))) {
            fail(
                    Outcome.MEMBERSHIP_INVALID,
                    "CLUSTER_MEMBER_SET_INVALID");
        }
    }

    private static void requireIdentitySafety(
            JsonNode validation,
            JsonNode publication) {
        List<String> requestPointers = new ArrayList<>();
        validation.path("matchRequestPointers").forEach(
                value -> requestPointers.add(pointer(value)));
        requireSorted(requestPointers, "CLUSTER_MATCH_POINTER_ORDER_INVALID");
        List<String> responsePointers = new ArrayList<>();
        JsonNode projections = validation.path("identityProjections");
        for (JsonNode projection : projections) {
            requestPointers.add(pointer(
                    projection.path("requestPointer")));
            List<String> localResponses = new ArrayList<>();
            projection.path("responsePointers").forEach(value -> {
                String exact = pointer(value);
                localResponses.add(exact);
                responsePointers.add(exact);
            });
            requireSorted(
                    localResponses,
                    "CLUSTER_RESPONSE_POINTER_ORDER_INVALID");
            requireNonOverlapping(
                    localResponses,
                    "CLUSTER_RESPONSE_POINTER_OVERLAP");
        }
        String mode = validation.path("identityMode").asText();
        if ("IDENTITY_FREE_RESPONSE".equals(mode) && projections.size() != 0
                || "REQUEST_PROJECTION".equals(mode)
                && projections.isEmpty()) {
            fail(
                    Outcome.IDENTITY_UNSAFE,
                    "CLUSTER_IDENTITY_MODE_INVALID");
        }
        requireNonOverlapping(
                requestPointers,
                "CLUSTER_REQUEST_POINTER_OVERLAP");
        requireNonOverlapping(
                responsePointers,
                "CLUSTER_RESPONSE_POINTER_OVERLAP");
        if (!validation.path("matchRequestPointers").equals(
                publication.path("matchRequestPointers"))
                || !validation.path("identityProjections").equals(
                publication.path("identityProjections"))) {
            fail(
                    Outcome.IDENTITY_UNSAFE,
                    "CLUSTER_IDENTITY_PROJECTION_DRIFT");
        }
    }

    private static void requireConfidence(
            JsonNode validation,
            JsonNode publication) {
        JsonNode holdout = validation.path("holdout");
        int sampleCount = holdout.path("sampleCount").asInt();
        int acceptedCount = holdout.path("acceptedCount").asInt();
        int correctCount = holdout.path("correctCount").asInt();
        int falsePositiveCount =
                holdout.path("falsePositiveCount").asInt();
        if (sampleCount < 1 || acceptedCount < 1
                || acceptedCount > sampleCount
                || correctCount < 0 || falsePositiveCount < 0
                || correctCount + falsePositiveCount != acceptedCount) {
            fail(
                    Outcome.CONFIDENCE_INVALID,
                    "CLUSTER_HOLDOUT_COUNTS_INVALID");
        }
        double point = (double) correctCount / acceptedCount;
        double z = 1.959963984540054d;
        double denominator = 1.0d + z * z / acceptedCount;
        double center = point + z * z / (2.0d * acceptedCount);
        double spread = z * Math.sqrt(
                point * (1.0d - point) / acceptedCount
                        + z * z / (4.0d * acceptedCount * acceptedCount));
        double lower = Math.max(0.0d, (center - spread) / denominator);
        double upper = Math.min(1.0d, (center + spread) / denominator);
        JsonNode confidence = validation.path("confidence");
        if (!CONFIDENCE_METHOD.equals(confidence.path("method").asText())
                || !near(point, confidence.path("point").asDouble())
                || !near(lower, confidence.path("lowerBound").asDouble())
                || !near(upper, confidence.path("upperBound").asDouble())
                || !confidence.equals(publication.path("confidence"))) {
            fail(
                    Outcome.CONFIDENCE_INVALID,
                    "CLUSTER_CONFIDENCE_INVALID");
        }
    }

    private static void requireWindows(
            JsonNode corpusRevision,
            JsonNode corpusPublication,
            JsonNode validation,
            JsonNode publication,
            Instant verificationTime) {
        Instant revisionUntil = instant(corpusRevision.path("usableUntil"));
        Instant corpusUntil = instant(
                corpusPublication.path("usableUntil"));
        Instant validationAt = instant(validation.path("validatedAt"));
        Instant validationUntil = instant(validation.path("expiresAt"));
        Instant publishedAt = instant(publication.path("publishedAt"));
        Instant usableUntil = instant(publication.path("usableUntil"));
        if (!validationUntil.isAfter(validationAt)
                || publishedAt.isBefore(validationAt)
                || !usableUntil.isAfter(publishedAt)
                || usableUntil.isAfter(revisionUntil)
                || usableUntil.isAfter(corpusUntil)
                || usableUntil.isAfter(validationUntil)
                || verificationTime.isBefore(publishedAt)
                || !verificationTime.isBefore(usableUntil)) {
            fail(Outcome.WINDOW_REJECTED, "CLUSTER_WINDOW_INVALID");
        }
    }

    private static void requireArtifactFingerprint(
            JsonNode value,
            String field,
            String reason) {
        if (!value.isObject()) {
            fail(Outcome.INTEGRITY_INVALID, reason);
        }
        ObjectNode material = ((ObjectNode) value).deepCopy();
        material.put(field, ZERO_FINGERPRINT);
        requireFingerprint(
                material,
                value.path(field).asText(),
                reason);
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

    private static ObjectNode artifactRef(
            JsonNode artifact,
            String kind,
            String fingerprintField,
            String idField) {
        ObjectNode ref = JsonNodeFactory.instance.objectNode();
        ref.put("kind", kind);
        ref.put("id", artifact.path(idField).asText());
        ref.set("revision", artifact.path("revision").deepCopy());
        ref.put(
                "fingerprint",
                artifact.path(fingerprintField).asText());
        return ref;
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

    private static String pointer(JsonNode value) {
        String exact = value.asText();
        if (exact.isEmpty() || exact.length() > 512
                || exact.charAt(0) != '/'
                || exact.contains("*")) {
            fail(Outcome.IDENTITY_UNSAFE, "CLUSTER_POINTER_INVALID");
        }
        for (int index = 0; index < exact.length(); index++) {
            if (exact.charAt(index) == '~'
                    && (index + 1 >= exact.length()
                    || exact.charAt(index + 1) != '0'
                    && exact.charAt(index + 1) != '1')) {
                fail(
                        Outcome.IDENTITY_UNSAFE,
                        "CLUSTER_POINTER_INVALID");
            }
            if (exact.charAt(index) == '~') {
                index++;
            }
        }
        return exact;
    }

    private static void requireSorted(
            List<String> values,
            String reason) {
        List<String> sorted = values.stream()
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (!sorted.equals(values)) {
            fail(Outcome.IDENTITY_UNSAFE, reason);
        }
    }

    private static void requireNonOverlapping(
            List<String> values,
            String reason) {
        for (int left = 0; left < values.size(); left++) {
            for (int right = left + 1; right < values.size(); right++) {
                String first = values.get(left);
                String second = values.get(right);
                if (first.equals(second)
                        || first.startsWith(second + "/")
                        || second.startsWith(first + "/")) {
                    fail(Outcome.IDENTITY_UNSAFE, reason);
                }
            }
        }
    }

    private static boolean contains(JsonNode values, JsonNode target) {
        for (JsonNode value : values) {
            if (value.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static Instant instant(JsonNode value) {
        try {
            Instant exact = Instant.parse(value.asText());
            if (!exact.toString().equals(value.asText())) {
                fail(Outcome.WINDOW_REJECTED, "CLUSTER_TIME_INVALID");
            }
            return exact;
        } catch (DateTimeParseException invalid) {
            fail(Outcome.WINDOW_REJECTED, "CLUSTER_TIME_INVALID");
            throw new IllegalStateException("unreachable");
        }
    }

    private static boolean near(double expected, double actual) {
        return Double.isFinite(actual)
                && Math.abs(expected - actual) <= CONFIDENCE_TOLERANCE;
    }

    private static String text(JsonNode value, String field) {
        return value == null ? "" : value.path(field).asText("");
    }

    private static VerificationResult rejected(
            Outcome outcome,
            String reasonCode,
            String clusterId,
            String fingerprint) {
        return new VerificationResult(
                outcome,
                reasonCode,
                clusterId,
                fingerprint,
                Set.of());
    }

    private static void fail(Outcome outcome, String reasonCode) {
        throw new VerificationFailure(outcome, reasonCode);
    }

    /** Closed offline verification outcomes. */
    public enum Outcome {
        /** Every offline structural and integrity check passed. */
        VERIFIED,
        /** One strict JSON Schema rejected its artifact. */
        SCHEMA_INVALID,
        /** A canonical content address or command fingerprint failed. */
        INTEGRITY_INVALID,
        /** Scope, corpus, validation, command, or append lineage is inconsistent. */
        LINEAGE_INVALID,
        /** A supporting or representative source is not in the exact corpus. */
        MEMBERSHIP_INVALID,
        /** Identity mode or request/response pointer topology is unsafe. */
        IDENTITY_UNSAFE,
        /** Holdout counts or the Wilson confidence interval is inconsistent. */
        CONFIDENCE_INVALID,
        /** A validation, publication, or verification time is invalid. */
        WINDOW_REJECTED
    }

    /**
     * Stable payload-free verification result.
     *
     * @param outcome closed verification outcome
     * @param reasonCode stable bounded reason
     * @param clusterId cluster identity when parseable
     * @param clusterFingerprint artifact fingerprint when parseable
     * @param limitations online checks that remain mandatory after success
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String clusterId,
            String clusterFingerprint,
            Set<String> limitations
    ) {
        /** Defensively copies the bounded limitation set. */
        public VerificationResult {
            outcome = java.util.Objects.requireNonNull(outcome, "outcome");
            reasonCode = reasonCode == null ? "" : reasonCode;
            clusterId = clusterId == null ? "" : clusterId;
            clusterFingerprint = clusterFingerprint == null
                    ? "" : clusterFingerprint;
            limitations = limitations == null
                    ? Set.of() : Set.copyOf(limitations);
        }

        /**
         * Reports successful offline verification.
         *
         * @return true only for the verified outcome
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
