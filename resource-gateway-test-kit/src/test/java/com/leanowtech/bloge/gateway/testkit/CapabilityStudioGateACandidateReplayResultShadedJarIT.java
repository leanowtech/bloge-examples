package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioGateACandidateReplayResultShadedJarIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesCandidateResultRelativeSchemaRefsFromShadedJar() throws Exception {
        var fixture = CapabilityStudioFormalEvidenceRunTestFixtures.empty(temporaryDirectory);
        fixture.addAllInventory();
        fixture.write();
        var bundle = CapabilityStudioGateACandidateReplayResult.create(
                fixture.manifest(), fixture.root(), context());
        Path resultFile = temporaryDirectory.resolve("candidate-result.json");
        Files.write(resultFile, bundle.resultBytes());

        Path shadedJar;
        try (var files = Files.list(Path.of("target").toAbsolutePath())) {
            shadedJar = files.filter(path -> path.getFileName().toString().endsWith("-cli.jar"))
                    .findFirst().orElseThrow();
        }
        String classpath = Path.of("target/test-classes").toAbsolutePath()
                + File.pathSeparator + shadedJar;
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-preview", "-cp", classpath,
                CapabilityStudioGateACandidateReplayResultShadedJarProbe.class.getName(),
                resultFile.toString())
                .redirectErrorStream(true)
                .start();

        assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.exitValue()).isZero();
        assertThat(output).contains("SHADED_SCHEMA_OK");
    }

    private static CapabilityStudioGateACandidateReplayResult.Context context() {
        return new CapabilityStudioGateACandidateReplayResult.Context(
                "A0-SHADED-001",
                raw("candidate/artifact", 'a'), raw("challenge/trust-pin", 'b'),
                "formal-evidence/manifest",
                new CapabilityStudioGateACandidateReplayResult.TreeRef(
                        "challenge/input-root", fp('c')),
                raw("registry/typed-replay", 'd'),
                "formal-evidence/files", "candidate-result/adapter-materials");
    }

    private static CapabilityStudioGateACandidateReplayResult.RawRef raw(
            String uri, char seed) {
        return new CapabilityStudioGateACandidateReplayResult.RawRef(uri, fp(seed));
    }

    private static String fp(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
