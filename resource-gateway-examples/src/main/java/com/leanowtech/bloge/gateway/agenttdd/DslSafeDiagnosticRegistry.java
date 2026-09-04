package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.dsl.compiler.CompilationDiagnostic;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.lint.LintDiagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts lower-layer diagnostics into a small, payload-free authoring vocabulary.
 *
 * <p>This class deliberately never reads a lower diagnostic's message or metadata. Safe prose is
 * owned here, while candidates are drawn only from the already-authorized authoring context.</p>
 */
final class DslSafeDiagnosticRegistry {
    private static final int MAX_FIX_HINTS = 3;
    private static final int MAX_FINGERPRINT_BYTES = 32 * 1024;

    private final ObjectMapper mapper;

    DslSafeDiagnosticRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Maps one visual importer or validator diagnostic without consuming its prose or metadata. */
    MappedDiagnostic visual(VisualDiagnostic source, DslAuthoringContext context) {
        return visual(source, context, "");
    }

    /** Maps a visual diagnostic and ranks only safe visible suggestions near an internal rejected ref. */
    MappedDiagnostic visual(VisualDiagnostic source, DslAuthoringContext context, String rejectedOperatorRef) {
        String lowerCode = source == null ? "" : source.code();
        String phase;
        String code;
        String summary;
        List<String> expected = List.of();
        List<String> references = List.of();
        List<DslAuthoringDiagnostic.FixHint> fixes = List.of();
        String resolution = "AGENT_CAN_REVISE";

        if ("visual.dslImport.parseFailed".equals(lowerCode)) {
            phase = "PARSE";
            code = "DSL_PARSE_EXPECTED_CONSTRUCT";
            summary = "The source is not a complete BLOGE graph construct.";
            expected = List.of("GRAPH_MEMBER_OR_RIGHT_BRACE");
            references = List.of("topic:graph");
        } else if ("visual.dslImport.rootUnsupported".equals(lowerCode)) {
            phase = "PARSE";
            code = "DSL_ROOT_UNSUPPORTED";
            summary = "Resource Gateway authoring accepts a graph root only.";
            expected = List.of("GRAPH");
            references = List.of("topic:graph");
        } else if ("visual.dslImport.operatorMissing".equals(lowerCode)
                || "visual.operator.unknown".equals(lowerCode)) {
            phase = "RESOLVE";
            code = "DSL_OPERATOR_NOT_FOUND";
            summary = "An operator reference is not visible in this authoring context.";
            expected = List.of("VISIBLE_OPERATOR_REF");
            references = List.of("topic:node");
            fixes = rankedOperatorCandidates(rejectedOperatorRef, context.operators().keySet()).stream()
                    .map(value -> new DslAuthoringDiagnostic.FixHint(
                            "REPLACE_OPERATOR_REF", value, "AUTHORIZED_CONTRACT_COMPATIBLE"))
                    .toList();
        } else if ("visual.dslImport.functionMissing".equals(lowerCode)) {
            phase = "RESOLVE";
            code = "DSL_FUNCTION_NOT_FOUND";
            summary = "A function is not visible in this authoring context.";
            expected = List.of("VISIBLE_FUNCTION_NAME");
            references = List.of("topic:transform");
            fixes = context.functions().keySet().stream().sorted().limit(MAX_FIX_HINTS)
                    .map(value -> new DslAuthoringDiagnostic.FixHint(
                            "USE_VISIBLE_FUNCTION", value, "VISIBLE_IN_AUTHORING_CONTEXT"))
                    .toList();
        } else if (lowerCode.contains("typeMismatch") || lowerCode.contains("constraintMismatch")
                || lowerCode.contains("enumMismatch")) {
            phase = "TYPE_CHECK";
            code = "DSL_TYPE_MISMATCH";
            summary = "A binding or value does not satisfy the selected contract type.";
            references = List.of("topic:bindings");
        } else if (lowerCode.contains("unknownTargetPort") || lowerCode.equals("visual.input.unknown")) {
            phase = "TYPE_CHECK";
            code = "DSL_INPUT_PORT_UNKNOWN";
            summary = "An input name is not declared by the selected operator contract.";
            expected = List.of("DECLARED_INPUT_PORT");
            references = List.of("topic:bindings");
        } else if (lowerCode.contains("unknownSourcePort") || lowerCode.contains("unknownOutput")
                || lowerCode.startsWith("visual.output.unknown")) {
            phase = "TYPE_CHECK";
            code = "DSL_OUTPUT_PORT_UNKNOWN";
            summary = "An output name or path is not declared by the selected contract.";
            expected = List.of("DECLARED_OUTPUT_PORT");
            references = List.of("topic:bindings");
        } else if (lowerCode.equals("visual.input.required") || lowerCode.equals("visual.config.required")) {
            phase = "TYPE_CHECK";
            code = "DSL_REQUIRED_INPUT_MISSING";
            summary = "A required contract input or configuration field is missing.";
            references = List.of("topic:bindings");
        } else if (lowerCode.contains("policyDenied") || lowerCode.contains("governance")
                || lowerCode.contains("runtime.")) {
            phase = "SEMANTIC_COMPILE";
            code = "DSL_EFFECT_NOT_ALLOWED";
            summary = "The selected operator effect is not permitted by this authoring context.";
            references = List.of("topic:effects");
            resolution = "HUMAN_OR_PLATFORM_REQUIRED";
        } else if (lowerCode.startsWith("visual.dslImport.")
                || lowerCode.startsWith("visual.transform.")
                || lowerCode.startsWith("visual.decisionTable.")) {
            phase = "PROJECT";
            code = "DSL_PROJECTION_UNSUPPORTED";
            summary = "This construct cannot yet be represented by the governed graph projection.";
            resolution = "PLATFORM_MAINTAINER";
        } else {
            phase = "SEMANTIC_COMPILE";
            code = "DSL_DIAGNOSTIC_UNCLASSIFIED";
            summary = "The compiler reported an unmapped structural diagnostic.";
            resolution = "PLATFORM_MAINTAINER";
        }
        String level = normalizeLevel(source == null ? null : source.level());
        boolean unresolved = code.equals("DSL_OPERATOR_NOT_FOUND") || code.equals("DSL_FUNCTION_NOT_FOUND");
        return mapped(level, phase, code, source == null ? "" : safeTarget(source.target()),
                span(source == null ? 0 : source.line(), source == null ? 0 : source.column()),
                summary, expected, references, fixes, resolution, false,
                "ERROR".equals(level) || unresolved || code.equals("DSL_PROJECTION_UNSUPPORTED"));
    }

    /** Creates the stable parse diagnostic for a keyword used where an identifier is required. */
    MappedDiagnostic identifierReserved(int line, int column) {
        return mapped("ERROR", "PARSE", "DSL_IDENTIFIER_RESERVED", "", span(line, column),
                "A declaration identifier uses a reserved BLOGE keyword.",
                List.of("IDENTIFIER"), List.of("topic:graph"), List.of(),
                "AGENT_CAN_REVISE", false, true);
    }

    /** Maps explicit structured compiler fields and never invokes message-derived fallback accessors. */
    MappedDiagnostic compiler(CompilationDiagnostic source) {
        String rule = source == null || source.ruleId() == null ? "" : source.ruleId();
        String code = switch (rule) {
            case "unknown-function" -> "DSL_FUNCTION_NOT_FOUND";
            case "unknown-schema-reference" -> "DSL_SCHEMA_NOT_FOUND";
            case "unresolved-operator" -> "DSL_OPERATOR_NOT_FOUND";
            case "invalid-expression-path" -> "DSL_OUTPUT_PORT_UNKNOWN";
            case "graph-output-contract-mismatch" -> "DSL_TYPE_MISMATCH";
            case "sandbox-violation" -> "DSL_EFFECT_NOT_ALLOWED";
            default -> "DSL_DIAGNOSTIC_UNCLASSIFIED";
        };
        String resolution = code.equals("DSL_DIAGNOSTIC_UNCLASSIFIED")
                ? "PLATFORM_MAINTAINER" : "AGENT_CAN_REVISE";
        String summary = code.equals("DSL_DIAGNOSTIC_UNCLASSIFIED")
                ? "The compiler reported an unmapped structural diagnostic."
                : "Semantic compilation rejected a referenced construct or contract.";
        String level = normalizeLevel(source == null ? "ERROR" : source.level().name());
        String target = source == null ? "" : safeTarget(joinTarget(source.nodeId(), source.field()));
        return mapped(level, "SEMANTIC_COMPILE", code, target,
                span(source == null ? 0 : source.line(), source == null ? 0 : source.column()),
                summary, List.of(), List.of("topic:graph"), List.of(), resolution, false,
                "ERROR".equals(level));
    }

    /** Maps a lint rule by stable rule id; the rule-authored message is never read. */
    MappedDiagnostic lint(LintDiagnostic source) {
        String rule = source == null || source.ruleId() == null ? "" : source.ruleId();
        String code;
        String summary;
        boolean blocking;
        if ("decision-table/unique-overlap".equals(rule)) {
            code = "DSL_DECISION_UNIQUE_OVERLAP";
            summary = "A unique decision table contains rules that can match the same input.";
            blocking = true;
        } else if ("decision-table/missing-otherwise".equals(rule)) {
            code = "DSL_DECISION_OTHERWISE_REQUIRED";
            summary = "A unique decision table needs an explicit fallback for uncovered inputs.";
            blocking = true;
        } else {
            code = "DSL_LINT_RULE_FAILED";
            summary = "A server lint rule reported a structural authoring issue.";
            blocking = source != null && source.severity() == LintDiagnostic.Severity.ERROR;
        }
        String level = blocking ? "ERROR" : source == null ? "WARNING" : source.severity().name();
        String phase = code.startsWith("DSL_DECISION_") ? "SEMANTIC_COMPILE" : "LINT";
        return mapped(level, phase, code, "",
                span(source == null ? 0 : source.line(), source == null ? 0 : source.column()),
                summary, List.of(), List.of("topic:decision-table"), List.of(),
                "AGENT_CAN_REVISE", false, blocking);
    }

    /** Creates a server-owned round-trip drift diagnostic. */
    MappedDiagnostic roundTripDrift() {
        return mapped("ERROR", "ROUND_TRIP", "DSL_ROUND_TRIP_DRIFT", "",
                span(0, 0), "The regenerated graph does not preserve the source graph semantics.",
                List.of(), List.of("topic:graph"), List.of(), "PLATFORM_MAINTAINER", false, true);
    }

    /** Records that schema-only operators cannot be regenerated until a runtime lowering is bound. */
    MappedDiagnostic designOnlyRoundTripDeferred() {
        return mapped("INFO", "ROUND_TRIP", "DSL_ROUND_TRIP_DEFERRED_DESIGN_ONLY", "",
                span(0, 0), "Round-trip lowering is deferred until design-only operators are bound.",
                List.of(), List.of("topic:effects"), List.of(), "HUMAN_OR_PLATFORM_REQUIRED", false, false);
    }

    /** Creates a payload-free platform defect without exposing the caught exception. */
    MappedDiagnostic platformDefect(String phase) {
        return mapped("ERROR", phase, "DSL_DIAGNOSTIC_UNCLASSIFIED", "", span(0, 0),
                "The authoring pipeline could not classify a compiler failure safely.",
                List.of(), List.of("topic:graph"), List.of(), "PLATFORM_MAINTAINER", false, true);
    }

    private MappedDiagnostic mapped(String level, String phase, String code, String target,
                                    DslAuthoringDiagnostic.Span span, String summary,
                                    List<String> expected, List<String> references,
                                    List<DslAuthoringDiagnostic.FixHint> fixes, String resolution,
                                    boolean retryable, boolean blocking) {
        Map<String, Object> material = Map.of(
                "level", level, "phase", phase, "code", code, "target", target,
                "span", span, "expectedKinds", expected, "referenceRefs", references,
                "fixHints", fixes, "resolutionClass", resolution, "retryable", retryable);
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, material,
                MAX_FINGERPRINT_BYTES);
        return new MappedDiagnostic(new DslAuthoringDiagnostic(level, phase, code, target, span,
                summary, expected, references, fixes, resolution, retryable, fingerprint), blocking);
    }

    private static DslAuthoringDiagnostic.Span span(int line, int column) {
        boolean known = line > 0 && column > 0;
        return known ? new DslAuthoringDiagnostic.Span(true, line, column, line, column)
                : new DslAuthoringDiagnostic.Span(false, 0, 0, 0, 0);
    }

    private static String normalizeLevel(String level) {
        String normalized = level == null ? "INFO" : level.trim().toUpperCase(Locale.ROOT);
        return List.of("ERROR", "WARNING", "INFO").contains(normalized) ? normalized : "INFO";
    }

    private static String safeTarget(String target) {
        if (target == null || target.isBlank()) return "";
        List<String> safe = new ArrayList<>();
        for (String part : target.split("/")) {
            if (part.isBlank()) continue;
            if (part.matches("\\d+") || !List.of("dsl", "graph", "nodes", "edges", "inputs", "outputs",
                    "config", "operatorRef", "source", "target", "roundTrip", "generatedDsl").contains(part)) {
                safe.add("*");
            } else {
                safe.add(part);
            }
        }
        return safe.isEmpty() ? "" : "/" + String.join("/", safe);
    }

    private static String joinTarget(String node, String field) {
        if (node == null || node.isBlank()) return field == null ? "" : "/field";
        return field == null || field.isBlank() ? "/node" : "/node/field";
    }

    private static List<String> rankedOperatorCandidates(String rejected, java.util.Set<String> visible) {
        String normalized = rejected == null ? "" : rejected.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return List.of();
        int threshold = Math.max(2, normalized.length() / 4);
        return visible.stream().map(candidate -> Map.entry(candidate,
                        editDistance(normalized, candidate.toLowerCase(Locale.ROOT))))
                .filter(candidate -> candidate.getValue() <= threshold)
                .sorted(java.util.Comparator.comparingInt((Map.Entry<String, Integer> value) -> value.getValue())
                        .thenComparing(Map.Entry::getKey))
                .limit(MAX_FIX_HINTS).map(Map.Entry::getKey).toList();
    }

    private static int editDistance(String left, String right) {
        int[] prior = java.util.stream.IntStream.rangeClosed(0, right.length()).toArray();
        for (int row = 1; row <= left.length(); row++) {
            int[] current = new int[right.length() + 1];
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int replace = prior[column - 1]
                        + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(Math.min(prior[column] + 1, current[column - 1] + 1), replace);
            }
            prior = current;
        }
        return prior[right.length()];
    }

    /** Safe diagnostic plus whether it blocks the current phase. */
    record MappedDiagnostic(DslAuthoringDiagnostic diagnostic, boolean blocking) { }
}
