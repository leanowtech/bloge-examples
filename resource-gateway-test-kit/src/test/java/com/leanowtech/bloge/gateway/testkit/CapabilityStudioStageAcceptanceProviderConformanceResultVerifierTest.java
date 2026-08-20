package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioStageAcceptanceProviderConformanceResultVerifierTest {
    private static final String FP = "sha256:" + "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-01-01T00:12:00Z");

    @Test
    void acceptsAllVerdictShapesAndRejectsOversizeReports() throws Exception {
        var verifier = new CapabilityStudioStageAcceptanceProviderConformanceResultVerifier();
        assertThat(verifier.verify(bytes(CapabilityStudioStageAcceptanceProviderConformanceResultBuilder
                .build(CapabilityStudioStageAcceptanceProviderConformanceResultBuilderTest
                        .conformant())))
                .verified()).isTrue();
        assertThat(verifier.verify(bytes(CapabilityStudioStageAcceptanceProviderConformanceResultBuilder
                .build(CapabilityStudioStageAcceptanceProviderConformanceResultBuilderTest
                        .inputInvalid())))
                .verified()).isTrue();
        assertThat(verifier.verify(bytes(report("NON_CONFORMANT", "BASELINE_AUTHORITY_REJECTED",
                "FAIL"))).verified()).isTrue();
        assertThat(verifier.verify(bytes(report("BLOCKED", "BASELINE_AUTHORITY_BLOCKED",
                "BLOCKED"))).verified()).isTrue();
        assertThat(verifier.verify(new byte[
                CapabilityStudioStageAcceptanceProviderConformanceResultVerifier.MAXIMUM_REPORT_BYTES + 1])
                .verified()).isFalse();
    }

    @Test
    void rejectsOrderSummaryFingerprintExternalListAndBlockedFailTampering() throws Exception {
        ObjectNode base = CapabilityStudioStageAcceptanceProviderConformanceResultBuilder.build(
                CapabilityStudioStageAcceptanceProviderConformanceResultBuilderTest.conformant());
        var verifier = new CapabilityStudioStageAcceptanceProviderConformanceResultVerifier();

        ObjectNode order = base.deepCopy();
        swap((ArrayNode) order.path("checks"), 0, 1);
        ObjectNode summary = base.deepCopy();
        ((ObjectNode) summary.path("summary")).put("passCount", 5);
        ObjectNode external = base.deepCopy();
        swap((ArrayNode) external.path("externalChecksRequired"), 0, 1);
        ObjectNode fingerprint = base.deepCopy();
        fingerprint.put("reportFingerprint", "sha256:" + "b".repeat(64));
        ObjectNode zeroChallenge = base.deepCopy();
        ((ObjectNode) zeroChallenge.path("checks").get(3)).put("challengeCount", 0);
        ((ObjectNode) zeroChallenge.path("summary")).put("challengeCount", 2);
        refingerprint(zeroChallenge);
        ObjectNode failedLocalWithVerifiedBinding = base.deepCopy();
        failedLocalWithVerifiedBinding.put("verdict", "NON_CONFORMANT");
        failedLocalWithVerifiedBinding.put("reasonCode",
                CapabilityStudioStageAcceptanceProviderConformance.CODE_PREFIX
                        + "LOCAL_PROTOCOL_INVALID");
        ((ObjectNode) failedLocalWithVerifiedBinding.path("checks").get(0))
                .put("status", "FAIL")
                .put("reasonCode", CapabilityStudioStageAcceptanceProviderConformance.CODE_PREFIX
                        + "LOCAL_PROTOCOL_INVALID");
        ((ObjectNode) failedLocalWithVerifiedBinding.path("summary"))
                .put("passCount", 5)
                .put("failCount", 1);
        refingerprint(failedLocalWithVerifiedBinding);
        ObjectNode blockedFail = report("BLOCKED", "BASELINE_AUTHORITY_BLOCKED", "BLOCKED");
        ((ObjectNode) ((ArrayNode) blockedFail.path("checks")).get(1)).put("status", "FAIL")
                .put("reasonCode", CapabilityStudioStageAcceptanceProviderConformance.CODE_PREFIX
                        + "BASELINE_AUTHORITY_REJECTED");

        assertThat(verifier.verify(bytes(order)).verified()).isFalse();
        assertThat(verifier.verify(bytes(summary)).verified()).isFalse();
        assertThat(verifier.verify(bytes(external)).verified()).isFalse();
        assertThat(verifier.verify(bytes(fingerprint)).verified()).isFalse();
        assertThat(verifier.verify(bytes(zeroChallenge)).verified()).isFalse();
        assertThat(verifier.verify(bytes(failedLocalWithVerifiedBinding)).verified()).isFalse();
        assertThat(verifier.verify(bytes(blockedFail)).verified()).isFalse();
    }

    @Test
    void verifiesExactBindingAgainstTheSourceStageResult() throws Exception {
        ObjectNode source = CapabilityStudioStageAcceptanceAuthorityVerifierTest.validStagePass();
        byte[] sourceBytes = bytes(source);
        var core = new CapabilityStudioStageAcceptanceProviderConformance().verify(
                sourceBytes, NOW, null);
        ObjectNode report = CapabilityStudioStageAcceptanceProviderConformanceResultBuilder
                .build(core);
        var verifier = new CapabilityStudioStageAcceptanceProviderConformanceResultVerifier();

        assertThat(verifier.verifyBound(bytes(report), sourceBytes, NOW).verified()).isTrue();

        ((ObjectNode) report.path("resultBinding")).put("resultId", "SAR-another-result");
        refingerprint(report);
        assertThat(verifier.verify(bytes(report)).verified()).isTrue();
        assertThat(verifier.verifyBound(bytes(report), sourceBytes, NOW).verified()).isFalse();
    }

    private static ObjectNode report(String verdict, String reason, String baselineStatus) {
        List<CapabilityStudioStageAcceptanceProviderConformance.CheckResult> checks = List.of(
                CapabilityStudioStageAcceptanceProviderConformanceResultBuilderTest.check(
                        "LOCAL_PROTOCOL", "PASS", 0, "LOCAL_PROTOCOL_VALID"),
                CapabilityStudioStageAcceptanceProviderConformanceResultBuilderTest.check(
                        "BASELINE_AUTHORITY_ACCEPTANCE", baselineStatus, 0, reason),
                CapabilityStudioStageAcceptanceProviderConformanceResultBuilderTest.check(
                        "DETERMINISTIC_REPLAY", "NOT_RUN", 0, "NOT_RUN"),
                CapabilityStudioStageAcceptanceProviderConformanceResultBuilderTest.check(
                        "RESOLVER_WRONG_FINGERPRINT_FAIL_CLOSED", "NOT_RUN", 0, "NOT_RUN"),
                CapabilityStudioStageAcceptanceProviderConformanceResultBuilderTest.check(
                        "EVIDENCE_POLICY_TAMPER_FAIL_CLOSED", "NOT_RUN", 0, "NOT_RUN"),
                CapabilityStudioStageAcceptanceProviderConformanceResultBuilderTest.check(
                        "OWNER_AUTHORITY_TAMPER_FAIL_CLOSED", "NOT_RUN", 0, "NOT_RUN"));
        var value = new CapabilityStudioStageAcceptanceProviderConformance.Result(
                CapabilityStudioStageAcceptanceProviderConformance.Verdict.valueOf(verdict), reason,
                checks, 0, "SAR-report", 1, FP, NOW);
        return CapabilityStudioStageAcceptanceProviderConformanceResultBuilder.build(value);
    }

    private static byte[] bytes(ObjectNode value) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(value);
    }

    private static void refingerprint(ObjectNode report) {
        report.remove("reportFingerprint");
        report.put("reportFingerprint", EvidenceVerificationSupport.sha256Bounded(report,
                CapabilityStudioStageAcceptanceProviderConformanceResultVerifier
                        .MAXIMUM_REPORT_BYTES));
    }

    private static void swap(ArrayNode values, int first, int second) {
        var value = values.get(first);
        values.set(first, values.get(second));
        values.set(second, value);
    }
}
