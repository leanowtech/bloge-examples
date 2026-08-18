package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class CapabilityStudioBrowserMatrixResultBuilderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final CapabilityStudioBrowserMatrixResultVerifier VERIFIER =
            new CapabilityStudioBrowserMatrixResultVerifier();

    @Test
    void producesACompleteSixtyCellArtifactAcceptedByTheVerifier() {
        CapabilityStudioBrowserMatrixResultBuilder builder = builder("clean");
        for (CapabilityStudioBrowserMatrixResultBuilder.CellKey cell
                : CapabilityStudioBrowserMatrixResultBuilder.expectedCells()) {
            builder.pass(cell, cell.viewport(), List.of(evidence(cell)));
        }

        ObjectNode result = builder.build();

        var verification = VERIFIER.verify(result);
        assertThat(verification.verified()).isTrue();
        assertThat(verification.artifactStatus())
                .isEqualTo(CapabilityStudioBrowserMatrixResultVerifier.ArtifactStatus.COMPLETE);
        assertThat(result.path("cells")).hasSize(60);
        assertThat(result.path("summary").path("passCellCount").asInt()).isEqualTo(60);
        assertThat(result.path("diagnostics")).isEmpty();
    }

    @Test
    void keepsTheFixedDenominatorAndExplicitlyRepresentsUnexecutedCells() {
        CapabilityStudioBrowserMatrixResultBuilder builder = builder("partial");
        CapabilityStudioBrowserMatrixResultBuilder.CellKey first =
                CapabilityStudioBrowserMatrixResultBuilder.expectedCells().get(0);
        builder.pass(first, first.viewport(), List.of(evidence(first)));

        ObjectNode result = builder.build();

        assertThat(result.path("cells")).hasSize(60);
        assertThat(result.path("summary").path("incompleteCellCount").asInt()).isEqualTo(59);
        assertThat(result.path("resultStatus").asText()).isEqualTo("INCOMPLETE");
        assertThat(result.path("diagnostics").toString()).contains("MATRIX_NOT_COMPLETE");
        ObjectNode notRun = (ObjectNode) result.path("cells").get(1);
        assertThat(notRun.path("status").asText()).isEqualTo("NOT_RUN");
        assertThat(notRun.path("actualInnerViewport").isNull()).isTrue();
        assertThat(notRun.path("evidenceRefs")).isEmpty();
        assertThat(VERIFIER.verify(result).verified()).isTrue();
    }

    @Test
    void derivesFailedStatusAndDiagnosticsFromObservedCellFacts() {
        CapabilityStudioBrowserMatrixResultBuilder builder = builder("failed");
        CapabilityStudioBrowserMatrixResultBuilder.CellKey first =
                CapabilityStudioBrowserMatrixResultBuilder.expectedCells().get(0);
        builder.fail(first, new CapabilityStudioBrowserMatrixResultBuilder.Viewport(1024, 768),
                true,
                new CapabilityStudioBrowserMatrixResultBuilder.Axe(0, 1),
                2,
                1,
                new CapabilityStudioBrowserMatrixResultBuilder.KeyboardPath(false, 2, 1),
                List.of(evidence(first)),
                1,
                0);

        ObjectNode result = builder.build();

        assertThat(result.path("resultStatus").asText()).isEqualTo("FAILED");
        assertThat(result.path("summary").path("failedCellCount").asInt()).isEqualTo(1);
        assertThat(result.path("diagnostics").toString())
                .contains("AXE_CRITICAL", "AUTHORING_LEAK", "INNER_VIEWPORT_MISMATCH",
                        "KEYBOARD_PATH_INCOMPLETE", "PAGE_HORIZONTAL_OVERFLOW", "P0_FINDING");
        assertThat(VERIFIER.verify(result).verified()).isTrue();
    }

    @Test
    void failsFastForDuplicateAndUnknownCells() {
        CapabilityStudioBrowserMatrixResultBuilder builder = builder("duplicates");
        CapabilityStudioBrowserMatrixResultBuilder.CellKey cell =
                CapabilityStudioBrowserMatrixResultBuilder.expectedCells().get(0);
        builder.pass(cell, cell.viewport(), List.of(evidence(cell)));

        assertThatIllegalStateException()
                .isThrownBy(() -> builder.pass(cell, cell.viewport(), List.of(evidence(cell))));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CapabilityStudioBrowserMatrixResultBuilder.CellKey(
                        "GP-99", "zh-CN", 1440, 900));
    }

    @Test
    void rejectsFabricatedNotRunObservation() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CapabilityStudioBrowserMatrixResultBuilder.CellObservation(
                        "NOT_RUN",
                        new CapabilityStudioBrowserMatrixResultBuilder.Viewport(1440, 900),
                        false,
                        CapabilityStudioBrowserMatrixResultBuilder.Axe.clear(),
                        0,
                        0,
                        CapabilityStudioBrowserMatrixResultBuilder.KeyboardPath.notRun(),
                        List.of(new CapabilityStudioBrowserMatrixResultBuilder.EvidenceRef(
                                "evidence:fake", FINGERPRINT)),
                        0,
                        0));
    }

    @Test
    void dirtyCandidateIsTruthfullyFailedEvenWhenEveryCellPasses() {
        CapabilityStudioBrowserMatrixResultBuilder builder = builder("dirty");
        for (CapabilityStudioBrowserMatrixResultBuilder.CellKey cell
                : CapabilityStudioBrowserMatrixResultBuilder.expectedCells()) {
            builder.pass(cell, cell.viewport(), List.of(evidence(cell)));
        }
        ObjectNode result = builder.build();
        assertThat(result.path("resultStatus").asText()).isEqualTo("FAILED");
        assertThat(result.path("diagnostics").toString()).contains("CANDIDATE_SOURCE_TREE_DIRTY");
        assertThat(VERIFIER.verify(result).verified()).isTrue();
    }

    @Test
    void outputIsDeterministicAndClosureChangesWhenEvidenceChanges() throws Exception {
        List<CapabilityStudioBrowserMatrixResultBuilder.CellKey> cells =
                new ArrayList<>(CapabilityStudioBrowserMatrixResultBuilder.expectedCells());
        CapabilityStudioBrowserMatrixResultBuilder first = builder("deterministic");
        for (CapabilityStudioBrowserMatrixResultBuilder.CellKey cell : cells) {
            first.pass(cell, cell.viewport(), List.of(evidence(cell)));
        }
        Collections.reverse(cells);
        CapabilityStudioBrowserMatrixResultBuilder second = builder("deterministic");
        for (CapabilityStudioBrowserMatrixResultBuilder.CellKey cell : cells) {
            second.pass(cell, cell.viewport(), List.of(evidence(cell)));
        }

        assertThat(JSON.writeValueAsBytes(first.build()))
                .isEqualTo(JSON.writeValueAsBytes(second.build()));
        CapabilityStudioBrowserMatrixResultBuilder changedBuilder = builder("deterministic");
        for (CapabilityStudioBrowserMatrixResultBuilder.CellKey cell
                : CapabilityStudioBrowserMatrixResultBuilder.expectedCells()) {
            changedBuilder.pass(cell, cell.viewport(), List.of(evidence(cell,
                    "sha256:" + "b".repeat(64))));
        }
        assertThat(changedBuilder.build().path("evidenceClosureFingerprint").asText())
                .isNotEqualTo(first.build().path("evidenceClosureFingerprint").asText());
    }

    @Test
    void cliReturnsPayloadFreeProcessOutcomes(@TempDir Path tempDir) throws Exception {
        Path complete = tempDir.resolve("complete.json");
        Files.write(complete, builderBytes("cli"));
        ByteArrayOutputStream completeOutput = new ByteArrayOutputStream();

        int completeExit = CapabilityStudioBrowserMatrixResultCli.run(
                new String[]{complete.toString()},
                new PrintStream(completeOutput),
                new PrintStream(new ByteArrayOutputStream()));

        assertThat(completeExit).isZero();
        assertThat(completeOutput.toString(StandardCharsets.UTF_8))
                .isEqualTo("VALID status=COMPLETE\n");

        Path invalid = tempDir.resolve("invalid.json");
        Files.writeString(invalid, "{not-json", StandardCharsets.UTF_8);
        ByteArrayOutputStream invalidOutput = new ByteArrayOutputStream();
        int invalidExit = CapabilityStudioBrowserMatrixResultCli.run(
                new String[]{invalid.toString()},
                new PrintStream(invalidOutput),
                new PrintStream(new ByteArrayOutputStream()));

        assertThat(invalidExit).isEqualTo(2);
        assertThat(invalidOutput.toString(StandardCharsets.UTF_8))
                .isEqualTo("INVALID errorCode=RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_INVALID_JSON\n");
        assertThat(invalidOutput.toString(StandardCharsets.UTF_8)).doesNotContain("not-json");

        Path incomplete = tempDir.resolve("incomplete.json");
        Files.write(incomplete, builder("incomplete").buildBytes());
        ByteArrayOutputStream incompleteOutput = new ByteArrayOutputStream();
        int incompleteExit = CapabilityStudioBrowserMatrixResultCli.run(
                new String[]{incomplete.toString()},
                new PrintStream(incompleteOutput),
                new PrintStream(new ByteArrayOutputStream()));
        assertThat(incompleteExit).isEqualTo(3);
        assertThat(incompleteOutput.toString(StandardCharsets.UTF_8))
                .isEqualTo("VALID status=INCOMPLETE\n");
    }

    private static CapabilityStudioBrowserMatrixResultBuilder builder(String resultId) {
        return new CapabilityStudioBrowserMatrixResultBuilder(
                "BMR-" + resultId,
                1,
                "v1",
                new CapabilityStudioBrowserMatrixResultBuilder.Candidate(
                        "build:capability-studio:1",
                        "1.0.0",
                        FINGERPRINT,
                        "a".repeat(40),
                        resultId.equals("dirty") ? "DIRTY" : "CLEAN"),
                new CapabilityStudioBrowserMatrixResultBuilder.BaselineRef(
                        "capability-studio-baseline-v1", 1, FINGERPRINT),
                new CapabilityStudioBrowserMatrixResultBuilder.Environment(
                        FINGERPRINT, "test", "chrome", "151.0", "150.0", "4.11.0"),
                new CapabilityStudioBrowserMatrixResultBuilder.ExecutionWindow(
                        "2026-08-18T10:00:00Z", "2026-08-18T10:10:00Z"));
    }

    private static CapabilityStudioBrowserMatrixResultBuilder.EvidenceRef evidence(
            CapabilityStudioBrowserMatrixResultBuilder.CellKey cell) {
        return evidence(cell, FINGERPRINT);
    }

    private static CapabilityStudioBrowserMatrixResultBuilder.EvidenceRef evidence(
            CapabilityStudioBrowserMatrixResultBuilder.CellKey cell,
            String fingerprint) {
        return new CapabilityStudioBrowserMatrixResultBuilder.EvidenceRef(
                "evidence:" + cell.cellId(), fingerprint);
    }

    private static byte[] builderBytes(String resultId) {
        CapabilityStudioBrowserMatrixResultBuilder builder = builder(resultId);
        for (CapabilityStudioBrowserMatrixResultBuilder.CellKey cell
                : CapabilityStudioBrowserMatrixResultBuilder.expectedCells()) {
            builder.pass(cell, cell.viewport(), List.of(evidence(cell)));
        }
        return builder.buildBytes();
    }
}
