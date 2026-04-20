package com.leanowtech.bloge.graphengine.service.command;

/**
 * Command that registers one remote worker against the active tenant-scoped deployment bindings.
 */
public record RegisterRemoteWorkerCommand(
        String workerId,
        String workerTopic
) {
    public RegisterRemoteWorkerCommand {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (workerTopic == null || workerTopic.isBlank()) {
            throw new IllegalArgumentException("workerTopic must not be blank");
        }
    }
}
