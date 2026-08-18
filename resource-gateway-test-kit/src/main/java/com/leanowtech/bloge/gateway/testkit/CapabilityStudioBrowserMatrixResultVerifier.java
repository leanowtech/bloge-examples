package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline, payload-free verifier for the fixed Stage 0 browser acceptance matrix.
 *
 * <p>The matrix is deliberately a protocol invariant rather than a caller-provided count:
 * {@code GP-01..GP-10 x zh-CN/en-US x 1440x900/1024x768/390x844} is always exactly 60 cells.
 * This verifier recomputes cell identity, order, uniqueness, aggregate counts, the result status
 * and the evidence closure fingerprint.</p>
 */
public final class CapabilityStudioBrowserMatrixResultVerifier {
    /** Maximum raw or canonical result size accepted by this verifier. */
    public static final int MAXIMUM_RESULT_BYTES = 4 * 1024 * 1024;

    private static final String SCHEMA_VERSION =
            "bloge.capabilityStudioBrowserMatrixResult.v1";
    private static final List<String> GOLDEN_PATHS = List.of(
            "GP-01", "GP-02", "GP-03", "GP-04", "GP-05",
            "GP-06", "GP-07", "GP-08", "GP-09", "GP-10");
    private static final List<String> LOCALES = List.of("zh-CN", "en-US");
    private static final List<Viewport> VIEWPORTS = List.of(
            new Viewport(1440, 900),
            new Viewport(1024, 768),
            new Viewport(390, 844));
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "payload", "request", "response", "body", "fixture", "mock",
            "rawJson", "screenshot", "html", "trace");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Stable classification of a verification result. */
    public enum FailureKind {
        /** All structural and semantic checks passed. */
        NONE,
        /** The wire document, schema or size boundary is invalid. */
        SCHEMA,
        /** The document is structurally valid but violates a protocol invariant. */
        SEMANTIC
    }

    /** The status truthfully reported by a structurally valid result artifact. */
    public enum ArtifactStatus {
        /** All 60 executed cells passed the browser contract. */
        COMPLETE,
        /** All 60 cells are present but one or more were explicitly not executed. */
        INCOMPLETE,
        /** At least one executed cell truthfully reports a contract failure. */
        FAILED
    }

    /**
     * Payload-free result suitable for CI logs and governance records.
     *
     * @param failureKind structural or semantic verifier outcome
     * @param checks completed check groups
     * @param errorCode stable machine-readable error code, or {@code null} when valid
     * @param artifactStatus truthful status of a valid artifact, or {@code null} when rejected
     */
    public record VerificationResult(
            FailureKind failureKind,
            Set<String> checks,
            String errorCode,
            ArtifactStatus artifactStatus) {
        /** Creates an immutable result and validates the protocol error-code shape. */
        public VerificationResult {
            if (failureKind == null) {
                throw new IllegalArgumentException("failureKind is required");
            }
            checks = checks == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(checks));
            if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("errorCode is not a protocol code");
            }
        }

        /**
         * Indicates that the artifact is structurally and semantically valid. This does not mean
         * the browser acceptance itself passed; inspect {@link #artifactStatus()} for that fact.
         *
         * @return true for a valid COMPLETE, INCOMPLETE or FAILED artifact
         */
        public boolean verified() {
            return failureKind == FailureKind.NONE && errorCode == null;
        }
    }

    private record Viewport(int width, int height) {
    }

    /** Creates a stateless verifier. */
    public CapabilityStudioBrowserMatrixResultVerifier() {
    }

    /**
     * Verifies a decoded browser matrix result.
     *
     * @param result decoded protocol result
     * @return payload-free verification result
     */
    public VerificationResult verify(JsonNode result) {
        VerificationResult schema = verifySchema(result);
        if (!schema.verified()) {
            return schema;
        }
        if (!canonicalFingerprintAvailable(result)) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_SIZE_LIMIT");
        }
        VerificationResult binding = verifyCandidateAndExecution(result);
        if (!binding.verified()) {
            return binding;
        }
        VerificationResult matrix = verifyFixedMatrix(result.path("matrix"));
        if (!matrix.verified()) {
            return matrix;
        }
        VerificationResult cells = verifyCells(result.path("cells"));
        if (!cells.verified()) {
            return cells;
        }
        VerificationResult summary = verifySummary(result);
        if (!summary.verified()) {
            return summary;
        }
        VerificationResult evidence = verifyEvidenceClosure(result);
        if (!evidence.verified()) {
            return evidence;
        }
        VerificationResult status = verifyResultStatus(result);
        if (!status.verified()) {
            return status;
        }
        return valid(
                result,
                "SCHEMA",
                "PAYLOAD_FREE",
                "CANDIDATE_BINDING",
                "EXECUTION_WINDOW",
                "FIXED_MATRIX",
                "CELL_ORDER_UNIQUENESS",
                "CELL_INVARIANTS",
                "SUMMARY",
                "EVIDENCE_CLOSURE",
                "RESULT_STATUS");
    }

    /**
     * Verifies a UTF-8 JSON wire document and enforces the raw byte limit before parsing.
     *
     * @param wireBytes UTF-8 protocol document bytes
     * @return payload-free verification result
     */
    public VerificationResult verify(byte[] wireBytes) {
        if (wireBytes == null || wireBytes.length > MAXIMUM_RESULT_BYTES) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_SIZE_LIMIT");
        }
        try {
            return verify(JSON.readTree(wireBytes));
        } catch (IOException | RuntimeException invalidJson) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_INVALID_JSON");
        }
    }

    private static VerificationResult verifySchema(JsonNode result) {
        if (result == null || !result.isObject()
                || !SCHEMA_VERSION.equals(result.path("schemaVersion").asText())) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_SCHEMA_INVALID");
        }
        if (containsForbiddenField(result)) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_PAYLOAD_FIELD");
        }
        try {
            if (!CapabilityStudioSchemaSupport.validate(
                    result,
                    CapabilityStudioSchemaSupport.BROWSER_MATRIX_RESULT_RESOURCE).isEmpty()) {
                return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_SCHEMA_INVALID");
            }
        } catch (RuntimeException unavailable) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_SCHEMA_UNAVAILABLE");
        }
        return valid("SCHEMA", "PAYLOAD_FREE");
    }

    private static VerificationResult verifyFixedMatrix(JsonNode matrix) {
        if (!"S0-AC-01.browser.v1".equals(matrix.path("matrixId").asText())
                || matrix.path("expectedCellCount").asInt(-1) != 60
                || !exactTextArray(matrix.path("goldenPathIds"), GOLDEN_PATHS)
                || !exactTextArray(matrix.path("locales"), LOCALES)
                || !exactViewportArray(matrix.path("viewports"), VIEWPORTS)) {
            return semanticFailure(
                    "FIXED_MATRIX",
                    "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_FIXED_MATRIX_INVALID");
        }
        return valid("FIXED_MATRIX");
    }

    private static VerificationResult verifyCandidateAndExecution(JsonNode result) {
        JsonNode candidate = result.path("candidate");
        JsonNode baseline = result.path("baselineRef");
        JsonNode environment = result.path("environment");
        if (!candidate.path("artifactFingerprint").isTextual()
                || !baseline.path("fingerprint").isTextual()
                || !environment.path("environmentFingerprint").isTextual()) {
            return semanticFailure(
                    "CANDIDATE_BINDING",
                    "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_CANDIDATE_BINDING_INVALID");
        }
        try {
            OffsetDateTime started = OffsetDateTime.parse(
                    result.path("executionWindow").path("startedAt").asText());
            OffsetDateTime completed = OffsetDateTime.parse(
                    result.path("executionWindow").path("completedAt").asText());
            if (completed.isBefore(started)) {
                return semanticFailure(
                        "EXECUTION_WINDOW",
                        "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_EXECUTION_WINDOW_INVALID");
            }
        } catch (DateTimeParseException invalid) {
            return semanticFailure(
                    "EXECUTION_WINDOW",
                    "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_EXECUTION_WINDOW_INVALID");
        }
        return valid("CANDIDATE_BINDING", "EXECUTION_WINDOW");
    }

    private static VerificationResult verifyCells(JsonNode cells) {
        Set<String> expected = new LinkedHashSet<>(expectedCellIds());
        if (!cells.isArray() || cells.size() != expected.size()) {
            return semanticFailure(
                    "CELL_ORDER_UNIQUENESS",
                    "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_CELL_COUNT_INVALID");
        }
        Set<String> seen = new HashSet<>();
        String previous = null;
        for (JsonNode cell : cells) {
            String cellId = cell.path("cellId").asText();
            String goldenPathId = cell.path("goldenPathId").asText();
            String locale = cell.path("locale").asText();
            Viewport viewport = viewport(cell.path("viewport"));
            String canonicalId = canonicalCellId(goldenPathId, locale, viewport);
            if (!expected.contains(cellId) || !cellId.equals(canonicalId)) {
                return semanticFailure(
                        "CELL_ORDER_UNIQUENESS",
                        "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_CELL_ID_INVALID");
            }
            if (!seen.add(cellId)) {
                return semanticFailure(
                        "CELL_ORDER_UNIQUENESS",
                        "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_CELL_DUPLICATE");
            }
            if (previous != null && compareCellIds(previous, cellId) >= 0) {
                return semanticFailure(
                        "CELL_ORDER_UNIQUENESS",
                        "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_CELL_ORDER_INVALID");
            }
            previous = cellId;
            VerificationResult cellResult = verifyCell(cell);
            if (!cellResult.verified()) {
                return cellResult;
            }
        }
        if (!seen.equals(expected)) {
            return semanticFailure(
                    "CELL_ORDER_UNIQUENESS",
                    "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_CELL_SET_INVALID");
        }
        return valid("CELL_ORDER_UNIQUENESS", "CELL_INVARIANTS");
    }

    private static VerificationResult verifyCell(JsonNode cell) {
        String status = cell.path("status").asText();
        JsonNode axe = cell.path("axe");
        JsonNode keyboard = cell.path("keyboardPath");
        boolean observedFailure = "FAIL".equals(status)
                || !sameViewport(cell.path("viewport"), cell.path("actualInnerViewport"))
                || cell.path("pageHorizontalOverflow").asBoolean()
                || axe.path("serious").asInt() > 0
                || axe.path("critical").asInt() > 0
                || cell.path("technicalIdCount").asInt() > 0
                || cell.path("rawJsonCount").asInt() > 0
                || !keyboard.path("completed").asBoolean()
                || keyboard.path("stepCount").asInt() < 1
                || keyboard.path("focusLossCount").asInt() > 0
                || cell.path("p0Count").asInt() > 0
                || cell.path("p1Count").asInt() > 0;
        VerificationResult evidenceOrder = verifyEvidenceOrder(cell.path("evidenceRefs"));
        if (!evidenceOrder.verified()) {
            return evidenceOrder;
        }
        if ("PASS".equals(status)) {
            if (observedFailure || cell.path("evidenceRefs").isEmpty()) {
                return semanticFailure(
                        "CELL_INVARIANTS",
                        "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_FALSE_PASS");
            }
            return valid("CELL_INVARIANTS");
        }
        if ("FAIL".equals(status)) {
            if (cell.path("actualInnerViewport").isNull()
                    || cell.path("evidenceRefs").isEmpty()) {
                return semanticFailure(
                        "CELL_INVARIANTS",
                        "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_FAILED_CELL_EVIDENCE_INVALID");
            }
            return valid("CELL_INVARIANTS");
        }
        if ("NOT_RUN".equals(status) || "SKIPPED".equals(status)) {
            if (!cell.path("actualInnerViewport").isNull()
                    || cell.path("pageHorizontalOverflow").asBoolean()
                    || axe.path("serious").asInt() != 0
                    || axe.path("critical").asInt() != 0
                    || cell.path("technicalIdCount").asInt() != 0
                    || cell.path("rawJsonCount").asInt() != 0
                    || keyboard.path("completed").asBoolean()
                    || keyboard.path("stepCount").asInt() != 0
                    || keyboard.path("focusLossCount").asInt() != 0
                    || cell.path("p0Count").asInt() != 0
                    || cell.path("p1Count").asInt() != 0
                    || !cell.path("evidenceRefs").isEmpty()) {
                return semanticFailure(
                        "CELL_INVARIANTS",
                        "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_NOT_RUN_CELL_NOT_EXPLICIT");
            }
            return valid("CELL_INVARIANTS");
        }
        if (!"PASS".equals(status) && !"NOT_RUN".equals(status)
                && !"SKIPPED".equals(status) && !"FAIL".equals(status)) {
            return semanticFailure(
                    "CELL_INVARIANTS",
                    "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_CELL_STATUS_INVALID");
        }
        return valid("CELL_INVARIANTS");
    }

    private static VerificationResult verifySummary(JsonNode result) {
        JsonNode summary = result.path("summary");
        int actual = result.path("cells").size();
        int pass = 0;
        int incomplete = 0;
        int failed = 0;
        int skipped = 0;
        int p0 = 0;
        int p1 = 0;
        int evidence = 0;
        for (JsonNode cell : result.path("cells")) {
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
        if (summary.path("expectedCellCount").asInt(-1) != 60
                || summary.path("actualCellCount").asInt(-1) != actual
                || summary.path("passCellCount").asInt(-1) != pass
                || summary.path("incompleteCellCount").asInt(-1) != incomplete
                || summary.path("failedCellCount").asInt(-1) != failed
                || summary.path("skippedCount").asInt(-1) != skipped
                || summary.path("p0Count").asInt(-1) != p0
                || summary.path("p1Count").asInt(-1) != p1
                || summary.path("evidenceRefCount").asInt(-1) != evidence) {
            return semanticFailure(
                    "SUMMARY",
                    "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_SUMMARY_MISMATCH");
        }
        return valid("SUMMARY");
    }

    private static VerificationResult verifyEvidenceClosure(JsonNode result) {
        String expected;
        try {
            expected = EvidenceVerificationSupport.sha256Bounded(
                    canonicalMaterial(result), MAXIMUM_RESULT_BYTES);
        } catch (IllegalArgumentException tooLarge) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_SIZE_LIMIT");
        }
        if (!expected.equals(result.path("evidenceClosureFingerprint").asText())) {
            return semanticFailure(
                    "EVIDENCE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_EVIDENCE_FINGERPRINT_MISMATCH");
        }
        return valid("EVIDENCE_CLOSURE");
    }

    private static VerificationResult verifyResultStatus(JsonNode result) {
        int failed = result.path("summary").path("failedCellCount").asInt();
        int incomplete = result.path("summary").path("incompleteCellCount").asInt();
        String expected;
        if (!"CLEAN".equals(result.path("candidate").path("sourceTreeStatus").asText())
                || failed > 0
                || result.path("summary").path("p0Count").asInt() > 0
                || result.path("summary").path("p1Count").asInt() > 0) {
            expected = "FAILED";
        } else if (result.path("cells").size() != 60 || incomplete > 0) {
            expected = "INCOMPLETE";
        } else {
            expected = "COMPLETE";
        }
        if (!expected.equals(result.path("resultStatus").asText())) {
            return semanticFailure(
                    "RESULT_STATUS",
                    "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_STATUS_MISMATCH");
        }
        return valid("RESULT_STATUS");
    }

    private static VerificationResult verifyEvidenceOrder(JsonNode refs) {
        String previous = null;
        Set<String> seen = new HashSet<>();
        for (JsonNode ref : refs) {
            String id = ref.path("evidenceId").asText();
            if (!seen.add(id)) {
                return semanticFailure(
                        "CELL_INVARIANTS",
                        "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_EVIDENCE_DUPLICATE");
            }
            if (previous != null && previous.compareTo(id) >= 0) {
                return semanticFailure(
                        "CELL_INVARIANTS",
                        "RG.CAPABILITY_STUDIO.BROWSER_MATRIX_RESULT_EVIDENCE_ORDER_INVALID");
            }
            previous = id;
        }
        return valid("CELL_INVARIANTS");
    }

    private static ObjectNode canonicalMaterial(JsonNode result) {
        ObjectNode material = result.deepCopy();
        material.remove("evidenceClosureFingerprint");
        EvidenceVerificationSupport.sha256Bounded(material, MAXIMUM_RESULT_BYTES);
        return material;
    }

    private static boolean canonicalFingerprintAvailable(JsonNode result) {
        try {
            canonicalMaterial(result);
            return true;
        } catch (IllegalArgumentException tooLarge) {
            return false;
        }
    }

    private static boolean containsForbiddenField(JsonNode value) {
        if (value == null) {
            return false;
        }
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (FORBIDDEN_FIELDS.contains(entry.getKey())) {
                    return true;
                }
                if (containsForbiddenField(entry.getValue())) {
                    return true;
                }
            }
        } else if (value.isArray()) {
            for (JsonNode child : value) {
                if (containsForbiddenField(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> expectedCellIds() {
        return GOLDEN_PATHS.stream()
                .flatMap(gp -> LOCALES.stream()
                        .flatMap(locale -> VIEWPORTS.stream()
                                .map(viewport -> canonicalCellId(gp, locale, viewport))))
                .toList();
    }

    private static String canonicalCellId(String goldenPathId, String locale, Viewport viewport) {
        return goldenPathId + ":" + locale + ":" + viewport.width + "x" + viewport.height;
    }

    private static int compareCellIds(String left, String right) {
        return Integer.compare(expectedCellIds().indexOf(left), expectedCellIds().indexOf(right));
    }

    private static Viewport viewport(JsonNode value) {
        return new Viewport(value.path("width").asInt(-1), value.path("height").asInt(-1));
    }

    private static boolean sameViewport(JsonNode expected, JsonNode actual) {
        return actual != null && !actual.isNull() && viewport(expected).equals(viewport(actual));
    }

    private static boolean exactTextArray(JsonNode values, List<String> expected) {
        if (!values.isArray() || values.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(values.get(i).asText())) {
                return false;
            }
        }
        return true;
    }

    private static boolean exactViewportArray(JsonNode values, List<Viewport> expected) {
        if (!values.isArray() || values.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(viewport(values.get(i)))) {
                return false;
            }
        }
        return true;
    }

    private static VerificationResult valid(String... checks) {
        return new VerificationResult(FailureKind.NONE, Set.of(checks), null, null);
    }

    private static VerificationResult valid(JsonNode result, String... checks) {
        ArtifactStatus status;
        try {
            status = ArtifactStatus.valueOf(result.path("resultStatus").asText());
        } catch (IllegalArgumentException invalidStatus) {
            status = null;
        }
        return new VerificationResult(FailureKind.NONE, Set.of(checks), null, status);
    }

    private static VerificationResult schemaFailure(String errorCode) {
        return new VerificationResult(FailureKind.SCHEMA, Set.of("SCHEMA"), errorCode, null);
    }

    private static VerificationResult semanticFailure(String check, String errorCode) {
        return new VerificationResult(FailureKind.SEMANTIC, Set.of(check), errorCode, null);
    }
}
