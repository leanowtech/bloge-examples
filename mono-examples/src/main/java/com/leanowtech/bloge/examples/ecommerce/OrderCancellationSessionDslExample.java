package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleSessionSnapshotStore;
import com.leanowtech.bloge.ext.compiler.SessionDslCompiler;
import com.leanowtech.bloge.ext.engine.SessionExecutor;
import com.leanowtech.bloge.ext.model.SessionGraph;
import com.leanowtech.bloge.ext.model.SessionHandle;
import com.leanowtech.bloge.ext.model.SessionStateSnapshot;
import com.leanowtech.bloge.ext.model.SessionStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DSL version of {@link OrderCancellationSessionExample}.
 *
 * <p>Compiles {@code order-cancellation-session.bloge} and drives the same cancellation scenario:
 * processing completes, session yields at {@code awaitDecision}, an external "cancel" signal
 * redirects to the {@code cancelled} phase where refund and notification nodes execute.</p>
 */
@SuppressWarnings("preview")
public final class OrderCancellationSessionDslExample {

    private static final String DSL_RESOURCE = "bloge/ecommerce/order-cancellation-session.bloge";

    private OrderCancellationSessionDslExample() {}

    /**
     * Compiles the DSL session graph and registers the required operators.
     */
    public static SessionGraph buildSessionGraph(DefaultOperatorRegistry registry) {
        OrderCancellationSessionExample.registerOperators(registry);
        return new SessionDslCompiler(registry).compile(loadDsl());
    }

    /**
     * Creates a new session executor backed by the given snapshot sink and registry.
     */
    public static SessionExecutor newExecutor(DefaultOperatorRegistry registry, ExampleSessionSnapshotStore store) {
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .build();
        return SessionExecutor.builder(engine)
                .snapshotCallbacks(java.util.List.of(store))
                .build();
    }

    public static void main(String[] args) throws Exception {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        SessionGraph sessionGraph = buildSessionGraph(registry);
        ExampleSessionSnapshotStore store = new ExampleSessionSnapshotStore();

        try (SessionExecutor executor = newExecutor(registry, store)) {
            SessionHandle handle = executor.start(
                    sessionGraph,
                    new GraphContext(Map.of(
                            "orderId", "ORD-CANCEL-DSL-001",
                            "customerId", "CUST-77"
                    )));
            OrderCancellationSessionExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(3));

            handle.signal(Map.of("action", "cancel"));

            SessionStateSnapshot completed = OrderCancellationSessionExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));
            System.out.println("Session outputs: " + new LinkedHashMap<>(completed.phaseOutputs()));
        }
    }

    private static String loadDsl() {
        try (InputStream in = OrderCancellationSessionDslExample.class.getClassLoader().getResourceAsStream(DSL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing DSL resource: " + DSL_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load DSL resource: " + DSL_RESOURCE, e);
        }
    }
}
