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
 * Verifies challenge-bound physical-attempt start attestations against pinned Ed25519 trust.
 *
 * <p>Verification re-derives the nested physical identity and start command, rebinds the provider,
 * deployment, key, isolation mode, attempt, lease epoch, and time window, then verifies the
 * detached signature. Provider exceptions and cryptographic details are collapsed into closed
 * reasons suitable for bounded diagnostics.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptStartVerifier {

    private final ObjectMapper objectMapper;
    private final Map<String, TrustKey> trustKeys;
    private final Duration maximumFutureSkew;

    /**
     * Creates an exact pinned-trust verifier for live start responses.
     *
     * @param objectMapper canonical mapper shared with command creation and provider signing
     * @param trustKeys exact provider/deployment/key trust inventory
     * @param maximumFutureSkew tolerated provider clock lead from zero through 30 seconds
     */
    public TestSuiteStabilityPhysicalAttemptStartVerifier(
            ObjectMapper objectMapper,
            Collection<TrustKey> trustKeys,
            Duration maximumFutureSkew) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Collection<TrustKey> requiredKeys = Objects.requireNonNull(trustKeys, "trustKeys");
        this.trustKeys = requiredKeys.stream().collect(Collectors.toUnmodifiableMap(
                TrustKey::keyId, Function.identity(), (left, right) -> {
                    throw new IllegalArgumentException(
                            "Duplicate physical-attempt start trust key id");
                }));
        if (this.trustKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "Physical-attempt start trust inventory is empty");
        }
        this.maximumFutureSkew = Objects.requireNonNull(
                maximumFutureSkew, "maximumFutureSkew");
        if (maximumFutureSkew.isNegative()
                || maximumFutureSkew.compareTo(Duration.ofSeconds(30)) > 0
                || !maximumFutureSkew.equals(
                Duration.ofMillis(maximumFutureSkew.toMillis()))) {
            throw new IllegalArgumentException(
                    "Physical-attempt start future skew is invalid");
        }
    }

    /**
     * Verifies one live provider response and returns its exact semantic receipt.
     *
     * @param command command originally sent to the provider
     * @param descriptor descriptor obtained from that provider before invocation
     * @param attestation untrusted detached provider response
     * @param observedAt caller time after the response was received
     * @return fully verified confirming or non-confirming start receipt
     * @throws VerificationException for structural, binding, time, trust, or signature failure
     */
    public TestSuiteStabilityPhysicalAttemptStartReceipt verify(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor,
            TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation,
            Instant observedAt) {
        TestSuiteStabilityPhysicalAttemptStartCommand requiredCommand =
                Objects.requireNonNull(command, "command");
        TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor requiredDescriptor =
                Objects.requireNonNull(descriptor, "descriptor");
        TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation requiredAttestation =
                Objects.requireNonNull(attestation, "attestation");
        Instant observation = Objects.requireNonNull(observedAt, "observedAt");
        if (observation.getNano() % 1_000_000 != 0) {
            throw failed(FailureReason.TIME_INVALID);
        }

        TestSuiteStabilityPhysicalAttemptIdentity identity = requiredCommand.identity();
        String derivedIdentity = ProtocolFingerprint.of(
                objectMapper, identity.canonicalMaterial());
        if (!derivedIdentity.equals(identity.identityFingerprint())
                || !identity.attemptId().equals("stability-attempt-"
                + derivedIdentity.substring("sha256:".length()))) {
            throw failed(FailureReason.IDENTITY_INTEGRITY_INVALID);
        }
        String derivedCommand = ProtocolFingerprint.of(
                objectMapper, requiredCommand.canonicalMaterial());
        if (!derivedCommand.equals(requiredCommand.commandFingerprint())
                || !requiredCommand.commandId().equals("stability-attempt-start-"
                + derivedCommand.substring("sha256:".length()))) {
            throw failed(FailureReason.COMMAND_INTEGRITY_INVALID);
        }

        TestSuiteStabilityPhysicalAttemptStartReceipt receipt =
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
        if (!requiredCommand.commandId().equals(receipt.commandId())
                || !requiredCommand.commandFingerprint().equals(receipt.commandFingerprint())
                || !identity.attemptId().equals(receipt.attemptId())
                || !identity.identityFingerprint().equals(receipt.identityFingerprint())
                || identity.leaseEpoch() != receipt.leaseEpoch()) {
            throw failed(FailureReason.COMMAND_BINDING_INVALID);
        }

        Duration providerLatency = Duration.between(
                requiredCommand.requestedAt(), receipt.confirmedAt());
        if (observation.isBefore(requiredCommand.requestedAt())
                || providerLatency.isNegative()
                || receipt.confirmedAt().isAfter(requiredCommand.confirmationDeadlineAt())
                || receipt.confirmedAt().isAfter(observation.plus(maximumFutureSkew))
                || providerLatency.compareTo(requiredDescriptor.maximumStartLatency()) > 0) {
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
     * Builds deterministic bytes signed by a start provider.
     *
     * @param objectMapper canonical protocol mapper
     * @param schemaVersion exact attestation schema
     * @param receipt complete semantic start receipt
     * @param keyId provider key id in the detached envelope
     * @return deterministic JSON bytes
     */
    public static byte[] signingBytes(
            ObjectMapper objectMapper,
            String schemaVersion,
            TestSuiteStabilityPhysicalAttemptStartReceipt receipt,
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
                    "Physical-attempt start signing material is invalid");
        }
    }

    private boolean verifySignature(
            PublicKey publicKey,
            TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation) {
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
        /** Nested reserved physical identity no longer matches its canonical material. */
        IDENTITY_INTEGRITY_INVALID,
        /** Content-addressed start command no longer matches its canonical material. */
        COMMAND_INTEGRITY_INVALID,
        /** Receipt does not bind the exact command, attempt identity, or lease epoch. */
        COMMAND_BINDING_INVALID,
        /** Descriptor, provider, deployment, key, or isolation mode is inconsistent. */
        PROVIDER_BINDING_INVALID,
        /** Provider confirmation or trust time is outside an accepted live window. */
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
            super("Suite-stability physical-attempt start verification failed: "
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
     * One pinned provider/deployment Ed25519 start-verification key.
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
                        "Invalid physical-attempt start trust key");
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
