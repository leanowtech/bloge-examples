package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/** Exact-reference read boundary for verified source-resolution attestations. */
public final class ReadOnlyShadowSourceResolutionAttestationService {
    private final ReadOnlyShadowSourceResolutionAttestationRepository
            attestations;

    /**
     * Creates the exact source-resolution read service.
     *
     * @param attestations append-only verified attestation repository
     */
    public ReadOnlyShadowSourceResolutionAttestationService(
            ReadOnlyShadowSourceResolutionAttestationRepository
                    attestations) {
        this.attestations = Objects.requireNonNull(
                attestations, "attestations");
    }

    /**
     * Resolves one exact verified source-resolution revision.
     *
     * @param scope complete authenticated enterprise namespace
     * @param reference exact content-addressed attestation reference
     * @return verified signed attestation
     */
    public ReadOnlyShadowSourceResolutionAttestation resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef reference) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        MirrorArtifactRef exactRef =
                Objects.requireNonNull(
                        reference, "reference");
        if (!ReadOnlyShadowSourceResolutionAttestation
                .ARTIFACT_KIND.equals(exactRef.kind())) {
            throw new Failure(Reason.REFERENCE_MISMATCH);
        }
        ReadOnlyShadowSourceResolutionAttestation attestation =
                attestations.find(
                        exactScope,
                        exactRef.id(),
                        exactRef.revision())
                        .orElseThrow(() ->
                                new Failure(
                                        Reason.NOT_FOUND));
        if (!exactRef.equals(attestation.artifactRef())) {
            throw new Failure(Reason.REFERENCE_MISMATCH);
        }
        return attestation;
    }

    /** Closed exact-resolution rejection vocabulary. */
    public enum Reason {
        /** Exact attestation revision does not exist in the authenticated scope. */
        NOT_FOUND,
        /** Caller reference differs from persisted signed coordinates. */
        REFERENCE_MISMATCH
    }

    /** Stable payload-free source-resolution lookup rejection. */
    public static final class Failure extends RuntimeException {
        /** Closed reason retained without source payloads. */
        private final Reason reason;

        /**
         * Creates one stable lookup rejection.
         *
         * @param reason closed rejection reason
         */
        public Failure(Reason reason) {
            super("Read-only Shadow source resolution lookup failed: "
                    + Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        /**
         * Returns the stable rejection reason.
         *
         * @return closed rejection reason
         */
        public Reason reason() {
            return reason;
        }
    }
}
