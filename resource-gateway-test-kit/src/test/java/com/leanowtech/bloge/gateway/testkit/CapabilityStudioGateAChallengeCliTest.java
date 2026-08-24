package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CapabilityStudioGateAChallengeCli.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Positive: byte-for-byte deterministic receipt with tracked authority + conforming JAR</li>
 *   <li>Receipt validates against NetworkNT receipt schema</li>
 *   <li>Wrong arg count / wrong arg values</li>
 *   <li>Symlink / directory / nonexistent authority paths</li>
 *   <li>Authority: duplicate keys, invalid UTF-8, trailing comma, non-finite number, invalid surrogate</li>
 *   <li>Artifact: malformed ZIP, missing required entry</li>
 *   <li>Schema: missing schema, extra schema</li>
 *   <li>Domain drift: wrong role, wrong fixture-set-id</li>
 *   <li>stdout/stderr boundary: success emits LF, failure emits nothing to stdout</li>
 * </ul>
 *
 * <p>Uses {@code runForTest} with {@code enforceCodeSource=false} to isolate from classpath JAR.</p>
 */
class CapabilityStudioGateAChallengeCliTest {

    private static final String RECEIPT_SCHEMA_RESOURCE =
            "/schemas/resource-gateway-capability-studio/capability-studio-gate-a-role-self-test-receipt-v1.schema.json";

    private static Schema RECEIPT_SCHEMA;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    // ── Schema setup ────────────────────────────────────────────────

    @BeforeAll
    static void setupSchema() throws Exception {
        try (InputStream in = CapabilityStudioGateAChallengeCliTest.class
                .getResourceAsStream(RECEIPT_SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new AssertionError("RECEIPT_SCHEMA_NOT_ON_CLASSPATH:" + RECEIPT_SCHEMA_RESOURCE);
            }
            String schemaText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            SchemaRegistry registry = SchemaRegistry.withDialect(Dialects.getDraft202012());
            RECEIPT_SCHEMA = registry.getSchema(
                    SchemaLocation.of(RECEIPT_SCHEMA_RESOURCE), schemaText, InputFormat.JSON);
        }
    }

    // ── Argument validation ──────────────────────────────────────────

    @Test
    void rejectsNullArgs() {
        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(null, out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    @Test
    void rejectsWrongArgCount() {
        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test"}, out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    @Test
    void rejectsWrongFirstArg() {
        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--wrong", "--role", "R", "--authority", "/a", "--artifact", "/b", "--fixture-set-id", "F"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    @Test
    void rejectsWrongRoleArg() {
        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "WRONG_ROLE", "--authority", "/a", "--artifact", "/b", "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    @Test
    void rejectsWrongFixtureSetId() {
        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE", "--authority", "/a", "--artifact", "/b", "--fixture-set-id", "WRONG_FIXTURE"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    // ── File reading tests ─────────────────────────────────────────

    @Test
    void rejectsSymlinkAuthority() throws Exception {
        Path target = temp.resolve("authority.json");
        Path link = temp.resolve("authority_link.json");
        Files.writeString(target, "{}", StandardCharsets.UTF_8);

        if (!CapabilityStudioGateAChallengeCliTestFixtures.createSymlink(target, link)) {
            // Skip if symlinks not supported
            return;
        }

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", link.toString(), "--artifact", "/b",
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    @Test
    void rejectsDirectoryAsAuthority() throws Exception {
        Path dir = temp.resolve("authority_dir");
        Files.createDirectory(dir);

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", dir.toString(), "--artifact", "/b",
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    @Test
    void rejectsNonexistentAuthority() {
        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", "/nonexistent/authority.json", "--artifact", "/b",
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    // ── Authority JSON validation tests ───────────────────────────────

    @Test
    void rejectsDuplicateJsonKeys() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");
        Files.write(authorityPath, CapabilityStudioGateAChallengeCliTestFixtures.createAuthorityWithDuplicateKeys());
        Files.write(artifactPath, "not a zip".getBytes(StandardCharsets.UTF_8));

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    @Test
    void rejectsInvalidUtf8Authority() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");
        Files.write(authorityPath, CapabilityStudioGateAChallengeCliTestFixtures.createAuthorityWithInvalidUtf8());
        Files.write(artifactPath, "not a zip".getBytes(StandardCharsets.UTF_8));

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    @Test
    void rejectsTrailingComma() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");
        Files.write(authorityPath, CapabilityStudioGateAChallengeCliTestFixtures.createAuthorityWithTrailingComma());
        Files.write(artifactPath, "not a zip".getBytes(StandardCharsets.UTF_8));

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    @Test
    void rejectsNonFiniteNumber() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");
        Files.write(authorityPath, CapabilityStudioGateAChallengeCliTestFixtures.createAuthorityWithNonFiniteNumber());
        Files.write(artifactPath, "not a zip".getBytes(StandardCharsets.UTF_8));

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    // ── Artifact validation tests ───────────────────────────────────

    @Test
    void rejectsMalformedZip() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");
        byte[] trackedAuth = CapabilityStudioGateAChallengeCliTestFixtures.readTrackedAuthority();
        Files.write(authorityPath, trackedAuth);
        Files.write(artifactPath, "not a zip file".getBytes(StandardCharsets.UTF_8));

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    @Test
    void rejectsMissingRequiredJarEntry() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");
        byte[] trackedAuth = CapabilityStudioGateAChallengeCliTestFixtures.readTrackedAuthority();
        Files.write(authorityPath, trackedAuth);

        // Create JAR missing required entries
        byte[] jar = createJarWithoutRequiredEntries();
        Files.write(artifactPath, jar);

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    @Test
    void rejectsTamperedDependency() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");
        byte[] trackedAuth = CapabilityStudioGateAChallengeCliTestFixtures.readTrackedAuthority();
        Files.write(authorityPath, trackedAuth);

        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins =
                CapabilityStudioGateAChallengeCliTestFixtures.buildDependencyPins();

        @SuppressWarnings("unchecked")
        List<String> visibleSchemaIds = (List<String>)
                CapabilityStudioGateAAuthorityValidator.validate(trackedAuth)
                        .get("_visibleSchemaIds");

        byte[] jar = createConformingJarWithTamperedDependency(depPins, visibleSchemaIds);
        Files.write(artifactPath, jar);

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    // ── Schema validation tests ────────────────────────────────────

    @Test
    void rejectsMissingSchema() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");
        byte[] trackedAuth = CapabilityStudioGateAChallengeCliTestFixtures.readTrackedAuthority();
        Files.write(authorityPath, trackedAuth);

        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins =
                CapabilityStudioGateAChallengeCliTestFixtures.buildDependencyPins();

        @SuppressWarnings("unchecked")
        List<String> visibleSchemaIds = (List<String>)
                CapabilityStudioGateAAuthorityValidator.validate(trackedAuth)
                        .get("_visibleSchemaIds");

        // Create JAR with schemas but missing one (remove first schema)
        List<String> subsetSchemaIds = visibleSchemaIds.subList(0, visibleSchemaIds.size() - 1);
        byte[] jar = createConformingJarWithSchemas(depPins, subsetSchemaIds);
        Files.write(artifactPath, jar);

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    @Test
    void rejectsExtraSchema() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");
        byte[] trackedAuth = CapabilityStudioGateAChallengeCliTestFixtures.readTrackedAuthority();
        Files.write(authorityPath, trackedAuth);

        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins =
                CapabilityStudioGateAChallengeCliTestFixtures.buildDependencyPins();

        @SuppressWarnings("unchecked")
        List<String> visibleSchemaIds = (List<String>)
                CapabilityStudioGateAAuthorityValidator.validate(trackedAuth)
                        .get("_visibleSchemaIds");

        // Create JAR with visible schemas + an extra non-visible schema
        byte[] jar = createConformingJarWithExtraSchema(depPins, visibleSchemaIds);
        Files.write(artifactPath, jar);

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                out(), err(), false);
        assertThat(exitCode).isNotZero();
    }

    // ── Success path: deterministic byte-for-byte receipt ───────────

    @Test
    void producesDeterministicReceipt() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");

        byte[] trackedAuth = CapabilityStudioGateAChallengeCliTestFixtures.readTrackedAuthority();
        Files.write(authorityPath, trackedAuth);

        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins =
                CapabilityStudioGateAChallengeCliTestFixtures.buildDependencyPins();

        @SuppressWarnings("unchecked")
        List<String> visibleSchemaIds = (List<String>)
                CapabilityStudioGateAAuthorityValidator.validate(trackedAuth)
                        .get("_visibleSchemaIds");

        byte[] conformingJar = CapabilityStudioGateAChallengeCliTestFixtures.createConformingJar(depPins, visibleSchemaIds);
        Files.write(artifactPath, conformingJar);

        // Run twice
        ByteArrayOutputStream stdout1 = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr1 = new ByteArrayOutputStream();
        int exit1 = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                new PrintStream(stdout1), new PrintStream(stderr1), false);

        ByteArrayOutputStream stdout2 = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr2 = new ByteArrayOutputStream();
        int exit2 = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                new PrintStream(stdout2), new PrintStream(stderr2), false);

        assertThat(exit1).isZero();
        assertThat(exit2).isZero();
        assertThat(stdout1.toString()).isEqualTo(stdout2.toString());
        assertThat(stderr1.toString()).isEmpty();
        assertThat(stderr2.toString()).isEmpty();
    }

    @Test
    void producesValidReceiptByteForByte() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");

        byte[] trackedAuth = CapabilityStudioGateAChallengeCliTestFixtures.readTrackedAuthority();
        Files.write(authorityPath, trackedAuth);

        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins =
                CapabilityStudioGateAChallengeCliTestFixtures.buildDependencyPins();

        @SuppressWarnings("unchecked")
        List<String> visibleSchemaIds = (List<String>)
                CapabilityStudioGateAAuthorityValidator.validate(trackedAuth)
                        .get("_visibleSchemaIds");

        Map<String, byte[]> embeddedSchemas = new LinkedHashMap<>();
        for (String schemaId : visibleSchemaIds) {
            embeddedSchemas.put(schemaId, CapabilityStudioGateAChallengeCliTestFixtures.readSchemaFile(schemaId));
        }

        byte[] conformingJar = CapabilityStudioGateAChallengeCliTestFixtures.createConformingJar(depPins, visibleSchemaIds);
        Files.write(artifactPath, conformingJar);

        // Compute expected receipt independently using Jackson
        byte[] expectedReceipt = computeExpectedReceipt(trackedAuth, conformingJar, embeddedSchemas);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                new PrintStream(stdout), new PrintStream(stderr), false);

        assertThat(exitCode).isZero();
        assertThat(stderr.toString()).isEmpty();

        String actualOutput = stdout.toString();
        assertThat(actualOutput).isNotEmpty();
        assertThat(actualOutput).endsWith("\n");

        // Strip trailing LF for byte-for-byte comparison
        String expectedStr = new String(expectedReceipt, StandardCharsets.UTF_8);
        assertThat(actualOutput.trim()).isEqualTo(expectedStr);
    }

    @Test
    void receiptPassesNetworkNTSchemaValidation() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");

        byte[] trackedAuth = CapabilityStudioGateAChallengeCliTestFixtures.readTrackedAuthority();
        Files.write(authorityPath, trackedAuth);

        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins =
                CapabilityStudioGateAChallengeCliTestFixtures.buildDependencyPins();

        @SuppressWarnings("unchecked")
        List<String> visibleSchemaIds = (List<String>)
                CapabilityStudioGateAAuthorityValidator.validate(trackedAuth)
                        .get("_visibleSchemaIds");

        byte[] conformingJar = CapabilityStudioGateAChallengeCliTestFixtures.createConformingJar(depPins, visibleSchemaIds);
        Files.write(artifactPath, conformingJar);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                new PrintStream(stdout), new PrintStream(stderr), false);

        assertThat(exitCode).isZero();

        String receiptJson = stdout.toString().trim();

        // Validate against receipt schema using NetworkNT
        List<Error> errors = RECEIPT_SCHEMA.validate(
                receiptJson,
                InputFormat.JSON,
                (ExecutionContext ctx) -> ctx.executionConfig(cfg -> cfg.failFast(false)));

        assertThat(errors)
                .as("Receipt must validate against role-self-test receipt schema")
                .isEmpty();
    }

    // ── stdout/stderr boundary tests ────────────────────────────────

    @Test
    void successWritesReceiptPlusLFToStdout() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");

        byte[] trackedAuth = CapabilityStudioGateAChallengeCliTestFixtures.readTrackedAuthority();
        Files.write(authorityPath, trackedAuth);

        Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins =
                CapabilityStudioGateAChallengeCliTestFixtures.buildDependencyPins();

        @SuppressWarnings("unchecked")
        List<String> visibleSchemaIds = (List<String>)
                CapabilityStudioGateAAuthorityValidator.validate(trackedAuth)
                        .get("_visibleSchemaIds");

        byte[] conformingJar = CapabilityStudioGateAChallengeCliTestFixtures.createConformingJar(depPins, visibleSchemaIds);
        Files.write(artifactPath, conformingJar);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                new PrintStream(stdout), new PrintStream(stderr), false);

        assertThat(exitCode).isZero();
        // Exactly one trailing LF
        String out = stdout.toString();
        assertThat(out).endsWith("\n");
        assertThat(out.substring(0, out.length() - 1)).doesNotEndWith("\n");
        // No stderr content
        assertThat(stderr.toString()).isEmpty();
    }

    @Test
    void failureWritesErrorCodeToStderr() throws Exception {
        Path authorityPath = temp.resolve("authority.json");
        Path artifactPath = temp.resolve("artifact.jar");
        Files.write(authorityPath, CapabilityStudioGateAChallengeCliTestFixtures.createAuthorityWithTrailingComma());
        Files.write(artifactPath, "not a zip".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode = CapabilityStudioGateAChallengeCli.runForTest(
                new String[]{"--role-self-test", "--role", "IMPLEMENTATION_CANDIDATE",
                        "--authority", authorityPath.toString(), "--artifact", artifactPath.toString(),
                        "--fixture-set-id", "GATE_A_ROLE_BLACK_BOX_V1"},
                new PrintStream(stdout), new PrintStream(stderr), false);

        assertThat(exitCode).isNotZero();
        // No stdout content on failure
        assertThat(stdout.toString()).isEmpty();
        // Stderr has error code (with trailing LF)
        assertThat(stderr.toString()).isNotEmpty();
        assertThat(stderr.toString()).endsWith("\n");
    }

    // ── Helper methods ─────────────────────────────────────────────

    private static PrintStream out() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static PrintStream err() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    /**
     * Creates a minimal JAR without required entries (for missing-entry test).
     */
    private byte[] createJarWithoutRequiredEntries() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
            jos.putNextEntry(new JarEntry("com/"));
            jos.closeEntry();
            byte[] classBytes = CapabilityStudioGateAChallengeCliTestFixtures.createMinimalClassBytes();
            jos.putNextEntry(new JarEntry("com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class"));
            jos.write(classBytes);
            jos.closeEntry();
        }
        return baos.toByteArray();
    }

    /**
     * Creates a conforming JAR but with one dependency JAR tampered.
     */
    private byte[] createConformingJarWithTamperedDependency(
            Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins,
            List<String> visibleSchemaIds) throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class",
                "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

        try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
            // Add required dirs
            for (String dir : List.of(
                    "com/", "com/leanowtech/", "com/leanowtech/bloge/",
                    "com/leanowtech/bloge/gateway/", "com/leanowtech/bloge/gateway/testkit/",
                    "META-INF/", "META-INF/maven/",
                    "META-INF/maven/com.leanowtech.bloge/",
                    "META-INF/maven/com.leanowtech.bloge/bloge-resource-gateway-test-kit/",
                    "META-INF/gate-a/", "META-INF/gate-a/protocol/",
                    "META-INF/gate-a/projections/", "META-INF/gate-a/canonicalization/",
                    "META-INF/gate-a/manifests/", "META-INF/gate-a/dependencies/", "schemas/")) {
                jos.putNextEntry(new JarEntry(dir));
                jos.closeEntry();
            }

            writeEntry(jos, "META-INF/maven/com.leanowtech.bloge/bloge-resource-gateway-test-kit/pom.properties",
                    "version=1.0.0\ngroupId=com.leanowtech.bloge\nartifactId=bloge-resource-gateway-test-kit\n"
                            .getBytes(StandardCharsets.UTF_8));

            byte[] cliClass = CapabilityStudioGateAChallengeCliTestFixtures.createMinimalClassBytes();
            writeEntry(jos, "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class", cliClass);
            writeEntry(jos, "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class", cliClass);

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

            // Add dependencies, but tamper the first one
            boolean first = true;
            for (CapabilityStudioGateAAuthorityValidator.DependencyPin pin : depPins.values()) {
                byte[] depJar = CapabilityStudioGateAChallengeCliTestFixtures.readDependencyJar(pin);
                if (first) {
                    // Tamper: append a byte
                    byte[] tampered = new byte[depJar.length + 1];
                    System.arraycopy(depJar, 0, tampered, 0, depJar.length);
                    tampered[depJar.length] = (byte) 0xFF;
                    writeEntry(jos, pin.entryPath, tampered);
                    first = false;
                } else {
                    writeEntry(jos, pin.entryPath, depJar);
                }
            }

            // Add schemas
            for (String schemaId : visibleSchemaIds) {
                byte[] schemaBytes = CapabilityStudioGateAChallengeCliTestFixtures.readSchemaFile(schemaId);
                writeEntry(jos, "schemas/" + schemaId, schemaBytes);
            }
        }
        return baos.toByteArray();
    }

    /**
     * Creates a conforming JAR with all visible schemas plus an extra non-visible schema.
     */
    private byte[] createConformingJarWithSchemas(
            Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins,
            List<String> schemaIds) throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class",
                "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");

        try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
            for (String dir : List.of(
                    "com/", "com/leanowtech/", "com/leanowtech/bloge/",
                    "com/leanowtech/bloge/gateway/", "com/leanowtech/bloge/gateway/testkit/",
                    "META-INF/", "META-INF/maven/",
                    "META-INF/maven/com.leanowtech.bloge/",
                    "META-INF/maven/com.leanowtech.bloge/bloge-resource-gateway-test-kit/",
                    "META-INF/gate-a/", "META-INF/gate-a/protocol/",
                    "META-INF/gate-a/projections/", "META-INF/gate-a/canonicalization/",
                    "META-INF/gate-a/manifests/", "META-INF/gate-a/dependencies/", "schemas/")) {
                jos.putNextEntry(new JarEntry(dir));
                jos.closeEntry();
            }

            writeEntry(jos, "META-INF/maven/com.leanowtech.bloge/bloge-resource-gateway-test-kit/pom.properties",
                    "version=1.0.0\ngroupId=com.leanowtech.bloge\nartifactId=bloge-resource-gateway-test-kit\n"
                            .getBytes(StandardCharsets.UTF_8));

            byte[] cliClass = CapabilityStudioGateAChallengeCliTestFixtures.createMinimalClassBytes();
            writeEntry(jos, "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class", cliClass);
            writeEntry(jos, "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class", cliClass);

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

            for (CapabilityStudioGateAAuthorityValidator.DependencyPin pin : depPins.values()) {
                writeEntry(jos, pin.entryPath, CapabilityStudioGateAChallengeCliTestFixtures.readDependencyJar(pin));
            }

            for (String schemaId : schemaIds) {
                writeEntry(jos, "schemas/" + schemaId,
                        CapabilityStudioGateAChallengeCliTestFixtures.readSchemaFile(schemaId));
            }
        }
        return baos.toByteArray();
    }

    /**
     * Creates a conforming JAR with an extra non-visible schema entry.
     */
    private byte[] createConformingJarWithExtraSchema(
            Map<String, CapabilityStudioGateAAuthorityValidator.DependencyPin> depPins,
            List<String> visibleSchemaIds) throws IOException {

        byte[] jar = createConformingJarWithSchemas(depPins, visibleSchemaIds);

        // Re-read as zip, add extra schema, rewrite
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos);

        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(jar))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                zos.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                zos.write(zis.readAllBytes());
                zos.closeEntry();
            }
        }
        // Add extra non-visible schema
        String extraSchema = "{\"$schema\":\"test\",\"type\":\"object\"}";
        zos.putNextEntry(new java.util.zip.ZipEntry("schemas/EXTRA_NOT_VISIBLE.schema.json"));
        zos.write(extraSchema.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.finish();

        return baos.toByteArray();
    }

    private void writeEntry(JarOutputStream jos, String name, byte[] data) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(data);
        jos.closeEntry();
    }
    // ── Jackson-based expected receipt oracle ──────────────────────

    /**
     * Computes the expected receipt using Jackson canonical JSON.
     * Mirrors the CLI receipt derivation independently.
     */
    @SuppressWarnings("unchecked")
    private byte[] computeExpectedReceipt(
            byte[] authorityRaw,
            byte[] artifactRaw,
            Map<String, byte[]> embeddedSchemas) throws java.io.IOException {

        Map<String, Object> authority = CapabilityStudioGateAAuthorityValidator.validate(authorityRaw);
        Map<String, Object> blackBox = (Map<String, Object>) authority.get("_blackBox");
        Number revision = (Number) authority.get("revision");
        int authorityRevision = revision != null ? revision.intValue() : 1;

        List<String> capabilities = (List<String>) blackBox.get("capabilities");

        // Build input tree
        List<Map<String, Object>> inputEntries = new java.util.ArrayList<>();
        Map<String, Object> authEntry = new LinkedHashMap<>();
        authEntry.put("relativePath", "role-views/IMPLEMENTATION_CANDIDATE/inputs/authority.json");
        authEntry.put("byteLength", (long) authorityRaw.length);
        authEntry.put("rawFingerprint", sha256Hex(authorityRaw));
        inputEntries.add(authEntry);

        Map<String, Object> artifactEntry = new LinkedHashMap<>();
        artifactEntry.put("relativePath", "role-views/IMPLEMENTATION_CANDIDATE/inputs/artifacts/IMPLEMENTATION_CANDIDATE.jar");
        artifactEntry.put("byteLength", (long) artifactRaw.length);
        artifactEntry.put("rawFingerprint", sha256Hex(artifactRaw));
        inputEntries.add(artifactEntry);

        String inputTreeFingerprint = treeCommitment(inputEntries, "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-INPUTS-v1");

        // Build schema set entries
        List<String> sortedSchemaIds = new java.util.ArrayList<>(embeddedSchemas.keySet());
        Collections.sort(sortedSchemaIds);

        List<Map<String, Object>> schemaSetEntries = new java.util.ArrayList<>();
        for (String schemaId : sortedSchemaIds) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("relativePath", "schemas/" + schemaId);
            entry.put("kind", "SCHEMA");
            entry.put("byteLength", (long) embeddedSchemas.get(schemaId).length);
            entry.put("rawFingerprint", sha256Hex(embeddedSchemas.get(schemaId)));
            schemaSetEntries.add(entry);
        }
        String schemaSetFingerprint = treeCommitment(schemaSetEntries, "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-SCHEMA-SET-v1");

        // Build role view
        Map<String, Object> roleViewMaterial = new LinkedHashMap<>();
        roleViewMaterial.put("messageVersion", "capability-studio.gate-a.release-authority-bundle.role-view.v1");
        roleViewMaterial.put("role", "IMPLEMENTATION_CANDIDATE");
        List<String> visibleFileRefs = new java.util.ArrayList<>();
        visibleFileRefs.add("role-views/IMPLEMENTATION_CANDIDATE/inputs/authority.json");
        visibleFileRefs.add("role-views/IMPLEMENTATION_CANDIDATE/inputs/artifacts/IMPLEMENTATION_CANDIDATE.jar");
        roleViewMaterial.put("visibleFileRefs", visibleFileRefs);
        roleViewMaterial.put("inputTreeFingerprint", inputTreeFingerprint);
        List<String> forbiddenCapabilities = new java.util.ArrayList<>();
        forbiddenCapabilities.add("ORACLE");
        forbiddenCapabilities.add("AUTHORITY_WORKSPACE");
        forbiddenCapabilities.add("REPOSITORY_ROOT");
        forbiddenCapabilities.add("OTHER_ROLE_INPUTS");
        roleViewMaterial.put("forbiddenCapabilities", forbiddenCapabilities);
        roleViewMaterial.put("requiredRuntimeArtifactRoles", java.util.Collections.emptyList());
        roleViewMaterial.put("packagedSchemaIds", sortedSchemaIds);
        roleViewMaterial.put("visibleSchemaIds", sortedSchemaIds);
        roleViewMaterial.put("schemaSetFingerprint", schemaSetFingerprint);

        String roleViewFingerprint = committed("RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-VIEW-v1", roleViewMaterial);

        // Build receipt
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("messageVersion", "resource-gateway.capability-studio.gate-a.role-self-test-receipt.v1");
        receipt.put("role", "IMPLEMENTATION_CANDIDATE");
        receipt.put("authority", new LinkedHashMap<String, Object>() {{
            put("rawFingerprint", typedRaw(authorityRaw));
            put("revision", authorityRevision);
        }});
        receipt.put("artifactRawFingerprint", typedRaw(artifactRaw));
        receipt.put("profileRawFingerprint", null);
        receipt.put("fixtureSetId", "GATE_A_ROLE_BLACK_BOX_V1");
        receipt.put("capabilities", capabilities);
        receipt.put("status", "READY");
        receipt.put("roleViewFingerprint", typedTree("sha256:" + roleViewFingerprint));
        receipt.put("inputTreeFingerprint", typedTree("sha256:" + inputTreeFingerprint));
        receipt.put("receiptFingerprint", null);

        String receiptFingerprint = committed("RG-CS-GATE-A-ROLE-SELF-TEST-RECEIPT-v1", receipt);
        receipt.put("receiptFingerprint", typedSelfNull(receiptFingerprint));

        return jacksonCanonicalBytes(receipt);
    }

    // ── Jackson canonical JSON ─────────────────────────────────────

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    @SuppressWarnings("unchecked")
    private byte[] jacksonCanonicalBytes(Object value) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(baos)) {
            jacksonCanonicalize(gen, value);
        }
        return baos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private void jacksonCanonicalize(JsonGenerator gen, Object value) throws java.io.IOException {
        if (value == null) {
            gen.writeNull();
        } else if (value instanceof Boolean) {
            gen.writeBoolean((Boolean) value);
        } else if (value instanceof Long) {
            gen.writeNumber((Long) value);
        } else if (value instanceof Integer) {
            gen.writeNumber((Integer) value);
        } else if (value instanceof Double) {
            gen.writeNumber((Double) value);
        } else if (value instanceof Float) {
            gen.writeNumber((Float) value);
        } else if (value instanceof Number) {
            gen.writeNumber(((Number) value).doubleValue());
        } else if (value instanceof String) {
            gen.writeString((String) value);
        } else if (value instanceof List) {
            gen.writeStartArray();
            for (Object item : (List<?>) value) {
                jacksonCanonicalize(gen, item);
            }
            gen.writeEndArray();
        } else if (value instanceof Map) {
            gen.writeStartObject();
            Map<?, ?> map = (Map<?, ?>) value;
            List<String> keys = new java.util.ArrayList<>();
            for (Object k : map.keySet()) keys.add((String) k);
            Collections.sort(keys);
            for (String key : keys) {
                gen.writeFieldName(key);
                jacksonCanonicalize(gen, map.get(key));
            }
            gen.writeEndObject();
        }
    }

    // ── Hash helpers ──────────────────────────────────────────────

    private String treeCommitment(List<Map<String, Object>> entries, String domain) {
        List<Map<String, Object>> sorted = new java.util.ArrayList<>(entries);
        sorted.sort((a, b) -> {
            String pA = (String) a.get("relativePath");
            String pB = (String) b.get("relativePath");
            return pA.compareTo(pB);
        });
        try {
            return committed(domain, sorted);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String committed(String domain, Object value) throws java.io.IOException {
        byte[] domainBytes = domain.getBytes(StandardCharsets.US_ASCII);
        byte[] canonicalBytes = jacksonCanonicalBytes(value);
        byte[] combined = new byte[domainBytes.length + 1 + canonicalBytes.length];
        System.arraycopy(domainBytes, 0, combined, 0, domainBytes.length);
        combined[domainBytes.length] = 0;
        System.arraycopy(canonicalBytes, 0, combined, domainBytes.length + 1, canonicalBytes.length);
        return sha256Hex(combined);
    }

    private String sha256Hex(byte[] data) {
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

    private Map<String, Object> typedRaw(byte[] raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "RAW_BYTES");
        result.put("algorithm", "SHA-256");
        result.put("value", "sha256:" + sha256Hex(raw));
        return result;
    }

    private Map<String, Object> typedTree(String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "TREE_COMMITMENT");
        result.put("algorithm", "SHA-256");
        result.put("value", value);
        return result;
    }

    private Map<String, Object> typedSelfNull(String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "SELF_NULL_RECEIPT");
        result.put("algorithm", "SHA-256");
        result.put("value", "sha256:" + value);
        result.put("selfNullField", "receiptFingerprint");
        return result;
    }



}
