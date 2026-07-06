package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.List;

/**
 * Stable authoring catalog for BLOGE expression functions exposed in visual editors.
 */
public final class BuiltInFunctionCatalog {

    private BuiltInFunctionCatalog() {
    }

    /**
     * @return default BLOGE expression functions available to transform and branch expressions
     */
    public static List<OperatorLibrary.BuiltInFunction> defaults() {
        return List.of(
                function("coalesce", "null-handling",
                        "Returns the first non-null argument.",
                        signature("coalesce(value, fallback)", "any",
                                param("value", "any", false, false),
                                param("fallback", "any", false, false)),
                        "coalesce(inputs.primaryScore, 0)"),
                function("defaultIfBlank", "string",
                        "Returns fallback when text is null or blank.",
                        signature("defaultIfBlank(text, fallback)", "string",
                                param("text", "string", false, false),
                                param("fallback", "string", false, false)),
                        "defaultIfBlank(inputs.reason, \"n/a\")"),
                function("toNumber", "conversion",
                        "Converts a scalar value to a number.",
                        signature("toNumber(value)", "number",
                                param("value", "any", false, false)),
                        "toNumber(inputs.amount)"),
                function("toString", "conversion",
                        "Converts a scalar value to a string.",
                        signature("toString(value)", "string",
                                param("value", "any", false, false)),
                        "toString(inputs.ruleId)"),
                function("jsonPath", "object",
                        "Reads a value from an object by JSONPath-like path.",
                        signature("jsonPath(object, path, fallback?)", "any",
                                param("object", "object", false, false),
                                param("path", "string", false, false),
                                param("fallback", "any", true, false)),
                        "jsonPath(inputs.profile, \"$.address.city\", \"unknown\")"),
                function("contains", "collection",
                        "Returns true when a string or collection contains the candidate.",
                        signature("contains(collection, candidate)", "boolean",
                                param("collection", "any", false, false),
                                param("candidate", "any", false, false)),
                        "contains(inputs.tags, \"vip\")"),
                function("round", "number",
                        "Rounds a number to an optional scale.",
                        signature("round(value, scale?)", "number",
                                param("value", "number", false, false),
                                param("scale", "integer", true, false)),
                        "round(inputs.score, 2)"),
                function("formatDate", "date",
                        "Formats a date/time value with a pattern.",
                        signature("formatDate(value, pattern)", "string",
                                param("value", "string", false, false),
                                param("pattern", "string", false, false)),
                        "formatDate(ctx.createdAt, \"yyyy-MM-dd\")")
        );
    }

    private static OperatorLibrary.BuiltInFunction function(String name,
                                                            String category,
                                                            String description,
                                                            OperatorLibrary.Signature signature,
                                                            String example) {
        return new OperatorLibrary.BuiltInFunction(
                name,
                "bloge",
                name,
                description,
                category,
                List.of(signature),
                List.of(example)
        );
    }

    private static OperatorLibrary.Signature signature(String label,
                                                       String returnType,
                                                       OperatorLibrary.Parameter... parameters) {
        return new OperatorLibrary.Signature(
                label,
                "",
                List.of(parameters),
                new OperatorLibrary.ReturnValue(returnType, null, "")
        );
    }

    private static OperatorLibrary.Parameter param(String name,
                                                   String type,
                                                   boolean optional,
                                                   boolean variadic) {
        return new OperatorLibrary.Parameter(name, type, null, optional, variadic, "");
    }
}
