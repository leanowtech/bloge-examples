package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Builds the strict, payload-free Provider Conformance report from the core TCK result. */
public final class CapabilityStudioStageAcceptanceProviderConformanceResultBuilder {
    /** The report schema version. */
    public static final String SCHEMA_VERSION =
            "bloge.capabilityStudioStageAcceptanceProviderConformanceResult.v1";

    private static final List<String> EXTERNAL_CHECKS = List.of(
            "TRUST_ROOT_ORGANIZATION",
            "KMS_HSM_CUSTODY",
            "TARGET_ENVIRONMENT_TRANSPORT",
            "DEPLOYMENT_EGRESS_ENFORCEMENT",
            "OWNER_PROCESS_ATTESTATION");

    private CapabilityStudioStageAcceptanceProviderConformanceResultBuilder() {
    }

    /**
     * Projects one core TCK result into a schema-valid and independently verified report.
     *
     * @param result core Provider Conformance result
     * @return defensive JSON report node
     */
    public static ObjectNode build(
            CapabilityStudioStageAcceptanceProviderConformance.Result result) {
        if (result == null) {
            throw new IllegalArgumentException("result is required");
        }
        ObjectNode report = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        report.put("schemaVersion", SCHEMA_VERSION);
        report.put("verdict", result.verdict().name());
        report.put("reasonCode", result.reasonCode());
        if (result.verdict() == CapabilityStudioStageAcceptanceProviderConformance.Verdict.INPUT_INVALID) {
            report.putNull("resultBinding");
            report.putNull("verifiedAt");
        } else {
            ObjectNode binding = report.putObject("resultBinding");
            binding.put("resultId", result.resultId());
            binding.put("revision", result.revision());
            binding.put("resultFingerprint", result.resultFingerprint());
            report.put("verifiedAt", result.verificationTime().toString());
        }

        ArrayNode checks = report.putArray("checks");
        int passCount = 0;
        int failCount = 0;
        int blockedCount = 0;
        int notRunCount = 0;
        int challengeCount = 0;
        for (CapabilityStudioStageAcceptanceProviderConformance.CheckResult check
                : result.checks()) {
            ObjectNode value = checks.addObject();
            value.put("checkId", check.checkId());
            value.put("status", check.status().name());
            value.put("reasonCode", check.reasonCode());
            value.put("challengeCount", check.challengeCount());
            challengeCount += check.challengeCount();
            switch (check.status()) {
                case PASS -> passCount++;
                case FAIL -> failCount++;
                case BLOCKED -> blockedCount++;
                case NOT_RUN -> notRunCount++;
            }
        }

        ObjectNode summary = report.putObject("summary");
        summary.put("totalCount", result.checks().size());
        summary.put("passCount", passCount);
        summary.put("failCount", failCount);
        summary.put("blockedCount", blockedCount);
        summary.put("notRunCount", notRunCount);
        summary.put("challengeCount", challengeCount);
        ArrayNode external = report.putArray("externalChecksRequired");
        EXTERNAL_CHECKS.forEach(external::add);

        String reportFingerprint = EvidenceVerificationSupport.sha256Bounded(
                report, CapabilityStudioStageAcceptanceProviderConformanceResultVerifier
                        .MAXIMUM_REPORT_BYTES);
        report.put("reportFingerprint", reportFingerprint);

        if (!CapabilityStudioSchemaSupport.validate(
                report, CapabilityStudioSchemaSupport.PROVIDER_CONFORMANCE_RESULT_V1_RESOURCE)
                .isEmpty()) {
            throw new IllegalStateException("RG.CAPABILITY_STUDIO.PROVIDER_CONFORMANCE_REPORT_INVALID");
        }
        CapabilityStudioStageAcceptanceProviderConformanceResultVerifier.VerificationResult verified =
                new CapabilityStudioStageAcceptanceProviderConformanceResultVerifier().verify(report);
        if (!verified.verified()) {
            throw new IllegalStateException("RG.CAPABILITY_STUDIO.PROVIDER_CONFORMANCE_REPORT_INVALID");
        }
        return report;
    }
}
