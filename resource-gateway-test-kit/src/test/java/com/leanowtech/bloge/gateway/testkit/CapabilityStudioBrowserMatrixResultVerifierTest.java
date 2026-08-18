package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioBrowserMatrixResultVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CapabilityStudioBrowserMatrixResultVerifier VERIFIER =
            new CapabilityStudioBrowserMatrixResultVerifier();
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final List<String> GOLDEN_PATHS = List.of(
            "GP-01", "GP-02", "GP-03", "GP-04", "GP-05",
            "GP-06", "GP-07", "GP-08", "GP-09", "GP-10");
    private static final List<String> LOCALES = List.of("zh-CN", "en-US");
    private static final List<int[]> VIEWPORTS = List.of(
            new int[]{1440, 900}, new int[]{1024, 768}, new int[]{390, 844});

    @Test
    void verifiesTheExactSixtyCellCompleteMatrix() {
        ObjectNode result = completeResult();

        CapabilityStudioBrowserMatrixResultVerifier.VerificationResult verification =
                VERIFIER.verify(result);

        assertThat(verification.verified()).isTrue();
        assertThat(verification.artifactStatus())
                .isEqualTo(CapabilityStudioBrowserMatrixResultVerifier.ArtifactStatus.COMPLETE);
        assertThat(verification.checks()).containsExactlyInAnyOrder(
                "SCHEMA", "PAYLOAD_FREE", "CANDIDATE_BINDING", "EXECUTION_WINDOW",
                "FIXED_MATRIX", "CELL_ORDER_UNIQUENESS", "CELL_INVARIANTS", "SUMMARY",
                "EVIDENCE_CLOSURE", "RESULT_STATUS");
        assertThat(result.path("cells")).hasSize(60);
        assertThat(result.path("summary").path("passCellCount").asInt()).isEqualTo(60);
    }

    @Test
    void rejectsMissingCellInsteadOfAutoCompletingTheFixedClosure() {
        ObjectNode result = completeResult();
        result.withArray("cells").remove(0);
        refreshDerivedFields(result);

        assertFailure(result, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_CELL_COUNT_INVALID");
    }

    @Test
    void acceptsNotRunAndSkippedCellsAsIncomplete() {
        ObjectNode result = completeResult();
        explicitNotRun((ObjectNode) result.withArray("cells").get(0), "NOT_RUN");
        explicitNotRun((ObjectNode) result.withArray("cells").get(1), "SKIPPED");
        result.put("resultStatus", "INCOMPLETE");
        result.putArray("diagnostics").addObject().put("code", "MATRIX_NOT_COMPLETE");
        refreshDerivedFields(result);

        assertThat(VERIFIER.verify(result).verified()).isTrue();
        assertThat(VERIFIER.verify(result).artifactStatus())
                .isEqualTo(CapabilityStudioBrowserMatrixResultVerifier.ArtifactStatus.INCOMPLETE);
        assertThat(result.path("summary").path("incompleteCellCount").asInt()).isEqualTo(2);
        assertThat(result.path("summary").path("skippedCount").asInt()).isEqualTo(1);
    }

    @Test
    void acceptsObservedOverflowAsFailed() {
        ObjectNode result = completeResult();
        ObjectNode cell = (ObjectNode) result.withArray("cells").get(0);
        cell.put("status", "FAIL");
        cell.put("pageHorizontalOverflow", true);
        result.put("resultStatus", "FAILED");
        result.putArray("diagnostics").addObject().put("code", "PAGE_HORIZONTAL_OVERFLOW");
        refreshDerivedFields(result);

        assertThat(VERIFIER.verify(result).verified()).isTrue();
        assertThat(VERIFIER.verify(result).artifactStatus())
                .isEqualTo(CapabilityStudioBrowserMatrixResultVerifier.ArtifactStatus.FAILED);
        assertThat(result.path("summary").path("failedCellCount").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsOverflowThatIsReportedAsPass() {
        ObjectNode result = completeResult();
        ((ObjectNode) result.withArray("cells").get(0)).put("pageHorizontalOverflow", true);

        assertFailure(result, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_FALSE_PASS");
    }

    @Test
    void acceptsAxeViolationAsFailed() {
        ObjectNode result = completeResult();
        ObjectNode cell = (ObjectNode) result.withArray("cells").get(1);
        cell.put("status", "FAIL");
        cell.with("axe").put("critical", 1);
        result.put("resultStatus", "FAILED");
        result.putArray("diagnostics").addObject().put("code", "AXE_CRITICAL");
        refreshDerivedFields(result);

        assertThat(VERIFIER.verify(result).verified()).isTrue();
        assertThat(result.path("summary").path("p0Count").asInt()).isEqualTo(0);
        assertThat(result.path("summary").path("failedCellCount").asInt()).isEqualTo(1);
    }

    @Test
    void acceptsTechnicalIdRawJsonAndMissingKeyboardAsFailed() {
        ObjectNode result = completeResult();
        ObjectNode first = (ObjectNode) result.withArray("cells").get(0);
        first.put("technicalIdCount", 2);
        first.put("rawJsonCount", 1);
        first.put("status", "FAIL");
        first.with("keyboardPath").put("completed", false);
        result.put("resultStatus", "FAILED");
        result.putArray("diagnostics").addObject().put("code", "AUTHORING_LEAK");
        refreshDerivedFields(result);

        CapabilityStudioBrowserMatrixResultVerifier.VerificationResult verification =
                VERIFIER.verify(result);

        assertThat(verification.verified()).isTrue();
        assertThat(result.path("summary").path("failedCellCount").asInt()).isEqualTo(1);
    }

    @Test
    void acceptsWrongActualViewportAsFailed() {
        ObjectNode result = completeResult();
        ObjectNode cell = (ObjectNode) result.withArray("cells").get(0);
        cell.put("status", "FAIL");
        cell.with("actualInnerViewport").put("width", 1024).put("height", 768);
        result.put("resultStatus", "FAILED");
        result.putArray("diagnostics").addObject().put("code", "INNER_VIEWPORT_MISMATCH");
        refreshDerivedFields(result);

        var verification = VERIFIER.verify(result);
        assertThat(verification.verified()).withFailMessage("%s", verification).isTrue();
        assertThat(result.path("summary").path("failedCellCount").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateCellsInsteadOfAcceptingAFalseFailedResult() {
        ObjectNode result = completeResult();
        result.withArray("cells").set(1, result.withArray("cells").get(0).deepCopy());
        refreshDerivedFields(result);

        assertFailure(result, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_CELL_DUPLICATE");
    }

    @Test
    void rejectsUnsortedCells() {
        ObjectNode result = completeResult();
        ArrayNode cells = result.withArray("cells");
        JsonNode first = cells.get(0).deepCopy();
        cells.set(0, cells.get(1).deepCopy());
        cells.set(1, first);
        refreshDerivedFields(result);

        assertFailure(result, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_CELL_ORDER_INVALID");
    }

    @Test
    void rejectsWrongFixedMatrixViewportPair() {
        ObjectNode result = completeResult();
        ((ObjectNode) result.with("matrix").withArray("viewports").get(0)).put("height", 768);
        refreshDerivedFields(result);

        assertFailure(result, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_FIXED_MATRIX_INVALID");
    }

    @Test
    void rejectsEvidenceOrderAndDuplicateEvidence() {
        ObjectNode result = completeResult();
        ArrayNode refs = ((ObjectNode) result.withArray("cells").get(0)).withArray("evidenceRefs");
        refs.addObject().put("evidenceId", refs.get(0).path("evidenceId").asText())
                .put("fingerprint", FINGERPRINT);
        refreshDerivedFields(result);

        assertFailure(result, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_EVIDENCE_DUPLICATE");
    }

    @Test
    void rejectsUnknownFieldsAtTheStrictSchemaBoundary() {
        ObjectNode result = completeResult();
        result.put("unexpected", true);

        assertFailure(result, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_SCHEMA_INVALID");
    }

    @Test
    void rejectsPayloadAndRawJsonContentBeforeSchemaProcessing() {
        ObjectNode payload = completeResult();
        payload.putObject("payload").put("orderId", "hidden");
        assertFailure(payload, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_PAYLOAD_FIELD");

        ObjectNode rawJson = completeResult();
        ((ObjectNode) rawJson.withArray("cells").get(0)).put("rawJson", "{\"orderId\":1}");
        assertFailure(rawJson, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_PAYLOAD_FIELD");
    }

    @Test
    void rejectsTamperedEvidenceClosureFingerprint() {
        ObjectNode result = completeResult();
        result.put("resultId", "BMR-tampered");

        assertFailure(result, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_EVIDENCE_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsAggregateStatusThatDoesNotMatchRecomputedCells() {
        ObjectNode result = completeResult();
        explicitNotRun((ObjectNode) result.withArray("cells").get(0), "NOT_RUN");
        result.put("resultStatus", "COMPLETE");
        result.putArray("diagnostics");
        refreshDerivedFields(result);

        assertFailure(result, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_STATUS_MISMATCH");
    }

    @Test
    void rejectsDirtyCandidateReportedAsComplete() {
        ObjectNode result = completeResult();
        result.with("candidate").put("sourceTreeStatus", "DIRTY");
        refreshDerivedFields(result);

        assertFailure(result, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_STATUS_MISMATCH");
    }

    @Test
    void acceptsDirtyCandidateOnlyAsTruthfulFailedEvidence() {
        ObjectNode result = completeResult();
        result.with("candidate").put("sourceTreeStatus", "DIRTY");
        result.put("resultStatus", "FAILED");
        result.putArray("diagnostics").addObject().put("code", "CANDIDATE_SOURCE_TREE_DIRTY");
        refreshDerivedFields(result);

        CapabilityStudioBrowserMatrixResultVerifier.VerificationResult verification =
                VERIFIER.verify(result);

        assertThat(verification.verified()).isTrue();
        assertThat(verification.artifactStatus())
                .isEqualTo(CapabilityStudioBrowserMatrixResultVerifier.ArtifactStatus.FAILED);
    }

    @Test
    void rejectsExecutionWindowWhoseCompletionPrecedesItsStart() {
        ObjectNode result = completeResult();
        result.with("executionWindow").put("completedAt", "2026-08-18T09:59:59Z");
        refreshDerivedFields(result);

        assertFailure(result, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_EXECUTION_WINDOW_INVALID");
    }

    @Test
    void rejectsResultWithoutCandidateBinding() {
        ObjectNode result = completeResult();
        result.remove("candidate");

        assertFailure(result, CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_SCHEMA_INVALID");
    }

    @Test
    void returnsPayloadFreeFailureForMalformedWireJson() {
        CapabilityStudioBrowserMatrixResultVerifier.VerificationResult verification =
                VERIFIER.verify("{not-json".getBytes(StandardCharsets.UTF_8));

        assertThat(verification.verified()).isFalse();
        assertThat(verification.failureKind())
                .isEqualTo(CapabilityStudioBrowserMatrixResultVerifier.FailureKind.SCHEMA);
        assertThat(verification.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_INVALID_JSON");
        assertThat(verification.toString()).doesNotContain("not-json");
    }

    private static ObjectNode completeResult() {
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", "bloge.capabilityStudioBrowserMatrixResult.v1");
        result.put("resultId", "BMR-s0-ac-01-complete");
        result.put("revision", 1);
        result.put("contractId", "S0-AC-01");
        result.put("contractRevision", "v1");
        result.put("resultStatus", "COMPLETE");

        result.putObject("candidate")
                .put("buildRef", "build:capability-studio:1")
                .put("revision", "1.0.0")
                .put("artifactFingerprint", FINGERPRINT)
                .put("sourceCommit", "a".repeat(40))
                .put("sourceTreeStatus", "CLEAN");
        result.putObject("baselineRef")
                .put("id", "capability-studio-baseline-cancellation-fee-v1")
                .put("revision", 6)
                .put("fingerprint", FINGERPRINT);
        result.putObject("environment")
                .put("environmentFingerprint", FINGERPRINT)
                .put("profile", "test")
                .put("browserName", "chrome")
                .put("browserVersion", "151.0.7922.138")
                .put("driverVersion", "150.0.7871.124")
                .put("axeVersion", "4.11.0");
        result.putObject("executionWindow")
                .put("startedAt", "2026-08-18T10:00:00Z")
                .put("completedAt", "2026-08-18T10:10:00Z");

        ObjectNode matrix = result.putObject("matrix");
        matrix.put("matrixId", "S0-AC-01.browser.v1");
        ArrayNode goldenPaths = matrix.putArray("goldenPathIds");
        GOLDEN_PATHS.forEach(goldenPaths::add);
        ArrayNode locales = matrix.putArray("locales");
        LOCALES.forEach(locales::add);
        ArrayNode viewports = matrix.putArray("viewports");
        VIEWPORTS.forEach(viewport -> viewports.addObject()
                .put("width", viewport[0]).put("height", viewport[1]));
        matrix.put("expectedCellCount", 60);

        ArrayNode cells = result.putArray("cells");
        for (String goldenPath : GOLDEN_PATHS) {
            for (String locale : LOCALES) {
                for (int[] viewport : VIEWPORTS) {
                    cells.add(cell(goldenPath, locale, viewport[0], viewport[1]));
                }
            }
        }
        result.putObject("summary");
        result.put("evidenceClosureFingerprint", FINGERPRINT);
        result.putArray("diagnostics");
        refreshDerivedFields(result);
        return result;
    }

    private static ObjectNode cell(String goldenPath, String locale, int width, int height) {
        ObjectNode cell = JSON.createObjectNode();
        cell.put("cellId", goldenPath + ":" + locale + ":" + width + "x" + height);
        cell.put("goldenPathId", goldenPath);
        cell.put("locale", locale);
        cell.putObject("viewport").put("width", width).put("height", height);
        cell.putObject("actualInnerViewport").put("width", width).put("height", height);
        cell.put("status", "PASS");
        cell.put("pageHorizontalOverflow", false);
        cell.putObject("axe").put("serious", 0).put("critical", 0);
        cell.put("technicalIdCount", 0);
        cell.put("rawJsonCount", 0);
        cell.putObject("keyboardPath")
                .put("completed", true)
                .put("stepCount", 3)
                .put("focusLossCount", 0);
        cell.putArray("evidenceRefs").addObject()
                .put("evidenceId", "evidence:" + goldenPath + ":" + locale + ":"
                        + width + "x" + height)
                .put("fingerprint", FINGERPRINT);
        cell.put("p0Count", 0);
        cell.put("p1Count", 0);
        return cell;
    }

    private static void explicitNotRun(ObjectNode cell, String status) {
        cell.put("status", status);
        cell.putNull("actualInnerViewport");
        cell.with("keyboardPath")
                .put("completed", false)
                .put("stepCount", 0)
                .put("focusLossCount", 0);
        cell.withArray("evidenceRefs").removeAll();
    }

    private static void refreshDerivedFields(ObjectNode result) {
        int pass = 0;
        int incomplete = 0;
        int failed = 0;
        int skipped = 0;
        int p0 = 0;
        int p1 = 0;
        int evidence = 0;
        for (JsonNode cell : result.withArray("cells")) {
            String status = cell.path("status").asText();
            if ("SKIPPED".equals(status)) {
                skipped++;
            }
            p0 += cell.path("p0Count").asInt();
            p1 += cell.path("p1Count").asInt();
            evidence += cell.path("evidenceRefs").size();
            if ("FAIL".equals(status)) {
                failed++;
            } else if ("PASS".equals(status)) {
                pass++;
            } else {
                incomplete++;
            }
        }
        result.with("summary")
                .put("expectedCellCount", 60)
                .put("actualCellCount", result.withArray("cells").size())
                .put("passCellCount", pass)
                .put("incompleteCellCount", incomplete)
                .put("failedCellCount", failed)
                .put("skippedCount", skipped)
                .put("p0Count", p0)
                .put("p1Count", p1)
                .put("evidenceRefCount", evidence);
        ObjectNode material = result.deepCopy();
        material.remove("evidenceClosureFingerprint");
        result.put("evidenceClosureFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        material,
                        CapabilityStudioBrowserMatrixResultVerifier.MAXIMUM_RESULT_BYTES));
    }

    private static void assertFailure(
            ObjectNode result,
            CapabilityStudioBrowserMatrixResultVerifier.FailureKind failureKind,
            String errorCode) {
        CapabilityStudioBrowserMatrixResultVerifier.VerificationResult verification =
                VERIFIER.verify(result);
        assertThat(verification.failureKind()).isEqualTo(failureKind);
        assertThat(verification.errorCode()).isEqualTo(errorCode);
        assertThat(verification.verified()).isFalse();
    }
}
