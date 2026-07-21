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
 * Challenge-bound request to start one exact reserved physical attempt.
 *
 * <p>The command carries only content identity and an opaque execution-envelope reference. It
 * contains no fixture value, business payload, credential, process id, or provider diagnostic.
 * Retrying the exact command is safe; changing any semantic field creates a new command id.</p>
 *
 * @param schemaVersion exact start-command generation
 * @param commandId content-addressed start-command identity
 * @param commandFingerprint canonical command-material fingerprint
 * @param identity exact reserved physical-attempt identity
 * @param executionEnvelopeRef opaque content-addressed provider input reference
 * @param executionEnvelopeFingerprint immutable encrypted execution-envelope commitment
 * @param requestedAt caller observation time
 * @param confirmationDeadlineAt latest acceptable provider start confirmation
 * @param challenge 32-byte base64url challenge preventing attestation replay
 */
public record TestSuiteStabilityPhysicalAttemptStartCommand(
        String schemaVersion,
        String commandId,
        String commandFingerprint,
        TestSuiteStabilityPhysicalAttemptIdentity identity,
        String executionEnvelopeRef,
        String executionEnvelopeFingerprint,
        Instant requestedAt,
        Instant confirmationDeadlineAt,
        String challenge) {

    /** Exact physical-attempt start-command generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityPhysicalAttemptStartCommand.v1";
    private static final Pattern COMMAND_ID =
            Pattern.compile("stability-attempt-start-[a-f0-9]{64}");
    private static final Pattern ENVELOPE_REF =
            Pattern.compile("stability-envelope-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Duration MINIMUM_CONFIRMATION_WINDOW = Duration.ofMillis(100);
    private static final Duration MAXIMUM_CONFIRMATION_WINDOW = Duration.ofMinutes(5);

    /** Enforces a closed, payload-free, content-addressed command envelope. */
    public TestSuiteStabilityPhysicalAttemptStartCommand {
        schemaVersion = required(schemaVersion, "schemaVersion");
        commandId = required(commandId, "commandId");
        commandFingerprint = required(commandFingerprint, "commandFingerprint");
        identity = Objects.requireNonNull(identity, "identity");
        executionEnvelopeRef = required(executionEnvelopeRef, "executionEnvelopeRef");
        executionEnvelopeFingerprint = required(
                executionEnvelopeFingerprint, "executionEnvelopeFingerprint");
        requestedAt = exactInstant(requestedAt, "requestedAt");
        confirmationDeadlineAt = exactInstant(
                confirmationDeadlineAt, "confirmationDeadlineAt");
        challenge = required(challenge, "challenge");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !COMMAND_ID.matcher(commandId).matches()
                || !FINGERPRINT.matcher(commandFingerprint).matches()
                || !commandId.equals("stability-attempt-start-"
                + commandFingerprint.substring("sha256:".length()))
                || !ENVELOPE_REF.matcher(executionEnvelopeRef).matches()
                || !FINGERPRINT.matcher(executionEnvelopeFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability physical-attempt start identity");
        }
        Duration window = Duration.between(requestedAt, confirmationDeadlineAt);
        if (window.compareTo(MINIMUM_CONFIRMATION_WINDOW) < 0
                || window.compareTo(MAXIMUM_CONFIRMATION_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability physical-attempt start window");
        }
        try {
            if (Base64.getUrlDecoder().decode(challenge).length != 32
                    || challenge.contains("=")) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability physical-attempt start challenge");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability physical-attempt start challenge");
        }
    }

    /**
     * Creates a command whose id commits to every semantic field.
     *
     * @param objectMapper canonical protocol mapper
     * @param identity exact reserved physical-attempt identity
     * @param executionEnvelopeRef opaque content-addressed execution-envelope reference
     * @param executionEnvelopeFingerprint encrypted execution-envelope commitment
     * @param requestedAt caller observation time
     * @param confirmationDeadlineAt latest acceptable provider confirmation
     * @param challenge caller-generated 32-byte base64url challenge
     * @return immutable content-addressed start command
     */
    public static TestSuiteStabilityPhysicalAttemptStartCommand create(
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            String executionEnvelopeRef,
            String executionEnvelopeFingerprint,
            Instant requestedAt,
            Instant confirmationDeadlineAt,
            String challenge) {
        Map<String, Object> material = material(
                identity, executionEnvelopeRef, executionEnvelopeFingerprint,
                requestedAt, confirmationDeadlineAt, challenge);
        String fingerprint = ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material);
        return new TestSuiteStabilityPhysicalAttemptStartCommand(
                SCHEMA_VERSION,
                "stability-attempt-start-" + fingerprint.substring("sha256:".length()),
                fingerprint, identity, executionEnvelopeRef, executionEnvelopeFingerprint,
                requestedAt, confirmationDeadlineAt, challenge);
    }

    /**
     * Reconstructs all semantic command material.
     *
     * @return canonical material excluding only the derived command id and fingerprint
     */
    public Map<String, Object> canonicalMaterial() {
        return material(identity, executionEnvelopeRef, executionEnvelopeFingerprint,
                requestedAt, confirmationDeadlineAt, challenge);
    }

    private static Map<String, Object> material(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            String executionEnvelopeRef,
            String executionEnvelopeFingerprint,
            Instant requestedAt,
            Instant confirmationDeadlineAt,
            String challenge) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("identity", Objects.requireNonNull(identity, "identity")
                .canonicalMaterial());
        material.put("identityFingerprint", identity.identityFingerprint());
        material.put("attemptId", identity.attemptId());
        material.put("executionEnvelopeRef", executionEnvelopeRef);
        material.put("executionEnvelopeFingerprint", executionEnvelopeFingerprint);
        material.put("requestedAt", requestedAt);
        material.put("confirmationDeadlineAt", confirmationDeadlineAt);
        material.put("challenge", challenge);
        return Map.copyOf(material);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
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
}
