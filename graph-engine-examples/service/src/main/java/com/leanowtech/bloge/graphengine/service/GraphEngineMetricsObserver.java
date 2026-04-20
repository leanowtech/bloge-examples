package com.leanowtech.bloge.graphengine.service;

/**
 * SPI for observing graph-engine control-plane events and recording product-layer
 * metrics (prefix {@code ge.*}).
 * <p>
 * Implementations translate the low-cardinality tag values supplied by the service
 * layer into the metric backend of choice (e.g. Micrometer, OpenTelemetry).
 * <p>
 * All method parameters are pre-resolved, low-cardinality strings — never instance
 * IDs, version IDs, or task IDs.
 * <p>
 * Metrics covered:
 * <ul>
 *   <li>{@code ge.version.published} — Counter</li>
 *   <li>{@code ge.instance.started}  — Counter</li>
 *   <li>{@code ge.instance.completed} — Counter (with status tag)</li>
 *   <li>{@code ge.task.claimed}      — Counter</li>
 *   <li>{@code ge.task.completed}    — Counter</li>
 * </ul>
 */
public interface GraphEngineMetricsObserver {

    /** A no-op singleton that silently discards every event. */
    GraphEngineMetricsObserver NOOP = new GraphEngineMetricsObserver() {
        @Override public void onVersionPublished(String definitionKey, String tenantId, String namespace) {}
        @Override public void onInstanceStarted(String definitionKey, String tenantId, String namespace, String executionMode) {}
        @Override public void onInstanceCompleted(String definitionKey, String tenantId, String namespace, String executionMode, String status) {}
        @Override public void onTaskClaimed(String definitionKey, String tenantId, String namespace, String nodeId) {}
        @Override public void onTaskCompleted(String definitionKey, String tenantId, String namespace, String nodeId) {}
    };

    /**
     * Called when a graph definition version is published.
     * <p>
     * Maps to the {@code ge.version.published} counter with tags
     * {@code definition}, {@code tenant}, and {@code namespace}.
     *
     * @param definitionKey the business key of the graph definition
     * @param tenantId      the tenant identifier
     * @param namespace     the namespace
     */
    void onVersionPublished(String definitionKey, String tenantId, String namespace);

    /**
     * Called when a graph instance starts execution.
     * <p>
     * Maps to the {@code ge.instance.started} counter with tags
     * {@code definition}, {@code tenant}, {@code namespace}, and {@code mode}.
     *
     * @param definitionKey the business key of the graph definition
     * @param tenantId      the tenant identifier
     * @param namespace     the namespace
     * @param executionMode the execution mode (GRAPH, SESSION, STATE_MACHINE)
     */
    void onInstanceStarted(String definitionKey, String tenantId, String namespace, String executionMode);

    /**
     * Called when a graph instance reaches a terminal state.
     * <p>
     * Maps to the {@code ge.instance.completed} counter with tags
     * {@code definition}, {@code tenant}, {@code namespace}, {@code mode}, and {@code status}.
     *
     * @param definitionKey the business key of the graph definition
     * @param tenantId      the tenant identifier
     * @param namespace     the namespace
     * @param executionMode the execution mode (GRAPH, SESSION, STATE_MACHINE)
     * @param status        the terminal status (COMPLETED, FAILED, CANCELLED, TERMINATED)
     */
    void onInstanceCompleted(String definitionKey, String tenantId, String namespace,
                             String executionMode, String status);

    /**
     * Called when a task is claimed by a worker.
     * <p>
     * Maps to the {@code ge.task.claimed} counter with tags
     * {@code definition}, {@code tenant}, {@code namespace}, and {@code node}.
     *
     * @param definitionKey the business key of the graph definition
     * @param tenantId      the tenant identifier
     * @param namespace     the namespace
     * @param nodeId        the node identifier within the graph
     */
    void onTaskClaimed(String definitionKey, String tenantId, String namespace, String nodeId);

    /**
     * Called when a task completes execution.
     * <p>
     * Maps to the {@code ge.task.completed} counter with tags
     * {@code definition}, {@code tenant}, {@code namespace}, and {@code node}.
     *
     * @param definitionKey the business key of the graph definition
     * @param tenantId      the tenant identifier
     * @param namespace     the namespace
     * @param nodeId        the node identifier within the graph
     */
    void onTaskCompleted(String definitionKey, String tenantId, String namespace, String nodeId);
}
