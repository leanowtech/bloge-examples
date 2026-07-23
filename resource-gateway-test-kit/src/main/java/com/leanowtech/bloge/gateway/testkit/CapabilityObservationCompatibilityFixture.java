package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * Fixed public-only compatibility input for capability-observation consumers.
 *
 * @param observation strict signed observation JSON
 * @param verificationKey immutable public producer key
 * @param expectedScope local full-scope expectation
 * @param verificationTime deterministic admission-time probe
 */
public record CapabilityObservationCompatibilityFixture(
        JsonNode observation,
        CapabilityObservationVerificationKey verificationKey,
        CapabilityObservationScope expectedScope,
        Instant verificationTime
) {
    /** Validates detached fixture components. */
    public CapabilityObservationCompatibilityFixture {
        observation = Objects.requireNonNull(observation, "observation").deepCopy();
        verificationKey = Objects.requireNonNull(
                verificationKey, "verificationKey");
        expectedScope = Objects.requireNonNull(expectedScope, "expectedScope");
        verificationTime = Objects.requireNonNull(
                verificationTime, "verificationTime");
    }

    /**
     * Returns a copy whose mutable JSON cannot alter the packaged singleton.
     *
     * @return detached fixture copy
     */
    public CapabilityObservationCompatibilityFixture detachedCopy() {
        return new CapabilityObservationCompatibilityFixture(
                observation.deepCopy(),
                verificationKey,
                expectedScope,
                verificationTime);
    }

    /**
     * Decodes the strict fixture envelope.
     *
     * @param value fixture JSON
     * @return typed public-only compatibility fixture
     */
    public static CapabilityObservationCompatibilityFixture from(JsonNode value) {
        try {
            return new CapabilityObservationCompatibilityFixture(
                    value.path("observation"),
                    CapabilityObservationVerificationKey.from(
                            value.path("verificationKey")),
                    CapabilityObservationScope.from(value.path("expectedScope")),
                    Instant.parse(value.path("verificationTime").asText()));
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(
                    "capability observation compatibility fixture is malformed",
                    malformed);
        }
    }
}
