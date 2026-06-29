package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Schema-aware validator for visual graph drafts.
 */
@Service
public class GraphDraftValidator {

    private final VisualOperatorCatalog catalog;

    /**
     * @param catalog visual operator catalog
     */
    public GraphDraftValidator(VisualOperatorCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Validates a graph draft before code generation.
     *
     * @param draft graph draft
     * @return validation result
     */
    public VisualValidationResult validate(GraphDraft draft) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (draft == null) {
            diagnostics.add(VisualDiagnostic.error("visual.draft.missing", "Graph draft is required.", "/"));
            return new VisualValidationResult(false, diagnostics);
        }
        if (draft.nodes().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.graph.empty", "Graph must contain at least one node.", "/nodes"));
        }

        Set<String> nodeIds = new HashSet<>();
        Map<String, GraphDraft.DraftNode> nodesById = new LinkedHashMap<>();
        Map<String, OperatorDefinition> operatorsByNodeId = new LinkedHashMap<>();
        for (int i = 0; i < draft.nodes().size(); i++) {
            GraphDraft.DraftNode node = draft.nodes().get(i);
            String nodePath = "/nodes/" + i;
            if (!nodeIds.add(node.id())) {
                diagnostics.add(VisualDiagnostic.error("visual.node.duplicateId",
                        "Duplicate node id: " + node.id(), nodePath + "/id"));
            }
            nodesById.put(node.id(), node);
            Optional<OperatorDefinition> operator = catalog.find(node.operatorRef());
            if (operator.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.unknown",
                        "Unknown operatorRef: " + node.operatorRef(), nodePath + "/operatorRef"));
                continue;
            }
            operatorsByNodeId.put(node.id(), operator.get());
            validateRequiredInputs(node, operator.get(), nodePath, diagnostics);
            validateUnknownInputs(node, operator.get(), nodePath, diagnostics);
        }

        validateNodePathBindings(draft, nodesById, operatorsByNodeId, diagnostics);
        validateEdges(draft, diagnostics);
        if (!draft.output().nodeId().isBlank() && !nodeIds.contains(draft.output().nodeId())) {
            diagnostics.add(VisualDiagnostic.error("visual.output.unknownNode",
                    "Output node does not exist: " + draft.output().nodeId(), "/output/nodeId"));
        }
        return new VisualValidationResult(diagnostics.stream().noneMatch(VisualDiagnostic::error), diagnostics);
    }

    private static void validateRequiredInputs(GraphDraft.DraftNode node,
                                               OperatorDefinition operator,
                                               String nodePath,
                                               List<VisualDiagnostic> diagnostics) {
        SchemaEnvelope schema = firstInputSchema(operator);
        for (String required : schema.required()) {
            if (!node.inputs().containsKey(required)) {
                diagnostics.add(VisualDiagnostic.error("visual.input.required",
                        "Node '%s' requires input '%s'.".formatted(node.id(), required),
                        nodePath + "/inputs/" + required));
            }
        }
    }

    private static void validateUnknownInputs(GraphDraft.DraftNode node,
                                              OperatorDefinition operator,
                                              String nodePath,
                                              List<VisualDiagnostic> diagnostics) {
        SchemaEnvelope schema = firstInputSchema(operator);
        Map<String, Object> properties = schema.properties();
        if (properties.isEmpty()) {
            return;
        }
        for (String input : node.inputs().keySet()) {
            if (!properties.containsKey(input)) {
                diagnostics.add(VisualDiagnostic.warning("visual.input.unknown",
                        "Input '%s' is not declared by operator '%s'.".formatted(input, operator.operatorRef()),
                        nodePath + "/inputs/" + input));
            }
        }
    }

    private static SchemaEnvelope firstInputSchema(OperatorDefinition operator) {
        return operator.ports().inputs().isEmpty()
                ? SchemaEnvelope.opaque()
                : operator.ports().inputs().get(0).schema();
    }

    private static SchemaEnvelope firstOutputSchema(OperatorDefinition operator) {
        return operator.ports().outputs().isEmpty()
                ? SchemaEnvelope.opaque()
                : operator.ports().outputs().get(0).schema();
    }

    private static void validateNodePathBindings(GraphDraft draft,
                                                 Map<String, GraphDraft.DraftNode> nodesById,
                                                 Map<String, OperatorDefinition> operatorsByNodeId,
                                                 List<VisualDiagnostic> diagnostics) {
        for (int i = 0; i < draft.nodes().size(); i++) {
            GraphDraft.DraftNode node = draft.nodes().get(i);
            OperatorDefinition targetOperator = operatorsByNodeId.get(node.id());
            if (targetOperator == null) {
                continue;
            }
            SchemaEnvelope targetSchema = firstInputSchema(targetOperator);
            for (Map.Entry<String, GraphDraft.Binding> input : node.inputs().entrySet()) {
                validateBinding(input.getValue(), input.getKey(), targetSchema, nodesById, operatorsByNodeId,
                        "/nodes/" + i + "/inputs/" + input.getKey(), diagnostics);
            }
        }
    }

    private static void validateBinding(GraphDraft.Binding binding,
                                        String inputName,
                                        SchemaEnvelope targetSchema,
                                        Map<String, GraphDraft.DraftNode> nodesById,
                                        Map<String, OperatorDefinition> operatorsByNodeId,
                                        String targetPath,
                                        List<VisualDiagnostic> diagnostics) {
        if ("objectTemplate".equals(binding.kind())) {
            binding.fields().forEach((key, nested) -> validateBinding(nested, key, targetSchema, nodesById,
                    operatorsByNodeId, targetPath + "/" + key, diagnostics));
            return;
        }
        if (!"nodePath".equals(binding.kind())) {
            return;
        }

        GraphDraft.DraftNode sourceNode = nodesById.get(binding.nodeId());
        OperatorDefinition sourceOperator = operatorsByNodeId.get(binding.nodeId());
        if (sourceNode == null || sourceOperator == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownSource",
                    "Binding source node does not exist: " + binding.nodeId(), targetPath));
            return;
        }

        Map<String, Object> sourceProperty = propertyAtPath(firstOutputSchema(sourceOperator), binding.path());
        if (sourceProperty == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownOutputPath",
                    "Source node '%s' output path does not exist: %s".formatted(binding.nodeId(), binding.path()),
                    targetPath));
            return;
        }

        Map<String, Object> targetProperty = objectProperty(targetSchema.properties().get(inputName));
        String sourceType = schemaType(sourceProperty);
        String targetType = schemaType(targetProperty);
        if (!sourceType.isBlank() && !targetType.isBlank() && !typesCompatible(sourceType, targetType)) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.typeMismatch",
                    "Cannot bind %s output '%s' to %s input '%s'."
                            .formatted(sourceType, binding.path(), targetType, inputName),
                    targetPath));
        }
    }

    private static Map<String, Object> propertyAtPath(SchemaEnvelope schema, String path) {
        if (path == null || path.isBlank()) {
            return Map.of("type", schema.schema().getOrDefault("type", "object"));
        }
        Map<String, Object> properties = schema.properties();
        Map<String, Object> current = null;
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            current = objectProperty(properties.get(segment));
            if (current == null) {
                return null;
            }
            Object nested = current.get("properties");
            if (nested instanceof Map<?, ?> rawNested) {
                Map<String, Object> nextProperties = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : rawNested.entrySet()) {
                    nextProperties.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                properties = nextProperties;
            } else {
                properties = Map.of();
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectProperty(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static String schemaType(Map<String, Object> property) {
        if (property == null) {
            return "";
        }
        Object type = property.get("type");
        return type == null ? "" : String.valueOf(type);
    }

    private static boolean typesCompatible(String sourceType, String targetType) {
        if (sourceType.equals(targetType)) {
            return true;
        }
        return numeric(sourceType) && numeric(targetType);
    }

    private static boolean numeric(String type) {
        return "number".equals(type) || "integer".equals(type);
    }

    private static void validateEdges(GraphDraft draft, List<VisualDiagnostic> diagnostics) {
        Set<String> nodeIds = new HashSet<>();
        draft.nodes().forEach(node -> nodeIds.add(node.id()));
        for (int i = 0; i < draft.edges().size(); i++) {
            GraphDraft.DraftEdge edge = draft.edges().get(i);
            String edgePath = "/edges/" + i;
            if (!nodeIds.contains(edge.source().nodeId())) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.unknownSource",
                        "Edge source node does not exist: " + edge.source().nodeId(),
                        edgePath + "/source/nodeId"));
            }
            if (!nodeIds.contains(edge.target().nodeId())) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.unknownTarget",
                        "Edge target node does not exist: " + edge.target().nodeId(),
                        edgePath + "/target/nodeId"));
            }
        }
    }
}
