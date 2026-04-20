package com.leanowtech.bloge.graphengine.service.command;

/**
 * Command that reassigns one human task.
 */
public record ReassignTaskCommand(
        String taskId,
        String newAssignee
) {
    public ReassignTaskCommand {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (newAssignee == null || newAssignee.isBlank()) {
            throw new IllegalArgumentException("newAssignee must not be blank");
        }
    }
}
