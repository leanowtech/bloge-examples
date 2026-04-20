package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.ExecutionListener;
import com.leanowtech.bloge.core.spi.event.NodeEvent.NodeSuspendedEvent;
import com.leanowtech.bloge.examples.chatbot.ChatbotCommon.*;
import com.leanowtech.bloge.examples.chatbot.CustomerServiceChatbotLongRunningExample.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the long-running customer-service chatbot (Plan B — suspend/resume).
 * Uses a background virtual thread + {@link CountDownLatch} + {@code engine.signal()}
 * to coordinate the suspend/resume lifecycle without a timer service.
 */
@SuppressWarnings({"preview", "unchecked"})
class CustomerServiceChatbotLongRunningExampleTest {

    // ── Java API helpers ──────────────────────────────────────────────────────

    /**
     * Runs the long-running graph on a background virtual thread.
     * Waits for suspension, signals with the given user message, and returns the result.
     */
    private GraphResult executeJavaApi(String userMessage) throws Exception {
        return executeJavaApi(userMessage, "SESSION-LR-TEST-001");
    }

    private GraphResult executeJavaApiWithUniqueSession(String userMessage) throws Exception {
        return executeJavaApi(userMessage, "SESSION-LR-TEST-" + UUID.randomUUID());
    }

    private GraphResult executeJavaApi(String userMessage, String sessionId) throws Exception {
        CountDownLatch suspended = new CountDownLatch(1);
        AtomicReference<String> execIdRef = new AtomicReference<>();

        ExecutionListener listener = new ExecutionListener() {
            @Override
            public void onGraphStart(String graphName, GraphContext ctx) {
                execIdRef.set((String) ctx.get(ReservedKeys.EXECUTION_ID));
            }
            @Override
            public void onNodeSuspended(NodeSuspendedEvent event) {
                if ("awaitUserInput".equals(event.nodeId())) suspended.countDown();
            }
        };

        var registry = new DefaultOperatorRegistry();
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(listener))
                .build();
        Graph graph = CustomerServiceChatbotLongRunningExample.buildGraph();
        var ctx = new GraphContext(Map.of("sessionId", sessionId));

        CompletableFuture<GraphResult> resultFuture = new CompletableFuture<>();
        Thread.ofVirtual().start(() ->
                resultFuture.complete(
                        engine.executeWithOperators(graph, ctx,
                                CustomerServiceChatbotLongRunningExample.buildOperatorMap())));

        assertTrue(suspended.await(5, TimeUnit.SECONDS), "Graph should suspend at awaitUserInput");

        String execId = execIdRef.get();
        assertNotNull(execId, "Execution ID should be captured");

        engine.signal(execId, "awaitUserInput",
                new UserMessagePayload(userMessage, sessionId));

        return resultFuture.get(5, TimeUnit.SECONDS);
    }

    // ── Java API tests ────────────────────────────────────────────────────────

    @Test
    @Timeout(10)
    void testJavaApi_graphSuspendsAndResumes() throws Exception {
        GraphResult result = executeJavaApiWithUniqueSession("Where is my order?");
        assertTrue(result.isSuccess(), "Graph should complete successfully after signal");
    }

    @Test
    @Timeout(10)
    void testJavaApi_greetNodeCompleted() throws Exception {
        GraphResult result = executeJavaApiWithUniqueSession("Where is my order?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("greet"));
    }

    @Test
    @Timeout(10)
    void testJavaApi_awaitUserInputCompleted() throws Exception {
        GraphResult result = executeJavaApiWithUniqueSession("Where is my order?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("awaitUserInput"));
    }

    @Test
    @Timeout(10)
    void testJavaApi_queryOrderIntent_routesToOrderSolver() throws Exception {
        GraphResult result = executeJavaApiWithUniqueSession("Where is my order?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    @Timeout(10)
    void testJavaApi_queryOrderIntent_responseIsCorrect() throws Exception {
        GraphResult result = executeJavaApiWithUniqueSession("Where is my order?");
        BotResponse response = result.getOutput("orderQuerySolver", BotResponse.class);
        assertNotNull(response);
        assertEquals("query_order", response.intent());
        assertTrue(response.resolved());
    }

    @Test
    @Timeout(10)
    void testJavaApi_complaintIntent_routesToComplaintHandler() throws Exception {
        GraphResult result = executeJavaApiWithUniqueSession("I want to make a complaint about this");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    @Timeout(10)
    void testJavaApi_faqIntent_routesToFaqResolver() throws Exception {
        GraphResult result = executeJavaApiWithUniqueSession("What is your return policy?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    @Timeout(10)
    void testJavaApi_unknownIntent_routesToFallback() throws Exception {
        GraphResult result = executeJavaApiWithUniqueSession("Blorp zork xyzzy");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fallbackResponder"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
    }

    // ── DSL helpers ───────────────────────────────────────────────────────────

    private GraphResult executeDsl(String userMessage) throws Exception {
        CountDownLatch suspended = new CountDownLatch(1);
        AtomicReference<String> execIdRef = new AtomicReference<>();
        String sessionId = "SESSION-LR-DSL-TEST-" + UUID.randomUUID();

        ExecutionListener listener = new ExecutionListener() {
            @Override
            public void onGraphStart(String graphName, GraphContext ctx) {
                execIdRef.set((String) ctx.get(ReservedKeys.EXECUTION_ID));
            }
            @Override
            public void onNodeSuspended(NodeSuspendedEvent event) {
                if ("awaitUserInput".equals(event.nodeId())) suspended.countDown();
            }
        };

        var registry = new DefaultOperatorRegistry();
        Graph graph = CustomerServiceChatbotLongRunningDslExample.buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(listener))
                .build();
        var ctx = new GraphContext(Map.of("sessionId", sessionId));

        CompletableFuture<GraphResult> resultFuture = new CompletableFuture<>();
        Thread.ofVirtual().start(() ->
                resultFuture.complete(engine.execute(graph, ctx)));

        assertTrue(suspended.await(5, TimeUnit.SECONDS), "Graph should suspend at awaitUserInput");

        engine.signal(execIdRef.get(), "awaitUserInput",
                Map.of("userMessage", userMessage, "sessionId", sessionId));

        return resultFuture.get(5, TimeUnit.SECONDS);
    }

    // ── DSL tests ─────────────────────────────────────────────────────────────

    @Test
    @Timeout(10)
    void testDsl_graphSuspendsAndResumes() throws Exception {
        GraphResult result = executeDsl("Where is my order?");
        assertTrue(result.isSuccess(), "DSL graph should complete after signal");
    }

    @Test
    @Timeout(10)
    void testDsl_queryOrderIntent_routesToOrderSolver() throws Exception {
        GraphResult result = executeDsl("Where is my order?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    @Timeout(10)
    void testDsl_complaintIntent_routesToComplaintHandler() throws Exception {
        GraphResult result = executeDsl("I want to make a complaint");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    @Timeout(10)
    void testDsl_faqIntent_routesToFaqResolver() throws Exception {
        GraphResult result = executeDsl("What is your return policy?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    @Timeout(10)
    void testDsl_signalPayloadFlowsToSolver() throws Exception {
        GraphResult result = executeDsl("Where is my order?");
        var resp = (Map<String, Object>) result.results().getRaw("orderQuerySolver");
        assertNotNull(resp);
        assertEquals("query_order", resp.get("intent"));
        assertTrue((Boolean) resp.get("resolved"));
    }
}
