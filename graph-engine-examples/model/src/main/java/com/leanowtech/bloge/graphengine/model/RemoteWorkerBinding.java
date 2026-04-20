package com.leanowtech.bloge.graphengine.model;

import java.util.Map;

/**
 * Resolves a logical remote worker topic or endpoint for one operator reference.
 *
 * @param workerId stable worker binding identifier
 * @param topic    queue or push topic used for remote dispatch
 * @param endpoint optional polling endpoint for worker registration flows
 * @param labels   free-form labels used by the control plane
 */
public record RemoteWorkerBinding(
        String workerId,
        String topic,
        String endpoint,
        Map<String, String> labels
) {
    public RemoteWorkerBinding {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if ((topic == null || topic.isBlank()) && (endpoint == null || endpoint.isBlank())) {
            throw new IllegalArgumentException("either topic or endpoint must be provided");
        }
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}
