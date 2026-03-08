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
 * Tests for the IT Helpdesk chatbot (Plan A — single-round graph).
 * Covers both the Java Fluent API and DSL versions.
 */
@SuppressWarnings({"preview", "unchecked"})
class ItHelpdeskChatbotExampleTest {

    // ── Java API helpers ──────────────────────────────────────────────────────

    private GraphResult executeJavaApi(String userMessage) {
        var registry = new DefaultOperatorRegistry();
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        Graph graph = ItHelpdeskChatbotExample.buildGraph();
        var roundInput = new RoundInput(userMessage, "SESSION-IT-001", ChatHistory.empty());
        var ctx = new GraphContext(Map.of("roundInput", roundInput));
        return engine.executeWithOperators(graph, ctx, ItHelpdeskChatbotExample.buildOperatorMap());
    }

    // ── Java API tests ────────────────────────────────────────────────────────

    @Test
    void testJavaApi_graphExecutesSuccessfully() {
        GraphResult result = executeJavaApi("I forgot my password");
        assertTrue(result.isSuccess());
    }

    @Test
    void testJavaApi_passwordResetIntent_routesToPasswordHandler() {
        GraphResult result = executeJavaApi("I forgot my password and got locked out");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("passwordResetHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("permissionRequestHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("incidentReporter"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
    }

    @Test
    void testJavaApi_passwordReset_responseContainsResetInfo() {
        GraphResult result = executeJavaApi("I forgot my password and got locked out");
        BotResponse response = result.getOutput("passwordResetHandler", BotResponse.class);
        assertNotNull(response);
        assertEquals("password_reset", response.intent());
        assertTrue(response.resolved());
        assertTrue(response.text().toLowerCase().contains("password"));
    }

    @Test
    void testJavaApi_permissionRequestIntent_routesToPermissionHandler() {
        GraphResult result = executeJavaApi("I need access to the production database");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("permissionRequestHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("passwordResetHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("incidentReporter"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
    }

    @Test
    void testJavaApi_incidentReportIntent_routesToIncidentReporter() {
        GraphResult result = executeJavaApi("The application is down and showing an error");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("incidentReporter"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("passwordResetHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("permissionRequestHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
    }

    @Test
    void testJavaApi_incidentReport_responseContainsTicketInfo() {
        GraphResult result = executeJavaApi("The system crashed");
        BotResponse response = result.getOutput("incidentReporter", BotResponse.class);
        assertNotNull(response);
        assertEquals("incident_report", response.intent());
        assertTrue(response.resolved());
    }

    @Test
    void testJavaApi_generalQuestion_routesToFaq() {
        GraphResult result = executeJavaApi("How do I configure VPN?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("passwordResetHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("permissionRequestHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("incidentReporter"));
    }

    @Test
    void testJavaApi_historyIsAppended() {
        GraphResult result = executeJavaApi("I forgot my password");
        BotResponse response = result.getOutput("passwordResetHandler", BotResponse.class);
        assertNotNull(response);
        assertEquals(2, response.updatedHistory().messages().size());
    }

    // ── DSL helpers ───────────────────────────────────────────────────────────

    private GraphResult executeDsl(String userMessage) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = ItHelpdeskChatbotDslExample.buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of(
                "userMessage", userMessage,
                "sessionId",   "SESSION-IT-DSL-001"
        ));
        return engine.execute(graph, ctx);
    }

    // ── DSL tests ─────────────────────────────────────────────────────────────

    @Test
    void testDsl_graphExecutesSuccessfully() {
        GraphResult result = executeDsl("I forgot my password");
        assertTrue(result.isSuccess());
    }

    @Test
    void testDsl_passwordResetIntent_routesToPasswordHandler() {
        GraphResult result = executeDsl("I forgot my password and got locked out");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("passwordResetHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("permissionRequestHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("incidentReporter"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
    }

    @Test
    void testDsl_incidentReportIntent_routesToIncidentReporter() {
        GraphResult result = executeDsl("The application is down and broken");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("incidentReporter"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("passwordResetHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("permissionRequestHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
    }

    @Test
    void testDsl_permissionRequestIntent_routesToPermissionHandler() {
        GraphResult result = executeDsl("I need access to the database");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("permissionRequestHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("passwordResetHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("incidentReporter"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("faqResolver"));
    }

    @Test
    void testDsl_generalQuestion_routesToFaq() {
        GraphResult result = executeDsl("How do I configure VPN?");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("faqResolver"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("passwordResetHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("permissionRequestHandler"));
        assertEquals(NodeStatus.SKIPPED,   result.getStatus("incidentReporter"));
    }

    @Test
    void testDsl_incidentReport_resolvedTrue() {
        GraphResult result = executeDsl("The system crashed");
        var resp = (Map<String, Object>) result.results().getRaw("incidentReporter");
        assertNotNull(resp);
        assertEquals("incident_report", resp.get("intent"));
        assertTrue((Boolean) resp.get("resolved"));
    }
}
