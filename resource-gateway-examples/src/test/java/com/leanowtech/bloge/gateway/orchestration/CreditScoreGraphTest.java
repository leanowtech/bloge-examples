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

class CreditScoreGraphTest {

    private static Graph graph;
    private static OperatorRegistry compilationRegistry;

    @BeforeAll
    static void loadGraph() throws IOException {
        compilationRegistry = new DefaultOperatorRegistry();
        compilationRegistry.register("httpResource", MockOperator.returning(null));
        GraphLoader loader = new GraphLoader(compilationRegistry);
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("bloge/gateway/credit-score.bloge")) {
            if (is == null) throw new IOException("Resource not found");
            graph = loader.load(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void primarySucceeds_assemblePrimaryBranch() {
        MockOperator<Object, Object> mockOp = MockOperator.of(input -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> inp = (Map<String, Object>) input;
            String resourceId = (String) inp.get("resourceId");
            return switch (resourceId) {
                case "credit-provider.primary" -> new HttpResourceOutput(
                        "credit-provider.primary", 200,
                        Map.of("score", 750, "provider", "equifax"),
                        "{}", Duration.ofMillis(50), true);
                case "credit-provider.secondary" -> new HttpResourceOutput(
                        "credit-provider.secondary", 200,
                        Map.of("score", 740, "provider", "transunion"),
                        "{}", Duration.ofMillis(50), true);
                default -> throw new IllegalArgumentException("Unexpected: " + resourceId);
            };
        });

        GraphEngine engine = GraphEngine.builder().registry(compilationRegistry).build();
        GraphResult result = engine.executeWithOperators(graph, new GraphContext(Map.of("userId", "u1")),
                Map.of("primaryCreditProvider", mockOp, "secondaryCreditProvider", mockOp));

        assertThat(result.isSuccess()).isTrue();
        // Primary branch: assemblePrimary has the output
        @SuppressWarnings("unchecked")
        Map<String, Object> out = result.findOutput("assemblePrimary", Map.class).orElseThrow();
        assertThat(out).containsEntry("provider", "primary");
        // Secondary branch should be skipped
        assertThat(result.findOutput("assembleSecondary", Object.class)).isEmpty();
    }

    @Test
    void primaryFails_fallbackToSecondary() {
        MockOperator<Object, Object> mockOp = MockOperator.of(input -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> inp = (Map<String, Object>) input;
            String resourceId = (String) inp.get("resourceId");
            return switch (resourceId) {
                case "credit-provider.primary" -> throw new RuntimeException("primary provider timeout");
                case "credit-provider.secondary" -> new HttpResourceOutput(
                        "credit-provider.secondary", 200,
                        Map.of("score", 740, "provider", "transunion"),
                        "{}", Duration.ofMillis(50), true);
                default -> throw new IllegalArgumentException("Unexpected: " + resourceId);
            };
        });

        GraphEngine engine = GraphEngine.builder().registry(compilationRegistry).build();
        GraphResult result = engine.executeWithOperators(graph, new GraphContext(Map.of("userId", "u1")),
                Map.of("primaryCreditProvider", mockOp, "secondaryCreditProvider", mockOp));

        assertThat(result.isSuccess()).isTrue();
        // Secondary branch should execute after primary failure
        @SuppressWarnings("unchecked")
        Map<String, Object> out = result.findOutput("assembleSecondary", Map.class).orElseThrow();
        assertThat(out).containsEntry("provider", "secondary");
    }
}
