package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphPublicationOperator;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Projects immutable visual graph publications back into reusable visual operators.
 */
@Component
public class VisualGraphPublicationOperatorProjector {

    /**
     * @param publication immutable visual graph publication
     * @return operator definition that invokes the frozen publication DSL
     */
    public OperatorDefinition project(VisualGraphPublication publication) {
        GraphDraft draft = publication.draft();
        SchemaEnvelope inputSchema = draft == null ? SchemaEnvelope.opaque() : draft.inputSchema();
        SchemaEnvelope outputSchema = outputSchema(publication);
        List<VisualDiagnostic> diagnostics = new ArrayList<>(publication.validation().diagnostics());
        diagnostics.addAll(publication.generation().diagnostics());
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                operatorRef(publication.publicationId()),
                Long.toString(publication.draftRevision()),
                new OperatorDefinition.Display(
                        publication.graphName(),
                        "Reusable published visual graph from draft %s@%d."
                                .formatted(publication.draftId(), publication.draftRevision()),
                        List.of("publication", "subgraph", "visual-graph")
                ),
                new OperatorDefinition.Source("visual-publication", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs", inputSchema, true,
                                "Context passed to the published visual graph.")),
                        List.of(new OperatorDefinition.Port("output", outputSchema, true,
                                "Selected output of the published visual graph."))
                ),
                SchemaEnvelope.opaque(),
                publicationCapabilities(publication),
                new OperatorDefinition.Policy(
                        List.of(publication.tenantId()),
                        List.of(publication.namespace()),
                        List.of(publication.environment())
                ),
                new OperatorDefinition.Lowering("native", VisualGraphPublicationOperator.NAME, Map.of(
                        "publicationId", publication.publicationId(),
                        "graphName", publication.graphName(),
                        "draftRevision", publication.draftRevision()
                )),
                diagnostics
        );
    }

    /**
     * @param publicationId publication id
     * @return stable visual operator reference for the publication
     */
    public static String operatorRef(String publicationId) {
        return "publication:" + (publicationId == null ? "" : publicationId);
    }

    private static OperatorDefinition.Capabilities publicationCapabilities(VisualGraphPublication publication) {
        boolean requiresSecrets = publication.operatorSnapshots().stream()
                .anyMatch(operator -> operator.capabilities().requiresSecrets());
        boolean pure = publication.operatorSnapshots().stream()
                .allMatch(operator -> "PURE".equals(operator.capabilities().effect()));
        boolean deterministic = publication.operatorSnapshots().stream()
                .allMatch(operator -> "DETERMINISTIC".equals(operator.capabilities().idempotency())
                        || "IDEMPOTENT".equals(operator.capabilities().idempotency()));
        return new OperatorDefinition.Capabilities(
                pure ? "PURE" : "EXTERNAL",
                deterministic ? "DETERMINISTIC" : "UNKNOWN",
                false,
                requiresSecrets
        );
    }

    private static SchemaEnvelope outputSchema(VisualGraphPublication publication) {
        GraphDraft draft = publication.draft();
        if (draft == null || draft.output().nodeId().isBlank()) {
            return SchemaEnvelope.opaque();
        }
        Optional<GraphDraft.DraftNode> outputNode = draft.nodes().stream()
                .filter(node -> node.id().equals(draft.output().nodeId()))
                .findFirst();
        if (outputNode.isEmpty()) {
            return SchemaEnvelope.opaque();
        }
        Optional<OperatorDefinition> outputOperator = publication.operatorSnapshots().stream()
                .filter(operator -> operator.operatorRef().equals(outputNode.get().operatorRef()))
                .findFirst();
        if (outputOperator.isEmpty() || outputOperator.get().ports().outputs().isEmpty()) {
            return SchemaEnvelope.opaque();
        }
        OperatorDefinition.Port port = outputOperator.get().ports().outputs().size() == 1
                ? outputOperator.get().ports().outputs().getFirst()
                : outputOperator.get().ports().outputs().stream()
                .filter(candidate -> "output".equals(candidate.name()))
                .findFirst()
                .orElse(outputOperator.get().ports().outputs().getFirst());
        Map<String, Object> schema = propertyAtPath(port.schema(), draft.output().path());
        return new SchemaEnvelope(port.schema().format(), port.schema().version(),
                schema == null ? SchemaEnvelope.opaque().schema() : schema);
    }

    private static Map<String, Object> propertyAtPath(SchemaEnvelope schema, String path) {
        if (path == null || path.isBlank()) {
            return new LinkedHashMap<>(schema.schema());
        }
        Map<String, Object> currentSchema = schema.schema();
        Map<String, Object> current = null;
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            current = objectProperty(propertiesOf(currentSchema).get(segment));
            if (current == null) {
                current = additionalPropertySchema(currentSchema);
                if (current == null) {
                    return null;
                }
            }
            currentSchema = current;
        }
        return current;
    }

    private static Map<String, Object> propertiesOf(Map<String, Object> schema) {
        Object nested = schema.get("properties");
        if (!(nested instanceof Map<?, ?> rawNested)) {
            return Map.of();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        rawNested.forEach((key, value) -> properties.put(String.valueOf(key), value));
        return properties;
    }

    private static Map<String, Object> additionalPropertySchema(Map<String, Object> schema) {
        Object raw = schema.get("additionalProperties");
        if (Boolean.TRUE.equals(raw)) {
            return Map.of();
        }
        return objectProperty(raw);
    }

    private static Map<String, Object> objectProperty(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }
}
