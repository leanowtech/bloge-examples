package com.leanowtech.bloge.examples.logistics;

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

public class ShipmentPlanningReplExample {

    private static final String DSL = """

                graph shipmentPlanning {
                  ///  fetchOrder: reads ctx.orderId → {orderId, origin, destination, weight, isUrgent, items}
                  node fetchOrder : FetchOrderOperator {
                    input { orderId = ctx.orderId }
                    timeout = 3s
                  }
                  ///  parallel fan-out: lookupWarehouse, selectCarrier, optimizeRoute run concurrently
                  node lookupWarehouse : LookupWarehouseOperator {
                    depends_on = [fetchOrder]
                    input {
                      origin = fetchOrder.output.origin
                    }
                    timeout = 2s
                  }
                  node selectCarrier : SelectCarrierOperator {
                    depends_on = [fetchOrder]
                    input {
                      destination = fetchOrder.output.destination
                      weight      = fetchOrder.output.weight
                    }
                    retry = { attempts: 2, backoff: 200ms, strategy: exponential }
                    fallback = { carrierId: "DEFAULT", name: "Standard Post", baseRate: 15.0, estimatedDays: 7 }
                  }
                  node optimizeRoute : OptimizeRouteOperator {
                    depends_on = [fetchOrder]
                    input {
                      origin      = fetchOrder.output.origin
                      destination = fetchOrder.output.destination
                    }
                    timeout = 5s
                  }
                  ///  parallel fan-in: all 3 planning nodes run concurrently; calculateCost waits for all
                  node calculateCost : CalculateCostOperator {
                    depends_on = [lookupWarehouse, selectCarrier, optimizeRoute]
                    input {
                      warehouse = lookupWarehouse.output
                      carrier   = selectCarrier.output
                      route     = optimizeRoute.output
                    }
                  }
                  ///  decideShipMode: reads cost.total, order.isUrgent → {mode, reason}
                  node decideShipMode : DecideShipModeOperator {
                    depends_on = [calculateCost]
                    input {
                      cost  = calculateCost.output
                      order = fetchOrder.output
                    }
                  }
                  ///  branch on mode: express → dispatchExpress, standard → dispatchStandard, otherwise → dispatchConsolidated
                  branch on decideShipMode.output.mode {
                    "express"  -> dispatchExpress
                    "standard" -> dispatchStandard
                    otherwise  -> dispatchConsolidated
                  }
                  ///  branch outcomes: only one dispatch node will execute
                  node dispatchExpress : DispatchExpressOperator {
                    depends_on = [decideShipMode]
                    input {
                      orderId = fetchOrder.output.orderId
                      mode    = "express"
                    }
                  }
                  node dispatchStandard : DispatchStandardOperator {
                    depends_on = [decideShipMode]
                    input {
                      orderId = fetchOrder.output.orderId
                      mode    = "standard"
                    }
                  }
                  node dispatchConsolidated : DispatchConsolidatedOperator {
                    depends_on = [decideShipMode]
                    input {
                      orderId = fetchOrder.output.orderId
                      mode    = "consolidated"
                    }
                  }
                }
                
            """;

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("FetchOrderOperator", ShipmentPlanningDslExample.FETCH_ORDER);
        registry.register("LookupWarehouseOperator", ShipmentPlanningDslExample.LOOKUP_WAREHOUSE);
        registry.register("SelectCarrierOperator", ShipmentPlanningDslExample.SELECT_CARRIER);
        registry.register("OptimizeRouteOperator", ShipmentPlanningDslExample.OPTIMIZE_ROUTE);
        registry.register("CalculateCostOperator", ShipmentPlanningDslExample.CALCULATE_COST);
        registry.register("DecideShipModeOperator", ShipmentPlanningDslExample.DECIDE_SHIP_MODE);
        registry.register("DispatchExpressOperator", ShipmentPlanningDslExample.DISPATCH_EXPRESS);
        registry.register("DispatchStandardOperator", ShipmentPlanningDslExample.DISPATCH_STANDARD);
        registry.register("DispatchConsolidatedOperator", ShipmentPlanningDslExample.DISPATCH_CONSOLIDATED);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String orderId = ReplHelper.promptString(scanner, "orderId", "ORD-8001");
        return Map.of("orderId", orderId);
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
                ReplHelper.header("Shipment Planning REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
