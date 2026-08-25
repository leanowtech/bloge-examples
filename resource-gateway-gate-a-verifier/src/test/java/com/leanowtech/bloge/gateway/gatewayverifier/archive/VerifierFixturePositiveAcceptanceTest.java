package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A1.3-02 PF-01..PF-10 positive acceptance tests with fixed denominator (10).
 *
 * <p>All tests verify successful acceptance (snapshot.rejected=false) after
 * full pipeline traversal: PackagingPlanParser + ArchiveKernel.
 *
 * <p>Tests use deterministic JAR construction via DeterministicJarBuilder
 * with explicit control over compression methods, entry contents, and sizes.
 */
class VerifierFixturePositiveAcceptanceTest {

    @TempDir
    Path tempDir;

    private static RealVerifierFixtureFactory FACTORY;
    private static Path AUTHORITY_PATH;
    private static Path DEP_JARS_DIR;
    private static byte[] BASELINE_JAR_BYTES;
    private static byte[] BASELINE_PLAN_BYTES;

    // Constants derived from Authority artifactLimits
    private static final int PF_DENOMINATOR = 10;
    private static final long MAX_RAW_BYTES = 16777216L;    // maxRawBytes from Authority
    private static final long MAX_SINGLE_ENTRY_BYTES = 8388608L;  // maxSingleEntryBytes
    private static final int MAX_ZIP_ENTRIES = 512;         // maxZipEntries

    @BeforeAll
    static void setupAll(@TempDir Path staticTempDir) throws Exception {
        String authPathStr = System.getProperty("gate.a.authority.path");
        assertNotNull(authPathStr, "System property gate.a.authority.path must be set");
        AUTHORITY_PATH = Path.of(authPathStr);
        assertTrue(Files.exists(AUTHORITY_PATH), "Authority JSON not found: " + AUTHORITY_PATH);

        String depJarsStr = System.getProperty("gate.a.dependency.jars");
        assertNotNull(depJarsStr, "System property gate.a.dependency.jars must be set");
        DEP_JARS_DIR = Path.of(depJarsStr);
        assertTrue(Files.isDirectory(DEP_JARS_DIR), "Dependency JARs dir not found: " + DEP_JARS_DIR);

        FACTORY = new RealVerifierFixtureFactory(AUTHORITY_PATH, DEP_JARS_DIR);
        BASELINE_JAR_BYTES = FACTORY.buildBaselineJar(staticTempDir);
        BASELINE_PLAN_BYTES = FACTORY.buildPackagingPlan(BASELINE_JAR_BYTES);
    }

    // -------------------------------------------------------------------------
    // PF-01: exact Authority closure
    // -------------------------------------------------------------------------

    /**
     * PF-01: Baseline JAR with exact Authority closure accepts.
     * Verifies snapshot.rejected=false and exact entry count (28).
     */
    @Test
    @DisplayName("PF-01: exact Authority closure")
    void pf01_exact_authority_closure() throws Exception {
        assertNotNull(BASELINE_JAR_BYTES, "Baseline JAR must be built");
        assertNotNull(BASELINE_PLAN_BYTES, "Baseline plan must be built");

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = RealVerifierFixtureFactory.sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        assertTrue(parseResult.isSuccess(), "Plan must parse successfully");

        PackagedPlan plan = parseResult.plan();

        // Assert denominator via reflection: REQUIRED_ARCHIVE_ENTRY_COUNT == 28
        int denominator = getIntField(PackagedPlan.class, "REQUIRED_ARCHIVE_ENTRY_COUNT");
        assertEquals(PF_DENOMINATOR * 28 / 10, denominator,
                "PF denominator check: archive entry count must equal 28");

        Path jarPath = tempDir.resolve("pf01.jar");
        Files.write(jarPath, BASELINE_JAR_BYTES);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        assertFalse(snapshot.rejected(), "PF-01: baseline JAR must be accepted");
        assertEquals(28, snapshot.entryCount(), "PF-01: must have exactly 28 entries");
        assertEquals(7, snapshot.dependencyCount(), "PF-01: must have exactly 7 dependencies");
    }

    // -------------------------------------------------------------------------
    // PF-02: all DEFLATE compression
    // -------------------------------------------------------------------------

    /**
     * PF-02: JAR with all DEFLATE compression accepts.
     * Verifies all entries use DEFLATE method.
     */
    @Test
    @DisplayName("PF-02: all DEFLATE compression")
    void pf02_all_deflate() throws Exception {
        assertNotNull(BASELINE_JAR_BYTES, "Baseline JAR must exist");

        // Verify via ZipInputStream that all entries are DEFLATED
        Set<Integer> methods = new HashSet<>();
        try (java.util.zip.ZipInputStream zis = 
                new java.util.zip.ZipInputStream(new ByteArrayInputStream(BASELINE_JAR_BYTES))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                methods.add(entry.getMethod());
                zis.closeEntry();
            }
        }

        assertTrue(methods.contains(java.util.zip.ZipEntry.DEFLATED),
                "PF-02: JAR must contain DEFLATED entries");
        assertEquals(1, methods.size(),
                "PF-02: all entries must use same compression method");

        // Full pipeline: Parser + Kernel
        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = RealVerifierFixtureFactory.sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        assertTrue(parseResult.isSuccess(), "PF-02: plan must parse");

        Path jarPath = tempDir.resolve("pf02.jar");
        Files.write(jarPath, BASELINE_JAR_BYTES);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, parseResult.plan());
        assertFalse(snapshot.rejected(), "PF-02: DEFLATE JAR must be accepted via full pipeline");
    }

    // -------------------------------------------------------------------------
    // PF-03: all STORED compression
    // -------------------------------------------------------------------------

    /**
     * PF-03: JAR with all STORED compression accepts.
     * Verifies all entries use STORED method with preset CRC and sizes.
     */
    @Test
    @DisplayName("PF-03: all STORED compression")
    void pf03_all_stored() throws Exception {
        assertNotNull(BASELINE_PLAN_BYTES, "Baseline plan must exist");

        // Build a JAR with all STORED entries
        RealVerifierFixtureFactory.DeterministicJarBuilder builder = FACTORY.jarBuilder();

        // Add all 28 required entries with STORED compression
        for (String entryPath : FACTORY.requiredEntries()) {
            if (FACTORY.embeddedDependencies().stream()
                    .anyMatch(dep -> dep.entryPath().equals(entryPath))) {
                // Dependency: load actual content, use STORED
                builder.addStoredDependency(entryPath);
            } else {
                // Non-dependency: generate content
                byte[] content = entryPath.getBytes(StandardCharsets.UTF_8);
                builder.addStoredEntry(entryPath, content);
            }
        }

        assertTrue(builder.isAllStored(), "PF-03: all entries must use STORED");
        byte[] storedJar = builder.build();
        assertNotNull(storedJar, "PF-03: STORED JAR must be built");

        // Verify via ZipInputStream
        try (java.util.zip.ZipInputStream zis = 
                new java.util.zip.ZipInputStream(new ByteArrayInputStream(storedJar))) {
            java.util.zip.ZipEntry entry;
            int count = 0;
            while ((entry = zis.getNextEntry()) != null) {
                assertEquals(java.util.zip.ZipEntry.STORED, entry.getMethod(),
                        "PF-03: entry " + entry.getName() + " must use STORED");
                assertEquals(entry.getSize(), entry.getCompressedSize(),
                        "PF-03: STORED entry size must equal compressed size");
                assertTrue(entry.getCrc() != 0 || entry.getSize() == 0,
                        "PF-03: STORED entry must have valid CRC");
                count++;
                zis.closeEntry();
            }
            assertEquals(28, count, "PF-03: must have 28 entries");
        }

        // Verify via kernel
        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = RealVerifierFixtureFactory.sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        assertTrue(parseResult.isSuccess());

        Path jarPath = tempDir.resolve("pf03.jar");
        Files.write(jarPath, storedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, parseResult.plan());

        assertFalse(snapshot.rejected(), "PF-03: all-STORED JAR must be accepted");
    }

    // -------------------------------------------------------------------------
    // PF-04: seven bound true
    // -------------------------------------------------------------------------

    /**
     * PF-04: Baseline JAR has all 7 dependencies bound with bound=true.
     * Verifies all nested JAR entries have matching fingerprints.
     */
    @Test
    @DisplayName("PF-04: seven bound true")
    void pf04_seven_bound_true() throws Exception {
        assertNotNull(BASELINE_JAR_BYTES, "Baseline JAR must exist");

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = RealVerifierFixtureFactory.sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        assertTrue(parseResult.isSuccess());

        Path jarPath = tempDir.resolve("pf04.jar");
        Files.write(jarPath, BASELINE_JAR_BYTES);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, parseResult.plan());

        assertFalse(snapshot.rejected(), "PF-04: baseline must be accepted");

        // Verify all 7 dependencies are bound
        assertEquals(7, snapshot.dependencyCount(), "PF-04: must have 7 dependencies");

        long boundCount = snapshot.dependencies().stream()
                .filter(ArchiveKernelSnapshot.Dependency::bound)
                .count();
        assertEquals(7, boundCount, "PF-04: all 7 dependencies must be bound=true");

        // Verify each dependency's actual fingerprint matches expected
        for (ArchiveKernelSnapshot.Dependency dep : snapshot.dependencies()) {
            assertTrue(dep.bound(),
                    "PF-04: dependency " + dep.lockId() + " at " + dep.entryPath() + " must be bound");
            assertEquals(dep.expectedFingerprint(), dep.actualFingerprint(),
                    "PF-04: fingerprint must match for " + dep.lockId());
        }
    }

    // -------------------------------------------------------------------------
    // PF-05: raw bytes exactly maxRawBytes-1 with STORED entries
    // -------------------------------------------------------------------------

    /**
     * PF-05: JAR with raw bytes exactly maxRawBytes-1 accepts.
     * Uses STORED entries for linear size calculation.
     * Padding split across at least 2 non-dependency entries, each < maxSingleEntryBytes.
     * Padding is appended to original entry content (not a replacement).
     */
    @Test
    @DisplayName("PF-05: raw bytes exactly maxRawBytes-1")
    void pf05_raw_bytes_max_minus_one() throws Exception {
        long targetSize = MAX_RAW_BYTES - 1;

        // Build JAR with all STORED entries
        RealVerifierFixtureFactory.DeterministicJarBuilder builder = FACTORY.jarBuilder();

        // Map to hold original non-dep content for padding calculation
        Map<String, byte[]> nonDepOriginal = new LinkedHashMap<>();

        for (String entryPath : FACTORY.requiredEntries()) {
            if (FACTORY.embeddedDependencies().stream()
                    .anyMatch(dep -> dep.entryPath().equals(entryPath))) {
                builder.addStoredDependency(entryPath);
            } else {
                byte[] content = entryPath.getBytes(StandardCharsets.UTF_8);
                nonDepOriginal.put(entryPath, content);
                builder.addStoredEntry(entryPath, content);
            }
        }

        byte[] baseJar = builder.build();
        long baseSize = baseJar.length;

        // Padding must be positive (assert, not assume - fixed denominator)
        long paddingNeeded = targetSize - baseSize;
        assertTrue(paddingNeeded > 0,
                "PF-05: STORED base JAR must be smaller than maxRawBytes-1 (paddingNeeded=" + paddingNeeded + ", baseSize=" + baseSize + ")");

        // Split padding across at least 2 non-dependency entries
        long halfPadding = paddingNeeded / 2;
        long otherPadding = paddingNeeded - halfPadding;
        assertTrue(halfPadding > 0 && otherPadding > 0,
                "PF-05: padding must be positive");

        // Each final payload (original + padding) must stay < maxSingleEntryBytes
        for (Map.Entry<String, byte[]> e : nonDepOriginal.entrySet()) {
            long finalSize0 = e.getValue().length + halfPadding;
            long finalSize1 = e.getValue().length + otherPadding;
            assertTrue(finalSize0 < MAX_SINGLE_ENTRY_BYTES,
                    "PF-05: " + e.getKey() + " + halfPadding=" + finalSize0 + " must be < maxSingleEntryBytes");
            assertTrue(finalSize1 < MAX_SINGLE_ENTRY_BYTES,
                    "PF-05: " + e.getKey() + " + otherPadding=" + finalSize1 + " must be < maxSingleEntryBytes");
        }

        // Build final content: original payload + padding bytes appended
        byte[] paddingA = new byte[(int) halfPadding];
        byte[] paddingB = new byte[(int) otherPadding];
        Arrays.fill(paddingA, (byte) 0x42);
        Arrays.fill(paddingB, (byte) 0x24);

        List<String> nonDepPaths = new ArrayList<>(nonDepOriginal.keySet());
        String padEntry0 = nonDepPaths.get(0);
        String padEntry1 = nonDepPaths.get(1);

        byte[] orig0 = nonDepOriginal.get(padEntry0);
        byte[] orig1 = nonDepOriginal.get(padEntry1);
        byte[] final0 = new byte[orig0.length + (int) halfPadding];
        byte[] final1 = new byte[orig1.length + (int) otherPadding];
        System.arraycopy(orig0, 0, final0, 0, orig0.length);
        System.arraycopy(paddingA, 0, final0, orig0.length, (int) halfPadding);
        System.arraycopy(orig1, 0, final1, 0, orig1.length);
        System.arraycopy(paddingB, 0, final1, orig1.length, (int) otherPadding);

        Map<String, byte[]> overrides = new HashMap<>();
        overrides.put(padEntry0, final0);
        overrides.put(padEntry1, final1);

        byte[] paddedJar = builder.buildWithOverrides(overrides);

        // Exact size assertion
        assertEquals(targetSize, paddedJar.length,
                "PF-05: JAR size must equal exactly maxRawBytes-1, got=" + paddedJar.length + " target=" + targetSize);

        Path jarPath = tempDir.resolve("pf05.jar");
        Files.write(jarPath, paddedJar);
        assertEquals(targetSize, Files.size(jarPath),
                "PF-05: Files.size must match targetSize");

        // Verify via kernel
        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = RealVerifierFixtureFactory.sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        assertTrue(parseResult.isSuccess());

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, parseResult.plan());

        assertFalse(snapshot.rejected(), "PF-05: maxRawBytes-1 JAR must be accepted");
    }

    // -------------------------------------------------------------------------
    // PF-06: single entry uncompressed exactly maxSingleEntryBytes-1
    // -------------------------------------------------------------------------

    /**
     * PF-06: JAR with one non-dependency entry at exactly maxSingleEntryBytes-1
     * and total raw < maxRawBytes accepts.
     */
    @Test
    @DisplayName("PF-06: single entry maxSingleEntryBytes-1")
    void pf06_single_entry_max_minus_one() throws Exception {
        long targetSize = MAX_SINGLE_ENTRY_BYTES - 1;
        assertTrue(targetSize > 0, "PF-06: maxSingleEntryBytes must be > 1");

        // Build JAR with one large entry
        RealVerifierFixtureFactory.DeterministicJarBuilder builder = FACTORY.jarBuilder();

        // Add all 28 required entries
        for (String entryPath : FACTORY.requiredEntries()) {
            if (FACTORY.embeddedDependencies().stream()
                    .anyMatch(dep -> dep.entryPath().equals(entryPath))) {
                builder.addDependency(entryPath);
            } else {
                // Use minimal content for non-target entries
                byte[] content = entryPath.getBytes(StandardCharsets.UTF_8);
                builder.addNonDependency(entryPath, content);
            }
        }

        // Add a large non-dependency entry with exact size
        String largeEntryName = "lib/overflow/Padding.class";
        byte[] largeContent = new byte[(int) targetSize];
        Arrays.fill(largeContent, (byte) 0xAB);

        Map<String, byte[]> overrides = new HashMap<>();
        overrides.put(largeEntryName, largeContent);

        byte[] jarBytes = builder.buildWithOverrides(overrides);

        // Verify total raw < maxRawBytes
        assertTrue(jarBytes.length < MAX_RAW_BYTES,
                "PF-06: total raw must be < maxRawBytes");

        // Write and verify
        Path jarPath = tempDir.resolve("pf06.jar");
        Files.write(jarPath, jarBytes);

        // Verify via kernel
        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = RealVerifierFixtureFactory.sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        assertTrue(parseResult.isSuccess());

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, parseResult.plan());

        assertFalse(snapshot.rejected(), "PF-06: single large entry JAR must be accepted");
    }

    // -------------------------------------------------------------------------
    // PF-07: seven dep path with META-INF/gate-a/ and no dot segment
    // -------------------------------------------------------------------------

    /**
     * PF-07: All 7 dependency paths are under META-INF/gate-a/ and contain no dot segment.
     * Verifies path structure compliance.
     */
    @Test
    @DisplayName("PF-07: seven dep path META-INF/gate-a/ no dot segment")
    void pf07_dependency_paths_structure() throws Exception {
        List<RealVerifierFixtureFactory.DependencyEntry> deps = FACTORY.embeddedDependencies();
        assertEquals(7, deps.size(), "PF-07: must have 7 dependencies");

        for (RealVerifierFixtureFactory.DependencyEntry dep : deps) {
            String path = dep.entryPath();

            // Must start with META-INF/gate-a/
            assertTrue(path.startsWith("META-INF/gate-a/"),
                    "PF-07: " + dep.lockId() + " path must start with META-INF/gate-a/: " + path);

            // Must not contain dot segment (no /./ or /. at end)
            assertFalse(path.contains("/./"),
                    "PF-07: " + dep.lockId() + " path must not contain /./: " + path);
            assertFalse(path.endsWith("/."),
                    "PF-07: " + dep.lockId() + " path must not end with /.: " + path);

            // No redundant path segments
            assertFalse(path.contains("//"),
                    "PF-07: " + dep.lockId() + " path must not contain //: " + path);
        }

        // Verify via kernel (baseline should pass)
        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = RealVerifierFixtureFactory.sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        assertTrue(parseResult.isSuccess());

        Path jarPath = tempDir.resolve("pf07.jar");
        Files.write(jarPath, BASELINE_JAR_BYTES);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, parseResult.plan());

        assertFalse(snapshot.rejected(), "PF-07: dependency path structure must be accepted");
    }

    // -------------------------------------------------------------------------
    // PF-08: manifest exists and no multi-release
    // -------------------------------------------------------------------------

    /**
     * PF-08: JAR manifest exists and JAR is not multi-release.
     * Verifies META-INF/MANIFEST.MF presence and absence of versioned directories.
     */
    @Test
    @DisplayName("PF-08: manifest exists no multi-release")
    void pf08_manifest_no_multirelease() throws Exception {
        // Verify manifest exists in baseline
        boolean hasManifest = false;
        boolean hasMultiRelease = false;

        try (java.util.zip.ZipInputStream zis = 
                new java.util.zip.ZipInputStream(new ByteArrayInputStream(BASELINE_JAR_BYTES))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("META-INF/MANIFEST.MF")) {
                    hasManifest = true;
                }
                // Check for META-INF/versions/N/ pattern (multi-release indicator)
                if (name.startsWith("META-INF/versions/")) {
                    hasMultiRelease = true;
                }
                zis.closeEntry();
            }
        }

        assertTrue(hasManifest, "PF-08: META-INF/MANIFEST.MF must exist");
        assertFalse(hasMultiRelease, "PF-08: JAR must not be multi-release");

        // Verify via kernel
        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = RealVerifierFixtureFactory.sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        assertTrue(parseResult.isSuccess());

        Path jarPath = tempDir.resolve("pf08.jar");
        Files.write(jarPath, BASELINE_JAR_BYTES);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, parseResult.plan());

        assertFalse(snapshot.rejected(), "PF-08: valid manifest JAR must be accepted");
    }

    // -------------------------------------------------------------------------
    // PF-09: all Authority paths NFC and Latin-1
    // -------------------------------------------------------------------------

    /**
     * PF-09: All Authority-derived paths are Unicode NFC and Latin-1 encodable.
     * ASCII is a subset of Latin-1, so this is the primary check.
     */
    @Test
    @DisplayName("PF-09: all paths NFC and Latin-1")
    void pf09_paths_nfc_latin1() throws Exception {
        List<String> allPaths = new ArrayList<>();
        allPaths.addAll(FACTORY.requiredEntries());
        FACTORY.embeddedDependencies().forEach(dep -> allPaths.add(dep.entryPath()));

        for (String path : allPaths) {
            // Check NFC form (should be unchanged for ASCII/Latin-1 paths)
            String nfc = Normalizer.normalize(path, Normalizer.Form.NFC);
            assertEquals(path, nfc,
                    "PF-09: path must be in NFC form: " + path);

            // Check Latin-1 encodability (charset covers ISO-8859-1 = Latin-1)
            byte[] latin1Bytes = path.getBytes(StandardCharsets.ISO_8859_1);
            String decoded = new String(latin1Bytes, StandardCharsets.ISO_8859_1);
            assertEquals(path, decoded,
                    "PF-09: path must be encodable as Latin-1: " + path);

            // Verify no new plan is fabricated (paths come from Authority)
            assertTrue(FACTORY.requiredEntries().contains(path) ||
                            FACTORY.embeddedDependencies().stream()
                                    .anyMatch(dep -> dep.entryPath().equals(path)),
                    "PF-09: path must be from Authority: " + path);
        }

        // Verify via kernel
        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = RealVerifierFixtureFactory.sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        assertTrue(parseResult.isSuccess());

        Path jarPath = tempDir.resolve("pf09.jar");
        Files.write(jarPath, BASELINE_JAR_BYTES);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, parseResult.plan());

        assertFalse(snapshot.rejected(), "PF-09: NFC/Latin-1 paths must be accepted");
    }

    // -------------------------------------------------------------------------
    // PF-10: legal character regex and no violation patterns
    // -------------------------------------------------------------------------

    /**
     * PF-10: All paths match legal JAR entry name pattern.
     * Valid pattern: path-segments separated by '/', each segment matching:
     * [A-Za-z0-9_.$-]+ (no leading dot, no control chars)
     */
    @Test
    @DisplayName("PF-10: legal character regex no violation")
    void pf10_legal_characters() throws Exception {
        // Legal path pattern: segments of [A-Za-z0-9_.$-]+ separated by '/'
        java.util.regex.Pattern LEGAL_PATH = java.util.regex.Pattern.compile(
                "^([A-Za-z0-9_.$-]+/)*[A-Za-z0-9_.$-]*$");

        // Forbidden patterns
        java.util.regex.Pattern FORBIDDEN_NUL = java.util.regex.Pattern.compile("\u0000");
        java.util.regex.Pattern FORBIDDEN_CONTROL = java.util.regex.Pattern.compile("[\\x00-\\x1f\\x7f]");

        List<String> allPaths = new ArrayList<>();
        allPaths.addAll(FACTORY.requiredEntries());
        FACTORY.embeddedDependencies().forEach(dep -> allPaths.add(dep.entryPath()));

        for (String path : allPaths) {
            // Check legal character pattern
            assertTrue(LEGAL_PATH.matcher(path).matches(),
                    "PF-10: path must match legal character pattern: " + path);

            // Check no NUL character
            assertFalse(FORBIDDEN_NUL.matcher(path).find(),
                    "PF-10: path must not contain NUL: " + path);

            // Check no control characters
            assertFalse(FORBIDDEN_CONTROL.matcher(path).find(),
                    "PF-10: path must not contain control chars: " + path);

            // No absolute path (leading /)
            assertFalse(path.startsWith("/"),
                    "PF-10: path must not be absolute: " + path);

            // No parent directory reference
            assertFalse(path.contains(".."),
                    "PF-10: path must not contain ..: " + path);
        }

        // Verify via kernel
        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = RealVerifierFixtureFactory.sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        assertTrue(parseResult.isSuccess());

        Path jarPath = tempDir.resolve("pf10.jar");
        Files.write(jarPath, BASELINE_JAR_BYTES);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, parseResult.plan());

        assertFalse(snapshot.rejected(), "PF-10: legal path JAR must be accepted");
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    /**
     * Asserts that a static field exists and returns its value via reflection.
     */
    private static Object getFieldValue(Class<?> clazz, String fieldName) {
        try {
            java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(null);
        } catch (Exception e) {
            fail("Cannot read field: " + clazz.getName() + "." + fieldName + " - " + e.getMessage());
            throw new AssertionError("unreachable");
        }
    }

    private static int getIntField(Class<?> clazz, String fieldName) {
        Object value = getFieldValue(clazz, fieldName);
        assertTrue(value instanceof Number,
                "Field " + fieldName + " must be numeric, got: " + value.getClass());
        return ((Number) value).intValue();
    }
}
