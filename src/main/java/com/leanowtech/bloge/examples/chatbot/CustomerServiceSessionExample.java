package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.ext.builder.PhaseBuilder;
import com.leanowtech.bloge.ext.checkpoint.InMemorySessionStore;
import com.leanowtech.bloge.ext.checkpoint.SessionCheckpoint;
import com.leanowtech.bloge.ext.engine.SessionExecutor;
import com.leanowtech.bloge.ext.model.SessionGraph;
import com.leanowtech.bloge.ext.model.SessionHandle;
import com.leanowtech.bloge.ext.model.SessionIdentity;
import com.leanowtech.bloge.ext.model.SessionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session/phase/round chatbot example using Java fluent API.
 */
@SuppressWarnings({"preview", "unchecked"})
public class CustomerServiceSessionExample {

    static final Operator<Map<String, Object>, Map<String, Object>> GREETER = (input, ctx) -> Map.of(
            "text", "Hello, tell me what you need help with.",
            "sessionId", input.get("sessionId")
    );

    static final Operator<Map<String, Object>, Map<String, Object>> RESPONDER = (input, ctx) -> {
        String userMessage = String.valueOf(input.getOrDefault("userMessage", ""));
        String lowered = userMessage.toLowerCase();
        if (lowered.contains("refund") || lowered.contains("human")) {
            return Map.of("action", "handoff", "done", true, "userMessage", userMessage);
        }
        if (lowered.contains("thanks") || lowered.contains("bye")) {
            return Map.of("action", "close", "done", true, "userMessage", userMessage);
        }
        return Map.of(
                "action", "continue",
                "done", false,
                "reply", "Can you share more details about your issue?",
                "userMessage", userMessage
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SOLVER = (input, ctx) -> Map.of(
            "resolution", "Transferred to specialist for: " + input.getOrDefault("userMessage", "unknown issue"),
            "resolved", true
    );

    static final Operator<Map<String, Object>, Map<String, Object>> CLOSER = (input, ctx) -> Map.of(
            "finalMessage", "Session closed: " + input.getOrDefault("resolution", "no summary")
    );

    public static SessionGraph buildSessionGraph() {
        Graph greetingGraph = Graph.builder("csSessionGreeting")
                .node("greet", GREETER)
                .input((results, ctx) -> Map.of("sessionId", ctx.get("sessionId", String.class)))
                .build();

        Graph triageGraph = Graph.builder("csSessionTriage")
                .node("respond", RESPONDER)
                .input((results, ctx) -> roundInput(ctx))
                .build();

        Graph solveGraph = Graph.builder("csSessionSolve")
                .node("solveCase", SOLVER)
                .input((results, ctx) -> {
                    Map<String, Object> triage = asMap(ctx.get("triage"));
                    Map<String, Object> triageOutput = asMap(triage.get("output"));
                    Map<String, Object> respond = asMap(triageOutput.get("respond"));
                    return Map.of(
                            "action", respond.get("action"),
                            "userMessage", respond.get("userMessage")
                    );
                })
                .build();

        Graph wrapUpGraph = Graph.builder("csSessionWrapUp")
                .node("close", CLOSER)
                .input((results, ctx) -> {
                    Map<String, Object> solve = asMap(ctx.get("solve"));
                    Map<String, Object> solveOutput = asMap(solve.get("output"));
                    Map<String, Object> solveCase = asMap(solveOutput.get("solveCase"));
                    Object resolution = solveCase.get("resolution");
                    if (resolution == null) {
                        Map<String, Object> triage = asMap(ctx.get("triage"));
                        Map<String, Object> triageOutput = asMap(triage.get("output"));
                        Map<String, Object> respond = asMap(triageOutput.get("respond"));
                        resolution = "Final action: " + respond.getOrDefault("action", "unknown");
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("resolution", resolution);
                    return payload;
                })
                .build();

        return SessionGraph.builder("customerServiceSession")
                .idleTimeout(Duration.ofMinutes(5))
                .timeoutPolicyRef("cs_session_timeout_policy")
                .maxTotalRounds(20)
                .maxHistorySize(50)
                .phase(PhaseBuilder.once("greeting").graph(greetingGraph).then("triage").build())
                .phase(PhaseBuilder.round("triage")
                        .graph(triageGraph)
                        .maxRounds(5)
                        .yieldOn("respond")
                        .until(out -> Boolean.TRUE.equals(asMap(out.get("respond")).get("done")))
                        .transition(
                                out -> "handoff".equals(asMap(out.get("respond")).get("action")),
                                "respond.action == handoff",
                                "solve")
                        .then("wrapUp")
                        .build())
                .phase(PhaseBuilder.once("solve").graph(solveGraph).then("wrapUp").build())
                .phase(PhaseBuilder.once("wrapUp").graph(wrapUpGraph).build())
                .build();
    }

    public static SessionExecutor newExecutor(InMemorySessionStore store) {
        GraphEngine engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .build();
        return new SessionExecutor(engine, store);
    }

    public static SessionCheckpoint awaitStatus(InMemorySessionStore store,
                                                String sessionId,
                                                SessionStatus status,
                                                Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        SessionCheckpoint latest = null;
        while (Instant.now().isBefore(deadline)) {
            SessionCheckpoint checkpoint = store.loadSession(sessionId).orElse(null);
            if (checkpoint != null) {
                latest = checkpoint;
            }
            if (checkpoint != null && checkpoint.status() == status) {
                return checkpoint;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for " + status + " session=" + sessionId
                + ", lastStatus=" + (latest == null ? "none" : latest.status()));
    }

    public static void main(String[] args) throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        SessionGraph sessionGraph = buildSessionGraph();
        try (SessionExecutor executor = newExecutor(store)) {
            SessionHandle handle = executor.start(
                    sessionGraph,
                    new GraphContext(Map.of("sessionId", "SESSION-JAVA-001")),
                    SessionIdentity.of("default", "user-123")
            );
            awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
            executor.signal(
                    handle.sessionId(),
                    Map.of("userMessage", "I need a refund and human support"),
                    "user-123"
            );
            // Calling with a different callerId (for example "user-999") would be rejected by default access guard.
            SessionCheckpoint completed = awaitStatus(store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));
            System.out.println("Session completed outputs: " + new LinkedHashMap<>(completed.phaseOutputs()));
        }
    }

    private static Map<String, Object> roundInput(GraphContext ctx) {
        Object round = ctx.get("round");
        if (!(round instanceof Map<?, ?> roundMap)) {
            return Map.of();
        }
        Object input = roundMap.get("input");
        return asMap(input);
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((k, v) -> normalized.put(String.valueOf(k), v));
            return normalized;
        }
        return Map.of();
    }
}
