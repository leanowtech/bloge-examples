package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared structural validator for schema envelopes used by visual authoring.
 */
public final class VisualSchemaValidator {

    private static final Set<String> SUPPORTED_SCHEMA_VERSIONS = Set.of("2020-12");
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
    private static final Set<String> UNSUPPORTED_REFERENCE_KEYWORDS = Set.of(
            "$ref",
            "$dynamicRef"
    );
    private static final Set<String> UNSUPPORTED_COMPOSITION_KEYWORDS = Set.of(
            "oneOf",
            "anyOf",
            "allOf",
            "not",
            "if",
            "then",
            "else"
    );
    private static final Set<String> UNSUPPORTED_CONSTRAINT_KEYWORDS = Set.of(
            "pattern",
            "multipleOf",
            "minLength",
            "maxLength",
            "minItems",
            "maxItems",
            "uniqueItems",
            "contains",
            "minContains",
            "maxContains",
            "prefixItems",
            "patternProperties",
            "propertyNames",
            "dependentRequired",
            "dependentSchemas",
            "unevaluatedProperties",
            "unevaluatedItems"
    );

    private VisualSchemaValidator() {
    }

    /**
     * @param envelope schema envelope to validate
     * @param path JSON pointer to the schema envelope
     * @return diagnostics describing envelope or schema issues
     */
    public static List<VisualDiagnostic> validateEnvelope(SchemaEnvelope envelope, String path) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        SchemaEnvelope effective = envelope == null ? SchemaEnvelope.opaque() : envelope;
        if (!SchemaEnvelope.JSON_SCHEMA.equals(effective.format())) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.formatUnsupported",
                    "Unsupported schema format '%s'; visual authoring supports '%s'."
                            .formatted(effective.format(), SchemaEnvelope.JSON_SCHEMA),
                    path + "/format"));
        }
        if (!SUPPORTED_SCHEMA_VERSIONS.contains(effective.version())) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.versionUnsupported",
                    "Unsupported schema version '%s'; visual authoring supports %s."
                            .formatted(effective.version(), SUPPORTED_SCHEMA_VERSIONS),
                    path + "/version"));
        }
        validateSchema(effective.schema(), path + "/schema", diagnostics);
        return diagnostics;
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
        boolean hasUnsupportedKeyword = validateUnsupportedKeywords(schema, path, diagnostics);
        String kind = schemaKind(schema);
        if (kind.isBlank()) {
            if (!hasUnsupportedKeyword) {
                diagnostics.add(VisualDiagnostic.warning("visual.schema.opaque",
                        "Schema has no type/kind; it will be treated as opaque.",
                        path));
            }
            return;
        }
        if (!SUPPORTED_SCHEMA_KINDS.contains(kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.unsupportedType",
                    "Unsupported schema type/kind '%s'.".formatted(kind),
                    path + "/type"));
            return;
        }
        validateStandardEnum(schema, kind, path, diagnostics);
        validateConstValue(schema, kind, path, diagnostics);
        validateNumericBounds(schema, kind, path, diagnostics);
        validateDefaultValue(schema, kind, path, diagnostics);
        if ("object".equals(kind)) {
            Map<String, Object> properties = objectProperties(schema, path, diagnostics);
            validateAdditionalProperties(schema, path, diagnostics);
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
            validateCustomEnumValues(schema, path, diagnostics);
        }
    }

    private static boolean validateUnsupportedKeywords(Map<String, Object> schema,
                                                       String path,
                                                       List<VisualDiagnostic> diagnostics) {
        boolean unsupported = false;
        for (String keyword : UNSUPPORTED_REFERENCE_KEYWORDS) {
            if (schema.containsKey(keyword)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.refUnsupported",
                        "Schema reference keyword '%s' is not supported by visual authoring schemas."
                                .formatted(keyword),
                        path + "/" + keyword));
                unsupported = true;
            }
        }
        for (String keyword : UNSUPPORTED_COMPOSITION_KEYWORDS) {
            if (schema.containsKey(keyword)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.compositionUnsupported",
                        "JSON Schema composition keyword '%s' is not supported by visual authoring schemas."
                                .formatted(keyword),
                        path + "/" + keyword));
                unsupported = true;
            }
        }
        for (String keyword : UNSUPPORTED_CONSTRAINT_KEYWORDS) {
            if (schema.containsKey(keyword)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.constraintUnsupported",
                        "JSON Schema constraint keyword '%s' is not supported by visual authoring schemas."
                                .formatted(keyword),
                        path + "/" + keyword));
                unsupported = true;
            }
        }
        return unsupported;
    }

    @SuppressWarnings("unchecked")
    private static void validateDefaultValue(Map<String, Object> schema,
                                             String kind,
                                             String path,
                                             List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey("default") || "any".equals(kind) || "opaque".equals(kind)) {
            return;
        }
        validateDefaultValue(schema, kind, path + "/default", diagnostics, schema.get("default"));
    }

    @SuppressWarnings("unchecked")
    private static void validateDefaultValue(Map<String, Object> schema,
                                             String kind,
                                             String path,
                                             List<VisualDiagnostic> diagnostics,
                                             Object value) {
        if (kind.isBlank() || "any".equals(kind) || "opaque".equals(kind)) {
            return;
        }
        if (schema.containsKey("const") && !Objects.equals(schema.get("const"), value)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstMismatch",
                    "Schema default must equal const value '%s'.".formatted(schema.get("const")),
                    path));
        }
        if ("object".equals(kind)) {
            if (!(value instanceof Map<?, ?> rawMap)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultTypeMismatch",
                        "Schema default must match type/kind '%s'.".formatted(kind),
                        path));
                return;
            }
            Map<String, Object> object = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> object.put(String.valueOf(key), item));
            Map<String, Object> properties = propertiesWithoutDiagnostics(schema);
            for (String required : requiredNamesWithoutDiagnostics(schema)) {
                if (!object.containsKey(required) || object.get(required) == null) {
                    diagnostics.add(VisualDiagnostic.error("visual.schema.defaultRequiredMissing",
                            "Schema default is missing required property '%s'.".formatted(required),
                            path + "/" + required));
                }
            }
            Object additional = schema.get("additionalProperties");
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                Object property = properties.get(entry.getKey());
                if (property instanceof Map<?, ?> nested) {
                    validateDefaultValue((Map<String, Object>) nested, schemaKind((Map<String, Object>) nested),
                            path + "/" + entry.getKey(), diagnostics, entry.getValue());
                } else if (Boolean.FALSE.equals(additional)) {
                    diagnostics.add(VisualDiagnostic.error("visual.schema.defaultUnknownProperty",
                            "Schema default contains undeclared property '%s'.".formatted(entry.getKey()),
                            path + "/" + entry.getKey()));
                } else if (additional instanceof Map<?, ?> additionalSchema) {
                    validateDefaultValue((Map<String, Object>) additionalSchema,
                            schemaKind((Map<String, Object>) additionalSchema),
                            path + "/" + entry.getKey(), diagnostics, entry.getValue());
                }
            }
            return;
        }
        if ("array".equals(kind)) {
            if (!(value instanceof List<?> list)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultTypeMismatch",
                        "Schema default must match type/kind '%s'.".formatted(kind),
                        path));
                return;
            }
            Object items = schema.get("items");
            if (items instanceof Map<?, ?> itemSchema) {
                Map<String, Object> itemSchemaMap = (Map<String, Object>) itemSchema;
                for (int i = 0; i < list.size(); i++) {
                    validateDefaultValue(itemSchemaMap, schemaKind(itemSchemaMap), path + "/" + i,
                            diagnostics, list.get(i));
                }
            }
            return;
        }
        if ("enum".equals(kind)) {
            Object values = schema.get("values");
            if (values instanceof List<?> list && !list.contains(value)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultEnumMismatch",
                        "Schema default must be one of %s.".formatted(list),
                        path));
            }
            return;
        }
        Object rawEnum = schema.get("enum");
        if (rawEnum instanceof List<?> values && !values.contains(value)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.defaultEnumMismatch",
                    "Schema default must be one of %s.".formatted(values),
                    path));
        }
        if (valueConstrainedKind(kind) && !enumValueMatchesKind(value, kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.defaultTypeMismatch",
                    "Schema default must match type/kind '%s'.".formatted(kind),
                    path));
        }
        if (numericKind(kind) && !numericValueMatchesBounds(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
                    "Schema default must satisfy numeric bounds.",
                    path));
        }
    }

    private static Map<String, Object> propertiesWithoutDiagnostics(Map<String, Object> schema) {
        Object raw = schema.get("properties");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> properties.put(String.valueOf(key), value));
        return properties;
    }

    private static List<String> requiredNamesWithoutDiagnostics(Map<String, Object> schema) {
        Object raw = schema.get("required");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> required = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String name && !name.isBlank()) {
                required.add(name);
            }
        }
        return required;
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
        if (raw == null && schema.containsKey("const")) {
            return schemaKindForValue(schema.get("const"));
        }
        return raw == null ? "" : String.valueOf(raw);
    }

    private static Map<String, Object> objectProperties(Map<String, Object> schema,
                                                        String path,
                                                        List<VisualDiagnostic> diagnostics) {
        Object raw = schema.get("properties");
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.propertiesInvalid",
                    "Object schema properties must be an object whose values are schemas.",
                    path + "/properties"));
            return Map.of();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> properties.put(String.valueOf(key), value));
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static void validateAdditionalProperties(Map<String, Object> schema,
                                                     String path,
                                                     List<VisualDiagnostic> diagnostics) {
        Object additional = schema.get("additionalProperties");
        if (additional == null || additional instanceof Boolean) {
            return;
        }
        if (additional instanceof Map<?, ?> additionalSchema) {
            validateSchema((Map<String, Object>) additionalSchema, path + "/additionalProperties", diagnostics);
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.schema.additionalPropertiesInvalid",
                "Object schema additionalProperties must be a boolean or schema object.",
                path + "/additionalProperties"));
    }

    private static void validateStandardEnum(Map<String, Object> schema,
                                             String kind,
                                             String path,
                                             List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey("enum")) {
            return;
        }
        Object rawEnum = schema.get("enum");
        if (!(rawEnum instanceof List<?> values) || values.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.enumInvalid",
                    "Schema enum must be a non-empty array.",
                    path + "/enum"));
            return;
        }
        validateEnumValues(values, path + "/enum", diagnostics);
        if (valueConstrainedKind(kind)) {
            for (int i = 0; i < values.size(); i++) {
                if (!enumValueMatchesKind(values.get(i), kind)) {
                    diagnostics.add(VisualDiagnostic.error("visual.schema.enumTypeMismatch",
                            "Enum value at index %d must match schema type '%s'.".formatted(i, kind),
                            path + "/enum/" + i));
                }
            }
        }
        if (numericKind(kind)) {
            for (int i = 0; i < values.size(); i++) {
                if (enumValueMatchesKind(values.get(i), kind)
                        && !numericValueMatchesBounds(values.get(i), schema)) {
                    diagnostics.add(VisualDiagnostic.error("visual.schema.enumConstraintMismatch",
                            "Enum value at index %d must satisfy numeric bounds.".formatted(i),
                            path + "/enum/" + i));
                }
            }
        }
    }

    private static void validateConstValue(Map<String, Object> schema,
                                           String kind,
                                           String path,
                                           List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey("const")) {
            return;
        }
        Object constValue = schema.get("const");
        if (!constValueMatchesKind(constValue, kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.constTypeMismatch",
                    "Const value must match schema type/kind '%s'.".formatted(kind),
                    path + "/const"));
        }
        Object rawEnum = schema.get("enum");
        if (rawEnum instanceof List<?> values && !values.contains(constValue)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.constEnumMismatch",
                    "Const value must be one of enum %s.".formatted(values),
                    path + "/const"));
        }
        if ("enum".equals(kind)) {
            Object rawValues = schema.get("values");
            if (rawValues instanceof List<?> values && !values.contains(constValue)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.constEnumMismatch",
                        "Const value must be one of enum values %s.".formatted(values),
                        path + "/const"));
            }
        }
        if (numericKind(kind) && constValueMatchesKind(constValue, kind)
                && !numericValueMatchesBounds(constValue, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.constConstraintMismatch",
                    "Const value must satisfy numeric bounds.",
                    path + "/const"));
        }
    }

    private static void validateNumericBounds(Map<String, Object> schema,
                                              String kind,
                                              String path,
                                              List<VisualDiagnostic> diagnostics) {
        if (!hasNumericBounds(schema)) {
            return;
        }
        boolean validNumericKind = numericKind(kind);
        if (!validNumericKind) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.numericConstraintTypeMismatch",
                    "Numeric bounds require schema type/kind integer, number, or decimal.",
                    path));
        }
        validateNumericBoundary(schema, "minimum", path, diagnostics);
        validateNumericBoundary(schema, "maximum", path, diagnostics);
        validateNumericBoundary(schema, "exclusiveMinimum", path, diagnostics);
        validateNumericBoundary(schema, "exclusiveMaximum", path, diagnostics);
        if (!validNumericKind || !numericBoundariesValid(schema)) {
            return;
        }
        NumericBoundary lower = lowerBound(schema);
        NumericBoundary upper = upperBound(schema);
        if (lower != null && upper != null && boundsContradict(lower, upper)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.numericBoundsInvalid",
                    "Numeric lower bound %s is incompatible with upper bound %s."
                            .formatted(lower.lowerLabel(), upper.upperLabel()),
                    path));
        }
    }

    private static void validateCustomEnumValues(Map<String, Object> schema,
                                                 String path,
                                                 List<VisualDiagnostic> diagnostics) {
        Object values = schema.get("values");
        if (!(values instanceof List<?> list) || list.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.enumValuesMissing",
                    "Enum schema must declare non-empty values.",
                    path + "/values"));
            return;
        }
        validateEnumValues(list, path + "/values", diagnostics);
    }

    private static void validateEnumValues(List<?> values,
                                           String path,
                                           List<VisualDiagnostic> diagnostics) {
        Set<Object> seen = new LinkedHashSet<>();
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (!seen.add(value)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.enumDuplicate",
                        "Enum value '%s' is duplicated.".formatted(value),
                        path + "/" + i));
            }
        }
    }

    private static boolean hasNumericBounds(Map<String, Object> schema) {
        return schema.containsKey("minimum")
                || schema.containsKey("maximum")
                || schema.containsKey("exclusiveMinimum")
                || schema.containsKey("exclusiveMaximum");
    }

    private static void validateNumericBoundary(Map<String, Object> schema,
                                                String keyword,
                                                String path,
                                                List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey(keyword)) {
            return;
        }
        Object raw = schema.get(keyword);
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.numericConstraintInvalid",
                    "Numeric constraint '%s' must be a finite number.".formatted(keyword),
                    path + "/" + keyword));
        }
    }

    private static boolean numericBoundariesValid(Map<String, Object> schema) {
        return (!schema.containsKey("minimum") || numericBoundaryValue(schema.get("minimum")).isPresent())
                && (!schema.containsKey("maximum") || numericBoundaryValue(schema.get("maximum")).isPresent())
                && (!schema.containsKey("exclusiveMinimum")
                || numericBoundaryValue(schema.get("exclusiveMinimum")).isPresent())
                && (!schema.containsKey("exclusiveMaximum")
                || numericBoundaryValue(schema.get("exclusiveMaximum")).isPresent());
    }

    private static boolean numericValueMatchesBounds(Object value, Map<String, Object> schema) {
        if (!(value instanceof Number number)) {
            return true;
        }
        double numericValue = number.doubleValue();
        NumericBoundary lower = lowerBound(schema);
        if (lower != null && !lower.acceptsLower(numericValue)) {
            return false;
        }
        NumericBoundary upper = upperBound(schema);
        return upper == null || upper.acceptsUpper(numericValue);
    }

    private static NumericBoundary lowerBound(Map<String, Object> schema) {
        NumericBoundary minimum = numericBoundary(schema.get("minimum"), false);
        NumericBoundary exclusiveMinimum = numericBoundary(schema.get("exclusiveMinimum"), true);
        if (minimum == null) {
            return exclusiveMinimum;
        }
        if (exclusiveMinimum == null) {
            return minimum;
        }
        int comparison = Double.compare(minimum.value(), exclusiveMinimum.value());
        if (comparison > 0) {
            return minimum;
        }
        if (comparison < 0) {
            return exclusiveMinimum;
        }
        return exclusiveMinimum.exclusive() ? exclusiveMinimum : minimum;
    }

    private static NumericBoundary upperBound(Map<String, Object> schema) {
        NumericBoundary maximum = numericBoundary(schema.get("maximum"), false);
        NumericBoundary exclusiveMaximum = numericBoundary(schema.get("exclusiveMaximum"), true);
        if (maximum == null) {
            return exclusiveMaximum;
        }
        if (exclusiveMaximum == null) {
            return maximum;
        }
        int comparison = Double.compare(maximum.value(), exclusiveMaximum.value());
        if (comparison < 0) {
            return maximum;
        }
        if (comparison > 0) {
            return exclusiveMaximum;
        }
        return exclusiveMaximum.exclusive() ? exclusiveMaximum : maximum;
    }

    private static NumericBoundary numericBoundary(Object value, boolean exclusive) {
        return numericBoundaryValue(value)
                .map(boundaryValue -> new NumericBoundary(boundaryValue, exclusive))
                .orElse(null);
    }

    private static java.util.Optional<Double> numericBoundaryValue(Object value) {
        if (!(value instanceof Number number)) {
            return java.util.Optional.empty();
        }
        double numericValue = number.doubleValue();
        return Double.isFinite(numericValue) ? java.util.Optional.of(numericValue) : java.util.Optional.empty();
    }

    private static boolean boundsContradict(NumericBoundary lower, NumericBoundary upper) {
        int comparison = Double.compare(lower.value(), upper.value());
        return comparison > 0 || comparison == 0 && (lower.exclusive() || upper.exclusive());
    }

    private static boolean numericKind(String kind) {
        return "integer".equals(kind) || "number".equals(kind) || "decimal".equals(kind);
    }

    private static boolean valueConstrainedKind(String kind) {
        return switch (kind) {
            case "string", "duration", "datetime", "integer", "number", "decimal", "boolean", "null" -> true;
            default -> false;
        };
    }

    private static boolean constValueMatchesKind(Object value, String kind) {
        return switch (kind) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "enum" -> true;
            default -> !valueConstrainedKind(kind) || enumValueMatchesKind(value, kind);
        };
    }

    private static boolean enumValueMatchesKind(Object value, String kind) {
        return switch (kind) {
            case "string", "duration", "datetime" -> value instanceof String;
            case "integer" -> isIntegerValue(value);
            case "number", "decimal" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true;
        };
    }

    private static boolean isIntegerValue(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return true;
        }
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            return Double.isFinite(doubleValue) && Math.rint(doubleValue) == doubleValue;
        }
        return false;
    }

    private static String schemaKindForValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (isIntegerValue(value)) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        return "";
    }

    private record NumericBoundary(double value, boolean exclusive) {

        private boolean acceptsLower(double candidate) {
            return exclusive ? candidate > value : candidate >= value;
        }

        private boolean acceptsUpper(double candidate) {
            return exclusive ? candidate < value : candidate <= value;
        }

        private String lowerLabel() {
            return exclusive ? "value > " + trimNumber(value) : "value >= " + trimNumber(value);
        }

        private String upperLabel() {
            return exclusive ? "value < " + trimNumber(value) : "value <= " + trimNumber(value);
        }

        private static String trimNumber(double value) {
            if (Math.rint(value) == value) {
                return String.valueOf((long) value);
            }
            return String.valueOf(value);
        }
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
