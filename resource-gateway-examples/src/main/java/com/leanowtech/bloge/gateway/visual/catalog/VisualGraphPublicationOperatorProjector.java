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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Projects immutable visual graph publications back into reusable visual operators.
 */
@Component
public class VisualGraphPublicationOperatorProjector {

    private static final Pattern ARRAY_INDEX = Pattern.compile("\\d+");

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
        if (draft.output().path().isBlank()) {
            return wholeOutputSchema(outputOperator.get());
        }
        OutputReference outputReference = outputReference(outputOperator.get(), draft.output().path());
        Optional<OperatorDefinition.Port> port = resolveOutputPort(outputOperator.get(), outputReference.port());
        if (port.isEmpty()) {
            return SchemaEnvelope.opaque();
        }
        Map<String, Object> schema = propertyAtPath(port.get().schema(), outputReference.path());
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
            if ("array".equals(schemaType(currentSchema))) {
                Integer index = arrayIndexSegment(segment);
                if (index == null) {
                    return null;
                }
                current = arrayItemSchemaForIndex(currentSchema, index);
                if (current == null) {
                    return null;
                }
                currentSchema = current;
                continue;
            }
            current = objectProperty(propertiesOf(currentSchema).get(segment));
            if (current == null) {
                current = patternPropertySchema(currentSchema, segment);
            }
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

    private static Integer arrayIndexSegment(String segment) {
        if (!ARRAY_INDEX.matcher(segment).matches()) {
            return null;
        }
        try {
            int index = Integer.parseInt(segment);
            return index < 0 ? null : index;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Map<String, Object> arrayItemSchemaForIndex(Map<String, Object> schema, int index) {
        List<Map<String, Object>> prefixItems = prefixItemsOf(schema);
        if (index < prefixItems.size()) {
            return prefixItems.get(index);
        }
        return objectProperty(schema.get("items"));
    }

    private static List<Map<String, Object>> prefixItemsOf(Map<String, Object> schema) {
        Object raw = schema.get("prefixItems");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> prefixItems = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> itemSchema = objectProperty(value);
            if (itemSchema != null) {
                prefixItems.add(itemSchema);
            }
        }
        return prefixItems;
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
        Object raw = residualPropertiesPolicy(schema);
        if (Boolean.TRUE.equals(raw)) {
            return Map.of();
        }
        return objectProperty(raw);
    }

    private static Object residualPropertiesPolicy(Map<String, Object> schema) {
        if (schema.containsKey("additionalProperties")) {
            return schema.get("additionalProperties");
        }
        return schema.get("unevaluatedProperties");
    }

    private static Map<String, Object> patternPropertySchema(Map<String, Object> schema, String propertyName) {
        List<Map<String, Object>> matches = matchingPatternPropertySchemas(schema, propertyName);
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static List<Map<String, Object>> matchingPatternPropertySchemas(Map<String, Object> schema,
                                                                            String propertyName) {
        Object raw = schema.get("patternProperties");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return List.of();
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String pattern = String.valueOf(entry.getKey());
            if (patternMatches(pattern, propertyName) && entry.getValue() instanceof Map<?, ?> nested) {
                matches.add(objectProperty(nested));
            }
        }
        return matches;
    }

    private static boolean patternMatches(String pattern, String value) {
        try {
            return Pattern.compile(pattern).matcher(value).find();
        } catch (PatternSyntaxException ex) {
            return false;
        }
    }

    private static String schemaType(Map<String, Object> schema) {
        Object type = schema.get("kind");
        if (type == null) {
            type = schema.get("type");
        }
        if (type instanceof List<?> values) {
            return nullableTypePrimary(values);
        }
        if (type == null && schema.containsKey("properties")) {
            return "object";
        }
        if (type == null && schema.containsKey("items")) {
            return "array";
        }
        return type == null ? "" : String.valueOf(type);
    }

    private static String nullableTypePrimary(List<?> types) {
        String primary = "";
        int concreteTypes = 0;
        for (Object item : types) {
            if (!(item instanceof String type) || type.isBlank()) {
                return String.valueOf(types);
            }
            if (!"null".equals(type)) {
                primary = type;
                concreteTypes++;
            }
        }
        if (concreteTypes > 1) {
            return String.valueOf(types);
        }
        return primary.isBlank() ? "null" : primary;
    }

    private static Map<String, Object> objectProperty(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private record OutputReference(String port, String path) {
    }
}
