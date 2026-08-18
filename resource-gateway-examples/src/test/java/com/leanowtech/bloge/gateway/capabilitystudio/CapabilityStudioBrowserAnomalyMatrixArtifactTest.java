package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioBrowserAnomalyMatrixArtifactTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String SOURCE_COMMIT = "a".repeat(40);
    private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-08-18T10:00:00Z");
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-08-18T10:05:00Z");

    @Test
    void emitsAll126ObligationsInTheFixedProfileOrderAndCompletesOnlyWithRealPassingFacts() {
        CapabilityStudioBrowserAnomalyMatrixArtifact first = artifact("CLEAN");
        CapabilityStudioBrowserAnomalyMatrixArtifact second = artifact("CLEAN");
        for (String obligationId : CapabilityStudioBrowserAnomalyMatrixArtifact.OBLIGATION_IDS) {
            CapabilityStudioBrowserAnomalyMatrixArtifact.Observation observation = pass(obligationId);
            first.record(observation);
            second.record(observation);
        }

        ObjectNode firstResult = first.build(COMPLETED_AT);
        ObjectNode secondResult = second.build(COMPLETED_AT);

        assertThat(firstResult).isEqualTo(secondResult);
        assertThat(firstResult.path("obligations")).hasSize(126);
        assertThat(firstResult.path("obligations").findValuesAsText("obligationId"))
                .containsExactlyElementsOf(CapabilityStudioBrowserAnomalyMatrixArtifact.OBLIGATION_IDS);
        assertThat(firstResult.at("/summary/expected").asInt()).isEqualTo(126);
        assertThat(firstResult.at("/summary/actual").asInt()).isEqualTo(126);
        assertThat(firstResult.at("/summary/passed").asInt()).isEqualTo(126);
        assertThat(firstResult.at("/summary/errorExpected").asInt()).isEqualTo(60);
        assertThat(firstResult.at("/summary/offlineExpected").asInt()).isEqualTo(60);
        assertThat(firstResult.at("/summary/conflictExpected").asInt()).isEqualTo(6);
        assertThat(firstResult.path("resultStatus").asText()).isEqualTo("COMPLETE");
        assertThat(firstResult.path("diagnostics")).isEmpty();
        assertThat(firstResult.path("evidenceClosureFingerprint").asText())
                .matches("sha256:[a-f0-9]{64}");
    }

    @Test
    void materializesEveryMissingObligationAsStrictNotRun() {
        ObjectNode result = artifact("CLEAN").build(COMPLETED_AT);

        assertThat(result.path("resultStatus").asText()).isEqualTo("NOT_RUN");
        assertThat(result.at("/summary/actual").asInt()).isEqualTo(126);
        assertThat(result.at("/summary/notRun").asInt()).isEqualTo(126);
        assertThat(result.at("/summary/passed").asInt()).isZero();
        assertThat(result.path("obligations")).allSatisfy(node -> {
            assertThat(node.path("status").asText()).isEqualTo("NOT_RUN");
            assertThat(node.path("trigger").path("triggered").asBoolean()).isFalse();
            assertThat(node.path("browserObservations").path("actualViewport").isNull()).isTrue();
            assertThat(node.path("browserObservations").path("errorVisible").asBoolean()).isFalse();
            assertThat(node.path("evidenceRefs")).isEmpty();
        });
        assertThat(result.path("obligations")).allSatisfy(node ->
                assertThat(node.path("trigger").path("targetRoute").asText()).isEqualTo(
                        expectedTargetRoute(
                                node.path("stateProfile").asText(),
                                node.path("goldenPathId").asText())));
    }

    @Test
    void rejectsCanonicalTargetRouteDriftForNotRunPassAndFail() {
        CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger driftedNotRun =
                new CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger(
                        "CDP_FETCH_FULFILL", "/api/capability-studio/scenario-dataset",
                        "HTTP_5XX", null, false);
        CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger driftedAttempt =
                new CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger(
                        "CDP_FETCH_FULFILL", "/api/capability-studio/scenario-dataset",
                        "HTTP_5XX", 503, true);

        assertThatThrownBy(() -> new CapabilityStudioBrowserAnomalyMatrixArtifact.Observation(
                "BAM-ERROR-GP-01-zh-CN-1440x900", "ERROR", "GP-01", "zh-CN", desktop(),
                "NOT_RUN", driftedNotRun, "ERROR_FEEDBACK", "RETRY",
                CapabilityStudioBrowserAnomalyMatrixArtifact.BrowserObservations.notRun(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOT_RUN obligation targetRoute drift");
        assertThatThrownBy(() -> passWith(
                "BAM-ERROR-GP-01-zh-CN-1440x900", driftedAttempt,
                passingBrowser(desktop(), false, false, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PASS obligation targetRoute drift");
        assertThatThrownBy(() -> new CapabilityStudioBrowserAnomalyMatrixArtifact.Observation(
                "BAM-ERROR-GP-01-zh-CN-1440x900", "ERROR", "GP-01", "zh-CN", desktop(),
                "FAIL", driftedAttempt, "ERROR_FEEDBACK", "RETRY",
                failingBrowser(desktop()), List.of(evidence("drifted-failure"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FAIL obligation targetRoute drift");
    }

    @Test
    void emitsOnlyTheSchemaFieldsAtEachRequiredObjectBoundary() {
        ObjectNode result = artifact("CLEAN").build(COMPLETED_AT);

        assertThat(fieldNames(result)).containsExactlyInAnyOrder(
                "schemaVersion", "resultId", "revision", "contractId", "contractRevision",
                "candidate", "baselineRef", "environment", "executionWindow", "baseMatrixRef",
                "resultStatus", "obligations", "summary", "evidenceClosureFingerprint", "diagnostics");
        ObjectNode obligation = (ObjectNode) result.path("obligations").get(0);
        assertThat(fieldNames(obligation)).containsExactlyInAnyOrder(
                "obligationId", "stateProfile", "goldenPathId", "locale", "viewport", "status",
                "trigger", "expectedUiState", "expectedRecoveryAction", "browserObservations",
                "evidenceRefs");
        assertThat(fieldNames((ObjectNode) obligation.path("trigger"))).containsExactlyInAnyOrder(
                "mechanism", "targetRoute", "observedFailureClass", "observedHttpStatus", "triggered");
        assertThat(fieldNames((ObjectNode) obligation.path("browserObservations"))).containsExactlyInAnyOrder(
                "actualViewport", "pageHorizontalOverflow", "axe", "technicalIdCount", "rawJsonCount",
                "keyboardPath", "errorVisible", "businessSafeExplanation", "recoveryActionVisible",
                "recoveryAttempted", "recoveredToReady", "localDraftRetained", "serverRevisionPreserved",
                "staleGreenPreflightAbsent", "staleErrorAbsent", "staleEvidenceAbsent", "staleSuccessAbsent",
                "p0Count", "p1Count");
    }

    @Test
    void dirtyCandidateMakesEvenACompleteObservationSetFail() {
        CapabilityStudioBrowserAnomalyMatrixArtifact artifact = artifact("DIRTY");
        CapabilityStudioBrowserAnomalyMatrixArtifact.OBLIGATION_IDS
                .forEach(id -> artifact.record(pass(id)));

        ObjectNode result = artifact.build(COMPLETED_AT);

        assertThat(result.path("resultStatus").asText()).isEqualTo("FAILED");
        assertThat(result.path("diagnostics").findValuesAsText("code"))
                .containsExactly("CANDIDATE_SOURCE_TREE_DIRTY");
    }

    @Test
    void anyRecordedFailureMakesTheRootResultFailed() {
        CapabilityStudioBrowserAnomalyMatrixArtifact artifact = artifact("CLEAN");
        artifact.record(new CapabilityStudioBrowserAnomalyMatrixArtifact.Observation(
                "BAM-ERROR-GP-01-zh-CN-1440x900", "ERROR", "GP-01", "zh-CN", desktop(),
                "FAIL", errorTrigger(503), "ERROR_FEEDBACK", "RETRY",
                failingBrowser(desktop()), List.of(evidence("failed-attempt"))));

        ObjectNode result = artifact.build(COMPLETED_AT);

        assertThat(result.path("resultStatus").asText()).isEqualTo("FAILED");
        assertThat(result.at("/summary/failed").asInt()).isEqualTo(1);
        assertThat(result.path("diagnostics").findValuesAsText("code"))
                .containsExactly("BROWSER_ANOMALY_FAILURE");
    }

    @Test
    void rejectsFailuresWithoutAttemptEvidenceAndLeavesPreTriggerFailuresNotRun() {
        CapabilityStudioBrowserAnomalyMatrixArtifact artifact = artifact("CLEAN");
        CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger notTriggered =
                new CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger(
                        "CDP_FETCH_FULFILL", "/api/capability-studio/demo-pack", "HTTP_5XX", 503, false);

        assertThatThrownBy(() -> new CapabilityStudioBrowserAnomalyMatrixArtifact.Observation(
                "BAM-ERROR-GP-01-zh-CN-1440x900", "ERROR", "GP-01", "zh-CN", desktop(),
                "FAIL", notTriggered, "ERROR_FEEDBACK", "RETRY",
                failingBrowser(desktop()), List.of(evidence("not-triggered"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triggered attempt, actual viewport, and evidence");
        assertThatThrownBy(() -> new CapabilityStudioBrowserAnomalyMatrixArtifact.Observation(
                "BAM-ERROR-GP-01-zh-CN-1440x900", "ERROR", "GP-01", "zh-CN", desktop(),
                "FAIL", errorTrigger(503), "ERROR_FEEDBACK", "RETRY",
                CapabilityStudioBrowserAnomalyMatrixArtifact.BrowserObservations.notRun(),
                List.of(evidence("no-browser"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triggered attempt, actual viewport, and evidence");
        assertThatThrownBy(() -> new CapabilityStudioBrowserAnomalyMatrixArtifact.Observation(
                "BAM-ERROR-GP-01-zh-CN-1440x900", "ERROR", "GP-01", "zh-CN", desktop(),
                "FAIL", errorTrigger(503), "ERROR_FEEDBACK", "RETRY",
                failingBrowser(desktop()), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triggered attempt, actual viewport, and evidence");
        assertThatThrownBy(() -> new CapabilityStudioBrowserAnomalyMatrixArtifact.Observation(
                "BAM-ERROR-GP-01-zh-CN-1440x900", "ERROR", "GP-01", "zh-CN", desktop(),
                "FAIL", new CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger(
                        "CDP_FETCH_FAIL", "/api/capability-studio/demo-pack", "TRANSPORT_FAILURE", null, true),
                "ERROR_FEEDBACK", "RETRY", failingBrowser(desktop()),
                List.of(evidence("wrong-trigger"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile-mismatched trigger");

        ObjectNode result = artifact.build(COMPLETED_AT);
        assertThat(result.path("resultStatus").asText()).isEqualTo("NOT_RUN");
        assertThat(result.at("/summary/notRun").asInt()).isEqualTo(126);
        assertThat(result.at("/summary/failed").asInt()).isZero();
    }

    @Test
    void rejectsDuplicateUnknownAndIllegalCombinations() {
        CapabilityStudioBrowserAnomalyMatrixArtifact artifact = artifact("CLEAN");
        CapabilityStudioBrowserAnomalyMatrixArtifact.Observation first = pass("BAM-ERROR-GP-01-zh-CN-1440x900");
        artifact.record(first);

        assertThatThrownBy(() -> artifact.record(first))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate browser anomaly obligation");
        assertThatThrownBy(() -> artifact.record(new CapabilityStudioBrowserAnomalyMatrixArtifact.Observation(
                "BAM-ERROR-GP-01-zh-CN-1440x900-extra", "ERROR", "GP-01", "zh-CN", desktop(),
                "PASS", errorTrigger(503), "ERROR_FEEDBACK", "RETRY",
                passingBrowser(desktop(), false, false, true), List.of(evidence("unknown")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown browser anomaly obligation");
        assertThatThrownBy(() -> artifact.record(new CapabilityStudioBrowserAnomalyMatrixArtifact.Observation(
                "BAM-ERROR-GP-01-zh-CN-1440x900", "ERROR", "GP-02", "zh-CN",
                desktop(), "PASS", errorTrigger(503), "ERROR_FEEDBACK", "RETRY",
                passingBrowser(desktop(), false, false, true), List.of(evidence("mismatch")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("obligation identity");
        assertThatThrownBy(() -> new CapabilityStudioBrowserAnomalyMatrixArtifact.BaseMatrixRef(
                "BMR-unit-test", FINGERPRINT, "FAILED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be COMPLETE");
        assertThatThrownBy(() -> new CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport(800, 600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the fixed anomaly matrix");
    }

    @Test
    void rejectsFalsePassesAndProfileTriggerMismatches() {
        assertThatThrownBy(() -> passWith("BAM-ERROR-GP-01-zh-CN-1440x900",
                new CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger(
                        "CDP_FETCH_FAIL", "/api/capability-studio/demo-pack", "HTTP_5XX", 503, true),
                passingBrowser(desktop(), false, false, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile-mismatched trigger");
        assertThatThrownBy(() -> passWith("BAM-OFFLINE-GP-01-zh-CN-1440x900",
                new CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger(
                        "CDP_FETCH_FAIL", "/api/capability-studio/demo-pack", "HTTP_5XX", 503, true),
                passingBrowser(desktop(), false, false, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile-mismatched trigger");
        assertThatThrownBy(() -> passWith("BAM-CONFLICT-GP-04-zh-CN-1440x900",
                new CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger(
                        "REAL_HTTP_STALE_REVISION",
                        "/api/capability-studio/tutorial-branch/behaviors/compensation-history",
                        "REVISION_CONFLICT", 409, true),
                passingBrowser(desktop(), false, false, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not preserve revision facts");
        assertThatThrownBy(() -> passWith("BAM-ERROR-GP-01-zh-CN-1440x900", errorTrigger(503),
                new CapabilityStudioBrowserAnomalyMatrixArtifact.BrowserObservations(
                        desktop(), false, new CapabilityStudioBrowserAnomalyMatrixArtifact.Axe(0, 0),
                        0, 0, new CapabilityStudioBrowserAnomalyMatrixArtifact.KeyboardPath(true, 1, 0),
                        true, false, true, true, true, false, false, false,
                        true, true, true, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("browser acceptance gates");
    }

    @Test
    void closureIsStableAndChangesWhenEvidenceMaterialChanges() {
        CapabilityStudioBrowserAnomalyMatrixArtifact first = artifact("CLEAN");
        CapabilityStudioBrowserAnomalyMatrixArtifact second = artifact("CLEAN");
        first.record(pass("BAM-ERROR-GP-01-zh-CN-1440x900"));
        second.record(passWithEvidence("BAM-ERROR-GP-01-zh-CN-1440x900", "different-evidence"));

        String firstClosure = first.build(COMPLETED_AT).path("evidenceClosureFingerprint").asText();
        String secondClosure = second.build(COMPLETED_AT).path("evidenceClosureFingerprint").asText();

        assertThat(firstClosure).matches("sha256:[a-f0-9]{64}");
        assertThat(secondClosure).matches("sha256:[a-f0-9]{64}");
        assertThat(secondClosure).isNotEqualTo(firstClosure);
        assertThat(firstClosure).isEqualTo(first.build(COMPLETED_AT).path("evidenceClosureFingerprint").asText());
    }

    private static CapabilityStudioBrowserAnomalyMatrixArtifact artifact(String treeStatus) {
        return new CapabilityStudioBrowserAnomalyMatrixArtifact(
                "BAMR-unit-test", 1, "v1",
                new CapabilityStudioBrowserAnomalyMatrixArtifact.Candidate(
                        "build:unit-test", "1", FINGERPRINT, SOURCE_COMMIT, treeStatus),
                new CapabilityStudioBrowserAnomalyMatrixArtifact.Baseline(
                        "cancellation-fee-canonical-baseline", 1, FINGERPRINT),
                new CapabilityStudioBrowserAnomalyMatrixArtifact.Environment(
                        FINGERPRINT, "test", "chrome", "151.0", "150.0", "4.12.1"),
                STARTED_AT,
                new CapabilityStudioBrowserAnomalyMatrixArtifact.BaseMatrixRef(
                        "BMR-unit-test", FINGERPRINT));
    }

    private static CapabilityStudioBrowserAnomalyMatrixArtifact.Observation pass(String id) {
        return passWithEvidence(id, "evidence:" + id);
    }

    private static CapabilityStudioBrowserAnomalyMatrixArtifact.Observation passWithEvidence(
            String id, String evidenceId) {
        String[] parts = parts(id);
        String profile = parts[0];
        CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport viewport = viewport(parts[3]);
        return passWith(id, trigger(profile, parts[1]), passingBrowser(viewport,
                "CONFLICT".equals(profile), "CONFLICT".equals(profile), true),
                evidence(evidenceId));
    }

    private static CapabilityStudioBrowserAnomalyMatrixArtifact.Observation passWith(
            String id,
            CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger trigger,
            CapabilityStudioBrowserAnomalyMatrixArtifact.BrowserObservations browser) {
        return passWith(id, trigger, browser, evidence("evidence:" + id));
    }

    private static CapabilityStudioBrowserAnomalyMatrixArtifact.Observation passWith(
            String id,
            CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger trigger,
            CapabilityStudioBrowserAnomalyMatrixArtifact.BrowserObservations browser,
            CapabilityStudioBrowserAnomalyMatrixArtifact.EvidenceRef evidence) {
        String[] parts = parts(id);
        return new CapabilityStudioBrowserAnomalyMatrixArtifact.Observation(
                id, parts[0], parts[1], parts[2], viewport(parts[3]), "PASS", trigger,
                parts[0] + "_FEEDBACK", "CONFLICT".equals(parts[0]) ? "RETRY_OR_MERGE" : "RETRY",
                browser, List.of(evidence));
    }

    private static CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger trigger(
            String profile, String goldenPathId) {
        String targetRoute = expectedTargetRoute(profile, goldenPathId);
        return switch (profile) {
            case "ERROR" -> new CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger(
                    "CDP_FETCH_FULFILL", targetRoute, "HTTP_5XX", 503, true);
            case "OFFLINE" -> new CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger(
                    "CDP_FETCH_FAIL", targetRoute, "TRANSPORT_FAILURE", null, true);
            case "CONFLICT" -> new CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger(
                    "REAL_HTTP_STALE_REVISION", targetRoute, "REVISION_CONFLICT", 409, true);
            default -> throw new IllegalArgumentException("unknown profile");
        };
    }

    private static CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger errorTrigger(int status) {
        return new CapabilityStudioBrowserAnomalyMatrixArtifact.Trigger(
                "CDP_FETCH_FULFILL", "/api/capability-studio/demo-pack", "HTTP_5XX", status, true);
    }

    private static CapabilityStudioBrowserAnomalyMatrixArtifact.BrowserObservations passingBrowser(
            CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport viewport,
            boolean localDraftRetained,
            boolean serverRevisionPreserved,
            boolean staleGreenPreflightAbsent) {
        return new CapabilityStudioBrowserAnomalyMatrixArtifact.BrowserObservations(
                viewport, false,
                new CapabilityStudioBrowserAnomalyMatrixArtifact.Axe(0, 0), 0, 0,
                new CapabilityStudioBrowserAnomalyMatrixArtifact.KeyboardPath(true, 2, 0),
                true, true, true, true, true,
                localDraftRetained, serverRevisionPreserved, staleGreenPreflightAbsent,
                true, true, true, 0, 0);
    }

    private static CapabilityStudioBrowserAnomalyMatrixArtifact.BrowserObservations failingBrowser(
            CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport viewport) {
        return new CapabilityStudioBrowserAnomalyMatrixArtifact.BrowserObservations(
                viewport, true,
                new CapabilityStudioBrowserAnomalyMatrixArtifact.Axe(1, 0), 1, 1,
                new CapabilityStudioBrowserAnomalyMatrixArtifact.KeyboardPath(false, 1, 1),
                true, false, true, true, false,
                false, false, false, false, false, false, 0, 1);
    }

    private static CapabilityStudioBrowserAnomalyMatrixArtifact.EvidenceRef evidence(String id) {
        return new CapabilityStudioBrowserAnomalyMatrixArtifact.EvidenceRef(id, FINGERPRINT);
    }

    private static String[] parts(String id) {
        String[] raw = id.split("-", 7);
        return new String[] {raw[1], raw[2] + "-" + raw[3], raw[4] + "-" + raw[5], raw[6]};
    }

    private static String expectedTargetRoute(String profile, String goldenPathId) {
        if ("CONFLICT".equals(profile)) {
            return "/api/capability-studio/tutorial-branch/behaviors/compensation-history";
        }
        return switch (goldenPathId) {
            case "GP-01", "GP-02", "GP-07" -> "/api/capability-studio/demo-pack";
            case "GP-03" -> "/api/capability-studio/scenario-dataset";
            case "GP-04" -> "/api/capability-studio/tutorial-branch/behaviors/compensation-history";
            case "GP-05", "GP-06" -> "/api/capability-studio/feature-rehearsal";
            case "GP-08" -> "/api/capability-studio/governed-baseline";
            case "GP-09" -> "/api/capability-studio/scenario-dataset/quality-impact";
            case "GP-10" -> "/api/capability-studio/governed-runs/runId/evidence";
            default -> throw new IllegalArgumentException("unknown golden path");
        };
    }

    private static CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport desktop() {
        return new CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport(1440, 900);
    }

    private static CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport viewport(String coordinate) {
        String[] parts = coordinate.split("x", -1);
        return new CapabilityStudioBrowserAnomalyMatrixArtifact.Viewport(
                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    private static Set<String> fieldNames(ObjectNode value) {
        Set<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
