package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Projects authoring contracts while removing runtime locations and author-controlled prose. */
final class DslContractLens {
    private static final Set<String> SAFE_SCHEMA_KEYS = Set.of(
            "type", "properties", "required", "items", "additionalProperties", "oneOf", "anyOf", "allOf",
            "not", "enum", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength",
            "maxLength", "minItems", "maxItems", "uniqueItems", "minProperties", "maxProperties");
    private static final int MAX_FINGERPRINT_BYTES = 512 * 1024;

    private final ObjectMapper mapper;

    DslContractLens(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Returns the minimal structure needed to bind and configure one authorized operator. */
    DslReferenceSnapshot.OperatorContract operator(OperatorDefinition value) {
        return new DslReferenceSnapshot.OperatorContract(
                value.operatorRef(), archetype(value), value.capabilities().effect(),
                ports(value.ports().inputs()), ports(value.ports().outputs()),
                schema(value.configSchema()), value.fingerprint(), bindingState(value));
    }

    /** Returns a callable signature derived from typed parameters rather than library prose. */
    DslReferenceSnapshot.FunctionContract function(OperatorLibrary.BuiltInFunction value) {
        List<String> signatures = value.signatures().stream().map(signature -> {
            String parameters = signature.parameters().stream()
                    .map(parameter -> parameter.type()
                            + (parameter.optional() ? "?" : "")
                            + (parameter.variadic() ? "..." : ""))
                    .collect(java.util.stream.Collectors.joining(", "));
            return "(" + parameters + ") -> " + signature.returns().type();
        }).toList();
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("name", value.name());
        material.put("signatures", signatures);
        return new DslReferenceSnapshot.FunctionContract(value.name(), String.join(" | ", signatures),
                VisualBundleFingerprint.fromCanonicalValue(mapper, material, MAX_FINGERPRINT_BYTES));
    }

    /** Returns a structural JSON Schema with documentation, defaults and examples removed. */
    Map<String, Object> schema(SchemaEnvelope envelope) {
        return schemaMap(envelope == null ? Map.of() : envelope.schema());
    }

    private List<DslReferenceSnapshot.PortContract> ports(List<OperatorDefinition.Port> values) {
        return values.stream().map(port -> new DslReferenceSnapshot.PortContract(
                port.name(), port.required(), VisualBundleFingerprint.fromCanonicalValue(
                        mapper, schema(port.schema()), MAX_FINGERPRINT_BYTES))).toList();
    }

    private static String archetype(OperatorDefinition value) {
        if ("bloge:decisionTable".equals(value.operatorRef())) return "decision-table";
        if ("bloge:transform".equals(value.operatorRef())) return "transform";
        return value.source().kind();
    }

    private static String bindingState(OperatorDefinition value) {
        if ("design".equals(value.lowering().mode())) return "DESIGN_ONLY";
        return value.runtimeReadiness().executable() ? "EXECUTABLE" : "AUTHORING_ONLY";
    }

    private static Map<String, Object> schemaMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((rawKey, rawValue) -> {
            String key = String.valueOf(rawKey);
            if (!SAFE_SCHEMA_KEYS.contains(key)) return;
            if ("properties".equals(key) && rawValue instanceof Map<?, ?> properties) {
                LinkedHashMap<String, Object> safeProperties = new LinkedHashMap<>();
                properties.entrySet().stream().sorted(Map.Entry.comparingByKey(
                                java.util.Comparator.comparing(String::valueOf)))
                        .forEach(entry -> safeProperties.put(String.valueOf(entry.getKey()),
                                entry.getValue() instanceof Map<?, ?> nested ? schemaMap(nested) : Map.of()));
                result.put(key, Map.copyOf(safeProperties));
            } else {
                result.put(key, schemaValue(rawValue));
            }
        });
        return Map.copyOf(result);
    }

    private static Object schemaValue(Object value) {
        if (value instanceof Map<?, ?> map) return schemaMap(map);
        if (value instanceof List<?> list) {
            return list.stream().map(item -> item instanceof Map<?, ?> map ? schemaMap(map) : scalar(item)).toList();
        }
        return scalar(value);
    }

    private static Object scalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean
                ? value : String.valueOf(value);
    }
}
