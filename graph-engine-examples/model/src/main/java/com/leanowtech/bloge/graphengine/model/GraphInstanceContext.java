package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.spi.SystemTimeSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Product-layer snapshot of the business-visible context for one instance.
 *
 * @param instanceId execution identifier shared with the durable runtime
 * @param executionMode runtime family that executes the instance
 * @param startVariables start variables captured by the control plane
 * @param nodeOutputs decoded graph node outputs keyed by node identifier
 * @param sharedState session or state-machine shared state visible to business callers
 * @param phaseOutputs session phase outputs keyed by phase identifier
 * @param stateOutputs state-machine outputs keyed by state identifier
 * @param snapshotAt timestamp when the projection was assembled
 */
public record GraphInstanceContext(
        String instanceId,
        GraphExecutionMode executionMode,
        Map<String, Object> startVariables,
        Map<String, Map<String, Object>> nodeOutputs,
        Map<String, Object> sharedState,
        Map<String, Object> phaseOutputs,
        Map<String, Map<String, Object>> stateOutputs,
        Instant snapshotAt
) {
    public GraphInstanceContext {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        executionMode = Objects.requireNonNullElse(executionMode, GraphExecutionMode.GRAPH);
        startVariables = immutableMap(startVariables);
        nodeOutputs = immutableNestedMap(nodeOutputs);
        sharedState = immutableMap(sharedState);
        phaseOutputs = immutableMap(phaseOutputs);
        stateOutputs = immutableNestedMap(stateOutputs);
        snapshotAt = snapshotAt == null ? SystemTimeSource.INSTANCE.now() : snapshotAt;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return source == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(source));
    }

    private static Map<String, Map<String, Object>> immutableNestedMap(Map<String, Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableMap(value)));
        return Map.copyOf(copy);
    }
}
