package com.leanowtech.bloge.examples.finance;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;

import java.util.List;
import java.util.Map;

/**
 * DSL-backed loan-term decision matrix.
 */
@SuppressWarnings("preview")
public final class LoanTermsDecisionDslExample {

    private static final String DSL_RESOURCE = "/bloge/loan-terms-decision.bloge";

    private LoanTermsDecisionDslExample() {
    }

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_APPLICANT = (input, ctx) -> {
        String applicantId = String.valueOf(input.get("applicantId"));
        int score = switch (applicantId) {
            case "prime" -> 780;
            case "standard" -> 720;
            default -> 610;
        };
        return Map.of("applicantId", applicantId, "score", score);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_LOAN = (input, ctx) -> {
        String loanId = String.valueOf(input.get("loanId"));
        double requestedAmount = switch (loanId) {
            case "large" -> 450_000.0;
            case "small" -> 250_000.0;
            default -> 650_000.0;
        };
        return Map.of("loanId", loanId, "requestedAmount", requestedAmount);
    };

    /**
     * Compiles the external DSL resource.
     *
     * @param registry registry used for operator resolution
     * @return compiled graph
     */
    public static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("FetchApplicantOperator", FETCH_APPLICANT);
        registry.register("FetchLoanOperator", FETCH_LOAN);
        return ExampleDslResources.loadGraph(DSL_RESOURCE, registry);
    }

    /**
     * Executes the DSL decision matrix.
     *
     * @param applicantId sample applicant id
     * @param loanId sample loan id
     * @return graph result with structured loan terms
     */
    public static GraphResult execute(String applicantId, String loanId) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.execute(graph, new GraphContext(Map.of("applicantId", applicantId, "loanId", loanId)));
    }
}