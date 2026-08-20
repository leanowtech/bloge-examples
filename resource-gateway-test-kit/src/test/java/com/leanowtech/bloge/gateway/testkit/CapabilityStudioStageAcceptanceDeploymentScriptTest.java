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
import java.util.LinkedHashMap;
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
    private static final String AUTHORITY_MATERIAL = fingerprint('a');
    private static final String TRUSTED_CLOCK_MATERIAL = fingerprint('b');
    private static final String LIFECYCLE_AUTHORITY_MATERIAL = fingerprint('c');
    private static final String LEASE_AUTHORITY_MATERIAL = fingerprint('d');
    private static final String TARGET_ADMISSION_MATERIAL = fingerprint('e');
    private static final String TARGET_RAW = fingerprint('f');
    private static final String TARGET_CANONICAL = fingerprint('1');
    private static final String LIFECYCLE_MATERIAL = fingerprint('2');
    private static final String REVOCATION_SNAPSHOT = fingerprint('3');
    private static final String LEASE_RECEIPT = fingerprint('4');
    private static final String DEPLOYMENT_AUTHORITY_MATERIAL = sha256Text(
            "{\"messageVersion\":\"resource-gateway.capability-studio."
                    + "deployment-admission-authority-binding.v1\","
                    + "\"trustedClockMaterialFingerprint\":\"" + TRUSTED_CLOCK_MATERIAL
                    + "\",\"admissionLifecycleAuthorityMaterialFingerprint\":\""
                    + LIFECYCLE_AUTHORITY_MATERIAL
                    + "\",\"executionLeaseAuthorityMaterialFingerprint\":\""
                    + LEASE_AUTHORITY_MATERIAL + "\"}");
    private static final String FORMAL_OUTER = sha256Text(
            "{\"messageVersion\":\"resource-gateway.capability-studio."
                    + "stage-acceptance-provider-binding.v2\","
                    + "\"authorityMaterialFingerprint\":\"" + AUTHORITY_MATERIAL
                    + "\",\"deploymentAdmissionAuthorityMaterialFingerprint\":\""
                    + DEPLOYMENT_AUTHORITY_MATERIAL
                    + "\",\"targetAdmissionMaterialFingerprint\":\""
                    + TARGET_ADMISSION_MATERIAL + "\",\"targetRawFingerprint\":\""
                    + TARGET_RAW + "\",\"targetCanonicalFingerprint\":\""
                    + TARGET_CANONICAL + "\"}");
    private static final String AUTHORITY_PIN_ENV =
            "BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT";
    private static final String FORMAL_PIN_ENV =
            "BLOGE_EXPECTED_FORMAL_AUTHORITY_BINDING_FINGERPRINT";
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
                        AUTHORITY_PIN_ENV, AUTHORITY_MATERIAL));
        ProcessResult help = process(List.of("bash", scriptPath().toString(), "--help"),
                Map.of("JAVA_BIN", fakeJava.toString(), "FAKE_LOG", log.toString(),
                        AUTHORITY_PIN_ENV, AUTHORITY_MATERIAL));

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
    void authorityMaterialPinIsRequiredAndValidatedBeforeJava() throws Exception {
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
                environment.put(AUTHORITY_PIN_ENV, pin);
            }
            environment.put(FORMAL_PIN_ENV, FORMAL_OUTER);
            ProcessResult result = process(withScript(arguments(jar, provider.toString(), stage,
                    output)), environment);
            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.output()).isEqualTo("ERROR code=AUTHORITY_BINDING\n")
                    .doesNotContain(jar.toString(), provider.toString(), stage.toString());
            assertThat(log).doesNotExist();
        }
    }

    @Test
    void formalOuterPinIsRequiredAndValidatedSeparatelyBeforeJava() throws Exception {
        Path fakeJava = fakeJava("success");
        Path log = temp.resolve("formal-pin-calls.log");
        Path jar = regularFile("formal-pin-test-kit.jar", "jar");
        Path provider = regularFile("formal-pin-provider.jar", "provider");
        Path stage = regularFile("formal-pin-stage.json", "{}");
        Path output = temp.resolve("formal-pin-report.json");
        Map<String, String> validPins = pinEnvironment(jar, provider.toString(), stage);
        for (String pin : java.util.Arrays.asList(null, "", "sha256:" + "A".repeat(64))) {
            Map<String, String> environment = new java.util.HashMap<>(validPins);
            environment.put("JAVA_BIN", fakeJava.toString());
            environment.put("FAKE_LOG", log.toString());
            environment.put(AUTHORITY_PIN_ENV, AUTHORITY_MATERIAL);
            if (pin != null) {
                environment.put(FORMAL_PIN_ENV, pin);
            }
            ProcessResult result = process(withScript(arguments(jar, provider.toString(), stage,
                    output)), environment);
            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.output()).isEqualTo("ERROR code=FORMAL_AUTHORITY_BINDING\n")
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
        validPins.put(AUTHORITY_PIN_ENV, AUTHORITY_MATERIAL);
        validPins.put(FORMAL_PIN_ENV, FORMAL_OUTER);
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
                AUTHORITY_PIN_ENV, AUTHORITY_MATERIAL);
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
                "ACCEPTED status=ACCEPTED authorityMaterialFingerprint="
                        + AUTHORITY_MATERIAL + " formalOuterFingerprint=" + FORMAL_OUTER
                        + " providerConformanceFingerprint=" + fingerprint('0')
                        + " leaseCommitStatus=COMMITTED leaseReceiptFingerprint="
                        + LEASE_RECEIPT + " formalTranscriptFingerprint="
                        + sha256Text(formalLine("COMMITTED") + "\n") + "\n");
        List<FakeInvocation> calls = invocations(log);
        assertThat(calls).extracting(FakeInvocation::main)
                .containsExactly(CONFORMANCE_MAIN, FORMAL_MAIN);
        assertThat(calls).extracting(FakeInvocation::authorityPin)
                .containsExactly(AUTHORITY_MATERIAL, FORMAL_OUTER);
        assertThat(calls).extracting(FakeInvocation::formalDeploymentPin)
                .containsOnly("<absent>");
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
    void recoveredLeasePreservesTheExpandedTranscriptAndFinalStatus() throws Exception {
        Path fakeJava = fakeJava("recovered");
        Path log = temp.resolve("recovered-calls.log");
        Path jar = regularFile("recovered-kit.jar", "kit");
        Path provider = regularFile("recovered-provider.jar", "provider");
        Path stage = regularFile("recovered-stage.json", "{}");

        ProcessResult result = run(fakeJava, log, jar, provider, stage,
                temp.resolve("recovered-report.json"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo(
                "ACCEPTED status=ACCEPTED authorityMaterialFingerprint="
                        + AUTHORITY_MATERIAL + " formalOuterFingerprint=" + FORMAL_OUTER
                        + " providerConformanceFingerprint=" + fingerprint('0')
                        + " leaseCommitStatus=RECOVERED leaseReceiptFingerprint="
                        + LEASE_RECEIPT + " formalTranscriptFingerprint="
                        + sha256Text(formalLine("RECOVERED") + "\n") + "\n");
        List<FakeInvocation> calls = invocations(log);
        assertThat(calls).extracting(FakeInvocation::authorityPin)
                .containsExactly(AUTHORITY_MATERIAL, FORMAL_OUTER);
        assertThat(calls).extracting(FakeInvocation::formalDeploymentPin)
                .containsOnly("<absent>");
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
    void nulBytesInChildStdoutAreRejectedByExactByteComparison() throws Exception {
        for (String mode : List.of("nul-conformance", "nul-formal")) {
            Path caseDirectory = Files.createDirectory(temp.resolve(mode));
            Path log = caseDirectory.resolve("calls.log");
            ProcessResult result = run(fakeJava(caseDirectory, mode), log,
                    regularFile(caseDirectory, "kit.jar", "kit"),
                    regularFile(caseDirectory, "provider.jar", "provider"),
                    regularFile(caseDirectory, "stage.json", "{}"),
                    caseDirectory.resolve("report.json"));

            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.output()).doesNotContain("ACCEPTED status=ACCEPTED");
            assertThat(invocations(log)).extracting(FakeInvocation::main)
                    .containsExactlyElementsOf(mode.equals("nul-formal")
                            ? List.of(CONFORMANCE_MAIN, FORMAL_MAIN)
                            : List.of(CONFORMANCE_MAIN));
        }
    }

    @Test
    void everyFormalFingerprintFieldRequiresStrictLowercaseSha256() throws Exception {
        List<String> fingerprintFields = List.of(
                "authorityBindingFingerprint", "authorityMaterialFingerprint",
                "leaseReceiptFingerprint", "targetAdmissionMaterialFingerprint",
                "deploymentAdmissionAuthorityMaterialFingerprint", "targetRawFingerprint",
                "targetCanonicalFingerprint", "trustedClockMaterialFingerprint",
                "admissionLifecycleAuthorityMaterialFingerprint",
                "executionLeaseAuthorityMaterialFingerprint", "lifecycleMaterialFingerprint",
                "revocationSnapshotFingerprint");
        for (String field : fingerprintFields) {
            ProcessResult result = runWithFormalOutput("malformed-" + field,
                    replaceField(formalLine("COMMITTED"), field,
                            "sha256:" + "A".repeat(64)) + "\n");
            assertThat(result.exitCode()).isEqualTo(2);
        }
    }

    @Test
    void formalTranscriptRejectsReorderExtraMissingWrongReasonAndLineShape()
            throws Exception {
        String valid = formalLine("COMMITTED");
        Map<String, String> malformed = new LinkedHashMap<>();
        malformed.put("reordered", valid.replace(
                "authorityBindingFingerprint=" + FORMAL_OUTER
                        + " authorityMaterialFingerprint=" + AUTHORITY_MATERIAL,
                "authorityMaterialFingerprint=" + AUTHORITY_MATERIAL
                        + " authorityBindingFingerprint=" + FORMAL_OUTER) + "\n");
        malformed.put("extra", valid.replace(" reasonCode=",
                " unexpected=" + fingerprint('6') + " reasonCode=") + "\n");
        malformed.put("missing", valid.replace(
                " targetRawFingerprint=" + TARGET_RAW, "") + "\n");
        malformed.put("wrong-reason", valid.replace(
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI.ACCEPTED",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_AUTHORITY.ACCEPTED") + "\n");
        malformed.put("multiple-lines", valid + "\nunexpected\n");
        malformed.put("no-newline", valid);
        for (Map.Entry<String, String> entry : malformed.entrySet()) {
            assertThat(runWithFormalOutput(entry.getKey(), entry.getValue()).exitCode())
                    .isEqualTo(2);
        }
    }

    @Test
    void canonicalDeploymentAndFormalAggregateDriftFailsClosed() throws Exception {
        ProcessResult deployment = runWithFormalOutput("deployment-aggregate-drift",
                replaceField(formalLine("COMMITTED"),
                        "deploymentAdmissionAuthorityMaterialFingerprint",
                        fingerprint('5')) + "\n");
        ProcessResult formal = runWithFormalOutput("formal-aggregate-drift",
                replaceField(formalLine("COMMITTED"),
                        "targetRawFingerprint", fingerprint('5')) + "\n");

        assertThat(deployment.exitCode()).isEqualTo(2);
        assertThat(deployment.output())
                .isEqualTo("ERROR code=DEPLOYMENT_AUTHORITY_AGGREGATE_MISMATCH\n");
        assertThat(formal.exitCode()).isEqualTo(2);
        assertThat(formal.output())
                .isEqualTo("ERROR code=FORMAL_AUTHORITY_AGGREGATE_MISMATCH\n");
    }

    @Test
    void eachJavaPhaseMustMatchItsOwnOutOfBandDeploymentPin() throws Exception {
        for (String mode : List.of(
                "mismatch-conformance", "mismatch-formal", "mismatch-material")) {
            Path caseDirectory = Files.createDirectory(temp.resolve(mode));
            Path fakeJava = fakeJava(caseDirectory, mode);
            Path log = caseDirectory.resolve("calls.log");
            Path jar = regularFile(caseDirectory, "kit.jar", "kit");
            Path provider = regularFile(caseDirectory, "provider.jar", "provider");
            Path stage = regularFile(caseDirectory, "stage.json", "{}");
            ProcessResult result = run(fakeJava, log, jar, provider, stage,
                    caseDirectory.resolve("report.json"));

            assertThat(result.exitCode()).isEqualTo(2);
            String code = mode.equals("mismatch-formal")
                    ? "FORMAL_AUTHORITY_BINDING_MISMATCH" : "AUTHORITY_BINDING_MISMATCH";
            assertThat(result.output()).isEqualTo("ERROR code=" + code + "\n")
                    .doesNotContain(jar.toString(), provider.toString(), stage.toString());
            assertThat(invocations(log)).hasSize(
                    mode.equals("mismatch-conformance") ? 1 : 2);
        }
    }

    @Test
    void swappedOrDriftedSplitPinsFailAtTheirOwnPhase() throws Exception {
        Path jar = regularFile("swapped-kit.jar", "kit");
        Path provider = regularFile("swapped-provider.jar", "provider");
        Path stage = regularFile("swapped-stage.json", "{}");
        for (boolean swap : List.of(false, true)) {
            Path caseDirectory = Files.createDirectory(temp.resolve("pin-drift-" + swap));
            Path log = caseDirectory.resolve("calls.log");
            Map<String, String> overrides = swap
                    ? Map.of(AUTHORITY_PIN_ENV, FORMAL_OUTER,
                            FORMAL_PIN_ENV, AUTHORITY_MATERIAL)
                    : Map.of(FORMAL_PIN_ENV, fingerprint('9'));

            ProcessResult result = run(fakeJava(caseDirectory, "success"), log, jar,
                    provider.toString(), stage, caseDirectory.resolve("report.json"), overrides);

            assertThat(result.exitCode()).isEqualTo(2);
            assertThat(result.output()).isEqualTo("ERROR code="
                    + (swap ? "AUTHORITY_BINDING_MISMATCH"
                    : "FORMAL_AUTHORITY_BINDING_MISMATCH") + "\n");
            assertThat(invocations(log)).hasSize(swap ? 1 : 2);
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
    void finalOutputWriteFailureFailsClosed() throws Exception {
        Path caseDirectory = Files.createDirectory(temp.resolve("closed-output"));
        Path fakeJava = fakeJava(caseDirectory, "success");
        Path log = caseDirectory.resolve("calls.log");
        Path jar = regularFile(caseDirectory, "kit.jar", "kit");
        Path provider = regularFile(caseDirectory, "provider.jar", "provider");
        Path stage = regularFile(caseDirectory, "stage.json", "{}");
        List<String> command = new java.util.ArrayList<>(List.of(
                "bash", "-c", "exec 1>&-; exec bash \"$@\"", "closed-output",
                scriptPath().toString()));
        command.addAll(arguments(jar, provider.toString(), stage,
                caseDirectory.resolve("report.json")));
        Map<String, String> environment = new java.util.HashMap<>(
                pinEnvironment(jar, provider.toString(), stage));
        environment.put("JAVA_BIN", fakeJava.toString());
        environment.put("FAKE_LOG", log.toString());
        environment.put(AUTHORITY_PIN_ENV, AUTHORITY_MATERIAL);
        environment.put(FORMAL_PIN_ENV, FORMAL_OUTER);

        ProcessResult result = process(command, environment);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).isEmpty();
        assertThat(invocations(log)).hasSize(2);
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
        builder.environment().put(AUTHORITY_PIN_ENV, AUTHORITY_MATERIAL);
        builder.environment().put(FORMAL_PIN_ENV, FORMAL_OUTER);
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
                AUTHORITY_PIN_ENV, AUTHORITY_MATERIAL,
                FORMAL_PIN_ENV, FORMAL_OUTER,
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
            if (fields.length != 5) {
                throw new AssertionError("invalid fake invocation log");
            }
            return new FakeInvocation(fields[0], fields[1], fields[2], fields[3], fields[4]);
        }).toList();
    }

    private ProcessResult runWithFormalOutput(String name, String formalOutput)
            throws Exception {
        Path caseDirectory = Files.createDirectory(temp.resolve("formal-output-" + name));
        Path log = caseDirectory.resolve("calls.log");
        ProcessResult result = run(fakeJava(caseDirectory, "success"), log,
                regularFile(caseDirectory, "kit.jar", "kit"),
                regularFile(caseDirectory, "provider.jar", "provider").toString(),
                regularFile(caseDirectory, "stage.json", "{}"),
                caseDirectory.resolve("report.json"),
                Map.of("FAKE_FORMAL_OUTPUT", formalOutput));
        assertThat(invocations(log)).hasSize(2);
        return result;
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
        environment.put(AUTHORITY_PIN_ENV, AUTHORITY_MATERIAL);
        environment.put(FORMAL_PIN_ENV, FORMAL_OUTER);
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
                + "formal_committed='" + formalLine("COMMITTED") + "'\n"
                + "formal_recovered='" + formalLine("RECOVERED") + "'\n"
                + "if [[ \"$3\" == \"" + CONFORMANCE_MAIN + "\" ]]; then effective_stage=\"$5\"; else effective_stage=\"$4\"; fi\n"
                + "printf '%s|%s|%s|%s|%s\\n' \"$3\" \"$2\" \"$effective_stage\" \"$BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT\" \"${BLOGE_EXPECTED_FORMAL_AUTHORITY_BINDING_FINGERPRINT-<absent>}\" >> \"$FAKE_LOG\"\n"
                + "if [[ \"$3\" == \"" + CONFORMANCE_MAIN + "\" ]]; then\n"
                + "  case \"${FAKE_MODE:-" + mode + "}\" in\n"
                + "    success|success-secret|recovered|formal-exit-3|spoof-formal|multiline-formal|mismatch-formal|mismatch-material|nul-formal) [[ \"${FAKE_MODE:-" + mode + "}\" == success-secret ]] && printf '%s\\n' \"$FAKE_SECRET\" >&2; printf '%s\\n' 'CONFORMANT verdict=CONFORMANT checkCount=7 challengeCount=1 authorityBindingFingerprint=" + AUTHORITY_MATERIAL + " reportFingerprint=" + fingerprint('0') + "'; printf '%s\\n' '{\"ok\":true}' > \"$7\" ;;\n"
                + "    nul-conformance) printf 'CONFOR\\0MANT verdict=CONFORMANT checkCount=7 challengeCount=1 authorityBindingFingerprint=" + AUTHORITY_MATERIAL + " reportFingerprint=" + fingerprint('0') + "\\n'; printf '%s\\n' '{\"ok\":true}' > \"$7\" ;;\n"
                + "    mutate-sources) printf '%s' changed-kit > \"$SOURCE_TEST_KIT\"; printf '%s' changed-provider > \"$SOURCE_PROVIDER\"; printf '%s' changed-stage > \"$SOURCE_STAGE\"; printf '%s\\n' 'CONFORMANT verdict=CONFORMANT checkCount=7 challengeCount=1 authorityBindingFingerprint=" + AUTHORITY_MATERIAL + " reportFingerprint=" + fingerprint('0') + "'; printf '%s\\n' '{\"ok\":true}' > \"$7\" ;;\n"
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
                + "  if [[ \"${FAKE_FORMAL_OUTPUT+x}\" == x ]]; then printf '%s' \"$FAKE_FORMAL_OUTPUT\"; exit 0; fi\n"
                + "  case \"${FAKE_MODE:-" + mode + "}\" in\n"
                + "    success|success-secret) [[ \"${FAKE_MODE:-" + mode + "}\" == success-secret ]] && printf '%s\\n' \"$FAKE_SECRET\" >&2; printf '%s\\n' \"$formal_committed\" ;;\n"
                + "    recovered) printf '%s\\n' \"$formal_recovered\" ;;\n"
                + "    mismatch-formal) printf '%s\\n' '" + replaceField(formalLine("COMMITTED"), "authorityBindingFingerprint", fingerprint('9')) + "' ;;\n"
                + "    mismatch-material) printf '%s\\n' '" + replaceField(formalLine("COMMITTED"), "authorityMaterialFingerprint", fingerprint('9')) + "' ;;\n"
                + "    mutate-sources) test_kit_snapshot=${2%%:*}; provider_snapshot=${2#*:}; [[ \"$(cat \"$test_kit_snapshot\")\" == kit && \"$(cat \"$provider_snapshot\")\" == provider && \"$(cat \"$4\")\" == '{}' ]] || exit 2; printf '%s\\n' \"$formal_committed\" ;;\n"
                + "    formal-exit-3) exit 3 ;;\n"
                + "    spoof-formal) printf '%s\\n' '" + formalLine("COMMITTED").replace("RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI.ACCEPTED", "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI./unsafe") + "' ;;\n"
                + "    multiline-formal) printf '%s\\n%s\\n' \"$formal_committed\" unexpected ;;\n"
                + "    nul-formal) printf '" + formalLine("COMMITTED").replace(
                        "ACCEPTED outcome=ACCEPTED", "ACCEPTED outcome=ACC\\0EPTED")
                        + "\\n' ;;\n"
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

    private static String sha256Text(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return "sha256:" + hex;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static String formalLine(String leaseStatus) {
        return "ACCEPTED outcome=ACCEPTED authorityBindingFingerprint=" + FORMAL_OUTER
                + " authorityMaterialFingerprint=" + AUTHORITY_MATERIAL
                + " leaseCommitStatus=" + leaseStatus
                + " leaseReceiptFingerprint=" + LEASE_RECEIPT
                + " targetAdmissionMaterialFingerprint=" + TARGET_ADMISSION_MATERIAL
                + " deploymentAdmissionAuthorityMaterialFingerprint="
                + DEPLOYMENT_AUTHORITY_MATERIAL
                + " targetRawFingerprint=" + TARGET_RAW
                + " targetCanonicalFingerprint=" + TARGET_CANONICAL
                + " trustedClockMaterialFingerprint=" + TRUSTED_CLOCK_MATERIAL
                + " admissionLifecycleAuthorityMaterialFingerprint="
                + LIFECYCLE_AUTHORITY_MATERIAL
                + " executionLeaseAuthorityMaterialFingerprint=" + LEASE_AUTHORITY_MATERIAL
                + " lifecycleMaterialFingerprint=" + LIFECYCLE_MATERIAL
                + " revocationSnapshotFingerprint=" + REVOCATION_SNAPSHOT
                + " reasonCode=RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI.ACCEPTED";
    }

    private static String replaceField(String line, String field, String replacement) {
        return line.replaceFirst(field + "=[^ ]+", field + "=" + replacement);
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

    private record FakeInvocation(
            String main, String classpath, String stageResult, String authorityPin,
            String formalDeploymentPin) {
    }
}
