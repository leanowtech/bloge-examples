package com.leanowtech.bloge.gateway.example;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Browser-submitted graph composition request.
 *
 * @param dsl BLOGE DSL source
 * @param context initial graph context values
 * @param outputNode optional node whose output should be promoted in the response
 */
public record DynamicGraphRunRequest(
        String dsl,
        Map<String, Object> context,
        String outputNode
) {
    /**
     * Creates a dynamic run request.
     */
    public DynamicGraphRunRequest {
        dsl = dsl == null ? "" : dsl;
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        outputNode = outputNode == null ? "" : outputNode;
    }
}
