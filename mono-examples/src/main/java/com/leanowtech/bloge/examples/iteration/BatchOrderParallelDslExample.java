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
 * DSL foreach example for parallel batch order processing.
 *
 * <p>This example demonstrates a parallel {@code foreach} sub-graph defined in DSL,
 * where each order item runs validation and stock deduction concurrently.
 *
 * <p>Graph layout:
 * <pre>
 * fetchOrders
 *   -> foreach processOrders (parallel): validateOrder -> deductStock
 *   -> summarize
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings({"preview", "unchecked"})
public class BatchOrderParallelDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_ORDERS = (input, ctx) -> {
        Thread.sleep(40);
        String customerId = (String) input.get("customerId");
        var orders = List.of(
                Map.<String, Object>of("orderId", "ORD-001", "quantity", 2, "price", 29.99),
                Map.<String, Object>of("orderId", "ORD-002", "quantity", 1, "price", 59.99),
                Map.<String, Object>of("orderId", "ORD-003", "quantity", 5, "price", 9.99)
        );
        return Map.of("orders", orders, "customerId", customerId);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> VALIDATE_ORDER = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of("valid", true, "reason", "OK");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> DEDUCT_STOCK = (input, ctx) -> {
        Thread.sleep(30);
        String orderId = (String) input.get("orderId");
        return Map.of("orderId", orderId, "deducted", true, "remainingStock", 42);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SUMMARIZE = (input, ctx) -> {
        var results = (List<Map<String, Object>>) input.get("results");
        int total = results.size();
        int success = 0;
        for (var itemResult : results) {
            var deductOutput = (Map<String, Object>) itemResult.get("deductStock");
            if (deductOutput != null && Boolean.TRUE.equals(deductOutput.get("deducted"))) {
                success++;
            }
        }
        return Map.of("totalProcessed", total, "successCount", success, "failCount", total - success);
    };

    public static void main(String[] args) {
        // ── Operator Registrations ─────────────────────────────────────────────
        var registry = new DefaultOperatorRegistry();
        // OrderFetcherOperator: reads ctx.customerId → returns {orders, customerId}
        registry.register("OrderFetcherOperator", FETCH_ORDERS);
        // OrderValidatorOperator: reads order, index → returns {valid, reason}
        registry.register("OrderValidatorOperator", VALIDATE_ORDER);
        // StockDeductionOperator: reads orderId, quantity, validated → returns {orderId, deducted, remainingStock}
        registry.register("StockDeductionOperator", DEDUCT_STOCK);
        // BatchSummaryOperator: reads results (foreach output list) → returns {totalProcessed, successCount, failCount}
        registry.register("BatchSummaryOperator", SUMMARIZE);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph batchOrderParallel {

                  /// Fetches pending orders for the given customer
                  node fetchOrders : OrderFetcherOperator {
                    input { customerId = ctx.customerId }
                    output {
                      orders: List
                      customerId: String
                    }
                  }

                  /// foreach parallel mode (default): process each order concurrently
                  /// order — variable referencing the current order
                  /// idx — variable referencing the 0-based index
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

                  /// Summarizes all processed order results from the foreach output
                  node summarize : BatchSummaryOperator {
                    depends_on = [processOrders]
                    input { results = processOrders.output }
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
        var ctx = new GraphContext(Map.of("customerId", "CUST-200"));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Batch Order Parallel Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("processOrders") == NodeStatus.COMPLETED) {
            var forEachResults = (List<Map<String, Object>>) result.results().getRaw("processOrders");
            System.out.println("ForEach results (" + forEachResults.size() + " items):");
            for (int i = 0; i < forEachResults.size(); i++) {
                System.out.printf("  Item #%d: %s%n", i, forEachResults.get(i));
            }
        }
        System.out.println();

        if (result.getStatus("summarize") == NodeStatus.COMPLETED) {
            var summaryMap = (Map<String, Object>) result.results().getRaw("summarize");
            System.out.println("Summary: " + summaryMap);
        }
    }
}
