package com.leanowtech.bloge.gateway.testkit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * JDK-only packager for Gate A IMPLEMENTATION_CANDIDATE JAR.
 *
 * Manifest schemas (exact field sets per authority packagingContract):
 * - Class manifest:    binaryName + entryPath + rawFingerprint
 * - Resource manifest: resourceRole + entryPath + rawFingerprint
 * - Dep manifest:     coordinate + scope + entryPath + rawFingerprint (NO lockId)
 *
 * Fingerprint domains:
 * - RG-CS-GATE-A-CLASS-MANIFEST-v1
 * - RG-CS-GATE-A-BUILD-RESOURCE-MANIFEST-v1
 * - RG-CS-GATE-A-DEPENDENCY-LOCK-MANIFEST-v1
 */
public final class CapabilityStudioGateACandidatePackager {

    private static final long FIXED_TIME_MS = 1700000000000L;

    // Manifest domain constants
    private static final String DOMAIN_CLASS_MANIFEST     = "RG-CS-GATE-A-CLASS-MANIFEST-v1";
    private static final String DOMAIN_RESOURCE_MANIFEST = "RG-CS-GATE-A-BUILD-RESOURCE-MANIFEST-v1";
    private static final String DOMAIN_DEPENDENCY_MANIFEST = "RG-CS-GATE-A-DEPENDENCY-LOCK-MANIFEST-v1";

    // Schema version constants
    private static final String SCHEMA_VERSION_CLASS      = "capability-studio.gate-a-class-manifest.v1";
    private static final String SCHEMA_VERSION_RESOURCE  = "capability-studio.gate-a-build-resource-manifest.v1";
    private static final String SCHEMA_VERSION_DEPENDENCY = "capability-studio.gate-a-dependency-lock-manifest.v1";

    // Required outer classes (StageAcceptanceAuthorityProvider/Verifier: full $ closure)
    private static final String[] REQUIRED_OUTER_CLASSES = {
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAArtifactValidator",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAAuthorityValidator",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAReceiptCanonicalizer",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateASchemaValidator",
            "com/leanowtech/bloge/gateway/testkit/StrictJsonParser",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAException",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityVerifier",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderRoleSelfTest",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderArtifactValidator",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderRuntimeProbe",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderReceiptComposer"
    };

    private final Path classesDir;
    private final Path outputJar;
    private final Path authorityFile;
    private final Path mavenRepo;
    private final Path schemaDir;
    private final Path canonicalizationDir;
    private final Path protocolCompilerDir;
    private final String gateSlice;

    // Manifest paths from authority (packagingContract)
    private String classManifestPath;
    private String resourceManifestPath;
    private String dependencyManifestPath;

    /**
     * Constructs a packager for Gate-A candidate JAR creation.
     *
     * @param classesDir          compiled class directory to include
     * @param outputJar           output path for the candidate JAR
     * @param authorityFile       path to the Gate-A protocol authority JSON
     * @param mavenRepo           local Maven repository root for dependency resolution
     * @param schemaDir           directory containing wire-protocol schemas
     * @param canonicalizationDir directory containing canonicalisation resources
     * @param protocolCompilerDir directory containing protocol compiler resources
     * @param gateSlice           gate slice identifier (e.g. A1.1)
     */
    public CapabilityStudioGateACandidatePackager(
            Path classesDir, Path outputJar, Path authorityFile, Path mavenRepo,
            Path schemaDir, Path canonicalizationDir, Path protocolCompilerDir, String gateSlice) {
        this.classesDir = classesDir;
        this.outputJar = outputJar;
        this.authorityFile = authorityFile;
        this.mavenRepo = mavenRepo;
        this.schemaDir = schemaDir;
        this.canonicalizationDir = canonicalizationDir;
        this.protocolCompilerDir = protocolCompilerDir;
        this.gateSlice = gateSlice;
    }

    /**
     * Entry point for the Gate-A candidate packager.
     *
     * @param args eight arguments: classesDir, outputJar, authorityFile, mavenRepo, schemaDir, canonicalizationDir, protocolCompilerDir, gateSlice
     * @throws Exception if packaging fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 8) {
            System.err.println("Usage: java ... <classesDir> <outputJar> <authorityFile> <mavenRepo> <schemaDir> <canonicalizationDir> <protocolCompilerDir> <gateSlice>");
            System.exit(1);
        }
        new CapabilityStudioGateACandidatePackager(
                Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
                Path.of(args[4]), Path.of(args[5]), Path.of(args[6]), args[7]).execute();
    }

    /**
     * Builds the Gate-A candidate JAR and writes it to the output path.
     *
     * @throws Exception if the JAR cannot be built
     */
    @SuppressWarnings("unchecked")
    public void execute() throws Exception {
        System.out.println("Gate A Candidate Packager starting...");
        System.out.println("  Gate slice: " + gateSlice);
        System.out.println("  Classes dir: " + classesDir);
        System.out.println("  Output JAR: " + outputJar);
        System.out.println("  Authority: " + authorityFile);

        byte[] authorityBytes = Files.readAllBytes(authorityFile);
        Map<String, Object> authority = StrictJsonParser.parse(authorityBytes);

        String sv = (String) authority.get("schemaVersion");
        if (!"capability-studio.gate-a-protocol-authority.v1".equals(sv)) {
            throw new RuntimeException("Unexpected schema version: " + sv);
        }

        List<Map<String, Object>> roles = (List<Map<String, Object>>) authority.get("roleContracts");
        Map<String, Object> implRole = roles.stream()
                .filter(r -> "IMPLEMENTATION_CANDIDATE".equals(r.get("role")))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("IMPLEMENTATION_CANDIDATE role not found"));

        // Read manifest paths from packagingContract
        Map<String, Object> pkg = (Map<String, Object>) implRole.get("packagingContract");
        this.classManifestPath = (String) pkg.get("classManifestEntryPath");
        this.resourceManifestPath = (String) pkg.get("resourceManifestEntryPath");
        this.dependencyManifestPath = (String) pkg.get("dependencyLockManifestEntryPath");

        List<String> visibleSchemaIds = (List<String>) implRole.get("visibleSchemaIds");
        List<String> requiredJarEntries = (List<String>) implRole.get("requiredJarEntries");
        List<String> runtimeDepIds = (List<String>) implRole.get("runtimeDependencyLockIds");

        Map<String, Object> limits = (Map<String, Object>) implRole.get("artifactLimits");
        Map<String, Object> artifactLimits = Map.of(
                "maxRawBytes", ((Number) limits.get("maxRawBytes")).longValue(),
                "maxZipEntries", ((Number) limits.get("maxZipEntries")).intValue(),
                "maxSingleEntryBytes", ((Number) limits.get("maxSingleEntryBytes")).longValue(),
                "maxTotalUncompressedBytes", ((Number) limits.get("maxTotalUncompressedBytes")).intValue()
        );

        // Build dep info list: join depAuth + embedded entries, fail if mismatch
        List<DependencyInfo> depInfoList = buildDependencyInfoList(authority, implRole, runtimeDepIds);

        List<EntryData> classEntries = collectClassEntries();
        List<EntryData> schemaEntries = collectSchemaEntries(visibleSchemaIds);

        // Load actual JAR bytes and verify fingerprints
        List<DependencyInfo> depInfoWithBytes = loadDependencyBytes(depInfoList);
        List<EntryData> depEntries = collectDependencyEntries(depInfoWithBytes);

        System.out.println("  Found " + classEntries.size() + " class entries");
        System.out.println("  Found " + schemaEntries.size() + " schema entries");
        System.out.println("  Found " + depEntries.size() + " dependency entries");

        List<EntryData> entries = new ArrayList<>();
        entries.addAll(classEntries);
        entries.addAll(depEntries);
        entries.addAll(schemaEntries);

        addMetadataEntries(entries, authorityBytes);

        // Build manifests BEFORE adding them to entries (so resource manifest excludes them)
        Map<String, Object> classManifest = buildClassManifest(classEntries);
        Map<String, Object> resourceManifest = buildResourceManifest(entries, classEntries, depEntries);
        Map<String, Object> depLockManifest = buildDependencyLockManifest(depInfoWithBytes);

        // Add manifests to entries
        entries.add(new EntryData(classManifestPath, canonicalJson(classManifest), EntryKind.MANIFEST));
        entries.add(new EntryData(resourceManifestPath, canonicalJson(resourceManifest), EntryKind.MANIFEST));
        entries.add(new EntryData(dependencyManifestPath, canonicalJson(depLockManifest), EntryKind.MANIFEST));

        selfCheck(entries, artifactLimits, requiredJarEntries, visibleSchemaIds, runtimeDepIds, depInfoList);

        writeJar(entries, outputJar);

        System.out.println("Packaging complete: " + outputJar);
        System.out.println("  Classes: " + classEntries.size());
        System.out.println("  Dependencies: " + depEntries.size());
        System.out.println("  Schemas: " + schemaEntries.size());
        System.out.println("  Total entries (incl MANIFEST.MF): " + (entries.size() + 1));
    }

    /**
     * Join depAuth + embedded entries on lockId+scope.
     * All lockIds from implRole.runtimeDependencyLockIds must match.
     * Fails if: depAuth missing, embedded missing, scope mismatch, duplicate keys, lockId set mismatch.
     */
    @SuppressWarnings("unchecked")
    private List<DependencyInfo> buildDependencyInfoList(Map<String, Object> authority,
                                                        Map<String, Object> implRole,
                                                        List<String> runtimeDepIds) {
        Map<String, Object> depAuth = (Map<String, Object>) authority.get("dependencyAuthority");
        List<Map<String, Object>> depAuthDeps = (List<Map<String, Object>>) depAuth.get("dependencies");

        // Build map with duplicate-key detection
        Map<String, Map<String, Object>> depAuthByKey = new LinkedHashMap<>();
        for (Map<String, Object> dep : depAuthDeps) {
            String lockId = (String) dep.get("lockId");
            String scope = (String) dep.get("scope");
            String key = lockId + "::" + scope;
            if (depAuthByKey.containsKey(key)) {
                throw new RuntimeException("Duplicate depAuth key: " + key);
            }
            depAuthByKey.put(key, dep);
        }

        Map<String, Object> pkg = (Map<String, Object>) implRole.get("packagingContract");
        List<Map<String, Object>> embEntries = (List<Map<String, Object>>) pkg.get("embeddedDependencyEntries");

        Map<String, Map<String, Object>> embByKey = new LinkedHashMap<>();
        for (Map<String, Object> e : embEntries) {
            String lockId = (String) e.get("lockId");
            String scope = (String) e.get("scope");
            String key = lockId + "::" + scope;
            if (embByKey.containsKey(key)) {
                throw new RuntimeException("Duplicate embedded key: " + key);
            }
            embByKey.put(key, e);
        }

        // Verify exact set of lockIds matches between depAuth and embedded
        Set<String> depAuthLockIds = new HashSet<>();
        for (Map<String, Object> dep : depAuthDeps) depAuthLockIds.add((String) dep.get("lockId"));
        Set<String> embLockIds = new HashSet<>();
        for (Map<String, Object> e : embEntries) embLockIds.add((String) e.get("lockId"));

        if (!depAuthLockIds.equals(embLockIds)) {
            Set<String> onlyInDepAuth = new HashSet<>(depAuthLockIds);
            onlyInDepAuth.removeAll(embLockIds);
            Set<String> onlyInEmb = new HashSet<>(embLockIds);
            onlyInEmb.removeAll(depAuthLockIds);
            throw new RuntimeException("LockId set mismatch: only in depAuth=" + onlyInDepAuth + ", only in embedded=" + onlyInEmb);
        }

        List<DependencyInfo> result = new ArrayList<>();
        for (String lockId : runtimeDepIds) {
            String key = lockId + "::runtime"; // all are runtime scope

            Map<String, Object> depAuthInfo = depAuthByKey.get(key);
            if (depAuthInfo == null) {
                throw new RuntimeException("depAuth missing for lockId=" + lockId);
            }

            Map<String, Object> embInfo = embByKey.get(key);
            if (embInfo == null) {
                throw new RuntimeException("embedded entry missing for lockId=" + lockId);
            }

            Map<String, Object> coord = (Map<String, Object>) depAuthInfo.get("coordinate");
            String groupId = (String) coord.get("groupId");
            String artifactId = (String) coord.get("artifactId");
            String version = (String) coord.get("version");
            String classifier = (String) coord.get("classifier");
            String scope = (String) depAuthInfo.get("scope");
            String expectedFingerprint = (String) depAuthInfo.get("rawFingerprint");
            String entryPath = (String) embInfo.get("entryPath");

            if (!scope.equals(embInfo.get("scope"))) {
                throw new RuntimeException("Scope mismatch for lockId=" + lockId + ": depAuth=" + scope + ", embedded=" + embInfo.get("scope"));
            }

            result.add(new DependencyInfo(lockId, groupId, artifactId, version, classifier, scope, entryPath, expectedFingerprint));
        }

        return result;
    }

    /**
     * Resolve dependency JAR path using Maven repository layout.
     * groupId/artifactId/version/artifactId-version[-classifier].jar
     */
    private Path resolveMavenJar(Path mavenRepo, DependencyInfo di) {
        String groupPath = di.groupId.replace('.', '/');
        String jarName = di.artifactId + "-" + di.version;
        if (di.classifier != null && !di.classifier.isEmpty()) {
            jarName += "-" + di.classifier;
        }
        jarName += ".jar";
        return mavenRepo.resolve(groupPath).resolve(di.artifactId).resolve(di.version).resolve(jarName);
    }

    /**
     * Collect class entries. StageAcceptanceAuthorityProvider is outer-only (no inner classes).
     * Other outers get their inner classes via "$*.class" glob in the outer class's parent directory.
     */
    private List<EntryData> collectClassEntries() throws Exception {
        Set<String> classPaths = new LinkedHashSet<>();

        for (String outer : REQUIRED_OUTER_CLASSES) {
            Path outerFile = classesDir.resolve(outer + ".class");
            if (!Files.exists(outerFile)) {
                throw new RuntimeException("Required outer class not found: " + outer);
            }
            classPaths.add(outer);

            // Find inner classes in the same package directory
            int lastSlash = outer.lastIndexOf('/');
            String pkgDir = outer.substring(0, lastSlash);
            String basename = outer.substring(lastSlash + 1);
            Path pkgPath = classesDir.resolve(pkgDir);

            collectInnerClasses(pkgPath, basename, classPaths);
        }

        List<EntryData> classEntries = new ArrayList<>();
        List<String> sortedPaths = new ArrayList<>(classPaths);
        Collections.sort(sortedPaths);

        for (String classPath : sortedPaths) {
            Path classFile = classesDir.resolve(classPath + ".class");
            if (Files.exists(classFile)) {
                byte[] data = Files.readAllBytes(classFile);
                classEntries.add(new EntryData(classPath + ".class", data, EntryKind.CLASS));
            }
        }

        return classEntries;
    }

    private void collectInnerClasses(Path dir, String basename, Set<String> result) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, basename + "$*.class")) {
            for (Path p : stream) {
                // dir == classesDir.resolve(pkgDir); resolve back through classesDir so
                // pkgDir prefix is preserved -- otherwise classesDir.resolve() finds nothing
                String name = classesDir.relativize(p).toString().replace('\\', '/');
                result.add(name.replace(".class", ""));
            }
        } catch (Exception e) { /* ignore - no inner classes */ }
    }

    private List<EntryData> collectSchemaEntries(List<String> visibleSchemaIds) throws Exception {
        List<EntryData> schemaEntries = new ArrayList<>();
        for (String schemaId : visibleSchemaIds) {
            Path schemaFile = schemaDir.resolve(schemaId);
            if (!Files.exists(schemaFile)) {
                throw new RuntimeException("Schema not found: " + schemaFile);
            }
            byte[] data = Files.readAllBytes(schemaFile);
            schemaEntries.add(new EntryData("schemas/" + schemaId, data, EntryKind.SCHEMA));
        }
        schemaEntries.sort((a, b) -> a.name.compareTo(b.name));
        return schemaEntries;
    }

    private List<EntryData> collectDependencyEntries(List<DependencyInfo> depInfoList) throws Exception {
        List<EntryData> depEntries = new ArrayList<>();
        for (DependencyInfo di : depInfoList) {
            Path jarPath = resolveMavenJar(mavenRepo, di);
            if (!Files.exists(jarPath)) {
                throw new RuntimeException("Dependency JAR not found: " + jarPath +
                        " (coord: " + di.groupId + ":" + di.artifactId + ":" + di.version + ")");
            }
            byte[] jarBytes = Files.readAllBytes(jarPath);
            depEntries.add(new EntryData(di.entryPath, jarBytes, EntryKind.DEPENDENCY));
        }
        depEntries.sort((a, b) -> a.name.compareTo(b.name));
        return depEntries;
    }

    /**
     * Load actual JAR bytes into DependencyInfo, verify fingerprint against authority pin.
     */
    @SuppressWarnings("unchecked")
    List<DependencyInfo> loadDependencyBytes(List<DependencyInfo> depInfoList) throws Exception {
        List<DependencyInfo> result = new ArrayList<>();
        for (DependencyInfo di : depInfoList) {
            Path jarPath = resolveMavenJar(mavenRepo, di);
            if (!Files.exists(jarPath)) {
                throw new RuntimeException("Dependency JAR not found: " + jarPath);
            }
            byte[] jarBytes = Files.readAllBytes(jarPath);
            String actualFingerprint = "sha256:" + sha256Hex(jarBytes);

            if (!actualFingerprint.equals(di.expectedFingerprint)) {
                throw new RuntimeException("Fingerprint mismatch for " + di.groupId + ":" + di.artifactId +
                        ": expected=" + di.expectedFingerprint + ", actual=" + actualFingerprint);
            }

            result.add(new DependencyInfo(di.lockId, di.groupId, di.artifactId, di.version, di.classifier,
                    di.scope, di.entryPath, di.expectedFingerprint, jarBytes));
        }
        return result;
    }

    private void addMetadataEntries(List<EntryData> entries, byte[] authorityBytes) throws Exception {
        Path protocolCompilationFile = protocolCompilerDir.resolve("protocol-compilation-manifest-v1.json");
        Path canonicalizationContractFile = protocolCompilerDir.resolve("canonicalization-contract-v1.json");
        Path fingerprintProfileFile = canonicalizationDir.resolve("fingerprint-profile-v1.json");

        if (!Files.exists(protocolCompilationFile)) {
            throw new RuntimeException("protocol-compilation-manifest not found: " + protocolCompilationFile);
        }
        if (!Files.exists(canonicalizationContractFile)) {
            throw new RuntimeException("canonicalization-contract not found: " + canonicalizationContractFile);
        }
        if (!Files.exists(fingerprintProfileFile)) {
            throw new RuntimeException("fingerprint-profile not found: " + fingerprintProfileFile);
        }

        entries.add(new EntryData("META-INF/gate-a/protocol/protocol-compilation-manifest-v1.json",
                Files.readAllBytes(protocolCompilationFile), EntryKind.METADATA));
        entries.add(new EntryData("META-INF/gate-a/projections/canonicalization-contract-v1.json",
                Files.readAllBytes(canonicalizationContractFile), EntryKind.METADATA));
        entries.add(new EntryData("META-INF/gate-a/canonicalization/fingerprint-profile-v1.json",
                Files.readAllBytes(fingerprintProfileFile), EntryKind.METADATA));
        entries.add(new EntryData("META-INF/gate-a/protocol/gate-a-protocol-authority.json",
                authorityBytes, EntryKind.METADATA));

        String pomProps = "artifactId: bloge-resource-gateway-test-kit\ngroupId: com.leanowtech.bloge\nversion: 1.0.0";
        entries.add(new EntryData("META-INF/maven/com.leanowtech.bloge/bloge-resource-gateway-test-kit/pom.properties",
                pomProps.getBytes(StandardCharsets.UTF_8), EntryKind.METADATA));
    }

    // --- Manifest builders ---

    /**
     * Build class manifest with EXACT fields: binaryName, entryPath, rawFingerprint.
     * Sorted by binaryName.
     */
    private Map<String, Object> buildClassManifest(List<EntryData> classEntries) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION_CLASS);

        List<Map<String, Object>> manifestEntries = new ArrayList<>();
        for (EntryData e : classEntries) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("binaryName", classPathToBinaryName(e.name));
            entry.put("entryPath", e.name);
            entry.put("rawFingerprint", typedRawFingerprint(e.data));
            manifestEntries.add(entry);
        }
        manifest.put("entries", manifestEntries);

        String commitBase = canonicalizeWithoutFingerprint(manifest);
        manifest.put("manifestFingerprint", aggregateCommitment(commitBase, DOMAIN_CLASS_MANIFEST));
        return manifest;
    }

    /**
     * Build resource manifest with EXACT fields: resourceRole, entryPath, rawFingerprint.
     * Excludes the three manifest files themselves and all class/dependency entries.
     * Sorted by entryPath.
     */
    private Map<String, Object> buildResourceManifest(List<EntryData> allEntries,
                                                     List<EntryData> classEntries,
                                                     List<EntryData> depEntries) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION_RESOURCE);

        Set<String> excludedPaths = Set.of(classManifestPath, resourceManifestPath, dependencyManifestPath);
        Set<String> excludedClassDeps = new HashSet<>();
        for (EntryData e : classEntries) excludedClassDeps.add(e.name);
        for (EntryData e : depEntries) excludedClassDeps.add(e.name);

        List<Map<String, Object>> manifestEntries = new ArrayList<>();
        for (EntryData e : allEntries) {
            if (excludedPaths.contains(e.name) || excludedClassDeps.contains(e.name) || e.name.endsWith("/")) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("resourceRole", determineResourceRole(e.name));
            entry.put("entryPath", e.name);
            entry.put("rawFingerprint", typedRawFingerprint(e.data));
            manifestEntries.add(entry);
        }

        manifestEntries.sort((a, b) -> ((String) a.get("entryPath")).compareTo((String) b.get("entryPath")));
        manifest.put("entries", manifestEntries);

        String commitBase = canonicalizeWithoutFingerprint(manifest);
        manifest.put("manifestFingerprint", aggregateCommitment(commitBase, DOMAIN_RESOURCE_MANIFEST));
        return manifest;
    }

    /**
     * Build dependency lock manifest with EXACT fields: coordinate, scope, entryPath, rawFingerprint.
     * NO lockId field. rawFingerprint = actual JAR bytes fingerprint.
     * Sorted by coordinate then entryPath.
     */
    private Map<String, Object> buildDependencyLockManifest(List<DependencyInfo> depInfoList) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION_DEPENDENCY);

        List<Map<String, Object>> manifestEntries = new ArrayList<>();
        for (DependencyInfo di : depInfoList) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("coordinate", di.groupId + ":" + di.artifactId + ":" + di.version);
            entry.put("scope", di.scope);
            entry.put("entryPath", di.entryPath);
            entry.put("rawFingerprint", typedRawFingerprint(di.actualJarBytes));
            manifestEntries.add(entry);
        }

        manifestEntries.sort((a, b) -> {
            String coordA = (String) a.get("coordinate");
            String coordB = (String) b.get("coordinate");
            int cmp = coordA.compareTo(coordB);
            if (cmp != 0) return cmp;
            return ((String) a.get("entryPath")).compareTo((String) b.get("entryPath"));
        });

        manifest.put("entries", manifestEntries);
        String commitBase = canonicalizeWithoutFingerprint(manifest);
        manifest.put("manifestFingerprint", aggregateCommitment(commitBase, DOMAIN_DEPENDENCY_MANIFEST));
        return manifest;
    }

    private String determineResourceRole(String entryPath) {
        if (entryPath.startsWith("schemas/")) return "SCHEMA";
        if (entryPath.contains("MANIFEST.MF") ||
            entryPath.contains("pom.properties") ||
            entryPath.startsWith("META-INF/gate-a/manifests/") ||
            entryPath.startsWith("META-INF/gate-a/protocol/") ||
            entryPath.startsWith("META-INF/gate-a/projections/") ||
            entryPath.startsWith("META-INF/gate-a/canonicalization/")) {
            return "MANIFEST";
        }
        return "SCHEMA";
    }

    /**
     * Self-check against authority limits and exact sets.
     * Includes MANIFEST.MF in entry count (it's written to JAR but not in entries list).
     */
    private void selfCheck(List<EntryData> entries,
                          Map<String, Object> limits,
                          List<String> requiredJarEntries,
                          List<String> visibleSchemaIds,
                          List<String> runtimeDepIds,
                          List<DependencyInfo> depInfoList) throws Exception {
        long maxRawBytes = (Long) limits.get("maxRawBytes");
        int maxZipEntries = (Integer) limits.get("maxZipEntries");
        long maxSingleEntryBytes = (Long) limits.get("maxSingleEntryBytes");
        int maxTotalUncompressedBytes = (Integer) limits.get("maxTotalUncompressedBytes");

        System.out.println("Running self-check...");

        // Write JAR temporarily to check actual raw size
        Path tempJar = outputJar.getParent().resolve("temp_check_" + outputJar.getFileName());
        writeJar(entries, tempJar);
        long actualRawSize = Files.size(tempJar);
        Files.deleteIfExists(tempJar);

        if (actualRawSize > maxRawBytes) {
            throw new RuntimeException("JAR raw size exceeds limit: " + actualRawSize + " > " + maxRawBytes);
        }
        System.out.println("  Raw size: " + actualRawSize + " / " + maxRawBytes + " bytes");

        // Entry count includes MANIFEST.MF (written separately in writeJar)
        int totalEntries = entries.size() + 1; // +1 for META-INF/MANIFEST.MF
        if (totalEntries > maxZipEntries) {
            throw new RuntimeException("Entry count exceeds limit: " + totalEntries + " > " + maxZipEntries);
        }
        System.out.println("  Entry count (incl MANIFEST.MF): " + totalEntries + " / " + maxZipEntries);

        long totalUncompressed = 0;
        for (EntryData e : entries) {
            if (e.data.length > maxSingleEntryBytes) {
                throw new RuntimeException("Entry exceeds maxSingleEntryBytes: " + e.name + " (" + e.data.length + ")");
            }
            totalUncompressed += e.data.length;
        }

        if (totalUncompressed > maxTotalUncompressedBytes) {
            throw new RuntimeException("Total uncompressed exceeds limit: " + totalUncompressed + " > " + maxTotalUncompressedBytes);
        }
        System.out.println("  Uncompressed total: " + totalUncompressed + " / " + maxTotalUncompressedBytes + " bytes");

        // Verify exact requiredJarEntries
        Set<String> presentPaths = new HashSet<>();
        for (EntryData e : entries) presentPaths.add(e.name);
        // Also verify META-INF/MANIFEST.MF is present (it's always written)
        presentPaths.add("META-INF/MANIFEST.MF");

        for (String required : requiredJarEntries) {
            if (!presentPaths.contains(required)) {
                throw new RuntimeException("Missing required entry: " + required);
            }
        }
        System.out.println("  Required entries: " + requiredJarEntries.size() + " verified");

        // Verify exact visibleSchemas count matches schema entries
        if (visibleSchemaIds.size() != entries.stream().filter(e -> e.kind == EntryKind.SCHEMA).count()) {
            throw new RuntimeException("Schema count mismatch: authority=" + visibleSchemaIds.size() +
                    ", found=" + entries.stream().filter(e -> e.kind == EntryKind.SCHEMA).count());
        }
        System.out.println("  Schemas: " + visibleSchemaIds.size() + " verified");

        // Authority-driven exact set comparison: runtimeDepIds (authority) vs depInfoList lockIds (built)
        Set<String> authorityDepIds = new HashSet<>(runtimeDepIds);
        Set<String> builtDepIds = new HashSet<>();
        for (DependencyInfo di : depInfoList) {
            if (di.lockId == null) {
                throw new RuntimeException("DependencyInfo missing lockId");
            }
            if (!builtDepIds.add(di.lockId)) {
                throw new RuntimeException("Duplicate lockId in depInfoList: " + di.lockId);
            }
        }
        if (!authorityDepIds.equals(builtDepIds)) {
            Set<String> onlyInAuthority = new HashSet<>(authorityDepIds);
            onlyInAuthority.removeAll(builtDepIds);
            Set<String> onlyInBuilt = new HashSet<>(builtDepIds);
            onlyInBuilt.removeAll(authorityDepIds);
            throw new RuntimeException("Dependency lockId set mismatch — only in authority: " + onlyInAuthority
                    + ", only in built: " + onlyInBuilt);
        }
        // Scope drift: each depInfoList entry must have scope matching authority entry
        for (DependencyInfo di : depInfoList) {
            if (!"runtime".equals(di.scope)) {
                throw new RuntimeException("Unexpected non-runtime scope in depInfoList: lockId=" + di.lockId
                        + ", scope=" + di.scope);
            }
        }
        System.out.println("  Dependencies: " + depInfoList.size() + " verified (authority-driven exact match)");

        System.out.println("Self-check passed.");
    }

    private void writeJar(List<EntryData> entries, Path outputJar) throws Exception {
        List<String> sortedNames = new ArrayList<>();
        for (EntryData e : entries) sortedNames.add(e.name);
        Collections.sort(sortedNames);
        Map<String, EntryData> entryMap = new HashMap<>();
        for (EntryData e : entries) entryMap.put(e.name, e);

        try (JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(outputJar)))) {
            Manifest manifest = new Manifest();
            Attributes mainAttrs = manifest.getMainAttributes();
            mainAttrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            mainAttrs.put(new Attributes.Name("Created-By"), "Gate A Candidate Packager");
            mainAttrs.put(new Attributes.Name("Main-Class"), "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

            ZipEntry manifestEntry = new ZipEntry(JarFile.MANIFEST_NAME);
            manifestEntry.setTime(FIXED_TIME_MS);
            jos.putNextEntry(manifestEntry);
            manifest.write(jos);
            jos.closeEntry();

            List<String> metaInfFirst = new ArrayList<>();
            List<String> others = new ArrayList<>();
            for (String name : sortedNames) {
                if (name.startsWith("META-INF/") && !JarFile.MANIFEST_NAME.equals(name)) {
                    metaInfFirst.add(name);
                } else if (!JarFile.MANIFEST_NAME.equals(name)) {
                    others.add(name);
                }
            }

            List<String> allSorted = new ArrayList<>(metaInfFirst);
            allSorted.addAll(others);

            for (String name : allSorted) {
                EntryData e = entryMap.get(name);
                ZipEntry ze = new ZipEntry(name);
                ze.setTime(FIXED_TIME_MS);
                jos.putNextEntry(ze);
                jos.write(e.data);
                jos.closeEntry();
            }
        }
    }

    private static byte[] canonicalJson(Map<String, Object> obj) {
        return canonicalize(obj).getBytes(StandardCharsets.UTF_8);
    }

    private String canonicalizeWithoutFingerprint(Map<String, Object> manifest) {
        Map<String, Object> commitData = new LinkedHashMap<>();
        commitData.put("schemaVersion", manifest.get("schemaVersion"));
        commitData.put("entries", manifest.get("entries"));
        return canonicalize(commitData);
    }

    private static String canonicalize(Object value) {
        StringBuilder sb = new StringBuilder();
        canonicalize(value, sb);
        return sb.toString();
    }

    private static void canonicalize(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean) {
            sb.append((Boolean) value);
        } else if (value instanceof Number) {
            sb.append(value);
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
            Map<?, ?> m = (Map<?, ?>) value;
            List<String> keys = new ArrayList<>();
            for (Object key : m.keySet()) keys.add((String) key);
            Collections.sort(keys);
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                appendString(sb, keys.get(i));
                sb.append(':');
                canonicalize(m.get(keys.get(i)), sb);
            }
            sb.append('}');
        }
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static String hexEncode(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /** SHA-256(raw) then hex-encode. */
    private static String sha256Hex(byte[] raw) {
        try {
            return hexEncode(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> typedRawFingerprint(byte[] data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "RAW_BYTES");
        result.put("algorithm", "SHA-256");
        result.put("value", "sha256:" + sha256Hex(data));
        return result;
    }

    /**
     * Aggregate commitment: SHA-256(domain || 0x00 || canonicalJson).
     * Result: sha256:<hex-encoded-digest>
     */
    private Map<String, Object> aggregateCommitment(String commitBase, String domain) {
        try {
            byte[] domainBytes = domain.getBytes(StandardCharsets.US_ASCII);
            byte[] jsonBytes = commitBase.getBytes(StandardCharsets.UTF_8);
            byte[] combined = new byte[domainBytes.length + 1 + jsonBytes.length];
            System.arraycopy(domainBytes, 0, combined, 0, domainBytes.length);
            combined[domainBytes.length] = 0;
            System.arraycopy(jsonBytes, 0, combined, domainBytes.length + 1, jsonBytes.length);

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(combined);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("kind", "AGGREGATE_COMMITMENT");
            result.put("algorithm", "SHA-256");
            result.put("value", "sha256:" + hexEncode(digest));
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String classPathToBinaryName(String classPath) {
        if (classPath.endsWith(".class")) {
            classPath = classPath.substring(0, classPath.length() - 6);
        }
        return classPath.replace('/', '.');
    }

    enum EntryKind { CLASS, DEPENDENCY, SCHEMA, METADATA, MANIFEST }

    static class EntryData {
        final String name;
        final byte[] data;
        final EntryKind kind;
        EntryData(String name, byte[] data, EntryKind kind) {
            this.name = name;
            this.data = data;
            this.kind = kind;
        }
    }

    static class DependencyInfo {
        final String lockId;   // authority lockId, used for exact-set verification in selfCheck
        final String groupId;
        final String artifactId;
        final String version;
        final String classifier;
        final String scope;
        final String entryPath;
        final String expectedFingerprint;
        final byte[] actualJarBytes;

        DependencyInfo(String lockId, String groupId, String artifactId, String version,
                       String classifier, String scope, String entryPath, String expectedFingerprint) {
            this(lockId, groupId, artifactId, version, classifier, scope, entryPath, expectedFingerprint, null);
        }

        DependencyInfo(String lockId, String groupId, String artifactId, String version,
                       String classifier, String scope, String entryPath, String expectedFingerprint,
                       byte[] actualJarBytes) {
            this.lockId = lockId;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.classifier = classifier;
            this.scope = scope;
            this.entryPath = entryPath;
            this.expectedFingerprint = expectedFingerprint;
            this.actualJarBytes = actualJarBytes;
        }
    }
}
