package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fixed server-produced public-only selected-population compatibility fixture.
 *
 * <p>The fixture exercises the complete immutable denominator, signed outcome observations,
 * independently authorized legal dispositions, a signed completeness assessment, and its full
 * historical source pagination. It contains one Resource Gateway public key and no private key,
 * credential, endpoint, or business payload. The built-in authority callbacks are intentionally
 * bounded to producer/consumer compatibility and must never replace customer-governed selection,
 * outcome, deletion, or source authorities.</p>
 *
 * @param populationBundle exact signed population root and ordered member chunks
 * @param observations exact signed payload-free outcome observations
 * @param dispositions exact signed payload-free legal dispositions
 * @param assessment exact signed denominator-preserving completeness assessment
 * @param sourcePages complete ordered historical assessment-source pages
 * @param verificationKey public Resource Gateway verification key
 * @param verificationTime frozen consumer verification time
 */
public record AuthoritativeOutcomeSelectedPopulationCompatibilityFixture(
        JsonNode populationBundle,
        List<JsonNode> observations,
        List<JsonNode> dispositions,
        JsonNode assessment,
        List<JsonNode> sourcePages,
        EvidenceVerificationKey verificationKey,
        Instant verificationTime
) {
    /** Fixed selected-population compatibility fixture envelope version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationCompatibility.v1";

    /** Defensively copies and validates all public fixture inputs. */
    public AuthoritativeOutcomeSelectedPopulationCompatibilityFixture {
        populationBundle = object(
                populationBundle, "populationBundle");
        observations = objects(
                observations, "observations", true);
        dispositions = objects(
                dispositions, "dispositions", true);
        assessment = object(assessment, "assessment");
        sourcePages = objects(
                sourcePages, "sourcePages", true);
        verificationKey = Objects.requireNonNull(
                verificationKey, "verificationKey");
        verificationTime = Objects.requireNonNull(
                verificationTime, "verificationTime");
    }

    /**
     * Parses one strict public-only fixture envelope.
     *
     * @param value untrusted fixture JSON
     * @return defensively copied typed fixture
     */
    public static
    AuthoritativeOutcomeSelectedPopulationCompatibilityFixture from(
            JsonNode value) {
        requireFields(
                value,
                Set.of(
                        "schemaVersion",
                        "verificationTime",
                        "verificationKey",
                        "populationBundle",
                        "observations",
                        "dispositions",
                        "assessment",
                        "sourcePages"),
                "fixture");
        if (!SCHEMA_VERSION.equals(
                value.path("schemaVersion").asText())) {
            throw new IllegalArgumentException(
                    "Selected-population compatibility fixture schemaVersion is invalid");
        }
        JsonNode key = value.path("verificationKey");
        requireFields(
                key,
                Set.of(
                        "schemaVersion",
                        "keyId",
                        "algorithm",
                        "encodedPublicKey",
                        "createdAt",
                        "state",
                        "provider"),
                "verificationKey");
        return new
                AuthoritativeOutcomeSelectedPopulationCompatibilityFixture(
                value.path("populationBundle"),
                nodes(value.path("observations"), "observations"),
                nodes(value.path("dispositions"), "dispositions"),
                value.path("assessment"),
                nodes(value.path("sourcePages"), "sourcePages"),
                new EvidenceVerificationKey(
                        key.path("schemaVersion").asText(),
                        key.path("keyId").asText(),
                        key.path("algorithm").asText(),
                        key.path("encodedPublicKey").asText(),
                        instant(
                                key.path("createdAt"),
                                "verificationKey.createdAt"),
                        key.path("state").asText(),
                        key.path("provider").asText()),
                instant(
                        value.path("verificationTime"),
                        "verificationTime"));
    }

    /**
     * Independently verifies every signed artifact and the complete historical source closure.
     *
     * <p>The returned result proves fixed producer/consumer wire and cryptographic compatibility.
     * It does not prove that any live customer authority currently accepts the fixture.</p>
     *
     * @return bounded payload-free compatibility result
     */
    public VerificationResult verify() {
        AuthoritativeOutcomeSelectedPopulationVerifier verifier =
                new AuthoritativeOutcomeSelectedPopulationVerifier();
        AuthoritativeOutcomeSelectedPopulationVerifier
                .VerificationKeyResolver keys =
                keyId -> verificationKey.keyId().equals(keyId)
                        ? verificationKey : null;
        AuthoritativeOutcomeSelectedPopulationVerifier
                .VerificationResult population =
                verifier.verifyPopulation(
                        populationBundle,
                        keys,
                        (ignoredManifest, ignoredChunks) -> true,
                        verificationTime);
        if (!population.verified()) {
            return failure(
                    "POPULATION_" + population.reasonCode());
        }

        Map<String, JsonNode> sources = new HashMap<>();
        AuthoritativeOutcomeObservationVerifier observationVerifier =
                new AuthoritativeOutcomeObservationVerifier();
        for (JsonNode observation : observations) {
            AuthoritativeOutcomeObservationVerifier
                    .VerificationResult result =
                    observationVerifier.verify(
                            observation,
                            verificationKey,
                            ignored -> true,
                            verificationTime);
            if (!result.verified()) {
                return failure(
                        "OBSERVATION_" + result.reasonCode());
            }
            if (sources.put(
                    sourceKey(
                            "AUTHORITATIVE_OUTCOME_OBSERVATION",
                            observation,
                            "observationId",
                            "observationFingerprint"),
                    observation) != null) {
                return failure(
                        "DUPLICATE_SOURCE_REFERENCE");
            }
        }
        for (JsonNode disposition : dispositions) {
            AuthoritativeOutcomeSelectedPopulationVerifier
                    .VerificationResult result =
                    verifier.verifyDisposition(
                            disposition,
                            keys,
                            ignored -> true,
                            verificationTime);
            if (!result.verified()) {
                return failure(
                        "DISPOSITION_" + result.reasonCode());
            }
            if (sources.put(
                    sourceKey(
                            "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_DISPOSITION",
                            disposition,
                            "dispositionId",
                            "dispositionFingerprint"),
                    disposition) != null) {
                return failure(
                        "DUPLICATE_SOURCE_REFERENCE");
            }
        }

        AuthoritativeOutcomeSelectedPopulationVerifier
                .VerificationResult completeness =
                verifier.verifyAssessment(
                        assessment,
                        sourcePages,
                        populationBundle,
                        keys,
                        (ignoredManifest, ignoredChunks) -> true,
                        (kind, reference, member) ->
                                matchesSource(
                                        kind,
                                        reference,
                                        member,
                                        sources),
                        verificationTime);
        if (!completeness.verified()) {
            return failure(
                    "ASSESSMENT_" + completeness.reasonCode());
        }
        return new VerificationResult(
                true,
                "VERIFIED",
                population.populationId(),
                population.artifactFingerprint(),
                completeness.artifactId(),
                completeness.artifactFingerprint(),
                observations.size(),
                dispositions.size(),
                sourcePages.size());
    }

    /**
     * Returns the exact population bundle without exposing mutable fixture state.
     *
     * @return defensive population-bundle copy
     */
    @Override
    public JsonNode populationBundle() {
        return populationBundle.deepCopy();
    }

    /**
     * Returns every signed outcome source without exposing mutable fixture state.
     *
     * @return defensive copies of all exact outcome observations
     */
    @Override
    public List<JsonNode> observations() {
        return defensive(observations);
    }

    /**
     * Returns every signed legal source without exposing mutable fixture state.
     *
     * @return defensive copies of all exact legal dispositions
     */
    @Override
    public List<JsonNode> dispositions() {
        return defensive(dispositions);
    }

    /**
     * Returns the signed assessment without exposing mutable fixture state.
     *
     * @return defensive copy of the exact completeness assessment
     */
    @Override
    public JsonNode assessment() {
        return assessment.deepCopy();
    }

    /**
     * Returns the complete historical source closure without exposing mutable fixture state.
     *
     * @return defensive copies of all ordered source pages
     */
    @Override
    public List<JsonNode> sourcePages() {
        return defensive(sourcePages);
    }

    AuthoritativeOutcomeSelectedPopulationCompatibilityFixture
    detachedCopy() {
        return new
                AuthoritativeOutcomeSelectedPopulationCompatibilityFixture(
                populationBundle,
                observations,
                dispositions,
                assessment,
                sourcePages,
                verificationKey,
                verificationTime);
    }

    /**
     * Bounded fixture result safe for dependency-upgrade and startup probes.
     *
     * @param verified whether every fixed compatibility step passed
     * @param reasonCode stable machine-readable result
     * @param populationId selected-population identity when verified
     * @param populationFingerprint exact population address when verified
     * @param assessmentId completeness-assessment identity when verified
     * @param assessmentFingerprint exact assessment address when verified
     * @param observationCount verified observation source count
     * @param dispositionCount verified legal-disposition source count
     * @param sourcePageCount verified historical source-page count
     */
    public record VerificationResult(
            boolean verified,
            String reasonCode,
            String populationId,
            String populationFingerprint,
            String assessmentId,
            String assessmentFingerprint,
            int observationCount,
            int dispositionCount,
            int sourcePageCount
    ) {
        /** Normalizes one payload-free compatibility result. */
        public VerificationResult {
            reasonCode = bounded(reasonCode, 255);
            populationId = bounded(populationId, 512);
            populationFingerprint = bounded(
                    populationFingerprint, 128);
            assessmentId = bounded(assessmentId, 512);
            assessmentFingerprint = bounded(
                    assessmentFingerprint, 128);
            observationCount = Math.max(
                    0, observationCount);
            dispositionCount = Math.max(
                    0, dispositionCount);
            sourcePageCount = Math.max(
                    0, sourcePageCount);
            if (!reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "Selected-population compatibility result is invalid");
            }
        }
    }

    private VerificationResult failure(
            String reasonCode) {
        return new VerificationResult(
                false,
                reasonCode,
                "",
                "",
                "",
                "",
                observations.size(),
                dispositions.size(),
                sourcePages.size());
    }

    private static boolean matchesSource(
            AuthoritativeOutcomeSelectedPopulationVerifier
                    .SourceKind kind,
            JsonNode reference,
            JsonNode member,
            Map<String, JsonNode> sources) {
        JsonNode source = sources.get(referenceKey(reference));
        if (source == null) {
            return false;
        }
        return kind
                == AuthoritativeOutcomeSelectedPopulationVerifier
                .SourceKind.OBSERVATION
                ? matchesObservation(reference, member, source)
                : matchesDisposition(reference, member, source);
    }

    private static boolean matchesObservation(
            JsonNode reference,
            JsonNode member,
            JsonNode observation) {
        JsonNode selection =
                observation.path("selectionProof");
        return referenceMatches(
                reference,
                "AUTHORITATIVE_OUTCOME_OBSERVATION",
                observation,
                "observationId",
                "observationFingerprint")
                && text(observation, "unitId").equals(
                text(member, "unitId"))
                && text(selection, "stratumId").equals(
                text(member, "stratumId"))
                && selection.path("sampleOrdinal").asLong(-1)
                == member.path("sampleOrdinal").asLong(-2)
                && text(selection, "inclusionFingerprint").equals(
                text(member, "inclusionFingerprint"))
                && text(observation, "subjectFingerprint").equals(
                text(member, "subjectFingerprint"))
                && text(observation, "attributionKeyFingerprint").equals(
                text(member, "attributionKeyFingerprint"));
    }

    private static boolean matchesDisposition(
            JsonNode reference,
            JsonNode member,
            JsonNode disposition) {
        return referenceMatches(
                reference,
                "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_DISPOSITION",
                disposition,
                "dispositionId",
                "dispositionFingerprint")
                && text(disposition, "unitId").equals(
                text(member, "unitId"))
                && text(disposition, "stratumId").equals(
                text(member, "stratumId"))
                && disposition.path("sampleOrdinal").asLong(-1)
                == member.path("sampleOrdinal").asLong(-2)
                && text(disposition, "inclusionFingerprint").equals(
                text(member, "inclusionFingerprint"))
                && text(disposition, "subjectFingerprint").equals(
                text(member, "subjectFingerprint"))
                && text(disposition, "attributionKeyFingerprint").equals(
                text(member, "attributionKeyFingerprint"));
    }

    private static boolean referenceMatches(
            JsonNode reference,
            String kind,
            JsonNode source,
            String idField,
            String fingerprintField) {
        return kind.equals(text(reference, "kind"))
                && text(source, idField).equals(
                text(reference, "id"))
                && source.path("revision").asLong(-1)
                == reference.path("revision").asLong(-2)
                && text(source, fingerprintField).equals(
                text(reference, "fingerprint"));
    }

    private static String sourceKey(
            String kind,
            JsonNode source,
            String idField,
            String fingerprintField) {
        return kind + "\u0000"
                + text(source, idField) + "\u0000"
                + source.path("revision").asLong(-1)
                + "\u0000"
                + text(source, fingerprintField);
    }

    private static String referenceKey(
            JsonNode reference) {
        return text(reference, "kind") + "\u0000"
                + text(reference, "id") + "\u0000"
                + reference.path("revision").asLong(-1)
                + "\u0000"
                + text(reference, "fingerprint");
    }

    private static String text(
            JsonNode value,
            String field) {
        JsonNode exact = value == null
                ? null : value.get(field);
        if (exact == null
                || !exact.isTextual()
                || exact.asText().isBlank()) {
            return "";
        }
        return exact.asText();
    }

    private static JsonNode object(
            JsonNode value,
            String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(
                    field + " must be an object");
        }
        return value.deepCopy();
    }

    private static List<JsonNode> nodes(
            JsonNode value,
            String field) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(
                    field + " must be an array");
        }
        return java.util.stream.StreamSupport
                .stream(value.spliterator(), false)
                .<JsonNode>map(node -> node.deepCopy())
                .toList();
    }

    private static List<JsonNode> objects(
            List<JsonNode> values,
            String field,
            boolean required) {
        List<JsonNode> exact = values == null
                ? List.of() : values.stream()
                .map(value -> object(value, field + "[]"))
                .toList();
        if (required && exact.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must not be empty");
        }
        return exact;
    }

    private static List<JsonNode> defensive(
            List<JsonNode> values) {
        return values.stream()
                .<JsonNode>map(value -> value.deepCopy())
                .toList();
    }

    private static void requireFields(
            JsonNode value,
            Set<String> expected,
            String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(
                    field + " must be an object");
        }
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(
                actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    field + " fields are invalid");
        }
    }

    private static Instant instant(
            JsonNode value,
            String field) {
        try {
            Instant exact = Instant.parse(
                    value.asText());
            if (Instant.EPOCH.equals(exact)
                    || !exact.toString().equals(
                    value.asText())) {
                throw new IllegalArgumentException(
                        field + " is invalid");
            }
            return exact;
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException(
                    field + " is invalid", invalid);
        }
    }

    private static String bounded(
            String value,
            int maximum) {
        String exact = value == null
                ? "" : value.trim();
        return exact.length() <= maximum
                ? exact : exact.substring(0, maximum);
    }
}
