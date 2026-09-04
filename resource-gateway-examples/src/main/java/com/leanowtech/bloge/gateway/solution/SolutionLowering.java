package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.importer.DslVisualProjection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lowers a Solution contract into a graph that consumes feature values but never collects them.
 *
 * <p>The generated BLOGE graph has exactly two semantic calls: a bounded {@code scenarioCall}
 * followed by an effect-aware {@code instructionCall}. It is immediately parsed and projected by
 * the production {@link DslImportService}; a draft carrying any blocking projection diagnostic is
 * never reported as precompiled.</p>
 */
public final class SolutionLowering {
    private final SolutionEntityRegistry registry;
    private final DslImportService importer;

    /** Creates the lowering boundary over canonical Feature contracts and the production importer. */
    public SolutionLowering(SolutionEntityRegistry registry, DslImportService importer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.importer = Objects.requireNonNull(importer, "importer");
    }

    /**
     * Generates and precompiles one pure Solution graph.
     *
     * @param scopeKey exact authenticated entity scope
     * @param solution canonical Solution contract
     * @return generated source, projected draft and compiler diagnostics
     */
    public LoweredSolution lower(String scopeKey, SolutionContract solution) {
        Objects.requireNonNull(solution, "solution");
        String source = generate(scopeKey, solution);
        DslVisualProjection projection = importer.preview(new DslImportPreviewRequest(
                solution.solutionRef() + ".bloge", source, List.of(), List.of(),
                "solution-lowering", Map.of()));
        boolean precompiled = projection.diagnostics().stream().noneMatch(VisualDiagnostic::error)
                && projection.draft().nodes().size() == 2;
        if (!precompiled) throw new SolutionContractException(
                "SOLUTION_LOWERING_FAILED", "Solution lowering did not pass precompilation.");
        return new LoweredSolution(source, projection.draft(), projection.diagnostics(), true);
    }

    private String generate(String scopeKey, SolutionContract solution) {
        ArrayList<String> declarations = new ArrayList<>();
        ArrayList<String> values = new ArrayList<>();
        solution.inputs().forEach((name, featureRef) -> {
            FeatureContract feature;
            try {
                feature = registry.requireFeature(scopeKey, featureRef);
            } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
                throw new SolutionContractException(
                        "REFERENCE_UNRESOLVED", "A Solution Feature is unresolved.");
            }
            declarations.add(identifier(name) + ": " + blogeType(feature.output().path("type")));
            values.add(identifier(name) + ": ctx." + identifier(name));
        });
        return """
                graph %s {
                  input { %s }
                  node decide : "%s" {
                    input {
                      scenarioRef = "%s"
                      values = { %s }
                    }
                  }
                  node dispatch : "%s" {
                    input {
                      instructionRef = decide.output.ref
                      values = decide.output.bind
                    }
                  }
                }
                """.formatted(
                identifier(solution.solutionRef()), String.join("  ", declarations),
                SolutionOperatorDefinitions.SCENARIO_CALL, escape(solution.rootScenarioRef()),
                String.join(", ", values), SolutionOperatorDefinitions.INSTRUCTION_CALL);
    }

    private static String blogeType(JsonNode type) {
        if (type.isObject() && type.has("enum")) return "String";
        if (!type.isTextual()) return "Object";
        return switch (type.asText().toLowerCase(java.util.Locale.ROOT)) {
            case "string" -> "String";
            case "boolean" -> "Boolean";
            case "integer" -> "Int";
            case "number" -> "Number";
            default -> "Object";
        };
    }

    private static String identifier(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "_");
        if (normalized.isBlank()) return "solution";
        return Character.isDigit(normalized.charAt(0)) ? "_" + normalized : normalized;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Immutable result of generation through the production projection boundary. */
    public record LoweredSolution(
            String dsl,
            GraphDraft draft,
            List<VisualDiagnostic> diagnostics,
            boolean precompiled
    ) {
        /** Freezes diagnostics and requires generated artifacts. */
        public LoweredSolution {
            dsl = dsl == null ? "" : dsl;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            if (draft == null) throw new IllegalArgumentException("draft is required");
        }
    }
}
