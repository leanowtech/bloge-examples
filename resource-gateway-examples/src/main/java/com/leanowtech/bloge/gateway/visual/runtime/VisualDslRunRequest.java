package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request passed from the visual orchestration core to a BLOGE DSL execution adapter.
 *
 * <p>The visual package owns this contract so runtime services do not depend on the concrete
 * resource-gateway composer implementation. Gateway-specific runners adapt this request to their
 * own transport or execution API.</p>
 *
 * @param dsl generated BLOGE DSL source
 * @param context initial graph context values
 * @param outputNode optional output node override
 */
public record VisualDslRunRequest(
        String dsl,
        Map<String, Object> context,
        String outputNode
) {
    /**
     * Creates a normalized DSL run request.
     */
    public VisualDslRunRequest {
        dsl = dsl == null ? "" : dsl;
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        outputNode = outputNode == null ? "" : outputNode;
    }
}
