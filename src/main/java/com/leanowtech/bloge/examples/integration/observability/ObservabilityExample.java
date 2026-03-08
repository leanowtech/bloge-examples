package com.leanowtech.bloge.examples.integration.observability;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.exception.RetryableException;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.metrics.otel.LoggingExecutionListener;
import com.leanowtech.bloge.metrics.otel.MetricsExecutionListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manual observability wiring example for readers who want metrics before adopting Spring Boot.
 *
 * <p>The code uses {@link MetricsExecutionListener} directly so the example stays lightweight, while
 * the checked-in {@code integration/observability/application.yml} and dashboard JSON show how the same
 * signals can be shipped to Prometheus and Jaeger in a Spring deployment.</p>
 */
public final class ObservabilityExample {

    /** Initial caller payload. */
    public record AlertRequest(String ticketId, String severity) {
    }

    /** Normalized alert data passed between nodes. */
    public record AlertEnvelope(String ticketId, String severity) {
    }

    /** Final routing decision emitted by the graph. */
    public record AlertPlan(String queue, String summary) {
    }

    /** Snapshot of the emitted metrics so tests and readers can inspect the result. */
    public record MetricsSnapshot(
            boolean success,
            String queue,
            long graphExecutions,
            long routeNodeExecutions,
            double retryCount,
            double graphMeanDurationMs
    ) {
    }

    private ObservabilityExample() {
    }

    /**
     * Executes the example graph once and captures the metrics that BLOGE emitted.
     */
    public static MetricsSnapshot executeObservedScenario(String ticketId, String severity, boolean retryOnce) {
        var attemptCounter = new AtomicInteger();
        Operator<AlertRequest, AlertEnvelope> normalizeAlert = (input, ctx) ->
                new AlertEnvelope(input.ticketId(), input.severity().trim().toLowerCase());
        Operator<AlertEnvelope, AlertPlan> routeAlert = (input, ctx) -> {
            if (retryOnce && attemptCounter.getAndIncrement() == 0) {
                throw new RetryableException("Telemetry exporter warming up");
            }
            String queue = "critical".equals(input.severity()) ? "vip-ops" : "standard-ops";
            return new AlertPlan(queue, "Dispatch dashboard refreshed for " + input.ticketId());
        };

        Graph graph = Graph.builder("observabilityExample")
                .node("normalizeAlert", normalizeAlert)
                    .input((results, ctx) -> new AlertRequest(
                            ctx.get("ticketId", String.class),
                            ctx.get("severity", String.class)))
                    .timeout(Duration.ofSeconds(1))
                .node("routeAlert", routeAlert)
                    .dependsOn("normalizeAlert")
                    .input((results, ctx) -> results.get("normalizeAlert", AlertEnvelope.class))
                    .retry(2, Duration.ofMillis(10), BackoffStrategy.FIXED)
                    .timeout(Duration.ofSeconds(1))
                .build();

        var meterRegistry = new SimpleMeterRegistry();
        var metricsListener = new MetricsExecutionListener(meterRegistry, "bloge");
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of(metricsListener, new LoggingExecutionListener(false, false)))
                .build();
        var result = engine.executeWithOperators(
                graph,
                new GraphContext(Map.of(
                        "ticketId", ticketId,
                        "severity", severity
                )),
                Map.of(
                        "normalizeAlert", normalizeAlert,
                        "routeAlert", routeAlert
                )
        );

        AlertPlan plan = result.getOutput("routeAlert", AlertPlan.class);
        Timer graphTimer = meterRegistry.find("bloge.graph.duration")
                .tag("graph", "observabilityExample")
                .tag("outcome", result.isSuccess() ? "success" : "failure")
                .timer();
        Timer routeTimer = meterRegistry.find("bloge.node.duration")
                .tag("graph", "observabilityExample")
                .tag("node", "routeAlert")
                .tag("outcome", "success")
                .timer();
        Counter retryCounter = meterRegistry.find("bloge.node.retries")
                .tag("graph", "observabilityExample")
                .tag("node", "routeAlert")
                .counter();

        return new MetricsSnapshot(
                result.isSuccess(),
                plan.queue(),
                graphTimer == null ? 0 : graphTimer.count(),
                routeTimer == null ? 0 : routeTimer.count(),
                retryCounter == null ? 0 : retryCounter.count(),
                graphTimer == null ? 0 : graphTimer.mean(TimeUnit.MILLISECONDS)
        );
    }

    public static void main(String[] args) {
        MetricsSnapshot snapshot = executeObservedScenario("ALERT-42", "critical", true);
        System.out.println("Success: " + snapshot.success());
        System.out.println("Queue: " + snapshot.queue());
        System.out.println("Graph executions observed: " + snapshot.graphExecutions());
        System.out.println("Retry count: " + snapshot.retryCount());
        System.out.println("Config example: src/main/resources/integration/observability/application.yml");
        System.out.println("Dashboard example: src/main/resources/integration/observability/grafana-dashboard.json");
    }
}
