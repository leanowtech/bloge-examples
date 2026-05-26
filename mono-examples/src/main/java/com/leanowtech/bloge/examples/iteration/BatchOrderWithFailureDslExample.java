package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;

import java.util.List;
import java.util.Map;

/**
 * DSL-backed foreach example for {@code batch_size} and {@code on_item_failure = continue}.
 */
@SuppressWarnings({"preview", "unchecked"})
public final class BatchOrderWithFailureDslExample {

    private static final String DSL_RESOURCE = "/bloge/batch-order-with-failure.bloge";

    private BatchOrderWithFailureDslExample() {
    }

    static final Operator<Map<String, Object>, Map<String, Object>> LOAD_ORDERS = (input, ctx) -> Map.of(
            "orders", List.of(
                    Map.of("id", "ORD-100", "quantity", 1),
                    Map.of("id", "ORD-FAIL", "quantity", 2),
                    Map.of("id", "ORD-101", "quantity", 3)
            )
    );

    static final Operator<Map<String, Object>, Map<String, Object>> LOAD_CONFIG = (input, ctx) -> Map.of("batchSize", 2);

    static final Operator<Map<String, Object>, Map<String, Object>> PROCESS_ORDER = (input, ctx) -> {
        String orderId = String.valueOf(input.get("orderId"));
        if ("ORD-FAIL".equals(orderId)) {
            throw new IllegalStateException("inventory reservation failed for " + orderId);
        }
        return Map.of("orderId", orderId, "index", input.get("index"), "status", "processed");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SUMMARIZE = (input, ctx) -> {
        List<Map<String, Object>> results = (List<Map<String, Object>>) input.get("results");
        BatchOrderWithFailureExample.BatchSummary summary = BatchOrderWithFailureExample.summarize(results);
        return Map.of(
                "totalProcessed", summary.totalProcessed(),
                "successCount", summary.successCount(),
                "failureCount", summary.failureCount()
        );
    };

    /**
     * Compiles the DSL foreach failure-tolerance resource.
     *
     * @param registry registry used for operator lookup
     * @return compiled graph
     */
    public static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("OrderLoaderOperator", LOAD_ORDERS);
        registry.register("BatchConfigOperator", LOAD_CONFIG);
        registry.register("OrderProcessorOperator", PROCESS_ORDER);
        registry.register("BatchFailureSummaryOperator", SUMMARIZE);
        return ExampleDslResources.loadGraph(DSL_RESOURCE, registry);
    }

    /**
     * Executes the DSL foreach failure-tolerance graph.
     *
     * @return graph result with partial foreach output
     */
    public static GraphResult execute() {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.execute(graph, new GraphContext(Map.of("batchId", "BATCH-42", "configId", "CFG-1")));
    }

    /**
     * Reads the DSL summary map.
     *
     * @param result graph result
     * @return summary map
     */
    public static Map<String, Object> summary(GraphResult result) {
        return (Map<String, Object>) result.results().getRaw(BatchOrderWithFailureExample.NODE_SUMMARIZE);
    }

    static boolean isErrorItem(Map<String, Object> itemResult) {
        return itemResult.containsKey(ReservedKeys.ERROR);
    }
}