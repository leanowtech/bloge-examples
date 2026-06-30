package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
            "contains",
            "minContains",
            "maxContains",
            "prefixItems",
            "dependentRequired",
            "dependentSchemas",
            "unevaluatedProperties",
            "unevaluatedItems"
    );
    private static final Set<String> SUPPORTED_STRING_FORMATS = Set.of(
            "date",
            "date-time",
            "duration",
            "email",
            "uri",
            "uuid"
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

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
	        validateNumericMultipleOf(schema, kind, path, diagnostics);
	        validateStringLengthBounds(schema, kind, path, diagnostics);
	        validateStringPattern(schema, kind, path, diagnostics);
	        validateStringFormat(schema, kind, path, diagnostics);
	        validateArrayItemBounds(schema, kind, path, diagnostics);
	        validateArrayUniqueItems(schema, kind, path, diagnostics);
	        validateObjectPropertyBounds(schema, kind, path, diagnostics);
	        validateObjectPatternProperties(schema, kind, path, diagnostics);
	        validateObjectPropertyNames(schema, kind, path, diagnostics);
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
	            if (!objectValueMatchesPropertyBounds(object, schema)) {
	                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
	                        "Schema default must satisfy object property count constraints.",
	                        path));
	            }
	            if (!objectValueMatchesPropertyNames(object, schema)) {
	                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
	                        "Schema default must satisfy object propertyNames constraint.",
	                        path));
	            }
	            if (!objectValueMatchesPatternProperties(object, schema)) {
	                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
	                        "Schema default must satisfy object patternProperties constraints.",
	                        path));
	            }
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
                List<Map<String, Object>> patternProperties = matchingPatternPropertySchemas(schema, entry.getKey());
                if (property instanceof Map<?, ?> nested) {
                    validateDefaultValue((Map<String, Object>) nested, schemaKind((Map<String, Object>) nested),
                            path + "/" + entry.getKey(), diagnostics, entry.getValue());
                }
                for (Map<String, Object> patternProperty : patternProperties) {
                    validateDefaultValue(patternProperty, schemaKind(patternProperty),
                            path + "/" + entry.getKey(), diagnostics, entry.getValue());
                }
                if (property instanceof Map<?, ?> || !patternProperties.isEmpty()) {
                    continue;
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
	            if (!arrayValueMatchesItemBounds(value, schema)) {
	                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
	                        "Schema default must satisfy array item count constraints.",
	                        path));
	            }
	            if (!arrayValueMatchesUniqueItems(value, schema)) {
	                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
	                        "Schema default must satisfy array uniqueItems constraint.",
	                        path));
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
	        if (numericKind(kind) && !numericValueMatchesMultipleOf(value, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
	                    "Schema default must satisfy numeric multipleOf constraint.",
	                    path));
	        }
	        if (stringKind(kind) && !stringValueMatchesLengthBounds(value, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
	                    "Schema default must satisfy string length constraints.",
	                    path));
	        }
		        if (stringKind(kind) && !stringValueMatchesPattern(value, schema)) {
		            diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
		                    "Schema default must satisfy string pattern constraint.",
		                    path));
		        }
	        if (stringKind(kind) && !stringValueMatchesFormat(value, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
	                    "Schema default must satisfy string format constraint.",
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

    @SuppressWarnings("unchecked")
    private static void validateObjectPatternProperties(Map<String, Object> schema,
                                                        String kind,
                                                        String path,
                                                        List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey("patternProperties")) {
            return;
        }
        if (!objectKind(kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.patternPropertiesConstraintTypeMismatch",
                    "Object patternProperties constraints require schema type/kind object.",
                    path));
        }
        Map<String, Object> patternProperties = patternPropertiesOf(schema);
        if (patternProperties == null) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.patternPropertiesInvalid",
                    "Object schema patternProperties must be an object whose values are schemas.",
                    path + "/patternProperties"));
            return;
        }
        for (Map.Entry<String, Object> entry : patternProperties.entrySet()) {
            String pattern = entry.getKey();
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException ex) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.patternPropertiesPatternInvalid",
                        "Object patternProperties key '%s' must be a valid regular expression.".formatted(pattern),
                        path + "/patternProperties/" + pattern));
            }
            if (!(entry.getValue() instanceof Map<?, ?> nested)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.patternPropertiesInvalid",
                        "Object patternProperties entry '%s' must be a schema object.".formatted(pattern),
                        path + "/patternProperties/" + pattern));
                continue;
            }
            validateSchema((Map<String, Object>) nested, path + "/patternProperties/" + pattern, diagnostics);
        }
    }

    private static void validateObjectPropertyNames(Map<String, Object> schema,
                                                    String kind,
                                                    String path,
                                                    List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey("propertyNames")) {
            return;
        }
        if (!objectKind(kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.propertyNamesConstraintTypeMismatch",
                    "Object propertyNames constraints require schema type/kind object.",
                    path));
        }
        Map<String, Object> propertyNameSchema = propertyNameSchema(schema);
        if (propertyNameSchema == null) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.propertyNamesConstraintInvalid",
                    "Object propertyNames constraint must be a schema object.",
                    path + "/propertyNames"));
            return;
        }
        String propertyNameKind = schemaKind(propertyNameSchema);
        if (!propertyNameKind.isBlank() && !stringKind(propertyNameKind)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.propertyNamesConstraintTypeMismatch",
                    "Object propertyNames constraint must use a string-compatible schema.",
                    path + "/propertyNames"));
        }
        validateSchema(effectivePropertyNameSchema(propertyNameSchema), path + "/propertyNames", diagnostics);
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
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            String valuePath = path + "/enum/" + i;
            if ((valueConstrainedKind(kind) || arrayKind(kind) || objectKind(kind))
                    && !constValueMatchesKind(value, kind)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.enumTypeMismatch",
                        "Enum value at index %d must match schema type '%s'.".formatted(i, kind),
                        valuePath));
            }
            if (numericKind(kind) && enumValueMatchesKind(value, kind)
                    && !numericValueMatchesBounds(value, schema)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.enumConstraintMismatch",
                        "Enum value at index %d must satisfy numeric bounds.".formatted(i),
                        valuePath));
            }
            if (numericKind(kind) && enumValueMatchesKind(value, kind)
                    && !numericValueMatchesMultipleOf(value, schema)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.enumConstraintMismatch",
                        "Enum value at index %d must satisfy numeric multipleOf constraint.".formatted(i),
                        valuePath));
            }
            if (stringKind(kind) && enumValueMatchesKind(value, kind)
                    && !stringValueMatchesLengthBounds(value, schema)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.enumConstraintMismatch",
                        "Enum value at index %d must satisfy string length constraints.".formatted(i),
                        valuePath));
            }
            if (stringKind(kind) && enumValueMatchesKind(value, kind)
                    && !stringValueMatchesPattern(value, schema)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.enumConstraintMismatch",
                        "Enum value at index %d must satisfy string pattern constraint.".formatted(i),
                        valuePath));
            }
            if (stringKind(kind) && enumValueMatchesKind(value, kind)
                    && !stringValueMatchesFormat(value, schema)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.enumConstraintMismatch",
                        "Enum value at index %d must satisfy string format constraint.".formatted(i),
                        valuePath));
            }
            if (arrayKind(kind) && value instanceof List<?> && !arrayValueMatchesSchema(value, schema)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.enumConstraintMismatch",
                        "Enum value at index %d must satisfy array schema constraints.".formatted(i),
                        valuePath));
            }
            if (objectKind(kind) && value instanceof Map<?, ?> && !objectValueMatchesSchema(value, schema)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.enumConstraintMismatch",
                        "Enum value at index %d must satisfy object schema constraints.".formatted(i),
                        valuePath));
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
	        if (numericKind(kind) && constValueMatchesKind(constValue, kind)
	                && !numericValueMatchesMultipleOf(constValue, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.constConstraintMismatch",
	                    "Const value must satisfy numeric multipleOf constraint.",
	                    path + "/const"));
	        }
	        if (stringKind(kind) && constValueMatchesKind(constValue, kind)
	                && !stringValueMatchesLengthBounds(constValue, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.constConstraintMismatch",
	                    "Const value must satisfy string length constraints.",
	                    path + "/const"));
	        }
		        if (stringKind(kind) && constValueMatchesKind(constValue, kind)
		                && !stringValueMatchesPattern(constValue, schema)) {
		            diagnostics.add(VisualDiagnostic.error("visual.schema.constConstraintMismatch",
		                    "Const value must satisfy string pattern constraint.",
		                    path + "/const"));
		        }
	        if (stringKind(kind) && constValueMatchesKind(constValue, kind)
	                && !stringValueMatchesFormat(constValue, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.constConstraintMismatch",
	                    "Const value must satisfy string format constraint.",
	                    path + "/const"));
	        }
		        if (arrayKind(kind) && constValueMatchesKind(constValue, kind)
		                && !arrayValueMatchesSchema(constValue, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.constConstraintMismatch",
	                    "Const value must satisfy array schema constraints.",
	                    path + "/const"));
	        }
	        if (objectKind(kind) && constValueMatchesKind(constValue, kind)
	                && !objectValueMatchesSchema(constValue, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.constConstraintMismatch",
	                    "Const value must satisfy object schema constraints.",
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

	    private static void validateNumericMultipleOf(Map<String, Object> schema,
	                                                  String kind,
	                                                  String path,
	                                                  List<VisualDiagnostic> diagnostics) {
	        if (!schema.containsKey("multipleOf")) {
	            return;
	        }
	        if (!numericKind(kind)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.multipleOfConstraintTypeMismatch",
	                    "Numeric multipleOf constraints require schema type/kind integer, number, or decimal.",
	                    path));
	        }
	        if (numericMultipleOf(schema.get("multipleOf")) == null) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.multipleOfConstraintInvalid",
	                    "Numeric multipleOf constraint must be a finite number greater than zero.",
	                    path + "/multipleOf"));
	        }
	    }

	    private static void validateStringLengthBounds(Map<String, Object> schema,
	                                                   String kind,
	                                                   String path,
	                                                   List<VisualDiagnostic> diagnostics) {
	        if (!hasStringLengthBounds(schema)) {
	            return;
	        }
	        boolean validStringKind = stringKind(kind);
	        if (!validStringKind) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.stringLengthConstraintTypeMismatch",
	                    "String length constraints require schema type/kind string, duration, or datetime.",
	                    path));
	        }
	        validateStringLengthBoundary(schema, "minLength", path, diagnostics);
	        validateStringLengthBoundary(schema, "maxLength", path, diagnostics);
	        if (!validStringKind || !stringLengthBoundariesValid(schema)) {
	            return;
	        }
	        Long minimum = stringLengthBoundary(schema.get("minLength"));
	        Long maximum = stringLengthBoundary(schema.get("maxLength"));
	        if (minimum != null && maximum != null && minimum > maximum) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.stringLengthBoundsInvalid",
	                    "String minLength %d is greater than maxLength %d.".formatted(minimum, maximum),
	                    path));
	        }
	    }

	    private static void validateArrayItemBounds(Map<String, Object> schema,
	                                                String kind,
	                                                String path,
	                                                List<VisualDiagnostic> diagnostics) {
	        if (!hasArrayItemBounds(schema)) {
	            return;
	        }
	        boolean validArrayKind = arrayKind(kind);
	        if (!validArrayKind) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.arrayItemConstraintTypeMismatch",
	                    "Array item count constraints require schema type/kind array.",
	                    path));
	        }
	        validateArrayItemBoundary(schema, "minItems", path, diagnostics);
	        validateArrayItemBoundary(schema, "maxItems", path, diagnostics);
	        if (!validArrayKind || !arrayItemBoundariesValid(schema)) {
	            return;
	        }
	        Long minimum = arrayItemBoundary(schema.get("minItems"));
	        Long maximum = arrayItemBoundary(schema.get("maxItems"));
	        if (minimum != null && maximum != null && minimum > maximum) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.arrayItemBoundsInvalid",
	                    "Array minItems %d is greater than maxItems %d.".formatted(minimum, maximum),
	                    path));
	        }
	    }

		    private static void validateArrayUniqueItems(Map<String, Object> schema,
		                                                 String kind,
		                                                 String path,
		                                                 List<VisualDiagnostic> diagnostics) {
	        if (!schema.containsKey("uniqueItems")) {
	            return;
	        }
	        if (!arrayKind(kind)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.uniqueItemsConstraintTypeMismatch",
	                    "Array uniqueItems constraints require schema type/kind array.",
	                    path));
	        }
	        if (!(schema.get("uniqueItems") instanceof Boolean)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.uniqueItemsConstraintInvalid",
	                    "Array uniqueItems constraint must be a boolean.",
	                    path + "/uniqueItems"));
		        }
		    }

	    private static void validateObjectPropertyBounds(Map<String, Object> schema,
	                                                     String kind,
	                                                     String path,
	                                                     List<VisualDiagnostic> diagnostics) {
	        if (!hasObjectPropertyBounds(schema)) {
	            return;
	        }
	        boolean validObjectKind = objectKind(kind);
	        if (!validObjectKind) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.objectPropertyConstraintTypeMismatch",
	                    "Object property count constraints require schema type/kind object.",
	                    path));
	        }
	        validateObjectPropertyBoundary(schema, "minProperties", path, diagnostics);
	        validateObjectPropertyBoundary(schema, "maxProperties", path, diagnostics);
	        if (!validObjectKind || !objectPropertyBoundariesValid(schema)) {
	            return;
	        }
	        Long minimum = objectPropertyBoundary(schema.get("minProperties"));
	        Long maximum = objectPropertyBoundary(schema.get("maxProperties"));
	        if (minimum != null && maximum != null && minimum > maximum) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.objectPropertyBoundsInvalid",
	                    "Object minProperties %d is greater than maxProperties %d.".formatted(minimum, maximum),
	                    path));
	        }
	    }

		    private static void validateStringPattern(Map<String, Object> schema,
		                                              String kind,
		                                              String path,
		                                              List<VisualDiagnostic> diagnostics) {
	        if (!hasStringPattern(schema)) {
	            return;
	        }
	        if (!stringKind(kind)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.patternConstraintTypeMismatch",
	                    "String pattern constraints require schema type/kind string, duration, or datetime.",
	                    path));
	        }
	        Object rawPattern = schema.get("pattern");
	        if (!(rawPattern instanceof String pattern)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.patternConstraintInvalid",
	                    "String pattern constraint must be a string.",
	                    path + "/pattern"));
	            return;
	        }
	        try {
	            Pattern.compile(pattern);
	        } catch (PatternSyntaxException ex) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.patternConstraintInvalid",
	                    "String pattern constraint must be a valid regular expression.",
	                    path + "/pattern"));
		        }
		    }

	    private static void validateStringFormat(Map<String, Object> schema,
	                                             String kind,
	                                             String path,
	                                             List<VisualDiagnostic> diagnostics) {
	        if (!schema.containsKey("format")) {
	            return;
	        }
	        if (!stringKind(kind)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.formatConstraintTypeMismatch",
	                    "String format constraints require schema type/kind string, duration, or datetime.",
	                    path));
	        }
	        Object rawFormat = schema.get("format");
	        if (!(rawFormat instanceof String format) || !SUPPORTED_STRING_FORMATS.contains(format)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.formatConstraintInvalid",
	                    "String format constraint must be one of %s.".formatted(SUPPORTED_STRING_FORMATS),
	                    path + "/format"));
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

	    private static boolean hasStringLengthBounds(Map<String, Object> schema) {
	        return schema.containsKey("minLength")
	                || schema.containsKey("maxLength");
	    }

		    private static boolean hasStringPattern(Map<String, Object> schema) {
		        return schema.containsKey("pattern");
		    }

		    private static boolean hasArrayItemBounds(Map<String, Object> schema) {
		        return schema.containsKey("minItems")
		                || schema.containsKey("maxItems");
		    }

	    private static boolean hasObjectPropertyBounds(Map<String, Object> schema) {
	        return schema.containsKey("minProperties")
	                || schema.containsKey("maxProperties");
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

	    private static boolean numericValueMatchesMultipleOf(Object value, Map<String, Object> schema) {
	        if (!(value instanceof Number number)) {
	            return true;
	        }
	        Double multipleOf = numericMultipleOf(schema.get("multipleOf"));
	        return multipleOf == null || numericValueIsMultipleOf(number.doubleValue(), multipleOf);
	    }

	    private static void validateStringLengthBoundary(Map<String, Object> schema,
	                                                     String keyword,
	                                                     String path,
	                                                     List<VisualDiagnostic> diagnostics) {
	        if (!schema.containsKey(keyword)) {
	            return;
	        }
	        if (stringLengthBoundary(schema.get(keyword)) == null) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.stringLengthConstraintInvalid",
	                    "String length constraint '%s' must be a non-negative integer.".formatted(keyword),
	                    path + "/" + keyword));
	        }
	    }

	    private static boolean stringLengthBoundariesValid(Map<String, Object> schema) {
	        return (!schema.containsKey("minLength") || stringLengthBoundary(schema.get("minLength")) != null)
	                && (!schema.containsKey("maxLength") || stringLengthBoundary(schema.get("maxLength")) != null);
	    }

	    private static boolean stringValueMatchesLengthBounds(Object value, Map<String, Object> schema) {
	        if (!(value instanceof String string)) {
	            return true;
	        }
	        long length = string.codePoints().count();
	        Long minimum = stringLengthBoundary(schema.get("minLength"));
	        if (minimum != null && length < minimum) {
	            return false;
	        }
	        Long maximum = stringLengthBoundary(schema.get("maxLength"));
	        return maximum == null || length <= maximum;
	    }

		    private static boolean stringValueMatchesPattern(Object value, Map<String, Object> schema) {
		        if (!(value instanceof String string)) {
		            return true;
		        }
		        Pattern pattern = compiledStringPattern(schema);
		        return pattern == null || pattern.matcher(string).find();
		    }

	    private static boolean stringValueMatchesFormat(Object value, Map<String, Object> schema) {
	        if (!(value instanceof String string)) {
	            return true;
	        }
	        String format = supportedStringFormat(schema);
	        return format == null || stringMatchesFormat(string, format);
	    }

	    private static String supportedStringFormat(Map<String, Object> schema) {
	        Object rawFormat = schema.get("format");
	        return rawFormat instanceof String format && SUPPORTED_STRING_FORMATS.contains(format) ? format : null;
	    }

	    private static boolean stringMatchesFormat(String value, String format) {
	        try {
	            switch (format) {
	                case "date" -> LocalDate.parse(value);
	                case "date-time" -> OffsetDateTime.parse(value);
	                case "duration" -> Duration.parse(value);
	                case "email" -> {
	                    return EMAIL_PATTERN.matcher(value).matches();
	                }
	                case "uri" -> {
	                    URI uri = new URI(value);
	                    return uri.isAbsolute();
	                }
	                case "uuid" -> UUID.fromString(value);
	                default -> {
	                    return true;
	                }
	            }
	            return true;
	        } catch (DateTimeParseException | IllegalArgumentException | URISyntaxException ex) {
	            return false;
	        }
	    }

	    private static Pattern compiledStringPattern(Map<String, Object> schema) {
	        Object rawPattern = schema.get("pattern");
	        if (!(rawPattern instanceof String pattern)) {
	            return null;
	        }
	        try {
	            return Pattern.compile(pattern);
	        } catch (PatternSyntaxException ex) {
	            return null;
	        }
	    }

	    private static void validateArrayItemBoundary(Map<String, Object> schema,
	                                                  String keyword,
	                                                  String path,
	                                                  List<VisualDiagnostic> diagnostics) {
	        if (!schema.containsKey(keyword)) {
	            return;
	        }
	        if (arrayItemBoundary(schema.get(keyword)) == null) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.arrayItemConstraintInvalid",
	                    "Array item count constraint '%s' must be a non-negative integer.".formatted(keyword),
	                    path + "/" + keyword));
	        }
	    }

	    private static boolean arrayItemBoundariesValid(Map<String, Object> schema) {
	        return (!schema.containsKey("minItems") || arrayItemBoundary(schema.get("minItems")) != null)
	                && (!schema.containsKey("maxItems") || arrayItemBoundary(schema.get("maxItems")) != null);
	    }

	    private static boolean arrayValueMatchesItemBounds(Object value, Map<String, Object> schema) {
	        if (!(value instanceof List<?> list)) {
	            return true;
	        }
	        long size = list.size();
	        Long minimum = arrayItemBoundary(schema.get("minItems"));
	        if (minimum != null && size < minimum) {
	            return false;
	        }
	        Long maximum = arrayItemBoundary(schema.get("maxItems"));
	        return maximum == null || size <= maximum;
	    }

	    private static boolean arrayValueMatchesUniqueItems(Object value, Map<String, Object> schema) {
	        if (!(value instanceof List<?> list) || !Boolean.TRUE.equals(schema.get("uniqueItems"))) {
	            return true;
	        }
	        return new LinkedHashSet<>(list).size() == list.size();
	    }

	    @SuppressWarnings("unchecked")
	    private static boolean arrayValueMatchesSchema(Object value, Map<String, Object> schema) {
	        if (!(value instanceof List<?> list) || !arrayKind(schemaKind(schema))) {
	            return true;
	        }
	        if (!arrayValueMatchesItemBounds(value, schema) || !arrayValueMatchesUniqueItems(value, schema)) {
	            return false;
	        }
	        Object items = schema.get("items");
	        if (!(items instanceof Map<?, ?> itemSchema)) {
	            return true;
	        }
	        Map<String, Object> itemSchemaMap = (Map<String, Object>) itemSchema;
	        return list.stream().allMatch(item -> valueMatchesSchema(item, itemSchemaMap));
	    }

	    private static void validateObjectPropertyBoundary(Map<String, Object> schema,
	                                                       String keyword,
	                                                       String path,
	                                                       List<VisualDiagnostic> diagnostics) {
	        if (!schema.containsKey(keyword)) {
	            return;
	        }
	        if (objectPropertyBoundary(schema.get(keyword)) == null) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.objectPropertyConstraintInvalid",
	                    "Object property count constraint '%s' must be a non-negative integer.".formatted(keyword),
	                    path + "/" + keyword));
	        }
	    }

	    private static boolean objectPropertyBoundariesValid(Map<String, Object> schema) {
	        return (!schema.containsKey("minProperties") || objectPropertyBoundary(schema.get("minProperties")) != null)
	                && (!schema.containsKey("maxProperties")
	                || objectPropertyBoundary(schema.get("maxProperties")) != null);
	    }

	    private static boolean objectValueMatchesPropertyBounds(Object value, Map<String, Object> schema) {
	        if (!(value instanceof Map<?, ?> map)) {
	            return true;
	        }
	        long size = map.size();
	        Long minimum = objectPropertyBoundary(schema.get("minProperties"));
	        if (minimum != null && size < minimum) {
	            return false;
	        }
	        Long maximum = objectPropertyBoundary(schema.get("maxProperties"));
	        return maximum == null || size <= maximum;
	    }

	    private static boolean objectValueMatchesPropertyNames(Map<String, Object> value, Map<String, Object> schema) {
	        Map<String, Object> propertyNameSchema = propertyNameSchema(schema);
	        if (propertyNameSchema == null) {
	            return true;
	        }
	        Map<String, Object> effectiveSchema = effectivePropertyNameSchema(propertyNameSchema);
	        return value.keySet().stream().allMatch(name -> valueMatchesSchema(name, effectiveSchema));
	    }

	    private static boolean objectValueMatchesPatternProperties(Map<String, Object> value,
	                                                               Map<String, Object> schema) {
	        for (Map.Entry<String, Object> entry : value.entrySet()) {
	            for (Map<String, Object> patternSchema : matchingPatternPropertySchemas(schema, entry.getKey())) {
	                if (!valueMatchesSchema(entry.getValue(), patternSchema)) {
	                    return false;
	                }
	            }
	        }
	        return true;
	    }

	    private static List<Map<String, Object>> matchingPatternPropertySchemas(Map<String, Object> schema,
	                                                                            String propertyName) {
	        Map<String, Object> patternProperties = patternPropertiesOf(schema);
	        if (patternProperties == null || patternProperties.isEmpty()) {
	            return List.of();
	        }
	        List<Map<String, Object>> matches = new ArrayList<>();
	        for (Map.Entry<String, Object> entry : patternProperties.entrySet()) {
	            if (patternMatches(entry.getKey(), propertyName) && entry.getValue() instanceof Map<?, ?> nested) {
	                Map<String, Object> copy = new LinkedHashMap<>();
	                nested.forEach((key, item) -> copy.put(String.valueOf(key), item));
	                matches.add(copy);
	            }
	        }
	        return matches;
	    }

	    private static Map<String, Object> patternPropertiesOf(Map<String, Object> schema) {
	        Object raw = schema.get("patternProperties");
	        if (!(raw instanceof Map<?, ?> rawMap)) {
	            return null;
	        }
	        Map<String, Object> patternProperties = new LinkedHashMap<>();
	        rawMap.forEach((key, item) -> patternProperties.put(String.valueOf(key), item));
	        return patternProperties;
	    }

	    private static boolean patternMatches(String pattern, String value) {
	        try {
	            return Pattern.compile(pattern).matcher(value).find();
	        } catch (PatternSyntaxException ex) {
	            return false;
	        }
	    }

	    private static Map<String, Object> propertyNameSchema(Map<String, Object> schema) {
	        Object raw = schema.get("propertyNames");
	        if (!(raw instanceof Map<?, ?> rawMap)) {
	            return null;
	        }
	        Map<String, Object> propertyNameSchema = new LinkedHashMap<>();
	        rawMap.forEach((key, item) -> propertyNameSchema.put(String.valueOf(key), item));
	        return propertyNameSchema;
	    }

	    private static Map<String, Object> effectivePropertyNameSchema(Map<String, Object> propertyNameSchema) {
	        Map<String, Object> effective = new LinkedHashMap<>(propertyNameSchema);
	        if (schemaKind(effective).isBlank()) {
	            effective.put("type", "string");
	        }
	        return effective;
	    }

	    @SuppressWarnings("unchecked")
	    private static boolean objectValueMatchesSchema(Object value, Map<String, Object> schema) {
	        if (!(value instanceof Map<?, ?> rawMap) || !objectKind(schemaKind(schema))) {
	            return true;
	        }
	        Map<String, Object> object = new LinkedHashMap<>();
	        rawMap.forEach((key, item) -> object.put(String.valueOf(key), item));
	        if (!objectValueMatchesPropertyBounds(object, schema)) {
	            return false;
	        }
	        if (!objectValueMatchesPropertyNames(object, schema)) {
	            return false;
	        }
	        if (!objectValueMatchesPatternProperties(object, schema)) {
	            return false;
	        }
	        for (String required : requiredNamesWithoutDiagnostics(schema)) {
	            if (!object.containsKey(required) || object.get(required) == null) {
	                return false;
	            }
	        }
	        Map<String, Object> properties = propertiesWithoutDiagnostics(schema);
	        Object additional = schema.get("additionalProperties");
	        for (Map.Entry<String, Object> entry : object.entrySet()) {
	            Object property = properties.get(entry.getKey());
	            List<Map<String, Object>> patternSchemas = matchingPatternPropertySchemas(schema, entry.getKey());
	            if (property instanceof Map<?, ?> propertySchema) {
	                if (!valueMatchesSchema(entry.getValue(), (Map<String, Object>) propertySchema)) {
	                    return false;
	                }
	            }
	            for (Map<String, Object> patternSchema : patternSchemas) {
	                if (!valueMatchesSchema(entry.getValue(), patternSchema)) {
	                    return false;
	                }
	            }
	            if (property instanceof Map<?, ?> || !patternSchemas.isEmpty()) {
	                continue;
	            } else if (Boolean.FALSE.equals(additional)) {
	                return false;
	            } else if (additional instanceof Map<?, ?> additionalSchema
	                    && !valueMatchesSchema(entry.getValue(), (Map<String, Object>) additionalSchema)) {
	                return false;
	            }
	        }
	        return true;
	    }

	    private static boolean valueMatchesSchema(Object value, Map<String, Object> schema) {
	        String kind = schemaKind(schema);
	        if (!constValueMatchesKind(value, kind)) {
	            return false;
	        }
	        Object rawEnum = schema.get("enum");
	        if (rawEnum instanceof List<?> values && !values.contains(value)) {
	            return false;
	        }
	        if ("enum".equals(kind)) {
	            Object rawValues = schema.get("values");
	            if (rawValues instanceof List<?> values && !values.contains(value)) {
	                return false;
	            }
	        }
	        return numericValueMatchesBounds(value, schema)
	                && numericValueMatchesMultipleOf(value, schema)
	                && stringValueMatchesLengthBounds(value, schema)
	                && stringValueMatchesPattern(value, schema)
	                && stringValueMatchesFormat(value, schema)
	                && arrayValueMatchesSchema(value, schema)
	                && objectValueMatchesSchema(value, schema);
	    }

	    private static Long stringLengthBoundary(Object value) {
	        if (!(value instanceof Number number)) {
	            return null;
	        }
	        double numericValue = number.doubleValue();
	        if (!Double.isFinite(numericValue) || Math.rint(numericValue) != numericValue || numericValue < 0) {
	            return null;
	        }
	        return (long) numericValue;
	    }

		    private static Long arrayItemBoundary(Object value) {
		        if (!(value instanceof Number number)) {
		            return null;
		        }
		        double numericValue = number.doubleValue();
		        if (!Double.isFinite(numericValue) || Math.rint(numericValue) != numericValue || numericValue < 0) {
		            return null;
		        }
		        return (long) numericValue;
		    }

	    private static Long objectPropertyBoundary(Object value) {
	        if (!(value instanceof Number number)) {
	            return null;
	        }
	        double numericValue = number.doubleValue();
	        if (!Double.isFinite(numericValue) || Math.rint(numericValue) != numericValue || numericValue < 0) {
	            return null;
	        }
	        return (long) numericValue;
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

	    private static Double numericMultipleOf(Object value) {
	        if (!(value instanceof Number number)) {
	            return null;
	        }
	        double numericValue = number.doubleValue();
	        return Double.isFinite(numericValue) && numericValue > 0 ? numericValue : null;
	    }

	    private static boolean numericValueIsMultipleOf(double value, double multipleOf) {
	        if (!Double.isFinite(value) || !Double.isFinite(multipleOf) || multipleOf <= 0) {
	            return true;
	        }
	        double quotient = value / multipleOf;
	        double nearest = Math.rint(quotient);
	        double tolerance = 1.0e-9 * Math.max(1.0, Math.abs(quotient));
	        return Math.abs(quotient - nearest) <= tolerance;
	    }

    private static boolean boundsContradict(NumericBoundary lower, NumericBoundary upper) {
        int comparison = Double.compare(lower.value(), upper.value());
        return comparison > 0 || comparison == 0 && (lower.exclusive() || upper.exclusive());
    }

	    private static boolean numericKind(String kind) {
	        return "integer".equals(kind) || "number".equals(kind) || "decimal".equals(kind);
	    }

	    private static boolean stringKind(String kind) {
	        return "string".equals(kind) || "duration".equals(kind) || "datetime".equals(kind);
	    }

		    private static boolean arrayKind(String kind) {
		        return "array".equals(kind);
		    }

	    private static boolean objectKind(String kind) {
	        return "object".equals(kind);
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
