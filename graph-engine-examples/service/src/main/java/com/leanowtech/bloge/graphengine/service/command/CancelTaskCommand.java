package com.leanowtech.bloge.graphengine.service.command;

/**
 * Command that cancels one human task.
 */
public record CancelTaskCommand(
        String taskId,
        String reason
) {
    public CancelTaskCommand {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
