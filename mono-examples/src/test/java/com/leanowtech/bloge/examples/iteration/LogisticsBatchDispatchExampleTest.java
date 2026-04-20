package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.engine.operators.ForEachOperator;
import com.leanowtech.bloge.core.engine.operators.LoopOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"preview", "unchecked"})
class LogisticsBatchDispatchExampleTest {

    // ═══════════════════════════════════════════════════════════
    //  Java API tests
    // ═══════════════════════════════════════════════════════════

    private GraphResult executeJavaApi() {
        var registry = new DefaultOperatorRegistry();
        // Register sub-graph operators under their node IDs so that
        // ForEachOperator/LoopOperator internal engines can resolve them via operatorRef.
        // operatorRef is set to the node ID for synthetic (lambda) operators (see GraphBuilder).
        registry.register("planRoute", LogisticsBatchDispatchExample.PLAN_ROUTE);
        registry.register("dispatchParcel", LogisticsBatchDispatchExample.DISPATCH_PARCEL);
        registry.register("checkAllStatus", LogisticsBatchDispatchExample.CHECK_ALL_STATUS);

        Graph foreachSubGraph = Graph.builder("assignRoutes__subgraph__")
                .node("planRoute", LogisticsBatchDispatchExample.PLAN_ROUTE)
                    .input((results, ctx) -> {
                        return ctx.get("__item__", LogisticsBatchDispatchExample.Parcel.class);
                    })
                .node("dispatchParcel", LogisticsBatchDispatchExample.DISPATCH_PARCEL)
                    .dependsOn("planRoute")
                    .input((results, ctx) -> {
                        var parcel = ctx.get("__item__", LogisticsBatchDispatchExample.Parcel.class);
                        var route = results.get("planRoute", LogisticsBatchDispatchExample.RouteResult.class);
                        Map<String, Object> dispatchInput = new LinkedHashMap<>();
                        dispatchInput.put("parcelId", parcel.parcelId());
                        dispatchInput.put("routeId", route.routeId());
                        return dispatchInput;
                    })
                .build();

        Graph loopSubGraph = Graph.builder("pollAllDispatched__subgraph__")
                .node("checkAllStatus", LogisticsBatchDispatchExample.CHECK_ALL_STATUS)
                    .input((results, ctx) -> {
                        Map<String, Object> loopInput = new LinkedHashMap<>();
                        loopInput.put("batchId", ctx.get("batchId", String.class));
                        loopInput.put("iteration", ctx.get("__loopIteration__", Integer.class));
                        return loopInput;
                    })
                .build();

        var forEachOp = new ForEachOperator(foreachSubGraph, registry, false, List.of());

        var loopOp = LoopOperator.withDurability(
                loopSubGraph,
                registry,
                30,
                Duration.ofMillis(10),
                outputs -> {
                    var status = (LogisticsBatchDispatchExample.BatchStatus) outputs.get("checkAllStatus");
                    return status.allDelivered();
                },
                outputs -> Map.of(),
                null,
                List.of()
        );

        Graph mainGraph = Graph.builder("logisticsBatchDispatch")
                .node("fetchParcels", LogisticsBatchDispatchExample.FETCH_PARCELS)
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
                .node("dispatchReport", LogisticsBatchDispatchExample.DISPATCH_REPORT)
                    .dependsOn("pollAllDispatched")
                    .input((results, ctx) -> {
                        var loopOutput = (Map<String, Object>) results.getRaw("pollAllDispatched");
                        var batchStatus = (LogisticsBatchDispatchExample.BatchStatus) loopOutput.get("checkAllStatus");
                        Map<String, Object> reportInput = new LinkedHashMap<>();
                        reportInput.put("batchStatus", batchStatus);
                        reportInput.put("routeResults", results.getRaw("assignRoutes"));
                        return reportInput;
                    })
                .build();

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of(
                "warehouseId", "WH-EAST-01",
                "batchId", "BATCH-2025-001"
        ));

        return engine.executeWithOperators(mainGraph, ctx, Map.of(
                "fetchParcels", LogisticsBatchDispatchExample.FETCH_PARCELS,
                "assignRoutes", forEachOp,
                "pollAllDispatched", loopOp,
                "dispatchReport", LogisticsBatchDispatchExample.DISPATCH_REPORT
        ));
    }

    @Test
    void testJavaApi_graphExecutesSuccessfully() {
        GraphResult result = executeJavaApi();
        assertTrue(result.isSuccess(), "Graph should execute successfully");
    }

    @Test
    void testJavaApi_allNodesCompleted() {
        GraphResult result = executeJavaApi();
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fetchParcels"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("assignRoutes"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("pollAllDispatched"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("dispatchReport"));
    }

    @Test
    void testJavaApi_forEachProcesses3Parcels() {
        GraphResult result = executeJavaApi();
        var forEachResults = (List<Map<String, Object>>) result.results().getRaw("assignRoutes");
        assertNotNull(forEachResults, "ForEach output should not be null");
        assertEquals(3, forEachResults.size(), "ForEach should process all 3 parcels");
    }

    @Test
    void testJavaApi_loopTerminatesWhenAllDelivered() {
        GraphResult result = executeJavaApi();
        var loopOutput = (Map<String, Object>) result.results().getRaw("pollAllDispatched");
        assertNotNull(loopOutput, "Loop output should not be null");
        var batchStatus = (LogisticsBatchDispatchExample.BatchStatus) loopOutput.get("checkAllStatus");
        assertNotNull(batchStatus, "checkAllStatus output should not be null");
        assertTrue(batchStatus.allDelivered(), "All parcels should be delivered when loop terminates");
    }

    @Test
    void testJavaApi_dispatchReportIsCorrect() {
        GraphResult result = executeJavaApi();
        var report = result.getOutput("dispatchReport", LogisticsBatchDispatchExample.DispatchReport.class);
        assertNotNull(report, "Dispatch report should not be null");
        assertEquals(3, report.totalParcels(), "Report should show 3 total parcels");
        assertEquals(3, report.delivered(), "Report should show 3 delivered parcels");
    }

    // ═══════════════════════════════════════════════════════════
    //  DSL tests
    // ═══════════════════════════════════════════════════════════

    private GraphResult executeDsl() {
        var registry = new DefaultOperatorRegistry();
        registry.register("ParcelFetcherOperator", LogisticsBatchDispatchDslExample.FETCH_PARCELS);
        registry.register("RoutePlannerOperator", LogisticsBatchDispatchDslExample.PLAN_ROUTE);
        registry.register("ParcelDispatcherOperator", LogisticsBatchDispatchDslExample.DISPATCH_PARCEL);
        registry.register("BatchStatusCheckerOperator", LogisticsBatchDispatchDslExample.CHECK_ALL_STATUS);
        registry.register("DispatchReportOperator", LogisticsBatchDispatchDslExample.DISPATCH_REPORT);

        // Multi-line input blocks required — DSL parser doesn't support comma-separated fields
        String dsl = """
                graph logisticsBatchDispatch {
                  node fetchParcels : ParcelFetcherOperator {
                    input { warehouseId = ctx.warehouseId }
                  }
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
                  loop pollAllDispatched {
                    max_iterations = 30
                    /// Match the fluent Java example's 10ms loop cadence so the DSL test exercises the same behavior.
                    delay = 10ms
                    depends_on = [assignRoutes]
                    node checkAllStatus : BatchStatusCheckerOperator {
                      input {
                        batchId   = ctx.batchId
                        iteration = loopIteration
                      }
                    }
                    until checkAllStatus.output.allDelivered == true
                  }
                  node dispatchReport : DispatchReportOperator {
                    depends_on = [pollAllDispatched]
                    input {
                      batchStatus = pollAllDispatched.output.checkAllStatus
                      routeResults = assignRoutes.output
                    }
                  }
                }
                """;

        Graph graph = new GraphLoader(registry).load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of(
                "warehouseId", "WH-EAST-01",
                "batchId", "BATCH-2025-001"
        ));

        return engine.execute(graph, ctx);
    }

    @Test
    void testDsl_graphExecutesSuccessfully() {
        GraphResult result = executeDsl();
        assertTrue(result.isSuccess(), "DSL graph should execute successfully");
    }

    @Test
    void testDsl_allNodesCompleted() {
        GraphResult result = executeDsl();
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fetchParcels"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("assignRoutes"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("pollAllDispatched"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("dispatchReport"));
    }

    @Test
    void testDsl_forEachAndLoopCombined() {
        GraphResult result = executeDsl();

        var forEachResults = (List<Map<String, Object>>) result.results().getRaw("assignRoutes");
        assertNotNull(forEachResults, "DSL ForEach output should not be null");
        assertEquals(3, forEachResults.size(), "DSL ForEach should process all 3 parcels");

        var loopOutput = (Map<String, Object>) result.results().getRaw("pollAllDispatched");
        assertNotNull(loopOutput, "DSL Loop output should not be null");
        var checkAllStatus = (Map<String, Object>) loopOutput.get("checkAllStatus");
        assertNotNull(checkAllStatus, "DSL checkAllStatus output should not be null");
        assertEquals(true, checkAllStatus.get("allDelivered"),
                "DSL loop should terminate when allDelivered is true");
    }
}
