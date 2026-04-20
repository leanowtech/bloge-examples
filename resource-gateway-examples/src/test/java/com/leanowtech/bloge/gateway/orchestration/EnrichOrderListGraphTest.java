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

class EnrichOrderListGraphTest {

    private static Graph graph;
    private static OperatorRegistry compilationRegistry;

    @BeforeAll
    static void loadGraph() throws IOException {
        compilationRegistry = new DefaultOperatorRegistry();
        compilationRegistry.register("httpResource", MockOperator.returning(null));
        GraphLoader loader = new GraphLoader(compilationRegistry);
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("bloge/gateway/enrich-order-list.bloge")) {
            if (is == null) throw new IOException("Resource not found");
            graph = loader.load(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void enrichesTwoOrders_withShippingAndInvoice() {
        MockOperator<Object, Object> mockOp = MockOperator.of(input -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> inp = (Map<String, Object>) input;
            String resourceId = (String) inp.get("resourceId");
            return switch (resourceId) {
                case "order-service.listOrders" -> httpOutput("order-service.listOrders",
                        Map.of("orders", List.of(
                                Map.of("orderId", "ord-1", "total", 29.99),
                                Map.of("orderId", "ord-2", "total", 59.00)
                        )));
                case "logistics-service.getShipping" -> httpOutput("logistics-service.getShipping",
                        Map.of("status", "shipped", "carrier", "FedEx"));
                case "invoice-service.getInvoice" -> httpOutput("invoice-service.getInvoice",
                        Map.of("invoiceUrl", "https://invoices.example.com/inv-1"));
                default -> throw new IllegalArgumentException("Unexpected: " + resourceId);
            };
        });

        compilationRegistry.register("httpResource", mockOp);
        GraphEngine engine = GraphEngine.builder().registry(compilationRegistry).build();
        GraphResult result = engine.execute(graph, new GraphContext(Map.of("userId", "u1")));

        assertThat(result.isSuccess()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> collected = result.findOutput("collectEnriched", Map.class).orElseThrow();
        assertThat(collected).containsKey("orders");
        Object orders = collected.get("orders");
        assertThat(orders).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> orderList = (List<Object>) orders;
        assertThat(orderList).hasSize(2);
    }

    private static HttpResourceOutput httpOutput(String resourceId, Object payload) {
        return new HttpResourceOutput(resourceId, 200, payload, "{}", Duration.ofMillis(50), true);
    }
}
