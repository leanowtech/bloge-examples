package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Fixed server-produced public-only authoritative outcome compatibility fixture.
 *
 * <p>The fixture carries one signed payload-free observation, its Resource Gateway public key,
 * and a frozen verification time. It deliberately does not carry a private key or claim to prove
 * a live customer business authority. Its bounded authority callback validates only producer and
 * standalone-consumer wire compatibility.</p>
 *
 * @param observation exact server-produced signed observation
 * @param verificationKey public Resource Gateway verification key
 * @param verificationTime frozen consumer verification time
 */
public record AuthoritativeOutcomeObservationCompatibilityFixture(
        JsonNode observation,
        EvidenceVerificationKey verificationKey,
        Instant verificationTime
) {
    /** Fixed authoritative outcome compatibility fixture envelope version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeObservationCompatibility.v1";

    /** Defensively copies and validates all public fixture inputs. */
    public AuthoritativeOutcomeObservationCompatibilityFixture {
        if (observation == null
                || !observation.isObject()) {
            throw new IllegalArgumentException(
                    "Outcome compatibility observation must be an object");
        }
        observation = observation.deepCopy();
        verificationKey = Objects.requireNonNull(
                verificationKey, "verificationKey");
        verificationTime = Objects.requireNonNull(
                verificationTime, "verificationTime");
    }

    /**
     * Parses one exact public-only fixture envelope.
     *
     * @param value untrusted fixture JSON
     * @return defensively copied typed fixture
     */
    public static
    AuthoritativeOutcomeObservationCompatibilityFixture from(
            JsonNode value) {
        requireFields(
                value,
                Set.of(
                        "schemaVersion",
                        "verificationTime",
                        "verificationKey",
                        "observation"),
                "fixture");
        if (!SCHEMA_VERSION.equals(
                value.path("schemaVersion").asText())) {
            throw new IllegalArgumentException(
                    "Outcome compatibility fixture schemaVersion is invalid");
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
        return new AuthoritativeOutcomeObservationCompatibilityFixture(
                value.path("observation"),
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
     * Runs the standalone verifier over the fixed producer output.
     *
     * <p>The fixture authority callback is intentionally a bounded compatibility stub. Production
     * consumers must replace it with their customer-governed authority closure.</p>
     *
     * @return bounded payload-free verification result
     */
    public AuthoritativeOutcomeObservationVerifier
    .VerificationResult verify() {
        return new AuthoritativeOutcomeObservationVerifier()
                .verify(
                        observation,
                        verificationKey,
                        ignored -> true,
                        verificationTime);
    }

    /**
     * Returns the exact signed observation without exposing mutable fixture state.
     *
     * @return defensive copy of the exact signed observation
     */
    @Override
    public JsonNode observation() {
        return observation.deepCopy();
    }

    AuthoritativeOutcomeObservationCompatibilityFixture
    detachedCopy() {
        return new AuthoritativeOutcomeObservationCompatibilityFixture(
                observation,
                verificationKey,
                verificationTime);
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
        value.fieldNames().forEachRemaining(actual::add);
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
}
