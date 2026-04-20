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
 * Tests for the e-commerce chatbot (Plan A — single-round graph).
 * Covers both the Java Fluent API and DSL versions.
 */
@SuppressWarnings({"preview", "unchecked"})
class EcommerceChatbotExampleTest {

    // ── Java API helpers ──────────────────────────────────────────────────────

    private GraphResult executeJavaApi(String userMessage) {
        var registry = new DefaultOperatorRegistry();
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        Graph graph = EcommerceChatbotExample.buildGraph();
        var roundInput = new RoundInput(userMessage, "SESSION-EC-001", ChatHistory.empty());
        var ctx = new GraphContext(Map.of("roundInput", roundInput));
        return engine.executeWithOperators(graph, ctx, EcommerceChatbotExample.buildOperatorMap());
    }

    // ── Java API tests ────────────────────────────────────────────────────────

    @Test
    void testJavaApi_graphExecutesSuccessfully() {
        GraphResult result = executeJavaApi("I'm looking for a laptop");
        assertTrue(result.isSuccess());
    }

    @Test
    void testJavaApi_searchProductIntent_routesToProductSearch() {
        GraphResult result = executeJavaApi("I'm looking for a good laptop");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("productSearchSolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("productComparator"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("recommendationEngine"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    void testJavaApi_searchProductIntent_responseContainsProductInfo() {
        GraphResult result = executeJavaApi("I'm looking for a good laptop");
        BotResponse response = result.getOutput("productSearchSolver", BotResponse.class);
        assertNotNull(response);
        assertEquals("search_product", response.intent());
        assertTrue(response.resolved());
    }

    @Test
    void testJavaApi_compareIntent_routesToComparator() {
        GraphResult result = executeJavaApi("Compare the two models vs each other");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("productComparator"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("productSearchSolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("recommendationEngine"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    void testJavaApi_recommendIntent_routesToRecommendation() {
        GraphResult result = executeJavaApi("What do you recommend for gaming?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("recommendationEngine"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("productSearchSolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("productComparator"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    void testJavaApi_unknownIntent_routesToFallback() {
        GraphResult result = executeJavaApi("Blorp zork xyzzy");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fallbackResponder"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("productSearchSolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("productComparator"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("recommendationEngine"));
    }

    @Test
    void testJavaApi_fallback_notResolved() {
        GraphResult result = executeJavaApi("Blorp zork xyzzy");
        BotResponse response = result.getOutput("fallbackResponder", BotResponse.class);
        assertNotNull(response);
        assertFalse(response.resolved());
    }

    @Test
    void testJavaApi_historyIsAppended() {
        GraphResult result = executeJavaApi("I'm looking for a laptop");
        BotResponse response = result.getOutput("productSearchSolver", BotResponse.class);
        assertNotNull(response);
        assertEquals(2, response.updatedHistory().messages().size());
    }

    // ── DSL helpers ───────────────────────────────────────────────────────────

    private GraphResult executeDsl(String userMessage) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = EcommerceChatbotDslExample.buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of(
                "userMessage", userMessage,
                "sessionId",   "SESSION-EC-DSL-001"
        ));
        return engine.execute(graph, ctx);
    }

    // ── DSL tests ─────────────────────────────────────────────────────────────

    @Test
    void testDsl_graphExecutesSuccessfully() {
        GraphResult result = executeDsl("I'm looking for a laptop");
        assertTrue(result.isSuccess());
    }

    @Test
    void testDsl_searchProductIntent_routesToProductSearch() {
        GraphResult result = executeDsl("I'm looking for a good laptop");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("productSearchSolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("productComparator"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("recommendationEngine"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    void testDsl_compareIntent_routesToComparator() {
        GraphResult result = executeDsl("Can you compare the two models vs each other?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("productComparator"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("productSearchSolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("recommendationEngine"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    void testDsl_recommendIntent_routesToRecommendation() {
        GraphResult result = executeDsl("What do you recommend?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("recommendationEngine"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("productSearchSolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("productComparator"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("fallbackResponder"));
    }

    @Test
    void testDsl_unknownIntent_routesToFallback() {
        GraphResult result = executeDsl("Blorp zork xyzzy");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fallbackResponder"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("productSearchSolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("productComparator"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("recommendationEngine"));
    }

    @Test
    void testDsl_searchProduct_resolvedTrue() {
        GraphResult result = executeDsl("I'm looking for a laptop");
        var resp = (Map<String, Object>) result.results().getRaw("productSearchSolver");
        assertNotNull(resp);
        assertEquals("search_product", resp.get("intent"));
        assertTrue((Boolean) resp.get("resolved"));
    }
}
