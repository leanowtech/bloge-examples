package com.leanowtech.bloge.graphengine.service.command;

/**
 * Command that delivers an external signal to an existing product-layer instance.
 */
public record SignalInstanceCommand(
        String instanceId,
        String nodeId,
        String eventName,
        Object payload,
        String callerId
) {
    public SignalInstanceCommand {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        if (nodeId != null && nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (eventName != null && eventName.isBlank()) {
            throw new IllegalArgumentException("eventName must not be blank");
        }
        if (callerId != null && callerId.isBlank()) {
            throw new IllegalArgumentException("callerId must not be blank");
        }
    }
}
