package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphPublicationOperator;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaIntrospection;

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
        boolean streaming = publication.operatorSnapshots().stream()
                .anyMatch(operator -> operator.capabilities().streaming());
        boolean durable = publication.operatorSnapshots().stream()
                .anyMatch(operator -> operator.capabilities().durable());
        boolean pure = publication.operatorSnapshots().stream()
                .allMatch(operator -> "PURE".equals(operator.capabilities().effect()));
        boolean deterministic = publication.operatorSnapshots().stream()
                .allMatch(operator -> "DETERMINISTIC".equals(operator.capabilities().idempotency())
                        || "IDEMPOTENT".equals(operator.capabilities().idempotency()));
        return new OperatorDefinition.Capabilities(
                pure ? "PURE" : "EXTERNAL",
                deterministic ? "DETERMINISTIC" : "UNKNOWN",
                streaming,
                durable,
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
        if (draft.output().path().isBlank()) {
            return wholeOutputSchema(outputOperator.get());
        }
        OutputReference outputReference = outputReference(outputOperator.get(), draft.output().path());
        Optional<OperatorDefinition.Port> port = resolveOutputPort(outputOperator.get(), outputReference.port());
        if (port.isEmpty()) {
            return SchemaEnvelope.opaque();
        }
        Map<String, Object> schema = VisualSchemaIntrospection.schemaAtPath(port.get().schema().schema(),
                outputReference.path());
        return new SchemaEnvelope(port.get().schema().format(), port.get().schema().version(),
                schema == null ? SchemaEnvelope.opaque().schema() : schema);
    }

    private static SchemaEnvelope wholeOutputSchema(OperatorDefinition operator) {
        List<OperatorDefinition.Port> outputs = operator.ports().outputs();
        if (outputs.size() == 1) {
            return outputs.getFirst().schema();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (OperatorDefinition.Port output : outputs) {
            properties.put(output.name(), output.schema().schema());
            if (output.required()) {
                required.add(output.name());
            }
        }
        return SchemaEnvelope.object(properties, required);
    }

    private static Optional<OperatorDefinition.Port> resolveOutputPort(OperatorDefinition operator,
                                                                       String portName) {
        List<OperatorDefinition.Port> outputs = operator.ports().outputs();
        if ((portName == null || portName.isBlank()) && outputs.size() == 1) {
            return Optional.of(outputs.getFirst());
        }
        return outputs.stream()
                .filter(port -> port.name().equals(portName))
                .findFirst();
    }

    private static OutputReference outputReference(OperatorDefinition operator, String outputPath) {
        if (outputPath == null || outputPath.isBlank()) {
            return new OutputReference("", "");
        }
        String[] segments = outputPath.split("\\.", 2);
        String first = segments[0];
        String rest = segments.length == 2 ? segments[1] : "";
        boolean firstNamesPort = operator.ports().outputs().stream()
                .anyMatch(port -> port.name().equals(first));
        return firstNamesPort ? new OutputReference(first, rest) : new OutputReference("", outputPath);
    }

    private record OutputReference(String port, String path) {
    }
}
