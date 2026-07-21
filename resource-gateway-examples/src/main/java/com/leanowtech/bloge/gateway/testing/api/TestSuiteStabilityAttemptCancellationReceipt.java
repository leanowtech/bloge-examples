package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Provider assertion about the terminal state of one isolated suite-stability attempt.
 *
 * <p>A receipt is not trusted by construction. It becomes provider-confirmed only after its
 * challenge-bound {@link Attestation} is verified against a pinned provider/deployment key and
 * the exact cancellation command. Fingerprints replace raw process identifiers and payloads.</p>
 *
 * @param schemaVersion exact receipt protocol generation
 * @param commandId exact cancellation command identity
 * @param commandFingerprint exact cancellation command material
 * @param providerId stable attempt-runtime provider
 * @param deploymentId exact provider deployment or workload generation
 * @param attemptId exact isolated attempt
 * @param leaseEpoch durable worker epoch observed by the provider
 * @param providerSequence monotonic provider-side receipt sequence
 * @param isolationMode physical isolation boundary asserted by the provider
 * @param outcome closed provider decision
 * @param terminationMode how a terminal state was established
 * @param processIdentityFingerprint opaque process/container identity commitment
 * @param terminalStateFingerprint opaque terminal-state commitment
 * @param confirmedAt provider confirmation time
 */
public record TestSuiteStabilityAttemptCancellationReceipt(
        String schemaVersion,
        String commandId,
        String commandFingerprint,
        String providerId,
        String deploymentId,
        String attemptId,
        long leaseEpoch,
        long providerSequence,
        IsolationMode isolationMode,
        Outcome outcome,
        TerminationMode terminationMode,
        String processIdentityFingerprint,
        String terminalStateFingerprint,
        Instant confirmedAt) {

    /** Exact receipt generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityAttemptCancellationReceipt.v1";
    private static final Pattern COMMAND_ID =
            Pattern.compile("stability-attempt-cancel-[a-f0-9]{64}");
    private static final Pattern ATTEMPT_ID =
            Pattern.compile("stability-attempt-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");

    /** Isolation boundaries strong enough to support provider-confirmed termination. */
    public enum IsolationMode {
        /** Dedicated operating-system process with an independently observed exit. */
        PROCESS,
        /** Dedicated container with an independently observed terminal state. */
        CONTAINER,
        /** Dedicated virtual-machine or micro-VM execution boundary. */
        VM
    }

    /** Closed provider response vocabulary. */
    public enum Outcome {
        /** The provider transitioned the exact live attempt to a terminal state. */
        TERMINATED,
        /** The exact attempt was already terminal and its identity still matched. */
        ALREADY_TERMINAL,
        /** The provider cannot prove that the requested exact attempt exists. */
        NOT_FOUND,
        /** The provider authenticated the command but refused its policy or fence. */
        REJECTED
    }

    /** Mechanism by which the provider established the reported terminal state. */
    public enum TerminationMode {
        /** The isolated attempt exited after a cooperative stop request. */
        GRACEFUL_EXIT,
        /** The provider observed an operating-system force kill and process exit. */
        PROCESS_KILL,
        /** The provider observed container termination. */
        CONTAINER_TERMINATION,
        /** The provider observed VM or micro-VM termination. */
        VM_TERMINATION,
        /** The exact attempt had already exited before the idempotent command. */
        ALREADY_EXITED,
        /** No terminal state was proved; valid only for non-confirming outcomes. */
        NONE
    }

    /** Validates the closed receipt truth table without treating it as trusted. */
    public TestSuiteStabilityAttemptCancellationReceipt {
        schemaVersion = required(schemaVersion, "schemaVersion");
        commandId = required(commandId, "commandId");
        commandFingerprint = required(commandFingerprint, "commandFingerprint");
        providerId = requiredIdentifier(providerId, "providerId");
        deploymentId = requiredIdentifier(deploymentId, "deploymentId");
        attemptId = required(attemptId, "attemptId");
        isolationMode = Objects.requireNonNull(isolationMode, "isolationMode");
        outcome = Objects.requireNonNull(outcome, "outcome");
        terminationMode = Objects.requireNonNull(terminationMode, "terminationMode");
        processIdentityFingerprint = required(
                processIdentityFingerprint, "processIdentityFingerprint");
        terminalStateFingerprint = required(
                terminalStateFingerprint, "terminalStateFingerprint");
        confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !COMMAND_ID.matcher(commandId).matches()
                || !FINGERPRINT.matcher(commandFingerprint).matches()
                || !ATTEMPT_ID.matcher(attemptId).matches()
                || leaseEpoch < 1
                || providerSequence < 1
                || !FINGERPRINT.matcher(processIdentityFingerprint).matches()
                || !FINGERPRINT.matcher(terminalStateFingerprint).matches()
                || confirmedAt.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability attempt cancellation receipt");
        }
        boolean validOutcome = switch (outcome) {
            case TERMINATED -> switch (isolationMode) {
                case PROCESS -> terminationMode == TerminationMode.GRACEFUL_EXIT
                        || terminationMode == TerminationMode.PROCESS_KILL;
                case CONTAINER -> terminationMode == TerminationMode.CONTAINER_TERMINATION;
                case VM -> terminationMode == TerminationMode.VM_TERMINATION;
            };
            case ALREADY_TERMINAL -> terminationMode == TerminationMode.ALREADY_EXITED;
            case NOT_FOUND, REJECTED -> terminationMode == TerminationMode.NONE;
        };
        if (!validOutcome) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability attempt cancellation receipt outcome");
        }
    }

    /**
     * Distinguishes terminal proof outcomes from signed non-confirming responses.
     *
     * @return whether this outcome can prove that the isolated attempt is no longer running
     */
    public boolean terminationConfirmed() {
        return outcome == Outcome.TERMINATED || outcome == Outcome.ALREADY_TERMINAL;
    }

    /**
     * Reconstructs every semantic receipt field covered by the provider signature.
     *
     * @return canonical receipt material included in the provider signature
     */
    public Map<String, Object> canonicalMaterial() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", schemaVersion);
        material.put("commandId", commandId);
        material.put("commandFingerprint", commandFingerprint);
        material.put("providerId", providerId);
        material.put("deploymentId", deploymentId);
        material.put("attemptId", attemptId);
        material.put("leaseEpoch", leaseEpoch);
        material.put("providerSequence", providerSequence);
        material.put("isolationMode", isolationMode);
        material.put("outcome", outcome);
        material.put("terminationMode", terminationMode);
        material.put("processIdentityFingerprint", processIdentityFingerprint);
        material.put("terminalStateFingerprint", terminalStateFingerprint);
        material.put("confirmedAt", confirmedAt);
        return Map.copyOf(material);
    }

    /**
     * Detached Ed25519 provider attestation over one complete receipt.
     *
     * @param schemaVersion exact attestation generation
     * @param receipt untrusted semantic receipt
     * @param keyId pinned provider signing-key generation
     * @param signature base64url Ed25519 signature without padding
     */
    public record Attestation(
            String schemaVersion,
            TestSuiteStabilityAttemptCancellationReceipt receipt,
            String keyId,
            String signature) {

        /** Exact attestation generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityAttemptCancellationAttestation.v1";

        /** Enforces a structurally valid detached signature envelope. */
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
                            "Invalid suite-stability attempt cancellation attestation");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability attempt cancellation attestation");
            }
        }

        /**
         * Reconstructs the detached signature envelope without the signature bytes.
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
}
