package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates user-provided operator libraries before they enter the visual catalog.
 */
@Service
public class OperatorLibraryValidator {

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
    private static final Set<String> SUPPORTED_LOWERING_MODES = Set.of("native", "transform");

    /**
     * @param library user-provided library
     * @return structured validation result
     */
    public VisualValidationResult validate(OperatorLibrary library) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (library == null) {
            diagnostics.add(VisualDiagnostic.error("visual.library.missing",
                    "Operator library is required.",
                    "/"));
            return new VisualValidationResult(false, diagnostics);
        }
        if (library.operators().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.library.empty",
                    "Operator library must contain at least one operator.",
                    "/operators"));
        }
        for (int i = 0; i < library.operators().size(); i++) {
            validateOperator(library.operators().get(i), "/operators/" + i, diagnostics);
        }
        return new VisualValidationResult(false, diagnostics);
    }

    private static void validateOperator(OperatorDefinition operator,
                                         String path,
                                         List<VisualDiagnostic> diagnostics) {
        if (operator.ports().outputs().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.output.required",
                    "Operator '%s' must declare at least one output port.".formatted(operator.operatorRef()),
                    path + "/ports/outputs"));
        }
        validatePorts(operator, "inputs", operator.ports().inputs(), path + "/ports/inputs", diagnostics);
        validatePorts(operator, "outputs", operator.ports().outputs(), path + "/ports/outputs", diagnostics);
        validateSchema(operator.configSchema().schema(), path + "/configSchema/schema", diagnostics);
        validateLowering(operator, path + "/lowering", diagnostics);
    }

    private static void validatePorts(OperatorDefinition operator,
                                      String direction,
                                      List<OperatorDefinition.Port> ports,
                                      String path,
                                      List<VisualDiagnostic> diagnostics) {
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < ports.size(); i++) {
            OperatorDefinition.Port port = ports.get(i);
            if (!seen.add(port.name())) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.port.duplicate",
                        "Operator '%s' declares duplicate %s port '%s'."
                                .formatted(operator.operatorRef(), direction, port.name()),
                        path + "/" + i + "/name"));
            }
            validateSchema(port.schema().schema(), path + "/" + i + "/schema/schema", diagnostics);
        }
    }

    private static void validateLowering(OperatorDefinition operator,
                                         String path,
                                         List<VisualDiagnostic> diagnostics) {
        String mode = operator.lowering().mode();
        if (!SUPPORTED_LOWERING_MODES.contains(mode)) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.lowering.unsupported",
                    "Operator '%s' uses unsupported lowering mode '%s'."
                            .formatted(operator.operatorRef(), mode),
                    path + "/mode"));
        }
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
            for (String required : requiredNames(schema)) {
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
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        rawMap.forEach((key, value) -> properties.put(String.valueOf(key), value));
        return properties;
    }

    private static List<String> requiredNames(Map<String, Object> schema) {
        Object raw = schema.get("required");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> required = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                required.add(String.valueOf(item));
            }
        }
        return required;
    }
}
