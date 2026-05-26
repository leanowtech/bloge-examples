package com.leanowtech.bloge.examples.insurance;

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
 * DSL-backed insurance premium decision-table example.
 */
@SuppressWarnings("preview")
public final class InsurancePremiumDecisionDslExample {

    private static final String DSL_RESOURCE = "/bloge/insurance-premium-decision.bloge";

    private InsurancePremiumDecisionDslExample() {
    }

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_APPLICANT = (input, ctx) -> {
        String applicantId = String.valueOf(input.get("applicantId"));
        int age = switch (applicantId) {
            case "young-safe" -> 28;
            case "adult" -> 42;
            default -> 66;
        };
        return Map.of("applicantId", applicantId, "age", age);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ASSESS_RISK = (input, ctx) -> {
        String applicantId = String.valueOf(input.get("applicantId"));
        int riskScore = switch (applicantId) {
            case "young-safe" -> 20;
            case "adult" -> 45;
            default -> 70;
        };
        return Map.of("riskScore", riskScore);
    };

    /**
     * Compiles the insurance premium DSL resource.
     *
     * @param registry registry used for operator lookup
     * @return compiled graph
     */
    public static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("InsuranceApplicantOperator", FETCH_APPLICANT);
        registry.register("RiskAssessmentOperator", ASSESS_RISK);
        return ExampleDslResources.loadGraph(DSL_RESOURCE, registry);
    }

    /**
     * Executes the DSL premium matrix.
     *
     * @param applicantId sample applicant id
     * @return graph result with structured premium output
     */
    public static GraphResult execute(String applicantId) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.execute(graph, new GraphContext(Map.of("applicantId", applicantId)));
    }
}