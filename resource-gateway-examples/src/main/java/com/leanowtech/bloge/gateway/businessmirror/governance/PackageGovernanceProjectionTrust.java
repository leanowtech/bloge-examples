package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

/** Deployment-owned ANEKE signature and lifecycle trust boundary. */
public interface PackageGovernanceProjectionTrust {
    /**
     * Verifies one detached projection seal against caller-pinned ANEKE trust, key lifecycle,
     * issuer policy, and revocation state.
     */
    boolean verify(
            VisualRunEvidenceSeal seal,
            DomainCapabilityPackageGovernanceProjection projection);

    /** @return whether this deployment can currently verify ANEKE projections */
    boolean available();

    /** @return fail-closed trust boundary for deployments without ANEKE integration */
    static PackageGovernanceProjectionTrust unavailable() {
        return new PackageGovernanceProjectionTrust() {
            @Override
            public boolean verify(
                    VisualRunEvidenceSeal seal,
                    DomainCapabilityPackageGovernanceProjection projection) {
                return false;
            }

            @Override
            public boolean available() {
                return false;
            }
        };
    }
}
