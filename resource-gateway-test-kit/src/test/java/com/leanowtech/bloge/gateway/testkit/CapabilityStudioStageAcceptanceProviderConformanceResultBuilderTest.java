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
                "providerBindingFingerprint",
                "checks", "summary", "externalChecksRequired", "reportFingerprint");
        assertThat(StreamSupport.stream(report.path("checks").spliterator(), false)
                .map(JsonNodeSupport::textCheckId).toList())
                .containsExactlyElementsOf(CapabilityStudioStageAcceptanceProviderConformance.CHECK_IDS);
        assertThat(report.path("summary").path("challengeCount").intValue()).isEqualTo(3);
        assertThat(CapabilityStudioSchemaSupport.validate(report,
                CapabilityStudioSchemaSupport.PROVIDER_CONFORMANCE_RESULT_V2_RESOURCE)).isEmpty();
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
        assertThat(report.path("summary").path("notRunCount").intValue()).isEqualTo(6);
        assertThat(report.path("providerBindingFingerprint").isNull()).isTrue();
        assertThat(new CapabilityStudioStageAcceptanceProviderConformanceResultVerifier()
                .verify(report).verified()).isTrue();
    }

    @Test
    void preservesLegacySixCheckResultAsV1WithoutV2BindingSemantics() {
        ObjectNode report = CapabilityStudioStageAcceptanceProviderConformanceResultBuilder.build(
                legacyConformant());

        assertThat(report.path("schemaVersion").asText())
                .isEqualTo(CapabilityStudioStageAcceptanceProviderConformanceResultBuilder
                        .LEGACY_SCHEMA_VERSION);
        assertThat(report.has("providerBindingFingerprint")).isFalse();
        assertThat(report.path("checks")).hasSize(6);
        assertThat(CapabilityStudioSchemaSupport.validate(report,
                CapabilityStudioSchemaSupport.PROVIDER_CONFORMANCE_RESULT_V1_RESOURCE)).isEmpty();
        assertThat(new CapabilityStudioStageAcceptanceProviderConformanceResultVerifier()
                .verify(report).verified()).isTrue();
    }

    @Test
    void packagesTheSchemaResource() throws Exception {
        for (String resource : List.of(
                CapabilityStudioSchemaSupport.PROVIDER_CONFORMANCE_RESULT_V1_RESOURCE,
                CapabilityStudioSchemaSupport.PROVIDER_CONFORMANCE_RESULT_V2_RESOURCE)) {
            try (InputStream input = getClass().getResourceAsStream(resource)) {
                assertThat(input).isNotNull();
                assertThat(new ObjectMapper().readTree(input).path("$id").asText())
                        .endsWith(resource.substring(resource.lastIndexOf('/') + 1));
            }
        }
    }

    static CapabilityStudioStageAcceptanceProviderConformance.Result conformant() {
        return result(CapabilityStudioStageAcceptanceProviderConformance.Verdict.CONFORMANT,
                "CONFORMANT", List.of(
                        check("LOCAL_PROTOCOL", "PASS", 0, "LOCAL_PROTOCOL_VALID"),
                        check("AUTHORITY_BINDING", "PASS", 0, "AUTHORITY_BINDING_VALID"),
                        check("BASELINE_AUTHORITY_ACCEPTANCE", "PASS", 0,
                                "BASELINE_AUTHORITY_ACCEPTED"),
                        check("DETERMINISTIC_REPLAY", "PASS", 0, "DETERMINISTIC_REPLAY_ACCEPTED"),
                        check("RESOLVER_WRONG_FINGERPRINT_FAIL_CLOSED", "PASS", 1,
                                "RESOLVER_WRONG_FINGERPRINT_NOT_FOUND"),
                        check("EVIDENCE_POLICY_TAMPER_FAIL_CLOSED", "PASS", 1,
                                "EVIDENCE_POLICY_TAMPER_REJECTED"),
                        check("OWNER_AUTHORITY_TAMPER_FAIL_CLOSED", "PASS", 1,
                                "OWNER_AUTHORITY_TAMPER_REJECTED")), 3,
                "SAR-report", 1, FP, NOW);
    }

    static CapabilityStudioStageAcceptanceProviderConformance.Result inputInvalid() {
        return result(CapabilityStudioStageAcceptanceProviderConformance.Verdict.INPUT_INVALID,
                "LOCAL_PROTOCOL_INVALID", List.of(
                        check("LOCAL_PROTOCOL", "FAIL", 0, "LOCAL_PROTOCOL_INVALID"),
                        check("AUTHORITY_BINDING", "NOT_RUN", 0, "NOT_RUN"),
                        check("BASELINE_AUTHORITY_ACCEPTANCE", "NOT_RUN", 0, "NOT_RUN"),
                        check("DETERMINISTIC_REPLAY", "NOT_RUN", 0, "NOT_RUN"),
                        check("RESOLVER_WRONG_FINGERPRINT_FAIL_CLOSED", "NOT_RUN", 0, "NOT_RUN"),
                        check("EVIDENCE_POLICY_TAMPER_FAIL_CLOSED", "NOT_RUN", 0, "NOT_RUN"),
                        check("OWNER_AUTHORITY_TAMPER_FAIL_CLOSED", "NOT_RUN", 0, "NOT_RUN")), 0,
                null, 0, null, null);
    }

    static CapabilityStudioStageAcceptanceProviderConformance.Result legacyConformant() {
        List<CapabilityStudioStageAcceptanceProviderConformance.CheckResult> checks = List.of(
                        check("LOCAL_PROTOCOL", "PASS", 0, "LOCAL_PROTOCOL_VALID"),
                        check("BASELINE_AUTHORITY_ACCEPTANCE", "PASS", 0,
                                "BASELINE_AUTHORITY_ACCEPTED"),
                        check("DETERMINISTIC_REPLAY", "PASS", 0,
                                "DETERMINISTIC_REPLAY_ACCEPTED"),
                        check("RESOLVER_WRONG_FINGERPRINT_FAIL_CLOSED", "PASS", 1,
                                "RESOLVER_WRONG_FINGERPRINT_NOT_FOUND"),
                        check("EVIDENCE_POLICY_TAMPER_FAIL_CLOSED", "PASS", 1,
                                "EVIDENCE_POLICY_TAMPER_REJECTED"),
                        check("OWNER_AUTHORITY_TAMPER_FAIL_CLOSED", "PASS", 1,
                                "OWNER_AUTHORITY_TAMPER_REJECTED"));
        return new CapabilityStudioStageAcceptanceProviderConformance.Result(
                CapabilityStudioStageAcceptanceProviderConformance.Verdict.CONFORMANT,
                "CONFORMANT", checks, 3, "SAR-legacy", 1, FP, NOW);
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
                checks, challengeCount, resultId, revision, fingerprint,
                verdict == CapabilityStudioStageAcceptanceProviderConformance.Verdict.INPUT_INVALID
                        || checks.size() == CapabilityStudioStageAcceptanceProviderConformance
                        .LEGACY_CHECK_IDS.size() ? null : FP, time);
    }

    private static final class JsonNodeSupport {
        private JsonNodeSupport() {
        }

        static String textCheckId(com.fasterxml.jackson.databind.JsonNode value) {
            return value.path("checkId").asText();
        }
    }
}
