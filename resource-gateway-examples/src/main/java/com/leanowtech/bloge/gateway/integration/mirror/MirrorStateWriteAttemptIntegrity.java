package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Canonical sealing and verification for durable Session write-attempt records.
 *
 * <p>The fingerprint covers every public field except itself. Verification first canonicalizes
 * through the strict Java record and then compares the detached fingerprint, so persisted JSON
 * cannot silently drift from indexed recovery coordinates.</p>
 */
public final class MirrorStateWriteAttemptIntegrity {
    /** Maximum canonical bytes admitted for one payload-free journal record. */
    public static final int MAXIMUM_RECORD_BYTES = 64 * 1024;

    private MirrorStateWriteAttemptIntegrity() {
    }

    /**
     * Derives the stable identifier of one exact execution-attempt coordinate.
     *
     * <p>The identifier excludes business payload. A new durable run-lease epoch necessarily
     * produces a different id, while a retry inside the same epoch and delegate coordinate
     * addresses the original record.</p>
     *
     * @param mapper canonical protocol mapper
     * @param scope exact enterprise namespace
     * @param sessionId exact Session identity
     * @param coordinate exact execution and delegate coordinate
     * @param writeEffectRef exact write effect
     * @param requestFingerprint canonical invocation input identity
     * @return deterministic bounded attempt identifier
     */
    public static String attemptId(
            ObjectMapper mapper,
            CapabilitySnapshot.Scope scope,
            String sessionId,
            MirrorStateWriteAttempt.Coordinate coordinate,
            MirrorArtifactRef writeEffectRef,
            String requestFingerprint) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("scope", Objects.requireNonNull(scope, "scope"));
        material.put("sessionId",
                MirrorStateProtocolSupport.required(
                        sessionId, "sessionId"));
        material.put("coordinate",
                Objects.requireNonNull(coordinate, "coordinate"));
        material.put("writeEffectRef",
                Objects.requireNonNull(writeEffectRef, "writeEffectRef"));
        material.put("requestFingerprint",
                MirrorStateProtocolSupport.fingerprint(
                        requestFingerprint, "requestFingerprint"));
        String fingerprint = ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                material, MAXIMUM_RECORD_BYTES);
        UUID id = UUID.nameUUIDFromBytes(
                fingerprint.getBytes(StandardCharsets.UTF_8));
        return "attempt-" + id;
    }

    /**
     * Seals one unsealed write-attempt record.
     *
     * @param mapper canonical protocol mapper
     * @param value complete record with a blank fingerprint
     * @return immutable record carrying its canonical fingerprint
     */
    public static MirrorStateWriteAttempt seal(
            ObjectMapper mapper, MirrorStateWriteAttempt value) {
        Objects.requireNonNull(mapper, "mapper");
        MirrorStateWriteAttempt candidate =
                Objects.requireNonNull(value, "value")
                        .withFingerprint("");
        String fingerprint = ProtocolFingerprint.ofBounded(
                mapper, material(candidate), MAXIMUM_RECORD_BYTES);
        MirrorStateWriteAttempt sealed =
                candidate.withFingerprint(fingerprint);
        verify(mapper, sealed);
        return sealed;
    }

    /**
     * Verifies the canonical fingerprint of one persisted record.
     *
     * @param mapper canonical protocol mapper
     * @param value persisted write-attempt record
     * @throws IllegalArgumentException when the record is unsealed or altered
     */
    public static void verify(
            ObjectMapper mapper, MirrorStateWriteAttempt value) {
        Objects.requireNonNull(mapper, "mapper");
        MirrorStateWriteAttempt candidate =
                Objects.requireNonNull(value, "value");
        MirrorSessionStoreGenerationIntegrity.verify(
                mapper, candidate.storeGeneration());
        String supplied = MirrorStateProtocolSupport.fingerprint(
                candidate.fingerprint(), "fingerprint");
        String expected = ProtocolFingerprint.ofBounded(
                mapper, material(candidate.withFingerprint("")),
                MAXIMUM_RECORD_BYTES);
        if (!supplied.equals(expected)) {
            throw new IllegalArgumentException(
                    "mirror state write-attempt fingerprint mismatch");
        }
    }

    /**
     * Computes a payload-free failure identity for a terminal attempt.
     *
     * @param mapper canonical protocol mapper
     * @param attemptId exact durable attempt
     * @param commandFingerprint canonical state-command identity
     * @param outcome conservative terminal outcome
     * @param stage last trustworthy stage
     * @param retryable whether a new attempt may be admitted
     * @param errorCode stable machine-readable code
     * @param errorType normalized failure family
     * @return canonical failure fingerprint
     */
    public static String failureFingerprint(
            ObjectMapper mapper,
            String attemptId,
            String commandFingerprint,
            MirrorStateWriteOutcomeRunEvidence.WriteOutcome outcome,
            MirrorStateWriteOutcomeRunEvidence.WriteStage stage,
            boolean retryable,
            String errorCode,
            String errorType) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("attemptId", MirrorStateProtocolSupport.required(
                attemptId, "attemptId"));
        material.put("commandFingerprint",
                MirrorStateProtocolSupport.fingerprint(
                        commandFingerprint, "commandFingerprint"));
        material.put("outcome", Objects.requireNonNull(outcome, "outcome"));
        material.put("stage", Objects.requireNonNull(stage, "stage"));
        material.put("retryable", retryable);
        material.put("errorCode",
                MirrorStateProtocolSupport.errorCode(errorCode));
        material.put("errorType",
                MirrorStateProtocolSupport.required(
                        errorType, "errorType"));
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                material, MAXIMUM_RECORD_BYTES);
    }

    private static Map<String, Object> material(
            MirrorStateWriteAttempt value) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", value.schemaVersion());
        material.put("scope", value.scope());
        material.put("sessionId", value.sessionId());
        material.put("attemptId", value.attemptId());
        material.put("coordinate", value.coordinate());
        material.put("storeGeneration", value.storeGeneration());
        material.put("planFingerprint", value.planFingerprint());
        material.put("writeEffectRef", value.writeEffectRef());
        material.put("requestFingerprint", value.requestFingerprint());
        material.put("commandFingerprint", value.commandFingerprint());
        material.put("initialStateRevision", value.initialStateRevision());
        material.put("initialWorldFingerprint",
                value.initialWorldFingerprint());
        material.put("initialStateFingerprint",
                value.initialStateFingerprint());
        material.put("status", value.status());
        material.put("outcome", value.outcome());
        material.put("stage", value.stage());
        material.put("stateDisposition", value.stateDisposition());
        material.put("resultingStateRevision",
                value.resultingStateRevision());
        material.put("resultingWorldFingerprint",
                value.resultingWorldFingerprint());
        material.put("resultingStateFingerprint",
                value.resultingStateFingerprint());
        material.put("receiptFingerprint", value.receiptFingerprint());
        material.put("retryable", value.retryable());
        material.put("errorCode", value.errorCode());
        material.put("errorType", value.errorType());
        material.put("failureFingerprint", value.failureFingerprint());
        material.put("resolutionSource", value.resolutionSource());
        material.put("startedAt", value.startedAt());
        material.put("terminalAt", value.terminalAt());
        material.put("reconciledAt", value.reconciledAt());
        material.put("fingerprint", value.fingerprint());
        return material;
    }
}
