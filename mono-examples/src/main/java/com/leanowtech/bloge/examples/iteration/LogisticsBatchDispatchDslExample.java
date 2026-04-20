package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.List;
import java.util.Map;

/**
 * DSL version of the Logistics Batch Dispatch example — loads the graph from
 * a .bloge DSL string combining forEach (parallel parcel routing) + loop
 * (poll all dispatched) in one graph.
 * <p>
 * Graph: fetchParcels → assignRoutes (forEach) → pollAllDispatched (loop) → dispatchReport
 */
@SuppressWarnings({"preview", "unchecked"})
public class LogisticsBatchDispatchDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_PARCELS = (input, ctx) -> {
        Thread.sleep(30);
        String warehouseId = (String) input.get("warehouseId");
        var parcels = List.of(
                Map.<String, Object>of("parcelId", "PKG-001", "destination", "London", "weight", 2.5),
                Map.<String, Object>of("parcelId", "PKG-002", "destination", "Paris", "weight", 1.8),
                Map.<String, Object>of("parcelId", "PKG-003", "destination", "Berlin", "weight", 4.2)
        );
        return Map.of("parcels", parcels, "warehouseId", warehouseId);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> PLAN_ROUTE = (input, ctx) -> {
        Thread.sleep(20);
        var parcel = (Map<String, Object>) input.get("parcel");
        String parcelId = (String) parcel.get("parcelId");
        double weight = ((Number) parcel.get("weight")).doubleValue();
        return Map.of(
                "parcelId", parcelId,
                "routeId", "RT-" + parcelId,
                "carrier", weight > 3.0 ? "HeavyFreight" : "ExpressCourier",
                "estimatedHours", weight > 3.0 ? 48.0 : 24.0
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> DISPATCH_PARCEL = (input, ctx) -> {
        Thread.sleep(25);
        var parcel = (Map<String, Object>) input.get("parcel");
        var route = (Map<String, Object>) input.get("route");
        String parcelId = (String) parcel.get("parcelId");
        String routeId = (String) route.get("routeId");
        return Map.of("parcelId", parcelId, "routeId", routeId, "status", "DISPATCHED");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CHECK_ALL_STATUS = (input, ctx) -> {
        Thread.sleep(15);
        int iteration = ((Number) input.get("iteration")).intValue();
        int total = 3;
        if (iteration >= 2) {
            return Map.of("allDelivered", true, "delivered", total, "pending", 0, "total", total);
        } else {
            int delivered = iteration + 1;
            return Map.of("allDelivered", false, "delivered", delivered, "pending", total - delivered, "total", total);
        }
    };

    static final Operator<Map<String, Object>, Map<String, Object>> DISPATCH_REPORT = (input, ctx) -> {
        Thread.sleep(10);
        var batchStatus = (Map<String, Object>) input.get("batchStatus");
        var routeResults = (List<Map<String, Object>>) input.get("routeResults");
        int total = ((Number) batchStatus.get("total")).intValue();
        int delivered = ((Number) batchStatus.get("delivered")).intValue();
        return Map.of(
                "totalParcels", total,
                "delivered", delivered,
                "routeCount", routeResults != null ? routeResults.size() : 0,
                "completedAt", "2025-02-24T15:00:00Z"
        );
    };

    public static void main(String[] args) {
        // ── Operator Registrations ─────────────────────────────────────────────
        var registry = new DefaultOperatorRegistry();
        // ParcelFetcherOperator: reads ctx.warehouseId → returns {parcels, warehouseId}
        registry.register("ParcelFetcherOperator", FETCH_PARCELS);
        // RoutePlannerOperator: reads parcel, index → returns {parcelId, routeId, carrier, estimatedHours}
        registry.register("RoutePlannerOperator", PLAN_ROUTE);
        // ParcelDispatcherOperator: reads parcel, route → returns {parcelId, routeId, status}
        registry.register("ParcelDispatcherOperator", DISPATCH_PARCEL);
        // BatchStatusCheckerOperator: reads ctx.batchId, loopIteration → returns {allDelivered, delivered, pending, total}
        registry.register("BatchStatusCheckerOperator", CHECK_ALL_STATUS);
        // DispatchReportOperator: reads batchStatus, routeResults → returns {totalParcels, delivered, routeCount, completedAt}
        registry.register("DispatchReportOperator", DISPATCH_REPORT);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph logisticsBatchDispatch {

                  /// Fetches pending parcels for dispatch
                  node fetchParcels : ParcelFetcherOperator {
                    input { warehouseId = ctx.warehouseId }
                  }

                  /// foreach: assign delivery routes to each parcel in parallel
                  /// parcel — the current parcel object
                  /// idx — the parcel's position in the batch
                  foreach assignRoutes : (parcel, idx) in fetchParcels.output.parcels {
                    node planRoute : RoutePlannerOperator {
                      input {
                        parcel = parcel
                        index = idx
                      }
                    }
                    node dispatchParcel : ParcelDispatcherOperator {
                      depends_on = [planRoute]
                      input {
                        parcel = parcel
                        route = planRoute.output
                      }
                    }
                  }

                  /// loop: poll dispatch status until all parcels are delivered
                  /// Demonstrates foreach output flowing into a loop via depends_on
                  loop pollAllDispatched {
                    max_iterations = 30
                    /// Keep the DSL example aligned with the fluent Java variant's fast polling cadence.
                    delay = 10ms
                    depends_on = [assignRoutes]
                    node checkAllStatus : BatchStatusCheckerOperator {
                      input {
                        batchId   = ctx.batchId
                        iteration = loopIteration
                      }
                    }
                    /// until: loop exits once all dispatched parcels have been delivered
                    until checkAllStatus.output.allDelivered == true
                  }

                  /// Final dispatch report
                  node dispatchReport : DispatchReportOperator {
                    depends_on = [pollAllDispatched]
                    input {
                      batchStatus = pollAllDispatched.output.checkAllStatus
                      routeResults = assignRoutes.output
                    }
                  }
                }
                """;

        // compile DSL; operators resolved by PascalCase name
        Graph graph = loader.load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "warehouseId", "WH-EAST-01",
                "batchId", "BATCH-2025-001"
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Logistics Batch Dispatch Result ═══");
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
            System.out.println("\nDispatch Report: " + result.results().getRaw("dispatchReport"));
        }
    }
}
