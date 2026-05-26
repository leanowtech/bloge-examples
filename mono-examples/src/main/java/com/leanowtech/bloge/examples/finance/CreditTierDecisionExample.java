package com.leanowtech.bloge.examples.finance;

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
 * Demonstrates a BLOGE decision table that maps a credit score into an underwriting tier.
 *
 * <p>The Java API example builds the same runtime shape that the DSL compiler emits: a
 * {@link DecisionTableInput} with ordered {@link CompiledDecisionRule} rows and an explicit
 * {@link HitPolicy#FIRST} policy. FIRST is a natural fit for ordered credit bands because the
 * first matching row wins and the final {@code otherwise} row provides a safe rejection fallback.
 * Scalar outputs are wrapped as {@code {value: ...}} to mirror the DSL compiler shape.</p>
 */
public final class CreditTierDecisionExample {

    static final String NODE_FETCH_APPLICANT = "fetchApplicant";
    static final String NODE_CREDIT_TIER = "creditTier";

    private CreditTierDecisionExample() {
    }

    public record ApplicantQuery(String applicantId) {}
    public record Applicant(String applicantId, int score) {}

    static final Operator<ApplicantQuery, Applicant> FETCH_APPLICANT = (input, ctx) -> switch (input.applicantId()) {
        case "prime" -> new Applicant(input.applicantId(), 780);
        case "gold" -> new Applicant(input.applicantId(), 710);
        case "silver" -> new Applicant(input.applicantId(), 640);
        default -> new Applicant(input.applicantId(), 520);
    };

    /**
     * Builds a two-node graph: fetch the applicant, then classify their credit tier.
     *
     * @return graph that demonstrates {@code hit=first} decision-table semantics
     */
    public static Graph buildGraph() {
        return Graph.builder("creditTierDecision")
                .node(NODE_FETCH_APPLICANT, FETCH_APPLICANT)
                    .input((results, ctx) -> new ApplicantQuery(ctx.get("applicantId", String.class)))
                .node(NODE_CREDIT_TIER, DecisionTableOperator.INSTANCE)
                    .dependsOn(NODE_FETCH_APPLICANT)
                    .input((results, ctx) -> decisionInput(results.get(NODE_FETCH_APPLICANT, Applicant.class).score()))
                .build();
    }

    /**
     * Executes the decision table with a sample applicant id.
     *
    * @param applicantId one of {@code prime}, {@code gold}, {@code silver}, or another fallback id
    * @return graph result containing the tier at {@code creditTier.output.value}
     */
    public static GraphResult execute(String applicantId) {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.executeWithOperators(buildGraph(), new GraphContext(Map.of("applicantId", applicantId)), Map.of(
                NODE_FETCH_APPLICANT, FETCH_APPLICANT,
                NODE_CREDIT_TIER, DecisionTableOperator.INSTANCE
        ));
    }

    static DecisionTableInput decisionInput(int score) {
        return new DecisionTableInput(HitPolicy.FIRST, List.of(
                new CompiledDecisionRule(0, params -> intParam(params, "score") >= 750,
                        params -> Map.of("value", "platinum"), false),
                new CompiledDecisionRule(1, params -> {
                    int value = intParam(params, "score");
                    return value >= 680 && value < 750;
                }, params -> Map.of("value", "gold"), false),
                new CompiledDecisionRule(2, params -> {
                    int value = intParam(params, "score");
                    return value >= 580 && value < 680;
                }, params -> Map.of("value", "silver"), false),
                new CompiledDecisionRule(3, null, params -> Map.of("value", "rejected"), true)
        ), Map.of("score", score));
    }

    private static int intParam(Map<String, Object> params, String name) {
        return ((Number) params.get(name)).intValue();
    }

    public static void main(String[] args) {
        String applicantId = args.length > 0 ? args[0] : "gold";
        GraphResult result = execute(applicantId);
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Credit tier: " + tierValue(result));
    }

    @SuppressWarnings("unchecked")
    static String tierValue(GraphResult result) {
        return String.valueOf(((Map<String, Object>) result.results().getRaw(NODE_CREDIT_TIER)).get("value"));
    }
}