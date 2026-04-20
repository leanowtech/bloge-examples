package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.schema.SchemaDescriptor;

import java.util.Map;
import java.util.Objects;
import java.util.List;

/**
 * Immutable metadata derived from a graph version's source and compiled artifact.
 *
 * @param executionMode        runtime family that executes the version
 * @param operatorRefs         operator references used by the version
 * @param operatorFingerprints stable operator fingerprints, keyed by node ID
 * @param inputSchema          published input contract, when known
 * @param outputSchema         published output contract, when known
 * @param taskDefinitions      human-task definitions keyed by node ID
 * @param migrationHints       free-form migration hints for control-plane tooling
 */
public record GraphVersionMetadata(
        GraphExecutionMode executionMode,
        List<String> operatorRefs,
        Map<String, String> operatorFingerprints,
        SchemaDescriptor inputSchema,
        SchemaDescriptor outputSchema,
        Map<String, TaskDefinition> taskDefinitions,
        Map<String, Object> migrationHints
) {
    public GraphVersionMetadata {
        executionMode = Objects.requireNonNullElse(executionMode, GraphExecutionMode.GRAPH);
        operatorRefs = operatorRefs == null ? List.of() : List.copyOf(operatorRefs);
        operatorFingerprints = operatorFingerprints == null ? Map.of() : Map.copyOf(operatorFingerprints);
        taskDefinitions = taskDefinitions == null ? Map.of() : Map.copyOf(taskDefinitions);
        migrationHints = migrationHints == null ? Map.of() : Map.copyOf(migrationHints);
    }
}
