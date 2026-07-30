package com.leanowtech.bloge.gateway.visual.authoring.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Conservatively lowers canonical source-adapter output into the progressive authoring contract.
 *
 * <p>Unsupported JSON Schema constructs become {@code unknown} plus review items. This is
 * intentional: source discovery must never turn partial schema understanding into a stronger
 * declaration than the source proves.</p>
 */
@Component
public final class AuthoringDocumentProjector {

    private final ObjectMapper objectMapper;

    public AuthoringDocumentProjector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Result project(OperatorLibrary library) {
        if (library == null) {
            return new Result(null, List.of());
        }
        List<AuthoringFactProjection.ReviewItem> reviewItems = new ArrayList<>();
        Map<String, VisualLibraryAuthoringDocument.OperatorAuthoring> operators = new LinkedHashMap<>();
        library.operators().stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .forEach(operator -> operators.put(
                        operator.operatorRef(),
                        operator(operator, reviewItems)));
        Map<String, VisualLibraryAuthoringDocument.FunctionAuthoring> functions = new LinkedHashMap<>();
        library.builtInFunctions().stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(OperatorLibrary.BuiltInFunction::name))
                .forEach(function -> functions.put(
                        function.name(),
                        function(function)));

        String namespace = defaultNamespace(library);
        VisualLibraryAuthoringDocument document = new VisualLibraryAuthoringDocument(
                VisualLibraryAuthoringDocument.SCHEMA_VERSION,
                new VisualLibraryAuthoringDocument.LibraryMetadata(
                        library.libraryId(),
                        library.displayName(),
                        library.version(),
                        library.owner(),
                        library.status()),
                new VisualLibraryAuthoringDocument.Defaults("1.0.0", namespace),
                Map.of(),
                operators,
                functions,
                List.of(),
                Map.of()
        );
        return new Result(document, reviewItems);
    }

    private VisualLibraryAuthoringDocument.OperatorAuthoring operator(
            OperatorDefinition operator,
            List<AuthoringFactProjection.ReviewItem> reviewItems) {
        Map<String, JsonNode> inputs = ports(operator, operator.ports().inputs(), "input", reviewItems);
        Map<String, JsonNode> outputs = ports(operator, operator.ports().outputs(), "output", reviewItems);
        JsonNode config = schema(
                operator.configSchema(),
                operator.operatorRef(),
                "config",
                reviewItems);
        ObjectNode runtime = objectMapper.createObjectNode();
        runtime.put("sourceKind", operator.source().kind());
        runtime.put("loweringMode", operator.lowering().mode());
        if (!operator.lowering().operatorRef().isBlank()) {
            runtime.put("executableOperatorRef", operator.lowering().operatorRef());
        }
        operator.lowering().parameters().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> runtime.set(entry.getKey(), objectMapper.valueToTree(entry.getValue())));
        return new VisualLibraryAuthoringDocument.OperatorAuthoring(
                operator.display().name(),
                operator.display().description(),
                archetype(operator),
                operator.operatorVersion(),
                operator.display().tags(),
                inputs,
                outputs,
                config,
                operator.capabilities().effect(),
                operator.capabilities().idempotency(),
                operator.capabilities().streaming(),
                operator.capabilities().durable(),
                operator.capabilities().requiresSecrets(),
                runtime,
                List.of()
        );
    }

    private Map<String, JsonNode> ports(
            OperatorDefinition operator,
            List<OperatorDefinition.Port> ports,
            String direction,
            List<AuthoringFactProjection.ReviewItem> reviewItems) {
        Map<String, JsonNode> projected = new LinkedHashMap<>();
        for (OperatorDefinition.Port port : ports) {
            if (port == null) {
                continue;
            }
            String key = port.required() ? port.name() : port.name() + "?";
            projected.put(key, schema(port.schema(), operator.operatorRef(),
                    direction + "/" + port.name(), reviewItems));
        }
        return Map.copyOf(projected);
    }

    private JsonNode schema(
            SchemaEnvelope envelope,
            String assetRef,
            String path,
            List<AuthoringFactProjection.ReviewItem> reviewItems) {
        Map<String, Object> raw = envelope == null ? Map.of() : envelope.schema();
        Projection projection = schemaNode(objectMapper.valueToTree(raw), 0);
        if (!projection.precise()) {
            reviewItems.add(new AuthoringFactProjection.ReviewItem(
                    "RG.AUTHORING.DISCOVERY_SCHEMA_REVIEW_REQUIRED",
                    "WARNING",
                    "OPERATOR",
                    assetRef,
                    "Some source schema constraints could not be represented by Quick authoring.",
                    "Review " + path + " in the structured schema editor before runtime promotion."
            ));
        }
        return projection.node();
    }

    private Projection schemaNode(JsonNode schema, int depth) {
        if (schema == null || !schema.isObject() || schema.isEmpty() || depth > 32) {
            return new Projection(TextNode.valueOf("unknown"), false);
        }
        JsonNode anyOf = schema.get("anyOf");
        if (anyOf != null && anyOf.isArray() && anyOf.size() == 2) {
            JsonNode nonNull = null;
            boolean nullable = false;
            for (JsonNode candidate : anyOf) {
                if ("null".equals(candidate.path("type").asText())) {
                    nullable = true;
                } else {
                    nonNull = candidate;
                }
            }
            if (nullable && nonNull != null) {
                Projection nested = schemaNode(nonNull, depth + 1);
                if (nested.node().isTextual()) {
                    return new Projection(TextNode.valueOf(nested.node().asText() + "?"), nested.precise());
                }
                return new Projection(TextNode.valueOf("unknown"), false);
            }
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray() && !enumValues.isEmpty()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.set("enum", enumValues.deepCopy());
            copyConstraints(schema, node);
            return new Projection(node, supportedKeys(schema, List.of(
                    "enum", "description", "minLength", "maxLength", "minimum", "maximum")));
        }
        String type = schema.path("type").asText("");
        if ("object".equals(type) && schema.path("properties").isObject()) {
            ObjectNode node = objectMapper.createObjectNode();
            ObjectNode fields = objectMapper.createObjectNode();
            List<String> required = new ArrayList<>();
            JsonNode requiredNode = schema.get("required");
            if (requiredNode != null && requiredNode.isArray()) {
                requiredNode.forEach(value -> required.add(value.asText()));
            }
            boolean precise = true;
            List<Map.Entry<String, JsonNode>> properties = new ArrayList<>();
            schema.path("properties").properties().forEach(properties::add);
            properties.sort(Map.Entry.comparingByKey());
            for (Map.Entry<String, JsonNode> property : properties) {
                Projection child = schemaNode(property.getValue(), depth + 1);
                fields.set(required.contains(property.getKey())
                        ? property.getKey()
                        : property.getKey() + "?", child.node());
                precise &= child.precise();
            }
            node.set("fields", fields);
            if (schema.has("additionalProperties") && schema.get("additionalProperties").isBoolean()) {
                node.put("additionalProperties", schema.get("additionalProperties").asBoolean());
            }
            copyConstraints(schema, node);
            precise &= supportedKeys(schema, List.of(
                    "type", "properties", "required", "additionalProperties",
                    "description", "minLength", "maxLength", "minimum", "maximum"));
            return new Projection(node, precise);
        }
        if ("array".equals(type)) {
            Projection item = schemaNode(schema.get("items"), depth + 1);
            if (item.node().isTextual()) {
                ObjectNode constrained = objectMapper.createObjectNode();
                constrained.put("type", item.node().asText() + "[]");
                copyConstraints(schema, constrained);
                boolean hasConstraints = constrained.size() > 1;
                return new Projection(
                        hasConstraints ? constrained : TextNode.valueOf(item.node().asText() + "[]"),
                        item.precise() && supportedKeys(schema, List.of(
                                "type", "items", "minItems", "maxItems", "description")));
            }
            return new Projection(TextNode.valueOf("unknown[]"), false);
        }
        String compact = switch (type) {
            case "string" -> switch (schema.path("format").asText("")) {
                case "date" -> "date";
                case "date-time" -> "datetime";
                default -> "string";
            };
            case "integer", "number", "boolean", "null" -> type;
            default -> type.isBlank() ? "unknown" : type;
        };
        ObjectNode constrained = objectMapper.createObjectNode();
        constrained.put("type", compact);
        copyConstraints(schema, constrained);
        boolean hasConstraints = constrained.size() > 1;
        return new Projection(
                hasConstraints ? constrained : TextNode.valueOf(compact),
                !"unknown".equals(compact) && supportedKeys(schema, List.of(
                        "type", "format", "description", "minLength", "maxLength",
                        "minimum", "maximum", "minItems", "maxItems"))
        );
    }

    private static boolean supportedKeys(JsonNode source, List<String> supported) {
        List<String> keys = new ArrayList<>();
        source.fieldNames().forEachRemaining(keys::add);
        return keys.stream().allMatch(supported::contains);
    }

    private static void copyConstraints(JsonNode source, ObjectNode target) {
        for (String key : List.of(
                "description", "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems")) {
            if (source.has(key)) {
                target.set(key, source.get(key).deepCopy());
            }
        }
    }

    private static VisualLibraryAuthoringDocument.FunctionAuthoring function(
            OperatorLibrary.BuiltInFunction function) {
        List<String> signatures = function.signatures().stream()
                .filter(java.util.Objects::nonNull)
                .map(AuthoringDocumentProjector::signature)
                .toList();
        return new VisualLibraryAuthoringDocument.FunctionAuthoring(
                function.name(),
                function.namespace(),
                function.description(),
                function.category(),
                "",
                signatures,
                function.examples(),
                List.of()
        );
    }

    private static String signature(OperatorLibrary.Signature signature) {
        String parameters = String.join(", ", signature.parameters().stream()
                .filter(java.util.Objects::nonNull)
                .map(parameter -> {
                    String prefix = parameter.variadic() ? "..." : "";
                    String optional = parameter.optional() ? "?" : "";
                    return prefix + parameter.name() + optional + ": " + functionType(parameter.type());
                })
                .toList());
        return "(" + parameters + ") -> " + functionType(signature.returns().type());
    }

    private static String functionType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "unknown" -> "unknown";
            case "bool" -> "boolean";
            case "int", "long" -> "integer";
            case "double", "float" -> "number";
            case "map", "list" -> "object";
            default -> normalized;
        };
    }

    private static String archetype(OperatorDefinition operator) {
        String sourceKind = operator.source().kind();
        String lowering = operator.lowering().mode();
        if (List.of("event-source", "message-handler", "webhook", "ai-tool", "remote-worker")
                .contains(sourceKind)) {
            return sourceKind;
        }
        if (List.of("event-source", "message-handler", "webhook", "ai-tool", "remote-worker")
                .contains(lowering)) {
            return lowering;
        }
        return switch (operator.capabilities().effect()) {
            case "PURE" -> operator.operatorRef().contains("decision") ? "decision" : "pure";
            case "READ_EXTERNAL" -> "resource-read";
            case "WRITE_EXTERNAL" -> "external-write";
            default -> "remote-worker";
        };
    }

    private static String defaultNamespace(OperatorLibrary library) {
        return library.operators().stream()
                .filter(java.util.Objects::nonNull)
                .map(OperatorDefinition::operatorRef)
                .map(ref -> ref.contains(":") ? ref.substring(0, ref.indexOf(':')) : "")
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("app");
    }

    public record Result(
            VisualLibraryAuthoringDocument document,
            List<AuthoringFactProjection.ReviewItem> reviewItems
    ) {
        public Result {
            reviewItems = reviewItems == null ? List.of() : List.copyOf(reviewItems);
        }
    }

    private record Projection(JsonNode node, boolean precise) {
    }
}
