package com.leanowtech.bloge.gateway.visualadapter.authoring.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.importer.DslVisualProjection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Projects a strict, composable-node subset of BLOGE DSL into the canonical reusable-Flow command.
 *
 * <p>The official DSL importer remains the only parser. This adapter accepts ordinary node input
 * bindings that can be represented losslessly as {@code FLOW_INPUT}, {@code NODE_OUTPUT}, or
 * {@code CONSTANT}; transforms, branches, retry/fallback attributes, expressions, and unpinned
 * operators fail closed rather than being silently dropped. When pinned external resources are
 * intentionally absent from the generic operator catalog, exact {@code dependencyPins} replace
 * only that catalog evidence; unsupported DSL syntax and missing functions still fail closed.
 * The existing reusable-Flow compiler subsequently validates exact dependency fingerprints,
 * schemas, mappings, cycles, and output.</p>
 */
public final class ReusableFlowDslProjector {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DslImportService importer;

    public ReusableFlowDslProjector(DslImportService importer) {
        this.importer = Objects.requireNonNull(importer, "importer");
    }

    /**
     * Converts human-authored BLOGE DSL into the sole canonical persistence command.
     *
     * @throws ReusableFlowFailure with {@code VALIDATION} when projection would be lossy
     */
    public ReusableFlowCommand project(ReusableFlowDslCommand command) {
        if (command == null || !ReusableFlowDslCommand.SCHEMA_VERSION.equals(command.schemaVersion())
                || command.source().dsl().isBlank()) {
            throw invalid();
        }
        DslVisualProjection projection = importer.preview(new DslImportPreviewRequest(
                command.source().sourceId(), command.source().dsl(), List.of(), List.of(),
                "reusable-flow", Map.of()));
        if (projection.diagnostics().stream().anyMatch(VisualDiagnostic::error)
                || projection.coverage().unsupportedSyntaxCount() > 0) {
            throw invalid();
        }

        GraphDraft draft = projection.draft();
        if (draft.nodes().isEmpty() || draft.edges().stream().anyMatch(edge -> !"data".equals(edge.kind()))) {
            throw invalid();
        }
        Set<String> usedPins = new LinkedHashSet<>();
        List<ReusableFlowCommand.Node> nodes = new ArrayList<>();
        Map<String, ReusableFlowCommand.Position> positions = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            ReusableFlowCommand.ComposableRef pin = command.dependencyPins().get(node.operatorRef());
            if (pin == null || !supportedConfig(node.config())) {
                throw invalid();
            }
            usedPins.add(node.operatorRef());
            List<ReusableFlowCommand.Input> inputs = new ArrayList<>();
            node.inputs().forEach((inputKey, binding) -> inputs.add(new ReusableFlowCommand.Input(
                    path(targetPath(inputKey, binding)), source(binding))));
            nodes.add(new ReusableFlowCommand.Node(node.id(), node.label(), pin, inputs));
            positions.put(node.id(), new ReusableFlowCommand.Position(node.position().x(), node.position().y()));
        }
        if (!usedPins.equals(command.dependencyPins().keySet())) {
            throw invalid();
        }
        boolean pinnedExternalCatalogClosure = "PARTIAL".equals(projection.roundTrip().status())
                && projection.coverage().missingFunctionCount() == 0
                && projection.coverage().missingOperatorCount() == usedPins.size()
                && projection.diagnostics().stream().allMatch(diagnostic ->
                "visual.dslImport.operatorMissing".equals(diagnostic.code())
                        && usedPins.contains(diagnostic.metadata().get("operatorRef")))
                && onlyPinnedOperatorRoundTripDiagnostics(projection, draft);
        if (!projection.roundTrip().supported() && !pinnedExternalCatalogClosure) {
            throw invalid();
        }

        GraphDraft.OutputSelection selected = draft.output();
        ReusableFlowCommand.Flow flow = new ReusableFlowCommand.Flow(
                command.displayName(), command.kind(), command.description(),
                new ReusableFlowCommand.Contract(draft.inputSchema(), draft.outputSchema()),
                new ReusableFlowCommand.Graph(nodes,
                        new ReusableFlowCommand.Output(selected.nodeId(), path(selected.path()))),
                new ReusableFlowCommand.Layout(positions));
        return new ReusableFlowCommand(ReusableFlowCommand.SCHEMA_VERSION, flow);
    }

    private static boolean onlyPinnedOperatorRoundTripDiagnostics(
            DslVisualProjection projection, GraphDraft draft) {
        Set<String> expectedTargets = draft.nodes().stream()
                .map(node -> "/nodes/" + node.id())
                .collect(Collectors.toUnmodifiableSet());
        List<VisualDiagnostic> diagnostics = projection.roundTrip().diagnostics();
        return !diagnostics.isEmpty()
                && diagnostics.stream().allMatch(diagnostic ->
                "visual.operator.unknown".equals(diagnostic.code())
                        && expectedTargets.contains(diagnostic.target()))
                && diagnostics.stream().map(VisualDiagnostic::target)
                .collect(Collectors.toUnmodifiableSet())
                .equals(expectedTargets);
    }

    private static boolean supportedConfig(Map<String, Object> config) {
        return config.keySet().stream().allMatch("description"::equals);
    }

    private static String targetPath(String inputKey, GraphDraft.Binding binding) {
        if (!binding.targetPath().isBlank()) return binding.targetPath();
        if (!binding.targetPort().isBlank()) return binding.targetPort();
        return inputKey;
    }

    private static ReusableFlowCommand.MappingSource source(GraphDraft.Binding binding) {
        return switch (binding.kind()) {
            case "contextPath" -> new ReusableFlowCommand.MappingSource.FlowInput(path(binding.path()));
            case "nodePath" -> new ReusableFlowCommand.MappingSource.NodeOutput(
                    binding.nodeId(), path(binding.path()));
            case "constant" -> new ReusableFlowCommand.MappingSource.Constant(json(binding.value()));
            default -> throw invalid();
        };
    }

    private static JsonNode json(Object value) {
        return value instanceof JsonNode node ? node.deepCopy() : JSON.valueToTree(value);
    }

    private static String path(String path) {
        if (path == null || path.isBlank()) return "$";
        if (path.startsWith("$")) return path;
        return "$." + path;
    }

    private static ReusableFlowFailure invalid() {
        return new ReusableFlowFailure(ReusableFlowFailure.Code.VALIDATION);
    }
}
