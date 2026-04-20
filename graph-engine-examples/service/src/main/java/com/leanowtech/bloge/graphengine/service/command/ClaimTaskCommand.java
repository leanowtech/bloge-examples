package com.leanowtech.bloge.graphengine.service.command;

/**
 * Command that claims one human task for a concrete user.
 */
public record ClaimTaskCommand(
        String taskId,
        String userId
) {
    public ClaimTaskCommand {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
    }
}
