package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioBrowserEvidenceBundleCliTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesNamedUsageAndWritesManifestAtomically() throws Exception {
        Path root = temporaryDirectory.resolve("bundle");
        var fixture = TestFixtures.complete(root);
        Path output = temporaryDirectory.resolve("manifest.json");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exit = CapabilityStudioBrowserEvidenceBundleCli.run(new String[] {
                "--normal-result", fixture.normalPath().toString(),
                "--anomaly-result", fixture.anomalyPath().toString(),
                "--artifact-root", root.toString(),
                "--manifest-output", output.toString()
        }, new PrintStream(stdout), System.err);

        assertThat(exit).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .startsWith("VALID status=COMPLETE expectedCount=438 persistedCount=438 "
                        + "manifestFingerprint=sha256:");
        assertThat(Files.readAllBytes(output)).isEqualTo(fixture.manifest());
    }

    @Test
    void rejectsUsageAndOutputFailureWithoutReplacingExistingOutput() throws Exception {
        ByteArrayOutputStream usageOut = new ByteArrayOutputStream();
        int usage = CapabilityStudioBrowserEvidenceBundleCli.run(
                new String[] {"only-one"}, new PrintStream(usageOut), System.err);
        assertThat(usage).isEqualTo(2);
        assertThat(usageOut.toString(StandardCharsets.UTF_8))
                .contains("errorCode=RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_CLI_USAGE")
                .doesNotContain("only-one");

        var fixture = TestFixtures.complete(temporaryDirectory.resolve("output-failure"));
        Path outputDirectory = temporaryDirectory.resolve("existing-output");
        Files.createDirectory(outputDirectory);
        int outputFailure = CapabilityStudioBrowserEvidenceBundleCli.run(new String[] {
                fixture.normalPath().toString(), fixture.anomalyPath().toString(),
                fixture.root().toString(), outputDirectory.toString()
        }, new PrintStream(new ByteArrayOutputStream()), System.err);
        assertThat(outputFailure).isEqualTo(2);
        assertThat(Files.isDirectory(outputDirectory)).isTrue();
    }

    @Test
    void mapsInvalidJsonToStablePayloadFreeOutput() {
        Path normal = temporaryDirectory.resolve("normal-invalid.json");
        Path anomaly = temporaryDirectory.resolve("anomaly-invalid.json");
        try {
            Files.writeString(normal, "{invalid", StandardCharsets.UTF_8);
            Files.writeString(anomaly, "{invalid", StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = CapabilityStudioBrowserEvidenceBundleCli.run(new String[] {
                normal.toString(), anomaly.toString(), temporaryDirectory.toString(),
                temporaryDirectory.resolve("manifest.json").toString()
        }, new PrintStream(stdout), System.err);

        assertThat(exit).isEqualTo(2);
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("errorCode=RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_NORMAL_RESULT_INVALID")
                .doesNotContain("invalid");
    }

    private static final class TestFixtures {
        private static Fixture complete(Path root) throws Exception {
            Files.createDirectories(root.resolve("browser-matrix-evidence"));
            Files.createDirectories(root.resolve("browser-anomaly-evidence"));
            var normalBuilder = new CapabilityStudioBrowserMatrixResultBuilder(
                    "BMR-fixture-1", 1, "s0-ac-01.v1",
                    new CapabilityStudioBrowserMatrixResultBuilder.Candidate(
                            "build/candidate-1", "candidate-revision-1", FINGERPRINT, "abcdef1", "CLEAN"),
                    new CapabilityStudioBrowserMatrixResultBuilder.BaselineRef(
                            "baseline/s0-ac-01", 1, FINGERPRINT),
                    new CapabilityStudioBrowserMatrixResultBuilder.Environment(
                            FINGERPRINT, "chrome/stable", "chromium", "128.0", "128.0", "4.10.2"),
                    new CapabilityStudioBrowserMatrixResultBuilder.ExecutionWindow(START, END));
            for (var key : CapabilityStudioBrowserMatrixResultBuilder.expectedCells()) {
                String fileName = key.goldenPathId().toLowerCase(java.util.Locale.ROOT) + "-"
                        + key.locale() + "-" + key.viewport().width() + "x"
                        + key.viewport().height() + ".png";
                String ref = "artifact:browser-matrix-evidence/" + fileName;
                byte[] bytes = ("normal:" + key.cellId()).getBytes(StandardCharsets.UTF_8);
                Files.write(root.resolve(ref.substring("artifact:".length())), bytes);
                normalBuilder.pass(key, key.viewport(), java.util.List.of(
                        new CapabilityStudioBrowserMatrixResultBuilder.EvidenceRef(ref, sha(bytes))));
            }
            var normal = normalBuilder.build();
            var anomalyBuilder = new CapabilityStudioBrowserAnomalyMatrixResultBuilder(
                    "BAMR-fixture-1", 1, "s0-ac-01.v1",
                    new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Candidate(
                            "build/candidate-1", "candidate-revision-1", FINGERPRINT, "abcdef1",
                            CapabilityStudioBrowserAnomalyMatrixResultBuilder.SourceTreeStatus.CLEAN),
                    new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaselineRef(
                            "baseline/s0-ac-01", 1, FINGERPRINT),
                    new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Environment(
                            FINGERPRINT, "chrome/stable", "chromium", "128.0", "128.0", "4.10.2"),
                    new CapabilityStudioBrowserAnomalyMatrixResultBuilder.ExecutionWindow(START, END),
                    new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaseMatrixRef(
                            "results/browser-matrix/BMR-fixture-1",
                            normal.path("evidenceClosureFingerprint").asText(),
                            CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaseMatrixStatus.COMPLETE));
            for (var key : CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations()) {
                var evidence = new java.util.ArrayList<
                        CapabilityStudioBrowserAnomalyMatrixResultBuilder.EvidenceRef>();
                String prefix = key.obligationId().substring("BAM-".length())
                        .toLowerCase(java.util.Locale.ROOT);
                for (String suffix : java.util.List.of(
                        "-error.png", "-recovered.png", "-trigger.json")) {
                    String ref = "artifact:browser-anomaly-evidence/" + prefix + suffix;
                    byte[] bytes = ("anomaly:" + key.obligationId() + ":" + suffix)
                            .getBytes(StandardCharsets.UTF_8);
                    Path path = root.resolve(ref.substring("artifact:".length()));
                    Files.write(path, bytes);
                    evidence.add(new CapabilityStudioBrowserAnomalyMatrixResultBuilder.EvidenceRef(
                            ref, sha(bytes)));
                }
                anomalyBuilder.pass(key,
                        new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations(
                                key.viewport(), false,
                                CapabilityStudioBrowserAnomalyMatrixResultBuilder.Axe.clear(), 0, 0,
                                CapabilityStudioBrowserAnomalyMatrixResultBuilder.KeyboardPath.complete(10),
                                true, true, true, true, true, true, true,
                                true, true, true, true, 0, 0),
                        evidence);
            }
            var anomaly = anomalyBuilder.build();
            byte[] normalBytes = JSON.writeValueAsBytes(normal);
            byte[] anomalyBytes = JSON.writeValueAsBytes(anomaly);
            var verification = new CapabilityStudioBrowserEvidenceBundleVerifier().verify(
                    normalBytes, anomalyBytes, root);
            Files.write(root.resolve("normal.json"), normalBytes);
            Files.write(root.resolve("anomaly.json"), anomalyBytes);
            return new Fixture(root, root.resolve("normal.json"), root.resolve("anomaly.json"),
                    normalBytes, anomalyBytes,
                    JSON.writeValueAsBytes(verification.manifest()));
        }

        private static String sha(byte[] bytes) throws Exception {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        }
    }

    private record Fixture(
            Path root, Path normalPath, Path anomalyPath, byte[] normal, byte[] anomaly, byte[] manifest) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String START = "2026-08-18T00:00:00Z";
    private static final String END = "2026-08-18T01:00:00Z";
}
