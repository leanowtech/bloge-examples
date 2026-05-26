package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.engine.operators.ForEachOperator;
import com.leanowtech.bloge.core.engine.operators.ItemFailurePolicy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates foreach batch sizing with per-item failure tolerance.
 *
 * <p>The graph processes three orders in parallel batches. One item intentionally fails, and
 * {@link ItemFailurePolicy#CONTINUE} records an {@code __error__} placeholder while allowing the
 * remaining items to complete.</p>
 */
@SuppressWarnings({"preview", "unchecked"})
public final class BatchOrderWithFailureExample {

    static final String NODE_LOAD_ORDERS = "loadOrders";
    static final String NODE_LOAD_CONFIG = "loadConfig";
    static final String NODE_PROCESS_ORDERS = "processOrders";
    static final String NODE_SUMMARIZE = "summarize";

    private BatchOrderWithFailureExample() {
    }

    public record Order(String id, int quantity) {}
    public record ProcessedOrder(String orderId, int index, String status) {}
    public record BatchSummary(int totalProcessed, int successCount, int failureCount) {}

    static final Operator<Map<String, Object>, Map<String, Object>> LOAD_ORDERS = (input, ctx) -> Map.of(
            "orders", List.of(
                    new Order("ORD-100", 1),
                    new Order("ORD-FAIL", 2),
                    new Order("ORD-101", 3)
            )
    );

    static final Operator<Map<String, Object>, Map<String, Object>> LOAD_CONFIG = (input, ctx) -> Map.of("batchSize", 2);

    static final Operator<Map<String, Object>, ProcessedOrder> PROCESS_ORDER = (input, ctx) -> {
        String orderId = String.valueOf(input.get("orderId"));
        if ("ORD-FAIL".equals(orderId)) {
            throw new IllegalStateException("inventory reservation failed for " + orderId);
        }
        return new ProcessedOrder(orderId, ((Number) input.get("index")).intValue(), "processed");
    };

    static final Operator<List<Map<String, Object>>, BatchSummary> SUMMARIZE = (input, ctx) -> summarize(input);

    /**
     * Builds the foreach failure-tolerance graph.
     *
     * @return graph using {@code batch_size=2} and CONTINUE item failure policy
     */
    public static Graph buildGraph(DefaultOperatorRegistry registry) {
        Graph subGraph = Graph.builder("processOrders__subgraph__")
                .node("process", PROCESS_ORDER)
                    .input((results, ctx) -> {
                        Order order = ctx.get(ReservedKeys.ITEM, Order.class);
                        return Map.of("orderId", order.id(), "index", ctx.get(ReservedKeys.ITEM_INDEX, Integer.class));
                    })
                .build();

        registry.register(subGraph.nodes().get("process").operatorRef(), PROCESS_ORDER);
        var forEach = ForEachOperator.builder(subGraph, registry)
                .sequential(false)
                .itemFailurePolicy(ItemFailurePolicy.CONTINUE)
                .build();

        return Graph.builder("batchOrderWithFailure")
                .node(NODE_LOAD_ORDERS, LOAD_ORDERS)
                    .input((results, ctx) -> Map.of("batchId", ctx.get("batchId", String.class)))
                .node(NODE_LOAD_CONFIG, LOAD_CONFIG)
                    .input((results, ctx) -> Map.of("configId", ctx.get("configId", String.class)))
                .node(NODE_PROCESS_ORDERS, forEach)
                    .dependsOn(NODE_LOAD_ORDERS, NODE_LOAD_CONFIG)
                    .input((results, ctx) -> {
                        Map<String, Object> orders = (Map<String, Object>) results.getRaw(NODE_LOAD_ORDERS);
                        Map<String, Object> config = (Map<String, Object>) results.getRaw(NODE_LOAD_CONFIG);
                        return Map.of(
                                ReservedKeys.ITEMS, orders.get("orders"),
                                ReservedKeys.RT_BATCH_SIZE, config.get("batchSize")
                        );
                    })
                .node(NODE_SUMMARIZE, SUMMARIZE)
                    .dependsOn(NODE_PROCESS_ORDERS)
                    .input((results, ctx) -> results.get(NODE_PROCESS_ORDERS, List.class))
                .build();
    }

    /**
     * Executes the foreach failure-tolerance graph.
     *
     * @return result containing partial successes and one per-item error placeholder
     */
    public static GraphResult execute() {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.execute(graph, new GraphContext(Map.of(
                "batchId", "BATCH-42",
                "configId", "CFG-1"
        )));
    }

    /**
     * Summarizes foreach item results, including {@code __error__} placeholders.
     *
     * @param itemResults foreach item result list
     * @return success/failure summary
     */
    static BatchSummary summarize(List<Map<String, Object>> itemResults) {
        int success = 0;
        int failure = 0;
        for (Map<String, Object> itemResult : itemResults) {
            if (itemResult.containsKey(ReservedKeys.ERROR)) {
                failure++;
            } else if (itemResult.get("process") instanceof ProcessedOrder) {
                success++;
            } else if (itemResult.get("process") instanceof Map<?, ?> process
                    && "processed".equals(process.get("status"))) {
                success++;
            }
        }
        return new BatchSummary(itemResults.size(), success, failure);
    }

    /**
     * Extracts materialized foreach item results.
     *
     * @param result graph result
     * @return foreach item outputs
     */
    public static List<Map<String, Object>> itemResults(GraphResult result) {
        return (List<Map<String, Object>>) result.results().getRaw(NODE_PROCESS_ORDERS);
    }
}