package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioFormalEvidenceRunScriptTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatorNoArgUsageIsClosed() throws Exception {
        ProcessResult result = process(List.of("bash", generatorPath().toString()), Map.of());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("usage: generate-formal-evidence-run-demo.sh");
    }

    @Test
    void generatorCreatesPrivateCanonicalIncompleteDemo() throws Exception {
        Path output = temporaryDirectory.resolve("gate-a0-demo");

        ProcessResult result = process(
                List.of("bash", generatorPath().toString(), output.toString()), Map.of());

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("status=INCOMPLETE");
        assertThat(Files.readAllBytes(output.resolve("manifest.json")))
                .isEqualTo(CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(
                        CapabilityStudioFormalEvidenceRunManifest.parseStrict(
                                Files.readAllBytes(output.resolve("manifest.json")))));
        assertThat(Files.isDirectory(output.resolve("evidence-root"))).isTrue();
        assertThat(posixPermissions(output)).isEqualTo("rwx------");
        assertThat(posixPermissions(output.resolve("evidence-root"))).isEqualTo("rwx------");
        assertThat(posixPermissions(output.resolve("manifest.json"))).isEqualTo("rw-------");
    }

    @Test
    void generatedDemoIsVerifiedByLauncherAsIncomplete() throws Exception {
        Path output = generateDemo();
        Path fakeMaven = fakeMavenThatWritesRuntimeClasspath();
        Map<String, String> environment = new HashMap<>();
        environment.put("MVN_BIN", fakeMaven.toString());
        environment.put("JAVA_BIN", javaBinary().toString());
        environment.put("RG_TEST_RUNTIME_CLASSPATH", System.getProperty("java.class.path"));

        ProcessResult result = process(List.of(
                "bash", verifierPath().toString(),
                "--manifest", output.resolve("manifest.json").toString(),
                "--bundle-root", output.resolve("evidence-root").toString()), environment);

        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.output()).startsWith("NOT_VERIFIED outcome=INCOMPLETE")
                .contains("typedReplayCount=0", "formalConclusion=INCOMPLETE")
                .doesNotContain("ACCEPTED", "VERIFIED status=");
    }

    @Test
    void launcherPreservesPayloadFreeInvalidExit() throws Exception {
        Path output = generateDemo();
        Files.writeString(output.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);
        Map<String, String> environment = new HashMap<>();
        environment.put("MVN_BIN", fakeMavenThatWritesRuntimeClasspath().toString());
        environment.put("JAVA_BIN", javaBinary().toString());
        environment.put("RG_TEST_RUNTIME_CLASSPATH", System.getProperty("java.class.path"));

        ProcessResult result = process(verifierCommand(output), environment);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).startsWith("INVALID reasonCode=")
                .doesNotContain(output.toString(), temporaryDirectory.toString());
    }

    @Test
    void buildAndJvmLaunchFailuresAreUnavailable() throws Exception {
        Path output = generateDemo();
        Path failingMaven = executable("failing-mvn", "#!/usr/bin/env bash\nexit 9\n");
        Map<String, String> buildEnvironment = Map.of("MVN_BIN", failingMaven.toString());
        List<String> command = verifierCommand(output);

        ProcessResult buildFailure = process(command, buildEnvironment);
        assertThat(buildFailure.exitCode()).isEqualTo(3);
        assertThat(buildFailure.output()).contains("BUILD_UNAVAILABLE")
                .doesNotContain(temporaryDirectory.toString(), output.toString());

        Path fakeMaven = fakeMavenThatWritesRuntimeClasspath();
        Path failingJava = executable("failing-java", "#!/usr/bin/env bash\nexit 9\n");
        Map<String, String> launchEnvironment = new HashMap<>();
        launchEnvironment.put("MVN_BIN", fakeMaven.toString());
        launchEnvironment.put("JAVA_BIN", failingJava.toString());
        launchEnvironment.put("RG_TEST_RUNTIME_CLASSPATH", System.getProperty("java.class.path"));

        ProcessResult launchFailure = process(command, launchEnvironment);
        assertThat(launchFailure.exitCode()).isEqualTo(3);
        assertThat(launchFailure.output()).contains("RUNTIME_UNAVAILABLE")
                .doesNotContain(temporaryDirectory.toString(), output.toString());
    }

    private Path generateDemo() throws Exception {
        Path output = temporaryDirectory.resolve("generated-demo-" + System.nanoTime());
        ProcessResult result = process(
                List.of("bash", generatorPath().toString(), output.toString()), Map.of());
        assertThat(result.exitCode()).isZero();
        return output.toRealPath();
    }

    private List<String> verifierCommand(Path output) {
        return List.of("bash", verifierPath().toString(),
                "--manifest", output.resolve("manifest.json").toString(),
                "--bundle-root", output.resolve("evidence-root").toString());
    }

    private Path fakeMavenThatWritesRuntimeClasspath() throws IOException {
        return executable("fake-mvn", "#!/usr/bin/env bash\n"
                + "for argument in \"$@\"; do\n"
                + "  case \"$argument\" in\n"
                + "    -Dmdep.outputFile=*) output=\"${argument#*=}\" ;;\n"
                + "  esac\n"
                + "done\n"
                + "printf '%s' \"$RG_TEST_RUNTIME_CLASSPATH\" > \"$output\"\n");
    }

    private Path executable(String name, String content) throws IOException {
        Path script = temporaryDirectory.resolve(name);
        Files.writeString(script, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    private ProcessResult process(List<String> command, Map<String, String> environment)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new ProcessResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private Path generatorPath() {
        return Path.of("scripts/generate-formal-evidence-run-demo.sh").toAbsolutePath();
    }

    private Path verifierPath() {
        return Path.of("scripts/verify-formal-evidence-run.sh").toAbsolutePath();
    }

    private Path javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    private String posixPermissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
