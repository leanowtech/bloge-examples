package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioBrowserMatrixArtifactTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final CapabilityStudioBrowserMatrixArtifact.Viewport DESKTOP =
            new CapabilityStudioBrowserMatrixArtifact.Viewport(1440, 900);

    @Test
    void fixesTheDenominatorAtSixtyAndMarksMissingCellsNotRun() {
        CapabilityStudioBrowserMatrixArtifact artifact = artifact("CLEAN");

        ObjectNode result = artifact.build(OffsetDateTime.parse("2026-08-18T10:05:00Z"));

        assertThat(result.path("cells")).hasSize(60);
        assertThat(result.at("/summary/expectedCellCount").asInt()).isEqualTo(60);
        assertThat(result.at("/summary/incompleteCellCount").asInt()).isEqualTo(60);
        assertThat(result.path("resultStatus").asText()).isEqualTo("INCOMPLETE");
        assertThat(result.path("diagnostics").get(0).path("code").asText())
                .isEqualTo("BROWSER_MATRIX_INCOMPLETE");
    }

    @Test
    void derivesACompleteDeterministicArtifactFromAllPassingCells() {
        CapabilityStudioBrowserMatrixArtifact first = artifact("CLEAN");
        CapabilityStudioBrowserMatrixArtifact second = artifact("CLEAN");
        for (String goldenPath : CapabilityStudioBrowserMatrixArtifact.GOLDEN_PATHS) {
            for (String locale : CapabilityStudioBrowserMatrixArtifact.LOCALES) {
                for (CapabilityStudioBrowserMatrixArtifact.Viewport viewport
                        : CapabilityStudioBrowserMatrixArtifact.VIEWPORTS) {
                    CapabilityStudioBrowserMatrixArtifact.Observation observation = pass(
                            goldenPath, locale, viewport);
                    first.record(observation);
                    second.record(observation);
                }
            }
        }

        ObjectNode firstResult = first.build(OffsetDateTime.parse("2026-08-18T10:05:00Z"));
        ObjectNode secondResult = second.build(OffsetDateTime.parse("2026-08-18T10:05:00Z"));

        assertThat(firstResult).isEqualTo(secondResult);
        assertThat(firstResult.path("resultStatus").asText()).isEqualTo("COMPLETE");
        assertThat(firstResult.at("/summary/passCellCount").asInt()).isEqualTo(60);
        assertThat(firstResult.path("diagnostics")).isEmpty();
        assertThat(firstResult.path("evidenceClosureFingerprint").asText())
                .matches("sha256:[a-f0-9]{64}");
    }

    @Test
    void dirtyCandidateAndObservedFailureCannotBecomeComplete() {
        CapabilityStudioBrowserMatrixArtifact artifact = artifact("DIRTY");
        artifact.record(new CapabilityStudioBrowserMatrixArtifact.Observation(
                "GP-01", "zh-CN", DESKTOP, DESKTOP, "FAIL", true,
                0, 0, 0, 0, true, 2, 0,
                List.of(new CapabilityStudioBrowserMatrixArtifact.EvidenceRef(
                        "evidence:GP-01:zh-CN:1440x900", FINGERPRINT)), 0, 1));

        ObjectNode result = artifact.build(OffsetDateTime.parse("2026-08-18T10:05:00Z"));

        assertThat(result.path("resultStatus").asText()).isEqualTo("FAILED");
        assertThat(result.at("/summary/failedCellCount").asInt()).isEqualTo(1);
        assertThat(result.at("/summary/p1Count").asInt()).isEqualTo(1);
        assertThat(result.path("diagnostics").findValuesAsText("code"))
                .containsExactly(
                        "CANDIDATE_SOURCE_TREE_DIRTY",
                        "BROWSER_CELL_FAILURE",
                        "BROWSER_MATRIX_INCOMPLETE",
                        "P1_FINDING_PRESENT");
    }

    @Test
    void rejectsDuplicateAndFabricatedNotRunObservations() {
        CapabilityStudioBrowserMatrixArtifact artifact = artifact("CLEAN");
        artifact.record(pass("GP-01", "zh-CN", DESKTOP));

        assertThatThrownBy(() -> artifact.record(pass("GP-01", "zh-CN", DESKTOP)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate browser matrix cell");
        assertThatThrownBy(() -> new CapabilityStudioBrowserMatrixArtifact.Observation(
                "GP-02", "zh-CN", DESKTOP, DESKTOP, "NOT_RUN", false,
                0, 0, 0, 0, false, 0, 0, List.of(), 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexecuted cells cannot carry observations");
    }

    private static CapabilityStudioBrowserMatrixArtifact artifact(String treeStatus) {
        return new CapabilityStudioBrowserMatrixArtifact(
                "BMR-unit-test", 1, "v1",
                new CapabilityStudioBrowserMatrixArtifact.Candidate(
                        "build:unit-test", "1", FINGERPRINT, "a".repeat(40), treeStatus),
                new CapabilityStudioBrowserMatrixArtifact.Baseline(
                        "cancellation-fee-canonical-baseline", 1, FINGERPRINT),
                new CapabilityStudioBrowserMatrixArtifact.Environment(
                        FINGERPRINT, "test", "chrome", "151.0", "150.0", "4.12.1"),
                OffsetDateTime.parse("2026-08-18T10:00:00Z"));
    }

    private static CapabilityStudioBrowserMatrixArtifact.Observation pass(
            String goldenPath,
            String locale,
            CapabilityStudioBrowserMatrixArtifact.Viewport viewport) {
        return new CapabilityStudioBrowserMatrixArtifact.Observation(
                goldenPath, locale, viewport, viewport, "PASS", false,
                0, 0, 0, 0, true, 2, 0,
                List.of(new CapabilityStudioBrowserMatrixArtifact.EvidenceRef(
                        "evidence:" + goldenPath + ":" + locale + ":" + viewport.coordinate(),
                        FINGERPRINT)),
                0, 0);
    }
}
