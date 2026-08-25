package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * Produces exact Authority-locked fixtures for A1.3-02 integration tests.
 *
 * <p>All facts are derived from the Authority JSON (gate-a-protocol-authority-v1.json)
 * which contains both {@code roleContracts} and the root-level {@code dependencyAuthority}
 * in a single document.
 *
 * <p>Strict derivation for {@code INDEPENDENT_VERIFIER} role:
 * <ul>
 *   <li>28 required JAR entry paths from {@code roleContracts[INDEPENDENT_VERIFIER].requiredJarEntries}</li>
 *   <li>5 artifact limits from {@code roleContracts[INDEPENDENT_VERIFIER].artifactLimits}</li>
 *   <li>7 runtime dependency lock IDs from {@code roleContracts[INDEPENDENT_VERIFIER].runtimeDependencyLockIds}</li>
 *   <li>7 embedded dependency entries from {@code roleContracts[INDEPENDENT_VERIFIER].packagingContract.embeddedDependencyEntries}</li>
 *   <li>7 SHA-256 fingerprints from joining embedded entries with {@code dependencyAuthority.dependencies}</li>
 * </ul>
 *
 * <p>This class is immutable and thread-safe once constructed.
 */
public final class RealVerifierFixtureFactory {

    private static final String ROLE = "INDEPENDENT_VERIFIER";
    private static final int EXPECTED_ENTRY_COUNT = 28;
    private static final int EXPECTED_DEP_COUNT = 7;

    private ObjectMapper planMapper;
    private final JsonNode authorityRoot;
    private final JsonNode depAuthorityRoot;
    private final Path depDir;

    // Derived from Authority
    private final List<String> requiredEntries;
    private final ArtifactLimitValues artifactLimits;
    private final List<String> dependencyLockIds;
    private final List<DependencyEntry> embeddedDependencies;
    private final String mainClass;
    private final byte[] cliBytes;

    /**
     * Immutable dependency entry with lockId, entryPath, sha256, and derived artifactFileName.
     * artifactFileName = artifactId + "-" + version + ".jar" derived from dependency coordinate.
     */
    public record DependencyEntry(
            String lockId,
            String entryPath,
            String sha256,
            String artifactFileName
    ) {}

    /** Immutable artifact limits values. */
    public record ArtifactLimitValues(
            long maxRawBytes,
            long maxZipEntries,
            long maxSingleEntryBytes,
            long maxTotalUncompressedBytes,
            long maxCompressionRatio
    ) {}

    /**
     * Constructs the factory by deriving all facts from Authority JSON.
     *
     * @param authorityJsonPath path to gate-a-protocol-authority-v1.json
     * @param copiedDepDir     directory containing Maven-copied dependency JARs
     * @throws IOException if JSON cannot be read
     * @throws IllegalArgumentException if Authority structure is invalid
     */
    public RealVerifierFixtureFactory(Path authorityJsonPath, Path copiedDepDir) throws IOException {
        Objects.requireNonNull(authorityJsonPath, "authorityJsonPath must not be null");
        Objects.requireNonNull(copiedDepDir, "copiedDepDir must not be null");
        this.depDir = copiedDepDir;

        JsonFactory factory = JsonFactory.builder()
                .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        ObjectMapper strictMapper = new ObjectMapper(factory)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);

        this.authorityRoot = strictMapper.readTree(authorityJsonPath.toFile());

        // dependencyAuthority is at the root level of the same Authority JSON
        JsonNode da = this.authorityRoot.path("dependencyAuthority");
        if (da.isMissingNode() || da.isNull()) {
            throw new IllegalArgumentException(
                    "dependencyAuthority not found at root of Authority JSON");
        }
        this.depAuthorityRoot = da;

        this.requiredEntries = deriveRequiredEntries();
        this.artifactLimits = deriveArtifactLimits();
        this.dependencyLockIds = deriveDependencyLockIds();
        this.embeddedDependencies = deriveEmbeddedDependencies();
        this.mainClass = deriveMainClass();
        this.cliBytes = deriveCliBytes();

        validateCounts();
        validateEmbeddedLocksInDependencyAuthority();
        validateEntryPathsInRequiredEntries();
    }

    // -------------------------------------------------------------------------
    // Authority derivation
    // -------------------------------------------------------------------------

    private String deriveMainClass() {
        String mc = findIndependentVerifierRole().path("mainClass").asText(null);
        if (mc == null || mc.isBlank()) {
            throw new IllegalArgumentException(
                    "roleContracts[INDEPENDENT_VERIFIER].mainClass is missing");
        }
        return mc;
    }

    private List<String> deriveRequiredEntries() {
        JsonNode entriesNode = findIndependentVerifierRole().path("requiredJarEntries");
        if (!entriesNode.isArray()) {
            throw new IllegalArgumentException(
                    "roleContracts[INDEPENDENT_VERIFIER].requiredJarEntries must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode n : entriesNode) {
            String s = n.asText(null);
            if (s == null || s.isBlank()) {
                throw new IllegalArgumentException(
                        "roleContracts[INDEPENDENT_VERIFIER].requiredJarEntries contains null/blank entry");
            }
            result.add(s);
        }
        if (result.size() != EXPECTED_ENTRY_COUNT) {
            throw new IllegalArgumentException(
                    "roleContracts[INDEPENDENT_VERIFIER].requiredJarEntries must have exactly "
                    + EXPECTED_ENTRY_COUNT + " entries, got " + result.size());
        }
        Set<String> uniqueCheck = new HashSet<>(result);
        if (uniqueCheck.size() != result.size()) {
            throw new IllegalArgumentException(
                    "roleContracts[INDEPENDENT_VERIFIER].requiredJarEntries contains duplicates");
        }
        return Collections.unmodifiableList(result);
    }

    private ArtifactLimitValues deriveArtifactLimits() {
        JsonNode limitsNode = findIndependentVerifierRole().path("artifactLimits");
        if (!limitsNode.isObject()) {
            throw new IllegalArgumentException(
                    "roleContracts[INDEPENDENT_VERIFIER].artifactLimits must be an object");
        }
        Set<String> keys = new HashSet<>();
        for (Iterator<String> it = limitsNode.fieldNames(); it.hasNext(); ) {
            keys.add(it.next());
        }
        if (keys.size() != 5) {
            throw new IllegalArgumentException(
                    "roleContracts[INDEPENDENT_VERIFIER].artifactLimits must have exactly 5 keys, got " + keys.size());
        }
        Set<String> requiredKeys = Set.of(
                "maxRawBytes", "maxZipEntries", "maxSingleEntryBytes",
                "maxTotalUncompressedBytes", "maxCompressionRatio"
        );
        if (!keys.containsAll(requiredKeys)) {
            Set<String> missing = new HashSet<>(requiredKeys);
            missing.removeAll(keys);
            throw new IllegalArgumentException(
                    "roleContracts[INDEPENDENT_VERIFIER].artifactLimits missing keys: " + missing);
        }
        // Verify all values are non-negative integers
        Map<String, Long> values = new LinkedHashMap<>();
        for (String key : requiredKeys) {
            JsonNode vn = limitsNode.path(key);
            if (!vn.isIntegralNumber()) {
                throw new IllegalArgumentException(
                        "roleContracts[INDEPENDENT_VERIFIER].artifactLimits." + key + " must be an integer");
            }
            long val = vn.asLong();
            if (val < 0) {
                throw new IllegalArgumentException(
                        "roleContracts[INDEPENDENT_VERIFIER].artifactLimits." + key + " must be non-negative, got " + val);
            }
            values.put(key, val);
        }
        return new ArtifactLimitValues(
                values.get("maxRawBytes"),
                values.get("maxZipEntries"),
                values.get("maxSingleEntryBytes"),
                values.get("maxTotalUncompressedBytes"),
                values.get("maxCompressionRatio")
        );
    }

    private List<String> deriveDependencyLockIds() {
        JsonNode idsNode = findIndependentVerifierRole().path("runtimeDependencyLockIds");
        if (!idsNode.isArray()) {
            throw new IllegalArgumentException(
                    "roleContracts[INDEPENDENT_VERIFIER].runtimeDependencyLockIds must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode n : idsNode) {
            String s = n.asText(null);
            if (s == null || s.isBlank()) {
                throw new IllegalArgumentException(
                        "roleContracts[INDEPENDENT_VERIFIER].runtimeDependencyLockIds contains null/blank lockId");
            }
            result.add(s);
        }
        if (result.size() != EXPECTED_DEP_COUNT) {
            throw new IllegalArgumentException(
                    "roleContracts[INDEPENDENT_VERIFIER].runtimeDependencyLockIds must have exactly "
                    + EXPECTED_DEP_COUNT + " entries, got " + result.size());
        }
        Set<String> uniqueCheck = new HashSet<>(result);
        if (uniqueCheck.size() != result.size()) {
            throw new IllegalArgumentException(
                    "roleContracts[INDEPENDENT_VERIFIER].runtimeDependencyLockIds contains duplicates");
        }
        return Collections.unmodifiableList(result);
    }

    private List<DependencyEntry> deriveEmbeddedDependencies() {
        JsonNode packagingNode = findIndependentVerifierRole().path("packagingContract");
        if (!packagingNode.isObject()) {
            throw new IllegalArgumentException(
                    "roleContracts[INDEPENDENT_VERIFIER].packagingContract must be an object");
        }
        JsonNode embNode = packagingNode.path("embeddedDependencyEntries");
        if (!embNode.isArray()) {
            throw new IllegalArgumentException(
                    "roleContracts[INDEPENDENT_VERIFIER].packagingContract.embeddedDependencyEntries must be an array");
        }
        List<DependencyEntry> result = new ArrayList<>();
        Set<String> seenLockIds = new HashSet<>();
        for (JsonNode n : embNode) {
            String lockId = n.path("lockId").asText(null);
            if (lockId == null || lockId.isBlank()) {
                throw new IllegalArgumentException(
                        "embeddedDependencyEntries entry missing lockId");
            }
            if (!seenLockIds.add(lockId)) {
                throw new IllegalArgumentException(
                        "embeddedDependencyEntries contains duplicate lockId: " + lockId);
            }
            String entryPath = n.path("entryPath").asText(null);
            if (entryPath == null || entryPath.isBlank()) {
                throw new IllegalArgumentException(
                        "embeddedDependencyEntries entry missing entryPath for lockId " + lockId);
            }
            // Join with dependencyAuthority.dependencies by lockId
            String sha256 = resolveFingerprintFromAuthority(lockId);
            String artifactFileName = resolveArtifactFileNameFromAuthority(lockId);
            result.add(new DependencyEntry(lockId, entryPath, sha256, artifactFileName));
        }
        if (result.size() != EXPECTED_DEP_COUNT) {
            throw new IllegalArgumentException(
                    "roleContracts[INDEPENDENT_VERIFIER].packagingContract.embeddedDependencyEntries "
                    + "must have exactly " + EXPECTED_DEP_COUNT + " entries, got " + result.size());
        }
        return Collections.unmodifiableList(result);
    }

    /** Resolve SHA-256 fingerprint from dependencyAuthority.dependencies by lockId. */
    private String resolveFingerprintFromAuthority(String lockId) {
        JsonNode deps = depAuthorityRoot.path("dependencies");
        if (!deps.isArray()) {
            throw new IllegalArgumentException("dependencyAuthority.dependencies must be an array");
        }
        for (JsonNode dep : deps) {
            if (lockId.equals(dep.path("lockId").asText(null))) {
                String fp = dep.path("rawFingerprint").asText(null);
                if (fp == null || fp.isBlank()) {
                    throw new IllegalArgumentException(
                            "dependencyAuthority.dependencies[" + lockId + "] missing rawFingerprint");
                }
                return fp;
            }
        }
        throw new IllegalArgumentException(
                "lockId " + lockId + " not found in dependencyAuthority.dependencies");
    }

    /** Resolve artifactFileName = artifactId + "-" + version + ".jar" from dependencyAuthority.dependencies by lockId. */
    private String resolveArtifactFileNameFromAuthority(String lockId) {
        JsonNode deps = depAuthorityRoot.path("dependencies");
        if (!deps.isArray()) {
            throw new IllegalArgumentException("dependencyAuthority.dependencies must be an array");
        }
        for (JsonNode dep : deps) {
            if (lockId.equals(dep.path("lockId").asText(null))) {
                JsonNode coord = dep.path("coordinate");
                String artifactId = coord.path("artifactId").asText(null);
                String version = coord.path("version").asText(null);
                if (artifactId == null || artifactId.isBlank()) {
                    throw new IllegalArgumentException(
                            "dependencyAuthority.dependencies[" + lockId + "] coordinate missing artifactId");
                }
                if (version == null || version.isBlank()) {
                    throw new IllegalArgumentException(
                            "dependencyAuthority.dependencies[" + lockId + "] coordinate missing version");
                }
                return artifactId + "-" + version + ".jar";
            }
        }
        throw new IllegalArgumentException(
                "lockId " + lockId + " not found in dependencyAuthority.dependencies");
    }

    /**
     * Derives CLI class bytes from classpath using this class's classloader
     * (which has access to test-classes) and validates CAFEBABE magic.
     * Fails with IllegalStateException if the resource is missing or lacks CAFEBABE magic.
     */
    private byte[] deriveCliBytes() {
        ClassLoader loader = getClass().getClassLoader();
        String resourcePath = mainClass.replace('.', '/') + ".class";
        InputStream is = loader.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IllegalStateException(
                    "CLI class resource not found on classpath: " + resourcePath
                    + " (mainClass=" + mainClass + ")");
        }
        byte[] bytes;
        try {
            bytes = is.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read CLI class resource: " + resourcePath, e);
        } finally {
            try { is.close(); } catch (IOException e) { /* ignore */ }
        }
        if (bytes.length < 4) {
            throw new IllegalStateException(
                    "CLI class resource too short for magic header: " + resourcePath);
        }
        if ((bytes[0] & 0xFF) != 0xCA || (bytes[1] & 0xFF) != 0xFE
            || (bytes[2] & 0xFF) != 0xBA || (bytes[3] & 0xFF) != 0xBE) {
            throw new IllegalStateException(
                    "CLI class resource does not have CAFEBABE magic: " + resourcePath);
        }
        return bytes;
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    private void validateCounts() {
        if (requiredEntries.size() != EXPECTED_ENTRY_COUNT) {
            throw new IllegalStateException("requiredEntries count != " + EXPECTED_ENTRY_COUNT);
        }
        if (dependencyLockIds.size() != EXPECTED_DEP_COUNT) {
            throw new IllegalStateException("dependencyLockIds count != " + EXPECTED_DEP_COUNT);
        }
        if (embeddedDependencies.size() != EXPECTED_DEP_COUNT) {
            throw new IllegalStateException("embeddedDependencies count != " + EXPECTED_DEP_COUNT);
        }
    }

    /**
     * Verify the set of lockIds from runtimeDependencyLockIds equals the set from
     * embeddedDependencyEntries.lockId.
     */
    private void validateEmbeddedLocksInDependencyAuthority() {
        Set<String> roleLocks = new HashSet<>(dependencyLockIds);
        Set<String> embeddedLocks = new HashSet<>();
        for (DependencyEntry de : embeddedDependencies) {
            embeddedLocks.add(de.lockId());
        }
        if (!roleLocks.equals(embeddedLocks)) {
            Set<String> onlyInRole = new HashSet<>(roleLocks);
            onlyInRole.removeAll(embeddedLocks);
            Set<String> onlyInEmbedded = new HashSet<>(embeddedLocks);
            onlyInEmbedded.removeAll(roleLocks);
            throw new IllegalArgumentException(
                    "runtimeDependencyLockIds and embeddedDependencyEntries.lockId sets differ. "
                    + "Only in role: " + onlyInRole + ", only in embedded: " + onlyInEmbedded);
        }
    }

    /**
     * Verify each entryPath from embeddedDependencyEntries is present in requiredEntries.
     */
    private void validateEntryPathsInRequiredEntries() {
        Set<String> reqSet = new HashSet<>(requiredEntries);
        for (DependencyEntry de : embeddedDependencies) {
            if (!reqSet.contains(de.entryPath())) {
                throw new IllegalArgumentException(
                        "embeddedDependencyEntries entryPath \"" + de.entryPath()
                        + "\" not found in requiredJarEntries");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public List<String> requiredEntries() { return requiredEntries; }
    public ArtifactLimitValues artifactLimits() { return artifactLimits; }
    public List<String> dependencyLockIds() { return dependencyLockIds; }
    public List<DependencyEntry> embeddedDependencies() { return embeddedDependencies; }
    public String mainClass() { return mainClass; }
    public byte[] cliBytes() { return cliBytes; }

    public Path depDir() { return depDir; }

    // -------------------------------------------------------------------------
    // Baseline JAR builder
    // -------------------------------------------------------------------------

    /**
     * Builds the baseline JAR with exactly 28 entries in Authority-defined order.
     * All ZipEntry uses fixed DOS time (1980-01-01 00:00:00), no extra fields,
     * default DEFLATE compression.
     *
     * @param outputDir directory for temp files
     * @return JAR bytes
     */
    public byte[] buildBaselineJar(Path outputDir) throws IOException {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Files.createDirectories(outputDir);
        Path jarPath = outputDir.resolve("baseline-" + System.nanoTime() + ".jar");

        // Use authority-defined entry order for baseline
        List<String> entryOrder = new ArrayList<>(requiredEntries);

        try (OutputStream fos = Files.newOutputStream(jarPath);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            for (String entryName : entryOrder) {
                ZipEntry ze = new ZipEntry(entryName);
                // Fixed DOS time: 1980-01-01 00:00:00 local
                ze.setTime(DOS_EPOCH_MILLIS);
                ze.setMethod(ZipOutputStream.DEFLATED);
                zos.putNextEntry(ze);

                if (entryName.endsWith(".class")) {
                    // Derive minimal class bytes from path
                    byte[] content = generateMinimalClassContent(entryName);
                    zos.write(content);
                } else if (entryName.endsWith(".jar")) {
                    // Nested JAR: load from depDir using derived artifactFileName
                    DependencyEntry dep = findDependencyByEntryPath(entryName);
                    if (dep != null) {
                        Path jarFile = depDir.resolve(dep.artifactFileName());
                        if (Files.exists(jarFile)) {
                            zos.write(Files.readAllBytes(jarFile));
                        } else {
                            // Write zero content if JAR not available
                            zos.write(new byte[0]);
                        }
                    } else {
                        zos.write(new byte[0]);
                    }
                } else {
                    // Generic text content from path
                    byte[] content = generateTextContent(entryName);
                    zos.write(content);
                }
                zos.closeEntry();
            }
        }

        byte[] result = Files.readAllBytes(jarPath);
        Files.deleteIfExists(jarPath);

        // Verify exactly 28 entries
        int actualCount = countZipEntries(result);
        if (actualCount != EXPECTED_ENTRY_COUNT) {
            throw new IllegalStateException(
                    "Baseline JAR has " + actualCount + " entries, expected " + EXPECTED_ENTRY_COUNT);
        }

        // Verify all 7 embedded dependency SHA-256 fingerprints
        verifyAllEmbeddedFingerprints(result);

        return result;
    }

    private static final long DOS_EPOCH_MILLIS;

    static {
        // 1980-01-01 00:00:00 in milliseconds since epoch
        // Java's ZipEntry.setTime takes milliseconds since epoch.
        // DOS time stores year-1980, so we compute the exact epoch ms.
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.set(1980, 0, 1, 0, 0, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        DOS_EPOCH_MILLIS = cal.getTimeInMillis();
    }

    private int countZipEntries(byte[] jarBytes) throws IOException {
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            while (zis.getNextEntry() != null) {
                count++;
                zis.closeEntry();
            }
        }
        return count;
    }

    private void verifyAllEmbeddedFingerprints(byte[] jarBytes) throws IOException {
        Map<String, byte[]> entryContents = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                entryContents.put(e.getName(), zis.readAllBytes());
                zis.closeEntry();
            }
        }
        for (DependencyEntry dep : embeddedDependencies) {
            byte[] content = entryContents.get(dep.entryPath());
            if (content == null) {
                throw new IllegalStateException(
                        "Embedded dependency entry missing from JAR: " + dep.entryPath());
            }
            String actualFp = sha256fp(content);
            if (!actualFp.equals(dep.sha256())) {
                throw new IllegalStateException(
                        "SHA-256 mismatch for " + dep.lockId() + " at " + dep.entryPath()
                        + ": expected " + dep.sha256() + ", got " + actualFp);
            }
        }
    }

    private DependencyEntry findDependencyByEntryPath(String entryPath) {
        for (DependencyEntry de : embeddedDependencies) {
            if (de.entryPath().equals(entryPath)) {
                return de;
            }
        }
        return null;
    }

    /** Derive minimal class-file-like bytes from entry path (for baseline only). */
    private byte[] generateMinimalClassContent(String entryPath) {
        byte[] pathBytes = entryPath.getBytes(StandardCharsets.UTF_8);
        int len = Math.max(64, pathBytes.length * 2);
        byte[] content = new byte[len];
        for (int i = 0; i < len; i++) {
            content[i] = (byte) (pathBytes[i % pathBytes.length] ^ (i * 31));
        }
        return content;
    }

    private byte[] generateTextContent(String entryPath) {
        return ("<!-- " + entryPath + " baseline content -->").getBytes(StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Packaging plan builder (deterministic via ObjectMapper + LinkedHashMap)
    // -------------------------------------------------------------------------

    /** Builds the Authority-locked v1 packaging plan JSON bytes using deterministic ObjectMapper. */
    public byte[] buildPackagingPlan(byte[] baselineJarBytes) {
        // Build exactArchiveEntries as list of strings (authority order)
        List<String> exactArchiveEntries = new ArrayList<>(requiredEntries);

        // Build embeddedDependencies
        List<Map<String, Object>> embDeps = new ArrayList<>();
        for (DependencyEntry de : embeddedDependencies) {
            embDeps.add(Map.of(
                    "lockId", de.lockId(),
                    "entryPath", de.entryPath(),
                    "rawFingerprint", de.sha256()
            ));
        }

        // Build artifactLimits in fixed key order
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxRawBytes", artifactLimits.maxRawBytes());
        limits.put("maxZipEntries", artifactLimits.maxZipEntries());
        limits.put("maxSingleEntryBytes", artifactLimits.maxSingleEntryBytes());
        limits.put("maxTotalUncompressedBytes", artifactLimits.maxTotalUncompressedBytes());
        limits.put("maxCompressionRatio", artifactLimits.maxCompressionRatio());

        // Root plan object in fixed key order
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schemaVersion", "v1");
        plan.put("exactArchiveEntries", exactArchiveEntries);
        plan.put("embeddedDependencies", embDeps);
        plan.put("artifactLimits", limits);

        try {
            return planMapper().writeValueAsBytes(plan);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize packaging plan", e);
        }
    }

    // -------------------------------------------------------------------------
    // D7-T3: DevelopmentPredecessorBinding-aware fixture builder
    // -------------------------------------------------------------------------

    /** Stable fail-closed code for provider path/content validation. */
    public static final String R03_FIXTURE_PROVIDER_MISMATCH = "R03-FIXTURE-PROVIDER-MISMATCH";

    /**
     * Immutable result of building a binding-aware fixture JAR.
     * Arrays are cloned in the compact constructor and exposed via defensive-copy accessors.
     */
    public record FixtureProviderResult(
            byte[] jarBytes,
            String providerEntryPath,
            byte[] providerBytes,
            int totalEntryCount
    ) {
        /** Compact ctor: clone all arrays to ensure immutability. */
        public FixtureProviderResult {
            jarBytes = jarBytes != null ? jarBytes.clone() : new byte[0];
            providerBytes = providerBytes != null ? providerBytes.clone() : new byte[0];
        }

        /** Defensive copy of JAR bytes. */
        @Override public byte[] jarBytes() { return jarBytes.clone(); }

        /** Defensive copy of provider bytes. */
        @Override public byte[] providerBytes() { return providerBytes.clone(); }
    }

    /**
     * Derives the provider entry path from the raw Authority INDEPENDENT_VERIFIER role contract.
     * Derives from authorityRoot, never hardcoded.
     *
     * @return the providerEntryPath from roleContracts[INDEPENDENT_VERIFIER]
     * @throws IllegalStateException if role or providerEntryPath is absent
     */
    public String deriveAuthorityProviderEntryPath() {
        JsonNode role = findIndependentVerifierRole();
        String pep = role.path("providerEntryPath").asText(null);
        if (pep == null || pep.isEmpty()) {
            throw new IllegalStateException(
                    "providerEntryPath not found in INDEPENDENT_VERIFIER role contract");
        }
        return pep;
    }

    /**
     * Builds the Authority-locked fixture JAR with injected provider bytes from binding.
     *
     * <p>Strict validation (all fail closed with R03-FIXTURE-PROVIDER-MISMATCH):
     * <ul>
     *   <li>binding non-null (converted to FixtureProviderException)</li>
     *   <li>outputDir non-null (converted to FixtureProviderException)</li>
     *   <li>binding.providerEntryPath() exactly matches Authority-derived provider entry path</li>
     *   <li>Authority-derived provider entry path belongs to requiredEntries (28-entry fixture)</li>
     *   <li>Authority-derived provider entry path is NOT an embedded runtime dependency</li>
     *   <li>providerBytes length matches providerArtifact.byteLength</li>
     *   <li>SHA-256 of providerBytes matches providerArtifact.rawFingerprint</li>
     *   <li>providerBytes is non-null and non-empty</li>
     *   <li>JAR reopened and provider entry bytes verified to equal binding.providerBytes()</li>
     * </ul>
     *
     * @param binding   verified immutable DevelopmentPredecessorBinding (never null)
     * @param outputDir directory for temporary JAR file
     * @return FixtureProviderResult with JAR bytes, provider metadata, and entry count
     * @throws FixtureProviderException if any validation fails, code = R03-FIXTURE-PROVIDER-MISMATCH
     * @throws IOException if JAR I/O fails
     */
    public FixtureProviderResult buildFixtureJar(DevelopmentPredecessorBinding binding, Path outputDir)
            throws IOException {
        // V0a: binding non-null -> FixtureProviderException
        if (binding == null) {
            throw new FixtureProviderException(R03_FIXTURE_PROVIDER_MISMATCH,
                    Map.of("detail", "binding-is-null"));
        }
        // V0b: outputDir non-null -> FixtureProviderException
        if (outputDir == null) {
            throw new FixtureProviderException(R03_FIXTURE_PROVIDER_MISMATCH,
                    Map.of("detail", "output-dir-is-null"));
        }

        // Derive authority provider entry path from raw Authority (never hardcoded)
        String authorityProviderPath;
        try {
            authorityProviderPath = deriveAuthorityProviderEntryPath();
        } catch (IllegalStateException e) {
            throw new FixtureProviderException(R03_FIXTURE_PROVIDER_MISMATCH,
                    Map.of("detail", "authority-provider-entry-path-derivation-failed"));
        }

        // V1: binding.providerEntryPath must exactly match authority-derived path
        if (!binding.providerEntryPath().equals(authorityProviderPath)) {
            throw new FixtureProviderException(R03_FIXTURE_PROVIDER_MISMATCH,
                    Map.of(
                            "detail", "provider-entry-path-mismatch",
                            "bindingPath", binding.providerEntryPath(),
                            "authorityPath", authorityProviderPath));
        }

        // V2: authority provider path must be in requiredEntries (28-entry fixture)
        if (!requiredEntries.contains(authorityProviderPath)) {
            throw new FixtureProviderException(R03_FIXTURE_PROVIDER_MISMATCH,
                    Map.of(
                            "detail", "provider-entry-not-in-required-entries",
                            "requiredCount", requiredEntries.size()));
        }

        // V3: authority provider path must NOT be an embedded runtime dependency
        boolean isEmbeddedDep = embeddedDependencies.stream()
                .anyMatch(dep -> dep.entryPath().equals(authorityProviderPath));
        if (isEmbeddedDep) {
            throw new FixtureProviderException(R03_FIXTURE_PROVIDER_MISMATCH,
                    Map.of("detail", "provider-entry-is-embedded-dependency"));
        }

        // V4: providerBytes must not be null
        byte[] bindingProviderBytes = binding.providerBytes();
        if (bindingProviderBytes == null) {
            throw new FixtureProviderException(R03_FIXTURE_PROVIDER_MISMATCH,
                    Map.of("detail", "binding-provider-bytes-null"));
        }

        // V5: metadata coherence - providerBytes length must match providerArtifact.byteLength
        if (bindingProviderBytes.length != binding.providerArtifact().byteLength()) {
            throw new FixtureProviderException(R03_FIXTURE_PROVIDER_MISMATCH,
                    Map.of(
                            "detail", "provider-bytes-length-mismatch",
                            "actualLength", (long) bindingProviderBytes.length,
                            "declaredLength", binding.providerArtifact().byteLength()));
        }

        // V6: metadata coherence - SHA-256 of providerBytes must match providerArtifact.rawFingerprint
        String computedFp = sha256fp(bindingProviderBytes);
        if (!computedFp.equals(binding.providerArtifact().rawFingerprint())) {
            throw new FixtureProviderException(R03_FIXTURE_PROVIDER_MISMATCH,
                    Map.of(
                            "detail", "provider-bytes-fingerprint-mismatch",
                            "computedFingerprint", computedFp,
                            "declaredFingerprint", binding.providerArtifact().rawFingerprint()));
        }

        // Build JAR with injected provider bytes
        Files.createDirectories(outputDir);
        Path jarPath = outputDir.resolve("fixture-" + System.nanoTime() + ".jar");
        List<String> entryOrder = new ArrayList<>(requiredEntries);

        try (OutputStream fos = Files.newOutputStream(jarPath);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            for (String entryName : entryOrder) {
                ZipEntry ze = new ZipEntry(entryName);
                ze.setTime(DOS_EPOCH_MILLIS);
                ze.setMethod(ZipOutputStream.DEFLATED);
                zos.putNextEntry(ze);

                if (entryName.equals(authorityProviderPath)) {
                    // Inject provider bytes from binding (defensive copy already in binding.providerBytes())
                    zos.write(bindingProviderBytes);
                } else if (entryName.endsWith(".class")) {
                    zos.write(generateMinimalClassContent(entryName));
                } else if (entryName.endsWith(".jar")) {
                    DependencyEntry dep = findDependencyByEntryPath(entryName);
                    if (dep != null) {
                        Path jarFile = depDir.resolve(dep.artifactFileName());
                        if (Files.exists(jarFile)) {
                            zos.write(Files.readAllBytes(jarFile));
                        } else {
                            zos.write(new byte[0]);
                        }
                    } else {
                        zos.write(new byte[0]);
                    }
                } else {
                    zos.write(generateTextContent(entryName));
                }
                zos.closeEntry();
            }
        }

        byte[] result = Files.readAllBytes(jarPath);
        Files.deleteIfExists(jarPath);

        // Verify exactly 28 entries
        int actualCount = countZipEntries(result);
        if (actualCount != EXPECTED_ENTRY_COUNT) {
            throw new FixtureProviderException(R03_FIXTURE_PROVIDER_MISMATCH,
                    Map.of(
                            "detail", "entry-count-mismatch",
                            "expected", (long) EXPECTED_ENTRY_COUNT,
                            "actual", (long) actualCount));
        }

        // Verify all 7 embedded dependency SHA-256 fingerprints (preserved)
        verifyAllEmbeddedFingerprints(result);

        // V7: Reopen JAR and verify provider entry bytes equal binding.providerBytes()
        byte[] reopenedProviderBytes;
        try {
            reopenedProviderBytes = extractEntryFromJarChecked(result, authorityProviderPath);
        } catch (IOException e) {
            throw new FixtureProviderException(R03_FIXTURE_PROVIDER_MISMATCH,
                    Map.of(
                            "detail", "provider-entry-reopen-unreadable",
                            "providerPath", authorityProviderPath));
        }
        if (reopenedProviderBytes == null) {
            throw new FixtureProviderException(R03_FIXTURE_PROVIDER_MISMATCH,
                    Map.of(
                            "detail", "provider-entry-missing-after-reopen",
                            "providerPath", authorityProviderPath));
        }
        if (!Arrays.equals(reopenedProviderBytes, bindingProviderBytes)) {
            throw new FixtureProviderException(R03_FIXTURE_PROVIDER_MISMATCH,
                    Map.of(
                            "detail", "provider-bytes-mismatch-after-reopen",
                            "providerPath", authorityProviderPath,
                            "reopenedLength", (long) reopenedProviderBytes.length,
                            "bindingLength", (long) bindingProviderBytes.length));
        }

        // Success: return immutable result
        return new FixtureProviderResult(
                result,
                authorityProviderPath,
                bindingProviderBytes,
                actualCount);
    }

    /**
     * Extracts entry bytes from a JAR by entry path. Propagates IOException on I/O failure.
     *
     * @param jarBytes  JAR bytes
     * @param entryPath entry path to extract
     * @return entry bytes or null if not found
     * @throws IOException if JAR reading fails
     */
    private static byte[] extractEntryFromJarChecked(byte[] jarBytes, String entryPath)
            throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryPath)) {
                    byte[] content = zis.readAllBytes();
                    zis.closeEntry();
                    return content;
                }
                zis.closeEntry();
            }
        }
        return null;
    }

    /**
     * Runtime exception for fixture provider validation failures.
     * reasonArgs is never null; message never includes raw provider payload.
     */
    public static final class FixtureProviderException extends RuntimeException {
        private final String reasonCode;
        private final Map<String, Object> reasonArgs;

        public FixtureProviderException(String reasonCode, Map<String, Object> reasonArgs) {
            // Message is code only - never include payload content
            super(reasonCode);
            this.reasonCode = reasonCode;
            // Never null: use empty immutable map
            this.reasonArgs = reasonArgs != null ? Map.copyOf(reasonArgs) : Map.of();
        }

        public String reasonCode() { return reasonCode; }
        public Map<String, Object> reasonArgs() { return reasonArgs; }
    }

    /** Returns a deterministic ObjectMapper (fresh instance each call). */
    private ObjectMapper planMapper() {
        ObjectMapper m = new ObjectMapper();
        m.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false);
        return m;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private JsonNode findIndependentVerifierRole() {
        JsonNode rc = authorityRoot.path("roleContracts");
        if (!rc.isArray()) {
            throw new IllegalArgumentException("roleContracts must be an array");
        }
        for (JsonNode role : rc) {
            if (ROLE.equals(role.path("role").asText(null))) {
                return role;
            }
        }
        throw new IllegalArgumentException(
                "roleContracts does not contain role: " + ROLE);
    }

    public static String sha256fp(byte[] data) {
        return "sha256:" + sha256hex(data);
    }

    public static String sha256hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static boolean isValidSha256Fingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() != 7 + 64) return false;
        if (!fingerprint.startsWith("sha256:")) return false;
        String hex = fingerprint.substring(7);
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }


    // =========================================================================

    // =========================================================================
    // Deterministic JAR builder for PF tests
    // =========================================================================

    /**
     * Builder for creating deterministic JAR/ZIP files with precise control over
     * compression method, entry contents, and size boundaries.
     *
     * <p>All entries use DOS epoch (1980-01-01 00:00:00) for timestamps.
     * STORED entries must have CRC-32 and size preset before writing.
     */
    public final class DeterministicJarBuilder {

        private final List<EntrySpec> entries = new ArrayList<>();
        private int storedEntries = 0;

        /** Specification for a single JAR entry. */
        public record EntrySpec(
                String name,
                byte[] content,
                int compressionMethod,
                boolean isDependency
        ) {}

        /** Adds a dependency entry with actual JAR content from the dependency JARs directory. */
        public DeterministicJarBuilder addDependency(String entryPath) {
            DependencyEntry dep = findDependencyByEntryPath(entryPath);
            if (dep == null) {
                throw new IllegalArgumentException("Unknown dependency entry path: " + entryPath);
            }
            Path depJarPath = depDir.resolve(dep.artifactFileName());
            if (!Files.exists(depJarPath)) {
                throw new IllegalStateException(
                        "Dependency JAR not found: " + depJarPath);
            }
            try {
                byte[] content = Files.readAllBytes(depJarPath);
                entries.add(new EntrySpec(entryPath, content, ZipEntry.DEFLATED, true));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read dependency JAR: " + depJarPath, e);
            }
            return this;
        }

        /** Adds a non-dependency entry with the specified content. */
        public DeterministicJarBuilder addNonDependency(String entryPath, byte[] content) {
            entries.add(new EntrySpec(entryPath, content, ZipEntry.DEFLATED, false));
            return this;
        }

        /** Adds a non-dependency entry using STORED compression. */
        public DeterministicJarBuilder addStoredEntry(String entryPath, byte[] content) {
            entries.add(new EntrySpec(entryPath, content, ZipEntry.STORED, false));
            storedEntries++;
            return this;
        }

        /** Adds a dependency entry with STORED compression using actual JAR content. */
        public DeterministicJarBuilder addStoredDependency(String entryPath) {
            DependencyEntry dep = findDependencyByEntryPath(entryPath);
            if (dep == null) {
                throw new IllegalArgumentException("Unknown dependency entry path: " + entryPath);
            }
            Path depJarPath = depDir.resolve(dep.artifactFileName());
            if (!Files.exists(depJarPath)) {
                throw new IllegalStateException("Dependency JAR not found: " + depJarPath);
            }
            try {
                byte[] content = Files.readAllBytes(depJarPath);
                entries.add(new EntrySpec(entryPath, content, ZipEntry.STORED, true));
                storedEntries++;
            } catch (IOException e) {
                throw new RuntimeException("Failed to read dependency JAR: " + depJarPath, e);
            }
            return this;
        }

        /** Returns the expected total number of entries. */
        public int entryCount() {
            return entries.size();
        }

        /** Returns true if all entries use DEFLATE compression. */
        public boolean isAllDeflate() {
            return storedEntries == 0;
        }

        /** Returns true if all entries use STORED compression. */
        public boolean isAllStored() {
            return storedEntries == entries.size() && !entries.isEmpty();
        }

        /**
         * Builds the JAR bytes.
         * STORED entries have CRC-32 and sizes preset; all entries use DOS epoch time.
         */
        public byte[] build() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            CRC32 crc = new CRC32();

            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
                for (EntrySpec spec : entries) {
                    ZipEntry entry = new ZipEntry(spec.name());
                    entry.setMethod(spec.compressionMethod());
                    entry.setTime(DOS_EPOCH_MILLIS);

                    if (spec.compressionMethod() == ZipEntry.STORED) {
                        crc.reset();
                        crc.update(spec.content());
                        entry.setCrc(crc.getValue());
                        entry.setSize(spec.content().length);
                        entry.setCompressedSize(spec.content().length);
                    }

                    zos.putNextEntry(entry);
                    zos.write(spec.content());
                    zos.closeEntry();
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to build JAR", e);
            }

            return baos.toByteArray();
        }

        /**
         * Builds a JAR with explicit content overrides for specific non-dependency entries.
         */
        public byte[] buildWithOverrides(Map<String, byte[]> overrides) {
            List<EntrySpec> resolved = new ArrayList<>();
            for (EntrySpec spec : entries) {
                if (!spec.isDependency() && overrides.containsKey(spec.name())) {
                    resolved.add(new EntrySpec(
                            spec.name(),
                            overrides.get(spec.name()),
                            spec.compressionMethod(),
                            false));
                } else {
                    resolved.add(spec);
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            CRC32 crc = new CRC32();

            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
                for (EntrySpec spec : resolved) {
                    ZipEntry entry = new ZipEntry(spec.name());
                    entry.setMethod(spec.compressionMethod());
                    entry.setTime(DOS_EPOCH_MILLIS);

                    if (spec.compressionMethod() == ZipEntry.STORED) {
                        crc.reset();
                        crc.update(spec.content());
                        entry.setCrc(crc.getValue());
                        entry.setSize(spec.content().length);
                        entry.setCompressedSize(spec.content().length);
                    }

                    zos.putNextEntry(entry);
                    zos.write(spec.content());
                    zos.closeEntry();
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to build JAR with overrides", e);
            }

            return baos.toByteArray();
        }
    }

    /** Returns a new DeterministicJarBuilder instance. */
    public DeterministicJarBuilder jarBuilder() {
        return new DeterministicJarBuilder();
    }

    /**
     * Computes exact STORED linear size for uncompressed content.
     * Formula: local header (30 + nameLen + 2) + data + data descriptor (12) + cd entry (46 + nameLen + 2)
     */
    public static long storedLinearSize(String entryName, long uncompressedSize) {
        byte[] nameBytes = entryName.getBytes(StandardCharsets.UTF_8);
        long nameLen = nameBytes.length;
        // Local file header: 30 + nameLen + extra(2)
        // Data descriptor for STORED: 12 bytes
        // Central directory entry: 46 + nameLen + extra(2)
        return (30 + nameLen + 2 + 12 + uncompressedSize) + (46 + nameLen + 2);
    }
}
