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
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ProductDetailGraphTest {

    private static Graph graph;
    private static OperatorRegistry compilationRegistry;

    @BeforeAll
    static void loadGraph() throws IOException {
        compilationRegistry = new DefaultOperatorRegistry();
        compilationRegistry.register("httpResource", MockOperator.returning(null));
        GraphLoader loader = new GraphLoader(compilationRegistry);
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("bloge/gateway/product-detail.bloge")) {
            if (is == null) throw new IOException("Resource not found");
            graph = loader.load(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void physicalProduct_routesToShippingBranch() {
        MockOperator<Object, Object> mockOp = MockOperator.of(input -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> inp = (Map<String, Object>) input;
            String resourceId = (String) inp.get("resourceId");
            return switch (resourceId) {
                case "catalog-service.getProduct" -> httpOutput("catalog-service.getProduct",
                        Map.of("productId", "p1", "name", "Wireless Mouse", "type", "physical", "price", 29.99));
                case "logistics-service.getShipping" -> httpOutput("logistics-service.getShipping",
                        Map.of("shippable", true, "estimatedDays", 3, "carrier", "FedEx"));
                default -> throw new IllegalArgumentException("Unexpected: " + resourceId);
            };
        });

        GraphEngine engine = GraphEngine.builder().registry(compilationRegistry).build();
        GraphResult result = engine.executeWithOperators(graph, new GraphContext(Map.of("productId", "p1")),
                Map.of("fetchProduct", mockOp, "fetchShippingInfo", mockOp));

        assertThat(result.isSuccess()).isTrue();
        // Physical branch: assemblePhysical should have the output
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = result.findOutput("assemblePhysical", Map.class).orElseThrow();
        assertThat(detail).containsEntry("productType", "physical");
        assertThat(detail).containsKey("shipping");

        // Non-physical branches should be skipped
        assertThat(result.findOutput("assembleDigital", Object.class)).isEmpty();
        assertThat(result.findOutput("assembleGeneric", Object.class)).isEmpty();
    }

    @Test
    void digitalProduct_routesToLicenseBranch() {
        MockOperator<Object, Object> mockOp = MockOperator.of(input -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> inp = (Map<String, Object>) input;
            String resourceId = (String) inp.get("resourceId");
            return switch (resourceId) {
                case "catalog-service.getProduct" -> httpOutput("catalog-service.getProduct",
                        Map.of("productId", "p2", "name", "Photo Editor Pro", "type", "digital", "price", 49.99));
                case "license-service.getLicense" -> httpOutput("license-service.getLicense",
                        Map.of("licenseType", "perpetual", "downloadUrl", "https://cdn.example.com/pe"));
                default -> throw new IllegalArgumentException("Unexpected: " + resourceId);
            };
        });

        GraphEngine engine = GraphEngine.builder().registry(compilationRegistry).build();
        GraphResult result = engine.executeWithOperators(graph, new GraphContext(Map.of("productId", "p2")),
                Map.of("fetchProduct", mockOp, "fetchLicenseInfo", mockOp));

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = result.findOutput("assembleDigital", Map.class).orElseThrow();
        assertThat(detail).containsEntry("productType", "digital");
        assertThat(detail).containsKey("license");

        assertThat(result.findOutput("assemblePhysical", Object.class)).isEmpty();
    }

    @Test
    void unknownType_routesToGenericBranch() {
        MockOperator<Object, Object> mockOp = MockOperator.of(input -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> inp = (Map<String, Object>) input;
            String resourceId = (String) inp.get("resourceId");
            if ("catalog-service.getProduct".equals(resourceId)) {
                return httpOutput("catalog-service.getProduct",
                        Map.of("productId", "p3", "name", "Gift Card", "type", "service", "price", 25.0));
            }
            throw new IllegalArgumentException("Unexpected: " + resourceId);
        });

        GraphEngine engine = GraphEngine.builder().registry(compilationRegistry).build();
        GraphResult result = engine.executeWithOperators(graph, new GraphContext(Map.of("productId", "p3")),
                Map.of("fetchProduct", mockOp));

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = result.findOutput("assembleGeneric", Map.class).orElseThrow();
        assertThat(detail).containsEntry("productType", "generic");
    }

    private static HttpResourceOutput httpOutput(String resourceId, Object payload) {
        return new HttpResourceOutput(resourceId, 200, payload, "{}", Duration.ofMillis(50), true);
    }
}
