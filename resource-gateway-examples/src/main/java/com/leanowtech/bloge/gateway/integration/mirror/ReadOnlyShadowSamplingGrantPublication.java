package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Signed online authorization for one exact read-only Shadow sampling budget.
 *
 * <p>The grant contains no business payload. It binds one execution scope, one logical-sample
 * ceiling, and one exact shared guard policy. Publishing a successor makes the predecessor stale;
 * an inactive successor therefore revokes already queued and in-flight work at the next mandatory
 * authority observation.</p>
 *
 * @param schemaVersion sampling-grant publication protocol version
 * @param publicationFingerprint canonical fingerprint of material and seal
 * @param materialFingerprint canonical domain-separated material fingerprint
 * @param material immutable grant material
 * @param seal detached data-governance authority signature
 */
public record ReadOnlyShadowSamplingGrantPublication(
        String schemaVersion,
        String publicationFingerprint,
        String materialFingerprint,
        Material material,
        ReadOnlyShadowAuthoritySeal seal
) {
    /** Current signed sampling-grant publication version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowSamplingGrantPublication.v1";
    /** Exact material kind embedded in a Shadow job request. */
    public static final String ARTIFACT_KIND =
            "SHADOW_SAMPLING_GRANT";
    /** Complete signed-publication proof kind. */
    public static final String ATTESTATION_KIND =
            "SHADOW_SAMPLING_GRANT_ATTESTATION";
    /** Maximum lifetime of one grant generation. */
    public static final Duration MAXIMUM_LIFETIME =
            Duration.ofHours(24);
    /** Maximum issuance-to-activation delay. */
    public static final Duration MAXIMUM_ACTIVATION_DELAY =
            Duration.ofMinutes(5);

    /** Validates deterministic envelope syntax without treating the signature as trusted. */
    public ReadOnlyShadowSamplingGrantPublication {
        schemaVersion =
                ReadOnlyShadowAuthoritySeal.schemaVersion(
                        schemaVersion,
                        SCHEMA_VERSION,
                        "read-only Shadow sampling grant");
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
                    "read-only Shadow sampling-grant seal is outside its issuance window");
        }
    }

    /**
     * Returns the exact grant material reference embedded in a job request.
     *
     * @return content-addressed sampling grant material
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                material.grantId(),
                material.revision(),
                materialFingerprint);
    }

    /**
     * Returns the exact signed grant proof used by admission evidence.
     *
     * @return content-addressed signed grant publication
     */
    public MirrorArtifactRef attestationRef() {
        return new MirrorArtifactRef(
                ATTESTATION_KIND,
                material.grantId(),
                material.revision(),
                publicationFingerprint);
    }

    /**
     * Immutable sampling authorization material.
     *
     * @param grantId stable grant stream identity
     * @param revision positive monotonic stream revision
     * @param previousPublicationFingerprint blank for revision one, otherwise exact predecessor
     * @param scope exact business execution scope
     * @param active whether this generation authorizes sampling
     * @param maximumSamples exact logical-sample ceiling
     * @param guardScope authority-owned shared physical budget scope
     * @param guardPolicyRef exact signed shared-pressure policy material
     * @param issuedAt authority issuance time
     * @param validFrom inclusive grant activation time
     * @param expiresAt exclusive online validity bound
     * @param issuer exact data-governance authority identity
     */
    public record Material(
            String grantId,
            long revision,
            String previousPublicationFingerprint,
            CapabilitySnapshot.Scope scope,
            boolean active,
            long maximumSamples,
            CapabilitySnapshot.Scope guardScope,
            MirrorArtifactRef guardPolicyRef,
            Instant issuedAt,
            Instant validFrom,
            Instant expiresAt,
            String issuer
    ) {
        /** Enforces a bounded, exact-scope, monotonic grant generation. */
        public Material {
            grantId =
                    ReadOnlyShadowAuthoritySeal.identifier(
                            grantId, "grantId");
            previousPublicationFingerprint =
                    ReadOnlyShadowAuthoritySeal.predecessor(
                            previousPublicationFingerprint,
                            revision,
                            "previousPublicationFingerprint");
            scope =
                    ReadOnlyShadowAuthoritySeal.scope(
                            scope, "scope");
            if (maximumSamples < 1
                    || maximumSamples > 1_000_000_000L) {
                throw new IllegalArgumentException(
                        "maximumSamples is outside protocol bounds");
            }
            guardScope =
                    ReadOnlyShadowAuthoritySeal.scope(
                            guardScope, "guardScope");
            guardPolicyRef =
                    ReadOnlyShadowAuthoritySeal.kind(
                            guardPolicyRef,
                            ReadOnlyShadowGuardPolicyPublication
                                    .ARTIFACT_KIND,
                            "guardPolicyRef");
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
                    "read-only Shadow sampling grant");
        }
    }
}
