package com.leanowtech.bloge.graphengine.service.metrics;

import com.leanowtech.bloge.graphengine.service.GraphEngineMetricsObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Default Micrometer implementation of {@link GraphEngineMetricsObserver}.
 * <p>
 * The graph-engine service emits low-cardinality control-plane callbacks
 * through {@link GraphEngineMetricsObserver}; this implementation translates
 * each callback into one Micrometer counter in the supplied
 * {@link MeterRegistry}. The implementation is packaged with the service SPI so
 * applications can enable the product-layer {@code ge.*} metrics without
 * depending on {@code bloge-metrics-otel}.
 */
public class MicrometerGraphEngineMetricsObserver implements GraphEngineMetricsObserver {

    private final MeterRegistry registry;
    private final String prefix;

    /**
     * Creates an observer that records metrics with the default {@code ge}
     * prefix.
     *
     * @param registry the target registry that receives the control-plane
     *                 counters
     */
    public MicrometerGraphEngineMetricsObserver(MeterRegistry registry) {
        this(registry, "ge");
    }

    /**
     * Creates an observer that records metrics with the supplied metric-name
     * prefix.
     *
     * @param registry the target registry that receives the control-plane
     *                 counters
     * @param prefix   the metric-name prefix; when {@code null}, the observer
     *                 falls back to {@code ge}
     */
    public MicrometerGraphEngineMetricsObserver(MeterRegistry registry, String prefix) {
        if (registry == null) {
            throw new IllegalArgumentException("MeterRegistry must not be null");
        }
        this.registry = registry;
        this.prefix = prefix != null ? prefix : "ge";
    }

    @Override
    public void onVersionPublished(String definitionKey, String tenantId, String namespace) {
        Counter.builder(prefix + ".version.published")
                .tag("definition", definitionKey)
                .tag("tenant", tenantId)
                .tag("namespace", namespace)
                .register(registry)
                .increment();
    }

    @Override
    public void onInstanceStarted(String definitionKey, String tenantId, String namespace,
                                  String executionMode) {
        Counter.builder(prefix + ".instance.started")
                .tag("definition", definitionKey)
                .tag("tenant", tenantId)
                .tag("namespace", namespace)
                .tag("mode", executionMode)
                .register(registry)
                .increment();
    }

    @Override
    public void onInstanceCompleted(String definitionKey, String tenantId, String namespace,
                                    String executionMode, String status) {
        Counter.builder(prefix + ".instance.completed")
                .tag("definition", definitionKey)
                .tag("tenant", tenantId)
                .tag("namespace", namespace)
                .tag("mode", executionMode)
                .tag("status", status)
                .register(registry)
                .increment();
    }

    @Override
    public void onTaskClaimed(String definitionKey, String tenantId, String namespace,
                              String nodeId) {
        Counter.builder(prefix + ".task.claimed")
                .tag("definition", definitionKey)
                .tag("tenant", tenantId)
                .tag("namespace", namespace)
                .tag("node", nodeId)
                .register(registry)
                .increment();
    }

    @Override
    public void onTaskCompleted(String definitionKey, String tenantId, String namespace,
                                String nodeId) {
        Counter.builder(prefix + ".task.completed")
                .tag("definition", definitionKey)
                .tag("tenant", tenantId)
                .tag("namespace", namespace)
                .tag("node", nodeId)
                .register(registry)
                .increment();
    }
}
