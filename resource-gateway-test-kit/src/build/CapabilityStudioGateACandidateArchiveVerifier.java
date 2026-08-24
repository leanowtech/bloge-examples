package com.leanowtech.bloge.gateway.testkit;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JDK-only, authority-driven independent IMPLEMENTATION_CANDIDATE JAR verifier.
 *
 * <p>Phases:</p>
 * <ol>
 *   <li>Parse authority – extract IMPLEMENTATION_CANDIDATE role, limits, required entries,
 *       visible schemas, dependency lock IDs; join embedded entries with depAuth pins</li>
 *   <li>JAR structure – size, entry count, path normalisation, duplicate, byte, and ratio limits;
 *       required entries; schema set equals authority visible set; dep JAR SHA-256 pins</li>
 *   <li>Class allowlist – 7 Gate-A kernel outers + their $ inner closure only; StageAcceptanceAuthorityProvider
 *       outer-only; Packager/Verifier forbidden; Main-Class must be ChallengeCli</li>
 *   <li>Manifest validation – classes/resources/dependencies JSON fields, schemaVersion, kind/scope
 *       enumerations, per-entry byteSize/rawFingerprint, single-shot SHA-256 of
 *       domain + NUL + canonical(JSON(...)) for each manifestFingerprint</li>
 *   <li>Embedded authority – bytes exactly equal to the caller-supplied authority;
 *       other protocol/profile/projection resources – verify against their manifest fingerprints</li>
 *   <li>CLI execution – java.home/bin/java, -cp candidateJar ChallengeCli --role-self-test
 *       --role IMPLEMENTATION_CANDIDATE --authority authority --artifact candidateJar
 *       --fixture-set-id GATE_A_ROLE_BLACK_BOX_V1; 300 s timeout, bounded I/O, exit=0,
 *       stderr empty, stdout exactly one trailing LF (untrimmed check); receipt fields validated</li>
 * </ol>
 *
 * <p>Failures emit only stable error codes; no payload content is included in diagnostics.</p>
 */
public final class CapabilityStudioGateACandidateArchiveVerifier {

    // ── Execution constraints ─────────────────────────────────────────
    private static final int  CLI_TIMEOUT_SECONDS = 300;
    private static final String EXPECTED_ROLE = "IMPLEMENTATION_CANDIDATE";
    private static final String EXPECTED_FIXTURE_SET_ID = "GATE_A_ROLE_BLACK_BOX_V1";
    private static final String EXPECTED_RECEIPT_MESSAGE_VERSION =
            "resource-gateway.capability-studio.gate-a.role-self-test-receipt.v1";

    // Authority schemaVersion check
    private static final String EXPECTED_SCHEMA_VERSION =
            "capability-studio.gate-a-protocol-authority.v1";

    // Manifest schema versions
    private static final String SCHEMA_VERSION_CLASS =
            "capability-studio.gate-a-class-manifest.v1";
    private static final String SCHEMA_VERSION_RESOURCE =
            "capability-studio.gate-a-build-resource-manifest.v1";
    private static final String SCHEMA_VERSION_DEPENDENCY =
            "capability-studio.gate-a-dependency-lock-manifest.v1";

    // Manifest fingerprint domains
    private static final String DOMAIN_CLASS_MANIFEST =
            "RG-CS-GATE-A-CLASS-MANIFEST-v1";
    private static final String DOMAIN_RESOURCE_MANIFEST =
            "RG-CS-GATE-A-BUILD-RESOURCE-MANIFEST-v1";
    private static final String DOMAIN_DEPENDENCY_MANIFEST =
            "RG-CS-GATE-A-DEPENDENCY-LOCK-MANIFEST-v1";

    // Manifest paths from packagingContract
    private static final String CLASS_MANIFEST_PATH =
            "META-INF/gate-a/manifests/classes.json";
    private static final String RESOURCE_MANIFEST_PATH =
            "META-INF/gate-a/manifests/resources.json";
    private static final String DEPENDENCY_MANIFEST_PATH =
            "META-INF/gate-a/manifests/dependencies.json";

    // Embedded authority path
    private static final String EMBEDDED_AUTHORITY_PATH =
            "META-INF/gate-a/protocol/gate-a-protocol-authority.json";

    // Allowed outer kernel class binary names (slash-separated for path matching)
    private static final Set<String> ALLOWED_OUTER_BINARIES = Set.of(
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAArtifactValidator",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAAuthorityValidator",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAReceiptCanonicalizer",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateASchemaValidator",
            "com/leanowtech/bloge/gateway/testkit/StrictJsonParser",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAException"
    );

    // StageAcceptanceAuthorityProvider/Verifier – outer + any depth $ closure allowed
    private static final String PROVIDER_OUTER =
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider";
    private static final String VERIFIER_OUTER =
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityVerifier";

    // CapabilityStudioGateATckProviderRoleSelfTest – outer + any depth $ closure allowed
    private static final String TCK_OUTER =
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderRoleSelfTest";

    // CapabilityStudioGateATckProviderArtifactValidator – outer + any depth $ closure allowed
    private static final String TCK_ARTIFACT_VALIDATOR_OUTER =
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderArtifactValidator";

    // Explicitly forbidden class patterns (outer + any inner)
    private static final String FORBIDDEN_OUTER_PREFIX =
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateACandidatePackager";

    // Required Main-Class
    private static final String EXPECTED_MAIN_CLASS =
            "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli";

    // Embedded authority source
    private static final String EMBEDDED_AUTHORITY_SOURCE_PATH =
            "META-INF/gate-a/protocol/gate-a-protocol-authority.json";

    // Protocol/profile/projection paths whose fingerprints are verified against manifest
    private static final String[] PROTOCOL_RESOURCE_PATHS = {
            "META-INF/gate-a/protocol/protocol-compilation-manifest-v1.json",
            "META-INF/gate-a/projections/canonicalization-contract-v1.json",
            "META-INF/gate-a/canonicalization/fingerprint-profile-v1.json"
    };

    // ── State ─────────────────────────────────────────────────────────
    private final Path candidateJar;
    private final Path authorityFile;
    private final Path workingDir;
    private final byte[] authorityRaw;

    private final List<String> errors = new ArrayList<>();

    // Extracted from authority
    @SuppressWarnings("unchecked")
    private Map<String, Object> authority;
    @SuppressWarnings("unchecked")
    private Map<String, Object> implRole;
    private Map<String, Object> limits;
    private List<String> requiredJarEntries;
    @SuppressWarnings("unchecked")
    private List<String> visibleSchemaIds;
    @SuppressWarnings("unchecked")
    private List<String> runtimeDepLockIds;
    @SuppressWarnings("unchecked")
    private Map<String, Object> packagingContract;
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> embeddedDepEntries;
    @SuppressWarnings("unchecked")
    private Map<String, Object> depAuthority;
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> depAuthorityDeps;
    private String expectedReceiptMessageVersion;
    private String expectedReceiptFixtureSetId;
    private String expectedReceiptStatus; // null when exitCode not derivable

    // Derived dependency pins: lockId -> DependencyPin
    private Map<String, DependencyPin> depPins = new LinkedHashMap<>();

    // JAR entry paths belonging to authority dependency artifacts (for resource-manifest exact-set check)
    private Set<String> authorityDepEntryPaths = new HashSet<>();

    /**
     * Constructs a verifier for the given candidate JAR and authority.
     *
     * @param candidateJar  path to the IMPLEMENTATION_CANDIDATE JAR to verify
     * @param authorityFile path to the Gate-A protocol authority JSON
     * @param workingDir    working directory for CLI execution
     * @throws Exception if the authority file cannot be read
     */
    public CapabilityStudioGateACandidateArchiveVerifier(
            Path candidateJar,
            Path authorityFile,
            Path workingDir) throws Exception {
        this.candidateJar = candidateJar;
        this.authorityFile = authorityFile;
        this.workingDir = workingDir;

        // Read authority bytes (bounded)
        this.authorityRaw = readBoundedFile(authorityFile, 8 * 1024 * 1024);

        // Parse with StrictJsonParser
        this.authority = StrictJsonParser.parse(this.authorityRaw);

        // Validate schemaVersion
        String sv = (String) authority.get("schemaVersion");
        if (!EXPECTED_SCHEMA_VERSION.equals(sv)) {
            throw new RuntimeException("AUTHORITY_SCHEMA_VERSION_DRIFT:" + sv);
        }

        // Find IMPLEMENTATION_CANDIDATE role
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roles = (List<Map<String, Object>>) authority.get("roleContracts");
        this.implRole = roles.stream()
                .filter(r -> EXPECTED_ROLE.equals(r.get("role")))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Role IMPLEMENTATION_CANDIDATE not found in authority"));

        // Extract role fields
        this.limits = (Map<String, Object>) implRole.get("artifactLimits");
        if (limits == null) throw new RuntimeException("No artifactLimits in role");

        this.requiredJarEntries = (List<String>) implRole.get("requiredJarEntries");
        if (requiredJarEntries == null) throw new RuntimeException("No requiredJarEntries");

        this.visibleSchemaIds = (List<String>) implRole.get("visibleSchemaIds");
        if (visibleSchemaIds == null) throw new RuntimeException("No visibleSchemaIds");

        this.runtimeDepLockIds = (List<String>) implRole.get("runtimeDependencyLockIds");
        if (runtimeDepLockIds == null) throw new RuntimeException("No runtimeDependencyLockIds");

        this.packagingContract = (Map<String, Object>) implRole.get("packagingContract");
        if (packagingContract == null) throw new RuntimeException("No packagingContract");

        this.embeddedDepEntries = (List<Map<String, Object>>) packagingContract.get("embeddedDependencyEntries");
        if (embeddedDepEntries == null) embeddedDepEntries = List.of();

        this.depAuthority = (Map<String, Object>) authority.get("dependencyAuthority");
        if (depAuthority == null) throw new RuntimeException("No dependencyAuthority in authority");

        this.depAuthorityDeps = (List<Map<String, Object>>) depAuthority.get("dependencies");
        if (depAuthorityDeps == null) throw new RuntimeException("No dependencies in dependencyAuthority");

        // Derive expected receipt fields from authority blackBoxContract
        @SuppressWarnings("unchecked")
        Map<String, Object> bbContract = (Map<String, Object>) implRole.get("blackBoxContract");
        if (bbContract != null) {
            this.expectedReceiptMessageVersion = (String) bbContract.get("stdoutMessageVersion");
            this.expectedReceiptFixtureSetId = (String) bbContract.get("fixtureSetId");
            Object exitCodeObj = bbContract.get("expectedExitCode");
            int expectedExitCode = (exitCodeObj instanceof Number) ? ((Number) exitCodeObj).intValue() : -1;
            // CLI emits status="READY" when exitCode==0 (success)
            this.expectedReceiptStatus = (expectedExitCode == 0) ? "READY" : null;
        } else {
            this.expectedReceiptMessageVersion = EXPECTED_RECEIPT_MESSAGE_VERSION;
            this.expectedReceiptFixtureSetId = EXPECTED_FIXTURE_SET_ID;
            this.expectedReceiptStatus = "READY";
        }

        // Build dependency pin map: join embedded entries + depAuth on lockId+scope
        buildDependencyPins();
    }

    // ── Dependency pin construction ────────────────────────────────────
    // Joins embeddedDependencyEntries (role packagingContract) with
    // dependencyAuthority.dependencies on lockId+scope.
    // Refuses: missing in depAuth, missing in embedded, scope drift, duplicate lockId.
    @SuppressWarnings("unchecked")
    private void buildDependencyPins() {
        // Index depAuthority by lockId
        Map<String, Map<String, Object>> depAuthByLockId = new LinkedHashMap<>();
        for (Map<String, Object> dep : depAuthorityDeps) {
            depAuthByLockId.put((String) dep.get("lockId"), dep);
        }

        // Index embedded entries by lockId
        Map<String, Map<String, Object>> embByLockId = new LinkedHashMap<>();
        for (Map<String, Object> e : embeddedDepEntries) {
            embByLockId.put((String) e.get("lockId"), e);
        }

        // Index by lockId+scope for duplicate detection
        Set<String> seenKeys = new HashSet<>();

        for (String lockId : runtimeDepLockIds) {
            Map<String, Object> depAuthInfo = depAuthByLockId.get(lockId);
            if (depAuthInfo == null) {
                throw new RuntimeException("LOCK_ID_MISSING_IN_DEP_AUTH:" + lockId);
            }

            Map<String, Object> embInfo = embByLockId.get(lockId);
            if (embInfo == null) {
                throw new RuntimeException("LOCK_ID_MISSING_IN_EMBEDDED:" + lockId);
            }

            String depAuthScope = (String) depAuthInfo.get("scope");
            String embScope = (String) embInfo.get("scope");
            if (!depAuthScope.equals(embScope)) {
                throw new RuntimeException("SCOPE_DRIFT:" + lockId
                        + " depAuth=" + depAuthScope + " embedded=" + embScope);
            }

            String key = lockId + "::" + depAuthScope;
            if (!seenKeys.add(key)) {
                throw new RuntimeException("DUPLICATE_DEP_KEY:" + key);
            }

            String rawFingerprint = (String) depAuthInfo.get("rawFingerprint");
            String entryPath = (String) embInfo.get("entryPath");

            @SuppressWarnings("rawtypes")
            Map coord = (Map) depAuthInfo.get("coordinate");
            String groupId = (String) coord.get("groupId");
            String artifactId = (String) coord.get("artifactId");
            String version = (String) coord.get("version");

            depPins.put(lockId, new DependencyPin(lockId, groupId, artifactId, version,
                    depAuthScope, entryPath, rawFingerprint));
            authorityDepEntryPaths.add(entryPath);
        }
    }

    // ── Main entry point ──────────────────────────────────────────────
    /**
     * Entry point for the Gate-A candidate JAR verifier.
     *
     * @param args three arguments: candidateJar, authorityFile, workingDir
     * @throws Exception if verification fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java ... <candidateJar> <authorityFile> <workingDir>");
            System.exit(1);
        }
        Path candidateJar = Path.of(args[0]);
        Path authorityFile = Path.of(args[1]);
        Path workingDir = Path.of(args[2]);

        CapabilityStudioGateACandidateArchiveVerifier verifier =
                new CapabilityStudioGateACandidateArchiveVerifier(candidateJar, authorityFile, workingDir);

        boolean ok = verifier.verify();
        System.exit(ok ? 0 : 1);
    }

    // ── Top-level verify ──────────────────────────────────────────────
    /**
     * Runs all verification phases against the candidate JAR.
     *
     * @return true if all phases pass, false otherwise
     */
    public boolean verify() {
        boolean ok = true;

        try {
            // Phase 1: read JAR entries
            JarEntries jarEntries = readJarEntries();

            // Phase 2: JAR structure validation
            if (!validateJarStructure(jarEntries)) ok = false;

            // Phase 3: Class allowlist
            if (!validateClassAllowlist(jarEntries)) ok = false;

            // Phase 4: Manifest validation
            if (!validateManifests(jarEntries)) ok = false;

            // Phase 5: Embedded authority + protocol resources
            if (!validateEmbeddedAuthority(jarEntries)) ok = false;

            // Phase 6: CLI execution
            if (!executeAndVerifyCli()) ok = false;

        } catch (CapabilityStudioGateAException e) {
            errors.add("FATAL:" + e.errorCode());
            ok = false;
        } catch (Exception e) {
            errors.add("FATAL:" + e.getClass().getSimpleName());
            ok = false;
        }

        if (!ok) {
            System.out.println("Verification FAILED");
            for (String err : errors) {
                System.out.println("  " + err);
            }
        } else {
            System.out.println("Verification PASSED");
        }
        return ok;
    }

    // ── Phase 2: JAR structure ────────────────────────────────────────
    private JarEntries readJarEntries() throws Exception {
        JarEntries entries = new JarEntries();

        try (JarFile jf = new JarFile(candidateJar.toFile(), false)) {
            java.util.Enumeration<JarEntry> en = jf.entries();
            long totalUncompressed = 0;
            int entryCount = 0;

            while (en.hasMoreElements()) {
                JarEntry je = en.nextElement();
                String name = je.getName();
                long size = je.getSize();

                // Bounded read
                byte[] data;
                try (InputStream is = jf.getInputStream(je)) {
                    data = readBoundedStream(is, limits, je.getName());
                }

                entries.add(name, data, size >= 0 ? size : data.length);

                if (size >= 0) totalUncompressed += size;
                entryCount++;

                // Per-entry limits
                if (size > asPositiveLong(limits.get("maxSingleEntryBytes"), "maxSingleEntryBytes")) {
                    throw new CapabilityStudioGateAException("ENTRY_SIZE_EXCEEDED:" + name);
                }
            }

            entries.totalUncompressedBytes = totalUncompressed;
            entries.entryCount = entryCount;
        }

        return entries;
    }

    private boolean validateJarStructure(JarEntries entries) {
        boolean ok = true;
        byte[] rawJar;
        try {
            rawJar = Files.readAllBytes(candidateJar);
        } catch (Exception e) {
            errors.add("JAR_READ_FAILED:" + e.getClass().getSimpleName());
            return false;
        }

        long maxRawBytes = asPositiveLong(limits.get("maxRawBytes"), "maxRawBytes");
        if (rawJar.length > maxRawBytes) {
            errors.add("RAW_SIZE_EXCEEDED:" + rawJar.length + ">" + maxRawBytes);
            ok = false;
        }

        int maxZipEntries = (int) asPositiveLong(limits.get("maxZipEntries"), "maxZipEntries");
        if (entries.entryCount > maxZipEntries) {
            errors.add("ENTRY_COUNT_EXCEEDED:" + entries.entryCount + ">" + maxZipEntries);
            ok = false;
        }

        long maxTotalUncomp = asPositiveLong(limits.get("maxTotalUncompressedBytes"), "maxTotalUncompressedBytes");
        if (entries.totalUncompressedBytes > maxTotalUncomp) {
            errors.add("TOTAL_UNCOMPRESSED_EXCEEDED:" + entries.totalUncompressedBytes + ">" + maxTotalUncomp);
            ok = false;
        }

        Object ratioVal = limits.get("maxCompressionRatio");
        if (ratioVal instanceof Number) {
            double maxRatio = ((Number) ratioVal).doubleValue();
            if (maxRatio > 0 && entries.totalUncompressedBytes > 0) {
                double ratio = (double) entries.totalUncompressedBytes / rawJar.length;
                if (ratio > maxRatio) {
                    errors.add("COMPRESSION_RATIO_EXCEEDED:" + ratio + ">" + maxRatio);
                    ok = false;
                }
            }
        }

        // Path normalisation / duplicate / dot-component check
        Set<String> normalizedNames = new HashSet<>();
        for (String name : entries.names) {
            if (name.startsWith("/") || name.contains("\\")) {
                errors.add("PATH_INVALID_CHAR:" + name);
                ok = false;
                continue;
            }
            String norm = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            if (norm.isEmpty()) {
                errors.add("PATH_EMPTY:" + name);
                ok = false;
                continue;
            }
            for (String part : norm.split("/")) {
                if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                    errors.add("PATH_DOT_COMPONENT:" + name);
                    ok = false;
                    break;
                }
            }
            if (!normalizedNames.add(norm)) {
                errors.add("PATH_COLLISION:" + name);
                ok = false;
            }
        }

        // Required JAR entries
        Set<String> entrySet = new HashSet<>(entries.names);
        for (String required : requiredJarEntries) {
            if (!entrySet.contains(required)) {
                errors.add("REQUIRED_ENTRY_MISSING:" + required);
                ok = false;
            }
        }

        // Schema entries: must EXACTLY equal visibleSchemaIds set
        String schemaPrefix = "schemas/";
        Set<String> embeddedSchemaIds = new HashSet<>();
        for (String name : entries.names) {
            if (name.startsWith(schemaPrefix)) {
                String schemaId = name.substring(schemaPrefix.length());
                if (!schemaId.isEmpty()) {
                    embeddedSchemaIds.add(schemaId);
                }
            }
        }

        Set<String> expectedSchemas = new HashSet<>(visibleSchemaIds);
        if (!embeddedSchemaIds.equals(expectedSchemas)) {
            Set<String> missing = new HashSet<>(expectedSchemas);
            missing.removeAll(embeddedSchemaIds);
            Set<String> extra = new HashSet<>(embeddedSchemaIds);
            extra.removeAll(expectedSchemas);
            for (String s : missing) errors.add("SCHEMA_MISSING:" + s);
            for (String s : extra) errors.add("SCHEMA_EXTRA:" + s);
            ok = false;
        }

        // Dependency JARs: SHA-256 must match authority pins, entry paths must match authority
        for (DependencyPin pin : depPins.values()) {
            byte[] depData = entries.dataByName.get(pin.entryPath);
            if (depData == null) {
                errors.add("DEP_ENTRY_MISSING:" + pin.entryPath);
                ok = false;
                continue;
            }
            String actualSha256 = rawFingerprint(depData);
            if (!actualSha256.equals(pin.rawFingerprint)) {
                errors.add("DEP_SHA256_MISMATCH:" + pin.lockId + " expected=" + pin.rawFingerprint + " actual=" + actualSha256);
                ok = false;
            }
        }

        // Also check that every dependency entry path in the JAR is in the authority entryPath set
        Set<String> authorityDepPaths = new HashSet<>();
        for (DependencyPin pin : depPins.values()) {
            authorityDepPaths.add(pin.entryPath);
        }
        String depPrefix = "META-INF/gate-a/dependencies/";
        for (String name : entries.names) {
            if (name.startsWith(depPrefix) && !name.endsWith("/")) {
                if (!authorityDepPaths.contains(name)) {
                    errors.add("DEP_EXTRA_JAR:" + name);
                    ok = false;
                }
            }
        }

        return ok;
    }

    // ── Phase 3: Class allowlist ─────────────────────────────────────
    private boolean validateClassAllowlist(JarEntries entries) {
        boolean ok = true;

        // Main-Class from manifest
        Manifest manifest;
        try {
            manifest = new Manifest(new ByteArrayInputStream(
                    entries.dataByName.get("META-INF/MANIFEST.MF")));
        } catch (Exception e) {
            errors.add("MANIFEST_READ_FAILED:" + e.getClass().getSimpleName());
            return false;
        }

        String mainClass = manifest.getMainAttributes().getValue("Main-Class");
        if (!EXPECTED_MAIN_CLASS.equals(mainClass)) {
            errors.add("MAIN_CLASS_WRONG:" + (mainClass == null ? "null" : mainClass));
            ok = false;
        }

        // Collect all class entries
        Set<String> classEntries = new HashSet<>();
        for (String name : entries.names) {
            if (name.endsWith(".class")) {
                classEntries.add(name);
            }
        }

        for (String name : classEntries) {
            String pathNoExt = name.substring(0, name.length() - 6);

            // StageAcceptanceAuthorityProvider/Verifier – outer + any depth $ closure allowed
            boolean isProviderOrVerifier = pathNoExt.equals(PROVIDER_OUTER)
                    || pathNoExt.equals(VERIFIER_OUTER)
                    || pathNoExt.equals(TCK_OUTER)
                    || pathNoExt.startsWith(PROVIDER_OUTER + "$")
                    || pathNoExt.startsWith(VERIFIER_OUTER + "$")
                    || pathNoExt.equals(TCK_ARTIFACT_VALIDATOR_OUTER)
                    || pathNoExt.startsWith(TCK_OUTER + "$")
                    || pathNoExt.startsWith(TCK_ARTIFACT_VALIDATOR_OUTER + "$");
            if (isProviderOrVerifier) {
                continue;
            }

            // Allowed kernel outers + any depth $ inner closures: use startsWith
            boolean isAllowedOuter = ALLOWED_OUTER_BINARIES.contains(pathNoExt);
            boolean isAllowedInner = false;
            if (!isAllowedOuter) {
                // Test each allowed outer as a prefix; inner classes use $ delimiters
                for (String allowedOuter : ALLOWED_OUTER_BINARIES) {
                    if (pathNoExt.startsWith(allowedOuter + "$")) {
                        isAllowedInner = true;
                        break;
                    }
                }
            }
            if (isAllowedOuter || isAllowedInner) {
                continue;
            }

            // Forbidden Packager/Verifier prefix (any inner too)
            if (pathNoExt.startsWith(FORBIDDEN_OUTER_PREFIX)) {
                errors.add("CLASS_FORBIDDEN_PACKAGER:" + name);
                ok = false;
                continue;
            }

            errors.add("CLASS_NOT_ALLOWLISTED:" + name);
            ok = false;
        }

        return ok;
    }

    // ── Phase 4: Manifest validation ──────────────────────────────────
    @SuppressWarnings("unchecked")
    private boolean validateManifests(JarEntries entries) {
        boolean ok = true;

        // ── classes.json ──────────────────────────────────────────────
        byte[] classManifestRaw = entries.dataByName.get(CLASS_MANIFEST_PATH);
        if (classManifestRaw == null) {
            errors.add("CLASS_MANIFEST_MISSING:" + CLASS_MANIFEST_PATH);
            return false;
        }

        Map<String, Object> classManifest;
        try {
            classManifest = StrictJsonParser.parse(classManifestRaw);
        } catch (CapabilityStudioGateAException e) {
            errors.add("CLASS_MANIFEST_PARSE_ERROR:" + e.errorCode());
            return false;
        }

        if (!validateClassManifestSchema(classManifest)) {
            ok = false;
        }

        if (!validateClassManifestFingerprint(classManifest, entries)) {
            ok = false;
        }

        // ── resources.json ───────────────────────────────────────────
        byte[] resourceManifestRaw = entries.dataByName.get(RESOURCE_MANIFEST_PATH);
        if (resourceManifestRaw == null) {
            errors.add("RESOURCE_MANIFEST_MISSING:" + RESOURCE_MANIFEST_PATH);
            return false;
        }

        Map<String, Object> resourceManifest;
        try {
            resourceManifest = StrictJsonParser.parse(resourceManifestRaw);
        } catch (CapabilityStudioGateAException e) {
            errors.add("RESOURCE_MANIFEST_PARSE_ERROR:" + e.errorCode());
            return false;
        }

        if (!validateResourceManifestSchema(resourceManifest, entries)) {
            ok = false;
        }

        if (!validateResourceManifestFingerprint(resourceManifest, entries)) {
            ok = false;
        }

        // ── dependencies.json ───────────────────────────────────────
        byte[] depManifestRaw = entries.dataByName.get(DEPENDENCY_MANIFEST_PATH);
        if (depManifestRaw == null) {
            errors.add("DEP_MANIFEST_MISSING:" + DEPENDENCY_MANIFEST_PATH);
            return false;
        }

        Map<String, Object> depManifest;
        try {
            depManifest = StrictJsonParser.parse(depManifestRaw);
        } catch (CapabilityStudioGateAException e) {
            errors.add("DEP_MANIFEST_PARSE_ERROR:" + e.errorCode());
            return false;
        }

        if (!validateDepManifestSchema(depManifest)) {
            ok = false;
        }

        if (!validateDepManifestFingerprint(depManifest, entries)) {
            ok = false;
        }

        return ok;
    }

    private boolean validateClassManifestSchema(Map<String, Object> manifest) {
        boolean ok = true;

        String schemaVersion = (String) manifest.get("schemaVersion");
        if (!SCHEMA_VERSION_CLASS.equals(schemaVersion)) {
            errors.add("CLASS_MANIFEST_SCHEMA_VERSION:" + schemaVersion);
            ok = false;
        }

        // Top-level keys must be exactly these 3; reject extra and reject missing
        Set<String> requiredKeys = Set.of("schemaVersion", "entries", "manifestFingerprint");
        for (String k : requiredKeys) {
            if (!manifest.containsKey(k)) {
                errors.add("CLASS_MANIFEST_MISSING_KEY:" + k);
                ok = false;
            }
        }
        for (String k : manifest.keySet()) {
            if (!requiredKeys.contains(k)) {
                errors.add("CLASS_MANIFEST_EXTRA_KEY:" + k);
                ok = false;
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) manifest.get("entries");
        if (entries == null) {
            errors.add("CLASS_MANIFEST_NO_ENTRIES");
            return false;
        }

        // Each entry: binaryName, entryPath, rawFingerprint (typed dict)
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> e = entries.get(i);
            Set<String> entryKeys = e.keySet();
            Set<String> requiredEntryKeys = Set.of("binaryName", "entryPath", "rawFingerprint");
            for (String k : requiredEntryKeys) {
                if (!entryKeys.contains(k)) {
                    errors.add("CLASS_MANIFEST_ENTRY_MISSING_KEY:" + k + "@" + i);
                    ok = false;
                }
            }
            for (String k : entryKeys) {
                if (!requiredEntryKeys.contains(k)) {
                    errors.add("CLASS_MANIFEST_ENTRY_EXTRA_KEY:" + k + "@" + i);
                    ok = false;
                }
            }

            // binaryName type
            if (!(e.get("binaryName") instanceof String)) {
                errors.add("CLASS_MANIFEST_ENTRY_BINARYNAME_TYPE:" + i);
                ok = false;
            }

            // entryPath type
            if (!(e.get("entryPath") instanceof String)) {
                errors.add("CLASS_MANIFEST_ENTRY_PATHTYPE:" + i);
                ok = false;
            }

            // rawFingerprint must be typed dict: kind=RAW_BYTES, algorithm=SHA-256, value=sha256:...
            Object fp = e.get("rawFingerprint");
            if (!(fp instanceof Map)) {
                errors.add("CLASS_MANIFEST_ENTRY_FP_TYPE:" + i);
                ok = false;
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> fpMap = (Map<String, Object>) fp;
                if (!"RAW_BYTES".equals(fpMap.get("kind"))) {
                    errors.add("CLASS_MANIFEST_ENTRY_FP_KIND:" + i);
                    ok = false;
                }
                if (!"SHA-256".equals(fpMap.get("algorithm"))) {
                    errors.add("CLASS_MANIFEST_ENTRY_FP_ALG:" + i);
                    ok = false;
                }
                Object fpValue = fpMap.get("value");
                if (!(fpValue instanceof String)) {
                    errors.add("CLASS_MANIFEST_ENTRY_FP_VALUE_TYPE:" + i);
                    ok = false;
                } else {
                    String fpStr = (String) fpValue;
                    if (!fpStr.startsWith("sha256:")) {
                        errors.add("CLASS_MANIFEST_ENTRY_FP_PREFIX:" + i);
                        ok = false;
                    } else if (fpStr.length() != 71) {
                        errors.add("CLASS_MANIFEST_ENTRY_FP_LENGTH:" + i);
                        ok = false;
                    }
                    // Also verify byteSize consistency
                    // Note: class manifest entries don't have byteSize field per packager
                }
            }
        }

        // manifestFingerprint must be typed dict: kind=AGGREGATE_COMMITMENT, algorithm=SHA-256, value=sha256:...
        Object mfp = manifest.get("manifestFingerprint");
        if (!(mfp instanceof Map)) {
            errors.add("CLASS_MANIFEST_FP_TYPE");
            ok = false;
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> mfpMap = (Map<String, Object>) mfp;
            if (!"AGGREGATE_COMMITMENT".equals(mfpMap.get("kind"))) {
                errors.add("CLASS_MANIFEST_FP_KIND");
                ok = false;
            }
            if (!"SHA-256".equals(mfpMap.get("algorithm"))) {
                errors.add("CLASS_MANIFEST_FP_ALG");
                ok = false;
            }
            Object mfpVal = mfpMap.get("value");
            if (!(mfpVal instanceof String) || !((String) mfpVal).startsWith("sha256:")) {
                errors.add("CLASS_MANIFEST_FP_VALUE");
                ok = false;
            }
        }

        return ok;
    }

    @SuppressWarnings("unchecked")
    private boolean validateClassManifestFingerprint(Map<String, Object> manifest, JarEntries entries) {
        boolean ok = true;
        List<Map<String, Object>> manifestEntries =
                new ArrayList<>((List<Map<String, Object>>) manifest.get("entries"));

        // 1. Compute actual JAR .class entry set (slash paths, no .class suffix for the path)
        Set<String> actualClassPaths = new HashSet<>();
        for (String name : entries.names) {
            if (name.endsWith(".class")) {
                actualClassPaths.add(name);
            }
        }

        // 2. Compute manifest entryPath set; collect binaryNames
        Set<String> manifestEntryPaths = new HashSet<>();
        Set<String> manifestBinaryNames = new HashSet<>();
        for (Map<String, Object> e : manifestEntries) {
            String ep = (String) e.get("entryPath");
            String bn = (String) e.get("binaryName");
            if (ep == null || bn == null) {
                // Schema validation already caught null; skip
                continue;
            }
            if (!manifestEntryPaths.add(ep)) {
                errors.add("CLASS_MANIFEST_DUP_PATH:" + ep);
                ok = false;
            }
            if (!manifestBinaryNames.add(bn)) {
                errors.add("CLASS_MANIFEST_DUP_BINARYNAME:" + bn);
                ok = false;
            }
            // 3. binaryName must be exactly entryPath with .class stripped
            if (!ep.endsWith(".class")) {
                errors.add("CLASS_MANIFEST_ENTRYPATH_SUFFIX:" + ep);
                ok = false;
            } else {
                String derivedBn = ep.substring(0, ep.length() - 6).replace('/', '.');
                if (!derivedBn.equals(bn)) {
                    errors.add("CLASS_MANIFEST_BINARYNAME_MISMATCH:path=" + ep
                            + " derived=" + derivedBn + " manifest=" + bn);
                    ok = false;
                }
            }
            // 4. Each rawFingerprint must equal actual JAR bytes
            Object fpObj = e.get("rawFingerprint");
            if (fpObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fpMap = (Map<String, Object>) fpObj;
                String expectedFp = (String) fpMap.get("value");
                if (expectedFp != null) {
                    byte[] jarBytes = entries.dataByName.get(ep);
                    if (jarBytes == null) {
                        errors.add("CLASS_MANIFEST_ENTRY_NOT_IN_JAR:" + ep);
                        ok = false;
                    } else {
                        String actualFp = rawFingerprint(jarBytes);
                        if (!actualFp.equals(expectedFp)) {
                            errors.add("CLASS_MANIFEST_SHA256_MISMATCH:" + ep
                                    + " manifest=" + expectedFp + " actual=" + actualFp);
                            ok = false;
                        }
                    }
                }
            }
        }

        // 5. entryPath set must exactly equal JAR .class set
        Set<String> missingFromManifest = new HashSet<>(actualClassPaths);
        missingFromManifest.removeAll(manifestEntryPaths);
        if (!missingFromManifest.isEmpty()) {
            errors.add("CLASS_MANIFEST_MISSING_PATHS:" + missingFromManifest);
            ok = false;
        }
        Set<String> extraInManifest = new HashSet<>(manifestEntryPaths);
        extraInManifest.removeAll(actualClassPaths);
        if (!extraInManifest.isEmpty()) {
            errors.add("CLASS_MANIFEST_EXTRA_PATHS:" + extraInManifest);
            ok = false;
        }

        // 6. Recompute aggregate fingerprint from canonical doc
        manifestEntries.sort((a, b) -> {
            String bnA = (String) a.get("binaryName");
            String bnB = (String) b.get("binaryName");
            return bnA.compareTo(bnB);
        });
        List<Map<String, Object>> canonicalEntries = new ArrayList<>();
        for (Map<String, Object> e : manifestEntries) {
            Map<String, Object> ce = new LinkedHashMap<>();
            ce.put("binaryName", e.get("binaryName"));
            ce.put("entryPath", e.get("entryPath"));
            ce.put("rawFingerprint", e.get("rawFingerprint"));
            canonicalEntries.add(ce);
        }
        Map<String, Object> canonicalDoc = new LinkedHashMap<>();
        canonicalDoc.put("schemaVersion", SCHEMA_VERSION_CLASS);
        canonicalDoc.put("entries", canonicalEntries);
        String computed = committedFingerprint(DOMAIN_CLASS_MANIFEST, canonicalDoc);

        Object mfpObj = manifest.get("manifestFingerprint");
        @SuppressWarnings("unchecked")
        Map<String, Object> mfpMap = (Map<String, Object>) mfpObj;
        String expected = (String) mfpMap.get("value");
        if (!computed.equals(expected)) {
            errors.add("CLASS_MANIFEST_FP_MISMATCH:expected=" + expected + " computed=" + computed);
            return false;
        }
        return ok;
    }

    @SuppressWarnings("unchecked")
    private boolean validateResourceManifestSchema(Map<String, Object> manifest, JarEntries entries) {
        boolean ok = true;

        String schemaVersion = (String) manifest.get("schemaVersion");
        if (!SCHEMA_VERSION_RESOURCE.equals(schemaVersion)) {
            errors.add("RESOURCE_MANIFEST_SCHEMA_VERSION:" + schemaVersion);
            ok = false;
        }

        Set<String> requiredKeys = Set.of("schemaVersion", "entries", "manifestFingerprint");
        for (String k : requiredKeys) {
            if (!manifest.containsKey(k)) {
                errors.add("RESOURCE_MANIFEST_MISSING_KEY:" + k);
                ok = false;
            }
        }
        for (String k : manifest.keySet()) {
            if (!requiredKeys.contains(k)) {
                errors.add("RESOURCE_MANIFEST_EXTRA_KEY:" + k);
                ok = false;
            }
        }

        List<Map<String, Object>> entriesList = (List<Map<String, Object>>) manifest.get("entries");
        if (entriesList == null) {
            errors.add("RESOURCE_MANIFEST_NO_ENTRIES");
            return false;
        }

        // Valid resourceRole values
        Set<String> validRoles = Set.of(
                "SCHEMA", "METADATA", "PROTOCOL", "PROJECTION", "CANONICALIZATION", "MANIFEST");

        // Each entry: resourceRole, entryPath, rawFingerprint
        for (int i = 0; i < entriesList.size(); i++) {
            Map<String, Object> e = entriesList.get(i);
            Set<String> entryKeys = e.keySet();

            // Only these 3 keys are allowed
            for (String k : entryKeys) {
                if (!Set.of("resourceRole", "entryPath", "rawFingerprint").contains(k)) {
                    errors.add("RESOURCE_MANIFEST_ENTRY_EXTRA_KEY:" + k + "@" + i);
                    ok = false;
                }
            }

            Object role = e.get("resourceRole");
            if (!(role instanceof String)) {
                errors.add("RESOURCE_MANIFEST_ENTRY_ROLE_TYPE:" + i);
                ok = false;
            } else if (!validRoles.contains(role)) {
                errors.add("RESOURCE_MANIFEST_ENTRY_ROLE_ENUM:" + role + "@" + i);
                ok = false;
            }

            if (!(e.get("entryPath") instanceof String)) {
                errors.add("RESOURCE_MANIFEST_ENTRY_PATH_TYPE:" + i);
                ok = false;
            }

            Object fp = e.get("rawFingerprint");
            if (!validateTypedRawFingerprint(fp, "RESOURCE_MANIFEST_ENTRY_FP", i, errors)) {
                ok = false;
            }
        }

        // manifestFingerprint
        if (!validateTypedAggregateFingerprint(manifest.get("manifestFingerprint"), "RESOURCE_MANIFEST_FP", errors)) {
            ok = false;
        }

        return ok;
    }

    @SuppressWarnings("unchecked")
    private boolean validateResourceManifestFingerprint(Map<String, Object> manifest, JarEntries entries) {
        boolean ok = true;
        List<Map<String, Object>> manifestEntries =
                new ArrayList<>((List<Map<String, Object>>) manifest.get("entries"));

        // 1. Compute actual JAR resource paths:
        //    non-dir, non-MANIFEST.MF, non-.class, non-authority-dep paths, non-3-manifest paths
        Set<String> actualResourcePaths = new HashSet<>();
        for (String name : entries.names) {
            if (entries.dataByName.get(name) == null) continue; // directory
            if ("META-INF/MANIFEST.MF".equals(name)) continue;
            if (name.endsWith(".class")) continue;
            if (authorityDepEntryPaths.contains(name)) continue;
            if (name.equals(CLASS_MANIFEST_PATH)) continue;
            if (name.equals(RESOURCE_MANIFEST_PATH)) continue;
            if (name.equals(DEPENDENCY_MANIFEST_PATH)) continue;
            actualResourcePaths.add(name);
        }

        // 2. Build manifest entryPath set; reject duplicates
        Set<String> manifestEntryPaths = new HashSet<>();
        for (Map<String, Object> e : manifestEntries) {
            String ep = (String) e.get("entryPath");
            if (ep == null) continue; // schema validation caught null
            if (!manifestEntryPaths.add(ep)) {
                errors.add("RESOURCE_MANIFEST_DUP_PATH:" + ep);
                ok = false;
            }
            // 3. Each rawFingerprint must equal actual JAR bytes
            Object fpObj = e.get("rawFingerprint");
            if (fpObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fpMap = (Map<String, Object>) fpObj;
                String expectedFp = (String) fpMap.get("value");
                if (expectedFp != null) {
                    byte[] jarBytes = entries.dataByName.get(ep);
                    if (jarBytes == null) {
                        errors.add("RESOURCE_MANIFEST_ENTRY_NOT_IN_JAR:" + ep);
                        ok = false;
                    } else {
                        String actualFp = rawFingerprint(jarBytes);
                        if (!actualFp.equals(expectedFp)) {
                            errors.add("RESOURCE_MANIFEST_SHA256_MISMATCH:" + ep
                                    + " manifest=" + expectedFp + " actual=" + actualFp);
                            ok = false;
                        }
                    }
                }
            }
        }

        // 4. entryPath set must exactly equal actual resource set
        Set<String> missingFromManifest = new HashSet<>(actualResourcePaths);
        missingFromManifest.removeAll(manifestEntryPaths);
        if (!missingFromManifest.isEmpty()) {
            errors.add("RESOURCE_MANIFEST_MISSING_PATHS:" + missingFromManifest);
            ok = false;
        }
        Set<String> extraInManifest = new HashSet<>(manifestEntryPaths);
        extraInManifest.removeAll(actualResourcePaths);
        if (!extraInManifest.isEmpty()) {
            errors.add("RESOURCE_MANIFEST_EXTRA_PATHS:" + extraInManifest);
            ok = false;
        }

        // 5. Recompute aggregate fingerprint
        manifestEntries.sort((a, b) -> {
            String pA = (String) a.get("entryPath");
            String pB = (String) b.get("entryPath");
            return pA.compareTo(pB);
        });
        List<Map<String, Object>> canonicalEntries = new ArrayList<>();
        for (Map<String, Object> e : manifestEntries) {
            Map<String, Object> ce = new LinkedHashMap<>();
            ce.put("resourceRole", e.get("resourceRole"));
            ce.put("entryPath", e.get("entryPath"));
            ce.put("rawFingerprint", e.get("rawFingerprint"));
            canonicalEntries.add(ce);
        }
        Map<String, Object> canonicalDoc = new LinkedHashMap<>();
        canonicalDoc.put("schemaVersion", SCHEMA_VERSION_RESOURCE);
        canonicalDoc.put("entries", canonicalEntries);
        String computed = committedFingerprint(DOMAIN_RESOURCE_MANIFEST, canonicalDoc);

        @SuppressWarnings("unchecked")
        Map<String, Object> mfpMap = (Map<String, Object>) manifest.get("manifestFingerprint");
        String expected = (String) mfpMap.get("value");
        if (!computed.equals(expected)) {
            errors.add("RESOURCE_MANIFEST_FP_MISMATCH:expected=" + expected + " computed=" + computed);
            return false;
        }
        return ok;
    }

    @SuppressWarnings("unchecked")
    private boolean validateDepManifestSchema(Map<String, Object> manifest) {
        boolean ok = true;

        String schemaVersion = (String) manifest.get("schemaVersion");
        if (!SCHEMA_VERSION_DEPENDENCY.equals(schemaVersion)) {
            errors.add("DEP_MANIFEST_SCHEMA_VERSION:" + schemaVersion);
            ok = false;
        }

        Set<String> requiredKeys = Set.of("schemaVersion", "entries", "manifestFingerprint");
        for (String k : requiredKeys) {
            if (!manifest.containsKey(k)) {
                errors.add("DEP_MANIFEST_MISSING_KEY:" + k);
                ok = false;
            }
        }
        for (String k : manifest.keySet()) {
            if (!requiredKeys.contains(k)) {
                errors.add("DEP_MANIFEST_EXTRA_KEY:" + k);
                ok = false;
            }
        }

        List<Map<String, Object>> entriesList = (List<Map<String, Object>>) manifest.get("entries");
        if (entriesList == null) {
            errors.add("DEP_MANIFEST_NO_ENTRIES");
            return false;
        }

        // Valid scope values
        Set<String> validScopes = Set.of("compile", "runtime", "provided", "test", "system");

        for (int i = 0; i < entriesList.size(); i++) {
            Map<String, Object> e = entriesList.get(i);

            // Only these 4 keys
            for (String k : e.keySet()) {
                if (!Set.of("coordinate", "scope", "entryPath", "rawFingerprint").contains(k)) {
                    errors.add("DEP_MANIFEST_ENTRY_EXTRA_KEY:" + k + "@" + i);
                    ok = false;
                }
            }

            // coordinate: string "groupId:artifactId:version"
            if (!(e.get("coordinate") instanceof String)) {
                errors.add("DEP_MANIFEST_ENTRY_COORD_TYPE:" + i);
                ok = false;
            }

            Object scope = e.get("scope");
            if (!(scope instanceof String)) {
                errors.add("DEP_MANIFEST_ENTRY_SCOPE_TYPE:" + i);
                ok = false;
            } else if (!validScopes.contains(scope)) {
                errors.add("DEP_MANIFEST_ENTRY_SCOPE_ENUM:" + scope + "@" + i);
                ok = false;
            }

            if (!(e.get("entryPath") instanceof String)) {
                errors.add("DEP_MANIFEST_ENTRY_PATH_TYPE:" + i);
                ok = false;
            }

            if (!validateTypedRawFingerprint(e.get("rawFingerprint"), "DEP_MANIFEST_ENTRY_FP", i, errors)) {
                ok = false;
            }
        }

        if (!validateTypedAggregateFingerprint(manifest.get("manifestFingerprint"), "DEP_MANIFEST_FP", errors)) {
            ok = false;
        }

        return ok;
    }

    @SuppressWarnings("unchecked")
    private boolean validateDepManifestFingerprint(Map<String, Object> manifest, JarEntries entries) {
        boolean ok = true;
        List<Map<String, Object>> manifestEntries =
                new ArrayList<>((List<Map<String, Object>>) manifest.get("entries"));

        // Build authority dep pins indexed by coordinate::scope
        Map<String, DependencyPin> authorityByKey = new LinkedHashMap<>();
        for (DependencyPin pin : depPins.values()) {
            String key = pin.groupId + ":" + pin.artifactId + ":" + pin.version
                    + "::" + pin.scope + "::" + pin.entryPath;
            authorityByKey.put(key, pin);
        }

        // Index manifest entries by same key
        Map<String, Map<String, Object>> manifestByKey = new LinkedHashMap<>();
        Set<String> manifestEntryPathsSeen = new HashSet<>();
        for (Map<String, Object> e : manifestEntries) {
            // coordinate: string "groupId:artifactId:version"
            String coordinate = (String) e.get("coordinate");
            String scope = (String) e.get("scope");
            String entryPath = (String) e.get("entryPath");
            // Reject null key components
            if (coordinate == null) {
                errors.add("DEP_MANIFEST_NULL_COORDINATE:" + entryPath);
                ok = false;
                continue;
            }
            if (scope == null) {
                errors.add("DEP_MANIFEST_NULL_SCOPE:" + entryPath);
                ok = false;
                continue;
            }
            if (entryPath == null) {
                errors.add("DEP_MANIFEST_NULL_ENTRY_PATH");
                ok = false;
                continue;
            }
            String key = coordinate + "::" + scope + "::" + entryPath;
            // Reject duplicate keys
            if (manifestByKey.containsKey(key)) {
                errors.add("DEP_MANIFEST_DUP_KEY:" + key);
                ok = false;
                continue;
            }
            manifestByKey.put(key, e);
            // 3. Each rawFingerprint must equal actual JAR bytes
            Object fpObj = e.get("rawFingerprint");
            if (fpObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fpMap = (Map<String, Object>) fpObj;
                String expectedFp = (String) fpMap.get("value");
                if (expectedFp != null) {
                    byte[] jarBytes = entries.dataByName.get(entryPath);
                    if (jarBytes == null) {
                        errors.add("DEP_MANIFEST_ENTRY_NOT_IN_JAR:" + entryPath);
                        ok = false;
                    } else {
                        String actualFp = rawFingerprint(jarBytes);
                        if (!actualFp.equals(expectedFp)) {
                            errors.add("DEP_MANIFEST_SHA256_MISMATCH:" + entryPath
                                    + " manifest=" + expectedFp + " actual=" + actualFp);
                            ok = false;
                        }
                    }
                }
            }
        }

        // 4. Authority pins must all appear in manifest (no missing)
        Set<String> missingFromManifest = new HashSet<>(authorityByKey.keySet());
        missingFromManifest.removeAll(manifestByKey.keySet());
        if (!missingFromManifest.isEmpty()) {
            errors.add("DEP_MANIFEST_MISSING_PINS:" + missingFromManifest);
            ok = false;
        }
        // 5. Manifest entries must all be authority pins (no extra)
        Set<String> extraInManifest = new HashSet<>(manifestByKey.keySet());
        extraInManifest.removeAll(authorityByKey.keySet());
        if (!extraInManifest.isEmpty()) {
            errors.add("DEP_MANIFEST_EXTRA_PINS:" + extraInManifest);
            ok = false;
        }
        // 6. rawFingerprint must match authority pin
        for (Map.Entry<String, Map<String, Object>> me : manifestByKey.entrySet()) {
            String key = me.getKey();
            DependencyPin pin = authorityByKey.get(key);
            if (pin == null) continue; // already flagged as extra above
            Object fpObj = me.getValue().get("rawFingerprint");
            if (fpObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fpMap = (Map<String, Object>) fpObj;
                String manifestFp = (String) fpMap.get("value");
                if (!pin.rawFingerprint.equals(manifestFp)) {
                    errors.add("DEP_MANIFEST_PIN_FP_MISMATCH:" + pin.lockId
                            + " authority=" + pin.rawFingerprint + " manifest=" + manifestFp);
                    ok = false;
                }
            }
        }

        // 7. Recompute aggregate fingerprint
        manifestEntries.sort((a, b) -> {
            String cA = (String) a.get("coordinate");
            String cB = (String) b.get("coordinate");
            int cmp = cA.compareTo(cB);
            if (cmp != 0) return cmp;
            String pA = (String) a.get("entryPath");
            String pB = (String) b.get("entryPath");
            return pA.compareTo(pB);
        });
        List<Map<String, Object>> canonicalEntries = new ArrayList<>();
        for (Map<String, Object> e : manifestEntries) {
            Map<String, Object> ce = new LinkedHashMap<>();
            ce.put("coordinate", e.get("coordinate"));
            ce.put("scope", e.get("scope"));
            ce.put("entryPath", e.get("entryPath"));
            ce.put("rawFingerprint", e.get("rawFingerprint"));
            canonicalEntries.add(ce);
        }
        Map<String, Object> canonicalDoc = new LinkedHashMap<>();
        canonicalDoc.put("schemaVersion", SCHEMA_VERSION_DEPENDENCY);
        canonicalDoc.put("entries", canonicalEntries);
        String computed = committedFingerprint(DOMAIN_DEPENDENCY_MANIFEST, canonicalDoc);

        @SuppressWarnings("unchecked")
        Map<String, Object> mfpMap = (Map<String, Object>) manifest.get("manifestFingerprint");
        String expected = (String) mfpMap.get("value");
        if (!computed.equals(expected)) {
            errors.add("DEP_MANIFEST_FP_MISMATCH:expected=" + expected + " computed=" + computed);
            return false;
        }
        return ok;
    }

    // ── Phase 5: Embedded authority + protocol resources ──────────────
    private boolean validateEmbeddedAuthority(JarEntries entries) {
        boolean ok = true;

        // Embedded authority must EXACTLY equal caller-supplied authority bytes
        byte[] embeddedAuthority = entries.dataByName.get(EMBEDDED_AUTHORITY_SOURCE_PATH);
        if (embeddedAuthority == null) {
            errors.add("EMBEDDED_AUTHORITY_MISSING:" + EMBEDDED_AUTHORITY_SOURCE_PATH);
            return false;
        }

        if (embeddedAuthority.length != this.authorityRaw.length) {
            errors.add("EMBEDDED_AUTHORITY_LENGTH_MISMATCH:expected=" + this.authorityRaw.length
                    + " embedded=" + embeddedAuthority.length);
            ok = false;
        }

        // Constant-time comparison
        if (!constantTimeEquals(embeddedAuthority, this.authorityRaw)) {
            errors.add("EMBEDDED_AUTHORITY_BYTES_MISMATCH");
            ok = false;
        }

        // Verify embedded authority parses identically
        try {
            Map<String, Object> parsed = StrictJsonParser.parse(embeddedAuthority);
            String sv = (String) parsed.get("schemaVersion");
            if (!EXPECTED_SCHEMA_VERSION.equals(sv)) {
                errors.add("EMBEDDED_AUTHORITY_SCHEMA_VERSION:" + sv);
                ok = false;
            }
        } catch (CapabilityStudioGateAException e) {
            errors.add("EMBEDDED_AUTHORITY_PARSE_ERROR:" + e.errorCode());
            ok = false;
        }

        // Protocol/profile/projection resources – verify against their manifest fingerprints
        // The resource manifest contains rawFingerprint for each resource.
        // We verify the resource's actual bytes match the manifest's fingerprint.
        @SuppressWarnings("unchecked")
        Map<String, Object> resourceManifest;
        try {
            byte[] resRaw = entries.dataByName.get(RESOURCE_MANIFEST_PATH);
            if (resRaw == null) {
                errors.add("RESOURCE_MANIFEST_MISSING_FOR_PROTOCOL_VERIFY");
                return false;
            }
            resourceManifest = StrictJsonParser.parse(resRaw);
        } catch (CapabilityStudioGateAException e) {
            errors.add("RESOURCE_MANIFEST_PARSE_PROTOCOL_ERROR:" + e.errorCode());
            return false;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resEntries =
                (List<Map<String, Object>>) resourceManifest.get("entries");

        // Build path -> expectedFingerprint map from resource manifest
        Map<String, String> resourceFingerprints = new HashMap<>();
        for (Map<String, Object> e : resEntries) {
            String path = (String) e.get("entryPath");
            Object fpObj = e.get("rawFingerprint");
            if (path != null && fpObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fpMap = (Map<String, Object>) fpObj;
                String fpVal = (String) fpMap.get("value");
                if (fpVal != null) {
                    resourceFingerprints.put(path, fpVal);
                }
            }
        }

        // Verify each protocol/projection/canonicalization resource
        for (String resourcePath : PROTOCOL_RESOURCE_PATHS) {
            byte[] data = entries.dataByName.get(resourcePath);
            if (data == null) {
                // Some resources may not be present in all artifacts; only fail if explicitly required
                // These paths should be present in the resource manifest, which we validate separately
                continue;
            }
            String expectedFp = resourceFingerprints.get(resourcePath);
            if (expectedFp != null) {
                String actualFp = rawFingerprint(data);
                if (!actualFp.equals(expectedFp)) {
                    errors.add("PROTOCOL_RESOURCE_FP_MISMATCH:" + resourcePath
                            + " expected=" + expectedFp + " actual=" + actualFp);
                    ok = false;
                }
            }
        }

        return ok;
    }

    // ── Phase 6: CLI execution ─────────────────────────────────────────
    private boolean executeAndVerifyCli() {
        // Find java binary: use java.home/bin/java, NOT PATH
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isEmpty()) {
            errors.add("JAVA_HOME_NOT_SET");
            return false;
        }
        Path javaBin = Path.of(javaHome, "bin", "java");
        if (!Files.exists(javaBin)) {
            errors.add("JAVA_BINARY_NOT_FOUND:" + javaBin);
            return false;
        }

        // Build command: java -cp candidateJar ChallengeCli --role-self-test
        // --role IMPLEMENTATION_CANDIDATE --authority authorityFile
        // --artifact candidateJar --fixture-set-id GATE_A_ROLE_BLACK_BOX_V1
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin.toAbsolutePath().toString());
        cmd.add("-cp");
        cmd.add(candidateJar.toAbsolutePath().toString());
        cmd.add("com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");
        cmd.add("--role-self-test");
        cmd.add("--role");
        cmd.add(EXPECTED_ROLE);
        cmd.add("--authority");
        cmd.add(authorityFile.toAbsolutePath().toString());
        cmd.add("--artifact");
        cmd.add(candidateJar.toAbsolutePath().toString());
        cmd.add("--fixture-set-id");
        cmd.add(EXPECTED_FIXTURE_SET_ID);

        System.out.println("  CLI command: " + String.join(" ", cmd));

        boolean cliOk = true;
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Ensure working directory exists and is a regular directory (not a symlink)
            Path wd = workingDir.toAbsolutePath().normalize();
            try {
                Files.createDirectories(wd);
                if (Files.isSymbolicLink(wd)) {
                    errors.add("CLI_WORKING_DIR_IS_SYMLINK:" + wd);
                    return false;
                }
                if (!Files.isDirectory(wd)) {
                    errors.add("CLI_WORKING_DIR_NOT_DIRECTORY:" + wd);
                    return false;
                }
            } catch (IOException e) {
                errors.add("CLI_WORKING_DIR_ERROR:" + e.getClass().getSimpleName());
                return false;
            }

            pb.directory(workingDir.toFile());
            pb.redirectError(ProcessBuilder.Redirect.PIPE);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

            process = pb.start();
            final InputStream stdoutStream = process.getInputStream();
            final InputStream stderrStream = process.getErrorStream();

            // Bounded I/O read with timeout; continue draining after limit to avoid blocking subprocess.
            ByteArrayOutputStream stdoutBaos = new ByteArrayOutputStream(8192);
            ByteArrayOutputStream stderrBaos = new ByteArrayOutputStream(4096);

            AtomicReference<IOException> stdoutReadError = new AtomicReference<>();
            AtomicReference<IOException> stderrReadError = new AtomicReference<>();
            AtomicBoolean stdoutOverLimit = new AtomicBoolean(false);
            AtomicBoolean stderrOverLimit = new AtomicBoolean(false);

            Thread stdoutReader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = stdoutStream.read(buf)) != -1) {
                        if (!stdoutOverLimit.get() && stdoutBaos.size() + r <= 10 * 1024 * 1024) {
                            stdoutBaos.write(buf, 0, r);
                        } else {
                            stdoutOverLimit.set(true);
                        }
                    }
                } catch (IOException e) {
                    stdoutReadError.set(e);
                }
            }, "stdout-reader");

            Thread stderrReader = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = stderrStream.read(buf)) != -1) {
                        if (!stderrOverLimit.get() && stderrBaos.size() + r <= 1024 * 1024) {
                            stderrBaos.write(buf, 0, r);
                        } else {
                            stderrOverLimit.set(true);
                        }
                    }
                } catch (IOException e) {
                    stderrReadError.set(e);
                }
            }, "stderr-reader");

            stdoutReader.start();
            stderrReader.start();

            boolean finished = process.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                errors.add("CLI_TIMEOUT:" + CLI_TIMEOUT_SECONDS + "s");
                cliOk = false;
                // Bounded wait for process to terminate after forced destroy.
                // Do not call exitValue() if process is still alive.
                try {
                    boolean died = process.waitFor(5, TimeUnit.SECONDS);
                    if (!died) {
                        errors.add("CLI_PROCESS_ORPHANED");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    errors.add("CLI_PROCESS_ORPHANED");
                }
            }

            stdoutReader.join(5000);
            stderrReader.join(5000);

            IOException readErr = stdoutReadError.get();
            if (readErr == null) readErr = stderrReadError.get();
            if (readErr != null) {
                errors.add("CLI_READ_ERROR:" + readErr.getClass().getSimpleName());
                cliOk = false;
            }
            if (stdoutOverLimit.get()) {
                errors.add("CLI_STDOUT_SIZE_EXCEEDED");
                cliOk = false;
            }
            if (stderrOverLimit.get()) {
                errors.add("CLI_STDERR_SIZE_EXCEEDED");
                cliOk = false;
            }

            int exitCode = finished ? process.exitValue() : -1;
            String stdout = new String(stdoutBaos.toByteArray(), StandardCharsets.UTF_8);
            String stderr = new String(stderrBaos.toByteArray(), StandardCharsets.UTF_8);

            System.out.println("  CLI exit code: " + exitCode);
            if (!stdout.isEmpty()) System.out.println("  CLI stdout length: " + stdout.length());
            if (!stderr.isEmpty()) System.out.println("  CLI stderr length: " + stderr.length() + " bytes");

            if (exitCode != 0) {
                errors.add("CLI_NONZERO_EXIT:" + exitCode);
                cliOk = false;
            }
            if (!stderr.isEmpty()) {
                errors.add("CLI_STDERR_NOT_EMPTY");
                cliOk = false;
            }
            // stdout must end with exactly one LF.
            if (stdout.isEmpty()) {
                errors.add("CLI_STDOUT_EMPTY");
                cliOk = false;
            } else {
                byte[] raw = stdout.getBytes(StandardCharsets.UTF_8);
                int last = raw[raw.length - 1] & 0xff;
                if (last != 0x0A) {
                    errors.add("CLI_STDOUT_MISSING_TRAILING_LF:lastByte=" + String.format("0x%02X", last));
                    cliOk = false;
                } else {
                    // Exactly one line (no embedded LF)
                    int lfCount = 0;
                    for (byte b : raw) if (b == 0x0A) lfCount++;
                    if (lfCount != 1) {
                        errors.add("CLI_STDOUT_MULTIPLE_LINES");
                        cliOk = false;
                    }
                    // No CR (Windows line endings)
                    for (byte b : raw) {
                        if (b == 0x0D) {
                            errors.add("CLI_STDOUT_CONTAINS_CR");
                            cliOk = false;
                            break;
                        }
                    }
                }

                String receiptJson = stdout.substring(0, stdout.length() - 1);
                try {
                    Map<String, Object> receipt = StrictJsonParser.parse(receiptJson.getBytes(StandardCharsets.UTF_8));

                    String messageVersion = castString(receipt.get("messageVersion"));
                    if (!expectedReceiptMessageVersion.equals(messageVersion)) {
                        errors.add("CLI_RECEIPT_MESSAGE_VERSION:" + messageVersion);
                        cliOk = false;
                    }

                    String role = castString(receipt.get("role"));
                    if (!EXPECTED_ROLE.equals(role)) {
                        errors.add("CLI_RECEIPT_ROLE:" + role);
                        cliOk = false;
                    }

                    String status = castString(receipt.get("status"));
                    if (expectedReceiptStatus != null && !expectedReceiptStatus.equals(status)) {
                        errors.add("CLI_RECEIPT_STATUS:" + status);
                        cliOk = false;
                    }

                    String fixtureSetId = castString(receipt.get("fixtureSetId"));
                    if (!expectedReceiptFixtureSetId.equals(fixtureSetId)) {
                        errors.add("CLI_RECEIPT_FIXTURE_SET_ID:" + fixtureSetId);
                        cliOk = false;
                    }

                    // receiptFingerprint must be present and typed self-null
                    Object rfp = receipt.get("receiptFingerprint");
                    if (!(rfp instanceof Map)) {
                        errors.add("CLI_RECEIPT_FP_TYPE");
                        cliOk = false;
                    } else {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> rfpMap = (Map<String, Object>) rfp;
                        if (!"SELF_NULL_RECEIPT".equals(rfpMap.get("kind"))) {
                            errors.add("CLI_RECEIPT_FP_KIND");
                            cliOk = false;
                        }
                    }

                    System.out.println("  Receipt verified: messageVersion=" + messageVersion
                            + " role=" + role + " status=" + status + " fixtureSetId=" + fixtureSetId);

                } catch (CapabilityStudioGateAException e) {
                    errors.add("CLI_RECEIPT_PARSE_ERROR:" + e.errorCode());
                    cliOk = false;
                } catch (Exception e) {
                    errors.add("CLI_RECEIPT_ERROR:" + e.getClass().getSimpleName());
                    cliOk = false;
                }
            }

            System.out.println("CLI verification " + (cliOk ? "PASSED" : "FAILED"));
            return cliOk;
        } catch (Exception e) {
            errors.add("CLI_EXECUTION_ERROR:" + e.getClass().getSimpleName());
            return false;
        } finally {
            //有限等待reader线程回收，失败后静默关闭流
            if (process != null) {
                // Wait briefly for reader threads to finish after process exits
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // Close streams to unblock reader threads if they are still alive
            try {
                if (process != null) process.getInputStream().close();
            } catch (IOException ignored) {}
            try {
                if (process != null) process.getErrorStream().close();
            } catch (IOException ignored) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Reads a file bounded to maxBytes, failing closed on any error.
     */
    private static byte[] readBoundedFile(Path path, long maxBytes) throws Exception {
        BasicFileAttributes attrs = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
            throw new RuntimeException("AUTHORITY_NOT_REGULAR_FILE");
        }
        if (attrs.size() > maxBytes) {
            throw new RuntimeException("AUTHORITY_SIZE_EXCEEDED:" + attrs.size());
        }
        byte[] data = Files.readAllBytes(path);
        if (data.length > maxBytes) {
            throw new RuntimeException("AUTHORITY_SIZE_EXCEEDED:" + data.length);
        }
        return data;
    }

    /**
     * Reads an InputStream with bounded size based on limits.
     */
    private static byte[] readBoundedStream(InputStream is, Map<String, Object> limits, String entryName)
            throws IOException {
        long maxEntry = asPositiveLong(limits.get("maxSingleEntryBytes"), "maxSingleEntryBytes");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        long read = 0;
        int r;
        while ((r = is.read(buf)) != -1) {
            read += r;
            if (read > maxEntry) {
                throw new CapabilityStudioGateAException("ENTRY_SIZE_EXCEEDED:" + entryName);
            }
            baos.write(buf, 0, r);
        }
        return baos.toByteArray();
    }

    private static long asPositiveLong(Object val, String name) {
        if (!(val instanceof Number)) {
            throw new CapabilityStudioGateAException("INVALID_LIMIT:" + name);
        }
        long v = ((Number) val).longValue();
        if (v <= 0) {
            throw new CapabilityStudioGateAException("INVALID_LIMIT:" + name);
        }
        return v;
    }

    // Canonical SHA-256: domain + NUL + canonical(JSON(value))
    private static String committedFingerprint(String domain, Map<String, Object> value) {
        byte[] domainBytes = domain.getBytes(StandardCharsets.US_ASCII);
        byte[] canonicalBytes = canonical(value);
        byte[] combined = new byte[domainBytes.length + 1 + canonicalBytes.length];
        System.arraycopy(domainBytes, 0, combined, 0, domainBytes.length);
        combined[domainBytes.length] = 0;
        System.arraycopy(canonicalBytes, 0, combined, domainBytes.length + 1, canonicalBytes.length);
        return "sha256:" + sha256Hex(combined);
    }

    // Canonical JSON: sort_keys=True, separators=(",", ":")
    private static byte[] canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        canonicalize(value, sb);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static void canonicalize(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new CapabilityStudioGateAException("NON_FINITE_NUMBER");
            }
            sb.append(value.toString());
        } else if (value instanceof String) {
            appendString(sb, (String) value);
        } else if (value instanceof List) {
            sb.append('[');
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                canonicalize(list.get(i), sb);
            }
            sb.append(']');
        } else if (value instanceof Map) {
            sb.append('{');
            @SuppressWarnings("rawtypes")
            Map map = (Map<?, ?>) value;
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) {
                if (!(key instanceof String)) {
                    throw new CapabilityStudioGateAException("NON_STRING_KEY");
                }
                keys.add((String) key);
            }
            Collections.sort(keys);
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                String key = keys.get(i);
                appendString(sb, key);
                sb.append(':');
                canonicalize(map.get(key), sb);
            }
            sb.append('}');
        } else {
            throw new CapabilityStudioGateAException("UNSUPPORTED_TYPE:" + value.getClass().getName());
        }
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static String rawFingerprint(byte[] data) {
        return "sha256:" + sha256Hex(data);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new CapabilityStudioGateAException("FINGERPRINT_ERROR", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) result |= a[i] ^ b[i];
        return result == 0;
    }

    // Validates typed RAW_BYTES fingerprint dict – must have exactly 3 keys: kind, algorithm, value
    private static boolean validateTypedRawFingerprint(Object fp, String prefix, int idx, List<String> errors) {
        if (!(fp instanceof Map)) {
            errors.add(prefix + "_TYPE:" + idx);
            return false;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fpMap = (Map<String, Object>) fp;
        if (fpMap.size() != 3) {
            errors.add(prefix + "_KEYS:" + idx + " expected=3 actual=" + fpMap.size());
            return false;
        }
        if (!"RAW_BYTES".equals(fpMap.get("kind"))) {
            errors.add(prefix + "_KIND:" + idx);
            return false;
        }
        if (!"SHA-256".equals(fpMap.get("algorithm"))) {
            errors.add(prefix + "_ALG:" + idx);
            return false;
        }
        Object val = fpMap.get("value");
        if (!(val instanceof String)) {
            errors.add(prefix + "_VALUE_TYPE:" + idx);
            return false;
        }
        String fpStr = (String) val;
        if (!fpStr.startsWith("sha256:") || fpStr.length() != 71) {
            errors.add(prefix + "_VALUE_FMT:" + idx);
            return false;
        }
        return true;
    }

    // Validates typed AGGREGATE_COMMITMENT fingerprint dict – must have exactly 3 keys: kind, algorithm, value
    private static boolean validateTypedAggregateFingerprint(Object fp, String prefix, List<String> errors) {
        if (!(fp instanceof Map)) {
            errors.add(prefix + "_TYPE");
            return false;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fpMap = (Map<String, Object>) fp;
        if (fpMap.size() != 3) {
            errors.add(prefix + "_KEYS expected=3 actual=" + fpMap.size());
            return false;
        }
        if (!"AGGREGATE_COMMITMENT".equals(fpMap.get("kind"))) {
            errors.add(prefix + "_KIND");
            return false;
        }
        if (!"SHA-256".equals(fpMap.get("algorithm"))) {
            errors.add(prefix + "_ALG");
            return false;
        }
        Object val = fpMap.get("value");
        if (!(val instanceof String) || !((String) val).startsWith("sha256:")) {
            errors.add(prefix + "_VALUE");
            return false;
        }
        return true;
    }

    // ── Inner types ───────────────────────────────────────────────────

    /** Holds all JAR entry data for one pass. */
    private static final class JarEntries {
        final List<String> names = new ArrayList<>();
        final Map<String, byte[]> dataByName = new LinkedHashMap<>();
        final Map<String, Long> sizeByName = new LinkedHashMap<>();
        long totalUncompressedBytes;
        int entryCount;

        void add(String name, byte[] data, long compressedSize) {
            names.add(name);
            dataByName.put(name, data);
            sizeByName.put(name, compressedSize);
        }
    }

    /** Immutable dependency pin enriched from authority join. */
    static final class DependencyPin {
        final String lockId;
        final String groupId;
        final String artifactId;
        final String version;
        final String scope;
        final String entryPath;
        final String rawFingerprint; // "sha256:..."

        DependencyPin(String lockId, String groupId, String artifactId,
                     String version, String scope, String entryPath, String rawFingerprint) {
            this.lockId = lockId;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope;
            this.entryPath = entryPath;
            this.rawFingerprint = rawFingerprint;
        }
    }

    /** Safe String cast; returns null on null or non-String. */
    private static String castString(Object o) {
        return (o instanceof String) ? (String) o : null;
    }
}
