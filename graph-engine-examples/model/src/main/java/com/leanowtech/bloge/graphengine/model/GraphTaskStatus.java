package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.runtime.task.TaskInboxStatus;

/**
 * Product-layer task states projected from the durable task inbox.
 */
public enum GraphTaskStatus {
    OPEN,
    CLAIMED,
    COMPLETED,
    CANCELLED,
    EXPIRED;

    /**
     * Maps a durable inbox status to the product-layer task status model.
     *
     * @param status inbox status to map
     * @return projected task status
     */
    public static GraphTaskStatus fromTaskInboxStatus(TaskInboxStatus status) {
        return GraphTaskStatus.valueOf(status.name());
    }
}
