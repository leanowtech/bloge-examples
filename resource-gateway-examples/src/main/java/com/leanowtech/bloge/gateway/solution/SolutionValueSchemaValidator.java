package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Bounded validator for the intentionally small scalar schema vocabulary of Solution entities. */
public final class SolutionValueSchemaValidator {
    private SolutionValueSchemaValidator() { }

    /** Returns whether an object has exactly the declared required scalar fields and value types. */
    static boolean inputObjectMatches(JsonNode declarations, JsonNode value) {
        if (declarations == null || !declarations.isObject() || value == null || !value.isObject()) return false;
        Set<String> declared = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = declarations.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            declared.add(field.getKey());
            if (!value.has(field.getKey()) || !matchesType(field.getValue(), value.path(field.getKey()))) {
                return false;
            }
        }
        Set<String> supplied = new HashSet<>();
        value.fieldNames().forEachRemaining(supplied::add);
        return supplied.equals(declared);
    }

    /** Returns whether one atomic Feature value satisfies its declared type or enum. */
    public static boolean featureValueMatches(JsonNode output, JsonNode value) {
        return output != null && output.isObject() && matchesType(output.path("type"), value);
    }

    private static boolean matchesType(JsonNode declaration, JsonNode value) {
        if (declaration == null || declaration.isMissingNode() || value == null || value.isMissingNode()) return false;
        if (declaration.isTextual()) {
            return switch (declaration.asText().trim().toLowerCase(java.util.Locale.ROOT)) {
                case "string" -> value.isTextual();
                case "boolean" -> value.isBoolean();
                case "number", "decimal" -> value.isNumber();
                case "integer" -> value.isIntegralNumber();
                default -> false;
            };
        }
        if (!declaration.isObject() || !declaration.path("enum").isArray()
                || declaration.path("enum").isEmpty()) return false;
        for (JsonNode candidate : declaration.path("enum")) {
            if (candidate.equals(value) || candidate.isNumber() && value.isNumber()
                    && new BigDecimal(candidate.asText()).compareTo(new BigDecimal(value.asText())) == 0) {
                return true;
            }
        }
        return false;
    }
}
