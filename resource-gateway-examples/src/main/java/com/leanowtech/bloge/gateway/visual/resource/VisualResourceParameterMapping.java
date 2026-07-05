package com.leanowtech.bloge.gateway.visual.resource;

import java.util.Map;

/**
 * Visual-owned mapping from canvas resource inputs to HTTP request components.
 *
 * @param pathExpressions expressions for URL path variables
 * @param queryExpressions expressions for query parameters
 * @param headerExpressions expressions for request headers
 * @param cookieExpressions expressions for request cookies
 * @param bodyExpression expression for the request body, or {@code null} when absent
 */
public record VisualResourceParameterMapping(
        Map<String, String> pathExpressions,
        Map<String, String> queryExpressions,
        Map<String, String> headerExpressions,
        Map<String, String> cookieExpressions,
        String bodyExpression
) {
    public VisualResourceParameterMapping {
        pathExpressions = pathExpressions == null ? Map.of() : Map.copyOf(pathExpressions);
        queryExpressions = queryExpressions == null ? Map.of() : Map.copyOf(queryExpressions);
        headerExpressions = headerExpressions == null ? Map.of() : Map.copyOf(headerExpressions);
        cookieExpressions = cookieExpressions == null ? Map.of() : Map.copyOf(cookieExpressions);
    }

    /**
     * Backward-compatible constructor for mappings without cookies.
     *
     * @param pathExpressions expressions for URL path variables
     * @param queryExpressions expressions for query parameters
     * @param headerExpressions expressions for request headers
     * @param bodyExpression expression for the request body
     */
    public VisualResourceParameterMapping(Map<String, String> pathExpressions,
                                          Map<String, String> queryExpressions,
                                          Map<String, String> headerExpressions,
                                          String bodyExpression) {
        this(pathExpressions, queryExpressions, headerExpressions, Map.of(), bodyExpression);
    }

    /**
     * Backward-compatible constructor for mappings with only path/query/body entries.
     *
     * @param pathExpressions expressions for URL path variables
     * @param queryExpressions expressions for query parameters
     * @param bodyExpression expression for the request body
     */
    public VisualResourceParameterMapping(Map<String, String> pathExpressions,
                                          Map<String, String> queryExpressions,
                                          String bodyExpression) {
        this(pathExpressions, queryExpressions, Map.of(), Map.of(), bodyExpression);
    }

    /**
     * @return an empty mapping with no request component expressions
     */
    public static VisualResourceParameterMapping empty() {
        return new VisualResourceParameterMapping(Map.of(), Map.of(), Map.of(), Map.of(), null);
    }

    /**
     * @param expression expression for the request body
     * @return a mapping that only supplies a request body expression
     */
    public static VisualResourceParameterMapping body(String expression) {
        return new VisualResourceParameterMapping(Map.of(), Map.of(), Map.of(), Map.of(), expression);
    }
}
