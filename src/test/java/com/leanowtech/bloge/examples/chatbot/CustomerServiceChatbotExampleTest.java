package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.chatbot.ChatbotCommon.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the customer-service chatbot (Plan A — single-round graph).
 * Covers both the Java Fluent API and DSL versions.
 */
@SuppressWarnings({"preview", "unchecked"})
class CustomerServiceChatbotExampleTest {

    // ── Java API helpers ──────────────────────────────────────────────────────

    private GraphResult executeJavaApi(String userMessage) {
        var registry = new DefaultOperatorRegistry();
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        Graph graph = CustomerServiceChatbotExample.buildGraph();
        var roundInput = new RoundInput(userMessage, "SESSION-001", ChatHistory.empty());
        var ctx = new GraphContext(Map.of("roundInput", roundInput));
        return engine.executeWithOperators(graph, ctx, CustomerServiceChatbotExample.buildOperatorMap());
    }

    // ── Java API tests ────────────────────────────────────────────────────────

    @Test
    void testJavaApi_graphExecutesSuccessfully() {
        GraphResult result = executeJavaApi("Where is my order?");
        assertTrue(result.isSuccess());
    }

    @Test
    void testJavaApi_queryOrderIntent_routesToOrderSolver() {
        GraphResult result = executeJavaApi("Where is my order?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    void testJavaApi_queryOrderIntent_responseContainsOrderInfo() {
        GraphResult result = executeJavaApi("Where is my order?");
        BotResponse response = result.getOutput("orderQuerySolver", BotResponse.class);
        assertNotNull(response);
        assertEquals("query_order", response.intent());
        assertTrue(response.resolved());
        assertTrue(response.text().toLowerCase().contains("order"));
    }

    @Test
    void testJavaApi_complaintIntent_routesToComplaintHandler() {
        GraphResult result = executeJavaApi("I want to make a complaint, this is wrong");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    void testJavaApi_complaintIntent_responseIsResolved() {
        GraphResult result = executeJavaApi("I am upset, this is wrong");
        BotResponse response = result.getOutput("complaintHandler", BotResponse.class);
        assertNotNull(response);
        assertEquals("make_complaint", response.intent());
        assertTrue(response.resolved());
    }

    @Test
    void testJavaApi_faqIntent_routesToFaqResolver() {
        GraphResult result = executeJavaApi("What is your return policy?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    void testJavaApi_unknownIntent_routesToFallback() {
        GraphResult result = executeJavaApi("Blorp zork xyzzy");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fallbackResponder"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
    }

    @Test
    void testJavaApi_fallback_notResolved() {
        GraphResult result = executeJavaApi("Blorp zork xyzzy");
        BotResponse response = result.getOutput("fallbackResponder", BotResponse.class);
        assertNotNull(response);
        assertFalse(response.resolved());
        assertEquals("fallback", response.intent());
    }

    @Test
    void testJavaApi_historyIsAppended() {
        GraphResult result = executeJavaApi("Where is my order?");
        BotResponse response = result.getOutput("orderQuerySolver", BotResponse.class);
        assertNotNull(response);
        // History should include user message + bot response
        assertEquals(2, response.updatedHistory().messages().size());
        assertEquals("user", response.updatedHistory().messages().get(0).role());
        assertEquals("bot",  response.updatedHistory().messages().get(1).role());
    }

    // ── DSL helpers ───────────────────────────────────────────────────────────

    private GraphResult executeDsl(String userMessage) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = CustomerServiceChatbotDslExample.buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of(
                "userMessage", userMessage,
                "sessionId",   "SESSION-DSL-001"
        ));
        return engine.execute(graph, ctx);
    }

    // ── DSL tests ─────────────────────────────────────────────────────────────

    @Test
    void testDsl_graphExecutesSuccessfully() {
        GraphResult result = executeDsl("Where is my order?");
        assertTrue(result.isSuccess());
    }

    @Test
    void testDsl_queryOrderIntent_routesToOrderSolver() {
        GraphResult result = executeDsl("Where is my order?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    void testDsl_complaintIntent_routesToComplaintHandler() {
        GraphResult result = executeDsl("I want to make a complaint, this is wrong");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    void testDsl_faqIntent_routesToFaqResolver() {
        GraphResult result = executeDsl("What is your return policy?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    void testDsl_unknownIntent_routesToFallback() {
        GraphResult result = executeDsl("Blorp zork xyzzy");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fallbackResponder"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("orderQuerySolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("complaintHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
    }

    @Test
    void testDsl_complaintIntent_responseTextPresent() {
        GraphResult result = executeDsl("I want to make a complaint");
        var resp = (Map<String, Object>) result.results().getRaw("complaintHandler");
        assertNotNull(resp);
        assertEquals("make_complaint", resp.get("intent"));
        assertTrue((Boolean) resp.get("resolved"));
    }
}
