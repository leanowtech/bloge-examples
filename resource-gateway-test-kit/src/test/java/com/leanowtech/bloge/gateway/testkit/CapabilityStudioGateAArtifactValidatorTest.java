package com.leanowtech.bloge.gateway.testkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive validator tests for Gate A artifact boundary.
 *
 * <p>Tests cover:</p>
 * <ul>
 *   <li>Success case with conforming synthetic JAR and exact 8 dependency JARs</li>
 *   <li>Size/count/ratio budget violations</li>
 *   <li>Unsafe and duplicate entries</li>
 *   <li>Missing required entries</li>
 *   <li>Wrong Main-Class</li>
 *   <li>Missing/tampered/extra dependency</li>
 *   <li>Malformed/truncated zip</li>
 *   <li>Path drift verification</li>
 *   <li>CodeSource enforcement</li>
 * </ul>
 *
 * <p>Uses exact tracked authority (resolves workspace file, does not copy into test resources).</p>
 */
class CapabilityStudioGateAArtifactValidatorTest {

    /** Tracked source authority resource path. */
    private static final String MAVEN_REPO = System.getProperty("user.home") + "/.m2/repository";

    /** Standard artifact limits */
    private static final Map<String, Object> STANDARD_LIMITS;
    static {
        STANDARD_LIMITS = new LinkedHashMap<>();
        STANDARD_LIMITS.put("maxRawBytes", 16777216L);
        STANDARD_LIMITS.put("maxZipEntries", 512);
        STANDARD_LIMITS.put("maxSingleEntryBytes", 8388608L);
        STANDARD_LIMITS.put("maxTotalUncompressedBytes", 67108864L);
        STANDARD_LIMITS.put("maxCompressionRatio", 100.0);
    }

    /** Required jar entries from authority */
    private static final List<String> REQUIRED_ENTRIES = List.of(
            "META-INF/MANIFEST.MF",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class",
            "META-INF/maven/com.leanowtech.bloge/bloge-resource-gateway-test-kit/pom.properties",
            "META-INF/gate-a/protocol/protocol-compilation-manifest-v1.json",
            "META-INF/gate-a/projections/canonicalization-contract-v1.json",
            "META-INF/gate-a/canonicalization/fingerprint-profile-v1.json",
            "META-INF/gate-a/manifests/dependencies.json",
            "META-INF/gate-a/protocol/gate-a-protocol-authority.json",
            "META-INF/gate-a/manifests/classes.json",
            "META-INF/gate-a/manifests/resources.json"
    );

    @TempDir
    Path tempDir;

    // ── Helper methods ────────────────────────────────────────────────

    /** Stable path to the tracked source authority document. */
    private static final Path TRACKED_AUTHORITY_PATH = Path.of(
            System.getProperty("user.dir"),
            "..", "docs", "acceptance", "capability-studio", "gate-a-wire-v1",
            "protocol-compiler", "gate-a-protocol-authority-v1.json");

    /**
     * Reads the tracked authority from the source-docs path.
     * Fails fast if the file does not exist (fail-closed).
     */
    private static byte[] readTrackedAuthority() {
        if (!Files.exists(TRACKED_AUTHORITY_PATH)) {
            fail("TRACKED_AUTHORITY_NOT_FOUND:" + TRACKED_AUTHORITY_PATH.toAbsolutePath());
            throw new AssertionError("unreachable");
        }
        try {
            return Files.readAllBytes(TRACKED_AUTHORITY_PATH);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read tracked authority: " + TRACKED_AUTHORITY_PATH, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> buildDependencyPins() {
        byte[] raw = readTrackedAuthority();
        Map<String, Object> authority = CapabilityStudioGateAAuthorityValidator.validate(raw);

        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> pins =
                (Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin>) authority.get("_dependencyPins");
        assertNotNull(pins, "_dependencyPins must be present");
        assertEquals(8, pins.size(), "exactly 8 dependency pins expected");

        return pins;
    }

    /**
     * Reads a real Maven dependency JAR from local repository.
     */
    private byte[] readRealDependencyJar(CapabilityStudioGateAAuthorityValidator.DependencyPin pin) {
        String groupId = pin.groupId().replace('.', '/');
        String artifactId = pin.artifactId();
        String version = pin.version();

        Path jarPath = Path.of(MAVEN_REPO, groupId, artifactId, version, artifactId + "-" + version + ".jar");
        try {
            return Files.readAllBytes(jarPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read dependency: " + jarPath, e);
        }
    }

    /**
     * Creates a synthetic JAR with all required entries and exact 8 dependency JARs
     * using REAL Maven dependency JARs from local repository.
     */
    private byte[] createConformingJar(Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                // Add directory entries
                for (String dir : List.of(
                        "com/",
                        "com/leanowtech/",
                        "com/leanowtech/bloge/",
                        "com/leanowtech/bloge/gateway/",
                        "com/leanowtech/bloge/gateway/testkit/",
                        "META-INF/",
                        "META-INF/maven/",
                        "META-INF/maven/com.leanowtech.bloge/",
                        "META-INF/maven/com.leanowtech.bloge/bloge-resource-gateway-test-kit/",
                        "META-INF/gate-a/",
                        "META-INF/gate-a/protocol/",
                        "META-INF/gate-a/projections/",
                        "META-INF/gate-a/canonicalization/",
                        "META-INF/gate-a/manifests/",
                        "META-INF/gate-a/dependencies/"
                )) {
                    jos.putNextEntry(new JarEntry(dir));
                    jos.closeEntry();
                }

                // Add pom.properties
                String pomProps = "version=1.0.0\ngroupId=com.leanowtech.bloge\nartifactId=bloge-resource-gateway-test-kit\n";
                jos.putNextEntry(new JarEntry("META-INF/maven/com.leanowtech.bloge/bloge-resource-gateway-test-kit/pom.properties"));
                jos.write(pomProps.getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();

                // Add CLI class
                byte[] cliClass = createMinimalClassBytes();
                jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class"));
                jos.write(cliClass);
                jos.closeEntry();

                // Add authority provider class
                byte[] providerClass = createMinimalClassBytes();
                jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class"));
                jos.write(providerClass);
                jos.closeEntry();

                // Add gate-a protocol files
                for (String[] file : new String[][] {
                        {"META-INF/gate-a/protocol/protocol-compilation-manifest-v1.json", "{}"},
                        {"META-INF/gate-a/projections/canonicalization-contract-v1.json", "{}"},
                        {"META-INF/gate-a/canonicalization/fingerprint-profile-v1.json", "{}"},
                        {"META-INF/gate-a/manifests/dependencies.json", "{}"},
                        {"META-INF/gate-a/protocol/gate-a-protocol-authority.json", "{}"},
                        {"META-INF/gate-a/manifests/classes.json", "{\"classes\":[]}"},
                        {"META-INF/gate-a/manifests/resources.json", "{\"resources\":[]}"}
                }) {
                    jos.putNextEntry(new JarEntry(file[0]));
                    jos.write(file[1].getBytes(StandardCharsets.UTF_8));
                    jos.closeEntry();
                }

                // Add 8 REAL dependency JARs from Maven local repository
                for (CapabilityStudioGateAAuthorityValidator.DependencyPin pin : depPins.values()) {
                    byte[] depJar = readRealDependencyJar(pin);
                    jos.putNextEntry(new JarEntry(pin.entryPath));
                    jos.write(depJar);
                    jos.closeEntry();
                }
            }

            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create JAR", e);
        }
    }

    /**
     * Creates a minimal valid class file bytes.
     */
    private byte[] createMinimalClassBytes() {
        return new byte[] {
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0x00, 0x00, 0x00, 0x34,
                0x00, 0x0D,
                0x0A, 0x00, 0x03, 0x00, 0x0A,
                0x07, 0x00, 0x0B,
                0x07, 0x00, 0x0C,
                0x01, 0x00, 0x06, '<', 'i', 'n', 'i', 't', '>',
                0x01, 0x00, 0x03, '(', 'V', ')', 'V',
                0x01, 0x00, 0x04, 'C', 'o', 'd', 'e',
                0x01, 0x00, 0x0A, 'S', 'o', 'u', 'r', 'c', 'e', 'F', 'i', 'l', 'e',
                0x01, 0x00, 0x0F, '<', 'u', 'n', 'n', 'a', 'm', 'e', 'd', '>',
                0x0C, 0x00, 0x04, 0x05,
                0x01, 0x00, 0x10, 'j', 'a', 'v', 'a', '/', 'l', 'a', 'n', '/', 'O', 'b', 'j', 'e', 'c', 't',
                0x01, 0x00, 0x10, 'j', 'a', 'v', 'a', '/', 'l', 'a', 'n', '/', 'O', 'b', 'j', 'e', 'c', 't',
                0x00, 0x21, 0x00, 0x02, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x04, 0x00, 0x05, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00, 0x00, 0x11,
                0x00, 0x01, 0x00, 0x00, 0x00, 0x05, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                (byte) 0xB1,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x07, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x09,
                0x00, 0x01, 0x00, 0x0C, 'D', 'u', 'm', 'm', 'y', '.', 'j', 'a', 'v', 'a'
        };
    }

    // ── Success tests ────────────────────────────────────────────────

    @Test
    void acceptsConformingJar() throws Exception {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        Path jarPath = tempDir.resolve("artifact.jar");
        Files.write(jarPath, jar);

        CapabilityStudioGateAArtifactValidator.ValidationResult result =
                CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, jarPath, false);

        assertTrue(result.isValid(), "Conforming JAR should be valid");
        assertNotNull(result.manifest, "Manifest should not be null");
        assertEquals("com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli",
                result.manifest.getMainAttributes().getValue("Main-Class"));
        assertEquals(8, result.dependencyPins.size(), "Should have 8 verified dependency pins");
        assertTrue(result.requiredJarEntriesMissing.isEmpty(), "No required entries should be missing");

        // Verify rawEntries is unmodifiable
        assertThrows(UnsupportedOperationException.class, () ->
                result.rawEntries.put("test", new byte[0]));
    }

    @Test
    void acceptsJarWithoutPathEnforcement() throws Exception {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        CapabilityStudioGateAArtifactValidator.ValidationResult result =
                CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, null, false);

        assertTrue(result.isValid(), "Should accept without path enforcement");
    }

    // ── Size budget tests ───────────────────────────────────────────

    @Test
    void rejectsRawBytesOverLimit() {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        Map<String, Object> tightLimits = new LinkedHashMap<>(STANDARD_LIMITS);
        tightLimits.put("maxRawBytes", (long) jar.length - 1);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, tightLimits, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_RAW_BYTES_LIMIT"), ex.errorCode());
    }

    @Test
    void rejectsSingleEntryOverLimit() {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        Map<String, Object> tightLimits = new LinkedHashMap<>(STANDARD_LIMITS);
        tightLimits.put("maxSingleEntryBytes", 100L);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, tightLimits, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_ENTRY_SIZE_LIMIT"), ex.errorCode());
    }

    @Test
    void rejectsTotalUncompressedOverLimit() {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        Map<String, Object> tightLimits = new LinkedHashMap<>(STANDARD_LIMITS);
        tightLimits.put("maxTotalUncompressedBytes", 1024L);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, tightLimits, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_TOTAL_UNCOMPRESSED_LIMIT"), ex.errorCode());
    }

    // ── Count budget tests ───────────────────────────────────────────

    @Test
    void rejectsEntryCountOverLimit() {
        byte[] jar = createJarWithManyEntries(15);

        Map<String, Object> tightLimits = new LinkedHashMap<>(STANDARD_LIMITS);
        tightLimits.put("maxZipEntries", 10);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, tightLimits, List.of(), Map.of(), null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_ENTRY_COUNT_LIMIT"), ex.errorCode());
    }

    private byte[] createJarWithManyEntries(int count) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                for (int i = 0; i < count; i++) {
                    jos.putNextEntry(new JarEntry("entry" + i + ".txt"));
                    jos.write(("Entry " + i).getBytes(StandardCharsets.UTF_8));
                    jos.closeEntry();
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Ratio budget tests ──────────────────────────────────────────

    @Test
    void rejectsExcessiveCompressionRatio() {
        byte[] jar;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 10000; i++) {
                    sb.append("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
                }
                byte[] content = sb.toString().getBytes(StandardCharsets.UTF_8);

                jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class"));
                jos.write(content);
                jos.closeEntry();
            }
            jar = baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Map<String, Object> tightLimits = new LinkedHashMap<>(STANDARD_LIMITS);
        tightLimits.put("maxCompressionRatio", 2.0);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, tightLimits, List.of(), Map.of(), null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_COMPRESSION_RATIO_EXCEEDED"), ex.errorCode());
    }

    @Test
    void rejectsInvalidCompressionRatio() {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        Map<String, Object> badLimits = new LinkedHashMap<>(STANDARD_LIMITS);
        badLimits.put("maxCompressionRatio", 0.0);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, badLimits, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_MAX_COMPRESSION_RATIO_INVALID"), ex.errorCode());
    }

    // ── Entry name validation tests ─────────────────────────────────

    @Test
    void rejectsEmptyEntryName() {
        byte[] jar = createJarWithBadEntry("");
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_ENTRY_EMPTY_NAME"), ex.errorCode());
    }

    @Test
    void rejectsAbsolutePathEntry() {
        byte[] jar = createJarWithBadEntry("/absolute/path.class");
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_ENTRY_PATH_INVALID"), ex.errorCode());
    }

    @Test
    void rejectsBackslashInEntryName() {
        byte[] jar = createJarWithBadEntry("path\\with\\backslash.class");
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_ENTRY_PATH_INVALID"), ex.errorCode());
    }

    @Test
    void rejectsDotEntryName() {
        byte[] jar = createJarWithBadEntry("path/./to/class.class");
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_ENTRY_DOT_PATH"), ex.errorCode());
    }


    @Test
    void rejectsDuplicateEntry() {
        byte[] jar = CapabilityStudioGateAArtifactValidatorTestFixtures.createJarWithDuplicateEntry();

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, List.of(), Map.of(), null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_ENTRY_COLLISION"), ex.errorCode());
    }

    private byte[] createJarWithBadEntry(String badName) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                jos.putNextEntry(new JarEntry(badName));
                jos.write(new byte[]{1, 2, 3, 4});
                jos.closeEntry();
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createJarWithDuplicateEntryRaw() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                // Write the same entry twice using ZipOutputStream (bypasses JarOutputStream's check)
                byte[] content = new byte[]{1, 2, 3, 4};

                // First write via JarOutputStream
                jos.putNextEntry(new JarEntry("com/dup/Duplicate.class"));
                jos.write(content);
                jos.closeEntry();
            }
            // Now create a new JAR with the duplicate entry added
            baos = new ByteArrayOutputStream();
            manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

            // Use ZipOutputStream directly to bypass JarOutputStream's duplicate detection
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                // Write manifest as a regular entry
                ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
                manifest.write(manifestBytes);
                zos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                zos.write(manifestBytes.toByteArray());
                zos.closeEntry();

                byte[] content = new byte[]{1, 2, 3, 4};
                // Write same entry twice
                zos.putNextEntry(new JarEntry("com/dup/Duplicate.class"));
                zos.write(content);
                zos.closeEntry();
                zos.putNextEntry(new JarEntry("com/dup/Duplicate.class"));
                zos.write(content);
                zos.closeEntry();
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Required entry tests ─────────────────────────────────────────

    @Test
    void rejectsMissingRequiredEntry() {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createJarMissingRequiredEntry("com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class", depPins);

        CapabilityStudioGateAArtifactValidator.ValidationResult result =
                CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, null, false);

        assertFalse(result.isValid(), "Should be invalid with missing entry");
        assertEquals(1, result.requiredJarEntriesMissing.size());
        assertTrue(result.requiredJarEntriesMissing.contains(
                "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class"));
    }

    private byte[] createJarMissingRequiredEntry(String missingEntry,
            Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                for (String entry : REQUIRED_ENTRIES) {
                    if (!entry.equals(missingEntry) && !entry.equals("META-INF/MANIFEST.MF")) {
                        jos.putNextEntry(new JarEntry(entry));
                        jos.write("{}".getBytes(StandardCharsets.UTF_8));
                        jos.closeEntry();
                    }
                }
                for (CapabilityStudioGateAAuthorityValidator.DependencyPin pin : depPins.values()) {
                    byte[] depJar = readRealDependencyJar(pin);
                    jos.putNextEntry(new JarEntry(pin.entryPath));
                    jos.write(depJar);
                    jos.closeEntry();
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Main-Class tests ────────────────────────────────────────────

    @Test
    void rejectsWrongMainClass() {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createJarWithWrongMainClass(depPins);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_MAIN_CLASS_MISMATCH"), ex.errorCode());
    }

    @Test
    void rejectsMissingManifest() {
        byte[] jar = createJarWithoutManifest();

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, List.of(), Map.of(), null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_MANIFEST_MISSING"), ex.errorCode());
    }

    private byte[] createJarWithWrongMainClass(Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class", "com.wrong.MainClass");

            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                // Add required entries except manifest
                for (String entry : REQUIRED_ENTRIES) {
                    if (!entry.equals("META-INF/MANIFEST.MF")) {
                        jos.putNextEntry(new JarEntry(entry));
                        jos.write("{}".getBytes(StandardCharsets.UTF_8));
                        jos.closeEntry();
                    }
                }
                for (CapabilityStudioGateAAuthorityValidator.DependencyPin pin : depPins.values()) {
                    byte[] depJar = readRealDependencyJar(pin);
                    jos.putNextEntry(new JarEntry(pin.entryPath));
                    jos.write(depJar);
                    jos.closeEntry();
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createJarWithoutManifest() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry("com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class"));
                zos.write(new byte[]{1, 2, 3, 4});
                zos.closeEntry();
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Dependency closure tests ─────────────────────────────────────

    @Test
    void rejectsMissingDependencyEntry() {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createJarWithMissingDependency(depPins);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_DEPENDENCY_ENTRY_MISSING"), ex.errorCode());
    }

    @Test
    void rejectsTamperedDependency() {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createJarWithTamperedDependency(depPins);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_DEPENDENCY_SHA256_MISMATCH"), ex.errorCode());
    }

    @Test
    void rejectsExtraDependencyJar() {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createJarWithExtraDependency(depPins);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_EXTRA_DEPENDENCY_JAR"), ex.errorCode());
    }

    private byte[] createJarWithMissingDependency(Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                for (String entry : REQUIRED_ENTRIES) {
                    if (!entry.equals("META-INF/MANIFEST.MF")) {
                        jos.putNextEntry(new JarEntry(entry));
                        jos.write("{}".getBytes(StandardCharsets.UTF_8));
                        jos.closeEntry();
                    }
                }
                // Add only 7 dependencies, not 8
                int count = 0;
                for (CapabilityStudioGateAAuthorityValidator.DependencyPin pin : depPins.values()) {
                    if (++count > 7) break;
                    byte[] depJar = readRealDependencyJar(pin);
                    jos.putNextEntry(new JarEntry(pin.entryPath));
                    jos.write(depJar);
                    jos.closeEntry();
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createJarWithTamperedDependency(Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                for (String entry : REQUIRED_ENTRIES) {
                    if (!entry.equals("META-INF/MANIFEST.MF")) {
                        jos.putNextEntry(new JarEntry(entry));
                        jos.write("{}".getBytes(StandardCharsets.UTF_8));
                        jos.closeEntry();
                    }
                }
                for (CapabilityStudioGateAAuthorityValidator.DependencyPin pin : depPins.values()) {
                    byte[] depJar = readRealDependencyJar(pin);
                    // Tamper with the first dependency
                    if (depJar.length > 10) {
                        depJar[10] ^= 0xFF;
                    }
                    jos.putNextEntry(new JarEntry(pin.entryPath));
                    jos.write(depJar);
                    jos.closeEntry();
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createJarWithExtraDependency(Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                for (String entry : REQUIRED_ENTRIES) {
                    if (!entry.equals("META-INF/MANIFEST.MF")) {
                        jos.putNextEntry(new JarEntry(entry));
                        jos.write("{}".getBytes(StandardCharsets.UTF_8));
                        jos.closeEntry();
                    }
                }
                for (CapabilityStudioGateAAuthorityValidator.DependencyPin pin : depPins.values()) {
                    byte[] depJar = readRealDependencyJar(pin);
                    jos.putNextEntry(new JarEntry(pin.entryPath));
                    jos.write(depJar);
                    jos.closeEntry();
                }
                // Add extra unknown dependency using the same jackson-databind jar with a different name
                Path unknownJar = Path.of(MAVEN_REPO, "com/fasterxml/jackson/core/jackson-databind/2.18.2/jackson-databind-2.18.2.jar");
                byte[] extraDepJar = Files.readAllBytes(unknownJar);
                jos.putNextEntry(new JarEntry("META-INF/gate-a/dependencies/unknown-1.0.0.jar"));
                jos.write(extraDepJar);
                jos.closeEntry();
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Malformed zip tests ──────────────────────────────────────────

    @Test
    void rejectsTruncatedZip() {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        int truncateAt = jar.length / 2;
        byte[] truncated = new byte[truncateAt];
        System.arraycopy(jar, 0, truncated, 0, truncateAt);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        truncated, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_IO_ERROR"), ex.errorCode());
    }

    @Test
    void rejectsInvalidZip() {
        byte[] invalidZip = "This is not a ZIP file".getBytes(StandardCharsets.UTF_8);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        invalidZip, STANDARD_LIMITS, REQUIRED_ENTRIES, Map.of(), null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_IO_ERROR") ||
                   ex.errorCode().startsWith("ARTIFACT_ENTRY"), ex.errorCode());
    }

    // ── Path drift tests ─────────────────────────────────────────────

    @Test
    void rejectsPathBytesMismatch() throws Exception {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        Path jarPath = tempDir.resolve("artifact.jar");
        Files.write(jarPath, jar);

        byte[] modified = jar.clone();
        modified[0] ^= 0xFF;
        Files.write(jarPath, modified);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, jarPath, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_PATH_RAW_BYTES_MISMATCH"), ex.errorCode());
    }

    // ── CodeSource enforcement tests ────────────────────────────────

    @Test
    void rejectsCodeSourceEnforcementWhenNotFromJar() throws Exception {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, null, true));

        assertTrue(ex.errorCode().startsWith("ARTIFACT_CODESOURCE") ||
                   ex.errorCode().startsWith("ARTIFACT_PATH_READ_ERROR"),
                   "Expected CodeSource or path error, got: " + ex.errorCode());
    }

    @Test
    void acceptsWithoutCodeSourceEnforcement() throws Exception {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        Path jarPath = tempDir.resolve("matching-artifact.jar");
        Files.write(jarPath, jar);

        CapabilityStudioGateAArtifactValidator.ValidationResult result =
                CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, jarPath, false);

        assertTrue(result.isValid(), "Should accept with matching path and no CodeSource enforcement");
    }

    // ── Limit validation tests ───────────────────────────────────────

    @Test
    void rejectsInvalidLimit() {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        Map<String, Object> badLimits = new LinkedHashMap<>(STANDARD_LIMITS);
        badLimits.put("maxRawBytes", "not-a-number");

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, badLimits, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_LIMIT_INVALID"), ex.errorCode());
    }

    @Test
    void rejectsZeroLimit() {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        Map<String, Object> zeroLimits = new LinkedHashMap<>(STANDARD_LIMITS);
        zeroLimits.put("maxRawBytes", 0L);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, zeroLimits, REQUIRED_ENTRIES, depPins, null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_LIMIT_INVALID"), ex.errorCode());
    }

    // ── Zero-size entry tests ───────────────────────────────────────


    @Test
    void rejectsZeroSizeEntryWithContent() {
        byte[] jar = CapabilityStudioGateAArtifactValidatorTestFixtures.createJarWithZeroSizeEntry();

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, List.of(), Map.of(), null, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_ENTRY_ZERO_SIZE_MISMATCH"), ex.errorCode());
    }

    private byte[] createJarWithZeroSizeEntryRaw() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                // Create entry with wrong size metadata
                ZipEntry ze = new ZipEntry("META-INF/some-file.txt");
                ze.setSize(0); // Declare zero size
                jos.putNextEntry(ze);
                jos.write("has content".getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Path stability and type tests ────────────────────────────────

    @Test
    void rejectsSymlinkPath() throws Exception {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        // Create real file
        Path realFile = tempDir.resolve("real-artifact.jar");
        Files.write(realFile, jar);

        // Create symlink to the real file
        Path symlink = tempDir.resolve("symlink-artifact.jar");
        try {
            Files.createSymbolicLink(symlink, realFile);
        } catch (UnsupportedOperationException e) {
            // Platform doesn't support symlinks - skip test
            return;
        }

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, symlink, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_PATH_NOT_REGULAR"),
                "Expected ARTIFACT_PATH_NOT_REGULAR, got: " + ex.errorCode());
    }

    @Test
    void rejectsDirectoryPath() throws Exception {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        // Create a directory instead of a file
        Path dir = tempDir.resolve("artifact-dir");
        Files.createDirectories(dir);

        CapabilityStudioGateAException ex = assertThrows(
                CapabilityStudioGateAException.class,
                () -> CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, dir, false));
        assertTrue(ex.errorCode().startsWith("ARTIFACT_PATH_NOT_REGULAR"),
                "Expected ARTIFACT_PATH_NOT_REGULAR, got: " + ex.errorCode());
    }

    @Test
    void acceptsPathStabilityAfterReading() throws Exception {
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins = buildDependencyPins();
        byte[] jar = createConformingJar(depPins);

        Path jarPath = tempDir.resolve("stable-artifact.jar");
        Files.write(jarPath, jar);

        // Validation should succeed and not change path attributes
        CapabilityStudioGateAArtifactValidator.ValidationResult result =
                CapabilityStudioGateAArtifactValidator.validate(
                        jar, STANDARD_LIMITS, REQUIRED_ENTRIES, depPins, jarPath, false);

        assertTrue(result.isValid(), "Should accept stable path");
        assertTrue(Files.exists(jarPath), "Path should still exist");
        assertTrue(Files.isRegularFile(jarPath, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "Path should still be regular file");
    }
}
