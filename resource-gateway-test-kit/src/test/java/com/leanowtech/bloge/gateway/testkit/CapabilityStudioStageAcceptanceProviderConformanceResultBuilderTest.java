package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioStageAcceptanceProviderConformanceResultBuilderTest {
    private static final String FP = "sha256:" + "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-01-01T00:12:00Z");

    @Test
    void buildsPositiveReportWithFrozenOrderSummaryAndFingerprint() throws Exception {
        ObjectNode report = CapabilityStudioStageAcceptanceProviderConformanceResultBuilder.build(
                conformant());

        assertThat(report.fieldNames()).toIterable().containsExactly(
                "schemaVersion", "verdict", "reasonCode", "resultBinding", "verifiedAt",
                "checks", "summary", "externalChecksRequired", "reportFingerprint");
        assertThat(StreamSupport.stream(report.path("checks").spliterator(), false)
                .map(JsonNodeSupport::textCheckId).toList())
                .containsExactlyElementsOf(CapabilityStudioStageAcceptanceProviderConformance.CHECK_IDS);
        assertThat(report.path("summary").path("challengeCount").intValue()).isEqualTo(3);
        assertThat(CapabilityStudioSchemaSupport.validate(report,
                CapabilityStudioSchemaSupport.PROVIDER_CONFORMANCE_RESULT_V1_RESOURCE)).isEmpty();
        assertThat(new CapabilityStudioStageAcceptanceProviderConformanceResultVerifier()
                .verify(report).verified()).isTrue();
    }

    @Test
    void buildsInputInvalidWithNullBindingTimeAndOneFailedCheck() {
        ObjectNode report = CapabilityStudioStageAcceptanceProviderConformanceResultBuilder.build(
                inputInvalid());

        assertThat(report.path("resultBinding").isNull()).isTrue();
        assertThat(report.path("verifiedAt").isNull()).isTrue();
        assertThat(report.path("summary").path("failCount").intValue()).isEqualTo(1);
        assertThat(report.path("summary").path("notRunCount").intValue()).isEqualTo(5);
        assertThat(new CapabilityStudioStageAcceptanceProviderConformanceResultVerifier()
                .verify(report).verified()).isTrue();
    }

    @Test
    void packagesTheSchemaResource() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                CapabilityStudioSchemaSupport.PROVIDER_CONFORMANCE_RESULT_V1_RESOURCE)) {
            assertThat(input).isNotNull();
            assertThat(new ObjectMapper().readTree(input).path("$id").asText())
                    .endsWith("capability-studio-stage-acceptance-provider-conformance-result-v1.schema.json");
        }
    }

    static CapabilityStudioStageAcceptanceProviderConformance.Result conformant() {
        return result(CapabilityStudioStageAcceptanceProviderConformance.Verdict.CONFORMANT,
                "CONFORMANT", List.of(
                        check("LOCAL_PROTOCOL", "PASS", 0, "LOCAL_PROTOCOL_VALID"),
                        check("BASELINE_AUTHORITY_ACCEPTANCE", "PASS", 0,
                                "BASELINE_AUTHORITY_ACCEPTED"),
                        check("DETERMINISTIC_REPLAY", "PASS", 0, "DETERMINISTIC_REPLAY_ACCEPTED"),
                        check("RESOLVER_WRONG_FINGERPRINT_FAIL_CLOSED", "PASS", 1,
                                "RESOLVER_WRONG_FINGERPRINT_NOT_FOUND"),
                        check("EVIDENCE_POLICY_TAMPER_FAIL_CLOSED", "PASS", 1,
                                "EVIDENCE_POLICY_TAMPER_REJECTED"),
                        check("OWNER_AUTHORITY_TAMPER_FAIL_CLOSED", "PASS", 1,
                                "OWNER_AUTHORITY_TAMPER_REJECTED")), 3);
    }

    static CapabilityStudioStageAcceptanceProviderConformance.Result inputInvalid() {
        return result(CapabilityStudioStageAcceptanceProviderConformance.Verdict.INPUT_INVALID,
                "LOCAL_PROTOCOL_INVALID", List.of(
                        check("LOCAL_PROTOCOL", "FAIL", 0, "LOCAL_PROTOCOL_INVALID"),
                        check("BASELINE_AUTHORITY_ACCEPTANCE", "NOT_RUN", 0, "NOT_RUN"),
                        check("DETERMINISTIC_REPLAY", "NOT_RUN", 0, "NOT_RUN"),
                        check("RESOLVER_WRONG_FINGERPRINT_FAIL_CLOSED", "NOT_RUN", 0, "NOT_RUN"),
                        check("EVIDENCE_POLICY_TAMPER_FAIL_CLOSED", "NOT_RUN", 0, "NOT_RUN"),
                        check("OWNER_AUTHORITY_TAMPER_FAIL_CLOSED", "NOT_RUN", 0, "NOT_RUN")), 0,
                null, 0, null, null);
    }

    static CapabilityStudioStageAcceptanceProviderConformance.CheckResult check(
            String id, String status, int challenges, String reason) {
        return new CapabilityStudioStageAcceptanceProviderConformance.CheckResult(id,
                CapabilityStudioStageAcceptanceProviderConformance.CheckStatus.valueOf(status),
                challenges, reason);
    }

    static CapabilityStudioStageAcceptanceProviderConformance.Result result(
            CapabilityStudioStageAcceptanceProviderConformance.Verdict verdict,
            String reason,
            List<CapabilityStudioStageAcceptanceProviderConformance.CheckResult> checks,
            int challengeCount) {
        return result(verdict, reason, checks, challengeCount, "SAR-report", 1, FP, NOW);
    }

    private static CapabilityStudioStageAcceptanceProviderConformance.Result result(
            CapabilityStudioStageAcceptanceProviderConformance.Verdict verdict,
            String reason,
            List<CapabilityStudioStageAcceptanceProviderConformance.CheckResult> checks,
            int challengeCount, String resultId, int revision, String fingerprint, Instant time) {
        return new CapabilityStudioStageAcceptanceProviderConformance.Result(verdict, reason,
                checks, challengeCount, resultId, revision, fingerprint, time);
    }

    private static final class JsonNodeSupport {
        private JsonNodeSupport() {
        }

        static String textCheckId(com.fasterxml.jackson.databind.JsonNode value) {
            return value.path("checkId").asText();
        }
    }
}
