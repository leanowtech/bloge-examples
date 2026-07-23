package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * Canonical sealing and independent verification for capability observations.
 *
 * <p>{@link #seal} is producer and fixture tooling, not an admission shortcut. Resource Gateway
 * admits an envelope only after {@link #verify} succeeds against an operator-owned authority key;
 * a key id or issuer copied from the request is never a trust root.</p>
 */
public final class CapabilityObservationIntegrity {
    /** Maximum canonical material size. */
    public static final int MAXIMUM_MATERIAL_BYTES = 768 * 1024;
    /** Maximum complete observation envelope size. */
    public static final int MAXIMUM_ENVELOPE_BYTES = 1024 * 1024;
    /** Signature domain shared by independent producers and consumers. */
    public static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_CAPABILITY_OBSERVATION_V1";

    private final ObjectMapper mapper;

    /**
     * Creates the canonical observation integrity boundary.
     *
     * @param mapper canonical protocol mapper
     */
    public CapabilityObservationIntegrity(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Produces a signed observation for an external ingestion producer.
     *
     * @param material immutable payload-free observation facts
     * @param signer external Ed25519 observation authority
     * @param issuer exact authority identity governed by admission policy
     * @return content-addressed signed envelope
     */
    public CapabilityObservationEnvelope seal(
            CapabilityObservationEnvelope.Material material,
            VisualEvidenceSigner signer,
            String issuer) {
        Objects.requireNonNull(material, "material");
        VisualEvidenceSigner authority = Objects.requireNonNull(signer, "signer");
        if (!authority.available()) {
            throw new IllegalArgumentException("observation signing authority is unavailable");
        }
        String exactIssuer = required(issuer, "issuer");
        String materialFingerprint = materialFingerprint(material);
        VisualRunEvidenceSeal signed = authority.seal(materialFingerprint);
        var seal = new CapabilityObservationEnvelope.Seal(
                materialFingerprint, signed.algorithm(), signed.keyId(), exactIssuer,
                signed.signedAt(), signed.signature());
        return new CapabilityObservationEnvelope(
                "", envelopeFingerprint(material, seal), material, seal);
    }

    /**
     * Recomputes both canonical fingerprints without making an authority decision.
     *
     * @param envelope untrusted decoded envelope
     * @return true only when material and envelope content addresses are exact
     */
    public boolean canonicalFingerprintVerified(CapabilityObservationEnvelope envelope) {
        if (envelope == null) {
            return false;
        }
        try {
            return envelope.seal().materialFingerprint().equals(
                    materialFingerprint(envelope.material()))
                    && envelope.observationFingerprint().equals(
                    envelopeFingerprint(envelope.material(), envelope.seal()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Independently verifies canonical content, authority lifecycle, and signature.
     *
     * @param envelope untrusted signed observation
     * @param authorityKey exact key selected by operator-owned admission policy
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            CapabilityObservationEnvelope envelope,
            AuthorityKey authorityKey) {
        Coordinates coordinates = Coordinates.from(envelope);
        if (envelope == null) {
            return result(Outcome.INVALID, "OBSERVATION_MISSING", coordinates);
        }
        if (!canonicalFingerprintVerified(envelope)) {
            return result(Outcome.INVALID,
                    "OBSERVATION_FINGERPRINT_INVALID", coordinates);
        }
        if (authorityKey == null) {
            return result(Outcome.KEY_UNAVAILABLE,
                    "AUTHORITY_KEY_UNAVAILABLE", coordinates);
        }
        CapabilityObservationEnvelope.Seal seal = envelope.seal();
        if (!authorityKey.keyRef().id().equals(seal.keyId())
                || !authorityKey.issuer().equals(seal.issuer())) {
            return result(Outcome.POLICY_REJECTED,
                    "AUTHORITY_IDENTITY_MISMATCH", coordinates);
        }
        if (!authorityKey.verificationAllowed()
                || !"Ed25519".equals(authorityKey.algorithm())
                || !authorityKey.algorithm().equals(seal.algorithm())) {
            return result(Outcome.POLICY_REJECTED,
                    "AUTHORITY_KEY_POLICY_REJECTED", coordinates);
        }
        if (seal.signedAt().isBefore(authorityKey.notBefore())
                || !seal.signedAt().isBefore(authorityKey.notAfter())) {
            return result(Outcome.POLICY_REJECTED,
                    "AUTHORITY_KEY_OUTSIDE_VALIDITY", coordinates);
        }
        try {
            if (!verifySignature(seal, authorityKey.encodedPublicKey())) {
                return result(Outcome.INVALID,
                        "OBSERVATION_SIGNATURE_INVALID", coordinates);
            }
        } catch (RuntimeException invalid) {
            return result(Outcome.INVALID,
                    "OBSERVATION_SIGNATURE_MATERIAL_INVALID", coordinates);
        }
        return result(Outcome.VERIFIED, "VERIFIED", coordinates);
    }

    private boolean verifySignature(
            CapabilityObservationEnvelope.Seal seal,
            String encodedPublicKey) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(encodedPublicKey))));
            verifier.update(seal.materialFingerprint().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(seal.signature()));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("observation signature is invalid", invalid);
        }
    }

    private String materialFingerprint(CapabilityObservationEnvelope.Material material) {
        return VisualBundleFingerprint.fromCanonicalValue(
                mapper,
                new SignatureMaterial(
                        SIGNATURE_DOMAIN,
                        CapabilityObservationEnvelope.SCHEMA_VERSION,
                        material),
                MAXIMUM_MATERIAL_BYTES);
    }

    private String envelopeFingerprint(
            CapabilityObservationEnvelope.Material material,
            CapabilityObservationEnvelope.Seal seal) {
        return VisualBundleFingerprint.fromCanonicalValue(
                mapper,
                new EnvelopeMaterial(
                        CapabilityObservationEnvelope.SCHEMA_VERSION,
                        "",
                        material,
                        seal),
                MAXIMUM_ENVELOPE_BYTES);
    }

    /** Closed independent verification outcomes. */
    public enum Outcome {
        /** Canonical content, key policy, and signature all passed. */
        VERIFIED,
        /** Structure, content address, key material, or signature is invalid. */
        INVALID,
        /** No exact authority key was supplied. */
        KEY_UNAVAILABLE,
        /** Authority identity, algorithm, lifecycle, or validity rejected the envelope. */
        POLICY_REJECTED
    }

    /**
     * Operator-owned public verification key for one observation producer.
     *
     * @param keyRef exact immutable authority-key artifact
     * @param algorithm fixed signature algorithm
     * @param encodedPublicKey canonical base64 X.509 SubjectPublicKeyInfo bytes
     * @param issuer exact producer authority
     * @param notBefore inclusive signing-time bound
     * @param notAfter exclusive signing-time bound
     * @param state current key lifecycle
     */
    public record AuthorityKey(
            MirrorArtifactRef keyRef,
            String algorithm,
            String encodedPublicKey,
            String issuer,
            Instant notBefore,
            Instant notAfter,
            KeyState state
    ) {
        /** Validates the externally provisioned key policy. */
        public AuthorityKey {
            keyRef = Objects.requireNonNull(keyRef, "keyRef");
            if (!"OBSERVATION_AUTHORITY_KEY".equals(keyRef.kind())) {
                throw new IllegalArgumentException(
                        "observation authority must use OBSERVATION_AUTHORITY_KEY");
            }
            algorithm = required(algorithm, "algorithm");
            encodedPublicKey = canonicalBase64(
                    required(encodedPublicKey, "encodedPublicKey"));
            issuer = required(issuer, "issuer");
            notBefore = Objects.requireNonNull(notBefore, "notBefore");
            notAfter = Objects.requireNonNull(notAfter, "notAfter");
            state = Objects.requireNonNull(state, "state");
            if (!"Ed25519".equals(algorithm) || !notAfter.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "observation authority key policy is invalid");
            }
        }

        /**
         * Reports whether the key may verify historical observations.
         *
         * @return true for active and retired keys
         */
        public boolean verificationAllowed() {
            return state == KeyState.ACTIVE || state == KeyState.RETIRED;
        }
    }

    /** Observation authority key lifecycle. */
    public enum KeyState {
        /** Key may sign and verify observations. */
        ACTIVE,
        /** Key may verify historical observations but must not sign new ones. */
        RETIRED,
        /** Key must not be trusted. */
        REVOKED
    }

    /**
     * Payload-free verification result.
     *
     * @param outcome closed verification outcome
     * @param reasonCode stable machine-readable reason
     * @param observationId observation id, or blank when unavailable
     * @param observationFingerprint envelope fingerprint, or blank when unavailable
     * @param keyId producer key id, or blank when unavailable
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String observationId,
            String observationFingerprint,
            String keyId
    ) {
        /** Validates log-safe result coordinates. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = required(reasonCode, "reasonCode");
            observationId = normalized(observationId);
            observationFingerprint = normalized(observationFingerprint);
            keyId = normalized(keyId);
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "observation verification reason is invalid");
            }
        }

        /**
         * Reports whether every independent integrity check passed.
         *
         * @return true only for a verified envelope
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    private static VerificationResult result(
            Outcome outcome, String reasonCode, Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reasonCode,
                coordinates.observationId(),
                coordinates.observationFingerprint(),
                coordinates.keyId());
    }

    private static String required(String value, String field) {
        String exact = normalized(value);
        if (exact.isBlank() || exact.length() > 4096) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String canonicalBase64(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 0
                    || !value.equals(Base64.getEncoder().encodeToString(decoded))) {
                throw new IllegalArgumentException(
                        "observation authority key is not canonical base64");
            }
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "observation authority key must be canonical base64", invalid);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record SignatureMaterial(
            String domain,
            String schemaVersion,
            CapabilityObservationEnvelope.Material material
    ) {
    }

    private record EnvelopeMaterial(
            String schemaVersion,
            String observationFingerprint,
            CapabilityObservationEnvelope.Material material,
            CapabilityObservationEnvelope.Seal seal
    ) {
    }

    private record Coordinates(
            String observationId,
            String observationFingerprint,
            String keyId
    ) {
        private static Coordinates from(CapabilityObservationEnvelope envelope) {
            return envelope == null
                    ? new Coordinates("", "", "")
                    : new Coordinates(
                    envelope.material().observationId(),
                    envelope.observationFingerprint(),
                    envelope.seal().keyId());
        }
    }
}
