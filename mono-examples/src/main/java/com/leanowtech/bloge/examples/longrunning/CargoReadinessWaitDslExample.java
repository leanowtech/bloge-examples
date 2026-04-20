package com.leanowtech.bloge.examples.longrunning;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DSL version of the cargo-readiness OR-mode await example.
 *
 * <p>Loads the graph from an inline DSL string that mirrors
 * {@code cargo-readiness-wait.bloge}.  The {@code awaitFirstTruck} node
 * represents the {@code await ... mode = or} DSL block.
 */
@SuppressWarnings("preview")
public class CargoReadinessWaitDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> PLAN_SHIPMENT = (input, ctx) -> {
        Thread.sleep(30);
        System.out.println("  [planShipment] shipmentId=" + input.get("shipmentId") + " Shanghai→Rotterdam");
        return Map.of("shipmentId", input.get("shipmentId"),
                "assignedTrucks", List.of("TRUCK-A", "TRUCK-B"),
                "warehouse", "DEPOT-WEST");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> NOTIFY_DEPOT = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [notifyDepot]  trucks assigned");
        return Map.of("notificationId", "NOTIF-DEPOT-DSL", "scheduledAt", Instant.now().toString());
    };

    /** OR-mode: suspends until any assigned truck reports arrival. */
    static final SuspendableOperator<Map<String, Object>, Map<String, Object>> AWAIT_FIRST_TRUCK = (input, ctx) -> {
        String shipmentId = ctx.graphContext().get("shipmentId", String.class);
        System.out.println("  [awaitFirstTruck] SUSPENDING — OR-mode shipmentId=" + shipmentId);
        return OperatorResult.suspend("truck.arrived:" + shipmentId, null, java.time.Duration.ofSeconds(2));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> GENERATE_MANIFEST = (input, ctx) -> {
        Thread.sleep(40);
        System.out.println("  [generateLoadingManifest] truck=" + input.get("arrivedTruck") + " manifest issued");
        return Map.of("manifestId", "MFST-DSL-001", "issuedAt", Instant.now().toString());
    };

    static final Operator<Map<String, Object>, Map<String, Object>> DISPATCH_NOTIFICATION = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [dispatchNotification] manifest=" + input.get("manifestId"));
        return Map.of("dispatchId", "DISP-DSL-001", "estimatedArrival", "2026-06-01T14:00:00Z");
    };

    public static void main(String[] args) throws Exception {
        var registry = new DefaultOperatorRegistry();
        registry.register("PlanShipmentOperator",        PLAN_SHIPMENT);
        registry.register("NotifyDepotOperator",         NOTIFY_DEPOT);
        // Represents: await awaitFirstTruck { mode = or event "truck.arrived" where truckId in ["TRUCK-A","TRUCK-B"] }
        registry.registerRaw("AwaitFirstTruckOperator",     AWAIT_FIRST_TRUCK);
        registry.register("GenerateManifestOperator",    GENERATE_MANIFEST);
        registry.register("DispatchNotificationOperator", DISPATCH_NOTIFICATION);

        var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
        var engine = runtime.engine();
        var loader = new GraphLoader(registry);

        String dsl = """
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

        Graph graph = loader.load(dsl);

        var ctx = new GraphContext(Map.of(
                "shipmentId",  "SHP-DSL-0015",
                "origin",      "Shanghai",
                "destination", "Rotterdam"
        ));

        System.out.println("\n═══ Phase 1 (DSL): Plan shipment and await truck ═══");
        GraphResult phase1 = engine.execute(graph, ctx);
        System.out.printf("%nSuspended: %s  executionId: %s%n",
                phase1.isSuspended(), phase1.executionId());
        for (var e : phase1.statusMap().entrySet()) {
            System.out.printf("  %-30s → %s%n", e.getKey(), e.getValue());
        }

        String execId = phase1.executionId();
        runtime.registerOrCorrelation(execId, "awaitFirstTruck",
                LongRunningRuntimeExampleSupport.event("truck.arrived", "truckId", "TRUCK-A"),
                LongRunningRuntimeExampleSupport.event("truck.arrived", "truckId", "TRUCK-B"));

        System.out.println("\n═══ Phase 2 (DSL): TRUCK-B arrives ═══");
        Thread.sleep(150);
        Map<String, Object> truckArrival = LongRunningRuntimeExampleSupport.payload(
                "truckId", "TRUCK-B",
                "arrivedAt", Instant.now().toString(),
                "depot", "DEPOT-WEST"
        );
        engine.publishEvent("truck.arrived", "TRUCK-B", truckArrival);
        System.out.println("TRUCK-B arrived — OR-mode correlation MATCHED");

        runtime.saveNodeOutput(execId, "cargoReadinessWait", "awaitFirstTruck", truckArrival);

        System.out.println("\n═══ Phase 3 (DSL): Resume cargo loading ═══");
        GraphResult phase3 = engine.resume(graph, execId, ctx);

        System.out.println("\n═══ Final DSL Result ═══");
        System.out.println("Success: " + phase3.isSuccess());
        for (var e : phase3.statusMap().entrySet()) {
            System.out.printf("  %-30s → %s%n", e.getKey(), e.getValue());
        }
        if (phase3.getStatus("dispatchNotification") == NodeStatus.COMPLETED) {
            System.out.println("Dispatch: " + phase3.results().getRaw("dispatchNotification"));
        }
    }
}
