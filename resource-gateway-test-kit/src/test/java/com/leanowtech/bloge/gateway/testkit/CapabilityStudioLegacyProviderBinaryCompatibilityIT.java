package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioLegacyProviderBinaryCompatibilityIT {
    private static final String PROVIDER_INTERFACE =
            "com.leanowtech.bloge.gateway.testkit."
                    + "CapabilityStudioStageAcceptanceAuthorityProvider";
    private static final String SERVICE_PATH = "META-INF/services/" + PROVIDER_INTERFACE;
    private static final String COMPATIBILITY_PATH =
            "META-INF/bloge/capability-studio-old-provider-compatibility-v1.json";
    private static final String COMPATIBILITY_VERSION =
            "resource-gateway.capability-studio.old-provider-binary-compatibility.v1";
    private static final String EXPECTED_OLD_PROVIDER_JAR_SHA256 =
            "sha256:c5762ffd6c6c7368c2c82c9aebb00c6863c24a172031f36c9c07f3f079c97ed6";
    private static final ObjectMapper STRICT_JSON = new ObjectMapper(
            new JsonFactory().rebuild()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private static final String PROVIDER_STUB = """
            package com.leanowtech.bloge.gateway.testkit;

            public interface CapabilityStudioStageAcceptanceAuthorityProvider {
                CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver
                        evidenceResolver();
                CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                        evidenceIssuerPolicy();
                CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority
                        ownerAuthority();
            }
            """;
    private static final String VERIFIER_STUB = """
            package com.leanowtech.bloge.gateway.testkit;

            public final class CapabilityStudioStageAcceptanceAuthorityVerifier {
                private CapabilityStudioStageAcceptanceAuthorityVerifier() { }
                public interface EvidenceResolver { }
                public interface EvidenceIssuerPolicy { }
                public interface OwnerAuthority { }
            }
            """;
    private static final String LEGACY_PROVIDER_SOURCE = """
            import com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider;
            import com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityVerifier;

            public final class LegacyProvider
                    implements CapabilityStudioStageAcceptanceAuthorityProvider {
                @Override
                public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver
                        evidenceResolver() {
                    return null;
                }

                @Override
                public CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy
                        evidenceIssuerPolicy() {
                    return null;
                }

                @Override
                public CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority
                        ownerAuthority() {
                    return null;
                }
            }
            """;
    private static final String PROBE_SOURCE = """
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.security.MessageDigest;
            import java.util.HexFormat;
            import java.util.List;
            import java.util.Objects;
            import java.util.ServiceLoader;

            import com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider;

            public final class LegacyBinaryProbe {
                private LegacyBinaryProbe() { }

                @SuppressWarnings("deprecation")
                public static void main(String[] args) throws Exception {
                    require(args.length == 2);
                    Path oldJar = Path.of(args[0]).toRealPath();
                    String actualJarSha = sha256(Files.readAllBytes(oldJar));
                    require(actualJarSha.equals(args[1]));

                    List<CapabilityStudioStageAcceptanceAuthorityProvider> providers =
                            ServiceLoader.load(
                                    CapabilityStudioStageAcceptanceAuthorityProvider.class)
                                    .stream().map(ServiceLoader.Provider::get).toList();
                    require(providers.size() == 1);
                    CapabilityStudioStageAcceptanceAuthorityProvider provider =
                            providers.getFirst();
                    require(provider.getClass().getName().equals("LegacyProvider"));
                    Path codeSource = Path.of(provider.getClass().getProtectionDomain()
                            .getCodeSource().getLocation().toURI()).toRealPath();
                    require(codeSource.equals(oldJar));

                    require(provider.evidenceResolver() == null);
                    require(provider.evidenceIssuerPolicy() == null);
                    require(provider.ownerAuthority() == null);
                    require(stableNull(provider.formalTargetBoundAuthorityBinding(),
                            provider.formalTargetBoundAuthorityBinding()));
                    require(stableNull(provider.targetBoundAuthorityBinding(),
                            provider.targetBoundAuthorityBinding()));
                    require(stableNull(provider.authorityBinding(),
                            provider.authorityBinding()));
                    require(stableNull(provider.authorityBindingFingerprint(),
                            provider.authorityBindingFingerprint()));
                    require(stableNull(provider.formalEvidenceAuthorityBinding(),
                            provider.formalEvidenceAuthorityBinding()));
                    require(stableNull(provider.formalEvidenceRecoveryBinding(),
                            provider.formalEvidenceRecoveryBinding()));
                    var failure = CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceJournalResult.unavailable();
                    require(failure.failureKind().orElseThrow()
                            == CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceFailureKind.UNAVAILABLE);
                    require(failure.toString().equals(
                            CapabilityStudioStageAcceptanceAuthorityProvider
                                    .EvidenceJournalResult.unavailable().toString()));

                    System.out.print("COMPATIBLE providerCodeSource=OLD_PROVIDER_JAR"
                            + " legacyAccessors=NULL currentDefaults=NULL"
                            + " failureKind=UNAVAILABLE oldProviderJarSha256="
                            + actualJarSha + "\\n");
                }

                private static boolean stableNull(Object first, Object second) {
                    return first == null && Objects.equals(first, second);
                }

                private static String sha256(byte[] bytes) throws Exception {
                    return "sha256:" + HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(bytes));
                }

                private static void require(boolean condition) {
                    if (!condition) {
                        throw new IllegalStateException("compatibility probe failed");
                    }
                }
            }
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void frozenLegacyProviderLoadsThroughCurrentDefaultsAndFormalClis() throws Exception {
        LegacyArtifact first = buildLegacyArtifact(temporaryDirectory.resolve("first"));
        LegacyArtifact second = buildLegacyArtifact(temporaryDirectory.resolve("second"));
        assertThat(Files.readAllBytes(first.jar())).isEqualTo(Files.readAllBytes(second.jar()));
        assertThat(first.rawJarFingerprint()).isEqualTo(second.rawJarFingerprint())
                .isEqualTo(EXPECTED_OLD_PROVIDER_JAR_SHA256);
        assertStrictArtifact(first);

        Path stage = temporaryDirectory.resolve("stage-result-v2.json");
        Files.write(stage, currentPassResult().toString().getBytes(StandardCharsets.UTF_8));
        String stageFingerprint = sha256(Files.readAllBytes(stage));
        Path publicationParent = Files.createDirectory(
                temporaryDirectory.toRealPath().resolve("publication"));
        Files.setPosixFilePermissions(publicationParent,
                PosixFilePermissions.fromString("rwx------"));
        var publication = CapabilityStudioExecutionLeaseEvidencePublication.provision(
                publicationParent, fingerprint('9'));
        Path transcript = publicationParent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        Map<String, String> publicationBefore = closure(publicationParent);

        Path probe = temporaryDirectory.resolve("LegacyBinaryProbe.java");
        Files.writeString(probe, PROBE_SOURCE, StandardCharsets.UTF_8);
        Path shaded = currentShadedTestKit();
        String childClasspath = shaded + System.getProperty("path.separator") + first.jar();
        ChildResult probeResult = runChild("probe", List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--class-path", childClasspath, probe.toString(), first.jar().toString(),
                first.rawJarFingerprint()), Map.of(), 0);

        String expectedEvidence = "COMPATIBLE providerCodeSource=OLD_PROVIDER_JAR"
                + " legacyAccessors=NULL currentDefaults=NULL"
                + " failureKind=UNAVAILABLE oldProviderJarSha256="
                + EXPECTED_OLD_PROVIDER_JAR_SHA256 + "\n";
        assertThat(probeResult.out()).isEqualTo(expectedEvidence)
                .doesNotContain("AbstractMethodError", "PAYLOAD", first.jar().toString());
        assertThat(probeResult.err()).isEmpty();

        String outer = fingerprint('a');
        String blocked = "NOT_ACCEPTED outcome=BLOCKED reasonCode="
                + "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI."
                + "FORMAL_TARGET_BINDING_UNAVAILABLE\n";
        ChildResult phase = runChild("phase2", List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--class-path", childClasspath,
                CapabilityStudioStageAcceptanceCli.class.getName(), stage.toString()),
                Map.of(CapabilityStudioStageAcceptanceCli
                        .EXPECTED_AUTHORITY_BINDING_ENV, outer), 3);
        assertClosedCliOutput(phase, blocked, stage, first.jar());

        ChildResult evidence = runChild("evidence", List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--class-path", childClasspath,
                CapabilityStudioExecutionLeaseEvidenceCli.class.getName(),
                stage.toString(), transcript.toString()),
                Map.of(
                        CapabilityStudioStageAcceptanceCli.EXPECTED_AUTHORITY_BINDING_ENV,
                        outer,
                        CapabilityStudioExecutionLeaseEvidencePublication
                                .EXPECTED_PUBLICATION_FINGERPRINT_ENV,
                        publication.publicationFingerprint()), 3);
        assertClosedCliOutput(evidence, blocked, stage, first.jar());
        assertThat(transcript).doesNotExist();
        assertThat(sha256(Files.readAllBytes(stage))).isEqualTo(stageFingerprint);
        assertThat(closure(publicationParent)).isEqualTo(publicationBefore);
        assertThat(publicationBefore.keySet()).containsExactlyInAnyOrder(
                CapabilityStudioExecutionLeaseEvidencePublication.OWNER_BOOTSTRAP_FILE,
                CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_LOCK_FILE,
                CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_DECLARATION_FILE);
    }

    private ChildResult runChild(
            String name, List<String> command, Map<String, String> environment,
            int expectedExit) throws Exception {
        Path out = temporaryDirectory.resolve(name + ".out");
        Path err = temporaryDirectory.resolve(name + ".err");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(temporaryDirectory.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().remove("CLASSPATH");
        builder.environment().remove(
                CapabilityStudioStageAcceptanceCli.EXPECTED_AUTHORITY_BINDING_ENV);
        builder.environment().remove(
                CapabilityStudioExecutionLeaseEvidencePublication
                        .EXPECTED_PUBLICATION_FINGERPRINT_ENV);
        builder.environment().putAll(environment);
        Process child = builder.start();
        try {
            assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).as("stdout=%s stderr=%s",
                    Files.readString(out), Files.readString(err)).isEqualTo(expectedExit);
        } finally {
            if (child.isAlive()) {
                child.destroy();
                if (!child.waitFor(1, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    assertThat(child.waitFor(2, TimeUnit.SECONDS)).isTrue();
                }
            }
        }
        return new ChildResult(Files.readString(out), Files.readString(err));
    }

    private static void assertClosedCliOutput(
            ChildResult result, String expected, Path stage, Path provider) {
        assertThat(result.out()).isEqualTo(expected).doesNotContain(
                "AbstractMethodError", "PAYLOAD", "CREDENTIAL",
                stage.toString(), provider.toString());
        assertThat(result.err()).isEmpty();
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode currentPassResult() {
        var result = CapabilityStudioStageAcceptanceCliTest.passResult();
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant started = now.minusSeconds(600);
        java.time.Instant completed = now.minusSeconds(300);
        java.time.Instant decided = now.minusSeconds(60);
        result.put("decidedAt", decided.toString());
        result.with("candidateExecutionBinding")
                .put("executionStartedAt", started.toString())
                .put("evidenceCompletedAt", completed.toString());
        result.with("environmentAttestation")
                .put("issuedAt", started.toString())
                .put("expiresAt", now.plusSeconds(1800).toString());
        result.with("deploymentEgressObservation")
                .put("observationStartedAt", started.toString())
                .put("observationCompletedAt", completed.toString());
        result.path("signoffs").forEach(signoff ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) signoff)
                        .put("signedAt", decided.minusSeconds(60).toString()));
        String closure = CapabilityStudioStageAcceptanceResultV2Verifier
                .closureFingerprint(result);
        result.put("evidenceClosureFingerprint", closure);
        result.path("signoffs").forEach(signoff ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) signoff)
                        .put("evidenceClosureFingerprint", closure));
        return result;
    }

    private LegacyArtifact buildLegacyArtifact(Path root) throws Exception {
        Path sources = Files.createDirectories(root.resolve("sources"));
        Path providerStub = source(sources, PROVIDER_INTERFACE, PROVIDER_STUB);
        Path verifierStub = source(sources,
                "com.leanowtech.bloge.gateway.testkit."
                        + "CapabilityStudioStageAcceptanceAuthorityVerifier",
                VERIFIER_STUB);
        Path providerSource = sources.resolve("LegacyProvider.java");
        Files.writeString(providerSource, LEGACY_PROVIDER_SOURCE, StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(root.resolve("classes"));
        ByteArrayOutputStream compilerError = new ByteArrayOutputStream();
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, compilerError,
                "--release", "17", "-g:none", "-encoding", "UTF-8",
                "-d", classes.toString(), providerStub.toString(), verifierStub.toString(),
                providerSource.toString());
        assertThat(compiled).as(compilerError.toString(StandardCharsets.UTF_8)).isZero();
        byte[] providerClass = Files.readAllBytes(classes.resolve("LegacyProvider.class"));
        byte[] service = "LegacyProvider\n".getBytes(StandardCharsets.UTF_8);
        String stubFingerprint = sha256((PROVIDER_STUB + "\n--VERIFIER--\n" + VERIFIER_STUB)
                .getBytes(StandardCharsets.UTF_8));
        String providerSourceFingerprint = sha256(
                LEGACY_PROVIDER_SOURCE.getBytes(StandardCharsets.UTF_8));
        String classFingerprint = sha256(providerClass);
        String serviceFingerprint = sha256(service);
        String jarMaterialFingerprint = sha256(("class=" + classFingerprint
                + "\nservice=" + serviceFingerprint + "\n")
                .getBytes(StandardCharsets.UTF_8));
        byte[] compatibility = ("{\"messageVersion\":\"" + COMPATIBILITY_VERSION
                + "\",\"oldApiStubSha256\":\"" + stubFingerprint
                + "\",\"providerSourceSha256\":\"" + providerSourceFingerprint
                + "\",\"providerClassSha256\":\"" + classFingerprint
                + "\",\"serviceDescriptorSha256\":\"" + serviceFingerprint
                + "\",\"jarMaterialSha256\":\"" + jarMaterialFingerprint
                + "\"}\n").getBytes(StandardCharsets.UTF_8);
        Path jar = root.resolve("old-provider.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            writeStored(output, "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\r\nCreated-By: BLOGE compatibility test\r\n\r\n"
                            .getBytes(StandardCharsets.UTF_8));
            writeStored(output, COMPATIBILITY_PATH, compatibility);
            writeStored(output, SERVICE_PATH, service);
            writeStored(output, "LegacyProvider.class", providerClass);
        }
        return new LegacyArtifact(jar, sha256(Files.readAllBytes(jar)),
                stubFingerprint, providerSourceFingerprint, classFingerprint,
                serviceFingerprint, jarMaterialFingerprint, compatibility);
    }

    private static void assertStrictArtifact(LegacyArtifact artifact) throws Exception {
        try (JarFile jar = new JarFile(artifact.jar().toFile())) {
            List<String> entries = new ArrayList<>();
            jar.entries().asIterator().forEachRemaining(entry -> entries.add(entry.getName()));
            assertThat(entries).containsExactly(
                    "META-INF/MANIFEST.MF", COMPATIBILITY_PATH,
                    SERVICE_PATH, "LegacyProvider.class");
            assertThat(jar.getJarEntry(PROVIDER_INTERFACE.replace('.', '/') + ".class"))
                    .isNull();
            byte[] manifestBytes = jar.getInputStream(
                    jar.getJarEntry(COMPATIBILITY_PATH)).readAllBytes();
            assertThat(manifestBytes).isEqualTo(artifact.compatibilityManifest());
            var manifest = STRICT_JSON.readTree(manifestBytes);
            assertThat(manifest.fieldNames()).toIterable().containsExactly(
                    "messageVersion", "oldApiStubSha256", "providerSourceSha256",
                    "providerClassSha256", "serviceDescriptorSha256",
                    "jarMaterialSha256");
            assertThat(manifest.path("messageVersion").asText())
                    .isEqualTo(COMPATIBILITY_VERSION);
            assertThat(manifest.path("oldApiStubSha256").asText())
                    .isEqualTo(artifact.oldStubFingerprint());
            assertThat(manifest.path("providerSourceSha256").asText())
                    .isEqualTo(artifact.providerSourceFingerprint());
            assertThat(manifest.path("providerClassSha256").asText())
                    .isEqualTo(artifact.providerClassFingerprint());
            assertThat(manifest.path("serviceDescriptorSha256").asText())
                    .isEqualTo(artifact.serviceFingerprint());
            assertThat(manifest.path("jarMaterialSha256").asText())
                    .isEqualTo(artifact.jarMaterialFingerprint());
        }
    }

    private static Path source(Path sourceRoot, String className, String value)
            throws IOException {
        Path path = sourceRoot.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
        return path;
    }

    private static void writeStored(JarOutputStream output, String name, byte[] bytes)
            throws IOException {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    private static Path currentShadedTestKit() {
        Path jar = Path.of(System.getProperty("user.dir"), "target",
                "bloge-resource-gateway-test-kit-1.0.0-cli.jar")
                .toAbsolutePath().normalize();
        assertThat(jar).isRegularFile();
        return jar;
    }

    private static Map<String, String> closure(Path parent) throws Exception {
        Map<String, String> closure = new TreeMap<>();
        try (var children = Files.list(parent)) {
            for (Path child : children.toList()) {
                BasicFileAttributes attributes = Files.readAttributes(child,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                long links = ((Number) Files.getAttribute(child, "unix:nlink",
                        LinkOption.NOFOLLOW_LINKS)).longValue();
                long uid = ((Number) Files.getAttribute(child, "unix:uid",
                        LinkOption.NOFOLLOW_LINKS)).longValue();
                int mode = ((Number) Files.getAttribute(child, "unix:mode",
                        LinkOption.NOFOLLOW_LINKS)).intValue() & 0777;
                String raw = attributes.isRegularFile()
                        ? sha256(Files.readAllBytes(child)) : "DIRECTORY";
                closure.put(child.getFileName().toString(), attributes.fileKey() + "|"
                        + attributes.isRegularFile() + "|" + links + "|" + uid + "|"
                        + mode + "|" + attributes.size() + "|"
                        + attributes.lastModifiedTime() + "|" + raw);
            }
        }
        return Map.copyOf(closure);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record ChildResult(String out, String err) {
    }

    private record LegacyArtifact(
            Path jar,
            String rawJarFingerprint,
            String oldStubFingerprint,
            String providerSourceFingerprint,
            String providerClassFingerprint,
            String serviceFingerprint,
            String jarMaterialFingerprint,
            byte[] compatibilityManifest) {
        private LegacyArtifact {
            compatibilityManifest = compatibilityManifest.clone();
        }

        @Override
        public byte[] compatibilityManifest() {
            return compatibilityManifest.clone();
        }
    }
}
