package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.engine.operators.ForEachOperator;
import com.leanowtech.bloge.core.engine.operators.LoopOperator;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates a composite graph combining forEach (parallel parcel routing) +
 * loop (poll all dispatched) in one graph using the Java fluent API.
 * <p>
 * Graph: fetchParcels → assignRoutes (forEach parallel) → pollAllDispatched (loop) → dispatchReport
 * <p>
 * The forEach iterates over each parcel concurrently, running planRoute → dispatchParcel.
 * The loop polls batch dispatch status until all parcels are delivered.
 */
@SuppressWarnings({"preview", "unchecked"})
public class LogisticsBatchDispatchExample {

    // --- Records ---

    public record Parcel(String parcelId, String destination, double weight) {}
    public record RouteResult(String parcelId, String routeId, String carrier, double estimatedHours) {}
    public record DispatchResult(String parcelId, String routeId, String status) {}
    public record BatchStatus(boolean allDelivered, int delivered, int pending, int total) {}
    public record DispatchReport(int totalParcels, int delivered, String completedAt) {}

    // --- Operators ---

    /** Fetches pending parcels from a warehouse. */
    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_PARCELS = (input, ctx) -> {
        Thread.sleep(30);
        String warehouseId = (String) input.get("warehouseId");
        var parcels = List.<Object>of(
                new Parcel("PKG-001", "London", 2.5),
                new Parcel("PKG-002", "Paris", 1.8),
                new Parcel("PKG-003", "Berlin", 4.2)
        );
        return Map.of("parcels", parcels, "warehouseId", warehouseId);
    };

    /** Plans a delivery route for a single parcel. */
    static final Operator<Parcel, RouteResult> PLAN_ROUTE = (input, ctx) -> {
        Thread.sleep(20);
        return new RouteResult(
                input.parcelId(),
                "RT-" + input.parcelId(),
                input.weight() > 3.0 ? "HeavyFreight" : "ExpressCourier",
                input.weight() > 3.0 ? 48.0 : 24.0
        );
    };

    /** Dispatches a parcel along its planned route. */
    static final Operator<Map<String, Object>, DispatchResult> DISPATCH_PARCEL = (input, ctx) -> {
        Thread.sleep(25);
        String parcelId = (String) input.get("parcelId");
        String routeId = (String) input.get("routeId");
        return new DispatchResult(parcelId, routeId, "DISPATCHED");
    };

    /**
     * Checks batch dispatch status.
     * Simulates: iterations 0-1 return allDelivered=false, iteration 2+ returns allDelivered=true.
     */
    static final Operator<Map<String, Object>, BatchStatus> CHECK_ALL_STATUS = (input, ctx) -> {
        Thread.sleep(15);
        int iteration = ((Number) input.get("iteration")).intValue();
        int total = 3;
        if (iteration >= 2) {
            return new BatchStatus(true, total, 0, total);
        } else {
            int delivered = iteration + 1;
            return new BatchStatus(false, delivered, total - delivered, total);
        }
    };

    /** Generates the final dispatch report from loop and forEach outputs. */
    static final Operator<Map<String, Object>, DispatchReport> DISPATCH_REPORT = (input, ctx) -> {
        Thread.sleep(10);
        var batchStatus = (BatchStatus) input.get("batchStatus");
        return new DispatchReport(
                batchStatus.total(),
                batchStatus.delivered(),
                "2025-02-24T15:00:00Z"
        );
    };

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        var listener = new LoggingListener();

        // Register sub-graph operators in registry
        registry.register("planRoute", PLAN_ROUTE);
        registry.register("dispatchParcel", DISPATCH_PARCEL);
        registry.register("checkAllStatus", CHECK_ALL_STATUS);

        // --- Build forEach sub-graph: planRoute → dispatchParcel ---
        Graph foreachSubGraph = Graph.builder("assignRoutes__subgraph__")
                .node("planRoute", PLAN_ROUTE)
                    .input((results, ctx) -> {
                        Parcel parcel = ctx.get("__item__", Parcel.class);
                        return parcel;
                    })
                .node("dispatchParcel", DISPATCH_PARCEL)
                    .dependsOn("planRoute")
                    .input((results, ctx) -> {
                        Parcel parcel = ctx.get("__item__", Parcel.class);
                        RouteResult route = results.get("planRoute", RouteResult.class);
                        Map<String, Object> dispatchInput = new LinkedHashMap<>();
                        dispatchInput.put("parcelId", parcel.parcelId());
                        dispatchInput.put("routeId", route.routeId());
                        return dispatchInput;
                    })
                .build();

        // --- Build loop sub-graph: checkAllStatus ---
        Graph loopSubGraph = Graph.builder("pollAllDispatched__subgraph__")
                .node("checkAllStatus", CHECK_ALL_STATUS)
                    .input((results, ctx) -> {
                        Map<String, Object> loopInput = new LinkedHashMap<>();
                        loopInput.put("batchId", ctx.get("batchId", String.class));
                        loopInput.put("iteration", ctx.get("__loopIteration__", Integer.class));
                        return loopInput;
                    })
                .build();

        // --- Create ForEachOperator (parallel mode) ---
        var forEachOp = ForEachOperator.builder(foreachSubGraph, registry)
            .sequential(false)
            .listeners(List.of(listener))
            .build();

        // --- Create LoopOperator ---
        var loopOp = LoopOperator.withDurability(
                loopSubGraph,
                registry,
                30,                          // maxIterations
                Duration.ofMillis(10),       // delay (fast for example, not real 10s)
                // untilCondition: stop when allDelivered == true
                outputs -> {
                    var status = (BatchStatus) outputs.get("checkAllStatus");
                    return status.allDelivered();
                },
                // carryMapper: no carry needed
                outputs -> Map.of(),
                null,
                List.of(listener)
        );

        // --- Build main graph: fetchParcels → assignRoutes → pollAllDispatched → dispatchReport ---
        Graph mainGraph = Graph.builder("logisticsBatchDispatch")
                .node("fetchParcels", FETCH_PARCELS)
                    .input((results, ctx) -> {
                        Map<String, Object> input = new LinkedHashMap<>();
                        input.put("warehouseId", ctx.get("warehouseId", String.class));
                        return input;
                    })
                .node("assignRoutes", forEachOp)
                    .dependsOn("fetchParcels")
                    .input((results, ctx) -> {
                        var fetchOutput = (Map<String, Object>) results.getRaw("fetchParcels");
                        return (List<Object>) fetchOutput.get("parcels");
                    })
                .node("pollAllDispatched", loopOp)
                    .dependsOn("assignRoutes")
                    .input((results, ctx) ->
                            Map.<String, Object>of("batchId", ctx.get("batchId", String.class)))
                .node("dispatchReport", DISPATCH_REPORT)
                    .dependsOn("pollAllDispatched")
                    .input((results, ctx) -> {
                        var loopOutput = (Map<String, Object>) results.getRaw("pollAllDispatched");
                        var batchStatus = (BatchStatus) loopOutput.get("checkAllStatus");
                        Map<String, Object> reportInput = new LinkedHashMap<>();
                        reportInput.put("batchStatus", batchStatus);
                        reportInput.put("routeResults", results.getRaw("assignRoutes"));
                        return reportInput;
                    })
                .build();

        // --- Execute ---
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(listener))
                .build();
        var ctx = new GraphContext(Map.of(
                "warehouseId", "WH-EAST-01",
                "batchId", "BATCH-2025-001"
        ));

        GraphResult result = engine.executeWithOperators(mainGraph, ctx, Map.of(
                "fetchParcels", FETCH_PARCELS,
                "assignRoutes", forEachOp,
                "pollAllDispatched", loopOp,
                "dispatchReport", DISPATCH_REPORT
        ));

        // --- Print results ---
        System.out.println("\n═══ Logistics Batch Dispatch Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("fetchParcels") == NodeStatus.COMPLETED) {
            var fetchOutput = (Map<String, Object>) result.results().getRaw("fetchParcels");
            System.out.println("Fetched parcels: " + fetchOutput.get("parcels"));
        }

        if (result.getStatus("assignRoutes") == NodeStatus.COMPLETED) {
            var forEachResults = (List<Map<String, Object>>) result.results().getRaw("assignRoutes");
            System.out.println("\nForEach results (" + forEachResults.size() + " items):");
            for (int i = 0; i < forEachResults.size(); i++) {
                System.out.printf("  Parcel #%d: %s%n", i, forEachResults.get(i));
            }
        }

        if (result.getStatus("pollAllDispatched") == NodeStatus.COMPLETED) {
            System.out.println("\nPoll loop output: " + result.results().getRaw("pollAllDispatched"));
        }

        if (result.getStatus("dispatchReport") == NodeStatus.COMPLETED) {
            DispatchReport report = result.getOutput("dispatchReport", DispatchReport.class);
            System.out.println("\nDispatch Report: " + report);
        }
    }
}
