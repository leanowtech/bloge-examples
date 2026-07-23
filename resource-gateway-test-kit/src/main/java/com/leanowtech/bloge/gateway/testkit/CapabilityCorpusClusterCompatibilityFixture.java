package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * Fixed payload-free compatibility input for recorded-cluster consumers.
 *
 * <p>The fixture proves wire, canonicalization, lineage, membership, identity-projection, and
 * confidence compatibility only. It does not replace online cluster-policy, validation-authority,
 * source-lifecycle, grant, retention, or payload-content checks.</p>
 *
 * @param corpusRevision exact immutable corpus revision
 * @param corpusPublication exact reviewed corpus serving publication
 * @param validation exact externally produced cluster validation
 * @param publishRequest exact owner-reviewed cluster command
 * @param publication immutable cluster publication
 * @param expectedScope local complete enterprise-scope expectation
 * @param verificationTime deterministic compatibility-probe time
 */
public record CapabilityCorpusClusterCompatibilityFixture(
        JsonNode corpusRevision,
        JsonNode corpusPublication,
        JsonNode validation,
        JsonNode publishRequest,
        JsonNode publication,
        CapabilityObservationScope expectedScope,
        Instant verificationTime
) {
    /** Validates and detaches every mutable JSON component. */
    public CapabilityCorpusClusterCompatibilityFixture {
        corpusRevision = copy(corpusRevision, "corpusRevision");
        corpusPublication = copy(corpusPublication, "corpusPublication");
        validation = copy(validation, "validation");
        publishRequest = copy(publishRequest, "publishRequest");
        publication = copy(publication, "publication");
        expectedScope = Objects.requireNonNull(expectedScope, "expectedScope");
        verificationTime = Objects.requireNonNull(
                verificationTime, "verificationTime");
    }

    /**
     * Returns a copy whose mutable JSON cannot alter the packaged singleton.
     *
     * @return fully detached compatibility fixture
     */
    public CapabilityCorpusClusterCompatibilityFixture detachedCopy() {
        return new CapabilityCorpusClusterCompatibilityFixture(
                corpusRevision,
                corpusPublication,
                validation,
                publishRequest,
                publication,
                expectedScope,
                verificationTime);
    }

    /**
     * Decodes the closed fixture envelope.
     *
     * @param value fixture JSON
     * @return detached compatibility fixture
     */
    public static CapabilityCorpusClusterCompatibilityFixture from(
            JsonNode value) {
        try {
            return new CapabilityCorpusClusterCompatibilityFixture(
                    value.path("corpusRevision"),
                    value.path("corpusPublication"),
                    value.path("validation"),
                    value.path("publishRequest"),
                    value.path("publication"),
                    CapabilityObservationScope.from(
                            value.path("expectedScope")),
                    Instant.parse(value.path("verificationTime").asText()));
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(
                    "capability corpus cluster fixture is malformed",
                    malformed);
        }
    }

    private static JsonNode copy(JsonNode value, String field) {
        JsonNode exact = Objects.requireNonNull(value, field);
        if (!exact.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return exact.deepCopy();
    }
}
