package com.leanowtech.bloge.examples.longrunning;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.time.Instant;
import java.util.Map;
import java.util.Scanner;

public class CargoReadinessWaitReplExample {

    private static final String DSL = """

                graph cargoReadinessWait {

                  node planShipment : PlanShipmentOperator {
                    input {
                      shipmentId  = ctx.shipmentId
                      origin      = ctx.origin
                      destination = ctx.destination
                    }
                    timeout = 5s
                  }

                  node notifyDepot : NotifyDepotOperator {
                    depends_on = [planShipment]
                    input {
                      shipmentId = ctx.shipmentId
                      warehouse  = planShipment.output.warehouse
                      trucks     = planShipment.output.assignedTrucks
                    }
                    timeout = 10s
                  }

                  /// Represents: await awaitFirstTruck { mode = or
                  ///   event "truck.arrived" where truckId = "TRUCK-A" timeout = 12h
                  ///   event "truck.arrived" where truckId = "TRUCK-B" timeout = 12h
                  /// }
                  node awaitFirstTruck : AwaitFirstTruckOperator {
                    depends_on = [notifyDepot]
                    input {
                      notificationId = notifyDepot.output.notificationId
                      shipmentId     = ctx.shipmentId
                    }
                  }

                  node generateLoadingManifest : GenerateManifestOperator {
                    depends_on = [awaitFirstTruck]
                    input {
                      shipmentId   = ctx.shipmentId
                      arrivedTruck = awaitFirstTruck.output.truckId
                      warehouse    = planShipment.output.warehouse
                    }
                    timeout = 15s
                  }

                  node dispatchNotification : DispatchNotificationOperator {
                    depends_on = [generateLoadingManifest]
                    input {
                      shipmentId = ctx.shipmentId
                      manifestId = generateLoadingManifest.output.manifestId
                    }
                    timeout = 10s
                  }
                }
                
            """;

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("PlanShipmentOperator", CargoReadinessWaitDslExample.PLAN_SHIPMENT);
        registry.register("NotifyDepotOperator", CargoReadinessWaitDslExample.NOTIFY_DEPOT);
        registry.registerRaw("AwaitFirstTruckOperator", CargoReadinessWaitDslExample.AWAIT_FIRST_TRUCK);
        registry.register("GenerateManifestOperator", CargoReadinessWaitDslExample.GENERATE_MANIFEST);
        registry.register("DispatchNotificationOperator", CargoReadinessWaitDslExample.DISPATCH_NOTIFICATION);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String shipmentId = ReplHelper.promptString(scanner, "shipmentId", "SHP-DSL-0015");
        String origin = ReplHelper.promptString(scanner, "origin", "Shanghai");
        String destination = ReplHelper.promptString(scanner, "destination", "Rotterdam");
        return Map.of(
                "shipmentId", shipmentId,
                "origin", origin,
                "destination", destination
        );
    }

    public static void main(String[] args) throws Exception {
        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            boolean runAgain;
            do {
                ReplHelper.header("Cargo Readiness Wait REPL");
                Map<String, Object> values = promptContext(scanner);

                var registry = new DefaultOperatorRegistry();
                Graph graph = buildGraph(registry);
                var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
                var engine = runtime.engine();

                GraphResult phase1 = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(phase1);

                if (phase1.isSuspended() || phase1.getStatus("awaitFirstTruck") == NodeStatus.SUSPENDED) {
                    runtime.registerOrCorrelation(phase1.executionId(), "awaitFirstTruck",
                            LongRunningRuntimeExampleSupport.event("truck.arrived", "truckId", "TRUCK-A"),
                            LongRunningRuntimeExampleSupport.event("truck.arrived", "truckId", "TRUCK-B"));
                    System.out.print("Press Enter to simulate TRUCK-B arrival");
                    scanner.nextLine();
                    Map<String, Object> truckArrival = LongRunningRuntimeExampleSupport.payload(
                            "truckId", "TRUCK-B",
                            "arrivedAt", Instant.now().toString(),
                            "depot", "DEPOT-WEST"
                    );
                    engine.publishEvent("truck.arrived", "TRUCK-B", truckArrival);
                    runtime.saveNodeOutput(phase1.executionId(), "cargoReadinessWait", "awaitFirstTruck", truckArrival);

                    GraphResult phase2 = engine.resume(graph, phase1.executionId(), new GraphContext(values));
                    ReplHelper.printResult(phase2);
                }

                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
