package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Provider assertion about the observed lifecycle state of one physical attempt.
 *
 * <p>The receipt is untrusted until its detached attestation is verified. It exposes no process
 * id, payload, credential, or provider diagnostic. {@link State#NOT_OBSERVED} and
 * {@link State#INDETERMINATE} are authenticated non-confirming observations and never prove that
 * a start side effect did not occur.</p>
 *
 * @param schemaVersion exact receipt generation
 * @param commandId exact observation command
 * @param commandFingerprint exact observation-command material
 * @param providerId stable isolated-runtime provider
 * @param deploymentId exact provider workload generation
 * @param attemptId exact physical attempt
 * @param identityFingerprint exact reserved attempt identity
 * @param startCommandId exact original start command
 * @param startCommandFingerprint exact original start-command material
 * @param leaseEpoch durable queue ownership generation observed by the provider
 * @param providerSequence monotonic provider-wide signed-fact sequence
 * @param attemptRevision monotonic state revision for this attempt, or zero if non-confirming
 * @param isolationMode physical boundary asserted by the provider
 * @param state closed observed lifecycle state
 * @param processIdentityFingerprint opaque process/container/VM identity commitment or empty
 * @param runtimeStateFingerprint opaque positive-state commitment or empty
 * @param terminalDisposition closed terminal outcome, or {@code NONE}
 * @param evidenceFingerprint terminal evidence-manifest commitment or empty
 * @param stateEffectiveAt provider time at which the asserted state became effective
 * @param confirmedAt provider observation confirmation time
 */
public record TestSuiteStabilityPhysicalAttemptObservationReceipt(
        String schemaVersion,
        String commandId,
        String commandFingerprint,
        String providerId,
        String deploymentId,
        String attemptId,
        String identityFingerprint,
        String startCommandId,
        String startCommandFingerprint,
        long leaseEpoch,
        long providerSequence,
        long attemptRevision,
        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode isolationMode,
        State state,
        String processIdentityFingerprint,
        String runtimeStateFingerprint,
        TerminalDisposition terminalDisposition,
        String evidenceFingerprint,
        Instant stateEffectiveAt,
        Instant confirmedAt) {

    /** Exact physical-attempt observation-receipt generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityPhysicalAttemptObservationReceipt.v1";
    private static final Pattern OBSERVATION_COMMAND_ID =
            Pattern.compile("stability-attempt-observe-[a-f0-9]{64}");
    private static final Pattern START_COMMAND_ID =
            Pattern.compile("stability-attempt-start-[a-f0-9]{64}");
    private static final Pattern ATTEMPT_ID =
            Pattern.compile("stability-attempt-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");

    /** Closed provider lifecycle observations. */
    public enum State {
        /** The provider durably accepted the start but has not proved a process identity. */
        START_PENDING,
        /** The provider independently observed the exact isolated process as live. */
        RUNNING,
        /** The provider independently observed the exact isolated process as terminal. */
        TERMINAL,
        /** No retained fact was found; this is not proof that no start occurred. */
        NOT_OBSERVED,
        /** Retained facts cannot establish one coherent positive lifecycle state. */
        INDETERMINATE
    }

    /** Closed terminal outcome vocabulary carried only by {@link State#TERMINAL}. */
    public enum TerminalDisposition {
        /** No terminal outcome is asserted. */
        NONE,
        /** The isolated runtime completed its declared work successfully. */
        SUCCEEDED,
        /** The isolated runtime completed with a business or runtime failure. */
        FAILED,
        /** Provider-confirmed cancellation caused the terminal state. */
        CANCELLED,
        /** Provider-enforced attempt deadline caused the terminal state. */
        TIMED_OUT,
        /** The provider aborted the runtime because its boundary became unhealthy. */
        PROVIDER_ABORTED
    }

    /** Validates the closed positive and non-confirming state shapes. */
    public TestSuiteStabilityPhysicalAttemptObservationReceipt {
        schemaVersion = required(schemaVersion, "schemaVersion");
        commandId = required(commandId, "commandId");
        commandFingerprint = required(commandFingerprint, "commandFingerprint");
        providerId = requiredIdentifier(providerId, "providerId");
        deploymentId = requiredIdentifier(deploymentId, "deploymentId");
        attemptId = required(attemptId, "attemptId");
        identityFingerprint = required(identityFingerprint, "identityFingerprint");
        startCommandId = required(startCommandId, "startCommandId");
        startCommandFingerprint = required(
                startCommandFingerprint, "startCommandFingerprint");
        isolationMode = Objects.requireNonNull(isolationMode, "isolationMode");
        state = Objects.requireNonNull(state, "state");
        processIdentityFingerprint = normalized(processIdentityFingerprint);
        runtimeStateFingerprint = normalized(runtimeStateFingerprint);
        terminalDisposition = Objects.requireNonNull(
                terminalDisposition, "terminalDisposition");
        evidenceFingerprint = normalized(evidenceFingerprint);
        stateEffectiveAt = exactInstant(stateEffectiveAt, "stateEffectiveAt");
        confirmedAt = exactInstant(confirmedAt, "confirmedAt");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !OBSERVATION_COMMAND_ID.matcher(commandId).matches()
                || !FINGERPRINT.matcher(commandFingerprint).matches()
                || !ATTEMPT_ID.matcher(attemptId).matches()
                || !FINGERPRINT.matcher(identityFingerprint).matches()
                || !START_COMMAND_ID.matcher(startCommandId).matches()
                || !FINGERPRINT.matcher(startCommandFingerprint).matches()
                || leaseEpoch < 1 || providerSequence < 1
                || stateEffectiveAt.isAfter(confirmedAt)) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability physical-attempt observation receipt");
        }
        boolean process = FINGERPRINT.matcher(processIdentityFingerprint).matches();
        boolean runtime = FINGERPRINT.matcher(runtimeStateFingerprint).matches();
        boolean evidence = FINGERPRINT.matcher(evidenceFingerprint).matches();
        boolean validShape = switch (state) {
            case START_PENDING -> attemptRevision >= 1 && !process && runtime
                    && terminalDisposition == TerminalDisposition.NONE && !evidence;
            case RUNNING -> attemptRevision >= 1 && process && runtime
                    && terminalDisposition == TerminalDisposition.NONE && !evidence;
            case TERMINAL -> attemptRevision >= 1 && process && runtime
                    && terminalDisposition != TerminalDisposition.NONE && evidence;
            case NOT_OBSERVED, INDETERMINATE -> attemptRevision == 0
                    && !process && !runtime
                    && terminalDisposition == TerminalDisposition.NONE && !evidence;
        };
        if (!validShape) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability physical-attempt observation state");
        }
    }

    /**
     * Reports whether an independently observed process identity is present.
     *
     * @return whether this receipt proves an exact running or terminal process identity
     */
    public boolean processIdentityConfirmed() {
        return state == State.RUNNING || state == State.TERMINAL;
    }

    /**
     * Reports whether the exact isolated runtime is provider-confirmed terminal.
     *
     * @return whether this receipt carries a terminal process and evidence commitment
     */
    public boolean terminalConfirmed() {
        return state == State.TERMINAL;
    }

    /**
     * Reports whether this observation still needs start-or-orphan reconciliation.
     *
     * @return whether bounded reconciliation remains necessary
     */
    public boolean reconciliationRequired() {
        return state == State.START_PENDING
                || state == State.NOT_OBSERVED
                || state == State.INDETERMINATE;
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
        material.put("startCommandId", startCommandId);
        material.put("startCommandFingerprint", startCommandFingerprint);
        material.put("leaseEpoch", leaseEpoch);
        material.put("providerSequence", providerSequence);
        material.put("attemptRevision", attemptRevision);
        material.put("isolationMode", isolationMode);
        material.put("state", state);
        material.put("processIdentityFingerprint", processIdentityFingerprint);
        material.put("runtimeStateFingerprint", runtimeStateFingerprint);
        material.put("terminalDisposition", terminalDisposition);
        material.put("evidenceFingerprint", evidenceFingerprint);
        material.put("stateEffectiveAt", stateEffectiveAt);
        material.put("confirmedAt", confirmedAt);
        return Map.copyOf(material);
    }

    /**
     * Detached Ed25519 provider attestation over one complete observation receipt.
     *
     * @param schemaVersion exact attestation generation
     * @param receipt untrusted semantic observation receipt
     * @param keyId pinned provider signing-key generation
     * @param signature base64url Ed25519 signature without padding
     */
    public record Attestation(
            String schemaVersion,
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt,
            String keyId,
            String signature) {

        /** Exact observation-attestation generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptObservationAttestation.v1";

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
                            "Invalid suite-stability physical-attempt observation attestation");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability physical-attempt observation attestation");
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
