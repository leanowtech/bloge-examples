package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioBrowserAnomalyMatrixSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final List<String> GOLDEN_PATHS = List.of(
            "GP-01", "GP-02", "GP-03", "GP-04", "GP-05",
            "GP-06", "GP-07", "GP-08", "GP-09", "GP-10");
    private static final List<String> LOCALES = List.of("zh-CN", "en-US");
    private static final List<int[]> VIEWPORTS = List.of(
            new int[] {1440, 900}, new int[] {1024, 768}, new int[] {390, 844});

    @Test
    void packagesAndAcceptsTheComplete126ObligationFixture() {
        assertThat(getClass().getResource(
                CapabilityStudioSchemaSupport.BROWSER_ANOMALY_MATRIX_RESULT_RESOURCE))
                .isNotNull();

        ObjectNode result = result();

        assertThat(CapabilityStudioSchemaSupport.validate(
                result, CapabilityStudioSchemaSupport.BROWSER_ANOMALY_MATRIX_RESULT_RESOURCE))
                .isEmpty();
        assertThat(result.path("obligations").size()).isEqualTo(126);
        assertThat(result.at("/summary/expected").asInt()).isEqualTo(126);
        assertThat(result.at("/summary/errorExpected").asInt()).isEqualTo(60);
        assertThat(result.at("/summary/offlineExpected").asInt()).isEqualTo(60);
        assertThat(result.at("/summary/conflictExpected").asInt()).isEqualTo(6);
    }

    @Test
    void rejectsExtraFieldsAndSensitiveUnknownFields() {
        ObjectNode extra = result();
        extra.put("unexpected", true);
        ObjectNode sensitive = result();
        sensitive.put("request", "must-not-leak");

        assertInvalid(extra);
        assertInvalid(sensitive);
    }

    @Test
    void rejectsWrongCountAndSkippedStatus() {
        ObjectNode wrongCount = result();
        ((ArrayNode) wrongCount.path("obligations")).remove(0);
        ObjectNode skipped = result();
        ((ObjectNode) skipped.at("/obligations/0")).put("status", "SKIPPED");

        assertInvalid(wrongCount);
        assertInvalid(skipped);
    }

    @Test
    void rejectsMalformedTriggerEvidenceAndFingerprint() {
        ObjectNode malformedTrigger = result();
        ((ObjectNode) malformedTrigger.at("/obligations/0/trigger"))
                .put("targetRoute", "/api/save?payload=secret");
        ObjectNode malformedEvidence = result();
        ((ObjectNode) malformedEvidence.at("/obligations/0/evidenceRefs/0"))
                .put("exactRef", "");
        ObjectNode malformedFingerprint = result();
        ((ObjectNode) malformedFingerprint.at("/obligations/0/evidenceRefs/0"))
                .put("fingerprint", "not-a-fingerprint");

        assertInvalid(malformedTrigger);
        assertInvalid(malformedEvidence);
        assertInvalid(malformedFingerprint);
    }

    @Test
    void rejectsInvalidEnumsStatusesAndViewports() {
        ObjectNode invalidEnum = result();
        ((ObjectNode) invalidEnum.at("/obligations/0/trigger"))
                .put("mechanism", "FAKE_MECHANISM");
        ObjectNode invalidStatus = result();
        invalidStatus.put("resultStatus", "INCOMPLETE");
        ObjectNode invalidViewport = result();
        ((ObjectNode) invalidViewport.at("/obligations/0/viewport"))
                .put("width", 800).put("height", 600);

        assertInvalid(invalidEnum);
        assertInvalid(invalidStatus);
        assertInvalid(invalidViewport);
    }

    private static ObjectNode result() {
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", "bloge.capabilityStudioBrowserAnomalyMatrixResult.v1");
        result.put("resultId", "BAMR-fixture-1");
        result.put("revision", 1);
        result.put("contractId", "S0-AC-01");
        result.put("contractRevision", "s0-ac-01.v1");
        result.set("candidate", JSON.createObjectNode()
                .put("buildRef", "build/candidate-1")
                .put("revision", "candidate-revision-1")
                .put("artifactFingerprint", FINGERPRINT)
                .put("sourceCommit", "abcdef1")
                .put("sourceTreeStatus", "CLEAN"));
        result.set("baselineRef", JSON.createObjectNode()
                .put("id", "baseline/s0-ac-01")
                .put("revision", 1)
                .put("fingerprint", FINGERPRINT));
        result.set("environment", JSON.createObjectNode()
                .put("environmentFingerprint", FINGERPRINT)
                .put("profile", "chrome/stable")
                .put("browserName", "chromium")
                .put("browserVersion", "128.0")
                .put("driverVersion", "128.0")
                .put("axeVersion", "4.10.2"));
        result.set("executionWindow", JSON.createObjectNode()
                .put("startedAt", "2026-08-18T00:00:00Z")
                .put("completedAt", "2026-08-18T01:00:00Z"));
        result.set("baseMatrixRef", JSON.createObjectNode()
                .put("exactRef", "results/browser-matrix/BMR-1")
                .put("fingerprint", FINGERPRINT)
                .put("resultStatus", "COMPLETE"));
        result.put("resultStatus", "COMPLETE");

        ArrayNode obligations = result.putArray("obligations");
        int sequence = 1;
        for (String profile : List.of("ERROR", "OFFLINE")) {
            for (String goldenPath : GOLDEN_PATHS) {
                for (String locale : LOCALES) {
                    for (int[] viewport : VIEWPORTS) {
                        obligations.add(obligation(
                                sequence++, profile, goldenPath, locale, viewport));
                    }
                }
            }
        }
        for (String locale : LOCALES) {
            for (int[] viewport : VIEWPORTS) {
                obligations.add(obligation(
                        sequence++, "CONFLICT", "GP-04", locale, viewport));
            }
        }
        result.set("summary", JSON.createObjectNode()
                .put("expected", 126)
                .put("actual", 126)
                .put("passed", 126)
                .put("failed", 0)
                .put("notRun", 0)
                .put("errorExpected", 60)
                .put("offlineExpected", 60)
                .put("conflictExpected", 6));
        result.put("evidenceClosureFingerprint", FINGERPRINT);
        result.putArray("diagnostics");
        return result;
    }

    private static ObjectNode obligation(
            int sequence,
            String profile,
            String goldenPath,
            String locale,
            int[] viewport) {
        boolean error = "ERROR".equals(profile);
        boolean conflict = "CONFLICT".equals(profile);
        String mechanism = conflict
                ? "REAL_HTTP_STALE_REVISION"
                : error ? "CDP_FETCH_FULFILL" : "CDP_FETCH_FAIL";
        String failureClass = conflict
                ? "REVISION_CONFLICT"
                : error ? "HTTP_5XX" : "TRANSPORT_FAILURE";
        ObjectNode obligation = JSON.createObjectNode()
                .put("obligationId", "BAM-" + String.format("%03d", sequence))
                .put("stateProfile", profile)
                .put("goldenPathId", goldenPath)
                .put("locale", locale)
                .put("status", "PASS")
                .put("expectedUiState", profile + "_FEEDBACK")
                .put("expectedRecoveryAction", conflict ? "RETRY_OR_MERGE" : "RETRY");
        obligation.set("viewport", viewport(viewport));
        obligation.set("trigger", JSON.createObjectNode()
                .put("mechanism", mechanism)
                .put("targetRoute", "/api/capability-studio/save")
                .put("observedFailureClass", failureClass)
                .put("observedHttpStatus", conflict ? 409 : error ? 503 : 0)
                .put("triggered", true));
        if (!error && !conflict) {
            ((ObjectNode) obligation.path("trigger")).putNull("observedHttpStatus");
        }
        ObjectNode observations = JSON.createObjectNode();
        observations.set("actualViewport", viewport(viewport));
        observations.put("pageHorizontalOverflow", false);
        observations.set("axe", JSON.createObjectNode().put("serious", 0).put("critical", 0));
        observations.put("technicalIdCount", 0);
        observations.put("rawJsonCount", 0);
        observations.set("keyboardPath", JSON.createObjectNode()
                .put("completed", true).put("steps", 10).put("focusLosses", 0));
        observations.put("errorVisible", true);
        observations.put("businessSafeExplanation", true);
        observations.put("recoveryActionVisible", true);
        observations.put("recoveryAttempted", true);
        observations.put("recoveredToReady", true);
        observations.put("localDraftRetained", true);
        observations.put("serverRevisionPreserved", true);
        observations.put("staleGreenPreflightAbsent", true);
        observations.put("staleErrorAbsent", true);
        observations.put("staleEvidenceAbsent", true);
        observations.put("staleSuccessAbsent", true);
        observations.put("p0Count", 0);
        observations.put("p1Count", 0);
        obligation.set("browserObservations", observations);
        obligation.putArray("evidenceRefs").addObject()
                .put("exactRef", "evidence/browser-anomaly/" + sequence)
                .put("fingerprint", FINGERPRINT);
        return obligation;
    }

    private static ObjectNode viewport(int[] viewport) {
        return JSON.createObjectNode()
                .put("width", viewport[0])
                .put("height", viewport[1]);
    }

    private static void assertInvalid(JsonNode value) {
        assertThat(CapabilityStudioSchemaSupport.validate(
                value, CapabilityStudioSchemaSupport.BROWSER_ANOMALY_MATRIX_RESULT_RESOURCE))
                .isNotEmpty();
    }
}
