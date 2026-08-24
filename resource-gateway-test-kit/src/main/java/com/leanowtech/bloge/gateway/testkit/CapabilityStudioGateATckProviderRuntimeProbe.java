package com.leanowtech.bloge.gateway.testkit;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.ServiceLoader;
import java.util.ServiceConfigurationError;

/**
 * JDK-only runtime probe for Gate A TCK provider discovery and CodeSource enforcement.
 *
 * <p>Package-private final. Provides static {@code probe()} API for ServiceLoader-based
 * TCK provider discovery with optional CodeSource enforcement against provider and candidate JARs.
 *
 * <p>All error codes are fixed strings — no runtime values are embedded in exceptions.
 * Does not call authority methods on discovered providers.
 *
 * <p>API: {@code RuntimeSnapshot probe(ClassLoader loader, Path providerArtifact, Path candidateArtifact, boolean enforceCodeSource)}
 *
 * @see RuntimeSnapshot
 */
final class CapabilityStudioGateATckProviderRuntimeProbe {

    // ── SPI and provider type constants ────────────────────────────────
    private static final String PROVIDER_TYPE_BINARY =
            "com.leanowtech.bloge.gatetckprovider.GateATckProvider";

    // ── Candidate closure classes to validate CodeSource when enforce=true ──────────
    private static final List<Class<?>> CANDIDATE_CLOSURE_CLASSES = List.of(
            CapabilityStudioStageAcceptanceAuthorityProvider.class,
            CapabilityStudioGateAChallengeCli.class,
            CapabilityStudioGateATckProviderRoleSelfTest.class,
            CapabilityStudioGateATckProviderArtifactValidator.class,
            CapabilityStudioGateATckProviderRuntimeProbe.class,
            CapabilityStudioGateAReceiptCanonicalizer.class,
            CapabilityStudioGateATckProviderReceiptComposer.class
    );

    // ── Error codes (all fixed) ───────────────────────────────────────
    private static final String E_NULL_LOADER              = "PROBE_NULL_LOADER";
    private static final String E_NULL_PROVIDER_ARTIFACT   = "PROBE_NULL_PROVIDER_ARTIFACT";
    private static final String E_NULL_CANDIDATE_ARTIFACT = "PROBE_NULL_CANDIDATE_ARTIFACT";
    private static final String E_PROVIDER_SYMLINK         = "PROBE_PROVIDER_SYMLINK";
    private static final String E_PROVIDER_NOT_REGULAR     = "PROBE_PROVIDER_NOT_REGULAR";
    private static final String E_CANDIDATE_SYMLINK        = "PROBE_CANDIDATE_SYMLINK";
    private static final String E_CANDIDATE_NOT_REGULAR   = "PROBE_CANDIDATE_NOT_REGULAR";
    private static final String E_PROVIDER_CS_NULL         = "PROBE_PROVIDER_CS_NULL";
    private static final String E_PROVIDER_CS_NOT_FILE     = "PROBE_PROVIDER_CS_NOT_FILE";
    private static final String E_PROVIDER_CS_SYMLINK      = "PROBE_PROVIDER_CS_SYMLINK";
    private static final String E_CANDIDATE_CS_NULL       = "PROBE_CANDIDATE_CS_NULL";
    private static final String E_CANDIDATE_CS_NOT_FILE   = "PROBE_CANDIDATE_CS_NOT_FILE";
    private static final String E_CANDIDATE_CS_SYMLINK    = "PROBE_CANDIDATE_CS_SYMLINK";
    private static final String E_NO_PROVIDER             = "PROBE_NO_PROVIDER";
    private static final String E_MULTIPLE_PROVIDERS       = "PROBE_MULTIPLE_PROVIDERS";
    private static final String E_WRONG_PROVIDER_TYPE     = "PROBE_WRONG_PROVIDER_TYPE";
    private static final String E_PROVIDER_INSTANTIATION  = "PROBE_PROVIDER_INSTANTIATION";
    private static final String E_LINKAGE_ERROR            = "PROBE_LINKAGE_ERROR";
    private static final String E_CS_MISMATCH             = "PROBE_CS_MISMATCH";

    private CapabilityStudioGateATckProviderRuntimeProbe() {
    }

    /**
     * Runtime probe result snapshot.
     *
     * <p>Immutable record containing only non-sensitive discovery metadata.
     *
     * @param providerClassName binary name of the discovered TCK provider, or null on failure
     * @param providerCount     number of providers discovered (0 or 1 on success path)
     */
    public record RuntimeSnapshot(String providerClassName, int providerCount) {

        /** Redacted representation — no class names or paths exposed. */
        @Override
        public String toString() {
            return "RuntimeSnapshot[providerCount=" + providerCount + "]";
        }
    }

    /**
     * Probes the given ClassLoader for a Gate A TCK provider using ServiceLoader.
     *
     * <p>Validates:</p>
     * <ul>
     *   <li>Exactly one provider is registered</li>
     *   <li>Provider type is exactly {@code com.leanowtech.bloge.gatetckprovider.GateATckProvider}</li>
     *   <li>Provider instantiates successfully via {@link ServiceLoader.Provider#get()}</li>
     *   <li>When {@code enforceCodeSource} is true:
     *     <ul>
     *       <li>Provider type CodeSource matches providerArtifact</li>
     *       <li>SPI and kernel classes CodeSource matches candidateArtifact</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>Does NOT call any authority methods on the discovered provider.</p>
     *
     * @param loader              ClassLoader to use for ServiceLoader discovery
     * @param providerArtifact    Path to the TCK provider JAR (required when enforceCodeSource is true)
     * @param candidateArtifact   Path to the candidate JAR (required when enforceCodeSource is true)
     * @param enforceCodeSource   if true, validates CodeSource constraints
     * @return immutable RuntimeSnapshot with discovery metadata
     * @throws CapabilityStudioGateAException on validation failure with fixed error codes
     */
    public static RuntimeSnapshot probe(
            ClassLoader loader,
            Path providerArtifact,
            Path candidateArtifact,
            boolean enforceCodeSource) {

        // Null input validation
        if (loader == null) {
            throw new CapabilityStudioGateAException(E_NULL_LOADER);
        }
        if (providerArtifact == null) {
            throw new CapabilityStudioGateAException(E_NULL_PROVIDER_ARTIFACT);
        }
        if (candidateArtifact == null) {
            throw new CapabilityStudioGateAException(E_NULL_CANDIDATE_ARTIFACT);
        }

        // Path validation: symlink check first, then regular file
        Path validatedProviderPath = validatePath(providerArtifact, true);
        Path validatedCandidatePath = validatePath(candidateArtifact, false);

        // Load SPI via ServiceLoader with typed reference
        ServiceLoader<CapabilityStudioStageAcceptanceAuthorityProvider> sl;
        try {
            sl = ServiceLoader.load(CapabilityStudioStageAcceptanceAuthorityProvider.class, loader);
        } catch (ServiceConfigurationError e) {
            throw new CapabilityStudioGateAException(E_LINKAGE_ERROR, e);
        }

        // Collect providers with fixed code on stream failure
        List<ServiceLoader.Provider<CapabilityStudioStageAcceptanceAuthorityProvider>> providers;
        try {
            providers = sl.stream().toList();
        } catch (ServiceConfigurationError e) {
            throw new CapabilityStudioGateAException(E_LINKAGE_ERROR, e);
        }

        int count = providers.size();
        if (count == 0) {
            throw new CapabilityStudioGateAException(E_NO_PROVIDER);
        }
        if (count > 1) {
            throw new CapabilityStudioGateAException(E_MULTIPLE_PROVIDERS);
        }

        // Get single provider; validate type BEFORE instantiation
        ServiceLoader.Provider<CapabilityStudioStageAcceptanceAuthorityProvider> providerHandle = providers.get(0);

        // Read declared type once, wrapped in ServiceConfigurationError | LinkageError
        Class<? extends CapabilityStudioStageAcceptanceAuthorityProvider> declaredType;
        try {
            declaredType = providerHandle.type();
        } catch (ServiceConfigurationError e) {
            throw new CapabilityStudioGateAException(E_LINKAGE_ERROR, e);
        } catch (LinkageError e) {
            throw new CapabilityStudioGateAException(E_LINKAGE_ERROR, e);
        }

        // Validate declared type name BEFORE calling get()
        String declaredTypeName;
        try {
            declaredTypeName = declaredType.getName();
        } catch (ServiceConfigurationError e) {
            throw new CapabilityStudioGateAException(E_LINKAGE_ERROR, e);
        } catch (LinkageError e) {
            throw new CapabilityStudioGateAException(E_LINKAGE_ERROR, e);
        }
        if (!PROVIDER_TYPE_BINARY.equals(declaredTypeName)) {
            throw new CapabilityStudioGateAException(E_WRONG_PROVIDER_TYPE);
        }

        // Instantiate only after type validation passes
        CapabilityStudioStageAcceptanceAuthorityProvider instance;
        try {
            instance = providerHandle.get();
        } catch (ServiceConfigurationError e) {
            throw new CapabilityStudioGateAException(E_PROVIDER_INSTANTIATION, e);
        } catch (LinkageError e) {
            throw new CapabilityStudioGateAException(E_LINKAGE_ERROR, e);
        } catch (RuntimeException e) {
            throw new CapabilityStudioGateAException(E_PROVIDER_INSTANTIATION, e);
        }

        // Verify actual instance class matches declared type (defense in depth)
        Class<?> actualClass = instance.getClass();
        if (actualClass != declaredType) {
            throw new CapabilityStudioGateAException(E_WRONG_PROVIDER_TYPE);
        }

        // CodeSource enforcement
        if (enforceCodeSource) {
            enforceCodeSources(validatedProviderPath, validatedCandidatePath, actualClass);
        }

        return new RuntimeSnapshot(PROVIDER_TYPE_BINARY, count);
    }

    // ── Path validation helpers ───────────────────────────────────────

    private static Path validatePath(Path path, boolean isProvider) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new CapabilityStudioGateAException(
                    isProvider ? E_PROVIDER_NOT_REGULAR : E_CANDIDATE_NOT_REGULAR, e);
        }

        // Symlink check first (before regular file check)
        if (attrs.isSymbolicLink()) {
            throw new CapabilityStudioGateAException(
                    isProvider ? E_PROVIDER_SYMLINK : E_CANDIDATE_SYMLINK);
        }
        if (!attrs.isRegularFile()) {
            throw new CapabilityStudioGateAException(
                    isProvider ? E_PROVIDER_NOT_REGULAR : E_CANDIDATE_NOT_REGULAR);
        }
        return path;
    }

    // ── CodeSource enforcement ────────────────────────────────────────

    private static void enforceCodeSources(Path providerPath, Path candidatePath, Class<?> providerType) {
        // Resolve provider CodeSource once
        CodeSource providerCs = getCodeSourceOrFail(providerType, true);
        Path providerCsPath = requireCodeSourcePath(providerCs, true);

        // Validate provider CodeSource matches providerArtifact
        if (!isSameFileOrMismatch(providerCsPath, providerPath)) {
            throw new CapabilityStudioGateAException(E_CS_MISMATCH);
        }

        // Validate candidate closure classes CodeSource matches candidateArtifact
        for (Class<?> cls : CANDIDATE_CLOSURE_CLASSES) {
            CodeSource candidateCs = getCodeSourceOrFail(cls, false);
            Path candidateCsPath = requireCodeSourcePath(candidateCs, false);
            if (!isSameFileOrMismatch(candidateCsPath, candidatePath)) {
                throw new CapabilityStudioGateAException(E_CS_MISMATCH);
            }
        }
    }

    private static CodeSource getCodeSourceOrFail(Class<?> cls, boolean isProvider) {
        ProtectionDomain pd = cls.getProtectionDomain();
        CodeSource cs = pd.getCodeSource();
        if (cs == null) {
            throw new CapabilityStudioGateAException(
                    isProvider ? E_PROVIDER_CS_NULL : E_CANDIDATE_CS_NULL);
        }
        return cs;
    }

    /**
     * Resolves a CodeSource to a validated Path, throwing role-specific fixed codes on failure.
     *
     * @param cs        CodeSource to resolve
     * @param isProvider true for provider roles, false for candidate roles
     * @return validated Path from the CodeSource
     * @throws CapabilityStudioGateAException with fixed codes on null URL, non-file URI, parse failure, or non-regular/symlink path
     */
    private static Path requireCodeSourcePath(CodeSource cs, boolean isProvider) {
        java.net.URL url = cs.getLocation();
        if (url == null) {
            throw new CapabilityStudioGateAException(
                    isProvider ? E_PROVIDER_CS_NOT_FILE : E_CANDIDATE_CS_NOT_FILE);
        }

        // Only accept file: URIs for security
        if (!"file".equalsIgnoreCase(url.getProtocol())) {
            throw new CapabilityStudioGateAException(
                    isProvider ? E_PROVIDER_CS_NOT_FILE : E_CANDIDATE_CS_NOT_FILE);
        }

        URI uri;
        try {
            uri = url.toURI();
        } catch (URISyntaxException e) {
            throw new CapabilityStudioGateAException(
                    isProvider ? E_PROVIDER_CS_NOT_FILE : E_CANDIDATE_CS_NOT_FILE);
        }

        Path csPath;
        try {
            csPath = Path.of(uri);
        } catch (IllegalArgumentException e) {
            throw new CapabilityStudioGateAException(
                    isProvider ? E_PROVIDER_CS_NOT_FILE : E_CANDIDATE_CS_NOT_FILE);
        } catch (java.nio.file.FileSystemNotFoundException e) {
            throw new CapabilityStudioGateAException(
                    isProvider ? E_PROVIDER_CS_NOT_FILE : E_CANDIDATE_CS_NOT_FILE);
        }

        // Verify CodeSource path is regular file (no symlinks)
        BasicFileAttributes csAttrs;
        try {
            csAttrs = Files.readAttributes(csPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new CapabilityStudioGateAException(
                    isProvider ? E_PROVIDER_CS_NOT_FILE : E_CANDIDATE_CS_NOT_FILE);
        }

        // Check symlink first
        if (csAttrs.isSymbolicLink()) {
            throw new CapabilityStudioGateAException(
                    isProvider ? E_PROVIDER_CS_SYMLINK : E_CANDIDATE_CS_SYMLINK);
        }
        if (!csAttrs.isRegularFile()) {
            throw new CapabilityStudioGateAException(
                    isProvider ? E_PROVIDER_CS_NOT_FILE : E_CANDIDATE_CS_NOT_FILE);
        }

        return csPath;
    }

    private static boolean isSameFileOrMismatch(Path csPath, Path expected) {
        try {
            return Files.isSameFile(csPath, expected);
        } catch (IOException e) {
            throw new CapabilityStudioGateAException(E_CS_MISMATCH);
        }
    }

    /**
     * Derives the candidate artifact path from the CodeSource of the executing ChallengeCli JAR.
     *
     * <p>Performs the same validation chain as {@link #requireCodeSourcePath}:
     * file URI protocol, no symlink, regular file — without copying or resolving URI dereferences.
     *
     * <p>Package-private for use by {@link CapabilityStudioGateATckProviderRoleSelfTest}.
     *
     * @return validated Path of the candidate artifact from ChallengeCli's CodeSource
     * @throws CapabilityStudioGateAException on any fixed-code failure
     */
    static Path candidateArtifactPath() {
        CodeSource cs = getCodeSourceOrFail(CapabilityStudioGateAChallengeCli.class, false);
        return requireCodeSourcePath(cs, false);
    }
}
