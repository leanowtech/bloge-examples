package com.leanowtech.bloge.gateway.testing.runtime;

/**
 * Optional runtime-binding contract used to classify executable operator unit tests.
 *
 * <p>The method is called synchronously during target discovery and must be deterministic,
 * non-blocking, free of external I/O, and credential-free. Returning a manifest does not itself
 * grant certification: the server validates its version, dependency controls, execution services,
 * global-state attestation and conformance artifact fingerprint.</p>
 */
public interface OperatorComposabilityManifestProvider {

    /**
     * Returns the immutable dependency and determinism declaration for this exact binding.
     *
     * @return versioned composability manifest; never {@code null}
     */
    OperatorComposabilityManifest operatorComposabilityManifest();
}
