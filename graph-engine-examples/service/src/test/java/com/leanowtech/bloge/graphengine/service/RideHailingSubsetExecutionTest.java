package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.ComplexityLimits;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.ExecutionListener;
import com.leanowtech.bloge.core.spi.event.NodeEvent.NodeSuspendedEvent;
import com.leanowtech.bloge.core.schema.SchemaValidationLevel;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes the representative ride-hailing DSL subset so Phase 4 keeps one readable regression
 * fixture for the real-world BPMN patterns called out in the implementation plan.
 */
@SuppressWarnings({"preview", "unchecked"})
class RideHailingSubsetExecutionTest {

    private static final Operator<Map<String, Object>, Map<String, Object>> PASS_THROUGH = (input, ctx) ->
            input == null ? Map.of() : new LinkedHashMap<>(input);

    private static final SuspendableOperator<Map<String, Object>, Map<String, Object>> COLLECT_DISPATCH_CHOICE =
            (input, ctx) -> {
                boolean waitForSignal = Boolean.TRUE.equals(input.get("waitForSignal"));
                if (waitForSignal) {
                    return OperatorResult.suspend("dispatch-choice", null, Duration.ofMinutes(5));
                }
                Object selection = input.get("preselected");
                return OperatorResult.completed(Map.of("selection", selection == null ? "option1" : selection));
            };

    private static final Operator<Map<String, Object>, Map<String, Object>> TEXT_CLASSIFY = (input, ctx) -> {
        String text = Objects.toString(input.get("text"), "").toLowerCase();
        String label;
        if (text.contains("complaint")) {
            label = "complaint";
        } else if (text.contains("loyalty")) {
            label = "loyalty";
        } else {
            label = "generic";
        }
        return Map.of("label", label, "text", input.get("text"));
    };

    private static final Operator<Map<String, Object>, Map<String, Object>> ANSWER_SUMMARY = (input, ctx) ->
            Map.of(
                    "summary",
                    "%s|%s|%s".formatted(
                            input.get("governanceRoute"),
                            input.get("selectedDispatch"),
                            input.get("routedIntent")),
                    "finalOutcome",
                    input.get("finalOutcome"),
                    "tripId",
                    input.get("tripId")
            );

    @Test
    @Timeout(10)
    void testSixWayBranch_selectsThirdBranch_cancelsOthers() throws Exception {
        Scenario scenario = compileScenario(new CountDownLatch(0), List.of());
        GraphResult result = scenario.engine().execute(scenario.graph(), defaultContext()).requireSuccess();

        assertEquals(NodeStatus.COMPLETED, result.getStatus("suburbanLane"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("suburbanLaneEnd"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("airportLane"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("downtownLane"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("eventLane"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("crossBorderLane"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("neighborhoodLane"));
        assertEquals(NodeStatus.CANCELLED, result.getStatus("airportLaneEnd"));
        assertEquals(NodeStatus.CANCELLED, result.getStatus("downtownLaneEnd"));
        assertEquals(NodeStatus.CANCELLED, result.getStatus("eventLaneEnd"));
        assertEquals(NodeStatus.CANCELLED, result.getStatus("crossBorderLaneEnd"));
        assertEquals(NodeStatus.CANCELLED, result.getStatus("neighborhoodLaneEnd"));
    }

    @Test
    @Timeout(10)
    void testNestedGateway_depth5_correctPathSelected() throws Exception {
        Scenario scenario = compileScenario(new CountDownLatch(0), List.of());
        GraphResult result = scenario.engine().execute(scenario.graph(), defaultContext()).requireSuccess();

        assertEquals(NodeStatus.COMPLETED, result.getStatus("orderCreated"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("feeWaive"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("waiverApproved"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("billingReady"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("settlementContinue"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("orderCancelled"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("feeReview"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("waiverDenied"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("billingMissing"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("settlementEscalate"));
    }

    @Test
    @Timeout(10)
    void testCallActivity_sameSubgraph3Times_contextIsolated() throws Exception {
        CountDownLatch authBarrier = new CountDownLatch(3);
        Scenario scenario = compileScenario(authBarrier, List.of());
        GraphContext context = defaultContext();
        context.put("counter", 0);
        context.put("contextMarker", "parent");

        GraphResult result = scenario.engine().execute(scenario.graph(), context).requireSuccess();
        Map<String, Object> audio = subGraphTerminalOutput(result.results().getRaw("authorizeAudio"));
        Map<String, Object> video = subGraphTerminalOutput(result.results().getRaw("authorizeVideo"));
        Map<String, Object> receipt = subGraphTerminalOutput(result.results().getRaw("authorizeReceipt"));

        assertEquals("audio-auth-A1", audio.get("token"));
        assertEquals("video-auth-B2", video.get("token"));
        assertEquals("receipt-auth-C3", receipt.get("token"));
        assertEquals(1, ((Number) audio.get("counter")).intValue());
        assertEquals(1, ((Number) video.get("counter")).intValue());
        assertEquals(1, ((Number) receipt.get("counter")).intValue());
        assertEquals("parent", context.get("contextMarker"));
        assertEquals(0, ((Number) context.get("counter")).intValue());
    }

    @Test
    @Timeout(10)
    void testDColNode_userSelectsOption2_routesToBranch2() throws Exception {
        CountDownLatch suspended = new CountDownLatch(1);
        AtomicReference<String> executionId = new AtomicReference<>();
        AtomicReference<GraphResult> resultRef = new AtomicReference<>();

        ExecutionListener listener = new ExecutionListener() {
            @Override
            public void onGraphStart(String graphName, GraphContext ctx) {
                if ("rideHailingSubset".equals(graphName)) {
                    executionId.set((String) ctx.get(ReservedKeys.EXECUTION_ID));
                }
            }

            @Override
            public void onNodeSuspended(NodeSuspendedEvent event) {
                if ("collectDispatchChoice".equals(event.nodeId())) {
                    suspended.countDown();
                }
            }
        };

        Scenario scenario = compileScenario(new CountDownLatch(0), List.of(listener));
        GraphContext context = defaultContext();
        context.put("waitForDispatchChoice", true);

        Thread executionThread = Thread.ofVirtual().start(() ->
                resultRef.set(scenario.engine().execute(scenario.graph(), context)));

        assertTrue(suspended.await(5, TimeUnit.SECONDS), "dispatch choice should suspend");
        assertNotNull(executionId.get());

        scenario.engine().signal(executionId.get(), "collectDispatchChoice", Map.of("selection", "option2"));
        executionThread.join(5_000);

        GraphResult result = resultRef.get();
        assertNotNull(result);
        result.requireSuccess();
        assertEquals(NodeStatus.COMPLETED, result.getStatus("dispatchOption2"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("dispatchOption1"));
        assertEquals("option2", ((Map<String, Object>) result.results().getRaw("collectDispatchChoice")).get("selection"));
    }

    @Test
    @Timeout(10)
    void testTextClassify_intentComplaint_routesToComplaintHandler() throws Exception {
        Scenario scenario = compileScenario(new CountDownLatch(0), List.of());
        GraphContext context = defaultContext();
        context.put("intentText", "Customer complaint about surge pricing");

        GraphResult result = scenario.engine().execute(scenario.graph(), context).requireSuccess();

        assertEquals(NodeStatus.COMPLETED, result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("loyaltyHandler"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("genericHandler"));
        assertEquals("complaint", ((Map<String, Object>) result.results().getRaw("classifyIntent")).get("label"));
    }

    @Test
    @Timeout(10)
    void testTerminalChain_reachesEnd_graphCompletes() throws Exception {
        Scenario scenario = compileScenario(new CountDownLatch(0), List.of());
        GraphResult result = scenario.engine().execute(scenario.graph(), defaultContext()).requireSuccess();

        assertEquals(NodeStatus.COMPLETED, result.getStatus("answerSummary"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("zrgFinalize"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("endCompleted"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("endManualReview"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("endRefunded"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("endEscalated"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("endRejected"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("endDriverFallback"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("endSafetyFollowup"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("endRebooked"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("endVoucherIssued"));
        assertEquals(NodeStatus.SKIPPED, result.getStatus("endClosed"));
    }

    private Scenario compileScenario(CountDownLatch authBarrier, List<ExecutionListener> listeners) throws Exception {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registerMainGraphOperators(registry);

        GraphLoader loader = new GraphLoader(registry);
        loader.compiler().withSchemaValidation(SchemaValidationLevel.OFF);
        loader.withComplexityLimits(rideHailingImportLimits());
        loader.compiler().registerSubGraph("ride-auth-leg", buildRideAuthSubGraph(authBarrier));
        Graph graph = loader.load(dslPath());
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .listeners(listeners)
                .build();
        return new Scenario(graph, engine);
    }

    /**
     * Returns the import profile required by the representative BPMN-derived graph fixture.
     *
     * <p>BLOGE 0.8.3 makes complexity limits compiler-scoped, so this test opts the loader into
     * the import profile directly instead of relying on global validator mutation. The fixture
     * also has a 20-way terminal fan-out so it can prove cancellation of many inactive end states.</p>
     *
     * @return permissive complexity limits for this intentionally large fixture
     */
    private static ComplexityLimits rideHailingImportLimits() {
        return ComplexityLimits.IMPORT
                .withRecommendedMaxBranchNesting(9)
                .withHardMaxFanOut(20)
                .withRecommendedMaxFanOut(20);
    }

    private void registerMainGraphOperators(DefaultOperatorRegistry registry) {
        registry.register("IngestRideRequestOperator", PASS_THROUGH);
        registry.register("RouteLaneOperator", PASS_THROUGH);
        registry.register("RouteTerminalOperator", PASS_THROUGH);
        registry.register("RouteMergeOperator", PASS_THROUGH);
        registry.register("DecisionSignalOperator", PASS_THROUGH);
        registry.register("DecisionPathOperator", PASS_THROUGH);
        registry.register("AuthAggregationOperator", PASS_THROUGH);
        registry.registerRaw("CollectDispatchChoiceOperator", COLLECT_DISPATCH_CHOICE);
        registry.register("DispatchRouteOperator", PASS_THROUGH);
        registry.register("IntentRouteOperator", PASS_THROUGH);
        registry.register("textClassify", TEXT_CLASSIFY);
        registry.register("AnswerSummaryOperator", ANSWER_SUMMARY);
        registry.register("ZrgFinalizeOperator", PASS_THROUGH);
        registry.register("FinalTerminalOperator", PASS_THROUGH);
    }

    private Graph buildRideAuthSubGraph(CountDownLatch authBarrier) {
        var builder = new com.leanowtech.bloge.core.dsl.GraphBuilder("ride-auth-leg");
        return builder
                .node("authorizeLeg", (Operator<Void, Map<String, Object>>) (input, ctx) -> {
                    ctx.graphContext().put("counter", 1);
                    ctx.graphContext().put("contextMarker", ctx.graphContext().get("legName"));
                    authBarrier.countDown();
                    assertTrue(authBarrier.await(5, TimeUnit.SECONDS), "all auth legs should overlap");
                    return Map.of(
                            "token", ctx.graphContext().get("legName", String.class) + "-" + ctx.graphContext().get("tokenSeed", String.class),
                            "counter", ctx.graphContext().get("counter"),
                            "contextMarker", ctx.graphContext().get("contextMarker"),
                            "tripId", ctx.graphContext().get("tripId", String.class)
                    );
                })
                .build();
    }

    private GraphContext defaultContext() {
        return new GraphContext(Map.ofEntries(
                Map.entry("tripId", "trip-2048"),
                Map.entry("riderId", "rider-7"),
                Map.entry("governanceRoute", "suburban"),
                Map.entry("orderState", "created"),
                Map.entry("cancelFeeProfile", "waive"),
                Map.entry("waiverResult", "approved"),
                Map.entry("billingState", "ready"),
                Map.entry("settlementAction", "continue"),
                Map.entry("dispatchChoice", "option1"),
                Map.entry("waitForDispatchChoice", false),
                Map.entry("intentText", "General driver update request"),
                Map.entry("finalOutcome", "completed")
        ));
    }

    private Path dslPath() throws Exception {
        return Path.of(Objects.requireNonNull(
                RideHailingSubsetExecutionTest.class.getResource("/bloge/ride-hailing-subset.bloge")).toURI());
    }

    private Map<String, Object> subGraphTerminalOutput(Object rawOutput) {
        return (Map<String, Object>) ((Map<String, Object>) rawOutput).get("authorizeLeg");
    }

    private record Scenario(Graph graph, GraphEngine engine) {
    }
}
