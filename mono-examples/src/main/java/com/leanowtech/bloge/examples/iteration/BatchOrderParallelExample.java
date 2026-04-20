package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.engine.operators.ForEachOperator;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates parallel forEach execution using the Java fluent API.
 * <p>
 * Graph: fetchOrders → processOrders (forEach parallel) → summarize
 * <p>
 * The forEach iterates over each order concurrently, running a sub-graph
 * with validate → deductStock for each item.
 */
@SuppressWarnings({"preview", "unchecked"})
public class BatchOrderParallelExample {

    // --- Records ---

    public record Order(String orderId, int quantity, double price) {}
    public record ValidatedOrder(String orderId, boolean valid, String reason) {}
    public record StockResult(String orderId, boolean deducted, int remainingStock) {}
    public record BatchSummary(int totalProcessed, int successCount, int failCount) {}

    // --- Operators ---

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_ORDERS = (input, ctx) -> {
        Thread.sleep(40);
        String customerId = (String) input.get("customerId");
        var orders = List.<Object>of(
                new Order("ORD-001", 2, 29.99),
                new Order("ORD-002", 1, 59.99),
                new Order("ORD-003", 5, 9.99)
        );
        return Map.of("orders", orders, "customerId", customerId);
    };

    static final Operator<Order, ValidatedOrder> VALIDATE_ORDER = (input, ctx) -> {
        Thread.sleep(20);
        return new ValidatedOrder(input.orderId(), true, "All checks passed");
    };

    static final Operator<Map<String, Object>, StockResult> DEDUCT_STOCK = (input, ctx) -> {
        Thread.sleep(30);
        String orderId = (String) input.get("orderId");
        int quantity = (int) input.get("quantity");
        boolean validated = (boolean) input.get("validated");
        int remaining = validated ? 100 - quantity : 100;
        return new StockResult(orderId, validated, remaining);
    };

    static final Operator<List<Map<String, Object>>, BatchSummary> SUMMARIZE = (input, ctx) -> {
        int total = input.size();
        int success = 0;
        for (var itemResult : input) {
            Object deductOutput = itemResult.get("deductStock");
            if (deductOutput instanceof StockResult sr && sr.deducted()) {
                success++;
            }
        }
        return new BatchSummary(total, success, total - success);
    };

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Build sub-graph for forEach: validate → deductStock
        Graph subGraph = Graph.builder("processOrders__subgraph__")
                .node("validate", VALIDATE_ORDER)
                    .input((results, ctx) -> {
                        Order order = ctx.get("__item__", Order.class);
                        return order;
                    })
                .node("deductStock", DEDUCT_STOCK)
                    .dependsOn("validate")
                    .input((results, ctx) -> {
                        Order order = ctx.get("__item__", Order.class);
                        ValidatedOrder validated = results.get("validate", ValidatedOrder.class);
                        Map<String, Object> deductInput = new LinkedHashMap<>();
                        deductInput.put("orderId", order.orderId());
                        deductInput.put("quantity", order.quantity());
                        deductInput.put("validated", validated.valid());
                        return deductInput;
                    })
                .build();

        // Register sub-graph operators using their operatorRef from the sub-graph
        registry.register(subGraph.nodes().get("validate").operatorRef(), VALIDATE_ORDER);
        registry.register(subGraph.nodes().get("deductStock").operatorRef(), DEDUCT_STOCK);

        // Create ForEachOperator in parallel mode (sequential = false)
        var listener = new LoggingListener();
        var forEachOp = new ForEachOperator(subGraph, registry, false, List.of(listener));

        // Build main graph
        Graph mainGraph = Graph.builder("batchOrderParallel")
                .node("fetchOrders", FETCH_ORDERS)
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
                .node("summarize", SUMMARIZE)
                    .dependsOn("processOrders")
                    .input((results, ctx) -> {
                        return results.get("processOrders", List.class);
                    })
                .build();

        // Execute
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(listener))
                .build();
        var ctx = new GraphContext(Map.of("customerId", "CUST-100"));

        GraphResult result = engine.executeWithOperators(mainGraph, ctx, Map.of(
                "fetchOrders", FETCH_ORDERS,
                "processOrders", forEachOp,
                "summarize", SUMMARIZE
        ));

        // Print results
        System.out.println("\n═══ Batch Order Parallel Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("processOrders") == NodeStatus.COMPLETED) {
            var forEachResults = (List<Map<String, Object>>) results(result, "processOrders");
            System.out.println("ForEach results (" + forEachResults.size() + " items):");
            for (int i = 0; i < forEachResults.size(); i++) {
                System.out.printf("  Item #%d: %s%n", i, forEachResults.get(i));
            }
        }
        System.out.println();

        if (result.getStatus("summarize") == NodeStatus.COMPLETED) {
            BatchSummary summary = result.getOutput("summarize", BatchSummary.class);
            System.out.println("Summary: " + summary);
        }
    }

    private static Object results(GraphResult result, String nodeId) {
        return result.results().getRaw(nodeId);
    }
}
