package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Signed online operational switch for read-only Shadow execution.
 *
 * <p>{@code enabled=true} permits work and {@code enabled=false} denies it. Every execution
 * observes the current signed head before and after connector work. Publishing a disabled or
 * otherwise changed successor invalidates an older exact reference at the next observation,
 * preventing a long-lived positive cache from bypassing an emergency stop.</p>
 *
 * @param schemaVersion kill-switch publication protocol version
 * @param publicationFingerprint canonical fingerprint of material and seal
 * @param materialFingerprint canonical domain-separated material fingerprint
 * @param material immutable switch material
 * @param seal detached operational-authority signature
 */
public record ReadOnlyShadowKillSwitchPublication(
        String schemaVersion,
        String publicationFingerprint,
        String materialFingerprint,
        Material material,
        ReadOnlyShadowAuthoritySeal seal
) {
    /** Current signed kill-switch publication version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowKillSwitchPublication.v1";
    /** Exact switch material kind embedded in a job request. */
    public static final String ARTIFACT_KIND =
            "SHADOW_KILL_SWITCH_STATE";
    /** Complete signed-publication proof kind. */
    public static final String ATTESTATION_KIND =
            "SHADOW_KILL_SWITCH_ATTESTATION";
    /** Maximum lifetime of one positive or negative switch observation. */
    public static final Duration MAXIMUM_LIFETIME =
            Duration.ofMinutes(15);
    /** Maximum issuance-to-activation delay. */
    public static final Duration MAXIMUM_ACTIVATION_DELAY =
            Duration.ofMinutes(2);

    /** Validates deterministic envelope syntax without treating the signature as trusted. */
    public ReadOnlyShadowKillSwitchPublication {
        schemaVersion =
                ReadOnlyShadowAuthoritySeal.schemaVersion(
                        schemaVersion,
                        SCHEMA_VERSION,
                        "read-only Shadow kill switch");
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
                    "read-only Shadow kill-switch seal is outside its issuance window");
        }
    }

    /**
     * Returns the exact switch material reference embedded in a job request.
     *
     * @return content-addressed switch material
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                material.switchId(),
                material.revision(),
                materialFingerprint);
    }

    /**
     * Returns the exact signed switch proof used by admission evidence.
     *
     * @return content-addressed signed switch publication
     */
    public MirrorArtifactRef attestationRef() {
        return new MirrorArtifactRef(
                ATTESTATION_KIND,
                material.switchId(),
                material.revision(),
                publicationFingerprint);
    }

    /**
     * Immutable operational switch material.
     *
     * @param switchId stable switch stream identity
     * @param revision positive monotonic stream revision
     * @param previousPublicationFingerprint blank for revision one, otherwise exact predecessor
     * @param scope exact business execution scope
     * @param enabled whether read-only Shadow work remains permitted
     * @param issuedAt authority issuance time
     * @param effectiveAt inclusive decision activation time
     * @param expiresAt exclusive online freshness bound
     * @param issuer exact operational authority identity
     */
    public record Material(
            String switchId,
            long revision,
            String previousPublicationFingerprint,
            CapabilitySnapshot.Scope scope,
            boolean enabled,
            Instant issuedAt,
            Instant effectiveAt,
            Instant expiresAt,
            String issuer
    ) {
        /** Enforces a bounded, exact-scope, monotonic switch generation. */
        public Material {
            switchId =
                    ReadOnlyShadowAuthoritySeal.identifier(
                            switchId, "switchId");
            previousPublicationFingerprint =
                    ReadOnlyShadowAuthoritySeal.predecessor(
                            previousPublicationFingerprint,
                            revision,
                            "previousPublicationFingerprint");
            scope =
                    ReadOnlyShadowAuthoritySeal.scope(
                            scope, "scope");
            issuedAt =
                    ReadOnlyShadowAuthoritySeal.time(
                            issuedAt, "issuedAt");
            effectiveAt =
                    ReadOnlyShadowAuthoritySeal.time(
                            effectiveAt, "effectiveAt");
            expiresAt =
                    ReadOnlyShadowAuthoritySeal.time(
                            expiresAt, "expiresAt");
            issuer =
                    ReadOnlyShadowAuthoritySeal.identifier(
                            issuer, "issuer");
            ReadOnlyShadowAuthoritySeal.validityWindow(
                    issuedAt,
                    effectiveAt,
                    expiresAt,
                    MAXIMUM_ACTIVATION_DELAY,
                    MAXIMUM_LIFETIME,
                    "read-only Shadow kill switch");
        }
    }
}
