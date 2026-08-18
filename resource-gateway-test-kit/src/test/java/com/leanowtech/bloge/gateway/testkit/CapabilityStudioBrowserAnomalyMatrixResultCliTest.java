package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioBrowserAnomalyMatrixResultCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String OTHER_FINGERPRINT = "sha256:" + "b".repeat(64);
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-08-18T00:00:00Z");
    private static final OffsetDateTime END = OffsetDateTime.parse("2026-08-18T01:00:00Z");

    @Test
    void returnsCompleteForAnomalyBoundToIndependentlyVerifiedBase(@TempDir Path tempDir)
            throws Exception {
        ObjectNode base = completeBase("BMR-cli", FINGERPRINT, "CLEAN");
        ObjectNode anomaly = completeAnomaly(base, "CLEAN");

        Invocation invocation = invoke(tempDir, anomaly, base);

        assertThat(invocation.exitCode()).as(invocation.stdout()).isEqualTo(0);
        assertThat(invocation.stdout()).isEqualTo("VALID status=COMPLETE\n");
        assertThat(invocation.stderr()).isEmpty();
    }

    @Test
    void returnsNotRunForAValidUnexecutedAnomaly(@TempDir Path tempDir) throws Exception {
        ObjectNode base = completeBase("BMR-cli-not-run", FINGERPRINT, "CLEAN");
        ObjectNode anomaly = anomalyBuilder(base, "CLEAN").build();

        Invocation invocation = invoke(tempDir, anomaly, base);

        assertThat(invocation.exitCode()).as(invocation.stdout()).isEqualTo(3);
        assertThat(invocation.stdout()).isEqualTo("VALID status=NOT_RUN\n");
        assertThat(invocation.stderr()).isEmpty();
    }

    @Test
    void returnsFailedForAValidObservedFailure(@TempDir Path tempDir) throws Exception {
        ObjectNode base = completeBase("BMR-cli-failed", FINGERPRINT, "CLEAN");
        CapabilityStudioBrowserAnomalyMatrixResultBuilder builder = anomalyBuilder(base, "CLEAN");
        var key = CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations().get(0);
        builder.fail(key, errorTrigger(), failingBrowser(), evidence(key));

        Invocation invocation = invoke(tempDir, builder.build(), base);

        assertThat(invocation.exitCode()).as(invocation.stdout()).isEqualTo(3);
        assertThat(invocation.stdout()).isEqualTo("VALID status=FAILED\n");
        assertThat(invocation.stderr()).isEmpty();
    }

    @Test
    void rejectsDirtyCandidateBecauseExactBaseMustAlsoBeACompleteMatrix(@TempDir Path tempDir)
            throws Exception {
        ObjectNode base = completeBase("BMR-cli-dirty", FINGERPRINT, "CLEAN");
        ObjectNode anomaly = completeAnomaly(base, "DIRTY");

        Invocation invocation = invoke(tempDir, anomaly, base);

        assertThat(invocation.exitCode()).isEqualTo(2);
        assertThat(invocation.stdout()).isEqualTo(
                "INVALID errorCode=RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_BASE_MATRIX_BINDING_MISMATCH\n");
        assertThat(invocation.stdout()).doesNotContain(tempDir.toString());
        assertThat(invocation.stderr()).isEmpty();
    }

    @Test
    void rejectsTamperedAnomalyAndWrongBaseCandidateDriftWithoutLeakingInputs(@TempDir Path tempDir)
            throws Exception {
        ObjectNode base = completeBase("BMR-cli-tamper", FINGERPRINT, "CLEAN");
        ObjectNode tampered = completeAnomaly(base, "CLEAN");
        tampered.put("resultId", "BAMR-tampered");
        Invocation tamperedInvocation = invoke(tempDir, tampered, base);

        assertThat(tamperedInvocation.exitCode()).isEqualTo(2);
        assertThat(tamperedInvocation.stdout()).isEqualTo(
                "INVALID errorCode=RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_EVIDENCE_FINGERPRINT_MISMATCH\n");
        assertThat(tamperedInvocation.stdout()).doesNotContain("BAMR-tampered");
        assertThat(tamperedInvocation.stderr()).isEmpty();

        ObjectNode driftedBase = completeBase("BMR-cli-tamper", OTHER_FINGERPRINT, "CLEAN");
        Invocation driftInvocation = invoke(tempDir, completeAnomaly(base, "CLEAN"), driftedBase);

        assertThat(driftInvocation.exitCode()).isEqualTo(2);
        assertThat(driftInvocation.stdout()).isEqualTo(
                "INVALID errorCode=RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_BASE_MATRIX_BINDING_MISMATCH\n");
        assertThat(driftInvocation.stdout()).doesNotContain(OTHER_FINGERPRINT);
        assertThat(driftInvocation.stderr()).isEmpty();
    }

    @Test
    void returnsUsageAndReadErrorsWithStablePayloadFreeOutput(@TempDir Path tempDir) throws Exception {
        Invocation nullArgs = invokeArgs(null);
        assertThat(nullArgs.exitCode()).isEqualTo(2);
        assertThat(nullArgs.stdout()).isEqualTo(
                "INVALID errorCode=RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_CLI_USAGE\n");

        Invocation blankArg = invokeArgs(new String[]{" ", "base.json"});
        assertThat(blankArg.exitCode()).isEqualTo(2);
        assertThat(blankArg.stdout()).isEqualTo(
                "INVALID errorCode=RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_CLI_USAGE\n");

        String missing = tempDir.resolve("missing-result-with-secret.json").toString();
        Invocation readFailure = invokeArgs(new String[]{missing, missing});
        assertThat(readFailure.exitCode()).isEqualTo(2);
        assertThat(readFailure.stdout()).isEqualTo(
                "INVALID errorCode=RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_CLI_READ\n");
        assertThat(readFailure.stdout()).doesNotContain(missing);
        assertThat(readFailure.stderr()).isEmpty();

        Path invalid = tempDir.resolve("invalid.json");
        Files.writeString(invalid, "{not-json secret-payload", StandardCharsets.UTF_8);
        Invocation invalidJson = invokeArgs(new String[]{invalid.toString(), invalid.toString()});
        assertThat(invalidJson.exitCode()).isEqualTo(2);
        assertThat(invalidJson.stdout()).isEqualTo(
                "INVALID errorCode=RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_INVALID_JSON\n");
        assertThat(invalidJson.stdout()).doesNotContain("secret-payload", invalid.toString());
        assertThat(invalidJson.stderr()).isEmpty();
    }

    private static Invocation invoke(Path tempDir, ObjectNode anomaly, ObjectNode base)
            throws Exception {
        Path anomalyPath = tempDir.resolve("anomaly.json");
        Path basePath = tempDir.resolve("base.json");
        Files.write(anomalyPath, bytes(anomaly));
        Files.write(basePath, bytes(base));
        return invokeArgs(new String[]{anomalyPath.toString(), basePath.toString()});
    }

    private static Invocation invokeArgs(String[] args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = CapabilityStudioBrowserAnomalyMatrixResultCli.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Invocation(exit, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static ObjectNode completeAnomaly(ObjectNode base, String treeStatus) {
        CapabilityStudioBrowserAnomalyMatrixResultBuilder builder = anomalyBuilder(base, treeStatus);
        for (var key : CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations()) {
            builder.pass(key, passingBrowser(key), evidence(key));
        }
        return builder.build();
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder anomalyBuilder(
            ObjectNode base, String treeStatus) {
        return new CapabilityStudioBrowserAnomalyMatrixResultBuilder(
                "BAMR-cli-1", 1, "s0-ac-01.v1",
                new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Candidate(
                        "build/candidate-1", "candidate-revision-1", FINGERPRINT,
                        "abcdef1", CapabilityStudioBrowserAnomalyMatrixResultBuilder.SourceTreeStatus
                                .valueOf(treeStatus)),
                new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaselineRef(
                        "baseline/s0-ac-01", 1, FINGERPRINT),
                new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Environment(
                        FINGERPRINT, "chrome/stable", "chromium", "128.0", "128.0", "4.10.2"),
                new CapabilityStudioBrowserAnomalyMatrixResultBuilder.ExecutionWindow(START, END),
                new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaseMatrixRef(
                        "results/browser-matrix/" + base.path("resultId").asText(),
                        base.path("evidenceClosureFingerprint").asText(),
                        CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaseMatrixStatus.COMPLETE));
    }

    private static ObjectNode completeBase(String resultId, String fingerprint, String treeStatus) {
        var builder = new CapabilityStudioBrowserMatrixResultBuilder(
                resultId, 1, "s0-ac-01.v1",
                new CapabilityStudioBrowserMatrixResultBuilder.Candidate(
                        "build/candidate-1", "candidate-revision-1", fingerprint,
                        "abcdef1", treeStatus),
                new CapabilityStudioBrowserMatrixResultBuilder.BaselineRef(
                        "baseline/s0-ac-01", 1, fingerprint),
                new CapabilityStudioBrowserMatrixResultBuilder.Environment(
                        fingerprint, "chrome/stable", "chromium", "128.0", "128.0", "4.10.2"),
                new CapabilityStudioBrowserMatrixResultBuilder.ExecutionWindow(
                        "2026-08-18T00:00:00Z", "2026-08-18T01:00:00Z"));
        for (var key : CapabilityStudioBrowserMatrixResultBuilder.expectedCells()) {
            builder.pass(key, key.viewport(), List.of(
                    new CapabilityStudioBrowserMatrixResultBuilder.EvidenceRef(
                            "evidence/browser-matrix/" + key.cellId(), fingerprint)));
        }
        return builder.build();
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations passingBrowser(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.ObligationKey key) {
        return new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations(
                key.viewport(), false,
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.Axe.clear(), 0, 0,
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.KeyboardPath.complete(10),
                true, true, true, true, true, true, true, true, true, true, true, 0, 0);
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations failingBrowser() {
        return new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations(
                new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Viewport(1024, 768),
                true, new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Axe(1, 0),
                1, 1, CapabilityStudioBrowserAnomalyMatrixResultBuilder.KeyboardPath.notRun(),
                true, true, false, false, false, true, true, true, true, true, true, 1, 0);
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder.Trigger errorTrigger() {
        return new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Trigger(
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.TriggerMechanism.CDP_FETCH_FULFILL,
                "/api/capability-studio/demo-pack",
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.FailureClass.HTTP_5XX,
                503, true);
    }

    private static List<CapabilityStudioBrowserAnomalyMatrixResultBuilder.EvidenceRef> evidence(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.ObligationKey key) {
        return List.of(new CapabilityStudioBrowserAnomalyMatrixResultBuilder.EvidenceRef(
                "evidence/browser-anomaly/" + key.obligationId(), FINGERPRINT));
    }

    private static byte[] bytes(ObjectNode value) throws Exception {
        return JSON.writeValueAsBytes(value);
    }

    private record Invocation(int exitCode, String stdout, String stderr) {
    }
}
