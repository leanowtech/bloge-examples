package com.leanowtech.bloge.gateway.integration.mirror;

/** Authenticated remote source used by one deployment-isolation trust agent. */
public interface MirrorDeploymentIsolationTrustSource {

    /**
     * Fetches the exact current active or revoked attestation stream head.
     *
     * @return exact current active or revoked attestation bundle
     */
    MirrorDeploymentIsolationAttestationBundle latestAttestation();

    /**
     * Reads the exact current authority publication referenced by an active bundle.
     *
     * @param authorityRef exact authority generation and fingerprint
     * @return exact current authority publication
     */
    MirrorDeploymentIsolationAuthorityKeySetPublication currentAuthority(
            MirrorArtifactRef authorityRef);

    /**
     * Projects source security without endpoint or identity material.
     *
     * @return payload-free source security and protocol facts
     */
    Descriptor descriptor();

    /**
     * Bounded source-security projection.
     *
     * @param schemaVersion descriptor version
     * @param privateTrustStore whether a deployment-owned trust store is used
     * @param serverSpkiPinned whether the server chain is independently pinned
     * @param mutualTls whether a deployment client certificate is presented
     * @param certificateIdentityBound whether both X.509 workload identities are exact
     * @param strictEnvelope whether response envelopes are strictly decoded and fingerprinted
     * @param protocolVersion exact trust-distribution protocol generation
     * @param requestTimeoutMillis finite deadline for each of at most two refresh requests
     */
    record Descriptor(
            String schemaVersion,
            boolean privateTrustStore,
            boolean serverSpkiPinned,
            boolean mutualTls,
            boolean certificateIdentityBound,
            boolean strictEnvelope,
            String protocolVersion,
            long requestTimeoutMillis) {
        /** Current source descriptor version. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.mirrorDeploymentIsolationTrustSourceDescriptor.v1";

        /** Rejects contradictory or downgraded source descriptions. */
        public Descriptor {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            protocolVersion = protocolVersion == null ? "" : protocolVersion.trim();
            if (!SCHEMA_VERSION.equals(schemaVersion) || !privateTrustStore
                    || !serverSpkiPinned || !mutualTls || !certificateIdentityBound
                    || !strictEnvelope
                    || !MirrorDeploymentIsolationTrustDistributionProtocol.VERSION.equals(
                    protocolVersion)
                    || requestTimeoutMillis < 100 || requestTimeoutMillis > 30_000) {
                throw new IllegalArgumentException(
                        "deployment isolation trust source descriptor is invalid");
            }
        }
    }
}
