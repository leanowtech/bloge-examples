package com.leanowtech.bloge.gateway.testkit.mounted;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseEvidenceCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrashInstrumentationPackagingTest {
    private static final String BUILD_IDENTITY =
            "META-INF/bloge/"
                    + "capability-studio-execution-lease-evidence-cli-build-identity-v1.json";
    private static final List<String> POINTS = List.of(
            "PRE_OWNER", "OWNER_SOURCE_FORCED", "WRAPPER_DURABLE", "OWNER_DURABLE",
            "BEFORE_SOURCE_FORCED", "BEFORE_DURABLE", "PRE_LEASE",
            "STATE_BEFORE_CHECKPOINT",
            "CHECKPOINT_DURABLE_BEFORE_TRANSITION_EVIDENCE",
            "COMMITTED_SOURCE_FORCED", "COMMITTED_DURABLE",
            "MANIFEST_DURABLE_BEFORE_FINAL_COMMIT", "FINAL_COMMIT_SOURCE_FORCED",
            "FINAL_COMMIT_INSTALLED", "FINAL_COMMIT_DURABLE", "FINAL_INSTALLED",
            "FINAL_BEFORE_STDOUT");
    private static final List<String> SEMANTIC_WINDOWS = List.of(
            "PRE_OWNER", "OWNER_PUBLICATION", "WRAPPER_PUBLICATION",
            "BEFORE_PUBLICATION", "PRE_LEASE", "STATE_TRANSITION_PRE_CHECKPOINT",
            "CHECKPOINT_POST_COMMIT", "COMMITTED_TRANSCRIPT_PUBLICATION",
            "MANIFEST_PUBLICATION", "FINAL_COMMIT_SOURCE", "FINAL_COMMIT_INSTALL",
            "FINAL_COMMIT_DURABILITY", "FINAL_TRANSCRIPT_INSTALL", "PRE_STDOUT");
    private static final Map<String, String> SEMANTIC_WINDOW_BY_POINT = Map.ofEntries(
            Map.entry("PRE_OWNER", "PRE_OWNER"),
            Map.entry("OWNER_SOURCE_FORCED", "OWNER_PUBLICATION"),
            Map.entry("WRAPPER_DURABLE", "WRAPPER_PUBLICATION"),
            Map.entry("OWNER_DURABLE", "OWNER_PUBLICATION"),
            Map.entry("BEFORE_SOURCE_FORCED", "BEFORE_PUBLICATION"),
            Map.entry("BEFORE_DURABLE", "BEFORE_PUBLICATION"),
            Map.entry("PRE_LEASE", "PRE_LEASE"),
            Map.entry("STATE_BEFORE_CHECKPOINT", "STATE_TRANSITION_PRE_CHECKPOINT"),
            Map.entry("CHECKPOINT_DURABLE_BEFORE_TRANSITION_EVIDENCE",
                    "CHECKPOINT_POST_COMMIT"),
            Map.entry("COMMITTED_SOURCE_FORCED", "COMMITTED_TRANSCRIPT_PUBLICATION"),
            Map.entry("COMMITTED_DURABLE", "COMMITTED_TRANSCRIPT_PUBLICATION"),
            Map.entry("MANIFEST_DURABLE_BEFORE_FINAL_COMMIT", "MANIFEST_PUBLICATION"),
            Map.entry("FINAL_COMMIT_SOURCE_FORCED", "FINAL_COMMIT_SOURCE"),
            Map.entry("FINAL_COMMIT_INSTALLED", "FINAL_COMMIT_INSTALL"),
            Map.entry("FINAL_COMMIT_DURABLE", "FINAL_COMMIT_DURABILITY"),
            Map.entry("FINAL_INSTALLED", "FINAL_TRANSCRIPT_INSTALL"),
            Map.entry("FINAL_BEFORE_STDOUT", "PRE_STDOUT"));
    private static final List<String> FORBIDDEN = List.of(
            "CrashCheckpoint", "CrashFault", "runPackagedForTesting",
            "PublicationFault", "PublicationPoint", "REPLAY_STEP",
            "HOLD_OWNER_DURABLE", "selectOwnerDurableHold",
            "OBSERVE_PUBLICATION_FILE_LOCK_MISS", "publicationFileLockMiss",
            "MountedProductionEvidenceInvocationWorker");

    @Test
    void ordinarySurefireUsesProductionClassesAndOnlyHarnessContainsInstrumentation()
            throws Exception {
        Path module = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path testKitTarget = module.resolve("target/resolved-test-kit");
        Path ordinary = testKitTarget.resolve(
                "bloge-resource-gateway-test-kit-1.0.0.jar");
        Path shaded = testKitTarget.resolve(
                "bloge-resource-gateway-test-kit-1.0.0-cli.jar");
        Path runtime = module.resolve("target/"
                + "bloge-capability-studio-mounted-authority-provider-1.0.0-"
                + "runtime-under-test.jar");
        Path harness = module.resolve("target/"
                + "bloge-capability-studio-mounted-authority-provider-1.0.0-"
                + "child-harness.jar");

        assertThat(List.of(ordinary, shaded, runtime, harness)).allSatisfy(path ->
                assertThat(path).isRegularFile());
        assertThat(Files.readString(module.resolve("pom.xml")))
                .doesNotContain("${project.basedir}/../../target/classes",
                        "../../target/bloge-resource-gateway-test-kit");
        assertThat(List.of(ordinary, shaded)).allSatisfy(path ->
                assertThat(path.toAbsolutePath().normalize()).startsWith(testKitTarget));
        assertProductionCodeSource(CapabilityStudioExecutionLeaseEvidenceCli.class);
        assertProductionCodeSource(FilesystemDeploymentAdmissionAuthority.class);
        assertJarClean(ordinary);
        assertJarClean(shaded);
        assertJarClean(runtime);
        BuildIdentity buildIdentity = verifyBuildIdentity(ordinary, shaded, module);

        try (JarFile jar = new JarFile(harness.toFile())) {
            assertThat(jar.getJarEntry("com/leanowtech/bloge/gateway/testkit/"
                    + "CapabilityStudioExecutionLeaseEvidenceCli.class")).isNotNull();
            assertThat(jar.getJarEntry("com/leanowtech/bloge/gateway/testkit/mounted/"
                    + "FilesystemDeploymentAdmissionAuthority.class")).isNotNull();
            var checkpoint = jar.getJarEntry(
                    "com/leanowtech/bloge/gateway/testkit/instrumentation/"
                            + "CrashCheckpoint.class");
            assertThat(checkpoint).isNotNull();
            assertThat(new String(jar.getInputStream(checkpoint).readAllBytes(),
                    StandardCharsets.ISO_8859_1))
                    .contains("HOLD_OWNER_DURABLE", "selectOwnerDurableHold",
                            "OBSERVE_PUBLICATION_FILE_LOCK_MISS",
                            "publicationFileLockMiss");
            assertThat(jar.getJarEntry("com/leanowtech/bloge/gateway/testkit/mounted/"
                    + "MountedProductionEvidenceInvocationWorker.class")).isNotNull();
            var instrumentedPublication = jar.getJarEntry(
                    "com/leanowtech/bloge/gateway/testkit/"
                            + "CapabilityStudioExecutionLeaseEvidenceCli$"
                            + "TranscriptPublication.class");
            assertThat(instrumentedPublication).isNotNull();
            String constants = new String(
                    jar.getInputStream(instrumentedPublication).readAllBytes(),
                    StandardCharsets.ISO_8859_1);
            assertThat(constants)
                    .contains("MANIFEST_DURABLE_BEFORE_FINAL_COMMIT", "CrashCheckpoint");
            var instrumentedCli = jar.getJarEntry(
                    "com/leanowtech/bloge/gateway/testkit/"
                            + "CapabilityStudioExecutionLeaseEvidenceCli.class");
            assertThat(instrumentedCli).isNotNull();
            assertThat(new String(jar.getInputStream(instrumentedCli).readAllBytes(),
                    StandardCharsets.ISO_8859_1)).contains("publicationFileLockMiss");
            var wire = StrictCrashInstrumentationManifest.read(jar);
            assertThat(fieldNames(wire)).containsExactly(
                    "messageVersion", "pointCount", "semanticWindowCount",
                    "semanticWindows", "sources", "observationHooks", "points", "classes");
            assertThat(wire.path("messageVersion").asText())
                    .isEqualTo("bloge.test-only.crash-instrumentation.v3");
            assertThat(wire.path("pointCount").intValue()).isEqualTo(17);
            assertThat(wire.path("semanticWindowCount").intValue()).isEqualTo(14);
            assertThat(wire.path("semanticWindows")).extracting(value -> value.asText())
                    .containsExactlyElementsOf(SEMANTIC_WINDOWS);
            assertThat(wire.path("observationHooks")).hasSize(1);
            var lockMissHook = wire.path("observationHooks").get(0);
            assertThat(fieldNames(lockMissHook)).containsExactly(
                    "hook", "className", "anchorSummary");
            assertThat(lockMissHook.path("hook").asText())
                    .isEqualTo("PUBLICATION_FILE_LOCK_MISS");
            assertThat(lockMissHook.path("className").asText()).isEqualTo(
                    "com.leanowtech.bloge.gateway.testkit."
                            + "CapabilityStudioExecutionLeaseEvidenceCli");
            assertThat(lockMissHook.path("anchorSummary").asText()).isNotBlank();
            assertThat(wire.path("points")).hasSize(17);
            List<String> pointNames = new java.util.ArrayList<>();
            wire.path("points").forEach(value ->
                    pointNames.add(value.path("point").asText()));
            assertThat(pointNames).containsExactlyElementsOf(POINTS);
            java.util.Set<String> mappedWindows = new java.util.LinkedHashSet<>();
            for (var point : wire.path("points")) {
                assertThat(fieldNames(point)).containsExactly(
                        "point", "semanticWindowId", "className", "anchorSummary");
                String className = point.path("className").asText();
                String pointName = point.path("point").asText();
                assertThat(point.path("semanticWindowId").asText())
                        .isEqualTo(SEMANTIC_WINDOW_BY_POINT.get(pointName));
                mappedWindows.add(point.path("semanticWindowId").asText());
                assertThat(point.path("anchorSummary").asText()).isNotBlank();
                var entry = jar.getJarEntry(className.replace('.', '/') + ".class");
                assertThat(entry).as(className).isNotNull();
                assertThat(new String(jar.getInputStream(entry).readAllBytes(),
                        StandardCharsets.ISO_8859_1)).contains(pointName);
            }
            assertThat(mappedWindows).containsExactlyInAnyOrderElementsOf(SEMANTIC_WINDOWS);
            assertThat(wire.path("sources")).hasSize(2);
            boolean matchedEvidenceCliSource = false;
            for (var source : wire.path("sources")) {
                assertThat(fieldNames(source)).containsExactly(
                        "sourcePath", "sourceSha256", "instrumentedSha256", "points");
                String relative = source.path("sourcePath").asText();
                Path production = relative.contains("/mounted/")
                        ? module.resolve(relative)
                        : module.resolve("../..").normalize().resolve(relative);
                Path generated = module.resolve("target/generated-crash-sources")
                        .resolve(relative);
                assertThat(source.path("sourceSha256").asText())
                        .isEqualTo(sha256(Files.readAllBytes(production)));
                assertThat(source.path("instrumentedSha256").asText())
                        .isEqualTo(sha256(Files.readAllBytes(generated)));
                if (relative.equals(buildIdentity.sourcePath())) {
                    matchedEvidenceCliSource = true;
                    assertThat(source.path("sourceSha256").asText())
                            .isEqualTo(buildIdentity.sourceFingerprint());
                }
            }
            assertThat(matchedEvidenceCliSource).isTrue();
            assertThat(wire.path("classes").isEmpty()).isFalse();
            for (var compiled : wire.path("classes")) {
                assertThat(fieldNames(compiled)).containsExactly(
                        "className", "classSha256");
                String className = compiled.path("className").asText();
                var entry = jar.getJarEntry(className.replace('.', '/') + ".class");
                assertThat(entry).as(className).isNotNull();
                assertThat(compiled.path("classSha256").asText())
                        .isEqualTo(sha256(jar.getInputStream(entry).readAllBytes()));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("duplicateManifestFields")
    void strictManifestRejectsDuplicateFields(
            String ignored, String needle, String replacement) throws Exception {
        Path module = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path harness = module.resolve("target/"
                + "bloge-capability-studio-mounted-authority-provider-1.0.0-"
                + "child-harness.jar");
        byte[] valid;
        try (JarFile jar = new JarFile(harness.toFile())) {
            valid = StrictCrashInstrumentationManifest.readRaw(jar);
        }
        String canonical = new String(valid, StandardCharsets.UTF_8);
        String attack = canonical.replace(needle, replacement);
        assertThat(attack).isNotEqualTo(canonical);
        assertThatThrownBy(() -> StrictCrashInstrumentationManifest.parse(
                attack.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonCanonicalManifestBytes")
    void strictManifestRejectsMalformedOrNonCanonicalBytes(
            String attackName) throws Exception {
        Path module = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path harness = module.resolve("target/"
                + "bloge-capability-studio-mounted-authority-provider-1.0.0-"
                + "child-harness.jar");
        byte[] valid;
        try (JarFile jar = new JarFile(harness.toFile())) {
            valid = StrictCrashInstrumentationManifest.readRaw(jar);
        }
        byte[] attack = nonCanonicalAttack(attackName, valid);
        assertThatThrownBy(() -> StrictCrashInstrumentationManifest.parse(attack))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> duplicateManifestFields() {
        return Stream.of(
                Arguments.of("top-level", "\"pointCount\":17,",
                        "\"pointCount\":17,\"pointCount\":17,"),
                Arguments.of("point", "{\"point\":\"PRE_OWNER\",",
                        "{\"point\":\"PRE_OWNER\",\"point\":\"PRE_OWNER\","),
                Arguments.of("source", "\"sources\":[{",
                        "\"sources\":[{\"sourcePath\":\"duplicate\","),
                Arguments.of("class", "\"classes\":[{",
                        "\"classes\":[{\"className\":\"duplicate\","),
                Arguments.of("observation-hook", "\"observationHooks\":[{",
                        "\"observationHooks\":[{\"hook\":\"duplicate\","));
    }

    private static Stream<Arguments> nonCanonicalManifestBytes() {
        return Stream.of("malformed-utf8", "second-document", "oversized", "missing-lf",
                "extra-lf", "field-order").map(Arguments::of);
    }

    private static byte[] nonCanonicalAttack(String name, byte[] valid) {
        return switch (name) {
            case "malformed-utf8" -> {
                byte[] attack = valid.clone();
                attack[0] = (byte) 0xc3;
                yield attack;
            }
            case "second-document" -> concat(valid, "{}\n".getBytes(StandardCharsets.UTF_8));
            case "oversized" -> new byte[
                    StrictCrashInstrumentationManifest.MAXIMUM_BYTES + 1];
            case "missing-lf" -> Arrays.copyOf(valid, valid.length - 1);
            case "extra-lf" -> concat(valid, new byte[]{'\n'});
            case "field-order" -> {
                String document = new String(valid, StandardCharsets.UTF_8);
                String prefix = "{\"messageVersion\":";
                int versionEnd = document.indexOf(",\"pointCount\":17");
                assertThat(document).startsWith(prefix);
                assertThat(versionEnd).isPositive();
                String versionField = document.substring(1, versionEnd);
                yield ("{\"pointCount\":17," + versionField
                        + document.substring(versionEnd + ",\"pointCount\":17".length()))
                        .getBytes(StandardCharsets.UTF_8);
            }
            default -> throw new IllegalArgumentException("unknown attack");
        };
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] joined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return joined;
    }

    private static BuildIdentity verifyBuildIdentity(
            Path ordinary, Path shaded, Path module) throws Exception {
        try (JarFile ordinaryJar = new JarFile(ordinary.toFile());
             JarFile shadedJar = new JarFile(shaded.toFile())) {
            var ordinaryIdentityEntry = ordinaryJar.getJarEntry(BUILD_IDENTITY);
            var shadedIdentityEntry = shadedJar.getJarEntry(BUILD_IDENTITY);
            assertThat(ordinaryIdentityEntry).isNotNull();
            assertThat(shadedIdentityEntry).isNotNull();
            byte[] ordinaryIdentity = ordinaryJar.getInputStream(
                    ordinaryIdentityEntry).readAllBytes();
            byte[] shadedIdentity = shadedJar.getInputStream(
                    shadedIdentityEntry).readAllBytes();
            assertThat(shadedIdentity).isEqualTo(ordinaryIdentity);

            var identity = MountedProviderTestFixtures.JSON.readTree(ordinaryIdentity);
            assertThat(fieldNames(identity)).containsExactly(
                    "messageVersion", "sourcePath", "sourceFingerprint", "className",
                    "classFingerprint", "identityFingerprint");
            assertThat(identity.path("messageVersion").asText()).isEqualTo(
                    "bloge.capability-studio.execution-lease-evidence-cli-build-identity.v1");
            String sourcePath = identity.path("sourcePath").asText();
            String sourceFingerprint = identity.path("sourceFingerprint").asText();
            String className = identity.path("className").asText();
            String classFingerprint = identity.path("classFingerprint").asText();
            String identityFingerprint = identity.path("identityFingerprint").asText();
            assertThat(sourcePath).isEqualTo(
                    "src/main/java/com/leanowtech/bloge/gateway/testkit/"
                            + "CapabilityStudioExecutionLeaseEvidenceCli.java");
            assertThat(className).isEqualTo(
                    "com.leanowtech.bloge.gateway.testkit."
                            + "CapabilityStudioExecutionLeaseEvidenceCli");
            assertThat(sourceFingerprint).isEqualTo(sha256(Files.readAllBytes(
                    module.resolve("../..").normalize().resolve(sourcePath))));

            String classEntryName = className.replace('.', '/') + ".class";
            var ordinaryClass = ordinaryJar.getJarEntry(classEntryName);
            var shadedClass = shadedJar.getJarEntry(classEntryName);
            assertThat(ordinaryClass).isNotNull();
            assertThat(shadedClass).isNotNull();
            assertThat(sha256(ordinaryJar.getInputStream(ordinaryClass).readAllBytes()))
                    .isEqualTo(classFingerprint);
            assertThat(sha256(shadedJar.getInputStream(shadedClass).readAllBytes()))
                    .isEqualTo(classFingerprint);

            String canonical = buildIdentityJson(identity, null);
            assertThat(identityFingerprint).isEqualTo(
                    sha256(canonical.getBytes(StandardCharsets.UTF_8)));
            assertThat(new String(ordinaryIdentity, StandardCharsets.UTF_8)).isEqualTo(
                    buildIdentityJson(identity, identityFingerprint) + "\n");
            return new BuildIdentity(sourcePath, sourceFingerprint,
                    classFingerprint, identityFingerprint);
        }
    }

    private static String buildIdentityJson(
            com.fasterxml.jackson.databind.JsonNode identity, String fingerprint) {
        return "{\"messageVersion\":\"" + identity.path("messageVersion").asText() + "\""
                + ",\"sourcePath\":\"" + identity.path("sourcePath").asText() + "\""
                + ",\"sourceFingerprint\":\""
                + identity.path("sourceFingerprint").asText() + "\""
                + ",\"className\":\"" + identity.path("className").asText() + "\""
                + ",\"classFingerprint\":\""
                + identity.path("classFingerprint").asText() + "\""
                + ",\"identityFingerprint\":"
                + (fingerprint == null ? "null" : "\"" + fingerprint + "\"") + "}";
    }

    private static List<String> fieldNames(com.fasterxml.jackson.databind.JsonNode value) {
        List<String> names = new java.util.ArrayList<>();
        value.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }

    private static void assertProductionCodeSource(Class<?> type)
            throws URISyntaxException {
        Path source = Path.of(type.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toAbsolutePath().normalize();
        assertThat(source.toString())
                .doesNotContain("child-harness", "crash-instrumented-classes");
    }

    private static void assertJarClean(Path artifact) throws IOException {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (var entries = jar.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                String constants = new String(jar.getInputStream(entry).readAllBytes(),
                        StandardCharsets.ISO_8859_1);
                assertThat(constants).as(artifact + "!" + entry.getName())
                        .doesNotContain(FORBIDDEN.toArray(String[]::new));
                assertThat(constants.contains("java/lang/Runtime")
                        && constants.contains("halt")).as(
                        artifact + "!" + entry.getName()).isFalse();
            }
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record BuildIdentity(
            String sourcePath,
            String sourceFingerprint,
            String classFingerprint,
            String identityFingerprint) {
    }
}
