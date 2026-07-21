package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed request to stop one exact isolated suite-stability attempt.
 *
 * <p>The command binds the durable queue fence, runtime binding, stop reason, confirmation
 * deadline, and a caller challenge. Retrying the same command is safe; changing any field creates
 * a different fingerprint and command id. It contains neither business payload nor process id.</p>
 *
 * @param schemaVersion exact command protocol generation
 * @param commandId content-addressed cancellation command identity
 * @param commandFingerprint canonical command-material fingerprint
 * @param tenantId authenticated tenant scope
 * @param environmentId isolated {@code test} or {@code staging} runtime
 * @param jobId durable suite-stability parent job
 * @param attemptId provider-independent isolated attempt identity
 * @param ownerId worker owner bound to the durable lease
 * @param leaseEpoch monotonic durable queue epoch
 * @param runtimeBindingFingerprint immutable executable runtime binding
 * @param reason closed stop reason
 * @param requestedAt caller observation time
 * @param confirmationDeadlineAt latest acceptable provider confirmation time
 * @param challenge 32-byte base64url challenge preventing receipt replay across commands
 */
public record TestSuiteStabilityAttemptCancellationCommand(
        String schemaVersion,
        String commandId,
        String commandFingerprint,
        String tenantId,
        String environmentId,
        String jobId,
        String attemptId,
        String ownerId,
        long leaseEpoch,
        String runtimeBindingFingerprint,
        Reason reason,
        Instant requestedAt,
        Instant confirmationDeadlineAt,
        String challenge) {

    /** Exact machine protocol generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityAttemptCancellationCommand.v1";
    private static final Pattern COMMAND_ID =
            Pattern.compile("stability-attempt-cancel-[a-f0-9]{64}");
    private static final Pattern JOB_ID = Pattern.compile("stability-job-[a-f0-9]{64}");
    private static final Pattern ATTEMPT_ID =
            Pattern.compile("stability-attempt-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");
    private static final Duration MINIMUM_CONFIRMATION_WINDOW = Duration.ofMillis(100);
    private static final Duration MAXIMUM_CONFIRMATION_WINDOW = Duration.ofMinutes(5);

    /** Supported reasons for asking an isolated provider to stop an attempt. */
    public enum Reason {
        /** An authenticated caller cancelled the parent job. */
        CANCELLED,
        /** The database-authoritative parent deadline elapsed. */
        DEADLINE_EXCEEDED,
        /** The worker lost its durable queue ownership fence. */
        LEASE_LOST,
        /** The owning worker is draining or shutting down. */
        WORKER_SHUTDOWN
    }

    /** Enforces a closed, payload-free, content-addressed command. */
    public TestSuiteStabilityAttemptCancellationCommand {
        schemaVersion = required(schemaVersion, "schemaVersion");
        commandId = required(commandId, "commandId");
        commandFingerprint = required(commandFingerprint, "commandFingerprint");
        tenantId = requiredIdentifier(tenantId, "tenantId");
        environmentId = required(environmentId, "environmentId");
        jobId = required(jobId, "jobId");
        attemptId = required(attemptId, "attemptId");
        ownerId = requiredIdentifier(ownerId, "ownerId");
        runtimeBindingFingerprint = required(
                runtimeBindingFingerprint, "runtimeBindingFingerprint");
        reason = Objects.requireNonNull(reason, "reason");
        requestedAt = exactInstant(requestedAt, "requestedAt");
        confirmationDeadlineAt = exactInstant(
                confirmationDeadlineAt, "confirmationDeadlineAt");
        challenge = required(challenge, "challenge");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !COMMAND_ID.matcher(commandId).matches()
                || !FINGERPRINT.matcher(commandFingerprint).matches()
                || !commandId.equals("stability-attempt-cancel-"
                + commandFingerprint.substring("sha256:".length()))
                || !Set.of("test", "staging").contains(environmentId)
                || !JOB_ID.matcher(jobId).matches()
                || !ATTEMPT_ID.matcher(attemptId).matches()
                || leaseEpoch < 1
                || !FINGERPRINT.matcher(runtimeBindingFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability attempt cancellation identity");
        }
        Duration confirmationWindow = Duration.between(requestedAt, confirmationDeadlineAt);
        if (confirmationWindow.compareTo(MINIMUM_CONFIRMATION_WINDOW) < 0
                || confirmationWindow.compareTo(MAXIMUM_CONFIRMATION_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability attempt cancellation confirmation window");
        }
        try {
            if (Base64.getUrlDecoder().decode(challenge).length != 32
                    || challenge.contains("=")) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability attempt cancellation challenge");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability attempt cancellation challenge");
        }
    }

    /**
     * Creates a command whose id and fingerprint are derived from every semantic field.
     *
     * @param objectMapper canonical protocol mapper
     * @param tenantId authenticated tenant scope
     * @param environmentId isolated {@code test} or {@code staging} environment
     * @param jobId durable parent stability job
     * @param attemptId exact isolated execution attempt
     * @param ownerId worker that owns the durable lease
     * @param leaseEpoch monotonic durable ownership epoch
     * @param runtimeBindingFingerprint immutable executable runtime binding
     * @param reason closed cancellation reason
     * @param requestedAt caller request time
     * @param confirmationDeadlineAt latest acceptable provider confirmation time
     * @param challenge caller-generated 32-byte base64url challenge
     * @return immutable content-addressed command
     */
    public static TestSuiteStabilityAttemptCancellationCommand create(
            ObjectMapper objectMapper,
            String tenantId,
            String environmentId,
            String jobId,
            String attemptId,
            String ownerId,
            long leaseEpoch,
            String runtimeBindingFingerprint,
            Reason reason,
            Instant requestedAt,
            Instant confirmationDeadlineAt,
            String challenge) {
        Map<String, Object> material = material(
                tenantId, environmentId, jobId, attemptId, ownerId, leaseEpoch,
                runtimeBindingFingerprint, reason, requestedAt, confirmationDeadlineAt,
                challenge);
        String fingerprint = ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material);
        return new TestSuiteStabilityAttemptCancellationCommand(
                SCHEMA_VERSION,
                "stability-attempt-cancel-" + fingerprint.substring("sha256:".length()),
                fingerprint, tenantId, environmentId, jobId, attemptId, ownerId, leaseEpoch,
                runtimeBindingFingerprint, reason, requestedAt, confirmationDeadlineAt,
                challenge);
    }

    /**
     * Reconstructs the exact semantic material used to derive command identity.
     *
     * @return canonical command material excluding only its derived id and fingerprint
     */
    public Map<String, Object> canonicalMaterial() {
        return material(tenantId, environmentId, jobId, attemptId, ownerId, leaseEpoch,
                runtimeBindingFingerprint, reason, requestedAt, confirmationDeadlineAt,
                challenge);
    }

    private static Map<String, Object> material(
            String tenantId,
            String environmentId,
            String jobId,
            String attemptId,
            String ownerId,
            long leaseEpoch,
            String runtimeBindingFingerprint,
            Reason reason,
            Instant requestedAt,
            Instant confirmationDeadlineAt,
            String challenge) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("tenantId", tenantId);
        material.put("environmentId", environmentId);
        material.put("jobId", jobId);
        material.put("attemptId", attemptId);
        material.put("ownerId", ownerId);
        material.put("leaseEpoch", leaseEpoch);
        material.put("runtimeBindingFingerprint", runtimeBindingFingerprint);
        material.put("reason", reason);
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

    private static String requiredIdentifier(String value, String field) {
        String normalized = required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
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
