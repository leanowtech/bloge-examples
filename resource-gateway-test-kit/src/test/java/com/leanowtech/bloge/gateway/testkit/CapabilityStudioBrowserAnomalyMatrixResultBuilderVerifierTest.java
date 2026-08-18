package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioBrowserAnomalyMatrixResultBuilderVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String OTHER_FINGERPRINT = "sha256:" + "b".repeat(64);
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-08-18T00:00:00Z");
    private static final OffsetDateTime END = OffsetDateTime.parse("2026-08-18T01:00:00Z");
    private static final CapabilityStudioBrowserAnomalyMatrixResultVerifier VERIFIER =
            new CapabilityStudioBrowserAnomalyMatrixResultVerifier();
    private static final Map<String, String> EXPECTED_ROUTES = Map.ofEntries(
            Map.entry("GP-01", "/api/capability-studio/demo-pack"),
            Map.entry("GP-02", "/api/capability-studio/demo-pack"),
            Map.entry("GP-03", "/api/capability-studio/scenario-dataset"),
            Map.entry("GP-04",
                    "/api/capability-studio/tutorial-branch/behaviors/compensation-history"),
            Map.entry("GP-05", "/api/capability-studio/feature-rehearsal"),
            Map.entry("GP-06", "/api/capability-studio/feature-rehearsal"),
            Map.entry("GP-07", "/api/capability-studio/demo-pack"),
            Map.entry("GP-08", "/api/capability-studio/governed-baseline"),
            Map.entry("GP-09", "/api/capability-studio/scenario-dataset/quality-impact"),
            Map.entry("GP-10", "/api/capability-studio/governed-runs/runId/evidence"));

    @Test
    void startsWithAll126ObligationsNotRunAndCannotClaimComplete() {
        ObjectNode result = newBuilder(defaultCandidate()).build();

        var verification = VERIFIER.verify(result);

        assertThat(verification.verified()).isTrue();
        assertThat(verification.artifactStatus())
                .isEqualTo(CapabilityStudioBrowserAnomalyMatrixResultVerifier.ArtifactStatus.NOT_RUN);
        assertThat(result.path("obligations")).hasSize(126);
        assertThat(result.path("summary").path("notRun").asInt()).isEqualTo(126);
        assertThat(result.path("resultStatus").asText()).isEqualTo("NOT_RUN");
    }

    @Test
    void buildsStableCompleteResultForAllCanonicalObligations() {
        CapabilityStudioBrowserAnomalyMatrixResultBuilder builder = newBuilder(defaultCandidate());
        for (var key : CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations()) {
            builder.pass(key, passingBrowser(key), evidence(key));
        }
        ObjectNode first = builder.build();
        ObjectNode second = builder.build();

        assertThat(first.toString()).isEqualTo(second.toString());
        assertThat(first.path("resultStatus").asText()).isEqualTo("COMPLETE");
        assertThat(first.path("summary").path("passed").asInt()).isEqualTo(126);
        assertThat(VERIFIER.verify(first).verified()).isTrue();
        assertThat(first.path("obligations").get(0).path("obligationId").asText())
                .isEqualTo("BAM-ERROR-GP-01-zh-CN-1440x900");
        assertThat(first.path("obligations").get(60).path("obligationId").asText())
                .isEqualTo("BAM-OFFLINE-GP-01-zh-CN-1440x900");
        assertThat(first.path("obligations").get(120).path("obligationId").asText())
                .isEqualTo("BAM-CONFLICT-GP-04-zh-CN-1440x900");
    }

    @Test
    void emitsTheCanonicalTargetRouteForEveryObligation() {
        ObjectNode result = newBuilder(defaultCandidate()).build();
        var keys = CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations();

        for (int i = 0; i < keys.size(); i++) {
            assertThat(result.path("obligations").get(i).at("/trigger/targetRoute").asText())
                    .as(keys.get(i).obligationId())
                    .isEqualTo(EXPECTED_ROUTES.get(keys.get(i).goldenPathId()));
        }
    }

    @Test
    void rejectsTargetRouteDriftInBuilderAndVerifier() {
        var key = CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations().get(0);
        var driftedTrigger = new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Trigger(
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.TriggerMechanism.CDP_FETCH_FULFILL,
                "/api/capability-studio/scenario-dataset",
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.FailureClass.HTTP_5XX,
                503, true);
        assertThatThrownBy(() -> newBuilder(defaultCandidate()).fail(
                key, driftedTrigger, failingBrowser(), evidence(key)))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode drifted = newBuilder(defaultCandidate()).build();
        ((ObjectNode) drifted.at("/obligations/0/trigger"))
                .put("targetRoute", "/api/capability-studio/scenario-dataset");
        refreshClosure(drifted);
        assertThat(VERIFIER.verify(drifted).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_TARGET_ROUTE_INVALID");
    }

    @Test
    void rejectsDuplicateUnknownAndDimensionMismatchedObligations() {
        CapabilityStudioBrowserAnomalyMatrixResultBuilder builder = newBuilder(defaultCandidate());
        var key = CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations().get(0);

        builder.notRun(key);
        assertThatThrownBy(() -> builder.notRun(key))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CapabilityStudioBrowserAnomalyMatrixResultBuilder.ObligationKey(
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.StateProfile.CONFLICT,
                "GP-01", "zh-CN", key.viewport()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Viewport(
                800, 600)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsProfileTriggerMismatchAndOfflineHttpStatus() {
        CapabilityStudioBrowserAnomalyMatrixResultBuilder builder = newBuilder(defaultCandidate());
        var error = CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations().get(0);
        var offlineTrigger = new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Trigger(
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.TriggerMechanism.CDP_FETCH_FAIL,
                "/api/capability-studio/demo-pack",
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.FailureClass.TRANSPORT_FAILURE,
                null, true);
        builder.fail(error, offlineTrigger, failingBrowser(), List.of());
        assertThat(VERIFIER.verify(builder.build()).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_TRIGGER_INVALID");

        CapabilityStudioBrowserAnomalyMatrixResultBuilder offlineBuilder = newBuilder(defaultCandidate());
        var offline = CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations().get(60);
        var invalidOfflineTrigger = new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Trigger(
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.TriggerMechanism.CDP_FETCH_FAIL,
                "/api/capability-studio/demo-pack",
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.FailureClass.TRANSPORT_FAILURE,
                503, true);
        offlineBuilder.fail(offline, invalidOfflineTrigger, failingBrowser(), List.of());
        assertThat(VERIFIER.verify(offlineBuilder.build()).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_TRIGGER_INVALID");

        CapabilityStudioBrowserAnomalyMatrixResultBuilder classMismatch = newBuilder(defaultCandidate());
        var wrongClassTrigger = new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Trigger(
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.TriggerMechanism.CDP_FETCH_FULFILL,
                "/api/capability-studio/demo-pack",
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.FailureClass.HTTP_4XX,
                503, true);
        classMismatch.fail(
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations().get(0),
                wrongClassTrigger, failingBrowser(), List.of());
        assertThat(VERIFIER.verify(classMismatch.build()).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_TRIGGER_INVALID");
    }

    @Test
    void rejectsFailureThatContainsNoRealBrowserObservationOrEvidence() {
        var key = CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations().get(0);
        var trigger = new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Trigger(
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.TriggerMechanism.CDP_FETCH_FULFILL,
                "/api/capability-studio/demo-pack",
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.FailureClass.HTTP_5XX,
                503, true);
        var builder = newBuilder(defaultCandidate());
        builder.fail(key, trigger,
                new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations(
                        null, false, CapabilityStudioBrowserAnomalyMatrixResultBuilder.Axe.clear(),
                        0, 0, CapabilityStudioBrowserAnomalyMatrixResultBuilder.KeyboardPath.notRun(),
                        false, false, false, false, false, false, false,
                        false, false, false, false, 0, 0), List.of());

        assertThat(VERIFIER.verify(builder.build()).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_FAILURE_WITHOUT_OBSERVATION");
    }

    @Test
    void rejectsFailureWithOnlyViewportAndNoEvidence() {
        var key = CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations().get(0);
        var builder = newBuilder(defaultCandidate());
        builder.fail(key, errorTrigger(), failingBrowser(), List.of());

        assertThat(VERIFIER.verify(builder.build()).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_FAILURE_WITHOUT_OBSERVATION");
    }

    @Test
    void rejectsFailureWithOnlyEvidenceAndNoViewport() {
        var key = CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations().get(0);
        var browser = failingBrowser();
        var noViewport = new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations(
                null, browser.pageHorizontalOverflow(), browser.axe(), browser.technicalIdCount(),
                browser.rawJsonCount(), browser.keyboardPath(), browser.errorVisible(),
                browser.businessSafeExplanation(), browser.recoveryActionVisible(),
                browser.recoveryAttempted(), browser.recoveredToReady(),
                browser.localDraftRetained(), browser.serverRevisionPreserved(),
                browser.staleGreenPreflightAbsent(), browser.staleErrorAbsent(),
                browser.staleEvidenceAbsent(), browser.staleSuccessAbsent(),
                browser.p0Count(), browser.p1Count());
        var builder = newBuilder(defaultCandidate());
        builder.fail(key, errorTrigger(), noViewport, evidence(key));

        assertThat(VERIFIER.verify(builder.build()).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_FAILURE_WITHOUT_OBSERVATION");
    }

    @Test
    void acceptsFailureOnlyWhenViewportAndEvidenceAreBothPresent() {
        var key = CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations().get(0);
        var builder = newBuilder(defaultCandidate());
        builder.fail(key, errorTrigger(), failingBrowser(), evidence(key));

        var verification = VERIFIER.verify(builder.build());

        assertThat(verification.verified()).isTrue();
        assertThat(verification.artifactStatus())
                .isEqualTo(CapabilityStudioBrowserAnomalyMatrixResultVerifier.ArtifactStatus.FAILED);
    }

    @Test
    void rejectsFakePassWhenAnyStrictPassFactIsMissing() {
        CapabilityStudioBrowserAnomalyMatrixResultBuilder builder = newBuilder(defaultCandidate());
        var key = CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations().get(0);
        var browser = passingBrowser(key);
        var fake = new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations(
                browser.actualViewport(), true, browser.axe(), browser.technicalIdCount(),
                browser.rawJsonCount(), browser.keyboardPath(), browser.errorVisible(),
                browser.businessSafeExplanation(), browser.recoveryActionVisible(),
                browser.recoveryAttempted(), browser.recoveredToReady(),
                browser.localDraftRetained(), browser.serverRevisionPreserved(),
                browser.staleGreenPreflightAbsent(), browser.staleErrorAbsent(),
                browser.staleEvidenceAbsent(), browser.staleSuccessAbsent(),
                browser.p0Count(), browser.p1Count());
        builder.record(key, CapabilityStudioBrowserAnomalyMatrixResultBuilder.Observation.pass(
                key, fake, evidence(key)));

        assertThat(VERIFIER.verify(builder.build()).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_FALSE_PASS");
    }

    @Test
    void notRunCannotCarryFabricatedBrowserEvidenceEvenAfterClosureRefresh() {
        ObjectNode result = newBuilder(defaultCandidate()).build();
        ((ObjectNode) result.at("/obligations/0/browserObservations")).put("errorVisible", true);
        refreshClosure(result);

        assertThat(VERIFIER.verify(result).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_NOT_RUN_HAS_EVIDENCE");
    }

    @Test
    void rejectsSensitiveKeysRecursivelyBeforeSchemaAcceptance() {
        ObjectNode result = newBuilder(defaultCandidate()).build();
        ((ObjectNode) result.at("/obligations/0/browserObservations"))
                .putObject("secretToken").put("value", "hidden");

        assertThat(VERIFIER.verify(result).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_SENSITIVE_FIELD");
    }

    @Test
    void detectsOrderAndClosureTampering() {
        CapabilityStudioBrowserAnomalyMatrixResultBuilder builder = newBuilder(defaultCandidate());
        for (var key : CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations()) {
            builder.pass(key, passingBrowser(key), evidence(key));
        }
        ObjectNode result = builder.build();
        ObjectNode first = (ObjectNode) result.path("obligations").get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) result.path("obligations"))
                .set(0, result.path("obligations").get(1).deepCopy());
        ((com.fasterxml.jackson.databind.node.ArrayNode) result.path("obligations"))
                .set(1, first);
        refreshClosure(result);
        assertThat(VERIFIER.verify(result).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_OBLIGATION_ORDER_INVALID");

        ObjectNode complete = builder.build();
        complete.put("resultId", "BAMR-tampered");
        assertThat(VERIFIER.verify(complete).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_EVIDENCE_FINGERPRINT_MISMATCH");
    }

    @Test
    void dirtyCandidateProducesVerifiedFailedArtifactAndPartialExecutionStaysNotRun() {
        CapabilityStudioBrowserAnomalyMatrixResultBuilder dirty = newBuilder(
                newCandidate(CapabilityStudioBrowserAnomalyMatrixResultBuilder.SourceTreeStatus.DIRTY));
        for (var key : CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations()) {
            dirty.pass(key, passingBrowser(key), evidence(key));
        }
        ObjectNode dirtyResult = dirty.build();
        assertThat(dirtyResult.path("resultStatus").asText()).isEqualTo("FAILED");
        assertThat(VERIFIER.verify(dirtyResult).artifactStatus())
                .isEqualTo(CapabilityStudioBrowserAnomalyMatrixResultVerifier.ArtifactStatus.FAILED);

        CapabilityStudioBrowserAnomalyMatrixResultBuilder partial = newBuilder(defaultCandidate());
        for (var key : CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations()) {
            if (!key.equals(CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations().get(0))) {
                partial.pass(key, passingBrowser(key), evidence(key));
            }
        }
        assertThat(partial.build().path("resultStatus").asText()).isEqualTo("NOT_RUN");
    }

    @Test
    void independentlyBindsBaseMatrixAndDetectsCandidateAndBaseDrift() {
        ObjectNode base = completeBase();
        String baseFingerprint = base.path("evidenceClosureFingerprint").asText();
        CapabilityStudioBrowserAnomalyMatrixResultBuilder builder = newBuilder(defaultCandidate(),
                new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaseMatrixRef(
                        "results/browser-matrix/" + base.path("resultId").asText(),
                        baseFingerprint,
                        CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaseMatrixStatus.COMPLETE));
        for (var key : CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations()) {
            builder.pass(key, passingBrowser(key), evidence(key));
        }
        ObjectNode anomaly = builder.build();
        byte[] baseBytes = bytes(base);
        assertThat(VERIFIER.verify(anomaly, baseBytes).verified())
                .withFailMessage("%s", VERIFIER.verify(anomaly, baseBytes))
                .isTrue();

        anomaly.with("candidate").put("artifactFingerprint", OTHER_FINGERPRINT);
        refreshClosure(anomaly);
        assertThat(VERIFIER.verify(anomaly, baseBytes).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_BASE_MATRIX_BINDING_MISMATCH");

        ObjectNode fresh = builder.build();
        fresh.with("baseMatrixRef").put("fingerprint", OTHER_FINGERPRINT);
        refreshClosure(fresh);
        assertThat(VERIFIER.verify(fresh, baseBytes).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_BASE_MATRIX_FINGERPRINT_MISMATCH");
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder newBuilder(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.Candidate candidate) {
        return newBuilder(candidate, new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaseMatrixRef(
                "results/browser-matrix/BMR-1", FINGERPRINT,
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaseMatrixStatus.COMPLETE));
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder newBuilder(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.Candidate candidate,
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaseMatrixRef baseMatrixRef) {
        return new CapabilityStudioBrowserAnomalyMatrixResultBuilder(
                "BAMR-fixture-1", 1, "s0-ac-01.v1", candidate,
                new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaselineRef(
                        "baseline/s0-ac-01", 1, FINGERPRINT),
                new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Environment(
                        FINGERPRINT, "chrome/stable", "chromium", "128.0", "128.0", "4.10.2"),
                new CapabilityStudioBrowserAnomalyMatrixResultBuilder.ExecutionWindow(START, END),
                baseMatrixRef);
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder.Candidate defaultCandidate() {
        return newCandidate(CapabilityStudioBrowserAnomalyMatrixResultBuilder.SourceTreeStatus.CLEAN);
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder.Candidate newCandidate(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.SourceTreeStatus status) {
        return new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Candidate(
                "build/candidate-1", "candidate-revision-1", FINGERPRINT, "abcdef1", status);
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations passingBrowser(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.ObligationKey key) {
        return new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations(
                key.viewport(), false,
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.Axe.clear(), 0, 0,
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.KeyboardPath.complete(10),
                true, true, true, true, true, true, true,
                true, true, true, true, 0, 0);
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations failingBrowser() {
        return new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations(
                new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Viewport(1024, 768),
                true, new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Axe(1, 0),
                1, 1, new CapabilityStudioBrowserAnomalyMatrixResultBuilder.KeyboardPath(
                        false, 0, 1), true, true, false, false, false, true, true,
                true, true, true, true, 1, 0);
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

    private static ObjectNode completeBase() {
        var baseBuilder = new CapabilityStudioBrowserMatrixResultBuilder(
                "BMR-fixture-1", 1, "s0-ac-01.v1",
                new CapabilityStudioBrowserMatrixResultBuilder.Candidate(
                        "build/candidate-1", "candidate-revision-1", FINGERPRINT,
                        "abcdef1", "CLEAN"),
                new CapabilityStudioBrowserMatrixResultBuilder.BaselineRef(
                        "baseline/s0-ac-01", 1, FINGERPRINT),
                new CapabilityStudioBrowserMatrixResultBuilder.Environment(
                        FINGERPRINT, "chrome/stable", "chromium", "128.0", "128.0", "4.10.2"),
                new CapabilityStudioBrowserMatrixResultBuilder.ExecutionWindow(
                        "2026-08-18T00:00:00Z", "2026-08-18T01:00:00Z"));
        for (var key : CapabilityStudioBrowserMatrixResultBuilder.expectedCells()) {
            baseBuilder.pass(key, key.viewport(), List.of(
                    new CapabilityStudioBrowserMatrixResultBuilder.EvidenceRef(
                            "evidence/browser-matrix/" + key.cellId(), FINGERPRINT)));
        }
        return baseBuilder.build();
    }

    private static byte[] bytes(ObjectNode value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void refreshClosure(ObjectNode result) {
        result.remove("evidenceClosureFingerprint");
        result.put("evidenceClosureFingerprint", EvidenceVerificationSupport.sha256Bounded(
                result, CapabilityStudioBrowserAnomalyMatrixResultVerifier.MAXIMUM_RESULT_BYTES));
    }
}
