package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.runtime.timer.InMemoryTimerService;
import com.leanowtech.bloge.state.engine.StateMachineExecutor;
import com.leanowtech.bloge.state.engine.StateMachineResult;
import com.leanowtech.bloge.state.model.StateMachineDef;
import com.leanowtech.bloge.state.model.StateMachineStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the timeout-escalation state machine pattern (evolution plan §4.2, scenario 2).
 *
 * <ul>
 *   <li><b>Normal approval</b>: signal {@code approve} before timeout → machine reaches
 *       {@code approved} terminal state without escalation.</li>
 *   <li><b>Timeout escalation</b>: no signal before timeout → machine auto-transitions to
 *       {@code escalated} state where the escalation graph runs, then a subsequent
 *       {@code approve} signal reaches the {@code approved} terminal state.</li>
 *   <li><b>DSL compilation</b>: verifies the DSL resource compiles to a valid state machine.</li>
 * </ul>
 */
class TimeoutEscalationStateMachineExampleTest {

    // --- Normal approval (no timeout) ---

    @Test
    @Timeout(10)
    void normalApproval_reachesApprovedWithoutEscalation() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        TimeoutEscalationStateMachineExample.registerOperators(registry);

        GraphEngine engine = GraphEngine.builder().registry(registry).build();
        StateMachineExecutor executor = StateMachineExecutor.builder(engine).build();

        StateMachineDef def = TimeoutEscalationStateMachineExample.buildStateMachine(Duration.ofHours(24));

        StateMachineResult result = executor.execute(def, Map.of(
                "requestId", "REQ-T-001", "requester", "alice"));
        assertTrue(result.isWaiting(), "Should be waiting for approve/reject signal");
        assertEquals("pendingApproval", result.instance().currentStateId());

        result = executor.signal(result.instance(), def, "approve",
                Map.of("approvedBy", "bob"));
        assertTrue(result.isCompleted(), "Should reach terminal state");
        assertEquals("approved", result.instance().currentStateId());

        // Verify no escalation happened
        Map<String, Map<String, Object>> stateOutputs = result.instance().stateOutputsSnapshot();
        assertFalse(stateOutputs.containsKey("escalated"),
                "escalated state should not have been visited");
    }

    // --- Timeout escalation ---

    @Test
    @Timeout(10)
    void timeoutEscalation_routesToEscalatedState() throws InterruptedException {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        TimeoutEscalationStateMachineExample.registerOperators(registry);

        InMemoryTimerService timerService = new InMemoryTimerService();
        GraphEngine engine = GraphEngine.builder().registry(registry).build();
        StateMachineExecutor executor = StateMachineExecutor.builder(engine)
                .timerService(timerService)
                .build();

        // Use a very short timeout so the escalation happens quickly
        StateMachineDef def = TimeoutEscalationStateMachineExample.buildStateMachine(Duration.ofMillis(75));

        StateMachineResult result = executor.execute(def, Map.of(
                "requestId", "REQ-T-002", "requester", "carol"));
        assertTrue(result.isWaiting(), "Should be waiting in pendingApproval");

        // Poll until the timeout fires and transitions to escalated
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)
                && "pendingApproval".equals(result.instance().currentStateId())) {
            Thread.sleep(20);
        }

        assertEquals("escalated", result.instance().currentStateId(),
                "Timeout should have auto-transitioned to escalated state");

        // Wait for the escalated state's graph to finish executing and outputs to be stored
        deadline = Instant.now().plusSeconds(2);
        while (Instant.now().isBefore(deadline)
                && !result.instance().stateOutputsSnapshot().containsKey("escalated")) {
            Thread.sleep(20);
        }

        // Verify the escalation graph ran
        Map<String, Map<String, Object>> stateOutputs = result.instance().stateOutputsSnapshot();
        assertTrue(stateOutputs.containsKey("escalated"),
                "escalated state should have outputs from its graph execution");
        Map<String, Object> escalatedOutputs = stateOutputs.get("escalated");
        Map<String, Object> escalateNode = asMap(escalatedOutputs.get("escalate"));
        assertEquals(true, escalateNode.get("escalated"),
                "Escalate node should have executed");
        assertEquals("senior-reviewer", escalateNode.get("escalatedTo"));

        // Verify the timeout event was recorded in history
        assertTrue(result.instance().history().stream()
                        .anyMatch(h -> "__timeout__".equals(h.event())),
                "History should contain the __timeout__ event");

        // Now approve from the escalated state
        result = executor.signal(result.instance(), def, "approve",
                Map.of("approvedBy", "senior-dave"));
        assertTrue(result.isCompleted());
        assertEquals("approved", result.instance().currentStateId());
    }

    // --- Normal rejection ---

    @Test
    @Timeout(10)
    void normalRejection_reachesRejected() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        TimeoutEscalationStateMachineExample.registerOperators(registry);

        GraphEngine engine = GraphEngine.builder().registry(registry).build();
        StateMachineExecutor executor = StateMachineExecutor.builder(engine).build();

        StateMachineDef def = TimeoutEscalationStateMachineExample.buildStateMachine(Duration.ofHours(24));

        StateMachineResult result = executor.execute(def, Map.of(
                "requestId", "REQ-T-003", "requester", "eve"));
        result = executor.signal(result.instance(), def, "reject",
                Map.of("rejectedBy", "frank"));
        assertTrue(result.isCompleted());
        assertEquals("rejected", result.instance().currentStateId());
    }

    // --- DSL compilation ---

    @Test
    void dsl_compilesValidStateMachine() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        StateMachineDef def = TimeoutEscalationStateMachineDslExample.compile(registry);

        assertNotNull(def, "DSL should compile to a valid state machine");
        assertTrue(def.states().containsKey("pendingApproval"));
        assertTrue(def.states().containsKey("escalated"));
        assertTrue(def.states().containsKey("approved"));
        assertTrue(def.states().containsKey("rejected"));

        // Verify the timeout configuration compiled
        assertNotNull(def.states().get("pendingApproval").stateTimeout(),
                "pendingApproval should have a state timeout");
        assertEquals("escalated", def.states().get("pendingApproval").onTimeoutState(),
                "pendingApproval on_timeout should target escalated");
    }

    // --- DSL: normal approval ---

    @Test
    @Timeout(10)
    void dsl_normalApproval_reachesApproved() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        StateMachineDef def = TimeoutEscalationStateMachineDslExample.compile(registry);

        GraphEngine engine = GraphEngine.builder().registry(registry).build();
        StateMachineExecutor executor = StateMachineExecutor.builder(engine).build();

        StateMachineResult result = executor.execute(def, Map.of(
                "requestId", "REQ-DSL-T-001", "requester", "alice"));
        assertTrue(result.isWaiting());
        assertEquals("pendingApproval", result.instance().currentStateId());

        result = executor.signal(result.instance(), def, "approve",
                Map.of("approvedBy", "bob"));
        assertTrue(result.isCompleted());
        assertEquals("approved", result.instance().currentStateId());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((k, v) -> normalized.put(String.valueOf(k), v));
            return normalized;
        }
        return Map.of();
    }
}
