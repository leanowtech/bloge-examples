package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.ext.checkpoint.InMemorySessionStore;
import com.leanowtech.bloge.ext.checkpoint.SessionCheckpoint;
import com.leanowtech.bloge.ext.engine.SessionExecutor;
import com.leanowtech.bloge.ext.model.SessionHandle;
import com.leanowtech.bloge.ext.model.SessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("preview")
class CustomerServiceSessionExampleTest {

    @Test
    @Timeout(10)
    void javaApi_handoffPath_reachesSolveAndWrapUp() throws Exception {
        SessionCheckpoint checkpoint = executeJavaApi("I need a refund and human support");
        Map<String, Object> phaseOutputs = checkpoint.phaseOutputs();

        assertEquals(SessionStatus.COMPLETED, checkpoint.status());
        assertTrue(phaseOutputs.containsKey("solve"));
        assertTrue(phaseOutputs.containsKey("wrapUp"));
        assertEquals("handoff", asMap(asMap(phaseOutputs.get("triage")).get("respond")).get("action"));
    }

    @Test
    @Timeout(10)
    void javaApi_closePath_skipsSolve() throws Exception {
        SessionCheckpoint checkpoint = executeJavaApi("Thanks, bye");
        Map<String, Object> phaseOutputs = checkpoint.phaseOutputs();

        assertEquals(SessionStatus.COMPLETED, checkpoint.status());
        assertFalse(phaseOutputs.containsKey("solve"));
        assertTrue(phaseOutputs.containsKey("wrapUp"));
        assertEquals("close", asMap(asMap(phaseOutputs.get("triage")).get("respond")).get("action"));
    }

    @Test
    @Timeout(10)
    void dsl_handoffPath_reachesSolveAndWrapUp() throws Exception {
        SessionCheckpoint checkpoint = executeDsl("I need a refund and human support");
        Map<String, Object> phaseOutputs = checkpoint.phaseOutputs();

        assertEquals(SessionStatus.COMPLETED, checkpoint.status());
        assertTrue(phaseOutputs.containsKey("solve"));
        assertTrue(phaseOutputs.containsKey("wrapUp"));
        assertEquals("handoff", asMap(asMap(phaseOutputs.get("triage")).get("respond")).get("action"));
    }

    @Test
    @Timeout(10)
    void dsl_closePath_skipsSolve() throws Exception {
        SessionCheckpoint checkpoint = executeDsl("Thanks, bye");
        Map<String, Object> phaseOutputs = checkpoint.phaseOutputs();

        assertEquals(SessionStatus.COMPLETED, checkpoint.status());
        assertFalse(phaseOutputs.containsKey("solve"));
        assertTrue(phaseOutputs.containsKey("wrapUp"));
        assertEquals("close", asMap(asMap(phaseOutputs.get("triage")).get("respond")).get("action"));
    }

    private SessionCheckpoint executeJavaApi(String userMessage) throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        try (SessionExecutor executor = CustomerServiceSessionExample.newExecutor(store)) {
            SessionHandle handle = executor.start(
                    CustomerServiceSessionExample.buildSessionGraph(),
                    new GraphContext(Map.of("sessionId", "SESSION-JAVA-TEST-001")));
            CustomerServiceSessionExample.awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
            handle.signal(Map.of("userMessage", userMessage));
            return CustomerServiceSessionExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));
        }
    }

    private SessionCheckpoint executeDsl(String userMessage) throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        var sessionGraph = CustomerServiceSessionDslExample.buildSessionGraph(registry);
        try (SessionExecutor executor = CustomerServiceSessionDslExample.newExecutor(registry, store)) {
            SessionHandle handle = executor.start(sessionGraph, new GraphContext(Map.of("sessionId", "SESSION-DSL-TEST-001")));
            CustomerServiceSessionExample.awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
            handle.signal(Map.of("userMessage", userMessage));
            return CustomerServiceSessionExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));
        }
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
