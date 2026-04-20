package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.schema.SchemaCompatibility;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;

import java.util.List;
import java.util.Objects;

/**
 * Structural comparison of the compiled metadata between two graph versions.
 *
 * @param executionModeChanged      whether the execution mode differs between left and right
 * @param leftExecutionMode         execution mode of the left version
 * @param rightExecutionMode        execution mode of the right version
 * @param addedOperators            operator references present in right but not left
 * @param removedOperators          operator references present in left but not right
 * @param changedOperatorFingerprints node IDs whose operator fingerprint changed between versions
 * @param inputSchemaChanged        whether the published input schema descriptor differs
 * @param outputSchemaChanged       whether the published output schema descriptor differs
 * @param inputCompatibility        schema-compatibility result from left input schema to right input schema
 * @param outputCompatibility       schema-compatibility result from left output schema to right output schema
 * @param addedTaskDefinitions      task definition node IDs present in right but not left
 * @param removedTaskDefinitions    task definition node IDs present in left but not right
 * @param summary                   human-readable change summary lines
 */
public record MetadataDiff(
        boolean executionModeChanged,
        GraphExecutionMode leftExecutionMode,
        GraphExecutionMode rightExecutionMode,
        List<String> addedOperators,
        List<String> removedOperators,
        List<String> changedOperatorFingerprints,
        boolean inputSchemaChanged,
        boolean outputSchemaChanged,
        SchemaCompatibility inputCompatibility,
        SchemaCompatibility outputCompatibility,
        List<String> addedTaskDefinitions,
        List<String> removedTaskDefinitions,
        List<String> summary
) {
    public MetadataDiff {
        leftExecutionMode = Objects.requireNonNullElse(leftExecutionMode, GraphExecutionMode.GRAPH);
        rightExecutionMode = Objects.requireNonNullElse(rightExecutionMode, GraphExecutionMode.GRAPH);
        addedOperators = addedOperators == null ? List.of() : List.copyOf(addedOperators);
        removedOperators = removedOperators == null ? List.of() : List.copyOf(removedOperators);
        changedOperatorFingerprints = changedOperatorFingerprints == null ? List.of() : List.copyOf(changedOperatorFingerprints);
        inputCompatibility = Objects.requireNonNullElse(inputCompatibility,
                new SchemaCompatibility.FullyCompatible("No input schema declared"));
        outputCompatibility = Objects.requireNonNullElse(outputCompatibility,
                new SchemaCompatibility.FullyCompatible("No output schema declared"));
        addedTaskDefinitions = addedTaskDefinitions == null ? List.of() : List.copyOf(addedTaskDefinitions);
        removedTaskDefinitions = removedTaskDefinitions == null ? List.of() : List.copyOf(removedTaskDefinitions);
        summary = summary == null ? List.of() : List.copyOf(summary);
    }

    /**
     * Returns {@code true} when the metadata is structurally identical between
     * left and right.
     *
     * @return {@code true} when no metadata field changed
     */
    public boolean unchanged() {
        return !executionModeChanged
                && addedOperators.isEmpty()
                && removedOperators.isEmpty()
                && changedOperatorFingerprints.isEmpty()
                && !inputSchemaChanged
                && !outputSchemaChanged
                && addedTaskDefinitions.isEmpty()
                && removedTaskDefinitions.isEmpty();
    }
}
