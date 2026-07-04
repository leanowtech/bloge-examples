package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.math.BigDecimal;
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
import java.util.Optional;
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
    private static final Set<String> SUPPORTED_UNION_KEYWORDS = Set.of("oneOf", "anyOf");
    private static final List<String> SUPPORTED_CONDITIONAL_KEYWORDS = List.of("if", "then", "else");
    private static final Set<String> UNSUPPORTED_CONSTRAINT_KEYWORDS = Set.of();
    private static final Set<String> SUPPORTED_STRING_FORMATS = Set.of(
            "date",
            "date-time",
            "duration",
            "email",
            "uri",
            "uuid"
    );
    private static final String LOCAL_DEFS_REF_PREFIX = "#/$defs/";
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

    /**
     * Validates a runtime value against the supported visual schema subset.
     *
     * @param envelope schema envelope
     * @param value runtime value
     * @param path JSON pointer to the runtime value
     * @return diagnostics describing value/schema mismatches
     */
    public static List<VisualDiagnostic> validateValue(SchemaEnvelope envelope, Object value, String path) {
        SchemaEnvelope effective = envelope == null ? SchemaEnvelope.opaque() : envelope;
        if (valueMatchesSchema(value, effective.schema())) {
            return List.of();
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        collectValueDiagnostics(value, effective.schema(), path, diagnostics);
        if (diagnostics.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.context.schemaMismatch",
                    "Runtime context does not satisfy graph inputSchema.",
                    path));
        }
        return diagnostics;
    }

    @SuppressWarnings("unchecked")
    private static void collectValueDiagnostics(Object value,
                                                Map<String, Object> schema,
                                                String path,
                                                List<VisualDiagnostic> diagnostics) {
        if (valueMatchesSchema(value, schema)) {
            return;
        }
        Optional<String> allOfMismatch = allOfMismatch(value, schema);
        if (allOfMismatch.isPresent()) {
            diagnostics.add(VisualDiagnostic.error("visual.context.allOfMismatch",
                    "Runtime value at '%s' does not satisfy schema allOf: %s."
                            .formatted(path, allOfMismatch.get()),
                    path));
            return;
        }
        Optional<String> unionMismatch = unionMismatch(value, schema);
        if (unionMismatch.isPresent()) {
            diagnostics.add(VisualDiagnostic.error(unionMismatch.get().startsWith("oneOf")
                            ? "visual.context.oneOfMismatch"
                            : "visual.context.anyOfMismatch",
                    "Runtime value at '%s' does not satisfy schema union: %s."
                            .formatted(path, unionMismatch.get()),
                    path));
            return;
        }
        Optional<String> conditionalMismatch = conditionalMismatch(value, schema);
        if (conditionalMismatch.isPresent()) {
            diagnostics.add(VisualDiagnostic.error("visual.context.conditionalMismatch",
                    "Runtime value at '%s' does not satisfy schema conditional: %s."
                            .formatted(path, conditionalMismatch.get()),
                    path));
            return;
        }
        String kind = schemaKind(schema);
        if (kind.isBlank() || "any".equals(kind) || "opaque".equals(kind)) {
            return;
        }
        if (!valueMatchesDeclaredType(value, schema, kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.typeMismatch",
                    "Runtime value at '%s' is %s but graph inputSchema requires %s."
                            .formatted(path, schemaKindForValue(value), kind),
                    path));
            return;
        }
        if (schema.containsKey("const") && !schemaValuesEqual(schema.get("const"), value)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.constMismatch",
                    "Runtime value at '%s' must equal const value '%s'."
                            .formatted(path, schema.get("const")),
                    path));
            return;
        }
        Object rawEnum = "enum".equals(kind) ? schema.get("values") : schema.get("enum");
        if (rawEnum instanceof List<?> values && !schemaValuesContain(values, value)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.enumMismatch",
                    "Runtime value at '%s' must be one of %s.".formatted(path, values),
                    path));
            return;
        }
        if (valueMatchesNotConstraint(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.notMismatch",
                    "Runtime value at '%s' must not match excluded schema %s."
                            .formatted(path, notSchemaLabel(objectProperty(schema.get("not")))),
                    path));
            return;
        }
        if (numericKind(kind)) {
            collectNumericValueDiagnostics(value, schema, path, diagnostics);
            return;
        }
        if (stringKind(kind)) {
            collectStringValueDiagnostics(value, schema, path, diagnostics);
            return;
        }
        if ("array".equals(kind) && value instanceof List<?> list) {
            collectArrayValueDiagnostics(list, schema, path, diagnostics);
            return;
        }
        if ("object".equals(kind) && value instanceof Map<?, ?> rawMap) {
            Map<String, Object> object = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> object.put(String.valueOf(key), item));
            collectObjectValueDiagnostics(object, schema, path, diagnostics);
        }
    }

    private static void collectNumericValueDiagnostics(Object value,
                                                       Map<String, Object> schema,
                                                       String path,
                                                       List<VisualDiagnostic> diagnostics) {
        if (!numericValueMatchesBounds(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.numericConstraintMismatch",
                    "Runtime numeric value at '%s' does not satisfy graph inputSchema bounds.".formatted(path),
                    path));
            return;
        }
        if (!numericValueMatchesMultipleOf(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.numericConstraintMismatch",
                    "Runtime numeric value at '%s' does not satisfy graph inputSchema multipleOf constraint."
                            .formatted(path),
                    path));
        }
    }

    private static void collectStringValueDiagnostics(Object value,
                                                      Map<String, Object> schema,
                                                      String path,
                                                      List<VisualDiagnostic> diagnostics) {
        if (!stringValueMatchesLengthBounds(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.stringConstraintMismatch",
                    "Runtime string value at '%s' does not satisfy graph inputSchema length constraints."
                            .formatted(path),
                    path));
            return;
        }
        if (!stringValueMatchesPattern(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.stringConstraintMismatch",
                    "Runtime string value at '%s' does not satisfy graph inputSchema pattern constraint."
                            .formatted(path),
                    path));
            return;
        }
        if (!stringValueMatchesFormat(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.stringConstraintMismatch",
                    "Runtime string value at '%s' does not satisfy graph inputSchema format constraint."
                            .formatted(path),
                    path));
        }
    }

    private static void collectArrayValueDiagnostics(List<?> value,
                                                     Map<String, Object> schema,
                                                     String path,
                                                     List<VisualDiagnostic> diagnostics) {
        if (!arrayValueMatchesItemBounds(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.arrayConstraintMismatch",
                    "Runtime array at '%s' does not satisfy graph inputSchema item count constraints."
                            .formatted(path),
                    path));
            return;
        }
        if (!arrayValueMatchesUniqueItems(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.arrayConstraintMismatch",
                    "Runtime array at '%s' does not satisfy graph inputSchema uniqueItems constraint."
                            .formatted(path),
                    path));
            return;
        }
        if (!arrayValueMatchesContains(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.arrayConstraintMismatch",
                    "Runtime array at '%s' does not satisfy graph inputSchema contains constraint."
                            .formatted(path),
                    path));
            return;
        }
        if (!arrayValueMatchesItemsPolicy(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.arrayConstraintMismatch",
                    "Runtime array at '%s' does not satisfy graph inputSchema items constraint."
                            .formatted(path),
                    path));
            return;
        }
        if (!arrayValueMatchesUnevaluatedItems(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.arrayConstraintMismatch",
                    "Runtime array at '%s' does not satisfy graph inputSchema unevaluatedItems constraint."
                            .formatted(path),
                    path));
            return;
        }
        for (int i = 0; i < value.size(); i++) {
            Map<String, Object> itemSchemaForIndex = arrayItemSchemaForIndex(schema, i);
            if (itemSchemaForIndex != null) {
                collectValueDiagnostics(value.get(i), itemSchemaForIndex, path + "/" + i, diagnostics);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectObjectValueDiagnostics(Map<String, Object> value,
                                                      Map<String, Object> schema,
                                                      String path,
                                                      List<VisualDiagnostic> diagnostics) {
        if (!objectValueMatchesPropertyBounds(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.objectConstraintMismatch",
                    "Runtime object at '%s' does not satisfy graph inputSchema property count constraints."
                            .formatted(path),
                    path));
            return;
        }
        if (!objectValueMatchesPropertyNames(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.objectConstraintMismatch",
                    "Runtime object at '%s' contains property names rejected by graph inputSchema."
                            .formatted(path),
                    path));
        }
        if (!objectValueMatchesDependentRequired(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.objectConstraintMismatch",
                    "Runtime object at '%s' does not satisfy graph inputSchema dependentRequired constraints."
                            .formatted(path),
                    path));
        }
        if (!objectValueMatchesDependentSchemas(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.context.objectConstraintMismatch",
                    "Runtime object at '%s' does not satisfy graph inputSchema dependentSchemas constraints."
                            .formatted(path),
                    path));
        }
        for (String required : requiredNamesWithoutDiagnostics(schema)) {
            if (!value.containsKey(required) || value.get(required) == null) {
                diagnostics.add(VisualDiagnostic.error("visual.context.requiredMissing",
                        "Runtime object at '%s' is missing required graph inputSchema property '%s'."
                                .formatted(path, required),
                        path + "/" + pointerSegment(required)));
            }
        }
        Map<String, Object> properties = propertiesWithoutDiagnostics(schema);
        Object residual = residualPropertiesPolicy(schema);
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            Object property = properties.get(entry.getKey());
            List<Map<String, Object>> patternSchemas = matchingPatternPropertySchemas(schema, entry.getKey());
            String propertyPath = path + "/" + pointerSegment(entry.getKey());
            if (property instanceof Map<?, ?> propertySchema) {
                collectValueDiagnostics(entry.getValue(), (Map<String, Object>) propertySchema,
                        propertyPath, diagnostics);
            }
            for (Map<String, Object> patternSchema : patternSchemas) {
                collectValueDiagnostics(entry.getValue(), patternSchema, propertyPath, diagnostics);
            }
            if (property instanceof Map<?, ?> || !patternSchemas.isEmpty()) {
                continue;
            }
            if (Boolean.FALSE.equals(residual)) {
                diagnostics.add(VisualDiagnostic.error("visual.context.unknownProperty",
                        "Runtime object at '%s' contains undeclared property '%s'."
                                .formatted(path, entry.getKey()),
                        propertyPath));
            } else if (residual instanceof Map<?, ?> residualSchema) {
                collectValueDiagnostics(entry.getValue(), (Map<String, Object>) residualSchema,
                        propertyPath, diagnostics);
            }
        }
    }

    private static String pointerSegment(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    @SuppressWarnings("unchecked")
    private static void validateSchema(Map<String, Object> schema,
                                       String path,
                                       List<VisualDiagnostic> diagnostics) {
        validateSchema(schema, path, diagnostics, true);
    }

    @SuppressWarnings("unchecked")
    private static void validateSchema(Map<String, Object> schema,
                                       String path,
                                       List<VisualDiagnostic> diagnostics,
                                       boolean warnOpaque) {
        boolean hasUnsupportedKeyword = validateUnsupportedKeywords(schema, path, diagnostics);
        boolean hasSupportedUnion = validateSupportedUnions(schema, path, diagnostics);
        boolean hasSupportedAllOf = validateSupportedAllOf(schema, path, diagnostics);
        boolean hasSupportedConditional = validateSupportedConditionals(schema, path, diagnostics);
        boolean invalidTypeArray = validateTypeArray(schema, path, diagnostics);
        validateDefinitions(schema, path, diagnostics);
        validateNotConstraint(schema, path, diagnostics);
        String kind = schemaKind(schema);
        if (invalidTypeArray) {
            return;
        }
        if (kind.isBlank()) {
            if (warnOpaque && !hasUnsupportedKeyword && !hasSupportedUnion && !hasSupportedAllOf
                    && !hasSupportedConditional && !schema.containsKey("not")) {
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
	        validateArrayPrefixItems(schema, kind, path, diagnostics);
	        validateArrayContains(schema, kind, path, diagnostics);
	        validateArrayUnevaluatedItems(schema, kind, path, diagnostics);
	        validateObjectPropertyBounds(schema, kind, path, diagnostics);
	        validateObjectPatternProperties(schema, kind, path, diagnostics);
	        validateObjectPropertyNames(schema, kind, path, diagnostics);
	        validateObjectDependentRequired(schema, kind, path, diagnostics);
	        validateObjectDependentSchemas(schema, kind, path, diagnostics);
	        validateUnevaluatedProperties(schema, kind, path, diagnostics);
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
            if (items instanceof Map<?, ?> nestedItems) {
                validateSchema((Map<String, Object>) nestedItems, path + "/items", diagnostics);
            } else if (items instanceof Boolean) {
                // Boolean schemas are supported only for array residual item policy.
            } else if (schema.containsKey("items")) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.arrayItemsMissing",
                        "Array schema items must be a schema object or boolean.",
                        path + "/items"));
            } else if (!hasSupportedArrayItemContract(schema)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.arrayItemsMissing",
                        "Array schema must declare items, prefixItems, or unevaluatedItems.",
                        path + "/items"));
            }
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
                diagnostics.add(referenceDiagnostic(keyword, schema.get(keyword), path + "/" + keyword));
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
    private static boolean validateSupportedAllOf(Map<String, Object> schema,
                                                  String path,
                                                  List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey("allOf")) {
            return false;
        }
        Object raw = schema.get("allOf");
        if (!(raw instanceof List<?> branches) || branches.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.allOfInvalid",
                    "JSON Schema composition keyword 'allOf' must be a non-empty array of schema objects.",
                    path + "/allOf"));
            return true;
        }
        for (int i = 0; i < branches.size(); i++) {
            Object branch = branches.get(i);
            String branchPath = path + "/allOf/" + i;
            if (!(branch instanceof Map<?, ?> branchSchema)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.allOfInvalid",
                        "JSON Schema composition keyword 'allOf' branch %d must be a schema object."
                                .formatted(i),
                        branchPath));
                continue;
            }
            validateSchema((Map<String, Object>) branchSchema, branchPath, diagnostics);
        }
        return true;
    }

    private static VisualDiagnostic referenceDiagnostic(String keyword, Object rawRef, String target) {
        if (!"$ref".equals(keyword)) {
            return VisualDiagnostic.error("visual.schema.refUnsupported",
                    "Schema reference keyword '%s' is not supported by visual authoring schemas."
                            .formatted(keyword),
                    target);
        }
        if (!(rawRef instanceof String ref) || ref.isBlank()) {
            return VisualDiagnostic.error("visual.schema.refUnsupported",
                    "Schema $ref must be a non-blank string and must be expanded before validation.",
                    target);
        }
        if (ref.startsWith(LOCAL_DEFS_REF_PREFIX)) {
            return VisualDiagnostic.error("visual.schema.refUnresolved",
                    "Schema local reference '%s' could not be resolved or safely expanded from $defs."
                            .formatted(ref),
                    target);
        }
        if (ref.contains("://") || ref.startsWith("urn:") || ref.startsWith("file:")) {
            return VisualDiagnostic.error("visual.schema.refRemoteUnsupported",
                    "Schema remote reference '%s' is not supported by visual authoring schemas; inline it under $defs before import."
                            .formatted(ref),
                    target);
        }
        return VisualDiagnostic.error("visual.schema.refUnsupported",
                "Schema reference '%s' is not supported by visual authoring schemas; supported local $defs references must be expanded before validation."
                        .formatted(ref),
                target);
    }

    @SuppressWarnings("unchecked")
    private static boolean validateSupportedUnions(Map<String, Object> schema,
                                                   String path,
                                                   List<VisualDiagnostic> diagnostics) {
        boolean present = false;
        boolean hasOneOf = schema.containsKey("oneOf");
        boolean hasAnyOf = schema.containsKey("anyOf");
        if (hasOneOf && hasAnyOf) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.unionAmbiguous",
                    "Schema cannot declare both oneOf and anyOf in the same schema object.",
                    path));
        }
        for (String keyword : SUPPORTED_UNION_KEYWORDS) {
            if (!schema.containsKey(keyword)) {
                continue;
            }
            present = true;
            Object raw = schema.get(keyword);
            if (!(raw instanceof List<?> branches) || branches.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.unionInvalid",
                        "JSON Schema union keyword '%s' must be a non-empty array of schema objects."
                                .formatted(keyword),
                        path + "/" + keyword));
                continue;
            }
            for (int i = 0; i < branches.size(); i++) {
                Object branch = branches.get(i);
                String branchPath = path + "/" + keyword + "/" + i;
                if (!(branch instanceof Map<?, ?> branchSchema)) {
                    diagnostics.add(VisualDiagnostic.error("visual.schema.unionInvalid",
                            "JSON Schema union keyword '%s' branch %d must be a schema object."
                                    .formatted(keyword, i),
                            branchPath));
                    continue;
                }
                validateSchema((Map<String, Object>) branchSchema, branchPath, diagnostics);
            }
        }
        return present;
    }

    @SuppressWarnings("unchecked")
    private static boolean validateSupportedConditionals(Map<String, Object> schema,
                                                         String path,
                                                         List<VisualDiagnostic> diagnostics) {
        boolean present = false;
        for (String keyword : SUPPORTED_CONDITIONAL_KEYWORDS) {
            if (!schema.containsKey(keyword)) {
                continue;
            }
            present = true;
            Object raw = schema.get(keyword);
            String branchPath = path + "/" + keyword;
            if (!(raw instanceof Map<?, ?> branchSchema)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.conditionalInvalid",
                        "JSON Schema conditional keyword '%s' must be a schema object."
                                .formatted(keyword),
                        branchPath));
                continue;
            }
            validateSchema(effectiveConditionalValidationSchema((Map<String, Object>) branchSchema, schema),
                    branchPath, diagnostics, false);
        }
        return present;
    }

    @SuppressWarnings("unchecked")
    private static void validateDefinitions(Map<String, Object> schema,
                                            String path,
                                            List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey("$defs")) {
            return;
        }
        Object raw = schema.get("$defs");
        if (!(raw instanceof Map<?, ?> definitions)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.defsInvalid",
                    "Schema $defs must be an object whose values are schemas.",
                    path + "/$defs"));
            return;
        }
        for (Map.Entry<?, ?> entry : definitions.entrySet()) {
            String name = String.valueOf(entry.getKey());
            String definitionPath = path + "/$defs/" + name;
            if (name.isBlank()) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.defsInvalid",
                        "Schema $defs keys must be non-blank names.",
                        definitionPath));
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> nested)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.defsInvalid",
                        "Schema $defs entry '%s' must be a schema object.".formatted(name),
                        definitionPath));
                continue;
            }
            validateSchema((Map<String, Object>) nested, definitionPath, diagnostics);
        }
    }

    private static boolean validateTypeArray(Map<String, Object> schema,
                                             String path,
                                             List<VisualDiagnostic> diagnostics) {
        Object rawType = schema.get("type");
        if (!(rawType instanceof List<?> types)) {
            return false;
        }
        boolean invalid = false;
        if (types.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.typeArrayInvalid",
                    "Schema type array must contain one supported type, optionally plus 'null'.",
                    path + "/type"));
            return true;
        }
        Set<String> seen = new LinkedHashSet<>();
        int concreteTypes = 0;
        for (int i = 0; i < types.size(); i++) {
            Object item = types.get(i);
            String typePath = path + "/type/" + i;
            if (!(item instanceof String type) || type.isBlank()) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.typeArrayInvalid",
                        "Schema type array entries must be non-blank strings.",
                        typePath));
                invalid = true;
                continue;
            }
            if (!seen.add(type)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.typeArrayDuplicate",
                        "Schema type array entry '%s' is duplicated.".formatted(type),
                        typePath));
                invalid = true;
                continue;
            }
            if (!SUPPORTED_SCHEMA_KINDS.contains(type)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.unsupportedType",
                        "Unsupported schema type/kind '%s'.".formatted(type),
                        typePath));
                invalid = true;
                continue;
            }
            if (!"null".equals(type)) {
                concreteTypes++;
            }
        }
        if (concreteTypes > 1) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.typeUnionUnsupported",
                    "Schema type arrays support one concrete type, optionally plus 'null'.",
                    path + "/type"));
            invalid = true;
        }
        return invalid;
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
        if (valueMatchesNotConstraint(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.defaultNotMismatch",
                    "Schema default must not match schema not exclusion.",
                    path));
        }
        if (!valueMatchesConditional(value, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConditionalMismatch",
                    "Schema default must satisfy schema if/then/else conditional constraints.",
                    path));
        }
        if (schema.containsKey("const") && !schemaValuesEqual(schema.get("const"), value)) {
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
	            if (!objectValueMatchesDependentRequired(object, schema)) {
	                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
	                        "Schema default must satisfy object dependentRequired constraints.",
	                        path));
	            }
	            if (!objectValueMatchesDependentSchemas(object, schema)) {
	                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
	                        "Schema default must satisfy object dependentSchemas constraints.",
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
            Object residual = residualPropertiesPolicy(schema);
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
                } else if (Boolean.FALSE.equals(residual)) {
                    diagnostics.add(VisualDiagnostic.error("visual.schema.defaultUnknownProperty",
                            "Schema default contains undeclared property '%s'.".formatted(entry.getKey()),
                            path + "/" + entry.getKey()));
                } else if (residual instanceof Map<?, ?> residualSchema) {
                    validateDefaultValue((Map<String, Object>) residualSchema,
                            schemaKind((Map<String, Object>) residualSchema),
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
	            if (!arrayValueMatchesContains(list, schema)) {
	                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
	                        "Schema default must satisfy array contains constraints.",
	                        path));
	            }
	            if (!arrayValueMatchesItemsPolicy(list, schema)) {
	                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
	                        "Schema default must satisfy array items constraints.",
	                        path));
	            }
	            if (!arrayValueMatchesUnevaluatedItems(list, schema)) {
	                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultConstraintMismatch",
	                        "Schema default must satisfy array unevaluatedItems constraints.",
	                        path));
	            }
	            for (int i = 0; i < list.size(); i++) {
	                Map<String, Object> itemSchemaForIndex = arrayItemSchemaForIndex(schema, i);
	                if (itemSchemaForIndex != null) {
	                    validateDefaultValue(itemSchemaForIndex, schemaKind(itemSchemaForIndex), path + "/" + i,
	                            diagnostics, list.get(i));
	                }
            }
            return;
        }
        if ("enum".equals(kind)) {
            Object values = schema.get("values");
            if (values instanceof List<?> list && !schemaValuesContain(list, value)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.defaultEnumMismatch",
                        "Schema default must be one of %s.".formatted(list),
                        path));
            }
            return;
        }
        Object rawEnum = schema.get("enum");
        if (rawEnum instanceof List<?> values && !schemaValuesContain(values, value)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.defaultEnumMismatch",
                    "Schema default must be one of %s.".formatted(values),
                    path));
        }
        if (valueConstrainedKind(kind) && !valueMatchesDeclaredType(value, schema, kind)) {
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

    private static Map<String, Object> objectProperty(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
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
        if (raw instanceof List<?> types) {
            return nullableTypePrimary(types);
        }
        if (raw == null && schema.containsKey("properties")) {
            return "object";
        }
        if (raw == null && hasSchemaKeyword(schema, "items", "prefixItems", "unevaluatedItems")) {
            return "array";
        }
        if (raw == null && schema.containsKey("const")) {
            return schemaKindForValue(schema.get("const"));
        }
        return raw == null ? "" : String.valueOf(raw);
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
    private static void validateUnevaluatedProperties(Map<String, Object> schema,
                                                      String kind,
                                                      String path,
                                                      List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey("unevaluatedProperties")) {
            return;
        }
        if (!objectKind(kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.unevaluatedPropertiesConstraintTypeMismatch",
                    "Object unevaluatedProperties constraints require schema type/kind object.",
                    path));
        }
        Object unevaluated = schema.get("unevaluatedProperties");
        if (unevaluated instanceof Boolean) {
            return;
        }
        if (unevaluated instanceof Map<?, ?> unevaluatedSchema) {
            validateSchema((Map<String, Object>) unevaluatedSchema, path + "/unevaluatedProperties", diagnostics);
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.schema.unevaluatedPropertiesInvalid",
                "Object schema unevaluatedProperties must be a boolean or schema object.",
                path + "/unevaluatedProperties"));
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

    private static void validateObjectDependentRequired(Map<String, Object> schema,
                                                        String kind,
                                                        String path,
                                                        List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey("dependentRequired")) {
            return;
        }
        if (!objectKind(kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.dependentRequiredConstraintTypeMismatch",
                    "Object dependentRequired constraints require schema type/kind object.",
                    path));
        }
        Object raw = schema.get("dependentRequired");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.dependentRequiredInvalid",
                    "Object schema dependentRequired must be an object whose values are arrays of property names.",
                    path + "/dependentRequired"));
            return;
        }
        Map<String, Object> properties = propertiesWithoutDiagnostics(schema);
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String trigger = String.valueOf(entry.getKey());
            String triggerPath = path + "/dependentRequired/" + trigger;
            if (trigger.isBlank()) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.dependentRequiredInvalid",
                        "Object schema dependentRequired keys must be non-blank property names.",
                        triggerPath));
            } else if (!properties.containsKey(trigger)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.dependentRequiredUnknown",
                        "Dependent-required trigger property '%s' is not declared in properties."
                                .formatted(trigger),
                        triggerPath));
            }
            if (!(entry.getValue() instanceof List<?> dependencies)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.dependentRequiredInvalid",
                        "Object schema dependentRequired entry '%s' must be an array of property names."
                                .formatted(trigger),
                        triggerPath));
                continue;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < dependencies.size(); i++) {
                Object dependency = dependencies.get(i);
                String dependencyPath = triggerPath + "/" + i;
                if (!(dependency instanceof String name) || name.isBlank()) {
                    diagnostics.add(VisualDiagnostic.error("visual.schema.dependentRequiredInvalid",
                            "Object schema dependentRequired entries must be non-blank strings.",
                            dependencyPath));
                    continue;
                }
                if (!seen.add(name)) {
                    diagnostics.add(VisualDiagnostic.error("visual.schema.dependentRequiredDuplicate",
                            "Dependent-required property '%s' is duplicated.".formatted(name),
                            dependencyPath));
                }
                if (!properties.containsKey(name)) {
                    diagnostics.add(VisualDiagnostic.error("visual.schema.dependentRequiredUnknown",
                            "Dependent-required property '%s' is not declared in properties."
                                    .formatted(name),
                            dependencyPath));
                }
            }
        }
    }

    private static void validateObjectDependentSchemas(Map<String, Object> schema,
                                                       String kind,
                                                       String path,
                                                       List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey("dependentSchemas")) {
            return;
        }
        if (!objectKind(kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.dependentSchemasConstraintTypeMismatch",
                    "Object dependentSchemas constraints require schema type/kind object.",
                    path));
        }
        Object raw = schema.get("dependentSchemas");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.dependentSchemasInvalid",
                    "Object schema dependentSchemas must be an object whose values are schema objects.",
                    path + "/dependentSchemas"));
            return;
        }
        Map<String, Object> properties = propertiesWithoutDiagnostics(schema);
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String trigger = String.valueOf(entry.getKey());
            String triggerPath = path + "/dependentSchemas/" + trigger;
            if (trigger.isBlank()) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.dependentSchemasInvalid",
                        "Object schema dependentSchemas keys must be non-blank property names.",
                        triggerPath));
            } else if (!properties.containsKey(trigger)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.dependentSchemasUnknown",
                        "Dependent-schema trigger property '%s' is not declared in properties."
                                .formatted(trigger),
                        triggerPath));
            }
            if (!(entry.getValue() instanceof Map<?, ?> dependentSchema)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.dependentSchemasInvalid",
                        "Object schema dependentSchemas entry '%s' must be a schema object."
                                .formatted(trigger),
                        triggerPath));
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            dependentSchema.forEach((key, item) -> copy.put(String.valueOf(key), item));
            validateSchema(effectiveDependentObjectSchema(copy), triggerPath, diagnostics);
        }
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
                    && !valueMatchesDeclaredType(value, schema, kind)) {
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
        if (!valueMatchesDeclaredType(constValue, schema, kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.constTypeMismatch",
                    "Const value must match schema type/kind '%s'.".formatted(kind),
                    path + "/const"));
        }
        Object rawEnum = schema.get("enum");
        if (rawEnum instanceof List<?> values && !schemaValuesContain(values, constValue)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.constEnumMismatch",
                    "Const value must be one of enum %s.".formatted(values),
                    path + "/const"));
        }
        if ("enum".equals(kind)) {
            Object rawValues = schema.get("values");
            if (rawValues instanceof List<?> values && !schemaValuesContain(values, constValue)) {
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
        if (valueMatchesNotConstraint(constValue, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.constConstraintMismatch",
                    "Const value must not match schema not exclusion.",
                    path + "/const"));
        }
        if (!valueMatchesConditional(constValue, schema)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.constConstraintMismatch",
                    "Const value must satisfy schema if/then/else conditional constraints.",
                    path + "/const"));
        }
	    }

    private static void validateNotConstraint(Map<String, Object> schema,
                                              String path,
                                              List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey("not")) {
            return;
        }
        Map<String, Object> notSchema = objectProperty(schema.get("not"));
        if (notSchema == null) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.notInvalid",
                    "Schema not constraint must be a schema object.",
                    path + "/not"));
            return;
        }
        String declaredNotKind = schemaKind(notSchema);
        validateSchema(notSchema, path + "/not", diagnostics, false);
        if (declaredNotKind.isBlank() && notSchema.containsKey("enum")) {
            Object rawEnum = notSchema.get("enum");
            if (!(rawEnum instanceof List<?> values) || values.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.enumInvalid",
                        "Schema enum must be a non-empty array.",
                        path + "/not/enum"));
            } else {
                validateEnumValues(values, path + "/not/enum", diagnostics);
            }
        }
        if (declaredNotKind.isBlank() && "enum".equals(effectiveNotSchemaKind(notSchema))) {
            Object rawValues = notSchema.get("values");
            if (!(rawValues instanceof List<?> values) || values.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.enumValuesMissing",
                        "Enum schema must declare non-empty values.",
                        path + "/not/values"));
            } else {
                validateEnumValues(values, path + "/not/values", diagnostics);
            }
        }
        String notKind = declaredNotKind.isBlank() ? effectiveNotSchemaKind(notSchema) : "";
        if (notKind.isBlank()) {
            return;
        }
        validateNumericBounds(notSchema, notKind, path + "/not", diagnostics);
        validateNumericMultipleOf(notSchema, notKind, path + "/not", diagnostics);
        validateStringLengthBounds(notSchema, notKind, path + "/not", diagnostics);
        validateStringPattern(notSchema, notKind, path + "/not", diagnostics);
        validateStringFormat(notSchema, notKind, path + "/not", diagnostics);
        validateArrayItemBounds(notSchema, notKind, path + "/not", diagnostics);
        validateArrayUniqueItems(notSchema, notKind, path + "/not", diagnostics);
        validateArrayContains(notSchema, notKind, path + "/not", diagnostics);
        validateArrayUnevaluatedItems(notSchema, notKind, path + "/not", diagnostics);
        validateObjectPropertyBounds(notSchema, notKind, path + "/not", diagnostics);
        validateObjectPatternProperties(notSchema, notKind, path + "/not", diagnostics);
        validateObjectPropertyNames(notSchema, notKind, path + "/not", diagnostics);
        validateObjectDependentRequired(notSchema, notKind, path + "/not", diagnostics);
        validateObjectDependentSchemas(notSchema, notKind, path + "/not", diagnostics);
        validateUnevaluatedProperties(notSchema, notKind, path + "/not", diagnostics);
    }

    private static String effectiveNotSchemaKind(Map<String, Object> schema) {
        String kind = schemaKind(schema);
        if (!kind.isBlank()) {
            return kind;
        }
        if (hasSchemaKeyword(schema, "pattern", "format", "minLength", "maxLength")) {
            return "string";
        }
        if (hasSchemaKeyword(schema, "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf")) {
            return "number";
        }
        if (schema.containsKey("values")) {
            return "enum";
        }
        if (hasSchemaKeyword(schema, "items", "prefixItems", "contains", "minItems", "maxItems", "uniqueItems",
                "minContains", "maxContains", "unevaluatedItems")) {
            return "array";
        }
        if (hasSchemaKeyword(schema, "properties", "required", "additionalProperties", "unevaluatedProperties",
                "patternProperties", "propertyNames", "dependentRequired", "dependentSchemas", "minProperties",
                "maxProperties")) {
            return "object";
        }
        return "";
    }

    private static boolean hasSchemaKeyword(Map<String, Object> schema, String... keywords) {
        if (schema == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (schema.containsKey(keyword)) {
                return true;
            }
        }
        return false;
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

    @SuppressWarnings("unchecked")
    private static void validateArrayPrefixItems(Map<String, Object> schema,
                                                 String kind,
                                                 String path,
                                                 List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey("prefixItems")) {
            return;
        }
        if (!arrayKind(kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.prefixItemsConstraintTypeMismatch",
                    "Array prefixItems constraints require schema type/kind array.",
                    path));
        }
        Object raw = schema.get("prefixItems");
        if (!(raw instanceof List<?> values)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.prefixItemsInvalid",
                    "Array schema prefixItems must be an array of schema objects.",
                    path + "/prefixItems"));
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            String itemPath = path + "/prefixItems/" + i;
            if (!(value instanceof Map<?, ?> itemSchema)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.prefixItemsInvalid",
                        "Array schema prefixItems entry %d must be a schema object.".formatted(i),
                        itemPath));
                continue;
            }
            validateSchema((Map<String, Object>) itemSchema, itemPath, diagnostics);
        }
    }

    private static void validateArrayContains(Map<String, Object> schema,
                                              String kind,
                                              String path,
	                                              List<VisualDiagnostic> diagnostics) {
	        if (!hasArrayContains(schema)) {
	            return;
	        }
	        boolean validArrayKind = arrayKind(kind);
	        if (!validArrayKind) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.containsConstraintTypeMismatch",
	                    "Array contains constraints require schema type/kind array.",
	                    path));
	        }
	        Object rawContains = schema.get("contains");
	        if (!(rawContains instanceof Map<?, ?> containsSchema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.containsConstraintInvalid",
	                    "Array contains constraint must be a schema object.",
	                    path + "/contains"));
	        } else {
	            Map<String, Object> copy = new LinkedHashMap<>();
	            containsSchema.forEach((key, item) -> copy.put(String.valueOf(key), item));
	            validateSchema(copy, path + "/contains", diagnostics);
	        }
	        validateArrayContainsBoundary(schema, "minContains", path, diagnostics);
	        validateArrayContainsBoundary(schema, "maxContains", path, diagnostics);
	        if (!schema.containsKey("contains")
	                && (schema.containsKey("minContains") || schema.containsKey("maxContains"))) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.containsConstraintInvalid",
	                    "Array minContains/maxContains constraints require a contains schema.",
	                    path));
	        }
	        if (!validArrayKind || !(rawContains instanceof Map<?, ?>) || !arrayContainsBoundariesValid(schema)) {
	            return;
	        }
	        Long minimum = arrayMinContains(schema);
	        Long maximum = arrayMaxContains(schema);
	        if (minimum != null && maximum != null && minimum > maximum) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.containsBoundsInvalid",
	                    "Array minContains %d is greater than maxContains %d.".formatted(minimum, maximum),
	                    path));
	        }
	    }

    @SuppressWarnings("unchecked")
    private static void validateArrayUnevaluatedItems(Map<String, Object> schema,
                                                      String kind,
                                                      String path,
                                                      List<VisualDiagnostic> diagnostics) {
        if (!schema.containsKey("unevaluatedItems")) {
            return;
        }
        if (!arrayKind(kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.schema.unevaluatedItemsConstraintTypeMismatch",
                    "Array unevaluatedItems constraints require schema type/kind array.",
                    path));
        }
        Object raw = schema.get("unevaluatedItems");
        if (raw instanceof Boolean) {
            return;
        }
        if (raw instanceof Map<?, ?> unevaluatedItemsSchema) {
            validateSchema((Map<String, Object>) unevaluatedItemsSchema,
                    path + "/unevaluatedItems", diagnostics);
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.schema.unevaluatedItemsInvalid",
                "Array schema unevaluatedItems must be a boolean or schema object.",
                path + "/unevaluatedItems"));
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
        List<Object> seen = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (schemaValuesContain(seen, value)) {
                diagnostics.add(VisualDiagnostic.error("visual.schema.enumDuplicate",
                        "Enum value '%s' is duplicated.".formatted(value),
                        path + "/" + i));
            }
            seen.add(value);
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

	    private static boolean hasArrayContains(Map<String, Object> schema) {
	        return schema.containsKey("contains")
	                || schema.containsKey("minContains")
	                || schema.containsKey("maxContains");
	    }

    private static boolean hasSupportedArrayItemContract(Map<String, Object> schema) {
        if (schema.get("items") instanceof Map<?, ?>) {
            return true;
        }
        if (schema.get("items") instanceof Boolean) {
            return true;
        }
        if (!prefixItemsOf(schema).isEmpty()) {
            return true;
        }
        return schema.containsKey("unevaluatedItems")
                && (schema.get("unevaluatedItems") instanceof Boolean
                || schema.get("unevaluatedItems") instanceof Map<?, ?>);
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

	    private static void validateArrayContainsBoundary(Map<String, Object> schema,
	                                                      String keyword,
	                                                      String path,
	                                                      List<VisualDiagnostic> diagnostics) {
	        if (!schema.containsKey(keyword)) {
	            return;
	        }
	        if (arrayItemBoundary(schema.get(keyword)) == null) {
	            diagnostics.add(VisualDiagnostic.error("visual.schema.containsConstraintInvalid",
	                    "Array contains constraint '%s' must be a non-negative integer.".formatted(keyword),
	                    path + "/" + keyword));
	        }
	    }

	    private static boolean arrayContainsBoundariesValid(Map<String, Object> schema) {
	        return (!schema.containsKey("minContains") || arrayItemBoundary(schema.get("minContains")) != null)
	                && (!schema.containsKey("maxContains") || arrayItemBoundary(schema.get("maxContains")) != null);
	    }

	    private static Long arrayMinContains(Map<String, Object> schema) {
	        if (!schema.containsKey("contains")) {
	            return null;
	        }
	        Long explicit = arrayItemBoundary(schema.get("minContains"));
	        return explicit == null ? 1L : explicit;
	    }

	    private static Long arrayMaxContains(Map<String, Object> schema) {
	        return arrayItemBoundary(schema.get("maxContains"));
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
	        return schemaValuesUnique(list);
	    }

	    private static boolean arrayValueMatchesContains(List<?> value, Map<String, Object> schema) {
	        Map<String, Object> contains = objectProperty(schema.get("contains"));
	        if (contains == null) {
	            return true;
	        }
	        long matches = value.stream()
	                .filter(item -> valueMatchesSchema(item, contains))
	                .count();
	        Long minimum = arrayMinContains(schema);
	        if (minimum != null && matches < minimum) {
	            return false;
	        }
	        Long maximum = arrayMaxContains(schema);
	        return maximum == null || matches <= maximum;
	    }

	    @SuppressWarnings("unchecked")
	    private static boolean arrayValueMatchesSchema(Object value, Map<String, Object> schema) {
	        if (!(value instanceof List<?> list) || !arrayKind(schemaKind(schema))) {
	            return true;
	        }
	        if (!arrayValueMatchesItemBounds(value, schema) || !arrayValueMatchesUniqueItems(value, schema)) {
	            return false;
	        }
	        if (!arrayValueMatchesContains(list, schema)) {
	            return false;
	        }
	        if (!arrayValueMatchesItemsPolicy(list, schema)) {
	            return false;
	        }
	        if (!arrayValueMatchesUnevaluatedItems(list, schema)) {
	            return false;
	        }
	        for (int i = 0; i < list.size(); i++) {
	            Map<String, Object> itemSchemaForIndex = arrayItemSchemaForIndex(schema, i);
	            if (itemSchemaForIndex != null && !valueMatchesSchema(list.get(i), itemSchemaForIndex)) {
	                return false;
	            }
	        }
	        return true;
	    }

    private static boolean arrayValueMatchesItemsPolicy(List<?> value, Map<String, Object> schema) {
        Object items = schema.get("items");
        if (!Boolean.FALSE.equals(items)) {
            return true;
        }
        return value.size() <= prefixItemsOf(schema).size();
    }

    private static boolean arrayValueMatchesUnevaluatedItems(List<?> value, Map<String, Object> schema) {
        Object residual = unevaluatedArrayItemsPolicy(schema);
        if (residual == null || Boolean.TRUE.equals(residual)) {
            return true;
        }
        int firstUnevaluatedIndex = prefixItemsOf(schema).size();
        if (value.size() <= firstUnevaluatedIndex) {
            return true;
        }
        if (Boolean.FALSE.equals(residual)) {
            return false;
        }
        if (residual instanceof Map<?, ?> residualSchema) {
            Map<String, Object> itemSchema = objectProperty(residualSchema);
            if (itemSchema == null) {
                return true;
            }
            for (int i = firstUnevaluatedIndex; i < value.size(); i++) {
                if (!valueMatchesSchema(value.get(i), itemSchema)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Object unevaluatedArrayItemsPolicy(Map<String, Object> schema) {
        if (schema.containsKey("items") || !schema.containsKey("unevaluatedItems")) {
            return null;
        }
        return schema.get("unevaluatedItems");
    }

    private static Map<String, Object> arrayItemSchemaForIndex(Map<String, Object> schema, int index) {
        List<Map<String, Object>> prefixItems = prefixItemsOf(schema);
        if (index < prefixItems.size()) {
            return prefixItems.get(index);
        }
        Map<String, Object> items = objectProperty(schema.get("items"));
        if (items != null) {
            return items;
        }
        Object residual = unevaluatedArrayItemsPolicy(schema);
        return residual instanceof Map<?, ?> residualSchema ? objectProperty(residualSchema) : null;
    }

	    @SuppressWarnings("unchecked")
	    private static List<Map<String, Object>> prefixItemsOf(Map<String, Object> schema) {
	        Object raw = schema.get("prefixItems");
	        if (!(raw instanceof List<?> values)) {
	            return List.of();
	        }
	        List<Map<String, Object>> prefixItems = new ArrayList<>();
	        for (Object value : values) {
	            if (value instanceof Map<?, ?> itemSchema) {
	                prefixItems.add((Map<String, Object>) itemSchema);
	            }
	        }
	        return prefixItems;
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

	    private static boolean objectValueMatchesDependentRequired(Map<String, Object> value,
	                                                               Map<String, Object> schema) {
	        Map<String, List<String>> dependencies = dependentRequiredOf(schema);
	        if (dependencies.isEmpty()) {
	            return true;
	        }
	        for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
	            if (!presentObjectProperty(value, entry.getKey())) {
	                continue;
	            }
	            for (String dependency : entry.getValue()) {
	                if (!presentObjectProperty(value, dependency)) {
	                    return false;
	                }
	            }
	        }
	        return true;
	    }

	    private static boolean objectValueMatchesDependentSchemas(Map<String, Object> value,
	                                                              Map<String, Object> schema) {
	        Map<String, Map<String, Object>> dependencies = dependentSchemasOf(schema);
	        if (dependencies.isEmpty()) {
	            return true;
	        }
	        for (Map.Entry<String, Map<String, Object>> entry : dependencies.entrySet()) {
	            if (!presentObjectProperty(value, entry.getKey())) {
	                continue;
	            }
	            if (!valueMatchesSchema(value, effectiveDependentObjectSchema(entry.getValue()))) {
	                return false;
	            }
	        }
	        return true;
	    }

	    private static Map<String, List<String>> dependentRequiredOf(Map<String, Object> schema) {
	        Object raw = schema.get("dependentRequired");
	        if (!(raw instanceof Map<?, ?> rawMap)) {
	            return Map.of();
	        }
	        Map<String, List<String>> dependencies = new LinkedHashMap<>();
	        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
	            if (!(entry.getValue() instanceof List<?> rawDependencies)) {
	                continue;
	            }
	            List<String> names = new ArrayList<>();
	            for (Object dependency : rawDependencies) {
	                if (dependency instanceof String name && !name.isBlank()) {
	                    names.add(name);
	                }
	            }
	            dependencies.put(String.valueOf(entry.getKey()), names);
	        }
	        return dependencies;
	    }

	    private static Map<String, Map<String, Object>> dependentSchemasOf(Map<String, Object> schema) {
	        Object raw = schema.get("dependentSchemas");
	        if (!(raw instanceof Map<?, ?> rawMap)) {
	            return Map.of();
	        }
	        Map<String, Map<String, Object>> dependencies = new LinkedHashMap<>();
	        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
	            if (!(entry.getValue() instanceof Map<?, ?> rawSchema)) {
	                continue;
	            }
	            Map<String, Object> copy = new LinkedHashMap<>();
	            rawSchema.forEach((key, item) -> copy.put(String.valueOf(key), item));
	            dependencies.put(String.valueOf(entry.getKey()), effectiveDependentObjectSchema(copy));
	        }
	        return dependencies;
	    }

	    private static Map<String, Object> effectiveDependentObjectSchema(Map<String, Object> schema) {
	        Map<String, Object> effective = new LinkedHashMap<>(schema);
	        if (schemaKind(effective).isBlank()
	                && (effective.containsKey("required")
	                || effective.containsKey("dependentRequired")
	                || effective.containsKey("dependentSchemas")
	                || effective.containsKey("minProperties")
                || effective.containsKey("maxProperties")
                || effective.containsKey("propertyNames")
                || effective.containsKey("patternProperties")
                || effective.containsKey("unevaluatedProperties"))) {
            effective.put("type", "object");
        }
        return effective;
	    }

	    private static boolean presentObjectProperty(Map<String, Object> value, String property) {
	        return value.containsKey(property) && value.get(property) != null;
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
	        if (!objectValueMatchesDependentRequired(object, schema)) {
	            return false;
	        }
	        if (!objectValueMatchesDependentSchemas(object, schema)) {
	            return false;
	        }
	        for (String required : requiredNamesWithoutDiagnostics(schema)) {
	            if (!object.containsKey(required) || object.get(required) == null) {
	                return false;
	            }
	        }
	        Map<String, Object> properties = propertiesWithoutDiagnostics(schema);
	        Object residual = residualPropertiesPolicy(schema);
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
	            } else if (Boolean.FALSE.equals(residual)) {
	                return false;
	            } else if (residual instanceof Map<?, ?> residualSchema
	                    && !valueMatchesSchema(entry.getValue(), (Map<String, Object>) residualSchema)) {
	                return false;
	            }
	        }
	        return true;
	    }

	    private static Object residualPropertiesPolicy(Map<String, Object> schema) {
		        if (schema.containsKey("additionalProperties")) {
		            return schema.get("additionalProperties");
	        }
		        return schema.get("unevaluatedProperties");
	    }

	    private static boolean valueMatchesSchema(Object value, Map<String, Object> schema) {
	        String kind = schemaKind(schema);
		        if (!valueMatchesDeclaredType(value, schema, kind)) {
	            return false;
	        }
	        if (schema.containsKey("const") && !schemaValuesEqual(schema.get("const"), value)) {
	            return false;
	        }
	        Object rawEnum = schema.get("enum");
	        if (rawEnum instanceof List<?> values && !schemaValuesContain(values, value)) {
	            return false;
	        }
	        if ("enum".equals(kind)) {
	            Object rawValues = schema.get("values");
	            if (rawValues instanceof List<?> values && !schemaValuesContain(values, value)) {
	                return false;
	            }
	        }
        if (valueMatchesNotConstraint(value, schema)) {
            return false;
        }
	        return numericValueMatchesBounds(value, schema)
	                && numericValueMatchesMultipleOf(value, schema)
	                && stringValueMatchesLengthBounds(value, schema)
	                && stringValueMatchesPattern(value, schema)
	                && stringValueMatchesFormat(value, schema)
	                && arrayValueMatchesSchema(value, schema)
	                && objectValueMatchesSchema(value, schema)
                    && allOfMismatch(value, schema).isEmpty()
                    && unionMismatch(value, schema).isEmpty()
                    && valueMatchesConditional(value, schema);
	    }

    private static boolean valueMatchesNotConstraint(Object value, Map<String, Object> schema) {
        Map<String, Object> notSchema = objectProperty(schema.get("not"));
        return notSchema != null && valueMatchesSchema(value, effectiveNotValueSchema(notSchema));
    }

    private static Map<String, Object> effectiveNotValueSchema(Map<String, Object> schema) {
        String declaredKind = schemaKind(schema);
        if (!declaredKind.isBlank()) {
            return schema;
        }
        String effectiveKind = effectiveNotSchemaKind(schema);
        if (effectiveKind.isBlank()) {
            return schema;
        }
        Map<String, Object> effective = new LinkedHashMap<>(schema);
        effective.put("type", effectiveKind);
        return effective;
    }

    private static boolean valueMatchesConditional(Object value, Map<String, Object> schema) {
        Map<String, Object> condition = objectProperty(schema.get("if"));
        if (condition == null) {
            return true;
        }
        boolean matched = valueMatchesSchema(value, effectiveConditionalValueSchema(condition, schema));
        Map<String, Object> branch = objectProperty(schema.get(matched ? "then" : "else"));
        return branch == null || valueMatchesSchema(value, effectiveConditionalValueSchema(branch, schema));
    }

    private static Optional<String> conditionalMismatch(Object value, Map<String, Object> schema) {
        Map<String, Object> condition = objectProperty(schema.get("if"));
        if (condition == null) {
            return Optional.empty();
        }
        boolean matched = valueMatchesSchema(value, effectiveConditionalValueSchema(condition, schema));
        String keyword = matched ? "then" : "else";
        Map<String, Object> branch = objectProperty(schema.get(keyword));
        if (branch == null || valueMatchesSchema(value, effectiveConditionalValueSchema(branch, schema))) {
            return Optional.empty();
        }
        return Optional.of("%s branch did not match".formatted(keyword));
    }

    private static Map<String, Object> effectiveConditionalValueSchema(Map<String, Object> schema,
                                                                       Map<String, Object> parentSchema) {
        Map<String, Object> effective = new LinkedHashMap<>(schema);
        if (schemaKind(effective).isBlank()) {
            String kind = effectiveNotSchemaKind(effective);
            if (!kind.isBlank()) {
                effective.put("type", kind);
            }
        }
        if ("object".equals(schemaKind(effective))
                && !effective.containsKey("properties")
                && "object".equals(schemaKind(parentSchema))) {
            Map<String, Object> parentProperties = propertiesWithoutDiagnostics(parentSchema);
            if (!parentProperties.isEmpty()) {
                effective.put("properties", parentProperties);
            }
        }
        return effective;
    }

    private static Map<String, Object> effectiveConditionalValidationSchema(Map<String, Object> schema,
                                                                            Map<String, Object> parentSchema) {
        Map<String, Object> effective = effectiveConditionalValueSchema(schema, parentSchema);
        if (!schema.containsKey("properties")
                && "object".equals(schemaKind(effective))
                && effective.get("properties") instanceof Map<?, ?> properties) {
            Map<String, Object> placeholders = new LinkedHashMap<>();
            properties.forEach((key, item) -> placeholders.put(String.valueOf(key), Map.of("type", "any")));
            effective.put("properties", placeholders);
        }
        return effective;
    }

    private static String notSchemaLabel(Map<String, Object> schema) {
        List<Object> values = finiteSchemaValues(schema);
        if (!values.isEmpty()) {
            return values.toString();
        }
        if (schema == null || schema.isEmpty()) {
            return "{}";
        }
        String kind = effectiveNotSchemaKind(schema);
        List<String> constraints = new ArrayList<>();
        addConstraintLabel(constraints, schema, "pattern");
        addConstraintLabel(constraints, schema, "format");
        addConstraintLabel(constraints, schema, "minimum");
        addConstraintLabel(constraints, schema, "maximum");
        addConstraintLabel(constraints, schema, "exclusiveMinimum");
        addConstraintLabel(constraints, schema, "exclusiveMaximum");
        addConstraintLabel(constraints, schema, "multipleOf");
        if (constraints.isEmpty()) {
            return kind.isBlank() ? schema.keySet().toString() : kind;
        }
        return kind.isBlank()
                ? String.join(", ", constraints)
                : kind + " " + String.join(", ", constraints);
    }

    private static void addConstraintLabel(List<String> labels, Map<String, Object> schema, String keyword) {
        if (schema.containsKey(keyword)) {
            labels.add(keyword + "=" + schema.get(keyword));
        }
    }

    private static List<Object> finiteSchemaValues(Map<String, Object> schema) {
        if (schema == null) {
            return List.of();
        }
        if (schema.containsKey("const")) {
            return List.of(schema.get("const"));
        }
        Object rawEnum = schema.get("enum");
        if (rawEnum instanceof List<?> values) {
            return uniqueSchemaValues(values);
        }
        if ("enum".equals(schemaKind(schema)) && schema.get("values") instanceof List<?> values) {
            return uniqueSchemaValues(values);
        }
        return List.of();
    }

    private static List<Object> uniqueSchemaValues(List<?> values) {
        List<Object> unique = new ArrayList<>();
        for (Object value : values) {
            if (!schemaValuesContain(unique, value)) {
                unique.add(value);
            }
        }
        return unique;
    }

    private static boolean schemaValuesContain(List<?> values, Object value) {
        return values.stream().anyMatch(item -> schemaValuesEqual(item, value));
    }

    private static boolean schemaValuesUnique(List<?> values) {
        List<Object> seen = new ArrayList<>();
        for (Object value : values) {
            if (schemaValuesContain(seen, value)) {
                return false;
            }
            seen.add(value);
        }
        return true;
    }

    private static boolean schemaValuesEqual(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            BigDecimal leftDecimal = schemaNumberValue(leftNumber);
            BigDecimal rightDecimal = schemaNumberValue(rightNumber);
            return leftDecimal != null && rightDecimal != null && leftDecimal.compareTo(rightDecimal) == 0;
        }
        if (left instanceof Map<?, ?> leftMap && right instanceof Map<?, ?> rightMap) {
            if (leftMap.size() != rightMap.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : leftMap.entrySet()) {
                Object key = entry.getKey();
                if (!rightMap.containsKey(key) || !schemaValuesEqual(entry.getValue(), rightMap.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
            if (leftList.size() != rightList.size()) {
                return false;
            }
            for (int i = 0; i < leftList.size(); i++) {
                if (!schemaValuesEqual(leftList.get(i), rightList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    private static BigDecimal schemaNumberValue(Number value) {
        if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)) {
            return null;
        }
        if (value instanceof Float floatValue && !Float.isFinite(floatValue)) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Optional<String> unionMismatch(Object value, Map<String, Object> schema) {
        if (schema.containsKey("oneOf")) {
            List<Map<String, Object>> branches = unionBranches(schema, "oneOf");
            if (!branches.isEmpty()) {
                long matches = branches.stream()
                        .filter(branch -> valueMatchesSchema(value, branch))
                        .count();
                if (matches != 1) {
                    return Optional.of(matches == 0
                            ? "oneOf matched none"
                            : "oneOf matched %d branches".formatted(matches));
                }
            }
        }
        if (schema.containsKey("anyOf")) {
            List<Map<String, Object>> branches = unionBranches(schema, "anyOf");
            if (!branches.isEmpty()) {
                long matches = branches.stream()
                        .filter(branch -> valueMatchesSchema(value, branch))
                        .count();
                if (matches < 1) {
                    return Optional.of("anyOf matched none");
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> allOfMismatch(Object value, Map<String, Object> schema) {
        List<Map<String, Object>> branches = unionBranches(schema, "allOf");
        for (int i = 0; i < branches.size(); i++) {
            if (!valueMatchesSchema(value, branches.get(i))) {
                return Optional.of("allOf branch %d did not match".formatted(i));
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> unionBranches(Map<String, Object> schema, String keyword) {
        Object raw = schema.get(keyword);
        if (!(raw instanceof List<?> branches)) {
            return List.of();
        }
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (Object branch : branches) {
            if (branch instanceof Map<?, ?> branchSchema) {
                schemas.add((Map<String, Object>) branchSchema);
            }
        }
        return schemas;
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

    private static boolean valueMatchesDeclaredType(Object value, Map<String, Object> schema, String kind) {
        return value == null && schemaAllowsNull(schema) || constValueMatchesKind(value, kind);
    }

    private static boolean schemaAllowsNull(Map<String, Object> schema) {
        Object raw = schema.containsKey("kind") ? schema.get("kind") : schema.get("type");
        if (raw instanceof List<?> types) {
            return types.stream().anyMatch(type -> "null".equals(type));
        }
        if ("null".equals(raw)) {
            return true;
        }
        return raw == null && schema.containsKey("const") && schema.get("const") == null;
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
