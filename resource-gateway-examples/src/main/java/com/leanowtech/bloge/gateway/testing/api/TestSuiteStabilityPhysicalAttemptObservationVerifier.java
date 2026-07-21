package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Verifies challenge-bound physical-attempt lifecycle observations against pinned Ed25519 trust.
 *
 * <p>Verification re-derives the nested physical identity, original start command, and observation
 * command before binding provider identity, process identity, minimum attempt revision, time, and
 * signature. Authenticated non-confirming states remain valid observations but are never promoted
 * to non-start or terminal proof.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptObservationVerifier {

    private final ObjectMapper objectMapper;
    private final Map<String, TrustKey> trustKeys;
    private final Duration maximumFutureSkew;

    /**
     * Creates an exact pinned-trust verifier for provider lifecycle observations.
     *
     * @param objectMapper canonical mapper shared by command creation and provider signing
     * @param trustKeys exact provider/deployment/key trust inventory
     * @param maximumFutureSkew tolerated provider clock lead from zero through 30 seconds
     */
    public TestSuiteStabilityPhysicalAttemptObservationVerifier(
            ObjectMapper objectMapper,
            Collection<TrustKey> trustKeys,
            Duration maximumFutureSkew) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Collection<TrustKey> requiredKeys = Objects.requireNonNull(trustKeys, "trustKeys");
        this.trustKeys = requiredKeys.stream().collect(Collectors.toUnmodifiableMap(
                TrustKey::keyId, Function.identity(), (left, right) -> {
                    throw new IllegalArgumentException(
                            "Duplicate physical-attempt observation trust key id");
                }));
        if (this.trustKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "Physical-attempt observation trust inventory is empty");
        }
        this.maximumFutureSkew = Objects.requireNonNull(
                maximumFutureSkew, "maximumFutureSkew");
        if (maximumFutureSkew.isNegative()
                || maximumFutureSkew.compareTo(Duration.ofSeconds(30)) > 0
                || !maximumFutureSkew.equals(
                Duration.ofMillis(maximumFutureSkew.toMillis()))) {
            throw new IllegalArgumentException(
                    "Physical-attempt observation future skew is invalid");
        }
    }

    /**
     * Verifies one live provider response and returns its exact semantic receipt.
     *
     * @param command exact observation command sent to the provider
     * @param descriptor descriptor obtained from that provider before invocation
     * @param attestation untrusted detached provider response
     * @param observedAt caller time after the response was received
     * @return fully verified positive or non-confirming lifecycle observation
     * @throws VerificationException for integrity, binding, rollback, time, trust, or signature
     */
    public TestSuiteStabilityPhysicalAttemptObservationReceipt verify(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attestation,
            Instant observedAt) {
        TestSuiteStabilityPhysicalAttemptObservationCommand requiredCommand =
                Objects.requireNonNull(command, "command");
        TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor requiredDescriptor =
                Objects.requireNonNull(descriptor, "descriptor");
        TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation requiredAttestation =
                Objects.requireNonNull(attestation, "attestation");
        Instant observation = Objects.requireNonNull(observedAt, "observedAt");
        if (observation.getNano() % 1_000_000 != 0) {
            throw failed(FailureReason.TIME_INVALID);
        }

        verifyNestedIntegrity(requiredCommand);
        TestSuiteStabilityPhysicalAttemptIdentity identity = requiredCommand.identity();
        TestSuiteStabilityPhysicalAttemptObservationReceipt receipt =
                requiredAttestation.receipt();
        if (!requiredDescriptor.available()
                || !identity.providerId().equals(requiredDescriptor.providerId())
                || !identity.deploymentId().equals(requiredDescriptor.deploymentId())
                || !requiredDescriptor.providerId().equals(receipt.providerId())
                || !requiredDescriptor.deploymentId().equals(receipt.deploymentId())
                || !requiredDescriptor.keyId().equals(requiredAttestation.keyId())
                || identity.isolationMode() != receipt.isolationMode()
                || !requiredDescriptor.isolationModes().contains(receipt.isolationMode())) {
            throw failed(FailureReason.PROVIDER_BINDING_INVALID);
        }
        TestSuiteStabilityPhysicalAttemptStartCommand start = requiredCommand.startCommand();
        if (!requiredCommand.commandId().equals(receipt.commandId())
                || !requiredCommand.commandFingerprint().equals(receipt.commandFingerprint())
                || !identity.attemptId().equals(receipt.attemptId())
                || !identity.identityFingerprint().equals(receipt.identityFingerprint())
                || !start.commandId().equals(receipt.startCommandId())
                || !start.commandFingerprint().equals(receipt.startCommandFingerprint())
                || identity.leaseEpoch() != receipt.leaseEpoch()) {
            throw failed(FailureReason.COMMAND_BINDING_INVALID);
        }
        verifyStateFence(requiredCommand, receipt);
        verifyTime(requiredCommand, requiredDescriptor, receipt, observation);

        TrustKey trust = trustKeys.get(requiredAttestation.keyId());
        if (trust == null
                || !trust.providerId().equals(receipt.providerId())
                || !trust.deploymentId().equals(receipt.deploymentId())
                || receipt.confirmedAt().isBefore(trust.notBefore())
                || receipt.confirmedAt().isAfter(trust.notAfter())
                || observation.isAfter(trust.notAfter().plus(maximumFutureSkew))) {
            throw failed(FailureReason.TRUST_INVALID);
        }
        if (!verifySignature(trust.publicKey(), requiredAttestation)) {
            throw failed(FailureReason.SIGNATURE_INVALID);
        }
        return receipt;
    }

    private void verifyNestedIntegrity(
            TestSuiteStabilityPhysicalAttemptObservationCommand command) {
        TestSuiteStabilityPhysicalAttemptIdentity identity = command.identity();
        String derivedIdentity = ProtocolFingerprint.of(
                objectMapper, identity.canonicalMaterial());
        if (!derivedIdentity.equals(identity.identityFingerprint())
                || !identity.attemptId().equals("stability-attempt-"
                + derivedIdentity.substring("sha256:".length()))) {
            throw failed(FailureReason.IDENTITY_INTEGRITY_INVALID);
        }
        TestSuiteStabilityPhysicalAttemptStartCommand start = command.startCommand();
        String derivedStart = ProtocolFingerprint.of(
                objectMapper, start.canonicalMaterial());
        if (!derivedStart.equals(start.commandFingerprint())
                || !start.commandId().equals("stability-attempt-start-"
                + derivedStart.substring("sha256:".length()))) {
            throw failed(FailureReason.START_COMMAND_INTEGRITY_INVALID);
        }
        String derivedObservation = ProtocolFingerprint.of(
                objectMapper, command.canonicalMaterial());
        if (!derivedObservation.equals(command.commandFingerprint())
                || !command.commandId().equals("stability-attempt-observe-"
                + derivedObservation.substring("sha256:".length()))) {
            throw failed(FailureReason.OBSERVATION_COMMAND_INTEGRITY_INVALID);
        }
    }

    private static void verifyStateFence(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt) {
        if (receipt.attemptRevision() > 0
                && receipt.attemptRevision() < command.minimumAttemptRevision()) {
            throw failed(FailureReason.STATE_ROLLBACK);
        }
        String expectedProcess = command.expectedProcessIdentityFingerprint();
        if (!expectedProcess.isEmpty()
                && receipt.state()
                == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.START_PENDING) {
            throw failed(FailureReason.STATE_ROLLBACK);
        }
        if (!expectedProcess.isEmpty() && receipt.processIdentityConfirmed()
                && !expectedProcess.equals(receipt.processIdentityFingerprint())) {
            throw failed(FailureReason.PROCESS_BINDING_INVALID);
        }
    }

    private void verifyTime(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor,
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt,
            Instant observation) {
        Duration providerLatency = Duration.between(
                command.requestedAt(), receipt.confirmedAt());
        boolean contemporaneousNonConfirmation =
                receipt.state()
                        != TestSuiteStabilityPhysicalAttemptObservationReceipt.State.NOT_OBSERVED
                        && receipt.state()
                        != TestSuiteStabilityPhysicalAttemptObservationReceipt.State.INDETERMINATE
                        || !receipt.stateEffectiveAt().isBefore(command.requestedAt());
        if (observation.isBefore(command.requestedAt())
                || providerLatency.isNegative()
                || receipt.confirmedAt().isAfter(command.confirmationDeadlineAt())
                || receipt.confirmedAt().isAfter(observation.plus(maximumFutureSkew))
                || providerLatency.compareTo(descriptor.maximumObservationLatency()) > 0
                || receipt.stateEffectiveAt().isBefore(
                command.startCommand().requestedAt())
                || !contemporaneousNonConfirmation) {
            throw failed(FailureReason.TIME_INVALID);
        }
    }

    /**
     * Builds deterministic bytes signed by an observation provider.
     *
     * @param objectMapper canonical protocol mapper
     * @param schemaVersion exact attestation schema
     * @param receipt complete semantic observation receipt
     * @param keyId provider key id in the detached envelope
     * @return deterministic JSON bytes
     */
    public static byte[] signingBytes(
            ObjectMapper objectMapper,
            String schemaVersion,
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt,
            String keyId) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", schemaVersion);
        material.put("keyId", keyId);
        material.put("receipt", Objects.requireNonNull(receipt, "receipt")
                .canonicalMaterial());
        try {
            ObjectMapper canonical = Objects.requireNonNull(objectMapper, "objectMapper")
                    .copy()
                    .registerModule(new JavaTimeModule())
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
            canonical.setConfig(canonical.getSerializationConfig()
                    .with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));
            return canonical.writeValueAsBytes(Map.copyOf(material));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Physical-attempt observation signing material is invalid");
        }
    }

    private boolean verifySignature(
            PublicKey publicKey,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attestation) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(signingBytes(objectMapper, attestation.schemaVersion(),
                    attestation.receipt(), attestation.keyId()));
            return verifier.verify(Base64.getUrlDecoder().decode(attestation.signature()));
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            return false;
        }
    }

    private static VerificationException failed(FailureReason reason) {
        return new VerificationException(reason);
    }

    /** Closed verification failure vocabulary safe for metrics and logs. */
    public enum FailureReason {
        /** Nested reserved physical identity no longer matches canonical material. */
        IDENTITY_INTEGRITY_INVALID,
        /** Original content-addressed start command no longer matches canonical material. */
        START_COMMAND_INTEGRITY_INVALID,
        /** Observation command no longer matches its canonical material. */
        OBSERVATION_COMMAND_INTEGRITY_INVALID,
        /** Receipt does not bind the exact observation, start, attempt, or queue epoch. */
        COMMAND_BINDING_INVALID,
        /** Descriptor, provider, deployment, key, or isolation mode is inconsistent. */
        PROVIDER_BINDING_INVALID,
        /** A positive observation names a different previously confirmed process. */
        PROCESS_BINDING_INVALID,
        /** A positive observation regresses a previously accepted attempt state fence. */
        STATE_ROLLBACK,
        /** Provider observation or trust time lies outside the accepted live window. */
        TIME_INVALID,
        /** No exact live provider trust key authorizes this attestation. */
        TRUST_INVALID,
        /** Detached Ed25519 signature did not verify. */
        SIGNATURE_INVALID
    }

    /** Stable verification exception without provider or cryptographic diagnostics. */
    public static final class VerificationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        /** Closed failure reason safe for metrics and logs. */
        private final FailureReason reason;

        private VerificationException(FailureReason reason) {
            super("Suite-stability physical-attempt observation verification failed: "
                    + Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        /**
         * Returns the stable failure class.
         *
         * @return exact closed verification failure
         */
        public FailureReason reason() {
            return reason;
        }
    }

    /**
     * One pinned provider/deployment Ed25519 observation-verification key.
     *
     * @param providerId exact isolated-runtime provider
     * @param deploymentId exact workload generation
     * @param keyId unique key generation
     * @param publicKey Ed25519 verification key
     * @param notBefore inclusive confirmation validity start
     * @param notAfter inclusive live verification expiry
     */
    public record TrustKey(
            String providerId,
            String deploymentId,
            String keyId,
            PublicKey publicKey,
            Instant notBefore,
            Instant notAfter) {

        /** Validates exact identity, Ed25519 key type, and a millisecond time window. */
        public TrustKey {
            providerId = required(providerId, "providerId");
            deploymentId = required(deploymentId, "deploymentId");
            keyId = required(keyId, "keyId");
            publicKey = Objects.requireNonNull(publicKey, "publicKey");
            notBefore = Objects.requireNonNull(notBefore, "notBefore");
            notAfter = Objects.requireNonNull(notAfter, "notAfter");
            if (!Set.of("Ed25519", "EdDSA").contains(publicKey.getAlgorithm())
                    || notBefore.getNano() % 1_000_000 != 0
                    || notAfter.getNano() % 1_000_000 != 0
                    || !notAfter.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt observation trust key");
            }
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
