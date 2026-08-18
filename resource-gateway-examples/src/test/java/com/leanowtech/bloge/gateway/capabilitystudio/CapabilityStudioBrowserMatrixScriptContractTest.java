package com.leanowtech.bloge.gateway.capabilitystudio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Focused executable contract checks for the formal browser matrix wrapper. */
class CapabilityStudioBrowserMatrixScriptContractTest {
    private static final Path SCRIPT = Path.of("..", "scripts",
            "run-capability-studio-browser-matrix.sh").toAbsolutePath().normalize();
    private static final String SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    private Path temporaryDirectory;

    @Test
    void scriptRemainsValidBash() throws Exception {
        Process process = new ProcessBuilder("bash", "-n", SCRIPT.toString())
                .redirectErrorStream(true)
                .start();

        assertThat(process.waitFor()).isZero();
        assertThat(process.getInputStream().readAllBytes()).isEmpty();
    }

    @Test
    void helpDocumentsFormalPreflightAndEvidenceManifest() throws Exception {
        Process process = new ProcessBuilder("bash", SCRIPT.toString(), "--help")
                .redirectErrorStream(true)
                .start();

        assertThat(process.waitFor()).isZero();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(output).contains(
                "4194304 KiB",
                "20000 free inodes",
                "run-scoped evidence",
                "--output PATH",
                "--anomaly-output PATH");
    }

    @Test
    void invalidThresholdFailsBeforeMaven() throws Exception {
        Path root = fakeRepository();
        Path marker = root.resolve("maven-started");

        ProcessResult result = run(root, Map.of(
                "CAPABILITY_STUDIO_MIN_FREE_KIB", "not-a-number",
                "MAVEN_MARKER", marker.toString()), "--no-build");

        assertThat(result.exitCode()).isEqualTo(20);
        assertThat(result.output()).contains(
                "ERROR code=RG.CAPABILITY_STUDIO.BROWSER_PREFLIGHT_INVALID_THRESHOLD");
        assertThat(Files.exists(marker)).isFalse();
    }

    @Test
    void insufficientDiskFailsBeforeMaven() throws Exception {
        Path root = fakeRepository();
        Path marker = root.resolve("maven-started");

        ProcessResult result = run(root, Map.of(
                "CAPABILITY_STUDIO_MIN_FREE_KIB", "999999999999999999",
                "MAVEN_MARKER", marker.toString()), "--no-build");

        assertThat(result.exitCode()).isEqualTo(21);
        assertThat(result.output()).contains(
                "ERROR code=RG.CAPABILITY_STUDIO.BROWSER_PREFLIGHT_INSUFFICIENT_SPACE");
        assertThat(Files.exists(marker)).isFalse();
    }

    @Test
    void formalThresholdBelowContractMinimumFailsBeforeMaven() throws Exception {
        Path root = fakeRepository();
        Path marker = root.resolve("maven-started");

        ProcessResult result = run(root, Map.of(
                "CAPABILITY_STUDIO_MIN_FREE_KIB", "4194303",
                "CAPABILITY_STUDIO_MIN_FREE_INODES", "20000",
                "MAVEN_MARKER", marker.toString()), "--no-build");

        assertThat(result.exitCode()).isEqualTo(20);
        assertThat(result.output()).contains(
                "ERROR code=RG.CAPABILITY_STUDIO.BROWSER_PREFLIGHT_FORMAL_THRESHOLD_BELOW_MINIMUM");
        assertThat(Files.exists(marker)).isFalse();
    }

    @Test
    void explicitResultFilesMustShareOneParentBeforeStartup() throws Exception {
        Path root = fakeRepository();
        Path marker = root.resolve("maven-started");
        Path normal = root.resolve("normal/capability-studio-browser-matrix-result-v1.json");
        Path anomaly = root.resolve("anomaly/capability-studio-browser-anomaly-matrix-result-v1.json");

        ProcessResult result = run(root, Map.of("MAVEN_MARKER", marker.toString()),
                "--no-build", "--output", normal.toString(), "--anomaly-output", anomaly.toString());

        assertThat(result.exitCode()).isEqualTo(26);
        assertThat(result.output()).contains(
                "ERROR code=RG.CAPABILITY_STUDIO.BROWSER_OUTPUT_PARENT_MISMATCH");
        assertThat(Files.exists(marker)).isFalse();
    }

    @Test
    void explicitArtifactRootMustBeFreshBeforeMaven() throws Exception {
        Path root = fakeRepository();
        Path marker = root.resolve("maven-started");
        Path artifactRoot = root.resolve("explicit-artifacts");
        Path normal = artifactRoot.resolve("capability-studio-browser-matrix-result-v1.json");
        Path anomaly = artifactRoot.resolve(
                "capability-studio-browser-anomaly-matrix-result-v1.json");
        Path oldEvidence = artifactRoot.resolve("browser-matrix-evidence/old-screenshot.png");
        Files.createDirectories(oldEvidence.getParent());
        Files.writeString(oldEvidence, "old evidence", StandardCharsets.UTF_8);

        ProcessResult result = run(root, Map.of(
                        "CAPABILITY_STUDIO_MIN_FREE_KIB", "4194304",
                        "CAPABILITY_STUDIO_MIN_FREE_INODES", "20000",
                        "MAVEN_MARKER", marker.toString()),
                "--no-build", "--output", normal.toString(),
                "--anomaly-output", anomaly.toString());

        assertThat(result.exitCode()).isEqualTo(23);
        assertThat(result.output()).contains(
                "ERROR code=RG.CAPABILITY_STUDIO.BROWSER_PREFLIGHT_ARTIFACT_ROOT_NOT_FRESH");
        assertThat(result.output()).doesNotContain(oldEvidence.toString(), "old evidence");
        assertThat(Files.exists(marker)).isFalse();
    }

    @Test
    void bundleCliIsTheFinalGateBeforeFormalComplete() throws Exception {
        String source = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        int normalCli = source.indexOf("CapabilityStudioBrowserMatrixResultCli");
        int anomalyCli = source.indexOf("CapabilityStudioBrowserAnomalyMatrixResultCli");
        int bundleCli = source.indexOf("CapabilityStudioBrowserEvidenceBundleCli");
        int formalBundleGate = source.lastIndexOf(
                "if [[ \"${FORMAL_RUN}\" == \"true\" ]]; then", bundleCli);
        int bundleMatch = source.indexOf(
                "\"${BUNDLE_CLI_OUTPUT}\" =~ ${BUNDLE_COMPLETE_PATTERN}");
        int complete = source.indexOf("COMPLETE: 186/186");

        assertThat(normalCli).isGreaterThanOrEqualTo(0);
        assertThat(anomalyCli).isGreaterThan(normalCli);
        assertThat(bundleCli).isGreaterThan(anomalyCli);
        assertThat(formalBundleGate).isGreaterThan(anomalyCli);
        assertThat(formalBundleGate).isLessThan(bundleCli);
        assertThat(bundleMatch).isGreaterThan(bundleCli);
        assertThat(complete).isGreaterThan(bundleMatch);
        assertThat(source).contains(
                "BUNDLE_COMPLETE_PATTERN='^VALID status=COMPLETE expectedCount=438 "
                        + "persistedCount=438 manifestFingerprint=sha256:[0-9a-f]{64}$'",
                "FORMAL_MIN_FREE_KIB=4194304",
                "FORMAL_MIN_FREE_INODES=20000",
                "--allow-dirty)\n            ALLOW_DIRTY=true\n            FORMAL_RUN=false",
                "--normal-result \"${OUTPUT}\"",
                "--anomaly-result \"${ANOMALY_OUTPUT}\"",
                "--artifact-root \"${ARTIFACT_ROOT}\"",
                "--manifest-output \"${BUNDLE_MANIFEST}\"",
                "EVIDENCE_MANIFEST: ${BUNDLE_MANIFEST}",
                "capability-studio-browser-evidence-bundle-manifest-v1.json");
    }

    private Path fakeRepository() throws IOException {
        Path root = temporaryDirectory.resolve("repository");
        Path script = root.resolve("scripts/run-capability-studio-browser-matrix.sh");
        Files.createDirectories(script.getParent());
        Files.copy(SCRIPT, script);

        Path bin = root.resolve("bin");
        Files.createDirectories(bin);
        executable(bin.resolve("git"), "#!/bin/sh\n"
                + "case \"$*\" in\n"
                + "  *\"rev-parse --short=12 HEAD\"*) printf '%s\\n' " + SOURCE_COMMIT.substring(0, 12) + " ;;\n"
                + "  *\"rev-parse HEAD\"*) printf '%s\\n' " + SOURCE_COMMIT + " ;;\n"
                + "  *\"status --porcelain=v1\"*) exit 0 ;;\n"
                + "  *) exit 0 ;;\n"
                + "esac\n");
        executable(bin.resolve("mvn"), "#!/bin/sh\n"
                + "printf '%s' \"${MAVEN_MARKER}\" > \"${MAVEN_MARKER}\"\n"
                + "exit 99\n");
        executable(bin.resolve("df"), "#!/bin/sh\n"
                + "printf '%s\\n' 'Filesystem 1024-blocks Used Available Capacity Mounted on'\n"
                + "printf '%s\\n' '/dev/fake 999999999 1 999999998 1% /'\n");
        Files.createDirectories(root.resolve("resource-gateway-examples/target"));
        Files.createDirectories(root.resolve("resource-gateway-test-kit/target"));
        return root;
    }

    private ProcessResult run(Path root, Map<String, String> environment, String... arguments)
            throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("bash");
        command.add(root.resolve("scripts/run-capability-studio-browser-matrix.sh").toString());
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true);
        builder.environment().put("PATH",
                root.resolve("bin") + ":" + System.getenv().getOrDefault("PATH", ""));
        builder.environment().put("MVN", root.resolve("bin/mvn").toString());
        builder.environment().putAll(environment);
        Process process = builder.start();
        assertThat(process.waitFor(Duration.ofSeconds(5))).isTrue();
        return new ProcessResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void executable(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        assertThat(path.toFile().setExecutable(true)).isTrue();
    }

    private record ProcessResult(int exitCode, String output) { }
}
