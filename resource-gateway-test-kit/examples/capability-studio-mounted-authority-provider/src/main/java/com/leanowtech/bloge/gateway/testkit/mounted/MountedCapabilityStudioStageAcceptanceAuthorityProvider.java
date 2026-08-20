package com.leanowtech.bloge.gateway.testkit.mounted;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioMountedAuthorityBundle;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Reference enterprise provider backed by one deployment-owned, read-only authority bundle.
 *
 * <p>The provider deliberately has no configuration object, network client, key material, or
 * alternate. Its no-argument constructor reads only {@value #AUTHORITY_BUNDLE_ROOT_PROPERTY} and
 * loads the immutable bundle exactly once. Formal acceptance still depends on the bundle being
 * populated by an independently governed deployment.</p>
 */
public final class MountedCapabilityStudioStageAcceptanceAuthorityProvider
        implements CapabilityStudioStageAcceptanceAuthorityProvider {
    /** The sole deployment-owned JVM property read by this provider. */
    public static final String AUTHORITY_BUNDLE_ROOT_PROPERTY =
            "bloge.capabilityStudio.authorityBundleRoot";

    /** Stable payload-free code for a missing or blank deployment property. */
    public static final String AUTHORITY_BUNDLE_ROOT_REQUIRED_CODE =
            "RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE_ROOT_REQUIRED";

    /** Stable payload-free code for a bundle that cannot be loaded. */
    public static final String AUTHORITY_BUNDLE_LOAD_FAILED_CODE =
            "RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE_LOAD_FAILED";

    private final CapabilityStudioMountedAuthorityBundle bundle;
    private final CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding binding;

    /**
     * Loads the deployment-owned bundle from the configured absolute, normalized mount path.
     *
     * @throws IllegalStateException when the sole property is missing, blank, or the bundle
     *                               cannot be loaded
     */
    public MountedCapabilityStudioStageAcceptanceAuthorityProvider() {
        String configuredRoot = System.getProperty(AUTHORITY_BUNDLE_ROOT_PROPERTY);
        if (configuredRoot == null || configuredRoot.isBlank()) {
            throw new IllegalStateException(AUTHORITY_BUNDLE_ROOT_REQUIRED_CODE);
        }

        try {
            Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
            bundle = CapabilityStudioMountedAuthorityBundle.load(root, Clock.systemUTC());
            binding = new CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding(
                    bundle.bundleFingerprint(), bundle.evidenceResolver(),
                    bundle.evidenceIssuerPolicy(), bundle.ownerAuthority());
        } catch (RuntimeException exception) {
            throw new IllegalStateException(AUTHORITY_BUNDLE_LOAD_FAILED_CODE);
        }
    }

    /** {@inheritDoc} */
    @Override
    public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver evidenceResolver() {
        return binding.resolver();
    }

    /** {@inheritDoc} */
    @Override
    public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
            evidenceIssuerPolicy() {
        return binding.issuerPolicy();
    }

    /** {@inheritDoc} */
    @Override
    public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority() {
        return binding.ownerAuthority();
    }

    /**
     * Returns the immutable authority binding fingerprint supplied by the mounted bundle.
     *
     * @return bundle fingerprint
     */
    @Override
    public String authorityBindingFingerprint() {
        return binding.fingerprint();
    }

    /** {@inheritDoc} */
    @Override
    public CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding authorityBinding() {
        return binding;
    }

    /**
     * Identifies the provider without exposing the mount path or authority contents.
     *
     * @return redacted provider description
     */
    @Override
    public String toString() {
        return "MountedCapabilityStudioStageAcceptanceAuthorityProvider"
                + "{authorityBundleRoot=<redacted>, bundleFingerprint=<redacted>}";
    }
}
