package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Optional;

/**
 * Trusted data-plane authority for payload-free cluster validation proofs.
 *
 * <p>Implementations own proof authenticity, revocation, and current-generation checks. They must
 * not return a validation from another enterprise scope and must fail closed when their payload or
 * signing authority is unavailable. Resource Gateway additionally recomputes the validation
 * content address and checks every referenced source against its local immutable corpus.</p>
 */
public interface CapabilityCorpusClusterValidationAuthority {
    /**
     * Reports whether authoritative validation lookup is currently usable.
     *
     * @return true only when fail-closed validation lookup is available
     */
    boolean available();

    /**
     * Resolves one exact current validation proof.
     *
     * @param scope complete enterprise scope
     * @param validationRef exact content-addressed validation reference
     * @return current verified validation, or empty when absent or revoked
     */
    Optional<CapabilityCorpusClusterValidation> resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef validationRef);

    /**
     * Returns a fail-closed placeholder for deployments without a data-plane validator.
     *
     * @return unavailable authority
     */
    static CapabilityCorpusClusterValidationAuthority unavailable() {
        return new CapabilityCorpusClusterValidationAuthority() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Optional<CapabilityCorpusClusterValidation> resolve(
                    CapabilitySnapshot.Scope scope,
                    MirrorArtifactRef validationRef) {
                return Optional.empty();
            }
        };
    }
}
