package com.leanowtech.bloge.gateway.testkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Test fixtures for Gate A Challenge CLI tests.
 */
public final class CapabilityStudioGateAChallengeCliTestFixtures {

    /** Stable path to the tracked source authority document. */
    public static final Path TRACKED_AUTHORITY_PATH = Path.of(
            System.getProperty("user.dir"),
            "..", "docs", "acceptance", "capability-studio", "gate-a-wire-v1",
            "protocol-compiler", "gate-a-protocol-authority-v1.json");

    /** Maven local repository base. */
    public static final String MAVEN_REPO = System.getProperty("user.home") + "/.m2/repository";

    private CapabilityStudioGateAChallengeCliTestFixtures() {}

    // ── Tracked authority reader ──────────────────────────────────

    /**
     * Reads the tracked authority from the source-docs path.
     * Fails fast if the file does not exist (fail-closed).
     */
    public static byte[] readTrackedAuthority() {
        if (!Files.exists(TRACKED_AUTHORITY_PATH)) {
            throw new AssertionError("TRACKED_AUTHORITY_NOT_FOUND:" + TRACKED_AUTHORITY_PATH.toAbsolutePath());
        }
        try {
            return Files.readAllBytes(TRACKED_AUTHORITY_PATH);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read tracked authority: " + TRACKED_AUTHORITY_PATH, e);
        }
    }

    /**
     * Reads a Maven dependency JAR from local repository.
     */
    public static byte[] readDependencyJar(CapabilityStudioGateAAuthorityValidator.DependencyPin pin) {
        String groupId = pin.groupId().replace('.', '/');
        String artifactId = pin.artifactId();
        String version = pin.version();
        String filename = artifactId + "-" + version + ".jar";
        Path jarPath = Path.of(MAVEN_REPO, groupId, artifactId, version, filename);
        try {
            return Files.readAllBytes(jarPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read dependency: " + jarPath, e);
        }
    }

    /**
     * Reads a schema file from the docs/schemas directory.
     */
    public static byte[] readSchemaFile(String schemaId) {
        // Schema files are at docs/schemas/resource-gateway-capability-studio/ in the repo root
        // user.dir is the test-kit module directory (resource-gateway-test-kit/)
        Path schemaPath = Path.of(
                System.getProperty("user.dir"),
                "..", "docs", "schemas", "resource-gateway-capability-studio", schemaId);
        if (!Files.exists(schemaPath)) {
            throw new RuntimeException("Schema file not found: " + schemaPath);
        }
        try {
            return Files.readAllBytes(schemaPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read schema: " + schemaPath, e);
        }
    }

    // ── Dependency pin helper ───────────────────────────────────────

    /**
     * Builds dependency pins from tracked authority.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> buildDependencyPins() {
        byte[] raw = readTrackedAuthority();
        Map<String, Object> authority = CapabilityStudioGateAAuthorityValidator.validate(raw);
        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> pins =
                (Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin>) authority.get("_dependencyPins");
        if (pins == null || pins.size() != 8) {
            throw new AssertionError("_dependencyPins must contain exactly 8 entries");
        }
        return pins;
    }

    /**
     * Creates a conforming synthetic JAR with all required entries, embedded schemas,
     * and all 8 Maven dependency JARs at their required paths.
     */
    @SuppressWarnings("unchecked")
    public static byte[] createConformingJar(
            Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins,
            List<String> visibleSchemaIds) {

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
                        "META-INF/gate-a/dependencies/",
                        "schemas/"
                )) {
                    jos.putNextEntry(new JarEntry(dir));
                    jos.closeEntry();
                }

                // Add pom.properties
                String pomProps = "version=1.0.0\ngroupId=com.leanowtech.bloge\nartifactId=bloge-resource-gateway-test-kit\n";
                writeEntry(jos, "META-INF/maven/com.leanowtech.bloge/bloge-resource-gateway-test-kit/pom.properties",
                        pomProps.getBytes(StandardCharsets.UTF_8));

                // Add CLI class
                byte[] cliClass = createMinimalClassBytes();
                writeEntry(jos, "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class", cliClass);

                // Add authority provider class
                byte[] providerClass = createMinimalClassBytes();
                writeEntry(jos, "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class", providerClass);

                // Add gate-a protocol files
                for (String[] file : new String[][] {
                        {"META-INF/gate-a/protocol/protocol-compilation-manifest-v1.json", "{}"},
                        {"META-INF/gate-a/projections/canonicalization-contract-v1.json", "{}"},
                        {"META-INF/gate-a/canonicalization/fingerprint-profile-v1.json", "{}"},
                        {"META-INF/gate-a/manifests/dependencies.json", "[]"},
                        {"META-INF/gate-a/protocol/gate-a-protocol-authority.json", "{}"},
                        {"META-INF/gate-a/manifests/classes.json", "[]"},
                        {"META-INF/gate-a/manifests/resources.json", "[]"}
                }) {
                    writeEntry(jos, file[0], file[1].getBytes(StandardCharsets.UTF_8));
                }

                // Add embedded dependency JARs (exactly 8)
                for (CapabilityStudioGateAAuthorityValidator.DependencyPin pin : depPins.values()) {
                    byte[] depJar = readDependencyJar(pin);
                    writeEntry(jos, pin.entryPath, depJar);
                }

                // Add embedded schemas
                for (String schemaId : visibleSchemaIds) {
                    byte[] schemaBytes = readSchemaFile(schemaId);
                    writeEntry(jos, "schemas/" + schemaId, schemaBytes);
                }
            }

            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create conforming JAR", e);
        }
    }

    private static void writeEntry(JarOutputStream jos, String name, byte[] data) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(data);
        jos.closeEntry();
    }

    /**
     * Creates a minimal valid class file bytes.
     */
    public static byte[] createMinimalClassBytes() {
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

    /**
     * Computes SHA-256 hex string.
     */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a symlink (if supported).
     */
    public static boolean createSymlink(Path target, Path link) {
        try {
            Files.createSymbolicLink(link, target.getFileName());
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    // ── Authority JSON generators (for negative tests) ──────────────

    /**
     * Creates authority with duplicate JSON keys.
     */
    public static byte[] createAuthorityWithDuplicateKeys() {
        String json = "{\"schemaVersion\":\"capability-studio.gate-a-protocol-authority.v1\",\"schemaVersion\":\"duplicate\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Creates authority with invalid UTF-8.
     */
    public static byte[] createAuthorityWithInvalidUtf8() {
        byte[] valid = "{\"test\":".getBytes(StandardCharsets.UTF_8);
        byte[] invalid = new byte[valid.length + 2];
        System.arraycopy(valid, 0, invalid, 0, valid.length);
        invalid[valid.length] = (byte) 0x80;
        invalid[valid.length + 1] = (byte) 0xFF;
        return invalid;
    }

    /**
     * Creates authority with trailing comma.
     */
    public static byte[] createAuthorityWithTrailingComma() {
        return "{\"schemaVersion\":\"test\",}".getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Creates authority with non-finite number.
     */
    public static byte[] createAuthorityWithNonFiniteNumber() {
        return "{\"test\":Infinity}".getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Creates authority with NaN.
     */
    public static byte[] createAuthorityWithNaN() {
        return "{\"test\":NaN}".getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Creates authority with invalid surrogate.
     */
    public static byte[] createAuthorityWithInvalidSurrogate() {
        return "{\"test\":\"\\uD800\"}".getBytes(StandardCharsets.UTF_8);
    }
}
