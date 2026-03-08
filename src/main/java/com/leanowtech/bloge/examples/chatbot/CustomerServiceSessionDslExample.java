package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.ext.checkpoint.InMemorySessionStore;
import com.leanowtech.bloge.ext.checkpoint.SessionCheckpoint;
import com.leanowtech.bloge.ext.compiler.SessionDslCompiler;
import com.leanowtech.bloge.ext.engine.SessionExecutor;
import com.leanowtech.bloge.ext.model.SessionGraph;
import com.leanowtech.bloge.ext.model.SessionHandle;
import com.leanowtech.bloge.ext.model.SessionStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session/phase/round chatbot example using DSL.
 */
@SuppressWarnings("preview")
public class CustomerServiceSessionDslExample {

    private static final String DSL_RESOURCE = "bloge/customer-service-session.bloge";

    public static SessionGraph buildSessionGraph(DefaultOperatorRegistry registry) {
        registry.register("CsSessionGreeter", CustomerServiceSessionExample.GREETER);
        registry.register("CsSessionResponder", CustomerServiceSessionExample.RESPONDER);
        registry.register("CsSessionSolver", CustomerServiceSessionExample.SOLVER);
        registry.register("CsSessionCloser", CustomerServiceSessionExample.CLOSER);
        return new SessionDslCompiler(registry).compile(loadDsl());
    }

    public static SessionExecutor newExecutor(DefaultOperatorRegistry registry, InMemorySessionStore store) {
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .build();
        return new SessionExecutor(engine, store);
    }

    public static void main(String[] args) throws Exception {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        SessionGraph sessionGraph = buildSessionGraph(registry);
        InMemorySessionStore store = new InMemorySessionStore();

        try (SessionExecutor executor = newExecutor(registry, store)) {
            SessionHandle handle = executor.start(sessionGraph, new GraphContext(Map.of("sessionId", "SESSION-DSL-001")));
            CustomerServiceSessionExample.awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
            handle.signal(Map.of("userMessage", "I need a refund and human support"));
            SessionCheckpoint completed = CustomerServiceSessionExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));
            System.out.println("Session completed outputs: " + new LinkedHashMap<>(completed.phaseOutputs()));
        }
    }

    private static String loadDsl() {
        try (InputStream in = CustomerServiceSessionDslExample.class.getClassLoader().getResourceAsStream(DSL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing DSL resource: " + DSL_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load DSL resource: " + DSL_RESOURCE, e);
        }
    }
}
