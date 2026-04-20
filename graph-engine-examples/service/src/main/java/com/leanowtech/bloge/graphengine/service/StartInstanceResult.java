package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.graphengine.model.GraphInstance;

import java.util.Map;
import java.util.Objects;

/**
 * Result returned after starting a new product-layer graph instance.
 *
 * @param instance projected instance snapshot
 * @param suspendedNodes suspended node map returned by graph-mode execution, or empty when not applicable
 */
public record StartInstanceResult(
        GraphInstance instance,
        Map<String, String> suspendedNodes
) {
    public StartInstanceResult {
        instance = Objects.requireNonNull(instance, "instance");
        suspendedNodes = suspendedNodes == null ? Map.of() : Map.copyOf(suspendedNodes);
    }
}
