package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.graphengine.model.GraphInstance;

import java.util.Objects;

/**
 * Result of retrying dead-lettered work items for a graph instance.
 *
 * @param instance         refreshed instance projection after the retry
 * @param retriedItemCount number of dead-lettered work items restored to {@code READY}
 */
public record RetryInstanceResult(
        GraphInstance instance,
        int retriedItemCount
) {
    public RetryInstanceResult {
        Objects.requireNonNull(instance, "instance");
        if (retriedItemCount < 0) {
            throw new IllegalArgumentException("retriedItemCount must be >= 0");
        }
    }
}
