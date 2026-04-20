package com.leanowtech.bloge.graphengine.service.command;

/**
 * Command that completes a human task and carries its output payload.
 */
public record CompleteTaskCommand(
        String taskId,
        Object output,
        String userId
) {
    public CompleteTaskCommand {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
    }
}
