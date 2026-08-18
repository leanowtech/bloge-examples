package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic producer for the payload-free Capability Studio Browser Matrix Result v1.
 *
 * <p>The builder owns the complete 10 x 2 x 3 denominator. A fresh builder contains 60 explicit
 * {@code NOT_RUN} cells, so a caller can record only observations obtained by a real browser and
 * still produce a truthful, complete matrix artifact.</p>
 */
public final class CapabilityStudioBrowserMatrixResultBuilder {
    /** Result schema version emitted by this builder. */
    public static final String SCHEMA_VERSION =
            "bloge.capabilityStudioBrowserMatrixResult.v1";
    /** Acceptance contract identity emitted by this builder. */
    public static final String CONTRACT_ID = "S0-AC-01";
    /** Fixed browser matrix identity emitted by this builder. */
    public static final String MATRIX_ID = "S0-AC-01.browser.v1";
    /** Fixed matrix denominator. */
    public static final int EXPECTED_CELL_COUNT = 60;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> GOLDEN_PATHS = List.of(
            "GP-01", "GP-02", "GP-03", "GP-04", "GP-05",
            "GP-06", "GP-07", "GP-08", "GP-09", "GP-10");
    private static final List<String> LOCALES = List.of("zh-CN", "en-US");
    private static final List<Viewport> VIEWPORTS = List.of(
            new Viewport(1440, 900), new Viewport(1024, 768), new Viewport(390, 844));
    private static final Set<String> STATUSES = Set.of("PASS", "FAIL", "NOT_RUN", "SKIPPED");

    private final String resultId;
    private final int revision;
    private final String contractRevision;
    private final Candidate candidate;
    private final BaselineRef baselineRef;
    private final Environment environment;
    private final ExecutionWindow executionWindow;
    private final Map<CellKey, CellObservation> observations = new LinkedHashMap<>();
    private final Set<CellKey> recordedCells = new LinkedHashSet<>();

    /**
     * Creates a producer bound to one candidate, baseline, environment and execution window.
     *
     * @param resultId result artifact identity
     * @param revision result artifact revision
     * @param contractRevision acceptance contract revision
     * @param candidate candidate artifact binding
     * @param baselineRef acceptance baseline binding
     * @param environment browser environment binding
     * @param executionWindow browser execution window
     */
    public CapabilityStudioBrowserMatrixResultBuilder(
            String resultId,
            int revision,
            String contractRevision,
            Candidate candidate,
            BaselineRef baselineRef,
            Environment environment,
            ExecutionWindow executionWindow) {
        this.resultId = requirePattern(resultId, "resultId", "BMR-[A-Za-z0-9._-]{1,120}");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        this.revision = revision;
        this.contractRevision = requireSafeRef(contractRevision, "contractRevision");
        this.candidate = Objects.requireNonNull(candidate, "candidate is required");
        this.baselineRef = Objects.requireNonNull(baselineRef, "baselineRef is required");
        this.environment = Objects.requireNonNull(environment, "environment is required");
        this.executionWindow = Objects.requireNonNull(
                executionWindow, "executionWindow is required");
        executionWindow.validateOrder();
        for (String goldenPath : GOLDEN_PATHS) {
            for (String locale : LOCALES) {
                for (Viewport viewport : VIEWPORTS) {
                    observations.put(
                            new CellKey(goldenPath, locale, viewport.width(), viewport.height()),
                            CellObservation.notRun());
                }
            }
        }
    }

    /**
     * Returns the fixed denominator in its canonical order.
     *
     * @return immutable GP/locale/viewport cell list
     */
    public static List<CellKey> expectedCells() {
        List<CellKey> cells = new ArrayList<>(EXPECTED_CELL_COUNT);
        for (String goldenPath : GOLDEN_PATHS) {
            for (String locale : LOCALES) {
                for (Viewport viewport : VIEWPORTS) {
                    cells.add(new CellKey(
                            goldenPath, locale, viewport.width(), viewport.height()));
                }
            }
        }
        return List.copyOf(cells);
    }

    /**
     * Records one real browser observation; duplicate and unknown cells fail immediately.
     *
     * @param cell fixed cell identity
     * @param observation typed browser observation
     * @return this builder
     */
    public CapabilityStudioBrowserMatrixResultBuilder record(
            CellKey cell,
            CellObservation observation) {
        Objects.requireNonNull(cell, "cell is required");
        Objects.requireNonNull(observation, "observation is required");
        if (!observations.containsKey(cell)) {
            throw new IllegalArgumentException("unknown browser matrix cell: " + cell.cellId());
        }
        if (!recordedCells.add(cell)) {
            throw new IllegalStateException("browser matrix cell already recorded: " + cell.cellId());
        }
        observations.put(cell, observation);
        return this;
    }

    /**
     * Alias for callers whose browser harness names the operation explicitly.
     *
     * @param cell fixed cell identity
     * @param observation typed browser observation
     * @return this builder
     */
    public CapabilityStudioBrowserMatrixResultBuilder recordObservation(
            CellKey cell,
            CellObservation observation) {
        return record(cell, observation);
    }

    /**
     * Records an observed pass with its evidence references.
     *
     * @param cell fixed cell identity
     * @param actualInnerViewport measured inner viewport
     * @param evidenceRefs payload-free evidence references
     * @return this builder
     */
    public CapabilityStudioBrowserMatrixResultBuilder pass(
            CellKey cell,
            Viewport actualInnerViewport,
            List<EvidenceRef> evidenceRefs) {
        return record(cell, CellObservation.pass(actualInnerViewport, evidenceRefs));
    }

    /**
     * Records an observed failure with the browser facts and evidence retained for that cell.
     *
     * @param cell fixed cell identity
     * @param actualInnerViewport measured inner viewport
     * @param pageHorizontalOverflow measured overflow flag
     * @param axe measured accessibility counts
     * @param technicalIdCount measured technical id count
     * @param rawJsonCount measured raw-json leak count
     * @param keyboardPath measured keyboard traversal
     * @param evidenceRefs payload-free evidence references
     * @param p0Count measured P0 finding count
     * @param p1Count measured P1 finding count
     * @return this builder
     */
    public CapabilityStudioBrowserMatrixResultBuilder fail(
            CellKey cell,
            Viewport actualInnerViewport,
            boolean pageHorizontalOverflow,
            Axe axe,
            int technicalIdCount,
            int rawJsonCount,
            KeyboardPath keyboardPath,
            List<EvidenceRef> evidenceRefs,
            int p0Count,
            int p1Count) {
        return record(cell, CellObservation.fail(
                actualInnerViewport,
                pageHorizontalOverflow,
                axe,
                technicalIdCount,
                rawJsonCount,
                keyboardPath,
                evidenceRefs,
                p0Count,
                p1Count));
    }

    /**
     * Records an explicit non-execution outcome with no fabricated browser observation.
     *
     * @param cell fixed cell identity
     * @return this builder
     */
    public CapabilityStudioBrowserMatrixResultBuilder notRun(CellKey cell) {
        return record(cell, CellObservation.notRun());
    }

    /**
     * Records an explicit skip with no fabricated browser observation.
     *
     * @param cell fixed cell identity
     * @return this builder
     */
    public CapabilityStudioBrowserMatrixResultBuilder skipped(CellKey cell) {
        return record(cell, CellObservation.skipped());
    }

    /**
     * Produces a detached JSON artifact; subsequent builder changes cannot mutate this result.
     *
     * @return strict payload-free result artifact
     */
    public ObjectNode build() {
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("resultId", resultId);
        result.put("revision", revision);
        result.put("contractId", CONTRACT_ID);
        result.put("contractRevision", contractRevision);
        writeCandidate(result.putObject("candidate"));
        writeBaseline(result.putObject("baselineRef"));
        writeEnvironment(result.putObject("environment"));
        writeExecutionWindow(result.putObject("executionWindow"));
        result.put("resultStatus", resultStatus());
        writeMatrix(result.putObject("matrix"));

        ArrayNode cells = result.putArray("cells");
        observations.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> expectedCells().indexOf(entry.getKey())))
                .forEach(entry -> writeCell(cells.addObject(), entry.getKey(), entry.getValue()));
        writeSummary(result.putObject("summary"));
        result.putArray("diagnostics").addAll(diagnostics());

        ObjectNode material = result.deepCopy();
        result.put("evidenceClosureFingerprint", EvidenceVerificationSupport.sha256Bounded(
                material, CapabilityStudioBrowserMatrixResultVerifier.MAXIMUM_RESULT_BYTES));
        return result;
    }

    /**
     * Produces the exact UTF-8 JSON wire artifact.
     *
     * @return UTF-8 encoded result artifact
     */
    public byte[] buildBytes() {
        try {
            return JSON.writeValueAsBytes(build());
        } catch (Exception failure) {
            throw new IllegalStateException("browser matrix result cannot be serialized", failure);
        }
    }

    /**
     * Alias for producer-oriented callers.
     *
     * @return strict payload-free result artifact
     */
    public ObjectNode produce() {
        return build();
    }

    private void writeCandidate(ObjectNode target) {
        target.put("buildRef", candidate.buildRef());
        target.put("revision", candidate.revision());
        target.put("artifactFingerprint", candidate.artifactFingerprint());
        target.put("sourceCommit", candidate.sourceCommit());
        target.put("sourceTreeStatus", candidate.sourceTreeStatus());
    }

    private void writeBaseline(ObjectNode target) {
        target.put("id", baselineRef.id());
        target.put("revision", baselineRef.revision());
        target.put("fingerprint", baselineRef.fingerprint());
    }

    private void writeEnvironment(ObjectNode target) {
        target.put("environmentFingerprint", environment.environmentFingerprint());
        target.put("profile", environment.profile());
        target.put("browserName", environment.browserName());
        target.put("browserVersion", environment.browserVersion());
        target.put("driverVersion", environment.driverVersion());
        target.put("axeVersion", environment.axeVersion());
    }

    private void writeExecutionWindow(ObjectNode target) {
        target.put("startedAt", executionWindow.startedAt());
        target.put("completedAt", executionWindow.completedAt());
    }

    private static void writeMatrix(ObjectNode target) {
        target.put("matrixId", MATRIX_ID);
        ArrayNode goldenPaths = target.putArray("goldenPathIds");
        GOLDEN_PATHS.forEach(goldenPaths::add);
        ArrayNode locales = target.putArray("locales");
        LOCALES.forEach(locales::add);
        ArrayNode viewports = target.putArray("viewports");
        VIEWPORTS.forEach(viewport -> writeViewport(viewports.addObject(), viewport));
        target.put("expectedCellCount", EXPECTED_CELL_COUNT);
    }

    private static void writeCell(ObjectNode target, CellKey key, CellObservation observation) {
        target.put("cellId", key.cellId());
        target.put("goldenPathId", key.goldenPathId());
        target.put("locale", key.locale());
        writeViewport(target.putObject("viewport"), key.viewport());
        if (observation.actualInnerViewport() == null) {
            target.putNull("actualInnerViewport");
        } else {
            writeViewport(target.putObject("actualInnerViewport"),
                    observation.actualInnerViewport());
        }
        target.put("status", observation.status());
        target.put("pageHorizontalOverflow", observation.pageHorizontalOverflow());
        target.putObject("axe")
                .put("serious", observation.axe().serious())
                .put("critical", observation.axe().critical());
        target.put("technicalIdCount", observation.technicalIdCount());
        target.put("rawJsonCount", observation.rawJsonCount());
        target.putObject("keyboardPath")
                .put("completed", observation.keyboardPath().completed())
                .put("stepCount", observation.keyboardPath().stepCount())
                .put("focusLossCount", observation.keyboardPath().focusLossCount());
        ArrayNode evidence = target.putArray("evidenceRefs");
        for (EvidenceRef ref : observation.evidenceRefs()) {
            evidence.addObject()
                    .put("evidenceId", ref.evidenceId())
                    .put("fingerprint", ref.fingerprint());
        }
        target.put("p0Count", observation.p0Count());
        target.put("p1Count", observation.p1Count());
    }

    private void writeSummary(ObjectNode target) {
        int pass = 0;
        int incomplete = 0;
        int failed = 0;
        int skipped = 0;
        int p0 = 0;
        int p1 = 0;
        int evidence = 0;
        for (CellObservation observation : observations.values()) {
            switch (observation.status()) {
                case "PASS" -> pass++;
                case "FAIL" -> failed++;
                case "SKIPPED" -> {
                    skipped++;
                    incomplete++;
                }
                case "NOT_RUN" -> incomplete++;
                default -> throw new IllegalStateException("unsupported cell status");
            }
            p0 += observation.p0Count();
            p1 += observation.p1Count();
            evidence += observation.evidenceRefs().size();
        }
        target.put("expectedCellCount", EXPECTED_CELL_COUNT);
        target.put("actualCellCount", observations.size());
        target.put("passCellCount", pass);
        target.put("incompleteCellCount", incomplete);
        target.put("failedCellCount", failed);
        target.put("skippedCount", skipped);
        target.put("p0Count", p0);
        target.put("p1Count", p1);
        target.put("evidenceRefCount", evidence);
    }

    private String resultStatus() {
        boolean dirty = "DIRTY".equals(candidate.sourceTreeStatus());
        boolean failed = observations.values().stream().anyMatch(o -> "FAIL".equals(o.status())
                || o.p0Count() > 0 || o.p1Count() > 0);
        if (dirty || failed) {
            return "FAILED";
        }
        return observations.values().stream().anyMatch(o -> !"PASS".equals(o.status()))
                ? "INCOMPLETE"
                : "COMPLETE";
    }

    private ArrayNode diagnostics() {
        Set<String> codes = new LinkedHashSet<>();
        if ("DIRTY".equals(candidate.sourceTreeStatus())) {
            codes.add("CANDIDATE_SOURCE_TREE_DIRTY");
        }
        if (observations.values().stream().anyMatch(o -> "FAIL".equals(o.status()))) {
            codes.add("MATRIX_CELL_FAILED");
        }
        if (observations.values().stream().anyMatch(o -> "NOT_RUN".equals(o.status()))) {
            codes.add("MATRIX_NOT_COMPLETE");
        }
        if (observations.values().stream().anyMatch(o -> "SKIPPED".equals(o.status()))) {
            codes.add("MATRIX_CELL_SKIPPED");
        }
        if (observations.values().stream().anyMatch(o -> o.pageHorizontalOverflow())) {
            codes.add("PAGE_HORIZONTAL_OVERFLOW");
        }
        if (observations.values().stream().anyMatch(o -> o.axe().serious() > 0)) {
            codes.add("AXE_SERIOUS");
        }
        if (observations.values().stream().anyMatch(o -> o.axe().critical() > 0)) {
            codes.add("AXE_CRITICAL");
        }
        if (observations.values().stream().anyMatch(o -> o.technicalIdCount() > 0
                || o.rawJsonCount() > 0)) {
            codes.add("AUTHORING_LEAK");
        }
        if (observations.entrySet().stream().anyMatch(entry -> {
            CellObservation observation = entry.getValue();
            return observation.actualInnerViewport() != null
                    && !observation.actualInnerViewport().equals(entry.getKey().viewport());
        })) {
            codes.add("INNER_VIEWPORT_MISMATCH");
        }
        if (observations.values().stream().anyMatch(o -> !o.keyboardPath().completed()
                || o.keyboardPath().stepCount() < 1
                || o.keyboardPath().focusLossCount() > 0)) {
            codes.add("KEYBOARD_PATH_INCOMPLETE");
        }
        if (observations.values().stream().anyMatch(o -> o.p0Count() > 0)) {
            codes.add("P0_FINDING");
        }
        if (observations.values().stream().anyMatch(o -> o.p1Count() > 0)) {
            codes.add("P1_FINDING");
        }
        ArrayNode result = JSON.createArrayNode();
        codes.stream().sorted().forEach(code -> result.addObject().put("code", code));
        return result;
    }

    private static void writeViewport(ObjectNode target, Viewport viewport) {
        target.put("width", viewport.width());
        target.put("height", viewport.height());
    }

    private static String requireSafeRef(String value, String field) {
        return requirePattern(value, field, "[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,511}");
    }

    private static String requireFingerprint(String value, String field) {
        return requirePattern(value, field, "sha256:[A-Fa-f0-9]{64}");
    }

    private static String requirePattern(String value, String field, String pattern) {
        if (value == null || !value.matches(pattern)) {
            throw new IllegalArgumentException(field + " has invalid protocol shape");
        }
        return value;
    }

    /**
     * Fixed browser viewport. Only the three protocol viewports can construct a matrix key.
     *
     * @param width viewport width
     * @param height viewport height
     */
    public record Viewport(int width, int height) {
        /** Validates that the viewport belongs to the fixed matrix. */
        public Viewport {
            if (!isFixedViewport(width, height)) {
                throw new IllegalArgumentException("viewport is outside the fixed browser matrix");
            }
        }

        private static boolean isFixedViewport(int width, int height) {
            return width == 1440 && height == 900
                    || width == 1024 && height == 768
                    || width == 390 && height == 844;
        }
    }

    /**
     * Identity of one fixed matrix cell.
     *
     * @param goldenPathId fixed golden path identity
     * @param locale fixed locale
     * @param viewport fixed viewport
     */
    public record CellKey(String goldenPathId, String locale, Viewport viewport) {
        /** Validates that the identity belongs to the fixed matrix. */
        public CellKey {
            if (!GOLDEN_PATHS.contains(goldenPathId)) {
                throw new IllegalArgumentException("unknown golden path: " + goldenPathId);
            }
            if (!LOCALES.contains(locale)) {
                throw new IllegalArgumentException("unknown locale: " + locale);
            }
            Objects.requireNonNull(viewport, "viewport is required");
        }

        /**
         * Creates a cell key from fixed viewport dimensions.
         *
         * @param goldenPathId fixed golden path identity
         * @param locale fixed locale
         * @param width viewport width
         * @param height viewport height
         */
        public CellKey(String goldenPathId, String locale, int width, int height) {
            this(goldenPathId, locale, new Viewport(width, height));
        }

        /**
         * Returns the canonical cell id.
         *
         * @return canonical GP/locale/viewport id
         */
        public String cellId() {
            return goldenPathId + ":" + locale + ":"
                    + viewport.width() + "x" + viewport.height();
        }
    }

    /**
     * Candidate identity bound into every produced artifact.
     *
     * @param buildRef candidate build reference
     * @param revision candidate revision
     * @param artifactFingerprint candidate artifact fingerprint
     * @param sourceCommit candidate source commit
     * @param sourceTreeStatus candidate source tree status
     */
    public record Candidate(
            String buildRef,
            String revision,
            String artifactFingerprint,
            String sourceCommit,
            String sourceTreeStatus) {
        /** Validates the candidate protocol fields. */
        public Candidate {
            buildRef = requireSafeRef(buildRef, "candidate.buildRef");
            revision = requireSafeRef(revision, "candidate.revision");
            artifactFingerprint = requireFingerprint(
                    artifactFingerprint, "candidate.artifactFingerprint");
            sourceCommit = requirePattern(
                    sourceCommit, "candidate.sourceCommit", "[a-f0-9]{7,64}");
            if (!"CLEAN".equals(sourceTreeStatus) && !"DIRTY".equals(sourceTreeStatus)) {
                throw new IllegalArgumentException("candidate.sourceTreeStatus is invalid");
            }
        }
    }

    /**
     * Acceptance baseline identity bound into every produced artifact.
     *
     * @param id baseline identity
     * @param revision baseline revision
     * @param fingerprint baseline fingerprint
     */
    public record BaselineRef(String id, int revision, String fingerprint) {
        /** Validates the baseline protocol fields. */
        public BaselineRef {
            id = requireSafeRef(id, "baselineRef.id");
            if (revision < 1) {
                throw new IllegalArgumentException("baselineRef.revision must be positive");
            }
            fingerprint = requireFingerprint(fingerprint, "baselineRef.fingerprint");
        }
    }

    /**
     * Browser, driver and axe environment identity bound into every produced artifact.
     *
     * @param environmentFingerprint environment fingerprint
     * @param profile environment profile
     * @param browserName browser name
     * @param browserVersion browser version
     * @param driverVersion driver version
     * @param axeVersion axe version
     */
    public record Environment(
            String environmentFingerprint,
            String profile,
            String browserName,
            String browserVersion,
            String driverVersion,
            String axeVersion) {
        /** Validates the environment protocol fields. */
        public Environment {
            environmentFingerprint = requireFingerprint(
                    environmentFingerprint, "environment.environmentFingerprint");
            profile = requireSafeRef(profile, "environment.profile");
            browserName = requireSafeRef(browserName, "environment.browserName");
            browserVersion = requireSafeRef(browserVersion, "environment.browserVersion");
            driverVersion = requireSafeRef(driverVersion, "environment.driverVersion");
            axeVersion = requireSafeRef(axeVersion, "environment.axeVersion");
        }
    }

    /**
     * Inclusive execution window for the browser evidence.
     *
     * @param startedAt ISO-8601 start time
     * @param completedAt ISO-8601 completion time
     */
    public record ExecutionWindow(String startedAt, String completedAt) {
        /** Validates the timestamp fields. */
        public ExecutionWindow {
            parse(startedAt, "executionWindow.startedAt");
            parse(completedAt, "executionWindow.completedAt");
        }

        /**
         * Creates a window from offset date-time values.
         *
         * @param startedAt start time
         * @param completedAt completion time
         */
        public ExecutionWindow(OffsetDateTime startedAt, OffsetDateTime completedAt) {
            this(Objects.requireNonNull(startedAt, "startedAt is required").toString(),
                    Objects.requireNonNull(completedAt, "completedAt is required").toString());
        }

        private static OffsetDateTime parse(String value, String field) {
            if (value == null) {
                throw new IllegalArgumentException(field + " is required");
            }
            try {
                return OffsetDateTime.parse(value);
            } catch (DateTimeParseException failure) {
                throw new IllegalArgumentException(field + " is not date-time", failure);
            }
        }

        private void validateOrder() {
            if (parse(completedAt, "executionWindow.completedAt")
                    .isBefore(parse(startedAt, "executionWindow.startedAt"))) {
                throw new IllegalArgumentException("execution window completes before it starts");
            }
        }
    }

    /**
     * Accessibility scan counts retained in one cell observation.
     *
     * @param serious serious violation count
     * @param critical critical violation count
     */
    public record Axe(int serious, int critical) {
        /** Validates non-negative accessibility counts. */
        public Axe {
            requireNonNegative(serious, "axe.serious");
            requireNonNegative(critical, "axe.critical");
        }

        /**
         * Returns zero accessibility counts.
         *
         * @return clear axe counts
         */
        public static Axe clear() {
            return new Axe(0, 0);
        }
    }

    /**
     * Keyboard traversal facts retained in one cell observation.
     *
     * @param completed whether traversal completed
     * @param stepCount traversal step count
     * @param focusLossCount focus loss count
     */
    public record KeyboardPath(boolean completed, int stepCount, int focusLossCount) {
        /** Validates non-negative keyboard counts. */
        public KeyboardPath {
            requireNonNegative(stepCount, "keyboardPath.stepCount");
            requireNonNegative(focusLossCount, "keyboardPath.focusLossCount");
        }

        /**
         * Creates a completed keyboard path.
         *
         * @param stepCount traversal step count
         * @return completed keyboard path
         */
        public static KeyboardPath complete(int stepCount) {
            if (stepCount < 1) {
                throw new IllegalArgumentException("completed keyboard path needs a step");
            }
            return new KeyboardPath(true, stepCount, 0);
        }

        /**
         * Returns the zero-observation keyboard path for a non-executed cell.
         *
         * @return empty keyboard path
         */
        public static KeyboardPath notRun() {
            return new KeyboardPath(false, 0, 0);
        }
    }

    /**
     * Evidence reference only; business payload and raw JSON cannot enter this API.
     *
     * @param evidenceId evidence identity
     * @param fingerprint evidence fingerprint
     */
    public record EvidenceRef(String evidenceId, String fingerprint) {
        /** Validates the evidence reference fields. */
        public EvidenceRef {
            evidenceId = requireSafeRef(evidenceId, "evidenceRef.evidenceId");
            fingerprint = requireFingerprint(fingerprint, "evidenceRef.fingerprint");
        }
    }

    /**
     * Typed, payload-free browser facts for one fixed cell.
     *
     * @param status cell status
     * @param actualInnerViewport measured inner viewport, or null when not executed
     * @param pageHorizontalOverflow measured overflow flag
     * @param axe measured accessibility counts
     * @param technicalIdCount measured technical id count
     * @param rawJsonCount measured raw-json leak count
     * @param keyboardPath measured keyboard traversal
     * @param evidenceRefs payload-free evidence references
     * @param p0Count measured P0 finding count
     * @param p1Count measured P1 finding count
     */
    public record CellObservation(
            String status,
            Viewport actualInnerViewport,
            boolean pageHorizontalOverflow,
            Axe axe,
            int technicalIdCount,
            int rawJsonCount,
            KeyboardPath keyboardPath,
            List<EvidenceRef> evidenceRefs,
            int p0Count,
            int p1Count) {
        /** Validates observed and non-executed cell invariants and canonicalizes evidence order. */
        public CellObservation {
            if (!STATUSES.contains(status)) {
                throw new IllegalArgumentException("cell status is invalid");
            }
            axe = Objects.requireNonNull(axe, "axe is required");
            keyboardPath = Objects.requireNonNull(keyboardPath, "keyboardPath is required");
            requireNonNegative(technicalIdCount, "technicalIdCount");
            requireNonNegative(rawJsonCount, "rawJsonCount");
            requireNonNegative(p0Count, "p0Count");
            requireNonNegative(p1Count, "p1Count");
            evidenceRefs = sortedEvidence(evidenceRefs);
            if ("PASS".equals(status) || "FAIL".equals(status)) {
                if (actualInnerViewport == null || evidenceRefs.isEmpty()) {
                    throw new IllegalArgumentException(
                            "observed cell requires actual viewport and evidence");
                }
            } else if (actualInnerViewport != null
                    || pageHorizontalOverflow
                    || !Axe.clear().equals(axe)
                    || technicalIdCount != 0
                    || rawJsonCount != 0
                    || keyboardPath.completed()
                    || keyboardPath.stepCount() != 0
                    || keyboardPath.focusLossCount() != 0
                    || !evidenceRefs.isEmpty()
                    || p0Count != 0
                    || p1Count != 0) {
                throw new IllegalArgumentException(
                        "non-executed cell cannot contain browser observations or evidence");
            }
        }

        /**
         * Creates a passing observation.
         *
         * @param actualInnerViewport measured inner viewport
         * @param evidenceRefs payload-free evidence references
         * @return passing observation
         */
        public static CellObservation pass(Viewport actualInnerViewport,
                                           List<EvidenceRef> evidenceRefs) {
            return new CellObservation("PASS", actualInnerViewport, false,
                    Axe.clear(), 0, 0, KeyboardPath.complete(1), evidenceRefs, 0, 0);
        }

        /**
         * Creates a failed observation from measured browser facts.
         *
         * @param actualInnerViewport measured inner viewport
         * @param pageHorizontalOverflow measured overflow flag
         * @param axe measured accessibility counts
         * @param technicalIdCount measured technical id count
         * @param rawJsonCount measured raw-json leak count
         * @param keyboardPath measured keyboard traversal
         * @param evidenceRefs payload-free evidence references
         * @param p0Count measured P0 finding count
         * @param p1Count measured P1 finding count
         * @return failed observation
         */
        public static CellObservation fail(Viewport actualInnerViewport,
                                           boolean pageHorizontalOverflow,
                                           Axe axe,
                                           int technicalIdCount,
                                           int rawJsonCount,
                                           KeyboardPath keyboardPath,
                                           List<EvidenceRef> evidenceRefs,
                                           int p0Count,
                                           int p1Count) {
            return new CellObservation("FAIL", actualInnerViewport,
                    pageHorizontalOverflow, axe, technicalIdCount, rawJsonCount, keyboardPath,
                    evidenceRefs, p0Count, p1Count);
        }

        /**
         * Creates a zero-observation NOT_RUN cell.
         *
         * @return explicit non-execution observation
         */
        public static CellObservation notRun() {
            return new CellObservation("NOT_RUN", null, false, Axe.clear(), 0, 0,
                    KeyboardPath.notRun(), List.of(), 0, 0);
        }

        /**
         * Creates a zero-observation SKIPPED cell.
         *
         * @return explicit skip observation
         */
        public static CellObservation skipped() {
            return new CellObservation("SKIPPED", null, false, Axe.clear(), 0, 0,
                    KeyboardPath.notRun(), List.of(), 0, 0);
        }

        private static List<EvidenceRef> sortedEvidence(List<EvidenceRef> refs) {
            List<EvidenceRef> sorted = new ArrayList<>(
                    refs == null ? List.of() : refs);
            if (sorted.size() > 32) {
                throw new IllegalArgumentException("a cell cannot contain more than 32 evidence refs");
            }
            sorted.sort(Comparator.comparing(EvidenceRef::evidenceId));
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i - 1).evidenceId().equals(sorted.get(i).evidenceId())) {
                    throw new IllegalArgumentException("duplicate evidence reference");
                }
            }
            return List.copyOf(sorted);
        }
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }
}
        /**
         * Returns zero accessibility counts.
         *
         * @return clear axe counts
         */
        /**
         * Returns the zero-observation keyboard path for a non-executed cell.
         *
         * @return empty keyboard path
         */
        /**
         * Creates a zero-observation NOT_RUN cell.
         *
         * @return explicit non-execution observation
         */
        /**
         * Creates a zero-observation SKIPPED cell.
         *
         * @return explicit skip observation
         */
