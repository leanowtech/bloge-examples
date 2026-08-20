package com.leanowtech.bloge.gateway.testkit.mounted;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioMountedAuthorityBundle;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioMountedTargetAdmissionBundle;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentAdmissionAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentUnavailableException;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.TrustedVerificationClockBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Set;

/**
 * Reference Provider backed by immutable authority and target-admission mounts plus a durable
 * deployment-owned execution-lease directory.
 *
 * <p>The authority path is read by the no-argument constructor. Formal target and lease-state
 * configuration is read once, lazily, by {@link #formalTargetBoundAuthorityBinding()}, so the
 * phase-2 authority-material API remains operational without formal configuration. The reference
 * trusted time source is {@link Clock#systemUTC()}; it is not independently authenticated by this
 * class. Deployments authenticate the complete declaration with the formal outer fingerprint and
 * the Provider artifact pin. All callbacks are synchronous and this implementation performs no
 * network access or background writes.</p>
 */
public final class MountedCapabilityStudioStageAcceptanceAuthorityProvider
        implements CapabilityStudioStageAcceptanceAuthorityProvider {
    /** Existing deployment-owned authority-bundle JVM property. */
    public static final String AUTHORITY_BUNDLE_ROOT_PROPERTY =
            "bloge.capabilityStudio.authorityBundleRoot";

    /** Deployment-owned target-admission-bundle JVM property. */
    public static final String TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY =
            "bloge.capabilityStudio.targetAdmissionBundleRoot";

    /** Deployment-owned durable execution-lease state JVM property. */
    public static final String EXECUTION_LEASE_STATE_ROOT_PROPERTY =
            "bloge.capabilityStudio.executionLeaseStateRoot";

    /** Stable payload-free code for a missing or blank authority-bundle property. */
    public static final String AUTHORITY_BUNDLE_ROOT_REQUIRED_CODE =
            "RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE_ROOT_REQUIRED";

    /** Stable payload-free code for an authority bundle that cannot be loaded. */
    public static final String AUTHORITY_BUNDLE_LOAD_FAILED_CODE =
            "RG.CAPABILITY_STUDIO.AUTHORITY_BUNDLE_LOAD_FAILED";

    /** Stable payload-free code for a missing target-admission-bundle property. */
    public static final String TARGET_ADMISSION_BUNDLE_ROOT_REQUIRED_CODE =
            "RG.CAPABILITY_STUDIO.TARGET_ADMISSION_BUNDLE_ROOT_REQUIRED";

    /** Stable payload-free code for a malformed target-admission-bundle root. */
    public static final String TARGET_ADMISSION_BUNDLE_ROOT_INVALID_CODE =
            "RG.CAPABILITY_STUDIO.TARGET_ADMISSION_BUNDLE_ROOT_INVALID";

    /** Stable payload-free code for invalid mounted target-admission material. */
    public static final String TARGET_ADMISSION_BUNDLE_LOAD_FAILED_CODE =
            "RG.CAPABILITY_STUDIO.TARGET_ADMISSION_BUNDLE_LOAD_FAILED";

    /** Stable payload-free code for a missing execution-lease state property. */
    public static final String EXECUTION_LEASE_STATE_ROOT_REQUIRED_CODE =
            "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_STATE_ROOT_REQUIRED";

    /** Stable payload-free code for a malformed execution-lease state root. */
    public static final String EXECUTION_LEASE_STATE_ROOT_INVALID_CODE =
            "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_STATE_ROOT_INVALID";

    /** Stable payload-free code for a partially configured formal Provider. */
    public static final String FORMAL_CONFIGURATION_INCOMPLETE_CODE =
            "RG.CAPABILITY_STUDIO.FORMAL_CONFIGURATION_INCOMPLETE";

    private static final String PROVIDER_ARTIFACT =
            "com.leanowtech.bloge:bloge-capability-studio-mounted-authority-provider:1.0.0";
    private static final String CLOCK_DOMAIN =
            "resource-gateway.capability-studio.mounted-provider-clock.v1";
    private static final String LIFECYCLE_DOMAIN =
            "resource-gateway.capability-studio.mounted-provider-lifecycle-authority.v1";
    private static final String LEASE_DOMAIN =
            "resource-gateway.capability-studio.mounted-provider-execution-lease-authority.v1";
    private static final String STORE_DOMAIN =
            "resource-gateway.capability-studio.mounted-provider-lease-store.v1";

    private final AuthorityBinding binding;
    private final Clock clock;
    private final Path realAuthorityRoot;
    private volatile boolean formalInitialized;
    private FormalTargetBoundAuthorityBinding formalBinding;
    private RuntimeException formalFailure;

    /**
     * Loads only the immutable post-run authority bundle. Formal dependencies remain lazy.
     *
     * @throws IllegalStateException for missing or malformed configuration and local material
     * @throws DeploymentUnavailableException when a configured mount or state dependency is down
     */
    public MountedCapabilityStudioStageAcceptanceAuthorityProvider() {
        this(Clock.systemUTC());
    }

    MountedCapabilityStudioStageAcceptanceAuthorityProvider(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock is required");
        Path authorityRoot = authorityRoot();
        CapabilityStudioMountedAuthorityBundle authorityBundle =
                loadAuthorityBundle(authorityRoot, clock);
        realAuthorityRoot = realReadOnlyRoot(authorityRoot,
                AUTHORITY_BUNDLE_LOAD_FAILED_CODE, false);
        binding = new AuthorityBinding(authorityBundle.bundleFingerprint(),
                authorityBundle.evidenceResolver(), authorityBundle.evidenceIssuerPolicy(),
                authorityBundle.ownerAuthority());
    }

    private FormalTargetBoundAuthorityBinding initializeFormalBinding() {
        String targetConfigured = System.getProperty(TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY);
        String stateConfigured = System.getProperty(EXECUTION_LEASE_STATE_ROOT_PROPERTY);
        boolean targetMissing = targetConfigured == null || targetConfigured.isBlank();
        boolean stateMissing = stateConfigured == null || stateConfigured.isBlank();
        if (targetMissing && stateMissing) {
            return null;
        }
        if (targetMissing != stateMissing) {
            throw new IllegalStateException(FORMAL_CONFIGURATION_INCOMPLETE_CODE);
        }
        Path targetRoot = normalizedAbsoluteRoot(
                targetConfigured, TARGET_ADMISSION_BUNDLE_ROOT_INVALID_CODE);
        Path stateRoot = normalizedAbsoluteRoot(
                stateConfigured, EXECUTION_LEASE_STATE_ROOT_INVALID_CODE);
        CapabilityStudioMountedTargetAdmissionBundle targetBundle =
                loadTargetBundle(targetRoot, clock);
        Path realTargetRoot = realReadOnlyRoot(targetRoot,
                TARGET_ADMISSION_BUNDLE_LOAD_FAILED_CODE, true);
        Path realStateRoot = FilesystemDeploymentAdmissionAuthority.requireStateRoot(stateRoot);
        String storeConfigurationFingerprint = componentFingerprint(STORE_DOMAIN,
                PROVIDER_ARTIFACT, realStateRoot.toString(),
                FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_VERSION,
                targetBundle.lifecycleMaterial().revocationAuthority().registryRef(),
                "IMMUTABLE_DESCRIPTOR_GENERATION_CHECKPOINT_V1");
        FilesystemDeploymentAdmissionAuthority.PreparedStore preparedStore =
                FilesystemDeploymentAdmissionAuthority.prepareStore(
                        realStateRoot, storeConfigurationFingerprint,
                        targetBundle.lifecycleMaterial().revocationAuthority());

        String clockFingerprint = componentFingerprint(CLOCK_DOMAIN,
                PROVIDER_ARTIFACT, "java.time.Clock.systemUTC");
        String lifecycleFingerprint = componentFingerprint(LIFECYCLE_DOMAIN,
                PROVIDER_ARTIFACT, realAuthorityRoot.toString(),
                binding.fingerprint(), realTargetRoot.toString(),
                targetBundle.bundleFingerprint(), realStateRoot.toString(),
                "ACTIVE_STRICT_MONOTONIC_PREDECESSOR_EXACT_REVOCATION_V1");
        String leaseFingerprint = componentFingerprint(LEASE_DOMAIN,
                PROVIDER_ARTIFACT, realStateRoot.toString(), targetBundle.bundleFingerprint(),
                preparedStore.descriptorFingerprint(),
                "ATOMIC_MOVE_FORCE_GENERATION_CHECKPOINT_EXACT_RECOVERY_V2");
        String deploymentFingerprint = DeploymentAdmissionAuthorityBinding.aggregateFingerprint(
                clockFingerprint, lifecycleFingerprint, leaseFingerprint);
        String expectedFormalFingerprint = CapabilityStudioStageAcceptanceAuthorityProvider
                .formalAggregateFingerprint(FormalTargetBoundAuthorityBinding.MESSAGE_VERSION,
                        binding.fingerprint(), deploymentFingerprint,
                        targetBundle.bundleFingerprint(), targetBundle.targetRawFingerprint(),
                        targetBundle.targetCanonicalFingerprint());
        String executionLeaseId = targetBundle.targetAdmissionBinding()
                .verificationContext().executionLeaseId();

        FilesystemDeploymentAdmissionAuthority deploymentAuthority =
                new FilesystemDeploymentAdmissionAuthority(preparedStore,
                        targetBundle.lifecycleMaterial(), targetBundle.targetRawFingerprint(),
                        targetBundle.targetCanonicalFingerprint(), deploymentFingerprint,
                        expectedFormalFingerprint, executionLeaseId, clock);
        DeploymentAdmissionAuthorityBinding deploymentBinding =
                new DeploymentAdmissionAuthorityBinding(
                        new TrustedVerificationClockBinding(clockFingerprint,
                                () -> trustedInstant(clock)),
                        new AdmissionLifecycleAuthorityBinding(lifecycleFingerprint,
                                deploymentAuthority::verify),
                        new ExecutionLeaseAuthorityBinding(leaseFingerprint,
                                deploymentAuthority::commit));
        FormalTargetBoundAuthorityBinding formal = new FormalTargetBoundAuthorityBinding(binding,
                targetBundle.formalTargetAdmissionBinding(deploymentBinding));
        if (!expectedFormalFingerprint.equals(formal.fingerprint())) {
            throw new IllegalStateException(TARGET_ADMISSION_BUNDLE_LOAD_FAILED_CODE);
        }
        return formal;
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

    /** {@inheritDoc} */
    @Override
    public String authorityBindingFingerprint() {
        return binding.fingerprint();
    }

    /** {@inheritDoc} */
    @Override
    public AuthorityBinding authorityBinding() {
        return binding;
    }

    /**
     * Returns the one precomputed atomic formal v2 snapshot.
     *
     * @return immutable formal target-bound Provider snapshot
     */
    @Override
    public FormalTargetBoundAuthorityBinding formalTargetBoundAuthorityBinding() {
        if (!formalInitialized) {
            synchronized (this) {
                if (!formalInitialized) {
                    try {
                        formalBinding = initializeFormalBinding();
                    } catch (RuntimeException failure) {
                        formalFailure = failure;
                    } finally {
                        formalInitialized = true;
                    }
                }
            }
        }
        if (formalFailure != null) {
            throw formalFailure;
        }
        return formalBinding;
    }

    /**
     * Returns a redacted Provider description.
     *
     * @return redacted description
     */
    @Override
    public String toString() {
        return "MountedCapabilityStudioStageAcceptanceAuthorityProvider"
                + "[mounts=REDACTED, material=REDACTED]";
    }

    static String componentFingerprint(String domain, String... coordinates) {
        if (domain == null || coordinates == null) {
            throw new IllegalArgumentException("provider component coordinate is invalid");
        }
        StringBuilder canonical = new StringBuilder();
        appendCoordinate(canonical, domain);
        for (String coordinate : coordinates) {
            appendCoordinate(canonical, coordinate);
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendCoordinate(StringBuilder target, String coordinate) {
        if (coordinate == null || coordinate.isBlank()) {
            throw new IllegalArgumentException("provider component coordinate is invalid");
        }
        byte[] bytes = coordinate.getBytes(StandardCharsets.UTF_8);
        target.append(bytes.length).append(':').append(coordinate);
    }

    private static Path authorityRoot() {
        String configured = System.getProperty(AUTHORITY_BUNDLE_ROOT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(AUTHORITY_BUNDLE_ROOT_REQUIRED_CODE);
        }
        try {
            return Path.of(configured).toAbsolutePath().normalize();
        } catch (RuntimeException failure) {
            throw new IllegalStateException(AUTHORITY_BUNDLE_LOAD_FAILED_CODE);
        }
    }

    private static Path normalizedAbsoluteRoot(String configured, String invalidCode) {
        try {
            Path root = Path.of(configured);
            if (!root.isAbsolute() || !root.equals(root.normalize())) {
                throw new IllegalStateException(invalidCode);
            }
            return root;
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalStateException(invalidCode);
        }
    }

    private static CapabilityStudioMountedAuthorityBundle loadAuthorityBundle(
            Path root, Clock clock) {
        try {
            return CapabilityStudioMountedAuthorityBundle.load(root, clock);
        } catch (RuntimeException failure) {
            DeploymentUnavailableException unavailable = deploymentUnavailableCause(failure);
            if (unavailable != null) {
                throw unavailable;
            }
            throw new IllegalStateException(AUTHORITY_BUNDLE_LOAD_FAILED_CODE);
        }
    }

    private static CapabilityStudioMountedTargetAdmissionBundle loadTargetBundle(
            Path root, Clock clock) {
        try {
            return CapabilityStudioMountedTargetAdmissionBundle.load(root, clock);
        } catch (DeploymentUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            DeploymentUnavailableException unavailable = deploymentUnavailableCause(failure);
            if (unavailable != null) {
                throw unavailable;
            }
            throw new IllegalStateException(TARGET_ADMISSION_BUNDLE_LOAD_FAILED_CODE);
        }
    }

    static DeploymentUnavailableException deploymentUnavailableCause(Throwable failure) {
        Set<Throwable> visited = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (Throwable current = failure;
             current != null && visited.add(current);
             current = current.getCause()) {
            if (current instanceof DeploymentUnavailableException unavailable) {
                return unavailable;
            }
        }
        return null;
    }

    private static Path realReadOnlyRoot(Path root, String invalidCode, boolean unavailable) {
        try {
            BasicFileAttributes attributes = java.nio.file.Files.readAttributes(
                    root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()
                    || attributes.fileKey() == null) {
                throw new IllegalStateException(invalidCode);
            }
            return root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException failure) {
            if (unavailable) {
                throw new DeploymentUnavailableException();
            }
            throw new IllegalStateException(invalidCode);
        } catch (java.io.IOException failure) {
            if (unavailable) {
                throw new DeploymentUnavailableException();
            }
            throw new IllegalStateException(invalidCode);
        }
    }

    private static java.time.Instant trustedInstant(Clock clock) {
        try {
            return clock.instant();
        } catch (RuntimeException unavailable) {
            throw new DeploymentUnavailableException();
        }
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
