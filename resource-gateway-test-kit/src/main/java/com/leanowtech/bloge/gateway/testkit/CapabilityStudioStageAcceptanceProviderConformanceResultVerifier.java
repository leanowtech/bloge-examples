package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Independently verifies the strict Provider Conformance report without returning its payload. */
public final class CapabilityStudioStageAcceptanceProviderConformanceResultVerifier {
    /** Maximum UTF-8 report size accepted by this verifier. */
    public static final int MAXIMUM_REPORT_BYTES = 128 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CODE_PREFIX =
            CapabilityStudioStageAcceptanceProviderConformance.CODE_PREFIX;
    private static final List<String> CHECK_IDS =
            CapabilityStudioStageAcceptanceProviderConformance.CHECK_IDS;
    private static final List<String> EXTERNAL_CHECKS = List.of(
            "TRUST_ROOT_ORGANIZATION",
            "KMS_HSM_CUSTODY",
            "TARGET_ENVIRONMENT_TRANSPORT",
            "DEPLOYMENT_EGRESS_ENFORCEMENT",
            "OWNER_PROCESS_ATTESTATION");

    /**
     * Payload-free verifier result.
     *
     * @param verified whether every report invariant passed
     * @param verdict verified report verdict, or {@code INPUT_INVALID}
     * @param reasonCode verified report reason or a verifier rejection code
     */
    public record VerificationResult(boolean verified, String verdict, String reasonCode) {
        /** Creates a defensive result with no report content. */
        public VerificationResult {
            if (verdict == null || reasonCode == null) {
                throw new IllegalArgumentException("verdict and reasonCode are required");
            }
        }

        /** Returns a redacted description. */
        @Override
        public String toString() {
            return "VerificationResult[verified=" + verified + ", verdict=" + verdict
                    + ", reasonCode=" + reasonCode + "]";
        }
    }

    /** Creates an independent Provider Conformance report verifier. */
    public CapabilityStudioStageAcceptanceProviderConformanceResultVerifier() {
    }

    /**
     * Verifies one bounded UTF-8 report.
     *
     * @param wire UTF-8 Provider Conformance report
     * @return payload-free verification result
     */
    public VerificationResult verify(byte[] wire) {
        if (wire == null || wire.length > MAXIMUM_REPORT_BYTES) {
            return invalid("REPORT_SIZE_LIMIT");
        }
        try (JsonParser parser = JSON.getFactory().createParser(wire)) {
            JsonNode report = JSON.readTree(parser);
            if (report == null || parser.nextToken() != null) {
                return invalid("REPORT_JSON_INVALID");
            }
            return verify(report);
        } catch (IOException | RuntimeException failure) {
            return invalid("REPORT_JSON_INVALID");
        }
    }

    /**
     * Verifies a report and independently binds it to the exact source Stage Result.
     *
     * @param reportWire UTF-8 Provider Conformance report
     * @param stageResultWire exact UTF-8 Stage Acceptance Result used by the TCK
     * @param verificationTime trusted instant used for Stage Result verification
     * @return payload-free verification result
     */
    public VerificationResult verifyBound(
            byte[] reportWire, byte[] stageResultWire, Instant verificationTime) {
        VerificationResult reportVerification = verify(reportWire);
        if (!reportVerification.verified()) {
            return reportVerification;
        }
        if (verificationTime == null) {
            return invalid("RESULT_BINDING_TIME_INVALID");
        }

        CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult sourceVerification =
                new CapabilityStudioStageAcceptanceResultV2Verifier().verify(
                        stageResultWire, verificationTime);
        if (!sourceVerification.verified()) {
            return "INPUT_INVALID".equals(reportVerification.verdict())
                    ? reportVerification : invalid("RESULT_BINDING_SOURCE_INVALID");
        }
        if ("INPUT_INVALID".equals(reportVerification.verdict())) {
            return invalid("RESULT_BINDING_SOURCE_MISMATCH");
        }

        try {
            JsonNode report = JSON.readTree(reportWire);
            JsonNode source = JSON.readTree(stageResultWire);
            JsonNode binding = report.path("resultBinding");
            String fingerprint = EvidenceVerificationSupport.sha256Bounded(
                    source, CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES);
            if (!source.path("resultId").asText().equals(binding.path("resultId").asText())
                    || source.path("revision").intValue()
                    != binding.path("revision").intValue()
                    || !fingerprint.equals(binding.path("resultFingerprint").asText())) {
                return invalid("RESULT_BINDING_MISMATCH");
            }
            return reportVerification;
        } catch (IOException | RuntimeException failure) {
            return invalid("RESULT_BINDING_SOURCE_INVALID");
        }
    }

    /**
     * Verifies one decoded report node without retaining or returning it.
     *
     * @param report decoded Provider Conformance report
     * @return payload-free verification result
     */
    public VerificationResult verify(JsonNode report) {
        if (report == null || !report.isObject()) {
            return invalid("REPORT_SCHEMA_INVALID");
        }
        try {
            if (!CapabilityStudioSchemaSupport.validate(
                    report, CapabilityStudioSchemaSupport.PROVIDER_CONFORMANCE_RESULT_V1_RESOURCE)
                    .isEmpty()) {
                return invalid("REPORT_SCHEMA_INVALID");
            }
        } catch (RuntimeException unavailable) {
            return invalid("REPORT_SCHEMA_UNAVAILABLE");
        }

        String verdict = report.path("verdict").textValue();
        String rootReason = report.path("reasonCode").textValue();
        if (!hasCode(rootReason)) {
            return invalid("REPORT_REASON_CODE_INVALID");
        }
        List<JsonNode> checks = reportNodes(report.path("checks"));
        if (checks == null || !checkOrder(checks)) {
            return invalid("CHECK_ORDER_INVALID");
        }
        for (JsonNode check : checks) {
            if (!hasCode(check.path("reasonCode").textValue())) {
                return invalid("CHECK_REASON_CODE_INVALID");
            }
        }
        if (!externalOrder(report.path("externalChecksRequired"))) {
            return invalid("EXTERNAL_CHECK_ORDER_INVALID");
        }
        if (!summaryMatches(report.path("summary"), checks)) {
            return invalid("SUMMARY_MISMATCH");
        }
        if (!bindingMatches(report, verdict)) {
            return invalid("RESULT_BINDING_INVALID");
        }
        if (!verdictMatches(report, verdict, rootReason, checks)) {
            return invalid("VERDICT_INVARIANT_INVALID");
        }
        ObjectNode material = ((ObjectNode) report).deepCopy();
        material.remove("reportFingerprint");
        String expected;
        try {
            expected = EvidenceVerificationSupport.sha256Bounded(material, MAXIMUM_REPORT_BYTES);
        } catch (RuntimeException failure) {
            return invalid("REPORT_FINGERPRINT_INVALID");
        }
        if (!expected.equals(report.path("reportFingerprint").textValue())) {
            return invalid("REPORT_FINGERPRINT_INVALID");
        }
        return new VerificationResult(true, verdict, rootReason);
    }

    private static List<JsonNode> reportNodes(JsonNode value) {
        if (!value.isArray()) {
            return null;
        }
        List<JsonNode> nodes = new ArrayList<>();
        value.forEach(nodes::add);
        return nodes;
    }

    private static boolean checkOrder(List<JsonNode> checks) {
        if (checks.size() != CHECK_IDS.size()) {
            return false;
        }
        for (int i = 0; i < CHECK_IDS.size(); i++) {
            if (!CHECK_IDS.get(i).equals(checks.get(i).path("checkId").textValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean externalOrder(JsonNode external) {
        if (!external.isArray() || external.size() != EXTERNAL_CHECKS.size()) {
            return false;
        }
        for (int i = 0; i < EXTERNAL_CHECKS.size(); i++) {
            if (!EXTERNAL_CHECKS.get(i).equals(external.get(i).textValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean summaryMatches(JsonNode summary, List<JsonNode> checks) {
        if (!summary.isObject() || summary.path("totalCount").intValue() != CHECK_IDS.size()) {
            return false;
        }
        int pass = 0;
        int fail = 0;
        int blocked = 0;
        int notRun = 0;
        int challenges = 0;
        for (int index = 0; index < checks.size(); index++) {
            JsonNode check = checks.get(index);
            switch (check.path("status").textValue()) {
                case "PASS" -> pass++;
                case "FAIL" -> fail++;
                case "BLOCKED" -> blocked++;
                case "NOT_RUN" -> notRun++;
                default -> { return false; }
            }
            int count = check.path("challengeCount").intValue();
            if (count < 0) {
                return false;
            }
            if (index < 3 && count != 0) {
                return false;
            }
            if ("NOT_RUN".equals(check.path("status").textValue()) && count != 0) {
                return false;
            }
            challenges += count;
        }
        return summary.path("passCount").intValue() == pass
                && summary.path("failCount").intValue() == fail
                && summary.path("blockedCount").intValue() == blocked
                && summary.path("notRunCount").intValue() == notRun
                && summary.path("challengeCount").intValue() == challenges;
    }

    private static boolean bindingMatches(JsonNode report, String verdict) {
        JsonNode binding = report.path("resultBinding");
        JsonNode verifiedAt = report.path("verifiedAt");
        if ("INPUT_INVALID".equals(verdict)) {
            return binding.isNull() && verifiedAt.isNull();
        }
        return binding.isObject() && binding.path("resultId").isTextual()
                && binding.path("revision").intValue() >= 1
                && binding.path("resultFingerprint").isTextual()
                && verifiedAt.isTextual();
    }

    private static boolean verdictMatches(
            JsonNode report, String verdict, String rootReason, List<JsonNode> checks) {
        List<String> statuses = checks.stream()
                .map(check -> check.path("status").textValue()).toList();
        JsonNode summary = report.path("summary");
        if ("INPUT_INVALID".equals(verdict)) {
            return "FAIL".equals(statuses.getFirst())
                    && statuses.subList(1, 6).stream().allMatch("NOT_RUN"::equals)
                    && summary.path("passCount").intValue() == 0
                    && summary.path("failCount").intValue() == 1
                    && summary.path("blockedCount").intValue() == 0
                    && summary.path("notRunCount").intValue() == 5
                    && summary.path("challengeCount").intValue() == 0
                    && rootReason.equals(checks.getFirst().path("reasonCode").textValue());
        }
        if (!"PASS".equals(statuses.getFirst())) {
            return false;
        }
        if ("CONFORMANT".equals(verdict)) {
            return statuses.stream().allMatch("PASS"::equals)
                    && checks.subList(3, 6).stream()
                    .allMatch(check -> check.path("challengeCount").intValue() > 0)
                    && summary.path("challengeCount").intValue() > 0
                    && rootReason.equals(CODE_PREFIX + "CONFORMANT");
        }
        boolean hasFail = statuses.stream().anyMatch("FAIL"::equals);
        boolean hasBlocked = statuses.stream().anyMatch("BLOCKED"::equals);
        if ("NON_CONFORMANT".equals(verdict) && !hasFail
                || "BLOCKED".equals(verdict) && !hasBlocked) {
            return false;
        }
        if ("BLOCKED".equals(verdict) && hasFail) {
            return false;
        }
        if ("NON_CONFORMANT".equals(verdict) || "BLOCKED".equals(verdict)) {
            String firstTerminalReason = checks.stream()
                    .filter(check -> "FAIL".equals(check.path("status").textValue())
                            || "BLOCKED".equals(check.path("status").textValue()))
                    .findFirst().orElseThrow().path("reasonCode").textValue();
            if (!rootReason.equals(firstTerminalReason)) {
                return false;
            }
        }
        if (!"PASS".equals(statuses.get(1))) {
            return statuses.subList(2, 6).stream().allMatch("NOT_RUN"::equals);
        }
        if (!"PASS".equals(statuses.get(2))) {
            return statuses.subList(3, 6).stream().allMatch("NOT_RUN"::equals);
        }
        return checks.subList(3, 6).stream()
                .allMatch(check -> !"NOT_RUN".equals(check.path("status").textValue())
                        && check.path("challengeCount").intValue() > 0);
    }

    private static boolean hasCode(String value) {
        return value != null && value.startsWith(CODE_PREFIX) && value.length() <= 255
                && value.substring(CODE_PREFIX.length()).matches("[A-Z][A-Z0-9_.-]{0,254}");
    }

    private static VerificationResult invalid(String suffix) {
        return new VerificationResult(false, "INPUT_INVALID", CODE_PREFIX + suffix);
    }
}
