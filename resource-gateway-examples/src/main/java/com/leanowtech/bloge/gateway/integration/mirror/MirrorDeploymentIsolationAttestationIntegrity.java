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
 * Canonical sealing and independent verification for deployment-isolation attestations.
 *
 * <p>Resource Gateway uses only {@link #verify}; {@link #seal} exists so an external SRE/security
 * issuer and compatibility-fixture tooling can produce the same protocol material. Possession of
 * the Resource Gateway evidence-signing key is not deployment authority: consumers must pin a
 * separate {@link AuthorityKey} issued for the attestation's named authority.</p>
 */
public final class MirrorDeploymentIsolationAttestationIntegrity {
    /** Maximum canonical signed statement size. */
    public static final int MAXIMUM_MATERIAL_BYTES = 512 * 1024;
    /** Maximum canonical complete attestation size. */
    public static final int MAXIMUM_ATTESTATION_BYTES = 1024 * 1024;
    /** Domain separator for isolation statements. */
    public static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_MIRROR_DEPLOYMENT_ISOLATION_V1";

    private final ObjectMapper mapper;

    /**
     * Creates the canonical sealing and verification boundary.
     *
     * @param mapper canonical protocol mapper
     */
    public MirrorDeploymentIsolationAttestationIntegrity(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Produces a signed artifact for an external isolation authority.
     *
     * @param material externally observed immutable deployment statement
     * @param authority external Ed25519 signing authority
     * @return content-addressed signed attestation
     */
    public MirrorDeploymentIsolationAttestation seal(
            MirrorDeploymentIsolationAttestation.Material material,
            VisualEvidenceSigner authority) {
        Objects.requireNonNull(material, "material");
        VisualEvidenceSigner signer = Objects.requireNonNull(authority, "authority");
        if (!signer.available()) {
            throw new IllegalArgumentException("deployment isolation authority is unavailable");
        }
        String materialFingerprint = materialFingerprint(material);
        VisualRunEvidenceSeal signed = signer.seal(materialFingerprint);
        MirrorDeploymentIsolationAttestation.Seal seal =
                new MirrorDeploymentIsolationAttestation.Seal(materialFingerprint,
                        signed.algorithm(), signed.keyId(), signed.signedAt(), signed.signature());
        String attestationFingerprint = attestationFingerprint(material, seal);
        return new MirrorDeploymentIsolationAttestation("", attestationFingerprint,
                material, seal);
    }

    /**
     * Independently verifies content, authority, runtime identity, and the complete run window.
     *
     * @param attestation signed deployment artifact
     * @param authorityKey externally pinned authority verification key
     * @param expectedDeployment immutable local runtime identity
     * @param executionStartedAt actual mirror execution start
     * @param executionCompletedAt actual mirror execution completion
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            MirrorDeploymentIsolationAttestation attestation,
            AuthorityKey authorityKey,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity expectedDeployment,
            Instant executionStartedAt,
            Instant executionCompletedAt) {
        Coordinates coordinates = Coordinates.from(attestation);
        if (attestation == null) {
            return result(Outcome.INVALID, "ATTESTATION_MISSING", coordinates);
        }
        try {
            if (!attestation.seal().materialFingerprint()
                    .equals(materialFingerprint(attestation.material()))
                    || !attestation.attestationFingerprint().equals(
                    attestationFingerprint(attestation.material(), attestation.seal()))) {
                return result(Outcome.INVALID, "ATTESTATION_FINGERPRINT_INVALID", coordinates);
            }
        } catch (RuntimeException invalid) {
            return result(Outcome.INVALID, "ATTESTATION_MATERIAL_INVALID", coordinates);
        }
        if (authorityKey == null) {
            return result(Outcome.KEY_UNAVAILABLE, "AUTHORITY_KEY_UNAVAILABLE", coordinates);
        }
        if (!authorityKey.keyId().equals(attestation.seal().keyId())
                || !authorityKey.issuer().equals(attestation.material().issuer())) {
            return result(Outcome.POLICY_REJECTED, "AUTHORITY_IDENTITY_MISMATCH", coordinates);
        }
        if (!authorityKey.verificationAllowed()
                || !"Ed25519".equals(authorityKey.algorithm())
                || !authorityKey.algorithm().equals(attestation.seal().algorithm())) {
            return result(Outcome.POLICY_REJECTED, "AUTHORITY_KEY_POLICY_REJECTED", coordinates);
        }
        Instant signedAt = attestation.seal().signedAt();
        if (signedAt.isBefore(authorityKey.notBefore())
                || !signedAt.isBefore(authorityKey.notAfter())) {
            return result(Outcome.POLICY_REJECTED,
                    "AUTHORITY_KEY_OUTSIDE_VALIDITY", coordinates);
        }
        if (expectedDeployment == null
                || !expectedDeployment.equals(attestation.material().deployment())) {
            return result(Outcome.IDENTITY_MISMATCH,
                    "DEPLOYMENT_IDENTITY_MISMATCH", coordinates);
        }
        if (!validExecutionWindow(attestation, executionStartedAt, executionCompletedAt)) {
            return result(Outcome.WINDOW_REJECTED,
                    "EXECUTION_OUTSIDE_ATTESTATION_WINDOW", coordinates);
        }
        try {
            if (!verifySignature(attestation.seal(), authorityKey.encodedPublicKey())) {
                return result(Outcome.INVALID, "ATTESTATION_SIGNATURE_INVALID", coordinates);
            }
        } catch (RuntimeException invalid) {
            return result(Outcome.INVALID, "ATTESTATION_SIGNATURE_MATERIAL_INVALID", coordinates);
        }
        return result(Outcome.VERIFIED, "VERIFIED", coordinates);
    }

    private boolean validExecutionWindow(
            MirrorDeploymentIsolationAttestation attestation,
            Instant executionStartedAt,
            Instant executionCompletedAt) {
        if (executionStartedAt == null || executionCompletedAt == null
                || executionCompletedAt.isBefore(executionStartedAt)) {
            return false;
        }
        Instant effectiveFrom = attestation.material().validFrom().isAfter(
                attestation.seal().signedAt())
                ? attestation.material().validFrom() : attestation.seal().signedAt();
        return !executionStartedAt.isBefore(effectiveFrom)
                && executionCompletedAt.isBefore(attestation.material().expiresAt());
    }

    private boolean verifySignature(
            MirrorDeploymentIsolationAttestation.Seal seal,
            String encodedPublicKey) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(encodedPublicKey))));
            verifier.update(seal.materialFingerprint().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(seal.signature()));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("deployment isolation signature is invalid", invalid);
        }
    }

    private String materialFingerprint(
            MirrorDeploymentIsolationAttestation.Material material) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper,
                new SignatureMaterial(SIGNATURE_DOMAIN,
                        MirrorDeploymentIsolationAttestation.SCHEMA_VERSION, material),
                MAXIMUM_MATERIAL_BYTES);
    }

    private String attestationFingerprint(
            MirrorDeploymentIsolationAttestation.Material material,
            MirrorDeploymentIsolationAttestation.Seal seal) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper,
                new AttestationMaterial(MirrorDeploymentIsolationAttestation.SCHEMA_VERSION,
                        "", material, seal), MAXIMUM_ATTESTATION_BYTES);
    }

    /** Bounded verification outcome. */
    public enum Outcome {
        /** Every cryptographic, identity, policy, and time-window check passed. */
        VERIFIED,
        /** The artifact structure, fingerprint, or signature is invalid. */
        INVALID,
        /** No exact externally pinned authority key was supplied. */
        KEY_UNAVAILABLE,
        /** Authority identity, key lifecycle, or algorithm policy rejected the artifact. */
        POLICY_REJECTED,
        /** Local immutable runtime coordinates differ from the attested deployment. */
        IDENTITY_MISMATCH,
        /** The mirror execution did not fit wholly within the signed validity window. */
        WINDOW_REJECTED
    }

    /**
     * Externally pinned isolation-authority key policy.
     *
     * @param keyId stable authority key id
     * @param algorithm fixed signature algorithm
     * @param encodedPublicKey base64 X.509 SubjectPublicKeyInfo bytes
     * @param issuer exact SRE/security authority identity
     * @param notBefore inclusive signing-time bound
     * @param notAfter exclusive signing-time bound
     * @param state current lifecycle state
     */
    public record AuthorityKey(
            String keyId,
            String algorithm,
            String encodedPublicKey,
            String issuer,
            Instant notBefore,
            Instant notAfter,
            KeyState state
    ) {
        /** Validates the externally provisioned trust policy. */
        public AuthorityKey {
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            encodedPublicKey = normalized(encodedPublicKey);
            issuer = normalized(issuer);
            notBefore = Objects.requireNonNull(notBefore, "notBefore");
            notAfter = Objects.requireNonNull(notAfter, "notAfter");
            state = Objects.requireNonNull(state, "state");
            if (keyId.isBlank() || encodedPublicKey.isBlank() || issuer.isBlank()
                    || !"Ed25519".equals(algorithm) || !notAfter.isAfter(notBefore)) {
                throw new IllegalArgumentException("deployment isolation authority key is invalid");
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(encodedPublicKey);
                if (decoded.length == 0 || !encodedPublicKey.equals(
                        Base64.getEncoder().encodeToString(decoded))) {
                    throw new IllegalArgumentException("authority public key is not canonical");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "authority public key must be canonical base64", invalid);
            }
        }

        /**
         * Reports whether the current lifecycle state permits historical verification.
         *
         * @return whether historical verification is allowed by the current key state
         */
        public boolean verificationAllowed() {
            return state == KeyState.ACTIVE || state == KeyState.RETIRED;
        }
    }

    /** Isolation-authority key lifecycle states. */
    public enum KeyState {
        /** Key may sign new attestations and verify historical attestations. */
        ACTIVE,
        /** Key may verify historical attestations but must not sign new ones. */
        RETIRED,
        /** Key must not be trusted for verification. */
        REVOKED
    }

    /**
     * Payload-free verification result.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param attestationId artifact identity, or blank when unavailable
     * @param attestationFingerprint artifact fingerprint, or blank when unavailable
     * @param keyId authority key id, or blank when unavailable
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String attestationId,
            String attestationFingerprint,
            String keyId
    ) {
        /** Validates log-safe result coordinates. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = normalized(reasonCode);
            attestationId = normalized(attestationId);
            attestationFingerprint = normalized(attestationFingerprint);
            keyId = normalized(keyId);
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("deployment isolation reason code is invalid");
            }
        }

        /**
         * Reports whether every independent verification step passed.
         *
         * @return true only when every independent verification step passed
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    private static VerificationResult result(
            Outcome outcome, String reason, Coordinates coordinates) {
        return new VerificationResult(outcome, reason, coordinates.attestationId(),
                coordinates.attestationFingerprint(), coordinates.keyId());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record SignatureMaterial(
            String domain,
            String schemaVersion,
            MirrorDeploymentIsolationAttestation.Material material
    ) {
    }

    private record AttestationMaterial(
            String schemaVersion,
            String attestationFingerprint,
            MirrorDeploymentIsolationAttestation.Material material,
            MirrorDeploymentIsolationAttestation.Seal seal
    ) {
    }

    private record Coordinates(
            String attestationId,
            String attestationFingerprint,
            String keyId
    ) {
        private static Coordinates from(MirrorDeploymentIsolationAttestation value) {
            return value == null
                    ? new Coordinates("", "", "")
                    : new Coordinates(value.material().attestationId(),
                    value.attestationFingerprint(), value.seal().keyId());
        }
    }
}
