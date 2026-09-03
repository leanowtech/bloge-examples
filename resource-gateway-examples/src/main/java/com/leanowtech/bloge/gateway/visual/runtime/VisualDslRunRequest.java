package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;

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
 * @param runIntent optional deadline and fenced cancellation intent
 * @param admittedResources exact resource descriptors admitted for this internal execution
 */
public record VisualDslRunRequest(
        String dsl,
        Map<String, Object> context,
        String outputNode,
        VisualRunIntent runIntent,
        Map<String, ResourceDescriptor> admittedResources
) {
    /**
     * Creates a normalized DSL run request.
     */
    public VisualDslRunRequest {
        dsl = dsl == null ? "" : dsl;
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        outputNode = outputNode == null ? "" : outputNode;
        runIntent = runIntent == null ? VisualRunIntent.unmanaged() : runIntent;
        admittedResources = admittedResources == null ? Map.of() : Map.copyOf(admittedResources);
    }

    /** Backward-compatible request without resource admission. */
    public VisualDslRunRequest(String dsl,
                               Map<String, Object> context,
                               String outputNode,
                               VisualRunIntent runIntent) {
        this(dsl, context, outputNode, runIntent, Map.of());
    }

    /** Backward-compatible unmanaged request. */
    public VisualDslRunRequest(String dsl, Map<String, Object> context, String outputNode) {
        this(dsl, context, outputNode, VisualRunIntent.unmanaged(), Map.of());
    }
}
