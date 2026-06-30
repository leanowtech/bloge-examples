package com.leanowtech.bloge.gateway.visual.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared schema compatibility helpers for visual authoring.
 */
public final class VisualSchemaCompatibility {

    private static final Pattern INTEGER_LITERAL = Pattern.compile("[-+]?\\d+");
    private static final Pattern NUMBER_LITERAL = Pattern.compile(
            "[-+]?(?:\\d+\\.\\d*|\\d*\\.\\d+|\\d+[eE][-+]?\\d+|\\d+\\.\\d*[eE][-+]?\\d+|\\d*\\.\\d+[eE][-+]?\\d+)");

    private VisualSchemaCompatibility() {
    }

    /**
     * @param sourceSchema source schema
     * @param targetSchema target schema
     * @return true when the source can safely feed the target
     */
    public static boolean schemasCompatible(Map<String, Object> sourceSchema, Map<String, Object> targetSchema) {
        return schemaCompatibilityIssue(sourceSchema, targetSchema).isEmpty();
    }

    /**
     * @param sourceSchema source schema
     * @param targetSchema target schema
     * @return optional reason when the source cannot safely feed the target
     */
    public static Optional<String> schemaCompatibilityIssue(Map<String, Object> sourceSchema,
                                                            Map<String, Object> targetSchema) {
        return schemaCompatibilityIssue(sourceSchema, targetSchema, "");
    }

    private static Optional<String> schemaCompatibilityIssue(Map<String, Object> sourceSchema,
                                                             Map<String, Object> targetSchema,
                                                             String path) {
        String sourceType = schemaType(sourceSchema);
        String targetType = schemaType(targetSchema);
        if (sourceType.isBlank() || targetType.isBlank()
                || "any".equals(sourceType) || "any".equals(targetType)
                || "opaque".equals(sourceType) || "opaque".equals(targetType)) {
            return Optional.empty();
        }
        if ("array".equals(sourceType) && "array".equals(targetType)) {
            Map<String, Object> sourceItems = objectProperty(sourceSchema.get("items"));
            Map<String, Object> targetItems = objectProperty(targetSchema.get("items"));
            return sourceItems == null || targetItems == null
                    ? Optional.empty()
                    : schemaCompatibilityIssue(sourceItems, targetItems, appendCompatibilityPath(path, "items"));
        }
        if ("object".equals(sourceType) && "object".equals(targetType)) {
            return objectSchemaCompatibilityIssue(sourceSchema, targetSchema, path);
        }
        List<Object> targetEnumValues = enumValues(targetSchema);
        if (!targetEnumValues.isEmpty()) {
            List<Object> sourceEnumValues = enumValues(sourceSchema);
            if (sourceEnumValues.isEmpty()) {
                return Optional.of(reasonAt(path,
                        "target enum %s requires a finite source enum domain, but source is %s"
                                .formatted(valueDomainLabel(targetEnumValues), schemaTypeLabel(sourceSchema))));
            }
            List<Object> outside = sourceEnumValues.stream()
                    .filter(value -> !targetEnumValues.contains(value))
                    .toList();
            return outside.isEmpty()
                    ? Optional.empty()
                    : Optional.of(reasonAt(path,
                    "source enum value(s) %s are outside target enum %s"
                            .formatted(valueDomainLabel(outside), valueDomainLabel(targetEnumValues))));
        }
        if ("enum".equals(sourceType)) {
            List<Object> sourceEnumValues = enumValues(sourceSchema);
            if (sourceEnumValues.isEmpty()) {
                return Optional.empty();
            }
            List<Object> incompatible = sourceEnumValues.stream()
                    .filter(value -> !valueMatchesSchema(value, targetSchema))
                    .toList();
            return incompatible.isEmpty()
                    ? Optional.empty()
                    : Optional.of(reasonAt(path,
                    "source enum value(s) %s do not match target schema %s"
                            .formatted(valueDomainLabel(incompatible), schemaTypeLabel(targetSchema))));
        }
        if (sourceType.equals(targetType) || numeric(sourceType) && numeric(targetType)) {
            return numericBoundsCompatibilityIssue(sourceSchema, targetSchema, path);
        }
        return Optional.of(reasonAt(path,
                "source type %s cannot feed target type %s"
                        .formatted(schemaTypeLabel(sourceSchema), schemaTypeLabel(targetSchema))));
    }

    private static Optional<String> numericBoundsCompatibilityIssue(Map<String, Object> sourceSchema,
                                                                    Map<String, Object> targetSchema,
                                                                    String path) {
        if (!numeric(schemaType(sourceSchema)) || !numeric(schemaType(targetSchema))) {
            return Optional.empty();
        }
        NumericBoundary targetLower = lowerBound(targetSchema);
        if (targetLower != null) {
            NumericBoundary sourceLower = lowerBound(sourceSchema);
            if (sourceLower == null) {
                return Optional.of(reasonAt(path,
                        "target requires %s but source has no lower bound".formatted(targetLower.lowerLabel())));
            }
            if (!lowerBoundAtLeast(sourceLower, targetLower)) {
                return Optional.of(reasonAt(path,
                        "source lower bound %s is weaker than target lower bound %s"
                                .formatted(sourceLower.lowerLabel(), targetLower.lowerLabel())));
            }
        }
        NumericBoundary targetUpper = upperBound(targetSchema);
        if (targetUpper != null) {
            NumericBoundary sourceUpper = upperBound(sourceSchema);
            if (sourceUpper == null) {
                return Optional.of(reasonAt(path,
                        "target requires %s but source has no upper bound".formatted(targetUpper.upperLabel())));
            }
            if (!upperBoundAtMost(sourceUpper, targetUpper)) {
                return Optional.of(reasonAt(path,
                        "source upper bound %s is weaker than target upper bound %s"
                                .formatted(sourceUpper.upperLabel(), targetUpper.upperLabel())));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> objectSchemaCompatibilityIssue(Map<String, Object> sourceSchema,
                                                                   Map<String, Object> targetSchema,
                                                                   String path) {
        Map<String, Object> sourceProperties = propertiesOf(sourceSchema);
        Map<String, Object> targetProperties = propertiesOf(targetSchema);
        Set<String> sourceRequired = new LinkedHashSet<>(requiredNamesOf(sourceSchema));
        for (String required : requiredNamesOf(targetSchema)) {
            String childPath = appendCompatibilityPath(path, required);
            Map<String, Object> sourceProperty = objectProperty(sourceProperties.get(required));
            Map<String, Object> targetProperty = objectProperty(targetProperties.get(required));
            if (sourceProperty == null) {
                return Optional.of(reasonAt(childPath,
                        "source object does not declare required field '%s'".formatted(required)));
            }
            if (targetProperty == null) {
                return Optional.of(reasonAt(childPath,
                        "target schema requires undeclared field '%s'".formatted(required)));
            }
            if (!sourceRequired.contains(required)) {
                return Optional.of(reasonAt(childPath,
                        "source object does not guarantee required field '%s'".formatted(required)));
            }
            Optional<String> nested = schemaCompatibilityIssue(sourceProperty, targetProperty, childPath);
            if (nested.isPresent()) {
                return nested;
            }
        }

        Object targetAdditional = targetSchema.get("additionalProperties");
        for (Map.Entry<String, Object> entry : sourceProperties.entrySet()) {
            String propertyName = entry.getKey();
            String childPath = appendCompatibilityPath(path, propertyName);
            Map<String, Object> sourceProperty = objectProperty(entry.getValue());
            if (sourceProperty == null) {
                continue;
            }
            Map<String, Object> targetProperty = objectProperty(targetProperties.get(propertyName));
            if (targetProperty != null) {
                Optional<String> nested = schemaCompatibilityIssue(sourceProperty, targetProperty, childPath);
                if (nested.isPresent()) {
                    return nested;
                }
            } else if (Boolean.FALSE.equals(targetAdditional)) {
                return Optional.of(reasonAt(childPath,
                        "source object declares additional field '%s' but target additionalProperties=false"
                                .formatted(propertyName)));
            } else if (targetAdditional instanceof Map<?, ?> additionalSchema) {
                Optional<String> nested = schemaCompatibilityIssue(sourceProperty, objectProperty(additionalSchema),
                        childPath);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return additionalPropertiesCompatibilityIssue(sourceSchema, targetAdditional, path);
    }

    private static Optional<String> additionalPropertiesCompatibilityIssue(Map<String, Object> sourceSchema,
                                                                           Object targetAdditional,
                                                                           String path) {
        Object sourceAdditional = sourceSchema.get("additionalProperties");
        if (Boolean.FALSE.equals(targetAdditional)) {
            return Boolean.FALSE.equals(sourceAdditional)
                    ? Optional.empty()
                    : Optional.of(reasonAt(path,
                    "source object allows undeclared additional fields but target additionalProperties=false"));
        }
        if (targetAdditional instanceof Map<?, ?> targetAdditionalSchema) {
            if (sourceAdditional == null || Boolean.TRUE.equals(sourceAdditional)) {
                return Optional.of(reasonAt(path,
                        "source object allows unconstrained additional fields but target additionalProperties requires %s"
                                .formatted(schemaTypeLabel(objectProperty(targetAdditionalSchema)))));
            }
            if (sourceAdditional instanceof Map<?, ?> sourceAdditionalSchema) {
                return schemaCompatibilityIssue(objectProperty(sourceAdditionalSchema),
                        objectProperty(targetAdditionalSchema), appendCompatibilityPath(path, "additionalProperties"));
            }
        }
        return Optional.empty();
    }

    /**
     * @param schema schema
     * @return readable type label used by diagnostics
     */
    public static String schemaTypeLabel(Map<String, Object> schema) {
        List<Object> values = enumValues(schema);
        if (!values.isEmpty()) {
            return "enum<" + String.join("|", values.stream().map(String::valueOf).toList()) + ">";
        }
        String type = schemaType(schema);
        if ("array".equals(type)) {
            Map<String, Object> items = objectProperty(schema.get("items"));
            return items == null ? "array" : "array<" + schemaTypeLabel(items) + ">";
        }
        return type.isBlank() ? "unknown" : type;
    }

    /**
     * @param reason compatibility reason
     * @return sentence suffix for diagnostics
     */
    public static String compatibilityReason(String reason) {
        return reason == null || reason.isBlank() ? "" : " Reason: " + reason + ".";
    }

    /**
     * @param expression raw expression
     * @return literal schema when the expression is statically known to be a literal value
     */
    public static Optional<StaticExpressionLiteral> staticExpressionLiteral(String expression) {
        String value = expression == null ? "" : expression.trim();
        if (value.isBlank()) {
            return Optional.empty();
        }
        if ("null".equals(value)) {
            return Optional.of(new StaticExpressionLiteral(value, literalEnumSchema(null)));
        }
        if ("true".equals(value) || "false".equals(value)) {
            return Optional.of(new StaticExpressionLiteral(value, literalEnumSchema(Boolean.valueOf(value))));
        }
        Optional<String> stringLiteral = parseStringLiteral(value);
        if (stringLiteral.isPresent()) {
            return Optional.of(new StaticExpressionLiteral(value, literalEnumSchema(stringLiteral.get())));
        }
        if (INTEGER_LITERAL.matcher(value).matches()) {
            return Optional.of(new StaticExpressionLiteral(value, literalEnumSchema(parseIntegerLiteral(value))));
        }
        if (NUMBER_LITERAL.matcher(value).matches()) {
            return Optional.of(new StaticExpressionLiteral(value, literalEnumSchema(Double.valueOf(value))));
        }
        return Optional.empty();
    }

    private static Optional<String> parseStringLiteral(String value) {
        if (value.length() < 2) {
            return Optional.empty();
        }
        char quote = value.charAt(0);
        if ((quote != '"' && quote != '\'') || value.charAt(value.length() - 1) != quote) {
            return Optional.empty();
        }
        StringBuilder result = new StringBuilder(value.length() - 2);
        boolean escaped = false;
        for (int i = 1; i < value.length() - 1; i++) {
            char current = value.charAt(i);
            if (escaped) {
                result.append(unescapedChar(current));
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == quote) {
                return Optional.empty();
            } else {
                result.append(current);
            }
        }
        return escaped ? Optional.empty() : Optional.of(result.toString());
    }

    private static char unescapedChar(char value) {
        return switch (value) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'b' -> '\b';
            case 'f' -> '\f';
            default -> value;
        };
    }

    private static Object parseIntegerLiteral(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
                return (int) parsed;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            return Double.valueOf(value);
        }
    }

    private static Map<String, Object> literalEnumSchema(Object value) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "enum");
        List<Object> values = new ArrayList<>();
        values.add(value);
        schema.put("values", values);
        return schema;
    }

    private static List<Object> enumValues(Map<String, Object> schema) {
        if (schema.containsKey("const")) {
            List<Object> values = new ArrayList<>();
            values.add(schema.get("const"));
            return values;
        }
        Object rawEnum = schema.get("enum");
        if (rawEnum instanceof List<?> values) {
            return values.stream().map(Object.class::cast).distinct().toList();
        }
        if ("enum".equals(schemaType(schema)) && schema.get("values") instanceof List<?> values) {
            return values.stream().map(Object.class::cast).distinct().toList();
        }
        return List.of();
    }

    private static String appendCompatibilityPath(String path, String segment) {
        if (path == null || path.isBlank()) {
            return segment;
        }
        return path + "." + segment;
    }

    private static String reasonAt(String path, String reason) {
        return path == null || path.isBlank() ? reason : "at '%s': %s".formatted(path, reason);
    }

    private static String valueDomainLabel(List<Object> values) {
        return values.stream().map(String::valueOf).toList().toString();
    }

    private static boolean numeric(String type) {
        return "number".equals(type) || "integer".equals(type) || "decimal".equals(type);
    }

    private static boolean valueMatchesType(Object value, String type) {
        return switch (type) {
            case "string", "duration", "datetime" -> value instanceof String;
            case "integer" -> isIntegerValue(value);
            case "number", "decimal" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true;
        };
    }

    private static boolean valueMatchesSchema(Object value, Map<String, Object> schema) {
        String type = schemaType(schema);
        if (!valueMatchesType(value, type)) {
            return false;
        }
        List<Object> values = enumValues(schema);
        if (!values.isEmpty() && !values.contains(value)) {
            return false;
        }
        return numericValueMatchesBounds(value, schema);
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

    private static List<String> requiredNamesOf(Map<String, Object> schema) {
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

    private static Map<String, Object> propertiesOf(Map<String, Object> schema) {
        Object nested = schema.get("properties");
        if (!(nested instanceof Map<?, ?> rawNested)) {
            return Map.of();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        rawNested.forEach((key, item) -> properties.put(String.valueOf(key), item));
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

    private static String schemaType(Map<String, Object> property) {
        if (property == null) {
            return "";
        }
        Object type = property.get("kind");
        if (type == null) {
            type = property.get("type");
        }
        if (type == null && property.containsKey("properties")) {
            return "object";
        }
        if (type == null && property.containsKey("items")) {
            return "array";
        }
        if (type == null && property.containsKey("const")) {
            return schemaTypeForValue(property.get("const"));
        }
        return type == null ? "" : String.valueOf(type);
    }

    private static String schemaTypeForValue(Object value) {
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
        if (!(value instanceof Number number)) {
            return null;
        }
        double numericValue = number.doubleValue();
        return Double.isFinite(numericValue) ? new NumericBoundary(numericValue, exclusive) : null;
    }

    private static boolean lowerBoundAtLeast(NumericBoundary source, NumericBoundary target) {
        int comparison = Double.compare(source.value(), target.value());
        return comparison > 0 || comparison == 0 && (source.exclusive() || !target.exclusive());
    }

    private static boolean upperBoundAtMost(NumericBoundary source, NumericBoundary target) {
        int comparison = Double.compare(source.value(), target.value());
        return comparison < 0 || comparison == 0 && (source.exclusive() || !target.exclusive());
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

    /**
     * Static expression literal with its derived schema.
     *
     * @param label literal expression text
     * @param schema single-value schema
     */
    public record StaticExpressionLiteral(String label, Map<String, Object> schema) {
    }
}
