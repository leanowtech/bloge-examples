package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioStageAcceptanceDeploymentScriptTest {
    private static final int MAX_STAGE_RESULT_BYTES = 4 * 1024 * 1024;
    private static final String SCRIPT = "scripts/verify-capability-studio-stage-acceptance.sh";
    private static final String CONFORMANCE_MAIN =
            "com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceProviderConformanceCli";
    private static final String FORMAL_MAIN =
            "com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceCli";
    private static final String EXPECTED_AUTHORITY_BINDING = "sha256:" + "a".repeat(64);
    private static final String TEST_KIT_PIN_ENV = "BLOGE_EXPECTED_TEST_KIT_JAR_SHA256";
    private static final String STAGE_RESULT_PIN_ENV = "BLOGE_EXPECTED_STAGE_RESULT_SHA256";
    private static final String PROVIDER_PINS_ENV = "BLOGE_EXPECTED_PROVIDER_CLASSPATH_SHA256S";

    @TempDir
    Path temp;

    @Test
    void shellSyntaxAndHelpAreAvailableWithoutInvokingJava() throws Exception {
        Path fakeJava = fakeJava("success");
        Path log = temp.resolve("calls.log");

        ProcessResult syntax = process(List.of("bash", "-n", scriptPath().toString()),
                Map.of("JAVA_BIN", fakeJava.toString(), "FAKE_LOG", log.toString(),
                        "BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT", EXPECTED_AUTHORITY_BINDING));
        ProcessResult help = process(List.of("bash", scriptPath().toString(), "--help"),
                Map.of("JAVA_BIN", fakeJava.toString(), "FAKE_LOG", log.toString(),
                        "BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT", EXPECTED_AUTHORITY_BINDING));

        assertThat(syntax.exitCode()).isZero();
        assertThat(help.exitCode()).isZero();
        assertThat(help.output()).contains("Usage:").doesNotContain(fakeJava.toString());
        assertThat(log).doesNotExist();
    }

    @Test
    void preflightRejectsMissingOversizedAndExistingOutputBeforeJava() throws Exception {
        Path fakeJava = fakeJava("success");
        Path log = temp.resolve("calls.log");
        Path jar = regularFile("test-kit.jar", "jar");
        Path provider = regularFile("provider.jar", "provider");
        Path stage = temp.resolve("missing-secret-stage.json");
        Path output = temp.resolve("missing-output.json");

        ProcessResult missing = run(fakeJava, log, jar, provider, stage, output);
        assertPreflightFailure(missing, stage);

        Files.writeString(stage, "x".repeat(MAX_STAGE_RESULT_BYTES + 1));
        ProcessResult oversized = run(fakeJava, log, jar, provider, stage, output);
        assertPreflightFailure(oversized, stage);

        Files.writeString(stage, "{}");
        Files.writeString(output, "already-present");
        ProcessResult existing = run(fakeJava, log, jar, provider, stage, output);
        assertPreflightFailure(existing, output);
        assertThat(log).doesNotExist();
    }

    @Test
    void deploymentBindingPinIsRequiredAndValidatedBeforeJava() throws Exception {
        Path fakeJava = fakeJava("success");
        Path log = temp.resolve("calls.log");
        Path jar = regularFile("test-kit.jar", "jar");
        Path provider = regularFile("provider.jar", "provider");
        Path stage = regularFile("stage.json", "{}");
        Path output = temp.resolve("report.json");
        Map<String, String> validPins = pinEnvironment(jar, provider.toString(), stage);
        for (String pin : java.util.Arrays.asList(null, "", "sha256:" + "A".repeat(64))) {
            Map<String, String> environment = new java.util.HashMap<>(validPins);
            environment.put("JAVA_BIN", fakeJava.toString());
            environment.put("FAKE_LOG", log.toString());
            if (pin != null) {
                environment.put("BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT", pin);
            }
            ProcessResult result = process(withScript(arguments(jar, provider.toString(), stage,
                    output)), environment);
            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.output()).isEqualTo("ERROR code=AUTHORITY_BINDING\n")
                    .doesNotContain(jar.toString(), provider.toString(), stage.toString());
            assertThat(log).doesNotExist();
        }
    }

    @Test
    void artifactPinsRequireLowercaseValuesExactCountsAndOrderedProvidersBeforeJava()
            throws Exception {
        Path fakeJava = fakeJava("success");
        Path log = temp.resolve("calls.log");
        Path jar = regularFile("test-kit.jar", "jar");
        Path providerOne = regularFile("provider-one.jar", "one");
        Path providerTwo = regularFile("provider-two.jar", "two");
        Path stage = regularFile("stage.json", "{}");
        Path output = temp.resolve("report.json");
        Map<String, String> validPins = new java.util.HashMap<>(
                pinEnvironment(jar, providerOne + ":" + providerTwo, stage));
        validPins.put("JAVA_BIN", fakeJava.toString());
        validPins.put("FAKE_LOG", log.toString());
        validPins.put("BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT",
                EXPECTED_AUTHORITY_BINDING);
        String[] orderedProviderPins = validPins.get(PROVIDER_PINS_ENV).split(",", -1);

        List<Map.Entry<String, String>> invalidPins = List.of(
                Map.entry(TEST_KIT_PIN_ENV, "A".repeat(64)),
                Map.entry(STAGE_RESULT_PIN_ENV, "f".repeat(63)),
                Map.entry(PROVIDER_PINS_ENV, sha256(providerOne)),
                Map.entry(PROVIDER_PINS_ENV,
                        orderedProviderPins[1] + "," + orderedProviderPins[0]),
                Map.entry(TEST_KIT_PIN_ENV, "f".repeat(64)));
        for (Map.Entry<String, String> invalidPin : invalidPins) {
            Map<String, String> environment = new java.util.HashMap<>(validPins);
            environment.put(invalidPin.getKey(), invalidPin.getValue());
            ProcessResult result = process(withScript(arguments(jar,
                    providerOne + ":" + providerTwo, stage, output)), environment);

            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.output()).startsWith("ERROR code=INPUT_PIN")
                    .doesNotContain(jar.toString(), providerOne.toString(), stage.toString());
            assertThat(log).doesNotExist();
        }

        for (String missingPin : List.of(TEST_KIT_PIN_ENV, STAGE_RESULT_PIN_ENV,
                PROVIDER_PINS_ENV)) {
            Map<String, String> environment = new java.util.HashMap<>(validPins);
            environment.remove(missingPin);
            ProcessResult result = process(withScript(arguments(jar,
                    providerOne + ":" + providerTwo, stage, output)), environment);

            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.output()).isEqualTo("ERROR code=INPUT_PIN\n")
                    .doesNotContain(jar.toString(), providerOne.toString(), stage.toString());
            assertThat(log).doesNotExist();
        }
    }

    @Test
    void sourceMutationBetweenHashAndCopyIsRejectedBeforeJava() throws Exception {
        Path caseDirectory = Files.createDirectory(temp.resolve("hash-copy-drift"));
        Path fakeJava = fakeJava(caseDirectory, "success");
        Path fakeBin = Files.createDirectory(caseDirectory.resolve("bin"));
        Path fakeCp = fakeCp(fakeBin);
        Path log = caseDirectory.resolve("calls.log");
        Path jar = regularFile(caseDirectory, "kit.jar", "kit");
        Path provider = regularFile(caseDirectory, "provider.jar", "provider");
        Path stage = regularFile(caseDirectory, "stage.json", "{}");

        ProcessResult result = run(fakeJava, log, jar, provider.toString(), stage,
                caseDirectory.resolve("report.json"), Map.of(
                        "PATH", fakeBin + ":" + System.getenv("PATH"),
                        "REAL_CP", "/bin/cp",
                        "SOURCE_TO_MUTATE", jar.toString(),
                        "FAKE_CP_MARKER", caseDirectory.resolve("cp.marker").toString(),
                        "FAKE_CP", fakeCp.toString()));

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).isEqualTo("ERROR code=INPUT_PIN_MISMATCH\n");
        assertThat(log).doesNotExist();
        assertThat(Files.readString(jar)).isEqualTo("changed-before-copy");
    }

    @Test
    void preflightRequiresAnExecutablePathButSupportsSpacesWithoutInvokingShellCode()
            throws Exception {
        Path log = temp.resolve("calls.log");
        Path jar = regularFile("test-kit.jar", "jar");
        Path provider = regularFile("provider.jar", "provider");
        Path stage = regularFile("stage.json", "{}");
        Path output = temp.resolve("report.json");

        ProcessResult commandName = run(Path.of("printf"), log, jar, provider, stage, output);

        assertThat(commandName.exitCode()).isEqualTo(2);
        assertThat(commandName.output()).isEqualTo("ERROR code=JAVA\n");
        assertThat(log).doesNotExist();

        Path spacedDirectory = Files.createDirectory(temp.resolve("java runtime"));
        Path spacedJava = fakeJava(spacedDirectory, "success");
        ProcessResult spacedPath = run(spacedJava, log, jar, provider, stage, output);

        assertThat(spacedPath.exitCode()).isZero();
        assertThat(Files.readAllLines(log)).hasSize(2);
    }

    @Test
    void preflightRejectsDuplicateUnknownEmptyClasspathAndSymlinkInputsBeforeJava()
            throws Exception {
        Path fakeJava = fakeJava("success");
        Path log = temp.resolve("calls.log");
        Path jar = regularFile("test-kit.jar", "jar");
        Path provider = regularFile("provider.jar", "provider");
        Path stage = regularFile("stage.json", "{}");
        Path stageLink = temp.resolve("stage-link.json");
        Files.createSymbolicLink(stageLink, stage.getFileName());
        Path output = temp.resolve("report.json");
        Map<String, String> environment = Map.of(
                "JAVA_BIN", fakeJava.toString(), "FAKE_LOG", log.toString(),
                "BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT", EXPECTED_AUTHORITY_BINDING);
        List<List<String>> invalidArguments = List.of(
                List.of("--test-kit-jar", jar.toString(),
                        "--test-kit-jar", jar.toString(),
                        "--stage-result", stage.toString(),
                        "--conformance-output", output.toString()),
                List.of("--test-kit-jar", jar.toString(),
                        "--provider-classpath", provider.toString(),
                        "--stage-result", stage.toString(),
                        "--unexpected", output.toString()),
                arguments(jar, "", stage, output),
                arguments(jar, provider.toString(), stageLink, output));

        for (List<String> arguments : invalidArguments) {
            ProcessResult result = process(withScript(arguments), environment);
            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.output()).startsWith("ERROR code=")
                    .doesNotContain(jar.toString(), provider.toString(), stage.toString());
        }
        assertThat(log).doesNotExist();
    }

    @Test
    void successfulRunUsesSameClasspathAndFixedOrderAndCleansTemporaryFiles() throws Exception {
        Path fakeJava = fakeJava("success");
        Path log = temp.resolve("calls.log");
        Path jar = regularFile("test-kit.jar", "jar");
        Path providerOne = regularFile("provider-one.jar", "one");
        Path providerTwo = regularFile("provider-two.jar", "two");
        Path stage = regularFile("stage.json", "{}");
        Path output = temp.resolve("evidence.json");

        ProcessResult result = run(fakeJava, log, jar, providerOne, providerTwo, stage, output);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo(
                "ACCEPTED status=ACCEPTED authorityBindingFingerprint="
                        + EXPECTED_AUTHORITY_BINDING + " providerConformanceFingerprint=sha256:"
                        + "0000000000000000000000000000000000000000000000000000000000000000\n");
        List<FakeInvocation> calls = invocations(log);
        assertThat(calls).extracting(FakeInvocation::main)
                .containsExactly(CONFORMANCE_MAIN, FORMAL_MAIN);
        assertThat(calls.get(0).classpath()).isEqualTo(calls.get(1).classpath())
                .doesNotContain(jar.toString(), providerOne.toString(), providerTwo.toString());
        assertThat(calls.get(0).stageResult()).isEqualTo(calls.get(1).stageResult())
                .isNotEqualTo(stage.toString());
        assertThat(output).isRegularFile();
        assertThat(Files.isSymbolicLink(output)).isFalse();
        try (Stream<Path> entries = Files.list(temp)) {
            assertThat(entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".capability-studio-acceptance.")))
                    .isEmpty();
        }
    }

    @Test
    void sourceMutationBetweenPhasesCannotChangeSnapshottedInputs() throws Exception {
        Path caseDirectory = Files.createDirectory(temp.resolve("source-mutation"));
        Path fakeJava = fakeJava(caseDirectory, "mutate-sources");
        Path log = caseDirectory.resolve("calls.log");
        Path jar = regularFile(caseDirectory, "kit.jar", "kit");
        Path provider = regularFile(caseDirectory, "provider.jar", "provider");
        Path stage = regularFile(caseDirectory, "stage.json", "{}");

        ProcessResult result = run(fakeJava, log, jar, provider.toString(), stage,
                caseDirectory.resolve("report.json"), Map.of(
                        "SOURCE_TEST_KIT", jar.toString(),
                        "SOURCE_PROVIDER", provider.toString(),
                        "SOURCE_STAGE", stage.toString()));

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(jar)).isEqualTo("changed-kit");
        assertThat(Files.readString(provider)).isEqualTo("changed-provider");
        assertThat(Files.readString(stage)).isEqualTo("changed-stage");
        List<FakeInvocation> calls = invocations(log);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).classpath()).isEqualTo(calls.get(1).classpath());
        assertThat(calls.get(0).stageResult()).isEqualTo(calls.get(1).stageResult());
    }

    @Test
    void snapshotMutationStopsBeforeFormalVerification() throws Exception {
        Path caseDirectory = Files.createDirectory(temp.resolve("snapshot-mutation"));
        Path log = caseDirectory.resolve("calls.log");

        ProcessResult result = run(fakeJava(caseDirectory, "mutate-snapshot"), log,
                regularFile(caseDirectory, "kit.jar", "kit"),
                regularFile(caseDirectory, "provider.jar", "provider"),
                regularFile(caseDirectory, "stage.json", "{}"),
                caseDirectory.resolve("report.json"));

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(invocations(log)).singleElement()
                .extracting(FakeInvocation::main).isEqualTo(CONFORMANCE_MAIN);
    }

    @Test
    void conformanceExitThreeStopsBeforeFormalVerification() throws Exception {
        Path fakeJava = fakeJava("conformance-exit-3");
        Path log = temp.resolve("calls.log");
        ProcessResult result = run(fakeJava, log, regularFile("kit.jar", "kit"),
                regularFile("provider.jar", "provider"), regularFile("stage.json", "{}"),
                temp.resolve("report.json"));

        assertThat(result.exitCode()).isEqualTo(3);
        List<String> calls = Files.readAllLines(log);
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst()).startsWith(CONFORMANCE_MAIN + "|");
        assertThat(Files.readString(temp.resolve("report.json"))).isEqualTo("blocked-report\n");
    }

    @Test
    void formalExitThreeIsMappedToThreeAfterConformancePasses() throws Exception {
        Path fakeJava = fakeJava("formal-exit-3");
        Path log = temp.resolve("calls.log");
        ProcessResult result = run(fakeJava, log, regularFile("kit.jar", "kit"),
                regularFile("provider.jar", "provider"), regularFile("stage.json", "{}"),
                temp.resolve("report.json"));

        assertThat(result.exitCode()).isEqualTo(3);
        List<String> calls = Files.readAllLines(log);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).startsWith(CONFORMANCE_MAIN + "|");
        assertThat(calls.get(1)).startsWith(FORMAL_MAIN + "|");
    }

    @Test
    void forgedOrMultilineSuccessOutputIsRejected() throws Exception {
        for (String mode : List.of("spoof-conformance", "multiline-conformance", "spoof-formal",
                "multiline-formal")) {
            Path caseDirectory = Files.createDirectory(temp.resolve(mode));
            Path fakeJava = fakeJava(caseDirectory, mode);
            Path log = caseDirectory.resolve("calls.log");
            Path jar = regularFile(caseDirectory, "kit.jar", "kit");
            Path provider = regularFile(caseDirectory, "provider.jar", "provider");
            Path stage = regularFile(caseDirectory, "stage.json", "{}");
            Path output = caseDirectory.resolve("report.json");

            ProcessResult result = run(fakeJava, log, jar, provider, stage, output);

            assertThat(result.exitCode()).isEqualTo(2);
            if (mode.endsWith("formal")) {
                List<String> calls = Files.readAllLines(log);
                assertThat(calls).hasSize(2);
                assertThat(calls.get(0)).startsWith(CONFORMANCE_MAIN + "|");
                assertThat(calls.get(1)).startsWith(FORMAL_MAIN + "|");
            } else {
                List<String> calls = Files.readAllLines(log);
                assertThat(calls).hasSize(1);
                assertThat(calls.getFirst()).startsWith(CONFORMANCE_MAIN + "|");
            }
        }
    }

    @Test
    void bothJavaPhasesMustMatchTheOutOfBandDeploymentPin() throws Exception {
        for (String mode : List.of("mismatch-conformance", "mismatch-formal")) {
            Path caseDirectory = Files.createDirectory(temp.resolve(mode));
            Path fakeJava = fakeJava(caseDirectory, mode);
            Path log = caseDirectory.resolve("calls.log");
            Path jar = regularFile(caseDirectory, "kit.jar", "kit");
            Path provider = regularFile(caseDirectory, "provider.jar", "provider");
            Path stage = regularFile(caseDirectory, "stage.json", "{}");
            ProcessResult result = run(fakeJava, log, jar, provider, stage,
                    caseDirectory.resolve("report.json"));

            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.output()).isEqualTo("ERROR code=AUTHORITY_BINDING_MISMATCH\n")
                    .doesNotContain(jar.toString(), provider.toString(), stage.toString());
            assertThat(invocations(log)).hasSize(mode.endsWith("formal") ? 2 : 1);
        }
    }

    @Test
    void missingEmptyOrOversizedConformanceReportsStopBeforeFormalVerification()
            throws Exception {
        for (String mode : List.of("missing-report", "empty-report", "oversized-report",
                "symlink-report")) {
            Path caseDirectory = Files.createDirectory(temp.resolve(mode));
            Path fakeJava = fakeJava(caseDirectory, mode);
            Path log = caseDirectory.resolve("calls.log");
            Path jar = regularFile(caseDirectory, "kit.jar", "kit");
            Path provider = regularFile(caseDirectory, "provider.jar", "provider");
            Path stage = regularFile(caseDirectory, "stage.json", "{}");

            ProcessResult result = run(fakeJava, log, jar, provider, stage,
                    caseDirectory.resolve("report.json"));

            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(Files.readAllLines(log)).singleElement()
                    .asString().startsWith(CONFORMANCE_MAIN + "|");
        }
    }

    @Test
    void successfulChildDiagnosticsStayPrivateAndTemporaryFilesAreRemoved() throws Exception {
        String secret = "successful-child-secret";
        Path caseDirectory = Files.createDirectory(temp.resolve("successful-secret"));
        Path log = caseDirectory.resolve("calls.log");

        ProcessResult result = run(fakeJava(caseDirectory, "success-secret"), log,
                regularFile(caseDirectory, "kit.jar", "kit"),
                regularFile(caseDirectory, "provider.jar", "provider").toString(),
                regularFile(caseDirectory, "stage.json", "{}"),
                caseDirectory.resolve("report.json"), Map.of("FAKE_SECRET", secret));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).doesNotContain(secret);
        assertNoTemporaryDirectories(caseDirectory);
    }

    @Test
    void terminationSignalStopsResponsiveAndUnresponsiveChildrenBeforeCleanup()
            throws Exception {
        assertSignalTermination("block", true);
        assertSignalTermination("ignore-term", false);
    }

    private void assertSignalTermination(String mode, boolean expectsGracefulStatus)
            throws Exception {
        Path caseDirectory = Files.createDirectory(temp.resolve("signal-" + mode));
        Path fakeJava = fakeJava(caseDirectory, mode);
        Path log = caseDirectory.resolve("calls.log");
        Path jar = regularFile(caseDirectory, "kit.jar", "kit");
        Path provider = regularFile(caseDirectory, "provider.jar", "provider");
        Path stage = regularFile(caseDirectory, "stage.json", "{}");
        Path output = caseDirectory.resolve("report.json");
        ProcessBuilder builder = new ProcessBuilder(withScript(
                arguments(jar, provider.toString(), stage, output))).redirectErrorStream(true);
        builder.environment().put("JAVA_BIN", fakeJava.toString());
        builder.environment().put("FAKE_LOG", log.toString());
        builder.environment().put("BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT",
                EXPECTED_AUTHORITY_BINDING);
        builder.environment().putAll(pinEnvironment(jar, provider.toString(), stage));
        Path childPidFile = caseDirectory.resolve("child.pid");
        Path childStatusFile = caseDirectory.resolve("child.status");
        builder.environment().put("FAKE_CHILD_PID_FILE", childPidFile.toString());
        builder.environment().put("FAKE_CHILD_STATUS", childStatusFile.toString());
        Process process = builder.start();
        try {
            await(() -> (Files.exists(childPidFile) && hasTemporaryDirectory(caseDirectory))
                    || !process.isAlive());
            if (!Files.exists(childPidFile)) {
                throw new AssertionError("script exited before the child became ready: "
                        + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            long childPid = Long.parseLong(Files.readString(childPidFile).trim());
            process.destroy();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isEqualTo(2);
            if (expectsGracefulStatus) {
                assertThat(Files.readString(childStatusFile)).isEqualTo("terminated\n");
            } else {
                assertThat(childStatusFile).doesNotExist();
            }
            assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
                    .isFalse();
            assertNoTemporaryDirectories(caseDirectory);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void temporaryDirectoryFailureEmitsOnlyTheStableErrorCode() throws Exception {
        String secret = "temporary-directory-secret";
        Path caseDirectory = Files.createDirectory(temp.resolve("mktemp-failure"));
        Path fakeBin = Files.createDirectory(caseDirectory.resolve("bin"));
        Path fakeMktemp = fakeBin.resolve("mktemp");
        Files.writeString(fakeMktemp, "#!/usr/bin/env bash\nprintf '%s\\n' \"$FAKE_SECRET\" >&2\nexit 1\n");
        Files.setPosixFilePermissions(fakeMktemp, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Path log = caseDirectory.resolve("calls.log");
        Map<String, String> environment = new java.util.HashMap<>(Map.of(
                "JAVA_BIN", fakeJava(caseDirectory, "success").toString(),
                "FAKE_LOG", log.toString(),
                "BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT", EXPECTED_AUTHORITY_BINDING,
                "FAKE_SECRET", secret,
                "PATH", fakeBin + ":" + System.getenv("PATH")));
        Path jar = regularFile(caseDirectory, "kit.jar", "kit");
        Path provider = regularFile(caseDirectory, "provider.jar", "provider");
        Path stage = regularFile(caseDirectory, "stage.json", "{}");
        environment.putAll(pinEnvironment(jar, provider.toString(), stage));

        ProcessResult result = process(withScript(arguments(
                jar,
                provider.toString(),
                stage,
                caseDirectory.resolve("report.json"))), environment);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).isEqualTo("ERROR code=TEMP\n").doesNotContain(secret);
        assertThat(log).doesNotExist();
    }

    @Test
    void childOutputNeverLeaksSecretsAndTemporaryFilesAreRemovedOnFailure() throws Exception {
        String secret = "customer-payload-secret";
        Path caseDirectory = Files.createDirectory(temp.resolve("secret-case"));
        Path fakeJava = fakeJava(caseDirectory, "secret");
        Path log = caseDirectory.resolve("calls.log");
        Path jar = regularFile(caseDirectory, "kit.jar", "kit");
        Path provider = regularFile(caseDirectory, "provider.jar", "provider");
        Path stage = regularFile(caseDirectory, "stage.json", "{}");
        Path output = caseDirectory.resolve("report.json");

        ProcessResult result = run(fakeJava, log, jar, provider.toString(), stage, output,
                Map.of("FAKE_SECRET", secret));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.output()).doesNotContain(secret);
        List<String> calls = Files.readAllLines(log);
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst()).startsWith(CONFORMANCE_MAIN + "|");
        try (Stream<Path> entries = Files.list(caseDirectory)) {
            assertThat(entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".capability-studio-acceptance.")))
                    .isEmpty();
        }
    }

    private void assertNoTemporaryDirectories(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            assertThat(entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".capability-studio-acceptance.")))
                    .isEmpty();
        }
    }

    private boolean hasTemporaryDirectory(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".capability-studio-acceptance."));
        } catch (IOException failure) {
            return false;
        }
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("condition was not observed before timeout");
    }

    private List<FakeInvocation> invocations(Path log) throws IOException {
        return Files.readAllLines(log).stream().map(line -> {
            String[] fields = line.split("\\|", -1);
            if (fields.length != 3) {
                throw new AssertionError("invalid fake invocation log");
            }
            return new FakeInvocation(fields[0], fields[1], fields[2]);
        }).toList();
    }

    private ProcessResult run(Path fakeJava, Path log, Path jar, Path provider, Path stage,
                              Path output) throws Exception {
        return run(fakeJava, log, jar, provider.toString(), stage, output, Map.of());
    }

    private ProcessResult run(Path fakeJava, Path log, Path jar, Path providerOne,
                              Path providerTwo, Path stage, Path output) throws Exception {
        return run(fakeJava, log, jar, providerOne + ":" + providerTwo, stage, output, Map.of());
    }

    private ProcessResult run(Path fakeJava, Path log, Path jar, String providerClasspath,
                              Path stage, Path output, Map<String, String> extraEnvironment)
            throws Exception {
        var command = List.of("bash", scriptPath().toString(),
                "--test-kit-jar", jar.toString(),
                "--provider-classpath", providerClasspath,
                "--stage-result", stage.toString(),
                "--conformance-output", output.toString());
        var environment = new java.util.HashMap<String, String>();
        environment.put("JAVA_BIN", fakeJava.toString());
        environment.put("FAKE_LOG", log.toString());
        environment.put("BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT", EXPECTED_AUTHORITY_BINDING);
        environment.putAll(pinEnvironment(jar, providerClasspath, stage));
        environment.putAll(extraEnvironment);
        return process(command, environment);
    }

    private void assertPreflightFailure(ProcessResult result, Path forbiddenPath) {
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).doesNotContain(forbiddenPath.toString());
    }

    private Path fakeJava(String mode) throws IOException {
        return fakeJava(temp, mode);
    }

    private Path fakeJava(Path directory, String mode) throws IOException {
        Path script = directory.resolve("fake-java-" + mode + ".sh");
        String source = "#!/usr/bin/env bash\n"
                + "set -u\n"
                + "if [[ \"$3\" == \"" + CONFORMANCE_MAIN + "\" ]]; then effective_stage=\"$5\"; else effective_stage=\"$4\"; fi\n"
                + "printf '%s|%s|%s\\n' \"$3\" \"$2\" \"$effective_stage\" >> \"$FAKE_LOG\"\n"
                + "if [[ \"$3\" == \"" + CONFORMANCE_MAIN + "\" ]]; then\n"
                + "  case \"${FAKE_MODE:-" + mode + "}\" in\n"
                + "    success|success-secret|formal-exit-3|spoof-formal|multiline-formal|mismatch-formal) [[ \"${FAKE_MODE:-" + mode + "}\" == success-secret ]] && printf '%s\\n' \"$FAKE_SECRET\" >&2; printf '%s\\n' 'CONFORMANT verdict=CONFORMANT checkCount=7 challengeCount=1 authorityBindingFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa reportFingerprint=sha256:0000000000000000000000000000000000000000000000000000000000000000'; printf '%s\\n' '{\"ok\":true}' > \"$7\" ;;\n"
                + "    mutate-sources) printf '%s' changed-kit > \"$SOURCE_TEST_KIT\"; printf '%s' changed-provider > \"$SOURCE_PROVIDER\"; printf '%s' changed-stage > \"$SOURCE_STAGE\"; printf '%s\\n' 'CONFORMANT verdict=CONFORMANT checkCount=7 challengeCount=1 authorityBindingFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa reportFingerprint=sha256:0000000000000000000000000000000000000000000000000000000000000000'; printf '%s\\n' '{\"ok\":true}' > \"$7\" ;;\n"
                + "    mutate-snapshot) chmod 600 \"$5\"; printf '%s' changed-snapshot > \"$5\"; printf '%s\\n' 'CONFORMANT verdict=CONFORMANT checkCount=7 challengeCount=1 authorityBindingFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa reportFingerprint=sha256:0000000000000000000000000000000000000000000000000000000000000000'; printf '%s\\n' '{\"ok\":true}' > \"$7\" ;;\n"
                + "    missing-report) printf '%s\\n' 'CONFORMANT verdict=CONFORMANT checkCount=7 challengeCount=1 authorityBindingFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa reportFingerprint=sha256:0000000000000000000000000000000000000000000000000000000000000000' ;;\n"
                + "    empty-report) printf '%s\\n' 'CONFORMANT verdict=CONFORMANT checkCount=7 challengeCount=1 authorityBindingFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa reportFingerprint=sha256:0000000000000000000000000000000000000000000000000000000000000000'; : > \"$7\" ;;\n"
                + "    oversized-report) printf '%s\\n' 'CONFORMANT verdict=CONFORMANT checkCount=7 challengeCount=1 authorityBindingFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa reportFingerprint=sha256:0000000000000000000000000000000000000000000000000000000000000000'; dd if=/dev/zero of=\"$7\" bs=131073 count=1 2>/dev/null ;;\n"
                + "    symlink-report) printf '%s\\n' 'CONFORMANT verdict=CONFORMANT checkCount=7 challengeCount=1 authorityBindingFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa reportFingerprint=sha256:0000000000000000000000000000000000000000000000000000000000000000'; printf '%s\\n' target > \"$7-target\"; ln -s \"$7-target\" \"$7\" ;;\n"
                + "    conformance-exit-3) printf '%s\\n' blocked-report > \"$7\"; exit 3 ;;\n"
                + "    spoof-conformance) printf '%s\\n' 'CONFORMANT verdict=CONFORMANT checkCount=7 challengeCount=1 authorityBindingFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa reportFingerprint=sha256:not-a-fingerprint'; exit 0 ;;\n"
                + "    multiline-conformance) printf '%s\\n%s\\n' 'CONFORMANT verdict=CONFORMANT checkCount=7 challengeCount=1 authorityBindingFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa reportFingerprint=sha256:0000000000000000000000000000000000000000000000000000000000000000' 'unexpected'; exit 0 ;;\n"
                + "    mismatch-conformance) printf '%s\\n' 'CONFORMANT verdict=CONFORMANT checkCount=7 challengeCount=1 authorityBindingFingerprint=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb reportFingerprint=sha256:0000000000000000000000000000000000000000000000000000000000000000'; printf '%s\\n' '{\"ok\":true}' > \"$7\" ;;\n"
                + "    secret) printf '%s\\n' \"$FAKE_SECRET\" >&2; exit 3 ;;\n"
                + "    block) trap 'printf \"%s\\n\" terminated > \"$FAKE_CHILD_STATUS\"; exit 42' TERM; printf '%s\\n' \"$$\" > \"$FAKE_CHILD_PID_FILE\"; while :; do sleep 1; done ;;\n"
                + "    ignore-term) trap '' TERM; printf '%s\\n' \"$$\" > \"$FAKE_CHILD_PID_FILE\"; while :; do sleep 1; done ;;\n"
                + "  esac\n"
                + "else\n"
                + "  case \"${FAKE_MODE:-" + mode + "}\" in\n"
                + "    success|success-secret) [[ \"${FAKE_MODE:-" + mode + "}\" == success-secret ]] && printf '%s\\n' \"$FAKE_SECRET\" >&2; printf '%s\\n' 'ACCEPTED outcome=ACCEPTED authorityBindingFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa reasonCode=RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_AUTHORITY.ACCEPTED' ;;\n"
                + "    mismatch-formal) printf '%s\\n' 'ACCEPTED outcome=ACCEPTED authorityBindingFingerprint=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb reasonCode=RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_AUTHORITY.ACCEPTED' ;;\n"
                + "    mutate-sources) test_kit_snapshot=${2%%:*}; provider_snapshot=${2#*:}; [[ \"$(cat \"$test_kit_snapshot\")\" == kit && \"$(cat \"$provider_snapshot\")\" == provider && \"$(cat \"$4\")\" == '{}' ]] || exit 2; printf '%s\\n' 'ACCEPTED outcome=ACCEPTED authorityBindingFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa reasonCode=RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_AUTHORITY.ACCEPTED' ;;\n"
                + "    formal-exit-3) exit 3 ;;\n"
                + "    spoof-formal) printf '%s\\n' 'ACCEPTED outcome=ACCEPTED authorityBindingFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa reasonCode=RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_AUTHORITY./unsafe' ;;\n"
                + "    multiline-formal) printf '%s\\n%s\\n' 'ACCEPTED outcome=ACCEPTED authorityBindingFingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa reasonCode=RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_AUTHORITY.ACCEPTED' 'unexpected' ;;\n"
                + "    mismatch-formal) printf '%s\\n' 'ACCEPTED outcome=ACCEPTED authorityBindingFingerprint=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb reasonCode=RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_AUTHORITY.ACCEPTED' ;;\n"
                + "  esac\n"
                + "fi\n";
        Files.writeString(script, source, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    private Path regularFile(String name, String content) throws IOException {
        return regularFile(temp, name, content);
    }

    private Path regularFile(Path directory, String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Map<String, String> pinEnvironment(Path jar, String providerClasspath, Path stage)
            throws Exception {
        String[] providers = providerClasspath.split(":", -1);
        StringBuilder providerPins = new StringBuilder();
        if (providerClasspath.isEmpty()) {
            providerPins.append("0".repeat(64));
        } else {
            for (String provider : providers) {
                if (providerPins.length() > 0) {
                    providerPins.append(',');
                }
                providerPins.append(pin(Path.of(provider)));
            }
        }
        return Map.of(
                TEST_KIT_PIN_ENV, pin(jar),
                STAGE_RESULT_PIN_ENV, pin(stage),
                PROVIDER_PINS_ENV, providerPins.toString());
    }

    private static String pin(Path file) throws Exception {
        return Files.isRegularFile(file) && !Files.isSymbolicLink(file)
                ? sha256(file)
                : "0".repeat(64);
    }

    private static String sha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private Path fakeCp(Path bin) throws IOException {
        Path script = bin.resolve("cp");
        Files.writeString(script, "#!/usr/bin/env bash\n"
                + "set -u\n"
                + "if [[ ! -e \"$FAKE_CP_MARKER\" ]]; then\n"
                + "  printf '%s' changed-before-copy > \"$SOURCE_TO_MUTATE\"\n"
                + "  : > \"$FAKE_CP_MARKER\"\n"
                + "fi\n"
                + "exec \"$REAL_CP\" \"$@\"\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    private Path scriptPath() {
        return Path.of(SCRIPT).toAbsolutePath().normalize();
    }

    private List<String> arguments(Path jar, String providerClasspath, Path stage, Path output) {
        return List.of("--test-kit-jar", jar.toString(),
                "--provider-classpath", providerClasspath,
                "--stage-result", stage.toString(),
                "--conformance-output", output.toString());
    }

    private List<String> withScript(List<String> arguments) {
        var command = new java.util.ArrayList<String>();
        command.add("bash");
        command.add(scriptPath().toString());
        command.addAll(arguments);
        return List.copyOf(command);
    }

    private ProcessResult process(List<String> command, Map<String, String> environment)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), output);
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private record FakeInvocation(String main, String classpath, String stageResult) {
    }
}
