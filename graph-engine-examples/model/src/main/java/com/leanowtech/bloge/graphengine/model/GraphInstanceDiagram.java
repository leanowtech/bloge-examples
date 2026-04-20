package com.leanowtech.bloge.graphengine.model;

import java.util.List;
import java.util.Objects;

/**
 * Diagram payload for one concrete instance, combining stored layout data with
 * the current node-state overlay.
 *
 * @param instanceId instance identifier
 * @param versionId pinned version identifier
 * @param visualLayout stored visual layout payload as-is
 * @param nodeStates inferred execution-node states for the instance
 */
public record GraphInstanceDiagram(
        String instanceId,
        String versionId,
        String visualLayout,
        List<GraphNodeState> nodeStates
) {
    public GraphInstanceDiagram {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(versionId, "versionId");
        nodeStates = nodeStates == null ? List.of() : List.copyOf(nodeStates);
    }
}
