package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;
import com.leanowtech.bloge.examples.common.ExampleSessionSnapshotStore;
import com.leanowtech.bloge.ext.compiler.SessionDslCompiler;
import com.leanowtech.bloge.ext.engine.SessionExecutor;
import com.leanowtech.bloge.ext.model.SessionGraph;
import com.leanowtech.bloge.ext.model.SessionHandle;
import com.leanowtech.bloge.ext.model.SessionStateSnapshot;
import com.leanowtech.bloge.ext.model.SessionStatus;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DSL-backed variant of {@link OrderSessionWithStateMachineExample}.
 */
public final class OrderSessionWithStateMachineDslExample {

    private static final String DSL_RESOURCE = "/bloge/ecommerce/order-session-with-state-machine.bloge";

    private OrderSessionWithStateMachineDslExample() {
    }

    /**
     * Compiles the example DSL resource into a nested session graph.
     *
     * @param registry operator registry used for nested graph compilation
     * @return compiled session graph
     */
    public static SessionGraph compile(DefaultOperatorRegistry registry) {
        OrderSessionWithStateMachineExample.registerOperators(registry);
        return new SessionDslCompiler(registry).compile(ExampleDslResources.readResource(DSL_RESOURCE));
    }

    /**
     * Executes the DSL-backed sample flow.
     *
     * @param args command-line arguments (unused)
     * @throws Exception when execution fails
     */
    public static void main(String[] args) throws Exception {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        SessionGraph sessionGraph = compile(registry);
        ExampleSessionSnapshotStore store = new ExampleSessionSnapshotStore();
        try (SessionExecutor executor = OrderSessionWithStateMachineExample.newExecutor(store, registry)) {
            SessionHandle handle = executor.start(sessionGraph, new GraphContext(Map.of(
                    "orderId", "ORD-2001",
                    "amount", 99.95,
                    "sessionId", "ORDER-SESSION-DSL-001"
            )));
            OrderSessionWithStateMachineExample.awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
            handle.signal(Map.of("event", "submit", "submittedBy", "api"));
            OrderSessionWithStateMachineExample.awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
            handle.signal(Map.of("event", "payment_confirmed", "receivedBy", "gateway"));
            SessionStateSnapshot completed = OrderSessionWithStateMachineExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));
            System.out.println("Order workflow outputs: " + new LinkedHashMap<>(completed.phaseOutputs()));
        }
    }
}
