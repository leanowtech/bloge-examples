package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Fixed server-produced public-only compatibility fixture for one online baseline observation.
 *
 * <p>The fixture carries an exact payload-free command, signed payload-free observation, expected
 * artifact reference, and public verification key. It contains no private key, endpoint,
 * credential, business request, or business response. Consumers can therefore prove producer and
 * standalone-consumer compatibility without starting Resource Gateway or contacting a sidecar.</p>
 *
 * @param expectedObservationRef exact expected observation artifact coordinates
 * @param command exact payload-free command sent to the regional sidecar
 * @param observation exact signed payload-free regional observation
 * @param verificationKey public regional observation authority key
 * @param verificationTime frozen compatibility verification time
 */
public record OnlineReadOnlyShadowBaselineCompatibilityFixture(
        JsonNode expectedObservationRef,
        JsonNode command,
        JsonNode observation,
        EvidenceVerificationKey verificationKey,
        Instant verificationTime
) {
    /** Fixed online baseline compatibility fixture envelope version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.onlineReadOnlyShadowBaselineCompatibility.v1";

    /** Defensively copies public protocol JSON and validates all verification inputs. */
    public OnlineReadOnlyShadowBaselineCompatibilityFixture {
        expectedObservationRef = object(
                expectedObservationRef,
                "expectedObservationRef");
        command = object(command, "command");
        observation = object(
                observation, "observation");
        verificationKey = Objects.requireNonNull(
                verificationKey,
                "verificationKey");
        verificationTime = Objects.requireNonNull(
                verificationTime,
                "verificationTime");
    }

    /**
     * Parses one strict public-only fixture envelope.
     *
     * @param value untrusted fixture JSON
     * @return defensively copied typed compatibility fixture
     */
    public static
    OnlineReadOnlyShadowBaselineCompatibilityFixture from(
            JsonNode value) {
        requireFields(
                value,
                Set.of(
                        "schemaVersion",
                        "verificationTime",
                        "verificationKey",
                        "expectedObservationRef",
                        "command",
                        "observation"),
                "fixture");
        if (!SCHEMA_VERSION.equals(
                value.path("schemaVersion").asText())) {
            throw new IllegalArgumentException(
                    "Online baseline fixture schemaVersion is invalid");
        }
        return new OnlineReadOnlyShadowBaselineCompatibilityFixture(
                value.path("expectedObservationRef"),
                value.path("command"),
                value.path("observation"),
                key(value.path("verificationKey")),
                instant(
                        value.path("verificationTime"),
                        "verificationTime"));
    }

    /**
     * Runs the standalone verifier over the complete fixed fixture.
     *
     * @return bounded payload-free verification result
     */
    public OnlineReadOnlyShadowBaselineObservationVerifier
    .VerificationResult verify() {
        return new OnlineReadOnlyShadowBaselineObservationVerifier()
                .verify(
                        observation,
                        verificationKey,
                        new OnlineReadOnlyShadowBaselineObservationVerifier
                                .VerificationContext(
                                command,
                                expectedObservationRef,
                                verificationTime));
    }

    /**
     * Returns the exact expected observation artifact coordinates.
     *
     * @return defensive copy of the expected artifact reference
     */
    @Override
    public JsonNode expectedObservationRef() {
        return expectedObservationRef.deepCopy();
    }

    /**
     * Returns the exact payload-free regional sidecar command.
     *
     * @return defensive copy of the command
     */
    @Override
    public JsonNode command() {
        return command.deepCopy();
    }

    /**
     * Returns the exact signed payload-free regional observation.
     *
     * @return defensive copy of the signed observation
     */
    @Override
    public JsonNode observation() {
        return observation.deepCopy();
    }

    OnlineReadOnlyShadowBaselineCompatibilityFixture
    detachedCopy() {
        return new OnlineReadOnlyShadowBaselineCompatibilityFixture(
                expectedObservationRef,
                command,
                observation,
                verificationKey,
                verificationTime);
    }

    private static EvidenceVerificationKey key(
            JsonNode value) {
        requireFields(
                value,
                Set.of(
                        "schemaVersion",
                        "keyId",
                        "algorithm",
                        "encodedPublicKey",
                        "createdAt",
                        "state",
                        "provider"),
                "verificationKey");
        return new EvidenceVerificationKey(
                value.path("schemaVersion").asText(),
                value.path("keyId").asText(),
                value.path("algorithm").asText(),
                value.path("encodedPublicKey").asText(),
                instant(
                        value.path("createdAt"),
                        "verificationKey.createdAt"),
                value.path("state").asText(),
                value.path("provider").asText());
    }

    private static void requireFields(
            JsonNode value,
            Set<String> expected,
            String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(
                    field + " must be an object");
        }
        HashSet<String> actual = new HashSet<>();
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
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    field + " is invalid",
                    invalid);
        }
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
}
