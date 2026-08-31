package com.leanowtech.bloge.gateway.visual.authoring.flow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, topologically ordered Flow plan shared by save, simulation and publication. */
public record CompiledReusableFlow(ReusableFlowCommand command,
                                   Map<String, ComposableDefinition> nodes,
                                   Map<String, List<String>> dependencies,
                                   List<String> topologicalNodeIds) {
    public CompiledReusableFlow {
        command = Objects.requireNonNull(command, "command");
        nodes = Map.copyOf(new LinkedHashMap<>(nodes));
        LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
        dependencies.forEach((nodeId, values) -> copied.put(nodeId, List.copyOf(values)));
        dependencies = Map.copyOf(copied);
        topologicalNodeIds = List.copyOf(topologicalNodeIds);
    }
}
