package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical server-owned key derivation for durable recovery child commands.
 *
 * <p>Execution, replay, and retention must derive exactly the same identities. Centralizing the
 * versioned formats prevents lifecycle code from silently orphaning rows after a local string
 * format change. Callers never supply these keys directly.</p>
 */
public final class DurableTestRecoveryCommandKeys {

    private static final String SHA_PREFIX = "sha256:";
    private static final Pattern SHA_256 = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern NAMESPACE = Pattern.compile("[a-f0-9]{64}");

    private DurableTestRecoveryCommandKeys() {
    }

    /**
     * Derives the tenant/environment-scoped namespace for one outer recovery sequence.
     *
     * @param objectMapper canonical protocol mapper
     * @param tenantId authenticated tenant
     * @param environmentId isolated test-runtime environment
     * @param clientRequestId outer caller-stable sequence key
     * @return 64-character lowercase hexadecimal namespace
     */
    public static String sequenceNamespace(
            ObjectMapper objectMapper,
            String tenantId,
            String environmentId,
            String clientRequestId) {
        String fingerprint = ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), Map.of(
                        "schemaVersion", "bloge.durableRecoverySequenceChildNamespace.v1",
                        "tenantId", required(tenantId, "tenantId"),
                        "environmentId", required(environmentId, "environmentId"),
                        "clientRequestId", required(clientRequestId, "clientRequestId")));
        return fingerprint.substring(SHA_PREFIX.length());
    }

    /** @return deterministic recovery-step key for a zero-based signal index */
    public static String sequenceStep(String namespace, int index) {
        return sequenceChild(namespace, "step", index);
    }

    /** @return deterministic owner-claim key for a positive intermediate boundary index */
    public static String sequenceClaim(String namespace, int index) {
        if (index < 1) {
            throw new IllegalArgumentException("Sequence claim index must be positive");
        }
        return sequenceChild(namespace, "claim", index);
    }

    /**
     * Derives one automatic heartbeat idempotency key from an operation and source revision.
     *
     * @param operationFingerprint canonical recovery operation fingerprint
     * @param revision non-negative source checkpoint revision
     * @return bounded server-owned heartbeat command key
     */
    public static String automaticHeartbeat(String operationFingerprint, long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("Heartbeat revision cannot be negative");
        }
        return automaticHeartbeatPrefix(operationFingerprint) + revision;
    }

    /**
     * Returns the exact prefix shared by automatic heartbeats for one recovery operation.
     *
     * @param operationFingerprint canonical recovery operation fingerprint
     * @return prefix safe for a database prefix lookup
     */
    public static String automaticHeartbeatPrefix(String operationFingerprint) {
        String fingerprint = required(operationFingerprint, "operationFingerprint");
        if (!SHA_256.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Recovery operation fingerprint must be canonical SHA-256");
        }
        return "auto-recovery-" + fingerprint.substring(SHA_PREFIX.length()) + "-";
    }

    private static String sequenceChild(String namespace, String kind, int index) {
        String safeNamespace = required(namespace, "namespace");
        if (!NAMESPACE.matcher(safeNamespace).matches()) {
            throw new IllegalArgumentException(
                    "Recovery-sequence namespace must be 64 lowercase hexadecimal characters");
        }
        if (index < 0 || index > 15) {
            throw new IllegalArgumentException(
                    "Recovery-sequence child index must be between zero and fifteen");
        }
        return "rseq:" + safeNamespace + ":" + kind + ":" + index;
    }

    private static String required(String value, String name) {
        String safe = value == null ? "" : value.trim();
        if (safe.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return safe;
    }
}
