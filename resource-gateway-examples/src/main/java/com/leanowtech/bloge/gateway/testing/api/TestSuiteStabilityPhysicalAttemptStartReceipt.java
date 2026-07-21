package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Provider assertion about the start state of one physically isolated attempt.
 *
 * <p>The assertion becomes trusted only after its challenge-bound attestation is verified against
 * a pinned provider/deployment key and the exact start command. A signed rejection is retained as
 * an authenticated non-confirming result; it is never evidence that no side effect occurred.</p>
 *
 * @param schemaVersion exact receipt generation
 * @param commandId exact start command
 * @param commandFingerprint exact start-command material
 * @param providerId stable isolated-runtime provider
 * @param deploymentId exact provider workload generation
 * @param attemptId exact physical attempt
 * @param identityFingerprint exact reserved attempt identity
 * @param leaseEpoch durable queue ownership generation observed by the provider
 * @param providerSequence monotonic provider-side start sequence
 * @param isolationMode physical boundary asserted by the provider
 * @param outcome closed provider start decision
 * @param processIdentityFingerprint opaque process/container/VM identity commitment
 * @param runtimeStateFingerprint opaque started-state commitment
 * @param confirmedAt provider confirmation time
 */
public record TestSuiteStabilityPhysicalAttemptStartReceipt(
        String schemaVersion,
        String commandId,
        String commandFingerprint,
        String providerId,
        String deploymentId,
        String attemptId,
        String identityFingerprint,
        long leaseEpoch,
        long providerSequence,
        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode isolationMode,
        Outcome outcome,
        String processIdentityFingerprint,
        String runtimeStateFingerprint,
        Instant confirmedAt) {

    /** Exact physical-attempt start-receipt generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityPhysicalAttemptStartReceipt.v1";
    private static final Pattern COMMAND_ID =
            Pattern.compile("stability-attempt-start-[a-f0-9]{64}");
    private static final Pattern ATTEMPT_ID =
            Pattern.compile("stability-attempt-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");

    /** Closed provider start outcomes. */
    public enum Outcome {
        /** The provider started the exact isolated runtime and observed its live identity. */
        STARTED,
        /** The exact idempotent command already owns the same observed live runtime. */
        ALREADY_STARTED,
        /** The provider authenticated but did not confirm an isolated runtime start. */
        REJECTED
    }

    /** Validates the closed confirming/non-confirming receipt truth table. */
    public TestSuiteStabilityPhysicalAttemptStartReceipt {
        schemaVersion = required(schemaVersion, "schemaVersion");
        commandId = required(commandId, "commandId");
        commandFingerprint = required(commandFingerprint, "commandFingerprint");
        providerId = requiredIdentifier(providerId, "providerId");
        deploymentId = requiredIdentifier(deploymentId, "deploymentId");
        attemptId = required(attemptId, "attemptId");
        identityFingerprint = required(identityFingerprint, "identityFingerprint");
        isolationMode = Objects.requireNonNull(isolationMode, "isolationMode");
        outcome = Objects.requireNonNull(outcome, "outcome");
        processIdentityFingerprint = normalized(processIdentityFingerprint);
        runtimeStateFingerprint = normalized(runtimeStateFingerprint);
        confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
        boolean confirmingShape = FINGERPRINT.matcher(processIdentityFingerprint).matches()
                && FINGERPRINT.matcher(runtimeStateFingerprint).matches();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !COMMAND_ID.matcher(commandId).matches()
                || !FINGERPRINT.matcher(commandFingerprint).matches()
                || !ATTEMPT_ID.matcher(attemptId).matches()
                || !FINGERPRINT.matcher(identityFingerprint).matches()
                || leaseEpoch < 1 || providerSequence < 1
                || confirmedAt.getNano() % 1_000_000 != 0
                || (outcome != Outcome.REJECTED) != confirmingShape
                || outcome == Outcome.REJECTED
                && (!processIdentityFingerprint.isEmpty()
                || !runtimeStateFingerprint.isEmpty())) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability physical-attempt start receipt");
        }
    }

    /**
     * Distinguishes observed isolated runtime start from signed non-confirmation.
     *
     * @return whether this receipt can prove that the exact attempt started
     */
    public boolean startConfirmed() {
        return outcome == Outcome.STARTED || outcome == Outcome.ALREADY_STARTED;
    }

    /**
     * Reconstructs every receipt field covered by the provider signature.
     *
     * @return canonical signed receipt material
     */
    public Map<String, Object> canonicalMaterial() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", schemaVersion);
        material.put("commandId", commandId);
        material.put("commandFingerprint", commandFingerprint);
        material.put("providerId", providerId);
        material.put("deploymentId", deploymentId);
        material.put("attemptId", attemptId);
        material.put("identityFingerprint", identityFingerprint);
        material.put("leaseEpoch", leaseEpoch);
        material.put("providerSequence", providerSequence);
        material.put("isolationMode", isolationMode);
        material.put("outcome", outcome);
        material.put("processIdentityFingerprint", processIdentityFingerprint);
        material.put("runtimeStateFingerprint", runtimeStateFingerprint);
        material.put("confirmedAt", confirmedAt);
        return Map.copyOf(material);
    }

    /**
     * Detached Ed25519 provider attestation over one complete start receipt.
     *
     * @param schemaVersion exact attestation generation
     * @param receipt untrusted semantic start receipt
     * @param keyId pinned provider signing-key generation
     * @param signature base64url Ed25519 signature without padding
     */
    public record Attestation(
            String schemaVersion,
            TestSuiteStabilityPhysicalAttemptStartReceipt receipt,
            String keyId,
            String signature) {

        /** Exact start-attestation generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptStartAttestation.v1";

        /** Enforces a structurally valid detached-signature envelope. */
        public Attestation {
            schemaVersion = required(schemaVersion, "schemaVersion");
            receipt = Objects.requireNonNull(receipt, "receipt");
            keyId = requiredIdentifier(keyId, "keyId");
            signature = required(signature, "signature");
            try {
                if (!SCHEMA_VERSION.equals(schemaVersion)
                        || Base64.getUrlDecoder().decode(signature).length != 64
                        || signature.contains("=")) {
                    throw new IllegalArgumentException(
                            "Invalid suite-stability physical-attempt start attestation");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability physical-attempt start attestation");
            }
        }

        /**
         * Reconstructs the detached signature envelope without signature bytes.
         *
         * @return canonical material covered by the detached signature
         */
        public Map<String, Object> signedMaterial() {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", schemaVersion);
            material.put("keyId", keyId);
            material.put("receipt", receipt.canonicalMaterial());
            return Map.copyOf(material);
        }
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
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

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
