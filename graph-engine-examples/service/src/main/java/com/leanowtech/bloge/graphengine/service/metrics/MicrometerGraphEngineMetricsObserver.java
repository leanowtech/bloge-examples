package com.leanowtech.bloge.graphengine.service.metrics;

import com.leanowtech.bloge.graphengine.service.GraphEngineMetricsObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final ConcurrentMap<GaugeKey, AtomicInteger> gauges = new ConcurrentHashMap<>();

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

    @Override
    public void onOperationsSnapshot(String tenantId, String namespace, String health,
                                     int deadLetterCount, int failedInstanceCount,
                                     int suspendedInstanceCount, int activeDeploymentCount,
                                     boolean truncated, boolean controlPlaneAvailable) {
        onOperationsSnapshot(
                tenantId,
                namespace,
                health,
                deadLetterCount,
                failedInstanceCount,
                suspendedInstanceCount,
                activeDeploymentCount,
                truncated,
                controlPlaneAvailable,
                0,
                0
        );
    }

    @Override
    public void onOperationsSnapshot(String tenantId, String namespace, String health,
                                     int deadLetterCount, int failedInstanceCount,
                                     int suspendedInstanceCount, int activeDeploymentCount,
                                     boolean truncated, boolean controlPlaneAvailable,
                                     int deadLetterOldestAgeSeconds,
                                     int suspendedOldestAgeSeconds) {
        setGauge("operations.health", tenantId, namespace, healthScore(health));
        setGauge("operations.dead_letters", tenantId, namespace, deadLetterCount);
        setGauge("operations.failed_instances", tenantId, namespace, failedInstanceCount);
        setGauge("operations.suspended_instances", tenantId, namespace, suspendedInstanceCount);
        setGauge("operations.active_deployments", tenantId, namespace, activeDeploymentCount);
        setGauge("operations.snapshot_truncated", tenantId, namespace, truncated ? 1 : 0);
        setGauge("operations.control_plane_available", tenantId, namespace, controlPlaneAvailable ? 1 : 0);
        setGauge("operations.dead_letter_oldest_age_seconds", tenantId, namespace, deadLetterOldestAgeSeconds);
        setGauge("operations.suspended_oldest_age_seconds", tenantId, namespace, suspendedOldestAgeSeconds);
    }

    private void setGauge(String suffix, String tenantId, String namespace, int value) {
        GaugeKey key = new GaugeKey(prefix + "." + suffix, safeTag(tenantId), safeTag(namespace));
        gauges.computeIfAbsent(key, this::registerGauge).set(value);
    }

    private AtomicInteger registerGauge(GaugeKey key) {
        AtomicInteger holder = new AtomicInteger();
        Gauge.builder(key.name(), holder, AtomicInteger::get)
                .tag("tenant", key.tenantId())
                .tag("namespace", key.namespace())
                .register(registry);
        return holder;
    }

    private static int healthScore(String health) {
        String normalized = health == null ? "OK" : health.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CRITICAL" -> 2;
            case "WARNING" -> 1;
            default -> 0;
        };
    }

    private static String safeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private record GaugeKey(String name, String tenantId, String namespace) {
    }
}
