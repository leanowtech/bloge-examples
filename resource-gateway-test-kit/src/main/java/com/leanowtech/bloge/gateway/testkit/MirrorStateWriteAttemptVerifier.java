package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Server-independent verifier for durable payload-free Session write attempts.
 *
 * <p>The verifier applies the packaged strict Schema, re-derives the nested store-generation and
 * record fingerprints, proves the deterministic attempt id, and checks terminal outcome/state
 * closure. It has no dependency on Resource Gateway server classes and never returns command,
 * response, entity, or raw idempotency material.</p>
 */
public final class MirrorStateWriteAttemptVerifier {
    /** Maximum canonical bytes admitted by the producer protocol. */
    public static final int MAXIMUM_RECORD_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Creates an independent durable write-attempt verifier. */
    public MirrorStateWriteAttemptVerifier() {
    }

    /**
     * Payload-free verified outcome.
     *
     * @param attemptId deterministic attempt identity
     * @param sessionId exact Session identity
     * @param status in-progress or terminal status
     * @param outcome terminal outcome, blank while in progress
     * @param initialStateRevision state revision observed before execution
     * @param resultingStateRevision proven resulting revision, or {@code -1}
     * @param resolutionSource execution or reconciler authority
     * @param fingerprint canonical record fingerprint
     */
    public record VerifiedWriteAttempt(
            String attemptId,
            String sessionId,
            String status,
            String outcome,
            long initialStateRevision,
            long resultingStateRevision,
            String resolutionSource,
            String fingerprint
    ) {
        /** Validates one bounded payload-free verification result. */
        public VerifiedWriteAttempt {
            attemptId = required(attemptId, "attemptId");
            sessionId = required(sessionId, "sessionId");
            status = required(status, "status");
            outcome = outcome == null ? "" : outcome.trim();
            resolutionSource = required(
                    resolutionSource, "resolutionSource");
            fingerprint = MirrorStateWriteAttemptVerifier.fingerprint(
                    fingerprint, "fingerprint");
            if (initialStateRevision < 0
                    || resultingStateRevision < -1) {
                throw new IllegalArgumentException(
                        "write-attempt revisions are invalid");
            }
        }
    }

    /**
     * Independently verifies one decoded write-attempt payload.
     *
     * @param value strict record payload from the protected API
     * @return bounded payload-free verified projection
     */
    public VerifiedWriteAttempt verify(JsonNode value) {
        CapabilityMirrorSchemaValidator.require(
                value,
                CapabilityMirrorProtocol
                        .MIRROR_STATE_WRITE_ATTEMPT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.STATE_WRITE_ATTEMPT_SCHEMA_INVALID");
        requireFingerprint(
                value.path("storeGeneration"),
                "fingerprint",
                MAXIMUM_RECORD_BYTES,
                "RG.MIRROR.CLIENT.STATE_WRITE_ATTEMPT_GENERATION_INVALID");
        requireAttemptFingerprint(value);
        verifyAttemptId(value);
        verifyTimes(value);
        verifyLifecycle(value);
        return new VerifiedWriteAttempt(
                value.path("attemptId").asText(),
                value.path("sessionId").asText(),
                value.path("status").asText(),
                value.path("outcome").asText(),
                value.path("initialStateRevision").asLong(-1),
                value.path("resultingStateRevision").asLong(-2),
                value.path("resolutionSource").asText(),
                value.path("fingerprint").asText());
    }

    private static void verifyAttemptId(JsonNode value) {
        ObjectNode material = JSON.createObjectNode();
        material.set("scope", value.path("scope").deepCopy());
        material.set("sessionId",
                value.path("sessionId").deepCopy());
        material.set("coordinate",
                value.path("coordinate").deepCopy());
        material.set("writeEffectRef",
                value.path("writeEffectRef").deepCopy());
        material.set("requestFingerprint",
                value.path("requestFingerprint").deepCopy());
        String fingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        material, MAXIMUM_RECORD_BYTES);
        String expected = "attempt-"
                + UUID.nameUUIDFromBytes(
                fingerprint.getBytes(
                        StandardCharsets.UTF_8));
        if (!expected.equals(
                value.path("attemptId").asText())) {
            throw invalid(
                    "RG.MIRROR.CLIENT.STATE_WRITE_ATTEMPT_ID_INVALID");
        }
    }

    private static void verifyTimes(JsonNode value) {
        Instant started = instant(
                value.path("startedAt"),
                "RG.MIRROR.CLIENT.STATE_WRITE_ATTEMPT_TIME_INVALID");
        if ("IN_PROGRESS".equals(
                value.path("status").asText())) {
            return;
        }
        Instant terminal = instant(
                value.path("terminalAt"),
                "RG.MIRROR.CLIENT.STATE_WRITE_ATTEMPT_TIME_INVALID");
        if (terminal.isBefore(started)) {
            throw invalid(
                    "RG.MIRROR.CLIENT.STATE_WRITE_ATTEMPT_TIME_INVALID");
        }
        if ("RECONCILER".equals(
                value.path("resolutionSource").asText())
                && !terminal.equals(instant(
                value.path("reconciledAt"),
                "RG.MIRROR.CLIENT.STATE_WRITE_ATTEMPT_TIME_INVALID"))) {
            throw invalid(
                    "RG.MIRROR.CLIENT.STATE_WRITE_ATTEMPT_TIME_INVALID");
        }
    }

    private static void verifyLifecycle(JsonNode value) {
        if ("IN_PROGRESS".equals(
                value.path("status").asText())) {
            return;
        }
        String outcome = value.path("outcome").asText();
        long initial =
                value.path("initialStateRevision").asLong(-1);
        long resulting =
                value.path("resultingStateRevision").asLong(-2);
        String disposition =
                value.path("stateDisposition").asText();
        boolean success = "COMMITTED".equals(outcome)
                || "REPLAYED".equals(outcome);
        if (success) {
            boolean committed = "COMMITTED".equals(outcome);
            if (!"COMPLETED".equals(
                    value.path("stage").asText())
                    || !(committed ? "ADVANCED" : "UNCHANGED")
                    .equals(disposition)
                    || resulting
                    != (committed ? initial + 1 : initial)
                    || value.path("receiptFingerprint")
                    .asText().isBlank()
                    || !value.path("errorCode")
                    .asText().isBlank()
                    || !value.path("failureFingerprint")
                    .asText().isBlank()) {
                throw invalid(
                        "RG.MIRROR.CLIENT.STATE_WRITE_ATTEMPT_CLOSURE_INVALID");
            }
            return;
        }
        requireFailureFingerprint(value);
        if ("COMMIT_OUTCOME_UNKNOWN".equals(outcome)) {
            if (!"UNKNOWN".equals(disposition)
                    || resulting != -1) {
                throw invalid(
                        "RG.MIRROR.CLIENT.STATE_WRITE_ATTEMPT_CLOSURE_INVALID");
            }
        } else if (!"UNCHANGED".equals(disposition)
                || resulting != initial
                || !value.path("initialWorldFingerprint").equals(
                value.path("resultingWorldFingerprint"))
                || !value.path("initialStateFingerprint").equals(
                value.path("resultingStateFingerprint"))) {
            throw invalid(
                    "RG.MIRROR.CLIENT.STATE_WRITE_ATTEMPT_CLOSURE_INVALID");
        }
    }

    private static void requireFailureFingerprint(
            JsonNode value) {
        ObjectNode material = JSON.createObjectNode();
        material.set("attemptId",
                value.path("attemptId").deepCopy());
        material.set("commandFingerprint",
                value.path("commandFingerprint").deepCopy());
        material.set("outcome",
                value.path("outcome").deepCopy());
        material.set("stage",
                value.path("stage").deepCopy());
        material.set("retryable",
                value.path("retryable").deepCopy());
        material.set("errorCode",
                value.path("errorCode").deepCopy());
        material.set("errorType",
                value.path("errorType").deepCopy());
        String expected =
                EvidenceVerificationSupport.sha256Bounded(
                        material, MAXIMUM_RECORD_BYTES);
        if (!expected.equals(
                value.path("failureFingerprint").asText())) {
            throw invalid(
                    "RG.MIRROR.CLIENT.STATE_WRITE_ATTEMPT_FAILURE_INVALID");
        }
    }

    private static void requireFingerprint(
            JsonNode value,
            String field,
            int maximumBytes,
            String failureCode) {
        ObjectNode material = value.deepCopy();
        material.put(field, "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                material, maximumBytes)
                .equals(value.path(field).asText())) {
            throw invalid(failureCode);
        }
    }

    private static void requireAttemptFingerprint(
            JsonNode value) {
        ObjectNode material = value.deepCopy();
        if (!material.has("outcome")) {
            material.putNull("outcome");
        }
        if (!material.has("terminalAt")) {
            material.putNull("terminalAt");
        }
        if (!material.has("reconciledAt")) {
            material.putNull("reconciledAt");
        }
        material.put("fingerprint", "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                material, MAXIMUM_RECORD_BYTES)
                .equals(value.path(
                        "fingerprint").asText())) {
            throw invalid(
                    "RG.MIRROR.CLIENT.STATE_WRITE_ATTEMPT_FINGERPRINT_INVALID");
        }
    }

    private static Instant instant(
            JsonNode value, String failureCode) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException invalid) {
            throw invalid(failureCode);
        }
    }

    private static String required(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }

    private static String fingerprint(
            String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches(
                "sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }

    private static IllegalArgumentException invalid(
            String code) {
        return new IllegalArgumentException(code);
    }
}
