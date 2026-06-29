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

import static org.assertj.core.api.Assertions.assertThat;

class LoanDecisionPolicyGraphTest {

    private static Graph graph;
    private static OperatorRegistry compilationRegistry;

    @BeforeAll
    static void loadGraph() throws IOException {
        compilationRegistry = new DefaultOperatorRegistry();
        compilationRegistry.register("httpResource", MockOperator.returning(null));
        GraphLoader loader = new GraphLoader(compilationRegistry);
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("bloge/gateway/loan-decision-policy.bloge")) {
            if (is == null) throw new IOException("Resource not found");
            graph = loader.load(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void primeApplicantMatchesFirstPolicyRow() {
        MockOperator<Object, Object> mockOp = MockOperator.returning(new HttpResourceOutput(
                "loan-applicant-service.getProfile",
                200,
                Map.of("applicantId", "prime", "score", 780, "segment", "private-bank"),
                "{}",
                Duration.ofMillis(40),
                true
        ));

        GraphEngine engine = GraphEngine.builder().registry(compilationRegistry).build();
        GraphResult result = engine.executeWithOperators(graph,
                new GraphContext(Map.of("applicantId", "prime", "requestedAmount", 450_000.0)),
                Map.of("fetchApplicant", mockOp));

        assertThat(result.isSuccess())
                .as("Errors: %s statuses: %s", result.errors(), result.statusMap())
                .isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> output = result.findOutput("assembleLoanDecision", Map.class).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) output.get("policy");

        assertThat(policy)
                .containsEntry("decision", "approved")
                .containsEntry("ruleId", "R1");
        assertThat(((Number) policy.get("rate")).doubleValue()).isEqualTo(3.5);
        assertThat(output).containsEntry("requestedAmount", 450_000.0);
    }

    @Test
    void lowScoreFallsThroughToDeclineRow() {
        MockOperator<Object, Object> mockOp = MockOperator.returning(new HttpResourceOutput(
                "loan-applicant-service.getProfile",
                200,
                Map.of("applicantId", "decline", "score", 590, "segment", "new"),
                "{}",
                Duration.ofMillis(40),
                true
        ));

        GraphEngine engine = GraphEngine.builder().registry(compilationRegistry).build();
        GraphResult result = engine.executeWithOperators(graph,
                new GraphContext(Map.of("applicantId", "decline", "requestedAmount", 120_000.0)),
                Map.of("fetchApplicant", mockOp));

        assertThat(result.isSuccess())
                .as("Errors: %s statuses: %s", result.errors(), result.statusMap())
                .isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> output = result.findOutput("assembleLoanDecision", Map.class).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) output.get("policy");

        assertThat(policy)
                .containsEntry("decision", "declined")
                .containsEntry("ruleId", "R4");
    }
}
