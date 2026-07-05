package com.leanowtech.bloge.graphengine.service.metrics;

import com.leanowtech.bloge.graphengine.service.GraphEngineMetricsObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MicrometerGraphEngineMetricsObserverTest {

    private SimpleMeterRegistry registry;
    private MicrometerGraphEngineMetricsObserver observer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        observer = new MicrometerGraphEngineMetricsObserver(registry);
    }

    // ── version.published ────────────────────────────────────────────────

    @Test
    void versionPublished_incrementsCounter() {
        observer.onVersionPublished("order-flow", "acme", "prod");

        Counter counter = registry.find("ge.version.published")
                .tag("definition", "order-flow")
                .tag("tenant", "acme")
                .tag("namespace", "prod")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void versionPublished_multipleIncrements() {
        observer.onVersionPublished("order-flow", "acme", "prod");
        observer.onVersionPublished("order-flow", "acme", "prod");
        observer.onVersionPublished("order-flow", "acme", "prod");

        Counter counter = registry.find("ge.version.published")
                .tag("definition", "order-flow")
                .tag("tenant", "acme")
                .tag("namespace", "prod")
                .counter();
        assertNotNull(counter);
        assertEquals(3.0, counter.count());
    }

    // ── instance.started ─────────────────────────────────────────────────

    @Test
    void instanceStarted_incrementsCounter() {
        observer.onInstanceStarted("order-flow", "acme", "prod", "GRAPH");

        Counter counter = registry.find("ge.instance.started")
                .tag("definition", "order-flow")
                .tag("tenant", "acme")
                .tag("namespace", "prod")
                .tag("mode", "GRAPH")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void instanceStarted_multipleIncrements() {
        observer.onInstanceStarted("order-flow", "acme", "prod", "SESSION");
        observer.onInstanceStarted("order-flow", "acme", "prod", "SESSION");

        Counter counter = registry.find("ge.instance.started")
                .tag("definition", "order-flow")
                .tag("tenant", "acme")
                .tag("namespace", "prod")
                .tag("mode", "SESSION")
                .counter();
        assertNotNull(counter);
        assertEquals(2.0, counter.count());
    }

    // ── instance.completed ───────────────────────────────────────────────

    @Test
    void instanceCompleted_incrementsCounterWithStatus() {
        observer.onInstanceCompleted("order-flow", "acme", "prod", "GRAPH", "COMPLETED");

        Counter counter = registry.find("ge.instance.completed")
                .tag("definition", "order-flow")
                .tag("tenant", "acme")
                .tag("namespace", "prod")
                .tag("mode", "GRAPH")
                .tag("status", "COMPLETED")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void instanceCompleted_differentStatusesSeparateCounters() {
        observer.onInstanceCompleted("order-flow", "acme", "prod", "GRAPH", "COMPLETED");
        observer.onInstanceCompleted("order-flow", "acme", "prod", "GRAPH", "FAILED");

        Counter completed = registry.find("ge.instance.completed")
                .tag("status", "COMPLETED")
                .counter();
        Counter failed = registry.find("ge.instance.completed")
                .tag("status", "FAILED")
                .counter();

        assertNotNull(completed);
        assertNotNull(failed);
        assertEquals(1.0, completed.count());
        assertEquals(1.0, failed.count());
    }

    // ── task.claimed ─────────────────────────────────────────────────────

    @Test
    void taskClaimed_incrementsCounter() {
        observer.onTaskClaimed("order-flow", "acme", "prod", "approve-order");

        Counter counter = registry.find("ge.task.claimed")
                .tag("definition", "order-flow")
                .tag("tenant", "acme")
                .tag("namespace", "prod")
                .tag("node", "approve-order")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void taskClaimed_multipleIncrements() {
        observer.onTaskClaimed("order-flow", "acme", "prod", "approve-order");
        observer.onTaskClaimed("order-flow", "acme", "prod", "approve-order");

        Counter counter = registry.find("ge.task.claimed")
                .tag("node", "approve-order")
                .counter();
        assertNotNull(counter);
        assertEquals(2.0, counter.count());
    }

    // ── task.completed ───────────────────────────────────────────────────

    @Test
    void taskCompleted_incrementsCounter() {
        observer.onTaskCompleted("order-flow", "acme", "prod", "approve-order");

        Counter counter = registry.find("ge.task.completed")
                .tag("definition", "order-flow")
                .tag("tenant", "acme")
                .tag("namespace", "prod")
                .tag("node", "approve-order")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void taskCompleted_multipleIncrements() {
        observer.onTaskCompleted("order-flow", "acme", "prod", "approve-order");
        observer.onTaskCompleted("order-flow", "acme", "prod", "approve-order");
        observer.onTaskCompleted("order-flow", "acme", "prod", "approve-order");

        Counter counter = registry.find("ge.task.completed")
                .tag("node", "approve-order")
                .counter();
        assertNotNull(counter);
        assertEquals(3.0, counter.count());
    }

    // ── operations snapshot gauges ──────────────────────────────────────

    @Test
    void operationsSnapshot_recordsCurrentStateGauges() {
        observer.onOperationsSnapshot("acme", "prod", "CRITICAL",
                2, 1, 3, 0, true, false);

        assertGauge("ge.operations.health", "acme", "prod", 2.0);
        assertGauge("ge.operations.dead_letters", "acme", "prod", 2.0);
        assertGauge("ge.operations.failed_instances", "acme", "prod", 1.0);
        assertGauge("ge.operations.suspended_instances", "acme", "prod", 3.0);
        assertGauge("ge.operations.active_deployments", "acme", "prod", 0.0);
        assertGauge("ge.operations.snapshot_truncated", "acme", "prod", 1.0);
        assertGauge("ge.operations.control_plane_available", "acme", "prod", 0.0);
    }

    @Test
    void operationsSnapshot_refreshesGaugeValuesInsteadOfAccumulating() {
        observer.onOperationsSnapshot("acme", "prod", "CRITICAL",
                2, 1, 3, 0, true, false);
        observer.onOperationsSnapshot("acme", "prod", "OK",
                0, 0, 0, 2, false, true);

        assertGauge("ge.operations.health", "acme", "prod", 0.0);
        assertGauge("ge.operations.dead_letters", "acme", "prod", 0.0);
        assertGauge("ge.operations.failed_instances", "acme", "prod", 0.0);
        assertGauge("ge.operations.suspended_instances", "acme", "prod", 0.0);
        assertGauge("ge.operations.active_deployments", "acme", "prod", 2.0);
        assertGauge("ge.operations.snapshot_truncated", "acme", "prod", 0.0);
        assertGauge("ge.operations.control_plane_available", "acme", "prod", 1.0);
    }

    // ── Custom prefix ────────────────────────────────────────────────────

    @Test
    void customPrefix_used() {
        var custom = new MicrometerGraphEngineMetricsObserver(registry, "myapp.ge");
        custom.onInstanceStarted("order-flow", "acme", "prod", "GRAPH");

        Counter counter = registry.find("myapp.ge.instance.started")
                .tag("definition", "order-flow")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());

        custom.onOperationsSnapshot("acme", "prod", "WARNING",
                0, 0, 1, 1, false, true);
        assertGauge("myapp.ge.operations.health", "acme", "prod", 1.0);
    }

    @Test
    void nullPrefix_defaultsToGe() {
        var custom = new MicrometerGraphEngineMetricsObserver(registry, null);
        custom.onVersionPublished("order-flow", "acme", "prod");

        Counter counter = registry.find("ge.version.published")
                .tag("definition", "order-flow")
                .tag("tenant", "acme")
                .tag("namespace", "prod")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    // ── Constructor validation ───────────────────────────────────────────

    @Test
    void constructor_rejectsNullRegistry() {
        assertThrows(IllegalArgumentException.class,
                () -> new MicrometerGraphEngineMetricsObserver(null));
    }

    @Test
    void constructor_rejectsNullRegistryWithPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> new MicrometerGraphEngineMetricsObserver(null, "ge"));
    }

    // ── NOOP observer ────────────────────────────────────────────────────

    @Test
    void noop_doesNotThrow() {
        GraphEngineMetricsObserver noop = GraphEngineMetricsObserver.NOOP;
        assertDoesNotThrow(() -> {
            noop.onVersionPublished("def", "t", "ns");
            noop.onInstanceStarted("def", "t", "ns", "GRAPH");
            noop.onInstanceCompleted("def", "t", "ns", "GRAPH", "COMPLETED");
            noop.onTaskClaimed("def", "t", "ns", "node-a");
            noop.onTaskCompleted("def", "t", "ns", "node-a");
            noop.onOperationsSnapshot("t", "ns", "OK", 0, 0, 0, 1, false, true);
        });
    }

    private void assertGauge(String name, String tenantId, String namespace, double expected) {
        Gauge gauge = registry.find(name)
                .tag("tenant", tenantId)
                .tag("namespace", namespace)
                .gauge();
        assertNotNull(gauge);
        assertEquals(expected, gauge.value());
    }
}
