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
 * Independent verifier for payload-free corpus governance commands and facts.
 *
 * <p>The verifier closes strict schemas, recomputes command and artifact fingerprints, checks full
 * enterprise scope, binds each command to its immutable result, verifies candidate/publication
 * lineage, recomputes policy-independent risk statistics, and checks local time horizons. It
 * deliberately does not claim payload existence, current external policy, reviewer authorization,
 * or current serving-head status; those require live operator-owned authorities.</p>
 */
public final class CapabilityCorpusVerifier {
    /** Maximum canonical bytes accepted for one command or artifact. */
    public static final int MAXIMUM_CANONICAL_BYTES = 4 * 1024 * 1024;
    private static final String ZERO_FINGERPRINT =
            "sha256:" + "0".repeat(64);

    /** Creates a dependency-free corpus governance verifier. */
    public CapabilityCorpusVerifier() {
    }

    /**
     * Verifies one complete compatibility fixture without server implementation classes.
     *
     * @param fixture detached corpus-governance compatibility fixture
     * @return stable verification result with no business payload
     */
    public VerificationResult verify(
            CapabilityCorpusCompatibilityFixture fixture) {
        Coordinates coordinates = Coordinates.from(fixture);
        try {
            if (fixture == null) {
                return result(
                        Outcome.SCHEMA_INVALID,
                        "CORPUS_FIXTURE_MISSING",
                        coordinates);
            }
            requireSchemas(fixture);
            requireScopes(fixture);
            verifyReview(fixture);
            verifyRevision(fixture);
            verifyPublication(fixture);
            return result(Outcome.VERIFIED, "VERIFIED", coordinates);
        } catch (VerificationFailure failure) {
            return result(failure.outcome, failure.reasonCode, coordinates);
        } catch (RuntimeException malformed) {
            return result(
                    Outcome.INTEGRITY_INVALID,
                    "CORPUS_VERIFICATION_FAILED",
                    coordinates);
        }
    }

    private static void requireSchemas(
            CapabilityCorpusCompatibilityFixture fixture) {
        schema(
                fixture.reviewRequest(),
                CapabilityMirrorProtocol
                        .CAPABILITY_OBSERVATION_REVIEW_REQUEST_SCHEMA_RESOURCE,
                "CORPUS_REVIEW_REQUEST_SCHEMA_INVALID");
        schema(
                fixture.review(),
                CapabilityMirrorProtocol
                        .CAPABILITY_OBSERVATION_REVIEW_SCHEMA_RESOURCE,
                "CORPUS_REVIEW_SCHEMA_INVALID");
        schema(
                fixture.candidateRequest(),
                CapabilityMirrorProtocol
                        .CAPABILITY_CORPUS_CANDIDATE_REQUEST_SCHEMA_RESOURCE,
                "CORPUS_CANDIDATE_REQUEST_SCHEMA_INVALID");
        schema(
                fixture.revision(),
                CapabilityMirrorProtocol.CAPABILITY_CORPUS_REVISION_SCHEMA_RESOURCE,
                "CORPUS_REVISION_SCHEMA_INVALID");
        schema(
                fixture.publishRequest(),
                CapabilityMirrorProtocol
                        .CAPABILITY_CORPUS_PUBLISH_REQUEST_SCHEMA_RESOURCE,
                "CORPUS_PUBLISH_REQUEST_SCHEMA_INVALID");
        schema(
                fixture.publication(),
                CapabilityMirrorProtocol
                        .CAPABILITY_CORPUS_PUBLICATION_SCHEMA_RESOURCE,
                "CORPUS_PUBLICATION_SCHEMA_INVALID");
    }

    private static void schema(
            JsonNode value, String resource, String reason) {
        try {
            CapabilityMirrorSchemaValidator.require(
                    value, resource, reason);
        } catch (IllegalArgumentException invalid) {
            fail(Outcome.SCHEMA_INVALID, reason);
        }
    }

    private static void requireScopes(
            CapabilityCorpusCompatibilityFixture fixture) {
        CapabilityObservationScope expected = fixture.expectedScope();
        if (!scopeMatches(fixture.review().path("scope"), expected)
                || !scopeMatches(fixture.revision().path("scope"), expected)
                || !scopeMatches(
                fixture.publication().path("scope"), expected)) {
            fail(Outcome.SCOPE_MISMATCH, "CORPUS_SCOPE_MISMATCH");
        }
    }

    private static void verifyReview(
            CapabilityCorpusCompatibilityFixture fixture) {
        JsonNode command = fixture.reviewRequest();
        JsonNode review = fixture.review();
        requireFingerprint(
                command,
                review.path("sourceCommandFingerprint").asText(),
                "CORPUS_REVIEW_COMMAND_FINGERPRINT_INVALID");
        requireArtifactFingerprint(
                review,
                "reviewFingerprint",
                "CORPUS_REVIEW_FINGERPRINT_INVALID");
        for (String field : Set.of(
                "observationRef",
                "admissionRef",
                "disposition",
                "reviewTicketRef",
                "reasonCode")) {
            if (!command.path(field).equals(review.path(field))) {
                fail(Outcome.LINEAGE_INVALID, "CORPUS_REVIEW_BINDING_INVALID");
            }
        }
        requireAdmissionBinding(
                review.path("observationRef"),
                review.path("admissionRef"),
                "CORPUS_REVIEW_ADMISSION_BINDING_INVALID");
        if (instant(review.path("reviewedAt")).isAfter(
                fixture.verificationTime())) {
            fail(Outcome.WINDOW_REJECTED, "CORPUS_REVIEW_IN_FUTURE");
        }
    }

    private static void verifyRevision(
            CapabilityCorpusCompatibilityFixture fixture) {
        JsonNode command = fixture.candidateRequest();
        JsonNode revision = fixture.revision();
        requireFingerprint(
                command,
                revision.path("sourceCommandFingerprint").asText(),
                "CORPUS_CANDIDATE_COMMAND_FINGERPRINT_INVALID");
        requireArtifactFingerprint(
                revision,
                "revisionFingerprint",
                "CORPUS_REVISION_FINGERPRINT_INVALID");
        for (String field : Set.of(
                "corpusId", "revision", "capabilityRef")) {
            if (!command.path(field).equals(revision.path(field))) {
                fail(Outcome.LINEAGE_INVALID, "CORPUS_REVISION_BINDING_INVALID");
            }
        }
        if (!command.path("expectedPredecessorRef").equals(
                revision.path("predecessorRef"))) {
            fail(Outcome.LINEAGE_INVALID, "CORPUS_REVISION_PREDECESSOR_INVALID");
        }
        requireLineage(
                revision.path("corpusId").asText(),
                revision.path("revision").asLong(),
                revision.path("predecessorRef"),
                "CAPABILITY_CORPUS_REVISION",
                "CORPUS_REVISION_LINEAGE_INVALID");
        JsonNode coordinates = command.path("sources");
        JsonNode sources = revision.path("sources");
        if (coordinates.size() != sources.size()) {
            fail(Outcome.LINEAGE_INVALID, "CORPUS_SOURCE_BINDING_INVALID");
        }
        String previous = "";
        Instant earliestHorizon = null;
        for (int index = 0; index < sources.size(); index++) {
            JsonNode source = sources.path(index);
            JsonNode coordinate = coordinates.path(index);
            if (!coordinate.path("observationRef").equals(
                    source.path("observationRef"))
                    || !coordinate.path("admissionRef").equals(
                    source.path("admissionRef"))) {
                fail(Outcome.LINEAGE_INVALID, "CORPUS_SOURCE_BINDING_INVALID");
            }
            requireAdmissionBinding(
                    source.path("observationRef"),
                    source.path("admissionRef"),
                    "CORPUS_SOURCE_ADMISSION_BINDING_INVALID");
            String current = source.at("/observationRef/id").asText();
            if (current.compareTo(previous) <= 0) {
                fail(Outcome.LINEAGE_INVALID, "CORPUS_SOURCE_ORDER_INVALID");
            }
            previous = current;
            Instant occurredAt = instant(source.path("occurredAt"));
            Instant usableUntil = instant(source.path("usableUntil"));
            if (!usableUntil.isAfter(occurredAt)) {
                fail(Outcome.WINDOW_REJECTED, "CORPUS_SOURCE_WINDOW_INVALID");
            }
            if (earliestHorizon == null
                    || usableUntil.isBefore(earliestHorizon)) {
                earliestHorizon = usableUntil;
            }
        }
        Instant createdAt = instant(revision.path("createdAt"));
        Instant usableUntil = instant(revision.path("usableUntil"));
        if (earliestHorizon == null
                || !earliestHorizon.equals(usableUntil)
                || !usableUntil.isAfter(createdAt)
                || createdAt.isAfter(fixture.verificationTime())) {
            fail(Outcome.WINDOW_REJECTED, "CORPUS_REVISION_WINDOW_INVALID");
        }
        verifyRisk(sources, revision.path("riskSummary"));
    }

    private static void verifyRisk(JsonNode sources, JsonNode risk) {
        Map<String, Integer> requestMultiplicity = new HashMap<>();
        Set<String> producerKeys = new HashSet<>();
        for (JsonNode source : sources) {
            requestMultiplicity.merge(
                    source.at("/requestPayloadRef/fingerprint").asText(),
                    1,
                    Integer::sum);
            producerKeys.add(
                    source.at("/authorityKeyRef/fingerprint").asText());
        }
        int samples = sources.size();
        int uniqueRequests = requestMultiplicity.size();
        int duplicates = samples - uniqueRequests;
        int maximumMultiplicity = requestMultiplicity.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        int duplicateBasisPoints =
                (int) (((long) duplicates * 10_000L) / samples);
        if (risk.path("sampleCount").asInt() != samples
                || risk.path("uniqueRequestCount").asInt() != uniqueRequests
                || risk.path("duplicateRequestCount").asInt() != duplicates
                || risk.path("maximumRequestMultiplicity").asInt()
                != maximumMultiplicity
                || risk.path("producerKeyCount").asInt()
                != producerKeys.size()
                || risk.path("duplicateBasisPoints").asInt()
                != duplicateBasisPoints) {
            fail(Outcome.RISK_INVALID, "CORPUS_RISK_STATISTICS_INVALID");
        }
        boolean eligible = "ELIGIBLE".equals(
                risk.path("eligibility").asText());
        if (eligible != risk.path("reasons").isEmpty()) {
            fail(Outcome.RISK_INVALID, "CORPUS_RISK_ELIGIBILITY_INVALID");
        }
    }

    private static void verifyPublication(
            CapabilityCorpusCompatibilityFixture fixture) {
        JsonNode command = fixture.publishRequest();
        JsonNode publication = fixture.publication();
        JsonNode revision = fixture.revision();
        requireFingerprint(
                command,
                publication.path("sourceCommandFingerprint").asText(),
                "CORPUS_PUBLISH_COMMAND_FINGERPRINT_INVALID");
        requireArtifactFingerprint(
                publication,
                "publicationFingerprint",
                "CORPUS_PUBLICATION_FINGERPRINT_INVALID");
        if (!command.path("corpusId").equals(publication.path("corpusId"))
                || !command.path("publicationRevision").equals(
                publication.path("revision"))
                || !command.path("expectedPublicationRef").equals(
                publication.path("predecessorRef"))
                || !command.path("corpusRevisionRef").equals(
                publication.path("corpusRevisionRef"))
                || !command.path("reviewTicketRef").equals(
                publication.path("reviewTicketRef"))
                || !command.path("reasonCode").equals(
                publication.path("reasonCode"))) {
            fail(Outcome.LINEAGE_INVALID, "CORPUS_PUBLICATION_BINDING_INVALID");
        }
        requireLineage(
                publication.path("corpusId").asText(),
                publication.path("revision").asLong(),
                publication.path("predecessorRef"),
                "CAPABILITY_CORPUS_PUBLICATION",
                "CORPUS_PUBLICATION_LINEAGE_INVALID");
        if (!artifactRef(
                revision,
                "CAPABILITY_CORPUS_REVISION",
                "revisionFingerprint").equals(
                publication.path("corpusRevisionRef"))) {
            fail(Outcome.LINEAGE_INVALID, "CORPUS_PUBLICATION_REVISION_INVALID");
        }
        Instant createdAt = instant(revision.path("createdAt"));
        Instant publishedAt = instant(publication.path("publishedAt"));
        Instant usableUntil = instant(publication.path("usableUntil"));
        if (publishedAt.isBefore(createdAt)
                || !usableUntil.equals(instant(revision.path("usableUntil")))
                || !usableUntil.isAfter(publishedAt)
                || !usableUntil.isAfter(fixture.verificationTime())
                || publishedAt.isAfter(fixture.verificationTime())) {
            fail(
                    Outcome.WINDOW_REJECTED,
                    "CORPUS_PUBLICATION_WINDOW_INVALID");
        }
    }

    private static void requireFingerprint(
            JsonNode value, String expected, String reason) {
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
            JsonNode value, String fingerprintField, String reason) {
        ObjectNode blank = (ObjectNode) value.deepCopy();
        String expected = blank.path(fingerprintField).asText();
        blank.put(fingerprintField, ZERO_FINGERPRINT);
        requireFingerprint(blank, expected, reason);
    }

    private static void requireAdmissionBinding(
            JsonNode observationRef,
            JsonNode admissionRef,
            String reason) {
        if (!admissionRef.path("id").asText().equals(
                observationRef.path("id").asText() + ":admission")) {
            fail(Outcome.LINEAGE_INVALID, reason);
        }
    }

    private static void requireLineage(
            String id,
            long revision,
            JsonNode predecessor,
            String predecessorKind,
            String reason) {
        if (revision == 1) {
            if (!predecessor.isNull()) {
                fail(Outcome.LINEAGE_INVALID, reason);
            }
            return;
        }
        if (predecessor.isNull()
                || !predecessorKind.equals(predecessor.path("kind").asText())
                || !id.equals(predecessor.path("id").asText())
                || predecessor.path("revision").asLong() != revision - 1) {
            fail(Outcome.LINEAGE_INVALID, reason);
        }
    }

    private static ObjectNode artifactRef(
            JsonNode value, String kind, String fingerprintField) {
        ObjectNode ref = JsonNodeFactory.instance.objectNode();
        ref.put("kind", kind);
        ref.put("id", value.path("corpusId").asText());
        ref.set("revision", value.path("revision").deepCopy());
        ref.put("fingerprint", value.path(fingerprintField).asText());
        return ref;
    }

    private static boolean scopeMatches(
            JsonNode value, CapabilityObservationScope expected) {
        return expected.tenantId().equals(value.path("tenantId").asText())
                && expected.organizationId().equals(
                value.path("organizationId").asText())
                && expected.projectId().equals(
                value.path("projectId").asText())
                && expected.environmentId().equals(
                value.path("environmentId").asText())
                && expected.region().equals(value.path("region").asText());
    }

    private static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException invalid) {
            fail(Outcome.WINDOW_REJECTED, "CORPUS_TIME_INVALID");
            throw new IllegalStateException("unreachable");
        }
    }

    private static VerificationResult result(
            Outcome outcome, String reasonCode, Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reasonCode,
                coordinates.corpusId(),
                coordinates.revisionFingerprint(),
                coordinates.publicationFingerprint());
    }

    private static void fail(Outcome outcome, String reasonCode) {
        throw new VerificationFailure(outcome, reasonCode);
    }

    /** Corpus compatibility verification outcome. */
    public enum Outcome {
        /** Every locally verifiable wire and integrity invariant passed. */
        VERIFIED,
        /** One of the six closed JSON schemas rejected its value. */
        SCHEMA_INVALID,
        /** A command or immutable artifact fingerprint failed verification. */
        INTEGRITY_INVALID,
        /** An artifact does not match the complete expected enterprise scope. */
        SCOPE_MISMATCH,
        /** A command, source, candidate, or publication lineage is inconsistent. */
        LINEAGE_INVALID,
        /** Policy-independent risk statistics are inconsistent with sources. */
        RISK_INVALID,
        /** A review, source, candidate, or publication time window is invalid. */
        WINDOW_REJECTED
    }

    /**
     * Payload-free local verification result.
     *
     * @param outcome closed verifier outcome
     * @param reasonCode stable low-cardinality reason
     * @param corpusId untrusted corpus coordinate, blank when unavailable
     * @param revisionFingerprint untrusted candidate fingerprint, blank when unavailable
     * @param publicationFingerprint untrusted publication fingerprint, blank when unavailable
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String corpusId,
            String revisionFingerprint,
            String publicationFingerprint
    ) {
        /**
         * Reports whether all locally verifiable invariants passed.
         *
         * @return true only for {@link Outcome#VERIFIED}
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    private record Coordinates(
            String corpusId,
            String revisionFingerprint,
            String publicationFingerprint
    ) {
        private static Coordinates from(
                CapabilityCorpusCompatibilityFixture fixture) {
            if (fixture == null) {
                return new Coordinates("", "", "");
            }
            return new Coordinates(
                    fixture.revision().path("corpusId").asText(),
                    fixture.revision().path(
                            "revisionFingerprint").asText(),
                    fixture.publication().path(
                            "publicationFingerprint").asText());
        }
    }

    private static final class VerificationFailure extends RuntimeException {
        private final Outcome outcome;
        private final String reasonCode;

        private VerificationFailure(Outcome outcome, String reasonCode) {
            super(reasonCode, null, false, false);
            this.outcome = outcome;
            this.reasonCode = reasonCode;
        }
    }
}
