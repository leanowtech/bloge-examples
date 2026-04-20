package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;

/**
 * Product-layer instance states derived from durable execution status.
 */
public enum GraphInstanceStatus {
    RUNNING,
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED,
    TERMINATED;

    /**
     * Maps a durable runtime status into the product-layer instance status model.
     *
     * @param status durable execution status
     * @return corresponding product-layer status
     */
    public static GraphInstanceStatus fromExecutionStatus(ExecutionStatus status) {
        return switch (status) {
            case RUNNING, PAUSED -> RUNNING;
            case SUSPENDED -> SUSPENDED;
            case COMPLETED -> COMPLETED;
            case FAILED, FAILED_RECOVERY -> FAILED;
            case CANCELLED -> CANCELLED;
            case TERMINATED -> TERMINATED;
        };
    }

    /**
     * Returns whether the status is terminal.
     *
     * @return {@code true} when the instance will no longer resume
     */
    public boolean terminal() {
        return this == COMPLETED
                || this == FAILED
                || this == CANCELLED
                || this == TERMINATED;
    }
}
