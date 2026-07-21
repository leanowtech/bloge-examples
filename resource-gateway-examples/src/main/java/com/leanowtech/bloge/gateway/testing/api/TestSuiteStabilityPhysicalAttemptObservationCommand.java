package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Challenge-bound request for the current provider state of one physical attempt.
 *
 * <p>The command embeds the exact content-addressed start command so an observation cannot be
 * moved to another execution envelope, queue epoch, or runtime deployment. A caller may bind an
 * already confirmed process identity and a minimum attempt revision to reject provider state
 * rollback. An empty process binding is intentional while a start remains uncertain.</p>
 *
 * @param schemaVersion exact observation-command generation
 * @param commandId content-addressed observation-command identity
 * @param commandFingerprint canonical command-material fingerprint
 * @param startCommand exact original physical-attempt start command
 * @param expectedProcessIdentityFingerprint previously confirmed process identity or empty
 * @param minimumAttemptRevision lowest previously accepted provider attempt revision
 * @param requestedAt caller observation time
 * @param confirmationDeadlineAt latest acceptable provider observation confirmation
 * @param challenge 32-byte base64url challenge preventing attestation replay
 */
public record TestSuiteStabilityPhysicalAttemptObservationCommand(
        String schemaVersion,
        String commandId,
        String commandFingerprint,
        TestSuiteStabilityPhysicalAttemptStartCommand startCommand,
        String expectedProcessIdentityFingerprint,
        long minimumAttemptRevision,
        Instant requestedAt,
        Instant confirmationDeadlineAt,
        String challenge) {

    /** Exact physical-attempt observation-command generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityPhysicalAttemptObservationCommand.v1";
    private static final Pattern COMMAND_ID =
            Pattern.compile("stability-attempt-observe-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Duration MINIMUM_CONFIRMATION_WINDOW = Duration.ofMillis(100);
    private static final Duration MAXIMUM_CONFIRMATION_WINDOW = Duration.ofMinutes(5);

    /** Enforces a closed, payload-free, content-addressed observation envelope. */
    public TestSuiteStabilityPhysicalAttemptObservationCommand {
        schemaVersion = required(schemaVersion, "schemaVersion");
        commandId = required(commandId, "commandId");
        commandFingerprint = required(commandFingerprint, "commandFingerprint");
        startCommand = Objects.requireNonNull(startCommand, "startCommand");
        expectedProcessIdentityFingerprint = normalized(
                expectedProcessIdentityFingerprint);
        requestedAt = exactInstant(requestedAt, "requestedAt");
        confirmationDeadlineAt = exactInstant(
                confirmationDeadlineAt, "confirmationDeadlineAt");
        challenge = required(challenge, "challenge");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !COMMAND_ID.matcher(commandId).matches()
                || !FINGERPRINT.matcher(commandFingerprint).matches()
                || !commandId.equals("stability-attempt-observe-"
                + commandFingerprint.substring("sha256:".length()))
                || !expectedProcessIdentityFingerprint.isEmpty()
                && !FINGERPRINT.matcher(expectedProcessIdentityFingerprint).matches()
                || minimumAttemptRevision < 0) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability physical-attempt observation identity");
        }
        Duration window = Duration.between(requestedAt, confirmationDeadlineAt);
        if (window.compareTo(MINIMUM_CONFIRMATION_WINDOW) < 0
                || window.compareTo(MAXIMUM_CONFIRMATION_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability physical-attempt observation window");
        }
        try {
            if (Base64.getUrlDecoder().decode(challenge).length != 32
                    || challenge.contains("=")) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability physical-attempt observation challenge");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability physical-attempt observation challenge");
        }
    }

    /**
     * Creates a command whose identity commits to the start and rollback fences.
     *
     * @param objectMapper canonical protocol mapper
     * @param startCommand exact original start command
     * @param expectedProcessIdentityFingerprint known process commitment or empty
     * @param minimumAttemptRevision lowest previously accepted provider revision
     * @param requestedAt caller observation time
     * @param confirmationDeadlineAt latest acceptable provider confirmation
     * @param challenge caller-generated 32-byte base64url challenge
     * @return immutable content-addressed observation command
     */
    public static TestSuiteStabilityPhysicalAttemptObservationCommand create(
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptStartCommand startCommand,
            String expectedProcessIdentityFingerprint,
            long minimumAttemptRevision,
            Instant requestedAt,
            Instant confirmationDeadlineAt,
            String challenge) {
        Map<String, Object> material = material(
                startCommand, expectedProcessIdentityFingerprint, minimumAttemptRevision,
                requestedAt, confirmationDeadlineAt, challenge);
        String fingerprint = ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material);
        return new TestSuiteStabilityPhysicalAttemptObservationCommand(
                SCHEMA_VERSION,
                "stability-attempt-observe-"
                        + fingerprint.substring("sha256:".length()),
                fingerprint, startCommand, expectedProcessIdentityFingerprint,
                minimumAttemptRevision, requestedAt, confirmationDeadlineAt, challenge);
    }

    /**
     * Returns the exact physical identity inherited from the original start command.
     *
     * @return reserved physical-attempt identity
     */
    public TestSuiteStabilityPhysicalAttemptIdentity identity() {
        return startCommand.identity();
    }

    /**
     * Reconstructs every semantic field used to derive this command.
     *
     * @return canonical material excluding only the derived id and fingerprint
     */
    public Map<String, Object> canonicalMaterial() {
        return material(startCommand, expectedProcessIdentityFingerprint,
                minimumAttemptRevision, requestedAt, confirmationDeadlineAt, challenge);
    }

    private static Map<String, Object> material(
            TestSuiteStabilityPhysicalAttemptStartCommand startCommand,
            String expectedProcessIdentityFingerprint,
            long minimumAttemptRevision,
            Instant requestedAt,
            Instant confirmationDeadlineAt,
            String challenge) {
        TestSuiteStabilityPhysicalAttemptStartCommand start =
                Objects.requireNonNull(startCommand, "startCommand");
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("startCommand", start.canonicalMaterial());
        material.put("startCommandId", start.commandId());
        material.put("startCommandFingerprint", start.commandFingerprint());
        material.put("expectedProcessIdentityFingerprint",
                normalized(expectedProcessIdentityFingerprint));
        material.put("minimumAttemptRevision", minimumAttemptRevision);
        material.put("requestedAt", requestedAt);
        material.put("confirmationDeadlineAt", confirmationDeadlineAt);
        material.put("challenge", challenge);
        return Map.copyOf(material);
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static Instant exactInstant(Instant value, String field) {
        Instant required = Objects.requireNonNull(value, field);
        if (required.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(field + " must be millisecond exact");
        }
        return required;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
