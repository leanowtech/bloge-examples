package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.schema.SchemaDescriptor;

import java.util.List;

/**
 * Declares human-task metadata derived from a published graph version.
 *
 * @param nodeId           node that creates the task
 * @param taskType         task category visible to callers and inbox queries
 * @param formRef          external form reference, when one exists
 * @param defaultAssignee  default assignee resolved at publish time, when present
 * @param candidateGroups  candidate groups allowed to claim the task
 * @param candidateRoles   candidate roles allowed to claim the task
 * @param payloadSchema    optional schema for task form/output payloads
 */
public record TaskDefinition(
        String nodeId,
        String taskType,
        String formRef,
        String defaultAssignee,
        List<String> candidateGroups,
        List<String> candidateRoles,
        SchemaDescriptor payloadSchema
) {
    public TaskDefinition {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (taskType == null || taskType.isBlank()) {
            throw new IllegalArgumentException("taskType must not be blank");
        }
        candidateGroups = candidateGroups == null ? List.of() : List.copyOf(candidateGroups);
        candidateRoles = candidateRoles == null ? List.of() : List.copyOf(candidateRoles);
    }
}
