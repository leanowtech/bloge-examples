package com.leanowtech.bloge.examples.iteration;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class BatchOrderParallelReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("OrderFetcherOperator", BatchOrderParallelDslExample.FETCH_ORDERS);
        registry.register("OrderValidatorOperator", BatchOrderParallelDslExample.VALIDATE_ORDER);
        registry.register("StockDeductionOperator", BatchOrderParallelDslExample.DEDUCT_STOCK);
        registry.register("BatchSummaryOperator", BatchOrderParallelDslExample.SUMMARIZE);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String customerId = ReplHelper.promptString(scanner, "customerId", "CUST-200");
        return Map.of("customerId", customerId);
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();

        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            boolean runAgain;
            do {
                ReplHelper.header("Batch Order Parallel REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
