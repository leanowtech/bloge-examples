package com.leanowtech.bloge.gateway.integration.mirror;

/**
 * Customer deployment adapter resolving current regional certification material.
 *
 * <p>The adapter is expected to read a private, mutually authenticated sidecar or equivalent
 * local authority. Resource Gateway intentionally provides no synthetic or production default.</p>
 */
public interface RegionalDataPlaneCertificationMaterialSource {
    /**
     * Resolves one atomic current view for an exact enterprise scope.
     *
     * @param scope authenticated enterprise scope
     * @return current isolation decision, contract, certification, and external trust key
     */
    Current current(CapabilitySnapshot.Scope scope);

    /** @return whether the external material source can currently serve exact reads */
    boolean available();

    /** Atomic material view returned by the customer-owned adapter. */
    record Current(
            MirrorDeploymentIsolationAttestationBundle isolationDecision,
            RegionalDataPlaneDeploymentContract contract,
            RegionalDataPlaneCertification certification,
            RegionalDataPlaneCertificationIntegrity.AuthorityKey authorityKey,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity localDeployment
    ) {
        /** Rejects structurally incomplete adapter output before trust evaluation. */
        public Current {
            if (isolationDecision == null || contract == null || certification == null
                    || authorityKey == null || localDeployment == null) {
                throw new IllegalArgumentException(
                        "regional certification material source returned an incomplete view");
            }
        }
    }
}
