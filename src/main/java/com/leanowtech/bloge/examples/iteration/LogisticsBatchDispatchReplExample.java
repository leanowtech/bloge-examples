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

public class LogisticsBatchDispatchReplExample {

    private static final String DSL = """

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
                    delay = 10s
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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("ParcelFetcherOperator", LogisticsBatchDispatchDslExample.FETCH_PARCELS);
        registry.register("RoutePlannerOperator", LogisticsBatchDispatchDslExample.PLAN_ROUTE);
        registry.register("ParcelDispatcherOperator", LogisticsBatchDispatchDslExample.DISPATCH_PARCEL);
        registry.register("BatchStatusCheckerOperator", LogisticsBatchDispatchDslExample.CHECK_ALL_STATUS);
        registry.register("DispatchReportOperator", LogisticsBatchDispatchDslExample.DISPATCH_REPORT);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String depotId = ReplHelper.promptString(scanner, "depotId", "WH-EAST-01");
        return Map.of(
                "warehouseId", depotId,
                "batchId", "BATCH-2025-001"
        );
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
                ReplHelper.header("Logistics Batch Dispatch REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
