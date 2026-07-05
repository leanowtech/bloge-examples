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
 *   <li>{@code ge.operations.health} — Gauge (OK=0, WARNING=1, CRITICAL=2)</li>
 *   <li>{@code ge.operations.dead_letters} — Gauge</li>
 *   <li>{@code ge.operations.failed_instances} — Gauge</li>
 *   <li>{@code ge.operations.suspended_instances} — Gauge</li>
 *   <li>{@code ge.operations.active_deployments} — Gauge</li>
 *   <li>{@code ge.operations.snapshot_truncated} — Gauge (false=0, true=1)</li>
 *   <li>{@code ge.operations.control_plane_available} — Gauge (false=0, true=1)</li>
 *   <li>{@code ge.operations.dead_letter_oldest_age_seconds} — Gauge</li>
 *   <li>{@code ge.operations.suspended_oldest_age_seconds} — Gauge</li>
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

    /**
     * Called when the operations snapshot for a tenant/namespace scope is queried.
     * <p>
     * Implementations should record current-state gauges, not counters. The
     * snapshot query is the refresh boundary for these product-layer gauges.
     *
     * @param tenantId              the tenant identifier
     * @param namespace             the namespace
     * @param health                snapshot health (OK, WARNING, CRITICAL)
     * @param deadLetterCount       dead-letter backlog count in the sampled scope
     * @param failedInstanceCount   failed instance count in the sampled scope
     * @param suspendedInstanceCount suspended instance count in the sampled scope
     * @param activeDeploymentCount active deployment count in the sampled scope
     * @param truncated             whether the snapshot hit the sample limit
     * @param controlPlaneAvailable whether control-plane dead-letter data was available
     */
    default void onOperationsSnapshot(String tenantId, String namespace, String health,
                                      int deadLetterCount, int failedInstanceCount,
                                      int suspendedInstanceCount, int activeDeploymentCount,
                                      boolean truncated, boolean controlPlaneAvailable) {
    }

    /**
     * Called when the operations snapshot for a tenant/namespace scope is queried,
     * including age-based SLO signals.
     *
     * @param tenantId the tenant identifier
     * @param namespace the namespace
     * @param health snapshot health (OK, WARNING, CRITICAL)
     * @param deadLetterCount dead-letter backlog count in the sampled scope
     * @param failedInstanceCount failed instance count in the sampled scope
     * @param suspendedInstanceCount suspended instance count in the sampled scope
     * @param activeDeploymentCount active deployment count in the sampled scope
     * @param truncated whether the snapshot hit the sample limit
     * @param controlPlaneAvailable whether control-plane dead-letter data was available
     * @param deadLetterOldestAgeSeconds age in seconds of the oldest sampled dead letter
     * @param suspendedOldestAgeSeconds age in seconds of the oldest sampled suspended instance
     */
    default void onOperationsSnapshot(String tenantId, String namespace, String health,
                                      int deadLetterCount, int failedInstanceCount,
                                      int suspendedInstanceCount, int activeDeploymentCount,
                                      boolean truncated, boolean controlPlaneAvailable,
                                      int deadLetterOldestAgeSeconds,
                                      int suspendedOldestAgeSeconds) {
        onOperationsSnapshot(
                tenantId,
                namespace,
                health,
                deadLetterCount,
                failedInstanceCount,
                suspendedInstanceCount,
                activeDeploymentCount,
                truncated,
                controlPlaneAvailable
        );
    }
}
