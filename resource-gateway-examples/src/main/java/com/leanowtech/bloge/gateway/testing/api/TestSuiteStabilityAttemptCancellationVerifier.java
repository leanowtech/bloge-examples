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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Verifies challenge-bound provider cancellation attestations against pinned Ed25519 trust.
 *
 * <p>Verification re-derives command content identity, rebinds provider/deployment/key and the
 * complete durable attempt fence, enforces provider and caller time windows, verifies the detached
 * signature, and only then exposes the receipt. Provider exception text and cryptographic causes
 * are intentionally collapsed into a closed failure reason.</p>
 */
public final class TestSuiteStabilityAttemptCancellationVerifier {

    private final ObjectMapper objectMapper;
    private final Map<String, TrustKey> trustKeys;
    private final Duration maximumFutureSkew;

    /**
     * Creates an exact pinned-trust verifier for live cancellation responses.
     *
     * @param objectMapper canonical protocol mapper shared with command creation and signing
     * @param trustKeys exact provider/deployment/key trust inventory
     * @param maximumFutureSkew tolerated provider clock lead from zero through 30 seconds
     */
    public TestSuiteStabilityAttemptCancellationVerifier(
            ObjectMapper objectMapper,
            Collection<TrustKey> trustKeys,
            Duration maximumFutureSkew) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Collection<TrustKey> requiredKeys = Objects.requireNonNull(trustKeys, "trustKeys");
        this.trustKeys = requiredKeys.stream().collect(Collectors.toUnmodifiableMap(
                TrustKey::keyId, Function.identity(), (left, right) -> {
                    throw new IllegalArgumentException(
                            "Duplicate suite-stability cancellation trust key id");
                }));
        if (this.trustKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "Suite-stability cancellation trust inventory is empty");
        }
        this.maximumFutureSkew = Objects.requireNonNull(maximumFutureSkew,
                "maximumFutureSkew");
        if (maximumFutureSkew.isNegative()
                || maximumFutureSkew.compareTo(Duration.ofSeconds(30)) > 0
                || !maximumFutureSkew.equals(Duration.ofMillis(maximumFutureSkew.toMillis()))) {
            throw new IllegalArgumentException(
                    "Suite-stability cancellation future skew is invalid");
        }
    }

    /**
     * Verifies one live provider response and returns its exact receipt.
     *
     * @param command command originally sent to the provider
     * @param descriptor descriptor obtained from that provider before invocation
     * @param attestation untrusted provider response
     * @param observedAt caller time after the response was received
     * @return fully verified receipt, which may still be a signed rejection/non-match
     * @throws VerificationException for any structural, binding, time, trust, or signature failure
     */
    public TestSuiteStabilityAttemptCancellationReceipt verify(
            TestSuiteStabilityAttemptCancellationCommand command,
            TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor,
            TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation,
            Instant observedAt) {
        TestSuiteStabilityAttemptCancellationCommand requiredCommand =
                Objects.requireNonNull(command, "command");
        TestSuiteStabilityAttemptCancellationAuthority.Descriptor requiredDescriptor =
                Objects.requireNonNull(descriptor, "descriptor");
        TestSuiteStabilityAttemptCancellationReceipt.Attestation requiredAttestation =
                Objects.requireNonNull(attestation, "attestation");
        Instant observation = Objects.requireNonNull(observedAt, "observedAt");
        if (observation.getNano() % 1_000_000 != 0) {
            throw failed(FailureReason.TIME_INVALID);
        }

        String derivedFingerprint = ProtocolFingerprint.of(
                objectMapper, requiredCommand.canonicalMaterial());
        if (!derivedFingerprint.equals(requiredCommand.commandFingerprint())
                || !requiredCommand.commandId().equals("stability-attempt-cancel-"
                + derivedFingerprint.substring("sha256:".length()))) {
            throw failed(FailureReason.COMMAND_INTEGRITY_INVALID);
        }
        TestSuiteStabilityAttemptCancellationReceipt receipt =
                requiredAttestation.receipt();
        if (!requiredDescriptor.available()
                || !requiredDescriptor.providerId().equals(receipt.providerId())
                || !requiredDescriptor.deploymentId().equals(receipt.deploymentId())
                || !requiredDescriptor.keyId().equals(requiredAttestation.keyId())
                || !requiredDescriptor.isolationModes().contains(receipt.isolationMode())) {
            throw failed(FailureReason.PROVIDER_BINDING_INVALID);
        }
        if (!requiredCommand.commandId().equals(receipt.commandId())
                || !requiredCommand.commandFingerprint().equals(receipt.commandFingerprint())
                || !requiredCommand.attemptId().equals(receipt.attemptId())
                || requiredCommand.leaseEpoch() != receipt.leaseEpoch()) {
            throw failed(FailureReason.COMMAND_BINDING_INVALID);
        }
        Duration requestedWindow = Duration.between(
                requiredCommand.requestedAt(), receipt.confirmedAt());
        if (observation.isBefore(requiredCommand.requestedAt())
                || requestedWindow.isNegative()
                || receipt.confirmedAt().isAfter(requiredCommand.confirmationDeadlineAt())
                || receipt.confirmedAt().isAfter(observation.plus(maximumFutureSkew))
                || Duration.between(requiredCommand.requestedAt(), receipt.confirmedAt())
                .compareTo(requiredDescriptor.maximumConfirmationLatency()) > 0) {
            throw failed(FailureReason.TIME_INVALID);
        }

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

    /**
     * Canonical bytes that a provider signs for one attestation.
     *
     * @param objectMapper canonical protocol mapper
     * @param schemaVersion exact attestation schema
     * @param receipt complete semantic receipt
     * @param keyId provider key id placed in the envelope
     * @return deterministic JSON bytes
     */
    public static byte[] signingBytes(
            ObjectMapper objectMapper,
            String schemaVersion,
            TestSuiteStabilityAttemptCancellationReceipt receipt,
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
                    "Suite-stability cancellation signing material is invalid");
        }
    }

    private boolean verifySignature(
            PublicKey publicKey,
            TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation) {
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
        /** The content-addressed command no longer matches its canonical material. */
        COMMAND_INTEGRITY_INVALID,
        /** The receipt does not bind the exact command, attempt, or lease epoch. */
        COMMAND_BINDING_INVALID,
        /** Descriptor, provider, deployment, key, or isolation mode is inconsistent. */
        PROVIDER_BINDING_INVALID,
        /** Confirmation or trust time is outside an accepted live window. */
        TIME_INVALID,
        /** No exact live provider trust key authorizes the attestation. */
        TRUST_INVALID,
        /** The detached Ed25519 signature did not verify. */
        SIGNATURE_INVALID
    }

    /** Stable verification exception that does not retain provider or crypto diagnostics. */
    public static final class VerificationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        /** Closed failure reason safe for metrics and logs. */
        private final FailureReason reason;

        private VerificationException(FailureReason reason) {
            super("Suite-stability attempt cancellation verification failed: "
                    + Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        /**
         * Returns the stable failure class without retaining cryptographic diagnostics.
         *
         * @return exact closed verification failure
         */
        public FailureReason reason() {
            return reason;
        }
    }

    /**
     * One pinned provider/deployment Ed25519 trust generation.
     *
     * @param providerId exact authority provider
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

        /** Validates exact identity, Ed25519 key type, and a non-empty millisecond time window. */
        public TrustKey {
            providerId = required(providerId, "providerId");
            deploymentId = required(deploymentId, "deploymentId");
            keyId = required(keyId, "keyId");
            publicKey = Objects.requireNonNull(publicKey, "publicKey");
            notBefore = Objects.requireNonNull(notBefore, "notBefore");
            notAfter = Objects.requireNonNull(notAfter, "notAfter");
            if (!(SetSupport.ED25519_ALGORITHMS.contains(publicKey.getAlgorithm()))
                    || notBefore.getNano() % 1_000_000 != 0
                    || notAfter.getNano() % 1_000_000 != 0
                    || !notAfter.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability cancellation trust key");
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

    private static final class SetSupport {
        private static final java.util.Set<String> ED25519_ALGORITHMS =
                java.util.Set.of("Ed25519", "EdDSA");

        private SetSupport() {
        }
    }
}
