package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * Fixed payload-free compatibility input for corpus-governance consumers.
 *
 * <p>The fixture proves local wire and integrity compatibility only. It does not assert that
 * payload references still exist, governance policy is current, the reviewer remains authorized,
 * or the publication is the current serving head.</p>
 *
 * @param reviewRequest exact quarantine-review command
 * @param review immutable terminal review
 * @param candidateRequest exact corpus-candidate command
 * @param revision immutable candidate revision
 * @param publishRequest exact owner-reviewed publication command
 * @param publication immutable serving-publication fact
 * @param expectedScope local complete enterprise-scope expectation
 * @param verificationTime deterministic compatibility-probe time
 */
public record CapabilityCorpusCompatibilityFixture(
        JsonNode reviewRequest,
        JsonNode review,
        JsonNode candidateRequest,
        JsonNode revision,
        JsonNode publishRequest,
        JsonNode publication,
        CapabilityObservationScope expectedScope,
        Instant verificationTime
) {
    /** Validates and detaches every mutable JSON component. */
    public CapabilityCorpusCompatibilityFixture {
        reviewRequest = copy(reviewRequest, "reviewRequest");
        review = copy(review, "review");
        candidateRequest = copy(candidateRequest, "candidateRequest");
        revision = copy(revision, "revision");
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
    public CapabilityCorpusCompatibilityFixture detachedCopy() {
        return new CapabilityCorpusCompatibilityFixture(
                reviewRequest,
                review,
                candidateRequest,
                revision,
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
    public static CapabilityCorpusCompatibilityFixture from(JsonNode value) {
        try {
            return new CapabilityCorpusCompatibilityFixture(
                    value.path("reviewRequest"),
                    value.path("review"),
                    value.path("candidateRequest"),
                    value.path("revision"),
                    value.path("publishRequest"),
                    value.path("publication"),
                    CapabilityObservationScope.from(value.path("expectedScope")),
                    Instant.parse(value.path("verificationTime").asText()));
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(
                    "capability corpus compatibility fixture is malformed",
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
