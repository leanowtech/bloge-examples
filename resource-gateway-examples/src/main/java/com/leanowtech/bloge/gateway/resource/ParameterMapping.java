package com.leanowtech.bloge.gateway.resource;

import java.util.Map;

/**
 * Maps bloge DSL expressions to HTTP request components (path variables, query parameters,
 * and request body).
 *
 * <p>Each expression string is a bloge expression that will be evaluated against the
 * operator input context at call time via {@code BlgeExpressionEvaluator}.
 *
 * @param pathExpressions  maps URL path-variable names to bloge expressions
 *                         (e.g. {@code "userId" -> "ctx.params.userId"})
 * @param queryExpressions maps query-parameter names to bloge expressions
 *                         (e.g. {@code "page" -> "ctx.params.page"})
 * @param bodyExpression   bloge expression that evaluates to the request body object,
 *                         or {@code null} if no body is needed
 */
public record ParameterMapping(
    Map<String, String> pathExpressions,
    Map<String, String> queryExpressions,
    String bodyExpression
) {
    public ParameterMapping {
        pathExpressions = pathExpressions == null ? Map.of() : Map.copyOf(pathExpressions);
        queryExpressions = queryExpressions == null ? Map.of() : Map.copyOf(queryExpressions);
    }

    /**
     * Returns an empty mapping with no path, query, or body expressions.
     */
    public static ParameterMapping empty() {
        return new ParameterMapping(Map.of(), Map.of(), null);
    }

    /**
     * Returns a mapping that only specifies a body expression.
     *
     * @param expr the bloge expression for the request body
     */
    public static ParameterMapping body(String expr) {
        return new ParameterMapping(Map.of(), Map.of(), expr);
    }
}
