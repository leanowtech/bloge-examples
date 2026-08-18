package com.leanowtech.bloge.gateway.testkit;

/**
 * Deployment-owned authority dependencies for formal Capability Studio stage acceptance.
 *
 * <p>Implementations are discovered by {@link java.util.ServiceLoader} only after a Stage
 * Acceptance Result v2 document has passed local schema and semantic verification and declares
 * {@code PASS}. An enterprise provider should obtain resolver storage, issuer pins, and owner
 * authority from independently governed deployment configuration. Resource Gateway must not
 * implement this provider by minting its own environment or owner evidence.</p>
 */
public interface CapabilityStudioStageAcceptanceAuthorityProvider {
    /**
     * Returns the exact-coordinate external evidence and signature resolver.
     *
     * @return deployment-owned resolver
     */
    CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver evidenceResolver();

    /**
     * Returns the pinned evidence issuer policy.
     *
     * @return deployment-owned evidence issuer policy
     */
    CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy evidenceIssuerPolicy();

    /**
     * Returns the organizational owner signature authority.
     *
     * @return deployment-owned owner authority
     */
    CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority();
}
