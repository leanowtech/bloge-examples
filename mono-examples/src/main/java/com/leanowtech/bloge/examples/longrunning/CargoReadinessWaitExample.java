package com.leanowtech.bloge.examples.longrunning;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Demonstrates the long-running <em>cargo readiness</em> pattern using
 * OR-mode runtime event matching (execution resumes when <em>any</em> truck arrives).
 *
 * <p>After a shipment is planned and depot is notified, execution suspends at
 * {@code awaitFirstTruck} waiting for <em>one of</em> the assigned trucks to
 * report that it has arrived:
 * <ul>
 *   <li>{@code "truck.arrived"} with key {@code truckId = TRUCK-A}</li>
 *   <li>{@code "truck.arrived"} with key {@code truckId = TRUCK-B}</li>
 * </ul>
 * Whichever truck reports first causes the correlation to resolve, and the
 * downstream loading-manifest step runs.
 *
 * <h2>Graph layout</h2>
 * <pre>
 * planShipment → notifyDepot
 *                    ↓
 *        [SUSPEND awaitFirstTruck]  (OR-mode: any truck)
 *                    ↓
 *           generateLoadingManifest → dispatchNotification
 * </pre>
 *
 * <h2>Long-running lifecycle</h2>
 * <ol>
 *   <li>Execute → suspends at {@code awaitFirstTruck}.</li>
 *   <li>TRUCK-B reports arrival first.</li>
 *   <li>OR-mode matcher state resolves immediately → save runtime node output → {@code resume()}.</li>
 * </ol>
 */
@SuppressWarnings("preview")
public class CargoReadinessWaitExample {

    // ── Records ───────────────────────────────────────────────────────────────

    public record ShipmentQuery(String shipmentId, String origin, String destination) {}
    public record ShipmentPlan(String shipmentId, List<String> assignedTrucks, String warehouse) {}

    public record DepotNotifyInput(String shipmentId, String warehouse, List<String> trucks) {}
    public record DepotNotifyResult(String notificationId, String scheduledAt) {}

    public record LoadingManifestInput(String shipmentId, String arrivedTruck, String warehouse) {}
    public record LoadingManifestResult(String manifestId, String issuedAt) {}

    public record DispatchInput(String shipmentId, String manifestId) {}
    public record DispatchResult(String dispatchId, String estimatedArrival) {}

    // ── Operators ─────────────────────────────────────────────────────────────

    static final Operator<ShipmentQuery, ShipmentPlan> PLAN_SHIPMENT = (input, ctx) -> {
        Thread.sleep(30);
        System.out.printf("  [planShipment]  shipmentId=%s %s→%s%n",
                input.shipmentId(), input.origin(), input.destination());
        return new ShipmentPlan(input.shipmentId(), List.of("TRUCK-A", "TRUCK-B"), "DEPOT-WEST");
    };

    static final Operator<DepotNotifyInput, DepotNotifyResult> NOTIFY_DEPOT = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [notifyDepot]   trucks assigned: " + input.trucks());
        return new DepotNotifyResult("NOTIF-DEPOT-001", Instant.now().toString());
    };

    /**
     * OR-mode: suspends until ANY assigned truck reports arrival.
     * In production, helper-backed runtime matchers would be registered per truck ID.
     */
    static final SuspendableOperator<DepotNotifyResult, Map<String, Object>> AWAIT_FIRST_TRUCK = (input, ctx) -> {
        String shipmentId = ctx.graphContext().get("shipmentId", String.class);
        System.out.println("  [awaitFirstTruck] SUSPENDING — OR-mode, waiting for any truck shipmentId=" + shipmentId);
        return OperatorResult.suspend("truck.arrived:" + shipmentId, null, Duration.ofSeconds(2));
    };

    static final Operator<LoadingManifestInput, LoadingManifestResult> GENERATE_MANIFEST = (input, ctx) -> {
        Thread.sleep(40);
        System.out.println("  [generateLoadingManifest] truck=" + input.arrivedTruck() + " manifest issued");
        return new LoadingManifestResult("MFST-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString());
    };

    static final Operator<DispatchInput, DispatchResult> DISPATCH_NOTIFICATION = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [dispatchNotification] manifest=" + input.manifestId());
        return new DispatchResult("DISP-001", "2026-06-01T14:00:00Z");
    };

    // ── Main ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var registry = new DefaultOperatorRegistry();
        var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
        var engine = runtime.engine();

        // ── Build graph ───────────────────────────────────────────────────────
        Graph graph = Graph.builder("cargoReadinessWait")
                .node("planShipment", PLAN_SHIPMENT)
                    .input((results, ctx) -> new ShipmentQuery(
                            ctx.get("shipmentId", String.class),
                            ctx.get("origin", String.class),
                            ctx.get("destination", String.class)))
                .node("notifyDepot", NOTIFY_DEPOT)
                    .dependsOn("planShipment")
                    .input((results, ctx) -> {
                        ShipmentPlan plan = results.get("planShipment", ShipmentPlan.class);
                        return new DepotNotifyInput(plan.shipmentId(), plan.warehouse(), plan.assignedTrucks());
                    })
                .suspendNode("awaitFirstTruck", AWAIT_FIRST_TRUCK)
                    .dependsOn("notifyDepot")
                    .input((results, ctx) -> results.get("notifyDepot", DepotNotifyResult.class))
                .node("generateLoadingManifest", GENERATE_MANIFEST)
                    .dependsOn("awaitFirstTruck")
                    .input((results, ctx) -> {
                        ShipmentPlan plan = results.get("planShipment", ShipmentPlan.class);
                        Object raw = results.getRaw("awaitFirstTruck");
                        String arrivedTruck = "UNKNOWN";
                        if (raw instanceof Map<?,?> m) { Object t = m.get("truckId"); if (t instanceof String s) arrivedTruck = s; }
                        return new LoadingManifestInput(plan.shipmentId(), arrivedTruck, plan.warehouse());
                    })
                .node("dispatchNotification", DISPATCH_NOTIFICATION)
                    .dependsOn("generateLoadingManifest")
                    .input((results, ctx) -> {
                        ShipmentPlan plan = results.get("planShipment", ShipmentPlan.class);
                        LoadingManifestResult manifest = results.get("generateLoadingManifest", LoadingManifestResult.class);
                        return new DispatchInput(plan.shipmentId(), manifest.manifestId());
                    })
                .build();

        var ctx = new GraphContext(Map.of(
                "shipmentId",   "SHP-2024-0015",
                "origin",       "Shanghai",
                "destination",  "Rotterdam"
        ));

        // ── Phase 1: execute until suspension ────────────────────────────────
        System.out.println("\n═══ Phase 1: Plan shipment and await truck arrival ═══");
        GraphResult phase1 = engine.executeWithOperators(graph, ctx, Map.of(
                "planShipment",          PLAN_SHIPMENT,
                "notifyDepot",           NOTIFY_DEPOT,
                "awaitFirstTruck",       AWAIT_FIRST_TRUCK,
                "generateLoadingManifest", GENERATE_MANIFEST,
                "dispatchNotification",  DISPATCH_NOTIFICATION
        ));

        System.out.printf("%nSuspended: %s  executionId: %s%n",
                phase1.isSuspended(), phase1.executionId());
        ShipmentPlan plan = phase1.getOutput("planShipment", ShipmentPlan.class);
        System.out.println("Assigned trucks: " + plan.assignedTrucks());
        for (var e : phase1.statusMap().entrySet()) {
            System.out.printf("  %-28s → %s%n", e.getKey(), e.getValue());
        }

        String execId = phase1.executionId();

        // Register OR-mode runtime matcher state for each assigned truck.
        runtime.registerOrCorrelation(execId, "awaitFirstTruck",
                LongRunningRuntimeExampleSupport.event("truck.arrived", "truckId", plan.assignedTrucks().get(0)),
                LongRunningRuntimeExampleSupport.event("truck.arrived", "truckId", plan.assignedTrucks().get(1)));

        // ── Phase 2: TRUCK-B arrives first ───────────────────────────────────
        System.out.println("\n═══ Phase 2: TRUCK-B reports arrival ═══");
        Thread.sleep(150);
        Map<String, Object> truckArrival = LongRunningRuntimeExampleSupport.payload(
                "truckId", "TRUCK-B",
                "arrivedAt", Instant.now().toString(),
                "depot", "DEPOT-WEST"
        );
        engine.publishEvent("truck.arrived", "TRUCK-B", truckArrival);
        System.out.println("TRUCK-B arrived at DEPOT-WEST — OR-mode correlation MATCHED");

        runtime.saveNodeOutput(execId, "cargoReadinessWait", "awaitFirstTruck", truckArrival);

        // ── Phase 3: resume ────────────────────────────────────────────────────
        System.out.println("\n═══ Phase 3: Resume cargo loading workflow ═══");
        registry.register("planShipment",          PLAN_SHIPMENT);
        registry.register("notifyDepot",           NOTIFY_DEPOT);
        registry.registerRaw("awaitFirstTruck",       AWAIT_FIRST_TRUCK);
        registry.register("generateLoadingManifest", GENERATE_MANIFEST);
        registry.register("dispatchNotification",  DISPATCH_NOTIFICATION);

        GraphResult phase3 = engine.resume(graph, execId, ctx);

        System.out.println("\n═══ Final Result ═══");
        System.out.println("Success: " + phase3.isSuccess());
        for (var e : phase3.statusMap().entrySet()) {
            System.out.printf("  %-28s → %s%n", e.getKey(), e.getValue());
        }
        if (phase3.getStatus("dispatchNotification") == NodeStatus.COMPLETED) {
            DispatchResult dispatch = phase3.getOutput("dispatchNotification", DispatchResult.class);
            System.out.println("Dispatch: " + dispatch);
        }
    }
}
