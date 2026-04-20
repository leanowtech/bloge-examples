package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.schema.SchemaDescriptor;
import com.leanowtech.bloge.core.spi.SystemTimeSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Product-layer task view that enriches a durable task inbox entry.
 *
 * @param taskId task identifier shared with the durable inbox
 * @param instanceId owning graph instance identifier
 * @param definitionKey owning definition key
 * @param nodeId node that created the task
 * @param taskType task category
 * @param title display title
 * @param assignee explicit assignee, when one exists
 * @param candidateUsers candidate users allowed to claim the task
 * @param candidateGroups candidate groups allowed to claim the task
 * @param candidateRoles candidate roles allowed to claim the task
 * @param formRef optional external form reference
 * @param payloadSchema optional payload schema
 * @param formData structured task payload
 * @param priority relative priority
 * @param dueDate optional due date
 * @param slaDeadline optional SLA deadline
 * @param status task status
 * @param revision optimistic-lock revision
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 * @param completedAt completion timestamp when present
 */
public record GraphTask(
        String taskId,
        String instanceId,
        String definitionKey,
        String nodeId,
        String taskType,
        String title,
        String assignee,
        List<String> candidateUsers,
        List<String> candidateGroups,
        List<String> candidateRoles,
        String formRef,
        SchemaDescriptor payloadSchema,
        Map<String, Object> formData,
        int priority,
        Instant dueDate,
        Instant slaDeadline,
        GraphTaskStatus status,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
    public GraphTask {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new IllegalArgumentException("definitionKey must not be blank");
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (taskType == null || taskType.isBlank()) {
            throw new IllegalArgumentException("taskType must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        candidateUsers = candidateUsers == null ? List.of() : List.copyOf(candidateUsers);
        candidateGroups = candidateGroups == null ? List.of() : List.copyOf(candidateGroups);
        candidateRoles = candidateRoles == null ? List.of() : List.copyOf(candidateRoles);
        formData = formData == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(formData));
        status = Objects.requireNonNullElse(status, GraphTaskStatus.OPEN);
        if (priority < 0) {
            throw new IllegalArgumentException("priority must be >= 0");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        createdAt = createdAt == null ? SystemTimeSource.INSTANCE.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
