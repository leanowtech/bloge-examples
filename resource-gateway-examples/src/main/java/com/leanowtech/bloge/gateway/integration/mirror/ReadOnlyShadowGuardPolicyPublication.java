package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Signed, content-addressed shared-pressure policy for read-only Shadow execution.
 *
 * <p>The policy is owned by the external-system authority rather than by an individual business
 * graph. Its {@code guardScope} may therefore intentionally span multiple execution projects.
 * A sampling grant references one exact policy material fingerprint; the runtime also requires
 * that generation to remain the current signed stream head.</p>
 *
 * @param schemaVersion guard-policy publication protocol version
 * @param publicationFingerprint canonical fingerprint of material and seal
 * @param materialFingerprint canonical domain-separated material fingerprint
 * @param material immutable policy material
 * @param seal detached authority signature
 */
public record ReadOnlyShadowGuardPolicyPublication(
        String schemaVersion,
        String publicationFingerprint,
        String materialFingerprint,
        Material material,
        ReadOnlyShadowAuthoritySeal seal
) {
    /** Current guard-policy publication protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowGuardPolicyPublication.v1";
    /** Artifact kind used by sampling grants. */
    public static final String ARTIFACT_KIND =
            "SHADOW_EXECUTION_GUARD_POLICY";
    /** Artifact kind proving the complete signed publication. */
    public static final String ATTESTATION_KIND =
            "SHADOW_EXECUTION_GUARD_POLICY_ATTESTATION";
    /** Maximum lifetime of one online guard-policy generation. */
    public static final Duration MAXIMUM_LIFETIME =
            Duration.ofHours(24);
    /** Maximum issuance-to-activation delay. */
    public static final Duration MAXIMUM_ACTIVATION_DELAY =
            Duration.ofMinutes(5);

    /** Validates deterministic envelope syntax without treating the signature as trusted. */
    public ReadOnlyShadowGuardPolicyPublication {
        schemaVersion =
                ReadOnlyShadowAuthoritySeal.schemaVersion(
                        schemaVersion,
                        SCHEMA_VERSION,
                        "read-only Shadow guard policy");
        publicationFingerprint =
                ReadOnlyShadowAuthoritySeal.fingerprint(
                        publicationFingerprint,
                        "publicationFingerprint");
        materialFingerprint =
                ReadOnlyShadowAuthoritySeal.fingerprint(
                        materialFingerprint,
                        "materialFingerprint");
        material = Objects.requireNonNull(
                material, "material");
        seal = Objects.requireNonNull(seal, "seal");
        if (!materialFingerprint.equals(
                seal.materialFingerprint())
                || seal.signedAt().isBefore(
                material.issuedAt())
                || !seal.signedAt().isBefore(
                material.expiresAt())) {
            throw new IllegalArgumentException(
                    "read-only Shadow guard-policy seal is outside its issuance window");
        }
    }

    /**
     * Returns the exact policy material reference embedded in a sampling grant.
     *
     * @return content-addressed policy material
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                material.policyId(),
                material.revision(),
                materialFingerprint);
    }

    /**
     * Returns the exact signed-publication proof reference used in evidence.
     *
     * @return content-addressed signed policy publication
     */
    public MirrorArtifactRef attestationRef() {
        return new MirrorArtifactRef(
                ATTESTATION_KIND,
                material.policyId(),
                material.revision(),
                publicationFingerprint);
    }

    /**
     * Immutable shared-pressure policy material.
     *
     * @param policyId stable guard-policy stream identity
     * @param revision positive monotonic stream revision
     * @param previousPublicationFingerprint blank for revision one, otherwise exact predecessor
     * @param guardScope authority-owned shared physical budget scope
     * @param limits fixed-window, concurrency, lease, and circuit limits
     * @param issuedAt authority issuance time
     * @param validFrom inclusive policy activation time
     * @param expiresAt exclusive online validity bound
     * @param issuer exact data-governance authority identity
     */
    public record Material(
            String policyId,
            long revision,
            String previousPublicationFingerprint,
            CapabilitySnapshot.Scope guardScope,
            ReadOnlyShadowExecutionGuard.Limits limits,
            Instant issuedAt,
            Instant validFrom,
            Instant expiresAt,
            String issuer
    ) {
        /** Enforces a bounded, exact-scope, monotonic policy generation. */
        public Material {
            policyId =
                    ReadOnlyShadowAuthoritySeal.identifier(
                            policyId, "policyId");
            previousPublicationFingerprint =
                    ReadOnlyShadowAuthoritySeal.predecessor(
                            previousPublicationFingerprint,
                            revision,
                            "previousPublicationFingerprint");
            guardScope =
                    ReadOnlyShadowAuthoritySeal.scope(
                            guardScope, "guardScope");
            limits = Objects.requireNonNull(
                    limits, "limits");
            issuedAt =
                    ReadOnlyShadowAuthoritySeal.time(
                            issuedAt, "issuedAt");
            validFrom =
                    ReadOnlyShadowAuthoritySeal.time(
                            validFrom, "validFrom");
            expiresAt =
                    ReadOnlyShadowAuthoritySeal.time(
                            expiresAt, "expiresAt");
            issuer =
                    ReadOnlyShadowAuthoritySeal.identifier(
                            issuer, "issuer");
            ReadOnlyShadowAuthoritySeal.validityWindow(
                    issuedAt,
                    validFrom,
                    expiresAt,
                    MAXIMUM_ACTIVATION_DELAY,
                    MAXIMUM_LIFETIME,
                    "read-only Shadow guard policy");
        }
    }
}
