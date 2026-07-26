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
 * Canonical sealing and independent verification for read-only Shadow online authorities.
 *
 * <p>The three publication types use distinct signature domains. A valid grant signature cannot
 * therefore be replayed as a kill-switch or guard-policy decision even when all transport fields
 * happen to align. Runtime verification additionally requires a separately provisioned authority
 * key, exact issuer, current key lifecycle, signing-time validity, and a trusted current time.</p>
 */
public final class ReadOnlyShadowAuthorityIntegrity {
    /** Maximum canonical signed decision material size. */
    public static final int MAXIMUM_MATERIAL_BYTES =
            512 * 1024;
    /** Maximum canonical complete publication size. */
    public static final int MAXIMUM_PUBLICATION_BYTES =
            1024 * 1024;
    /** Signature domain for sampling grants. */
    public static final String SAMPLING_GRANT_DOMAIN =
            "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SAMPLING_GRANT_V1";
    /** Signature domain for kill-switch decisions. */
    public static final String KILL_SWITCH_DOMAIN =
            "RESOURCE_GATEWAY_READ_ONLY_SHADOW_KILL_SWITCH_V1";
    /** Signature domain for shared execution-guard policies. */
    public static final String GUARD_POLICY_DOMAIN =
            "RESOURCE_GATEWAY_READ_ONLY_SHADOW_GUARD_POLICY_V1";

    private final ObjectMapper mapper;

    /**
     * Creates the canonical authority integrity boundary.
     *
     * @param mapper canonical protocol mapper
     */
    public ReadOnlyShadowAuthorityIntegrity(
            ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(
                mapper, "mapper");
    }

    /**
     * Produces one signed shared guard-policy publication.
     *
     * @param material immutable policy material
     * @param signer external data-plane authority signer
     * @return content-addressed signed policy publication
     */
    public ReadOnlyShadowGuardPolicyPublication
    sealGuardPolicy(
            ReadOnlyShadowGuardPolicyPublication.Material material,
            VisualEvidenceSigner signer) {
        Objects.requireNonNull(material, "material");
        Signed signed = sign(
                GUARD_POLICY_DOMAIN,
                ReadOnlyShadowGuardPolicyPublication
                        .SCHEMA_VERSION,
                material,
                signer);
        String publicationFingerprint =
                publicationFingerprint(
                        ReadOnlyShadowGuardPolicyPublication
                                .SCHEMA_VERSION,
                        signed.materialFingerprint(),
                        material,
                        signed.seal());
        return new ReadOnlyShadowGuardPolicyPublication(
                "",
                publicationFingerprint,
                signed.materialFingerprint(),
                material,
                signed.seal());
    }

    /**
     * Produces one signed sampling-grant publication.
     *
     * @param material immutable grant material
     * @param signer external data-governance authority signer
     * @return content-addressed signed grant publication
     */
    public ReadOnlyShadowSamplingGrantPublication
    sealSamplingGrant(
            ReadOnlyShadowSamplingGrantPublication.Material material,
            VisualEvidenceSigner signer) {
        Objects.requireNonNull(material, "material");
        Signed signed = sign(
                SAMPLING_GRANT_DOMAIN,
                ReadOnlyShadowSamplingGrantPublication
                        .SCHEMA_VERSION,
                material,
                signer);
        String publicationFingerprint =
                publicationFingerprint(
                        ReadOnlyShadowSamplingGrantPublication
                                .SCHEMA_VERSION,
                        signed.materialFingerprint(),
                        material,
                        signed.seal());
        return new ReadOnlyShadowSamplingGrantPublication(
                "",
                publicationFingerprint,
                signed.materialFingerprint(),
                material,
                signed.seal());
    }

    /**
     * Produces one signed kill-switch publication.
     *
     * @param material immutable switch material
     * @param signer external operational-authority signer
     * @return content-addressed signed switch publication
     */
    public ReadOnlyShadowKillSwitchPublication
    sealKillSwitch(
            ReadOnlyShadowKillSwitchPublication.Material material,
            VisualEvidenceSigner signer) {
        Objects.requireNonNull(material, "material");
        Signed signed = sign(
                KILL_SWITCH_DOMAIN,
                ReadOnlyShadowKillSwitchPublication
                        .SCHEMA_VERSION,
                material,
                signer);
        String publicationFingerprint =
                publicationFingerprint(
                        ReadOnlyShadowKillSwitchPublication
                                .SCHEMA_VERSION,
                        signed.materialFingerprint(),
                        material,
                        signed.seal());
        return new ReadOnlyShadowKillSwitchPublication(
                "",
                publicationFingerprint,
                signed.materialFingerprint(),
                material,
                signed.seal());
    }

    /**
     * Verifies a guard-policy publication at one trusted instant.
     *
     * @param publication untrusted decoded policy publication
     * @param authorityKey independently provisioned authority key
     * @param verificationTime trusted current time
     * @return bounded payload-free verification result
     */
    public VerificationResult verifyGuardPolicy(
            ReadOnlyShadowGuardPolicyPublication publication,
            AuthorityKey authorityKey,
            Instant verificationTime) {
        if (publication == null) {
            return result(
                    Outcome.INVALID,
                    "PUBLICATION_MISSING",
                    Coordinates.empty());
        }
        return verify(
                publication.schemaVersion(),
                publication.publicationFingerprint(),
                publication.materialFingerprint(),
                publication.material(),
                publication.seal(),
                GUARD_POLICY_DOMAIN,
                publication.material().issuer(),
                publication.material().guardScope(),
                PublicationKind.GUARD_POLICY,
                publication.material().validFrom(),
                publication.material().expiresAt(),
                publication.material().policyId(),
                publication.material().revision(),
                authorityKey,
                verificationTime);
    }

    /**
     * Verifies a sampling-grant publication at one trusted instant.
     *
     * @param publication untrusted decoded grant publication
     * @param authorityKey independently provisioned authority key
     * @param verificationTime trusted current time
     * @return bounded payload-free verification result
     */
    public VerificationResult verifySamplingGrant(
            ReadOnlyShadowSamplingGrantPublication publication,
            AuthorityKey authorityKey,
            Instant verificationTime) {
        if (publication == null) {
            return result(
                    Outcome.INVALID,
                    "PUBLICATION_MISSING",
                    Coordinates.empty());
        }
        return verify(
                publication.schemaVersion(),
                publication.publicationFingerprint(),
                publication.materialFingerprint(),
                publication.material(),
                publication.seal(),
                SAMPLING_GRANT_DOMAIN,
                publication.material().issuer(),
                publication.material().scope(),
                PublicationKind.SAMPLING_GRANT,
                publication.material().validFrom(),
                publication.material().expiresAt(),
                publication.material().grantId(),
                publication.material().revision(),
                authorityKey,
                verificationTime);
    }

    /**
     * Verifies a kill-switch publication at one trusted instant.
     *
     * @param publication untrusted decoded switch publication
     * @param authorityKey independently provisioned authority key
     * @param verificationTime trusted current time
     * @return bounded payload-free verification result
     */
    public VerificationResult verifyKillSwitch(
            ReadOnlyShadowKillSwitchPublication publication,
            AuthorityKey authorityKey,
            Instant verificationTime) {
        if (publication == null) {
            return result(
                    Outcome.INVALID,
                    "PUBLICATION_MISSING",
                    Coordinates.empty());
        }
        return verify(
                publication.schemaVersion(),
                publication.publicationFingerprint(),
                publication.materialFingerprint(),
                publication.material(),
                publication.seal(),
                KILL_SWITCH_DOMAIN,
                publication.material().issuer(),
                publication.material().scope(),
                PublicationKind.KILL_SWITCH,
                publication.material().effectiveAt(),
                publication.material().expiresAt(),
                publication.material().switchId(),
                publication.material().revision(),
                authorityKey,
                verificationTime);
    }

    /**
     * Recomputes a guard-policy publication's canonical content addresses.
     *
     * @param publication untrusted decoded publication
     * @return whether both material and complete-publication fingerprints are exact
     */
    public boolean canonicalFingerprintVerified(
            ReadOnlyShadowGuardPolicyPublication publication) {
        return publication != null
                && canonical(
                publication.schemaVersion(),
                publication.publicationFingerprint(),
                publication.materialFingerprint(),
                publication.material(),
                publication.seal(),
                GUARD_POLICY_DOMAIN);
    }

    /**
     * Recomputes a sampling-grant publication's canonical content addresses.
     *
     * @param publication untrusted decoded publication
     * @return whether both material and complete-publication fingerprints are exact
     */
    public boolean canonicalFingerprintVerified(
            ReadOnlyShadowSamplingGrantPublication publication) {
        return publication != null
                && canonical(
                publication.schemaVersion(),
                publication.publicationFingerprint(),
                publication.materialFingerprint(),
                publication.material(),
                publication.seal(),
                SAMPLING_GRANT_DOMAIN);
    }

    /**
     * Recomputes a kill-switch publication's canonical content addresses.
     *
     * @param publication untrusted decoded publication
     * @return whether both material and complete-publication fingerprints are exact
     */
    public boolean canonicalFingerprintVerified(
            ReadOnlyShadowKillSwitchPublication publication) {
        return publication != null
                && canonical(
                publication.schemaVersion(),
                publication.publicationFingerprint(),
                publication.materialFingerprint(),
                publication.material(),
                publication.seal(),
                KILL_SWITCH_DOMAIN);
    }

    private VerificationResult verify(
            String schemaVersion,
            String publicationFingerprint,
            String materialFingerprint,
            Object material,
            ReadOnlyShadowAuthoritySeal seal,
            String domain,
            String issuer,
            CapabilitySnapshot.Scope scope,
            PublicationKind publicationKind,
            Instant effectiveAt,
            Instant expiresAt,
            String streamId,
            long revision,
            AuthorityKey authorityKey,
            Instant verificationTime) {
        Coordinates coordinates = new Coordinates(
                streamId,
                revision,
                publicationFingerprint,
                seal.keyId());
        if (!canonical(
                schemaVersion,
                publicationFingerprint,
                materialFingerprint,
                material,
                seal,
                domain)) {
            return result(
                    Outcome.INVALID,
                    "PUBLICATION_FINGERPRINT_INVALID",
                    coordinates);
        }
        if (authorityKey == null) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    "AUTHORITY_KEY_UNAVAILABLE",
                    coordinates);
        }
        if (!authorityKey.keyId().equals(seal.keyId())
                || !authorityKey.issuer().equals(issuer)
                || !authorityKey.scope().equals(scope)
                || authorityKey.publicationKind()
                != publicationKind) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "AUTHORITY_IDENTITY_MISMATCH",
                    coordinates);
        }
        if (!authorityKey.verificationAllowed(
                seal.signedAt())
                || !authorityKey.algorithm().equals(
                seal.algorithm())) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "AUTHORITY_KEY_POLICY_REJECTED",
                    coordinates);
        }
        if (seal.signedAt().isBefore(
                authorityKey.notBefore())
                || !seal.signedAt().isBefore(
                authorityKey.notAfter())) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "AUTHORITY_KEY_OUTSIDE_VALIDITY",
                    coordinates);
        }
        Instant activeFrom = effectiveAt.isAfter(
                seal.signedAt())
                ? effectiveAt
                : seal.signedAt();
        if (verificationTime == null
                || verificationTime.isBefore(activeFrom)
                || !verificationTime.isBefore(expiresAt)) {
            return result(
                    Outcome.WINDOW_REJECTED,
                    "PUBLICATION_OUTSIDE_VALIDITY_WINDOW",
                    coordinates);
        }
        try {
            if (!verifySignature(
                    materialFingerprint,
                    seal.signature(),
                    authorityKey.encodedPublicKey())) {
                return result(
                        Outcome.INVALID,
                        "AUTHORITY_SIGNATURE_INVALID",
                        coordinates);
            }
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "AUTHORITY_SIGNATURE_MATERIAL_INVALID",
                    coordinates);
        }
        return result(
                Outcome.VERIFIED,
                "VERIFIED",
                coordinates);
    }

    private Signed sign(
            String domain,
            String schemaVersion,
            Object material,
            VisualEvidenceSigner authority) {
        VisualEvidenceSigner signer = Objects.requireNonNull(
                authority, "authority");
        if (!signer.available()) {
            throw new IllegalArgumentException(
                    "read-only Shadow authority signer is unavailable");
        }
        String materialFingerprint = materialFingerprint(
                domain, schemaVersion, material);
        VisualRunEvidenceSeal signed =
                signer.seal(materialFingerprint);
        return new Signed(
                materialFingerprint,
                new ReadOnlyShadowAuthoritySeal(
                        materialFingerprint,
                        signed.algorithm(),
                        signed.keyId(),
                        signed.signedAt(),
                        signed.signature()));
    }

    private boolean canonical(
            String schemaVersion,
            String publicationFingerprint,
            String materialFingerprint,
            Object material,
            ReadOnlyShadowAuthoritySeal seal,
            String domain) {
        try {
            return materialFingerprint.equals(
                    materialFingerprint(
                            domain,
                            schemaVersion,
                            material))
                    && seal.materialFingerprint().equals(
                    materialFingerprint)
                    && publicationFingerprint.equals(
                    publicationFingerprint(
                            schemaVersion,
                            materialFingerprint,
                            material,
                            seal));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private String materialFingerprint(
            String domain,
            String schemaVersion,
            Object material) {
        return VisualBundleFingerprint.fromCanonicalValue(
                mapper,
                new SignatureMaterial(
                        domain,
                        schemaVersion,
                        material),
                MAXIMUM_MATERIAL_BYTES);
    }

    private String publicationFingerprint(
            String schemaVersion,
            String materialFingerprint,
            Object material,
            ReadOnlyShadowAuthoritySeal seal) {
        return VisualBundleFingerprint.fromCanonicalValue(
                mapper,
                new PublicationMaterial(
                        schemaVersion,
                        "",
                        materialFingerprint,
                        material,
                        seal),
                MAXIMUM_PUBLICATION_BYTES);
    }

    private static boolean verifySignature(
            String materialFingerprint,
            String encodedSignature,
            String encodedPublicKey) {
        try {
            Signature verifier =
                    Signature.getInstance("Ed25519");
            verifier.initVerify(
                    KeyFactory.getInstance("Ed25519")
                            .generatePublic(
                                    new X509EncodedKeySpec(
                                            Base64.getDecoder()
                                                    .decode(
                                                            encodedPublicKey))));
            verifier.update(
                    materialFingerprint.getBytes(
                            StandardCharsets.UTF_8));
            return verifier.verify(
                    Base64.getDecoder().decode(
                            encodedSignature));
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "read-only Shadow authority signature is invalid",
                    invalid);
        }
    }

    /** Bounded authority-verification outcome. */
    public enum Outcome {
        /** Every content, signature, authority, and time check passed. */
        VERIFIED,
        /** Publication structure, content address, or signature is invalid. */
        INVALID,
        /** No exact independently provisioned key was supplied. */
        KEY_UNAVAILABLE,
        /** Issuer, key lifecycle, algorithm, or signing time was rejected. */
        POLICY_REJECTED,
        /** Publication is not active at the trusted verification instant. */
        WINDOW_REJECTED
    }

    /** Authority-key lifecycle policy. */
    public enum KeyState {
        /** Key may sign and verify current publications. */
        ACTIVE,
        /** Key may verify signatures created strictly before its recorded retirement. */
        RETIRED,
        /** Key must not verify any publication. */
        REVOKED
    }

    /** Authority publication classes used to prevent cross-purpose key delegation. */
    public enum PublicationKind {
        /** Shared concurrency, rate, and circuit policy. */
        GUARD_POLICY,
        /** Business-scope logical sampling authorization. */
        SAMPLING_GRANT,
        /** Business-scope operational emergency switch. */
        KILL_SWITCH
    }

    /**
     * Independently provisioned read-only Shadow authority key.
     *
     * @param keyId stable key identity
     * @param algorithm fixed signature algorithm
     * @param encodedPublicKey canonical base64 X.509 SubjectPublicKeyInfo bytes
     * @param issuer exact data-governance or operational authority identity
     * @param scope exact enterprise namespace delegated to this key
     * @param publicationKind only authority protocol this key may verify
     * @param notBefore inclusive signing-time bound
     * @param notAfter exclusive signing-time bound
     * @param retiredAt exclusive retirement boundary; required only for RETIRED
     * @param state current local key lifecycle
     */
    public record AuthorityKey(
            String keyId,
            String algorithm,
            String encodedPublicKey,
            String issuer,
            CapabilitySnapshot.Scope scope,
            PublicationKind publicationKind,
            Instant notBefore,
            Instant notAfter,
            Instant retiredAt,
            KeyState state
    ) {
        /** Validates externally provisioned public-key policy. */
        public AuthorityKey {
            keyId =
                    ReadOnlyShadowAuthoritySeal.identifier(
                            keyId, "keyId");
            algorithm =
                    ReadOnlyShadowAuthoritySeal.required(
                            algorithm, "algorithm", 32);
            encodedPublicKey =
                    ReadOnlyShadowAuthoritySeal
                            .canonicalBase64(
                                    encodedPublicKey,
                                    "encodedPublicKey",
                                    16_384);
            issuer =
                    ReadOnlyShadowAuthoritySeal.identifier(
                            issuer, "issuer");
            scope =
                    ReadOnlyShadowAuthoritySeal.scope(
                            scope, "scope");
            publicationKind = Objects.requireNonNull(
                    publicationKind, "publicationKind");
            notBefore =
                    ReadOnlyShadowAuthoritySeal.time(
                            notBefore, "notBefore");
            notAfter =
                    ReadOnlyShadowAuthoritySeal.time(
                            notAfter, "notAfter");
            state = Objects.requireNonNull(
                    state, "state");
            if (state == KeyState.RETIRED) {
                retiredAt =
                        ReadOnlyShadowAuthoritySeal.time(
                                retiredAt, "retiredAt");
            } else if (retiredAt != null) {
                throw new IllegalArgumentException(
                        "retiredAt is valid only for a retired authority key");
            }
            if (!"Ed25519".equals(algorithm)
                    || !notAfter.isAfter(notBefore)
                    || retiredAt != null
                    && (retiredAt.isBefore(notBefore)
                    || retiredAt.isAfter(notAfter))) {
                throw new IllegalArgumentException(
                        "read-only Shadow authority key policy is invalid");
            }
        }

        /**
         * Reports whether local policy permits signature verification.
         *
         * @param signedAt exact detached-signature time
         * @return true for active keys or signatures strictly preceding retirement
         */
        public boolean verificationAllowed(
                Instant signedAt) {
            Instant exact = Objects.requireNonNull(
                    signedAt, "signedAt");
            return state == KeyState.ACTIVE
                    || state == KeyState.RETIRED
                    && exact.isBefore(retiredAt);
        }
    }

    /**
     * Payload-free independent verification result.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param streamId authority stream identity, or blank
     * @param revision publication revision, or zero
     * @param publicationFingerprint complete publication fingerprint, or blank
     * @param keyId authority key identity, or blank
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String streamId,
            long revision,
            String publicationFingerprint,
            String keyId
    ) {
        /** Validates log-safe verification coordinates. */
        public VerificationResult {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            reasonCode =
                    ReadOnlyShadowAuthoritySeal.required(
                            reasonCode,
                            "reasonCode",
                            255);
            streamId =
                    ReadOnlyShadowAuthoritySeal.normalized(
                            streamId);
            publicationFingerprint =
                    ReadOnlyShadowAuthoritySeal.normalized(
                            publicationFingerprint);
            keyId =
                    ReadOnlyShadowAuthoritySeal.normalized(
                            keyId);
            if (!reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")
                    || revision < 0) {
                throw new IllegalArgumentException(
                        "read-only Shadow verification result is invalid");
            }
        }

        /**
         * Reports whether every independent verification step passed.
         *
         * @return true only for a fully verified publication
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    private static VerificationResult result(
            Outcome outcome,
            String reason,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reason,
                coordinates.streamId(),
                coordinates.revision(),
                coordinates.publicationFingerprint(),
                coordinates.keyId());
    }

    private record Signed(
            String materialFingerprint,
            ReadOnlyShadowAuthoritySeal seal
    ) {
    }

    private record SignatureMaterial(
            String domain,
            String schemaVersion,
            Object material
    ) {
    }

    private record PublicationMaterial(
            String schemaVersion,
            String publicationFingerprint,
            String materialFingerprint,
            Object material,
            ReadOnlyShadowAuthoritySeal seal
    ) {
    }

    private record Coordinates(
            String streamId,
            long revision,
            String publicationFingerprint,
            String keyId
    ) {
        private static Coordinates empty() {
            return new Coordinates("", 0, "", "");
        }
    }
}
