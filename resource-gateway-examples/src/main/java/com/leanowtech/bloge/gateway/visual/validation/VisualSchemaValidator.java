package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared structural validator for schema envelopes used by visual authoring.
 */
public final class VisualSchemaValidator {

    private static final Set<String> SUPPORTED_SCHEMA_KINDS = Set.of(
            "object",
            "array",
            "string",
            "integer",
            "number",
            "decimal",
            "boolean",
            "duration",
            "datetime",
            "enum",
            "any",
            "opaque",
            "null"
    );

    private VisualSchemaValidator() {
    }

    /**
     * @param schema schema body to validate
     * @param path JSON pointer to the schema body
     * @return diagnostics describing schema issues
     */
    public static List<VisualDiagnostic> validateSchema(Map<String, Object> schema, String path) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        validateSchema(schema == null ? Map.of() : schema, path, diagnostics);
        return diagnostics;
    }

    @SuppressWarnings("unchecked")
    private static void validateSchema(Map<String, Object> schema,
                                       String path,
                                       List<VisualDiagnostic> diagnostics) {
        String kind = schemaKind(schema);
        if (kind.isBlank()) {
            diagnostics.add(VisualDiagnostic.warning("visual.schema.opaque",
                    "Schema has no type/kind; it will be treated as opaque.",
                    path));
            return;
        }
        if (!SUPPORTED_SCHEMA_KINDS.contains(kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.unsupportedType",
                    "Unsupported schema type/kind '%s'.".formatted(kind),
                    path + "/type"));
            return;
        }
        if ("object".equals(kind)) {
            Map<String, Object> properties = objectProperties(schema);
            List<String> requiredNames = requiredNames(schema, path, diagnostics);
            for (String required : requiredNames) {
                if (!properties.containsKey(required)) {
                    diagnostics.add(VisualDiagnostic.error("visual.schema.requiredUnknown",
                            "Required property '%s' is not declared in properties.".formatted(required),
                            path + "/required"));
                }
            }
            for (Map.Entry<String, Object> property : properties.entrySet()) {
                if (!(property.getValue() instanceof Map<?, ?> nested)) {
                    diagnostics.add(VisualDiagnostic.error("visual.schema.propertyInvalid",
                            "Property '%s' must be a schema object.".formatted(property.getKey()),
                            path + "/properties/" + property.getKey()));
                    continue;
                }
                validateSchema((Map<String, Object>) nested,
                        path + "/properties/" + property.getKey(), diagnostics);
            }
        } else if ("array".equals(kind)) {
            Object items = schema.get("items");
            if (!(items instanceof Map<?, ?> nestedItems)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.arrayItemsMissing",
                        "Array schema must declare an item schema.",
                        path + "/items"));
                return;
            }
            validateSchema((Map<String, Object>) nestedItems, path + "/items", diagnostics);
        } else if ("enum".equals(kind)) {
            Object values = schema.get("values");
            if (!(values instanceof List<?> list) || list.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.enumValuesMissing",
                        "Enum schema must declare non-empty values.",
                        path + "/values"));
            }
        }
    }

    private static String schemaKind(Map<String, Object> schema) {
        Object raw = schema.get("kind");
        if (raw == null) {
            raw = schema.get("type");
        }
        if (raw == null && schema.containsKey("properties")) {
            return "object";
        }
        if (raw == null && schema.containsKey("items")) {
            return "array";
        }
        return raw == null ? "" : String.valueOf(raw);
    }

    private static Map<String, Object> objectProperties(Map<String, Object> schema) {
        Object raw = schema.get("properties");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> properties.put(String.valueOf(key), value));
        return properties;
    }

    private static List<String> requiredNames(Map<String, Object> schema,
                                              String path,
                                              List<VisualDiagnostic> diagnostics) {
        Object raw = schema.get("required");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.requiredInvalid",
                    "Object schema required must be an array of property names.",
                    path + "/required"));
            return List.of();
        }
        List<String> required = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof String name) || name.isBlank()) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.requiredInvalid",
                        "Object schema required entries must be non-blank strings.",
                        path + "/required/" + i));
                continue;
            }
            if (!seen.add(name)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.requiredDuplicate",
                        "Object schema required entry '%s' is duplicated.".formatted(name),
                        path + "/required/" + i));
                continue;
            }
            required.add(name);
        }
        return required;
    }
}
