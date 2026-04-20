package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.runtime.timer.InMemoryTimerService;
import com.leanowtech.bloge.state.builder.StateMachineBuilder;
import com.leanowtech.bloge.state.engine.StateMachineExecutor;
import com.leanowtech.bloge.state.engine.StateMachineResult;
import com.leanowtech.bloge.state.model.StateMachineDef;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates the timeout-escalation pattern described in the evolution plan §4.2, scenario 2.
 *
 * <p>BPMN Timer Boundary Events allow escalation when a task stays in a state too long.
 * bloge replaces this with per-state {@code timeout} + {@code on_timeout} transitions in a
 * state machine: when a state's timeout expires, the machine automatically transitions to
 * the escalation state, proving that timeout-driven routing works without new primitives.</p>
 *
 * <h3>Scenario: Approval workflow with timeout escalation</h3>
 * <pre>
 *   pendingApproval [initial]
 *       │   timeout → escalated
 *       │   approve → approved
 *       │   reject  → rejected
 *       ▼
 *   escalated
 *       │   approve → approved
 *       │   reject  → rejected
 *       ▼
 *   approved [terminal]
 *   rejected [terminal]
 * </pre>
 *
 * <p>The {@code pendingApproval} state has a short timeout. If no {@code approve} or
 * {@code reject} event arrives before the timeout, the machine transitions to
 * {@code escalated} where a senior reviewer is notified. The escalated state still accepts
 * the same events, eventually reaching a terminal state.</p>
 *
 * @see <a href="../../../../../../docs/implements-plan/bloge-evolution-to-ai-native-graph-engine-plan.md">
 *      Evolution plan §4.2: Timeout escalation via state machine timeout + on_timeout</a>
 */
public final class TimeoutEscalationStateMachineExample {

    static final Operator<Map<String, Object>, Map<String, Object>> REQUEST_APPROVAL = (input, ctx) -> Map.of(
            "requestId", input.get("requestId"),
            "requester", input.get("requester"),
            "status", "pending",
            "assignedTo", "approval-queue"
    );

    static final Operator<Map<String, Object>, Map<String, Object>> ESCALATE = (input, ctx) -> Map.of(
            "requestId", input.get("requestId"),
            "escalatedTo", "senior-reviewer",
            "reason", "Approval timed out — escalated to senior reviewer",
            "escalated", true
    );

    static final Operator<Map<String, Object>, Map<String, Object>> FINALIZE_APPROVAL = (input, ctx) -> Map.of(
            "requestId", input.get("requestId"),
            "decision", "approved",
            "approvedBy", input.getOrDefault("approvedBy", "unknown")
    );

    static final Operator<Map<String, Object>, Map<String, Object>> FINALIZE_REJECTION = (input, ctx) -> Map.of(
            "requestId", input.get("requestId"),
            "decision", "rejected",
            "rejectedBy", input.getOrDefault("rejectedBy", "unknown")
    );

    private TimeoutEscalationStateMachineExample() {}

    /**
     * Builds the approval state machine with a timeout-escalation path.
     *
     * @param approvalTimeout timeout duration for the {@code pendingApproval} state;
     *                        callers can pass a short duration for testing
     * @return immutable state-machine definition
     */
    public static StateMachineDef buildStateMachine(Duration approvalTimeout) {
        Graph pendingGraph = Graph.builder("pendingApproval")
                .node("requestApproval", REQUEST_APPROVAL)
                    .input((results, ctx) -> Map.of(
                            "requestId", ctx.get("requestId", String.class),
                            "requester", ctx.get("requester", String.class)))
                .build();

        Graph escalatedGraph = Graph.builder("escalation")
                .node("escalate", ESCALATE)
                    .input((results, ctx) -> {
                        Map<String, Object> pending = asMap(ctx.get("pendingApproval"));
                        Map<String, Object> output = asMap(pending.get("output"));
                        Map<String, Object> req = asMap(output.get("requestApproval"));
                        return Map.of("requestId", req.getOrDefault("requestId", ctx.get("requestId", String.class)));
                    })
                .build();

        Graph approvedGraph = Graph.builder("approvedOutcome")
                .node("finalizeApproval", FINALIZE_APPROVAL)
                    .input((results, ctx) -> Map.of(
                            "requestId", ctx.get("requestId", String.class),
                            "approvedBy", ctx.get("approvedBy") != null ? ctx.get("approvedBy", String.class) : "system"))
                .build();

        Graph rejectedGraph = Graph.builder("rejectedOutcome")
                .node("finalizeRejection", FINALIZE_REJECTION)
                    .input((results, ctx) -> Map.of(
                            "requestId", ctx.get("requestId", String.class),
                            "rejectedBy", ctx.get("rejectedBy") != null ? ctx.get("rejectedBy", String.class) : "system"))
                .build();

        return StateMachineBuilder.create("timeoutEscalation")
                .maxTransitions(10)
                .maxStateVisits(3)
                .state("pendingApproval").initial()
                    .graph(pendingGraph)
                    .on("approve").goTo("approved")
                    .on("reject").goTo("rejected")
                    .timeout(approvalTimeout).onTimeout("escalated")
                    .done()
                .state("escalated")
                    .graph(escalatedGraph)
                    .on("approve").goTo("approved")
                    .on("reject").goTo("rejected")
                    .done()
                .state("approved").terminal()
                    .graph(approvedGraph)
                    .done()
                .state("rejected").terminal()
                    .graph(rejectedGraph)
                    .done()
                .build();
    }

    /**
     * Registers operators so the DSL variant can share them.
     */
    public static void registerOperators(DefaultOperatorRegistry registry) {
        registry.register("RequestApprovalOperator", REQUEST_APPROVAL);
        registry.register("EscalateOperator", ESCALATE);
        registry.register("FinalizeApprovalOperator", FINALIZE_APPROVAL);
        registry.register("FinalizeRejectionOperator", FINALIZE_REJECTION);
    }

    /**
     * Demonstrates the timeout-escalation and normal-approval paths.
     */
    public static void main(String[] args) throws InterruptedException {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registerOperators(registry);

        GraphEngine engine = GraphEngine.builder().registry(registry).build();
        StateMachineExecutor executor = StateMachineExecutor.builder(engine).build();

        // --- Normal approval (no timeout) ---
        StateMachineDef def = buildStateMachine(Duration.ofHours(24));
        StateMachineResult result = executor.execute(def, Map.of(
                "requestId", "REQ-001", "requester", "alice"));
        System.out.println("Initial: " + describe(result));

        result = executor.signal(result.instance(), def, "approve",
                Map.of("approvedBy", "bob"));
        System.out.println("After approve: " + describe(result));

        // --- Timeout escalation (requires a timer-backed executor) ---
        System.out.println("\n--- Timeout escalation path ---");
        StateMachineDef shortDef = buildStateMachine(Duration.ofMillis(1));
        InMemoryTimerService timerService = new InMemoryTimerService();
        StateMachineExecutor timerBackedExecutor = StateMachineExecutor.builder(engine)
                .timerService(timerService)
                .build();
        result = timerBackedExecutor.execute(shortDef, Map.of(
                "requestId", "REQ-002", "requester", "carol"));
        System.out.println("Before timeout: " + describe(result));
        awaitState(result, "escalated", Duration.ofSeconds(2));
        System.out.println("After timeout: " + describe(result));

        result = timerBackedExecutor.signal(result.instance(), shortDef, "approve",
                Map.of("approvedBy", "senior-dave"));
        System.out.println("After senior approve: " + describe(result));
        System.out.println("State outputs: " + new LinkedHashMap<>(result.instance().stateOutputsSnapshot()));
    }

    private static void awaitState(StateMachineResult result,
                                   String expectedStateId,
                                   Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (expectedStateId.equals(result.instance().currentStateId())) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Timed out waiting for state '" + expectedStateId
                + "', lastState=" + result.instance().currentStateId());
    }

    private static String describe(StateMachineResult result) {
        return result.status() + " state=" + result.instance().currentStateId()
                + " awaited=" + result.awaitedEvents();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, entry) -> normalized.put(String.valueOf(key), entry));
            return normalized;
        }
        return Map.of();
    }
}
