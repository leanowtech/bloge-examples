package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic test-side producer for the fixed Stage 0 browser matrix artifact. */
final class CapabilityStudioBrowserMatrixArtifact {
    static final List<String> GOLDEN_PATHS = List.of(
            "GP-01", "GP-02", "GP-03", "GP-04", "GP-05",
            "GP-06", "GP-07", "GP-08", "GP-09", "GP-10");
    static final List<String> LOCALES = List.of("zh-CN", "en-US");
    static final List<Viewport> VIEWPORTS = List.of(
            new Viewport(1440, 900),
            new Viewport(1024, 768),
            new Viewport(390, 844));
    static final int EXPECTED_CELL_COUNT = 60;

    private static final ObjectMapper JSON = new ObjectMapper();

    record Viewport(int width, int height) {
        String coordinate() {
            return width + "x" + height;
        }
    }

    record Candidate(
            String buildRef,
            String revision,
            String artifactFingerprint,
            String sourceCommit,
            String sourceTreeStatus) {
    }

    record Baseline(String id, int revision, String fingerprint) {
    }

    record Environment(
            String environmentFingerprint,
            String profile,
            String browserName,
            String browserVersion,
            String driverVersion,
            String axeVersion) {
    }

    record EvidenceRef(String evidenceId, String fingerprint) {
    }

    record Observation(
            String goldenPathId,
            String locale,
            Viewport viewport,
            Viewport actualInnerViewport,
            String status,
            boolean pageHorizontalOverflow,
            int axeSerious,
            int axeCritical,
            int technicalIdCount,
            int rawJsonCount,
            boolean keyboardCompleted,
            int keyboardStepCount,
            int focusLossCount,
            List<EvidenceRef> evidenceRefs,
            int p0Count,
            int p1Count) {
        Observation {
            if (!GOLDEN_PATHS.contains(goldenPathId)) {
                throw new IllegalArgumentException("goldenPathId is outside the fixed browser matrix");
            }
            if (!LOCALES.contains(locale)) {
                throw new IllegalArgumentException("locale is outside the fixed browser matrix");
            }
            if (!VIEWPORTS.contains(viewport)) {
                throw new IllegalArgumentException("viewport is outside the fixed browser matrix");
            }
            if (!List.of("PASS", "FAIL", "NOT_RUN", "SKIPPED").contains(status)) {
                throw new IllegalArgumentException("status is invalid");
            }
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            if (List.of(axeSerious, axeCritical, technicalIdCount, rawJsonCount,
                    keyboardStepCount, focusLossCount, p0Count, p1Count)
                    .stream().anyMatch(value -> value < 0)) {
                throw new IllegalArgumentException("browser observation counts cannot be negative");
            }
            if (("NOT_RUN".equals(status) || "SKIPPED".equals(status))
                    && (actualInnerViewport != null
                    || pageHorizontalOverflow
                    || axeSerious != 0
                    || axeCritical != 0
                    || technicalIdCount != 0
                    || rawJsonCount != 0
                    || keyboardCompleted
                    || keyboardStepCount != 0
                    || focusLossCount != 0
                    || !evidenceRefs.isEmpty()
                    || p0Count != 0
                    || p1Count != 0)) {
                throw new IllegalArgumentException("unexecuted cells cannot carry observations");
            }
        }

        static Observation notRun(String goldenPathId, String locale, Viewport viewport) {
            return new Observation(
                    goldenPathId, locale, viewport, null, "NOT_RUN", false,
                    0, 0, 0, 0, false, 0, 0, List.of(), 0, 0);
        }

        String cellId() {
            return goldenPathId + ":" + locale + ":" + viewport.coordinate();
        }
    }

    private final String resultId;
    private final int revision;
    private final String contractRevision;
    private final Candidate candidate;
    private final Baseline baseline;
    private final Environment environment;
    private final OffsetDateTime startedAt;
    private final Map<String, Observation> observations = new LinkedHashMap<>();

    CapabilityStudioBrowserMatrixArtifact(
            String resultId,
            int revision,
            String contractRevision,
            Candidate candidate,
            Baseline baseline,
            Environment environment,
            OffsetDateTime startedAt) {
        this.resultId = resultId;
        this.revision = revision;
        this.contractRevision = contractRevision;
        this.candidate = candidate;
        this.baseline = baseline;
        this.environment = environment;
        this.startedAt = startedAt;
    }

    void record(Observation observation) {
        if (observations.putIfAbsent(observation.cellId(), observation) != null) {
            throw new IllegalArgumentException("duplicate browser matrix cell: " + observation.cellId());
        }
    }

    ObjectNode build(OffsetDateTime completedAt) {
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", "bloge.capabilityStudioBrowserMatrixResult.v1");
        result.put("resultId", resultId);
        result.put("revision", revision);
        result.put("contractId", "S0-AC-01");
        result.put("contractRevision", contractRevision);

        result.putObject("candidate")
                .put("buildRef", candidate.buildRef())
                .put("revision", candidate.revision())
                .put("artifactFingerprint", candidate.artifactFingerprint())
                .put("sourceCommit", candidate.sourceCommit())
                .put("sourceTreeStatus", candidate.sourceTreeStatus());
        result.putObject("baselineRef")
                .put("id", baseline.id())
                .put("revision", baseline.revision())
                .put("fingerprint", baseline.fingerprint());
        result.putObject("environment")
                .put("environmentFingerprint", environment.environmentFingerprint())
                .put("profile", environment.profile())
                .put("browserName", environment.browserName())
                .put("browserVersion", environment.browserVersion())
                .put("driverVersion", environment.driverVersion())
                .put("axeVersion", environment.axeVersion());
        result.putObject("executionWindow")
                .put("startedAt", startedAt.toString())
                .put("completedAt", completedAt.toString());

        ObjectNode matrix = result.putObject("matrix");
        matrix.put("matrixId", "S0-AC-01.browser.v1");
        GOLDEN_PATHS.forEach(matrix.putArray("goldenPathIds")::add);
        LOCALES.forEach(matrix.putArray("locales")::add);
        ArrayNode matrixViewports = matrix.putArray("viewports");
        VIEWPORTS.forEach(viewport -> writeViewport(matrixViewports.addObject(), viewport));
        matrix.put("expectedCellCount", EXPECTED_CELL_COUNT);

        List<Observation> completeCells = new ArrayList<>(EXPECTED_CELL_COUNT);
        for (String goldenPath : GOLDEN_PATHS) {
            for (String locale : LOCALES) {
                for (Viewport viewport : VIEWPORTS) {
                    String cellId = goldenPath + ":" + locale + ":" + viewport.coordinate();
                    completeCells.add(observations.getOrDefault(
                            cellId, Observation.notRun(goldenPath, locale, viewport)));
                }
            }
        }
        ArrayNode cells = result.putArray("cells");
        completeCells.forEach(observation -> writeObservation(cells.addObject(), observation));

        int pass = (int) completeCells.stream().filter(cell -> "PASS".equals(cell.status())).count();
        int failed = (int) completeCells.stream().filter(cell -> "FAIL".equals(cell.status())).count();
        int skipped = (int) completeCells.stream().filter(cell -> "SKIPPED".equals(cell.status())).count();
        int incomplete = EXPECTED_CELL_COUNT - pass - failed;
        int p0 = completeCells.stream().mapToInt(Observation::p0Count).sum();
        int p1 = completeCells.stream().mapToInt(Observation::p1Count).sum();
        int evidence = completeCells.stream().mapToInt(cell -> cell.evidenceRefs().size()).sum();

        ObjectNode summary = result.putObject("summary");
        summary.put("expectedCellCount", EXPECTED_CELL_COUNT);
        summary.put("actualCellCount", completeCells.size());
        summary.put("passCellCount", pass);
        summary.put("incompleteCellCount", incomplete);
        summary.put("failedCellCount", failed);
        summary.put("skippedCount", skipped);
        summary.put("p0Count", p0);
        summary.put("p1Count", p1);
        summary.put("evidenceRefCount", evidence);

        String resultStatus = !"CLEAN".equals(candidate.sourceTreeStatus()) || failed > 0 || p0 > 0 || p1 > 0
                ? "FAILED"
                : incomplete > 0 ? "INCOMPLETE" : "COMPLETE";
        result.put("resultStatus", resultStatus);
        ArrayNode diagnostics = result.putArray("diagnostics");
        Set<String> diagnosticCodes = new LinkedHashSet<>();
        if (!"CLEAN".equals(candidate.sourceTreeStatus())) {
            diagnosticCodes.add("CANDIDATE_SOURCE_TREE_DIRTY");
        }
        if (failed > 0) {
            diagnosticCodes.add("BROWSER_CELL_FAILURE");
        }
        if (incomplete > 0) {
            diagnosticCodes.add("BROWSER_MATRIX_INCOMPLETE");
        }
        if (p0 > 0) {
            diagnosticCodes.add("P0_FINDING_PRESENT");
        }
        if (p1 > 0) {
            diagnosticCodes.add("P1_FINDING_PRESENT");
        }
        diagnosticCodes.forEach(code -> diagnostics.addObject().put("code", code));

        ObjectNode material = result.deepCopy();
        material.remove("evidenceClosureFingerprint");
        result.put("evidenceClosureFingerprint", fingerprint(material));
        return result;
    }

    static String fingerprint(JsonNode value) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(canonical(value));
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalArgumentException("browser matrix material cannot be fingerprinted", failure);
        }
    }

    static String fingerprint(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new IllegalArgumentException("browser evidence cannot be fingerprinted", failure);
        }
    }

    private static void writeObservation(ObjectNode target, Observation observation) {
        target.put("cellId", observation.cellId());
        target.put("goldenPathId", observation.goldenPathId());
        target.put("locale", observation.locale());
        writeViewport(target.putObject("viewport"), observation.viewport());
        if (observation.actualInnerViewport() == null) {
            target.putNull("actualInnerViewport");
        } else {
            writeViewport(target.putObject("actualInnerViewport"), observation.actualInnerViewport());
        }
        target.put("status", observation.status());
        target.put("pageHorizontalOverflow", observation.pageHorizontalOverflow());
        target.putObject("axe")
                .put("serious", observation.axeSerious())
                .put("critical", observation.axeCritical());
        target.put("technicalIdCount", observation.technicalIdCount());
        target.put("rawJsonCount", observation.rawJsonCount());
        target.putObject("keyboardPath")
                .put("completed", observation.keyboardCompleted())
                .put("stepCount", observation.keyboardStepCount())
                .put("focusLossCount", observation.focusLossCount());
        ArrayNode evidence = target.putArray("evidenceRefs");
        observation.evidenceRefs().stream()
                .sorted(Comparator.comparing(EvidenceRef::evidenceId))
                .forEach(ref -> evidence.addObject()
                        .put("evidenceId", ref.evidenceId())
                        .put("fingerprint", ref.fingerprint()));
        target.put("p0Count", observation.p0Count());
        target.put("p1Count", observation.p1Count());
    }

    private static void writeViewport(ObjectNode target, Viewport viewport) {
        target.put("width", viewport.width());
        target.put("height", viewport.height());
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }
}
