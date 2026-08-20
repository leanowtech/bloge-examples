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
     * One immutable snapshot of all authority dependencies used by one verification attempt.
     *
     * <p>Implementations must construct this value from the same deployment snapshot as the
     * fingerprint. Consumers must not reconstruct a binding by calling the legacy accessors
     * independently.</p>
     *
     * @param fingerprint lowercase deployment binding fingerprint
     * @param resolver exact-coordinate evidence resolver
     * @param issuerPolicy pinned evidence issuer policy
     * @param ownerAuthority organizational owner authority
     */
    record AuthorityBinding(
            String fingerprint,
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver resolver,
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuerPolicy,
            CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority) {
        /** Validates and defensively fixes the authority snapshot boundary. */
        public AuthorityBinding {
            if (fingerprint == null || resolver == null || issuerPolicy == null
                    || ownerAuthority == null) {
                throw new IllegalArgumentException("authority binding is incomplete");
            }
        }
    }

    /**
     * Returns one atomic authority snapshot for formal verification.
     *
     * <p>The default is intentionally {@code null}: it preserves source and binary compatibility
     * for legacy providers, while current formal and conformance paths reject it closed.</p>
     *
     * @return one immutable binding, or null for a legacy provider
     */
    default AuthorityBinding authorityBinding() {
        return null;
    }

    /**
     * Returns the deployment-owned immutable fingerprint for the complete authority binding.
     *
     * <p>The fingerprint identifies the resolver, issuer policy, and owner authority as one
     * deployment binding. It must never contain or derive from secrets. The default keeps source
     * and binary compatibility for providers compiled before the binding contract was added;
     * formal and conformance paths reject a missing or malformed value.</p>
     *
     * @return lowercase {@code sha256:} fingerprint, or null for a legacy provider
     */
    @Deprecated
    default String authorityBindingFingerprint() {
        return null;
    }

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
