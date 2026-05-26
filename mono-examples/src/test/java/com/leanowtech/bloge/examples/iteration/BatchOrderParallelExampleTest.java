package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.engine.operators.ForEachOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"preview", "unchecked"})
class BatchOrderParallelExampleTest {



    private GraphResult executeJavaApi() {
        var registry = new DefaultOperatorRegistry();

        Graph subGraph = Graph.builder("processOrders__subgraph__")
                .node("validate", BatchOrderParallelExample.VALIDATE_ORDER)
                    .input((results, ctx) -> {
                        return ctx.get("__item__", BatchOrderParallelExample.Order.class);
                    })
                .node("deductStock", BatchOrderParallelExample.DEDUCT_STOCK)
                    .dependsOn("validate")
                    .input((results, ctx) -> {
                        var order = ctx.get("__item__", BatchOrderParallelExample.Order.class);
                        var validated = results.get("validate", BatchOrderParallelExample.ValidatedOrder.class);
                        Map<String, Object> deductInput = new LinkedHashMap<>();
                        deductInput.put("orderId", order.orderId());
                        deductInput.put("quantity", order.quantity());
                        deductInput.put("validated", validated.valid());
                        return deductInput;
                    })
                .build();

        // Register sub-graph operators using their operatorRef from the sub-graph
        registry.register(subGraph.nodes().get("validate").operatorRef(), BatchOrderParallelExample.VALIDATE_ORDER);
        registry.register(subGraph.nodes().get("deductStock").operatorRef(), BatchOrderParallelExample.DEDUCT_STOCK);

        var forEachOp = ForEachOperator.builder(subGraph, registry)
          .sequential(false)
          .listeners(List.of())
          .build();

        Graph mainGraph = Graph.builder("batchOrderParallel")
                .node("fetchOrders", BatchOrderParallelExample.FETCH_ORDERS)
                    .input((results, ctx) -> {
                        Map<String, Object> input = new LinkedHashMap<>();
                        input.put("customerId", ctx.get("customerId", String.class));
                        return input;
                    })
                .node("processOrders", forEachOp)
                    .dependsOn("fetchOrders")
                    .input((results, ctx) -> {
                        var fetchOutput = (Map<String, Object>) results.getRaw("fetchOrders");
                        return (List<Object>) fetchOutput.get("orders");
                    })
                .node("summarize", BatchOrderParallelExample.SUMMARIZE)
                    .dependsOn("processOrders")
                    .input((results, ctx) -> {
                        return results.get("processOrders", List.class);
                    })
                .build();

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of("customerId", "CUST-100"));

        return engine.executeWithOperators(mainGraph, ctx, Map.of(
                "fetchOrders", BatchOrderParallelExample.FETCH_ORDERS,
                "processOrders", forEachOp,
                "summarize", BatchOrderParallelExample.SUMMARIZE
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
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fetchOrders"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("processOrders"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("summarize"));
    }

    @Test
    void testJavaApi_forEachProcessesAllItems() {
        GraphResult result = executeJavaApi();
        var forEachResults = (List<Map<String, Object>>) result.results().getRaw("processOrders");
        assertNotNull(forEachResults, "ForEach output should not be null");
        assertEquals(3, forEachResults.size(), "ForEach should process all 3 orders");
    }

    @Test
    void testJavaApi_summaryIsCorrect() {
        GraphResult result = executeJavaApi();
        var summary = result.getOutput("summarize", BatchOrderParallelExample.BatchSummary.class);
        assertNotNull(summary, "Summary should not be null");
        assertEquals(3, summary.totalProcessed(), "Should have processed 3 orders");
        assertEquals(3, summary.successCount(), "All 3 orders should be successfully processed");
    }



    private GraphResult executeDsl() {
        var registry = new DefaultOperatorRegistry();
        registry.register("OrderFetcherOperator", BatchOrderParallelDslExample.FETCH_ORDERS);
        registry.register("OrderValidatorOperator", BatchOrderParallelDslExample.VALIDATE_ORDER);
        registry.register("StockDeductionOperator", BatchOrderParallelDslExample.DEDUCT_STOCK);
        registry.register("BatchSummaryOperator", BatchOrderParallelDslExample.SUMMARIZE);

        String dsl = """
                graph batchOrderParallel {
                  node fetchOrders : OrderFetcherOperator {
                    input { customerId = ctx.customerId }
                    output {
                      orders: List
                      customerId: String
                    }
                  }
                  foreach processOrders : (order, idx) in fetchOrders.output.orders {
                    node validate : OrderValidatorOperator {
                      input {
                        order = order
                        index = idx
                      }
                      output {
                        valid: Boolean
                        reason: String
                      }
                    }
                    node deductStock : StockDeductionOperator {
                      depends_on = [validate]
                      input {
                        orderId = order.orderId
                        quantity = order.quantity
                        validated = validate.output.valid
                      }
                    }
                  }
                  node summarize : BatchSummaryOperator {
                    depends_on = [processOrders]
                    input { results = processOrders.output }
                  }
                }
                """;

        Graph graph = new GraphLoader(registry).load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of("customerId", "CUST-200"));

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
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fetchOrders"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("processOrders"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("summarize"));
    }

    @Test
    void testDsl_forEachProcessesAllItems() {
        GraphResult result = executeDsl();
        var forEachResults = (List<Map<String, Object>>) result.results().getRaw("processOrders");
        assertNotNull(forEachResults, "DSL ForEach output should not be null");
        assertEquals(3, forEachResults.size(), "DSL ForEach should process all 3 orders");
    }
}
