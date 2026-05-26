package com.leanowtech.bloge.examples.insurance;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.CompiledDecisionRule;
import com.leanowtech.bloge.core.operator.DecisionTableInput;
import com.leanowtech.bloge.core.operator.DecisionTableOperator;
import com.leanowtech.bloge.core.operator.HitPolicy;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates insurance premium pricing with a UNIQUE decision table.
 */
public final class InsurancePremiumDecisionExample {

    static final String NODE_FETCH_APPLICANT = "fetchApplicant";
    static final String NODE_ASSESS_RISK = "assessRisk";
    static final String NODE_PREMIUM_CALC = "premiumCalc";

    private InsurancePremiumDecisionExample() {
    }

    public record ApplicantQuery(String applicantId) {}
    public record Applicant(String applicantId, int age) {}
    public record RiskInput(String applicantId) {}
    public record RiskAssessment(int riskScore) {}

    static final Operator<ApplicantQuery, Applicant> FETCH_APPLICANT = (input, ctx) -> switch (input.applicantId()) {
        case "young-safe" -> new Applicant(input.applicantId(), 28);
        case "adult" -> new Applicant(input.applicantId(), 42);
        default -> new Applicant(input.applicantId(), 66);
    };

    static final Operator<RiskInput, RiskAssessment> ASSESS_RISK = (input, ctx) -> switch (input.applicantId()) {
        case "young-safe" -> new RiskAssessment(20);
        case "adult" -> new RiskAssessment(45);
        default -> new RiskAssessment(70);
    };

    /**
     * Builds a premium-pricing graph from applicant and risk signals.
     *
     * @return graph with a structured premium output
     */
    public static Graph buildGraph() {
        return Graph.builder("insurancePremiumDecision")
                .node(NODE_FETCH_APPLICANT, FETCH_APPLICANT)
                    .input((results, ctx) -> new ApplicantQuery(ctx.get("applicantId", String.class)))
                .node(NODE_ASSESS_RISK, ASSESS_RISK)
                    .dependsOn(NODE_FETCH_APPLICANT)
                    .input((results, ctx) -> new RiskInput(results.get(NODE_FETCH_APPLICANT, Applicant.class).applicantId()))
                .node(NODE_PREMIUM_CALC, DecisionTableOperator.INSTANCE)
                    .dependsOn(NODE_FETCH_APPLICANT, NODE_ASSESS_RISK)
                    .input((results, ctx) -> decisionInput(
                            results.get(NODE_FETCH_APPLICANT, Applicant.class).age(),
                            results.get(NODE_ASSESS_RISK, RiskAssessment.class).riskScore()))
                .build();
    }

    /**
     * Executes the insurance premium matrix.
     *
     * @param applicantId sample applicant id
     * @return result containing {@code premiumCalc.output.premium} and {@code premiumCalc.output.tier}
     */
    public static GraphResult execute(String applicantId) {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.executeWithOperators(buildGraph(), new GraphContext(Map.of("applicantId", applicantId)), Map.of(
                NODE_FETCH_APPLICANT, FETCH_APPLICANT,
                NODE_ASSESS_RISK, ASSESS_RISK,
                NODE_PREMIUM_CALC, DecisionTableOperator.INSTANCE
        ));
    }

    static DecisionTableInput decisionInput(int age, int riskScore) {
        return new DecisionTableInput(HitPolicy.UNIQUE, List.of(
                new CompiledDecisionRule(0, params -> intParam(params, "age") < 30
                        && intParam(params, "riskScore") < 30,
                        params -> Map.of("premium", 120.0, "tier", "standard"), false),
                new CompiledDecisionRule(1, params -> intParam(params, "age") < 30
                        && intParam(params, "riskScore") >= 30,
                        params -> Map.of("premium", 180.0, "tier", "elevated"), false),
                new CompiledDecisionRule(2, params -> intParam(params, "age") >= 30
                        && intParam(params, "age") < 60
                        && intParam(params, "riskScore") < 50,
                        params -> Map.of("premium", 150.0, "tier", "standard"), false),
                new CompiledDecisionRule(3, params -> intParam(params, "age") >= 60
                        && intParam(params, "riskScore") < 40,
                        params -> Map.of("premium", 220.0, "tier", "senior"), false),
                new CompiledDecisionRule(4, null,
                        params -> Map.of("premium", 350.0, "tier", "high-risk"), true)
        ), Map.of("age", age, "riskScore", riskScore));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> premium(GraphResult result) {
        return (Map<String, Object>) result.results().getRaw(NODE_PREMIUM_CALC);
    }

    private static int intParam(Map<String, Object> params, String name) {
        return ((Number) params.get(name)).intValue();
    }
}