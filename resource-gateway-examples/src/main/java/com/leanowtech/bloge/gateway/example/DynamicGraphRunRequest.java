package com.leanowtech.bloge.gateway.example;

import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Browser-submitted graph composition request.
 *
 * @param dsl BLOGE DSL source
 * @param context initial graph context values
 * @param outputNode optional node whose output should be promoted in the response
 * @param runIntent optional deadline and fenced cancellation intent
 * @param admittedResources exact internal resource descriptors admitted for this execution
 */
public record DynamicGraphRunRequest(
        String dsl,
        Map<String, Object> context,
        String outputNode,
        DynamicRunIntent runIntent,
        Map<String, ResourceDescriptor> admittedResources
) {
    /**
     * Creates a dynamic run request.
     */
    public DynamicGraphRunRequest {
        dsl = dsl == null ? "" : dsl;
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        outputNode = outputNode == null ? "" : outputNode;
        runIntent = runIntent == null ? DynamicRunIntent.unmanaged() : runIntent;
        admittedResources = admittedResources == null ? Map.of() : Map.copyOf(admittedResources);
    }

    /** Backward-compatible request without resource admission. */
    public DynamicGraphRunRequest(String dsl,
                                  Map<String, Object> context,
                                  String outputNode,
                                  DynamicRunIntent runIntent) {
        this(dsl, context, outputNode, runIntent, Map.of());
    }

    /** Backward-compatible unmanaged request. */
    public DynamicGraphRunRequest(String dsl, Map<String, Object> context, String outputNode) {
        this(dsl, context, outputNode, DynamicRunIntent.unmanaged(), Map.of());
    }
}
