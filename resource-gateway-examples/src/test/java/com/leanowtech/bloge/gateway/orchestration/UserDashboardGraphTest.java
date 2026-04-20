package com.leanowtech.bloge.gateway.orchestration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.test.MockOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class UserDashboardGraphTest {

    private static Graph graph;
    private static OperatorRegistry compilationRegistry;

    @BeforeAll
    static void loadGraph() throws IOException {
        compilationRegistry = new DefaultOperatorRegistry();
        compilationRegistry.register("httpResource", MockOperator.returning(null));
        GraphLoader loader = new GraphLoader(compilationRegistry);
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("bloge/gateway/user-dashboard.bloge")) {
            if (is == null) throw new IOException("Resource not found");
            graph = loader.load(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void allNodesExecuteInParallel_andAssembleDashboard() {
        var profileOutput = httpOutput("user-service.getProfile",
                Map.of("userId", "u1", "name", "Alice", "tier", "premium"));
        var ordersOutput = httpOutput("order-service.listOrders",
                Map.of("orders", List.of(Map.of("id", "o1"))));
        var recsOutput = httpOutput("recommendation-service.forUser",
                Map.of("entries", List.of("rec1")));
        var walletOutput = httpOutput("wallet-service.getBalance",
                Map.of("balance", 100.50, "currency", "USD"));
        var notifOutput = httpOutput("notification-service.unread",
                Map.of("unread", 3, "entries", List.of()));

        MockOperator<Object, Object> mockOp = MockOperator.of(input -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> inp = (Map<String, Object>) input;
            String resourceId = (String) inp.get("resourceId");
            return switch (resourceId) {
                case "user-service.getProfile" -> profileOutput;
                case "order-service.listOrders" -> ordersOutput;
                case "recommendation-service.forUser" -> recsOutput;
                case "wallet-service.getBalance" -> walletOutput;
                case "notification-service.unread" -> notifOutput;
                default -> throw new IllegalArgumentException("Unknown: " + resourceId);
            };
        });

        GraphEngine engine = GraphEngine.builder()
                .registry(compilationRegistry)
                .build();

        Map<String, Object> nodeOps = Map.of(
                "fetchProfile", mockOp,
                "fetchOrders", mockOp,
                "fetchRecommendations", mockOp,
                "fetchWallet", mockOp,
                "fetchNotifications", mockOp
        );
        GraphResult result = engine.executeWithOperators(graph, new GraphContext(Map.of("userId", "u1")), nodeOps);

        assertThat(result.isSuccess()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> dashboard = result.findOutput("assembleDashboard", Map.class).orElseThrow();
        assertThat(dashboard).containsKeys("profile", "orders", "recommendations", "wallet", "notifications");
    }

    @Test
    void fallbackNode_producesPartialResult_whenFetchFails() {
        MockOperator<Object, Object> mockOp = MockOperator.of(input -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> inp = (Map<String, Object>) input;
            String resourceId = (String) inp.get("resourceId");
            if ("user-service.getProfile".equals(resourceId)) {
                return httpOutput("user-service.getProfile", Map.of("name", "Alice"));
            }
            throw new RuntimeException("service unavailable");
        });

        GraphEngine engine = GraphEngine.builder()
                .registry(compilationRegistry)
                .build();

        Map<String, Object> nodeOps = Map.of(
                "fetchProfile", mockOp,
                "fetchOrders", mockOp,
                "fetchRecommendations", mockOp,
                "fetchWallet", mockOp,
                "fetchNotifications", mockOp
        );
        GraphResult result = engine.executeWithOperators(graph, new GraphContext(Map.of("userId", "u1")), nodeOps);

        assertThat(result.isSuccess()).isTrue();
    }

    private static HttpResourceOutput httpOutput(String resourceId, Object payload) {
        return new HttpResourceOutput(resourceId, 200, payload, "{}", Duration.ofMillis(50), true);
    }
}
