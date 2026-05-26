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
 * DSL-backed version of {@link CreditTierDecisionExample}.
 *
 * <p>The {@code credit-tier-decision.bloge} resource shows the decision table in its intended
 * authoring form, including a chained comparison row and an {@code otherwise} fallback.</p>
 */
@SuppressWarnings("preview")
public final class CreditTierDecisionDslExample {

    private static final String DSL_RESOURCE = "/bloge/credit-tier-decision.bloge";

    private CreditTierDecisionDslExample() {
    }

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_APPLICANT = (input, ctx) -> {
        String applicantId = String.valueOf(input.get("applicantId"));
        int score = switch (applicantId) {
            case "prime" -> 780;
            case "gold" -> 710;
            case "silver" -> 640;
            default -> 520;
        };
        return Map.of("applicantId", applicantId, "score", score);
    };

    /**
     * Builds the graph by compiling the external DSL resource.
     *
     * @param registry registry that resolves the DSL operator references
     * @return compiled graph definition
     */
    public static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("FetchApplicantOperator", FETCH_APPLICANT);
        return ExampleDslResources.loadGraph(DSL_RESOURCE, registry);
    }

    /**
     * Executes the DSL decision-table example for the supplied applicant id.
     *
    * @param applicantId one of {@code prime}, {@code gold}, {@code silver}, or another fallback id
    * @return graph result containing the tier at {@code creditTier.output.value}
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

    public static void main(String[] args) {
        String applicantId = args.length > 0 ? args[0] : "gold";
        GraphResult result = execute(applicantId);
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Credit tier: " + CreditTierDecisionExample.tierValue(result));
    }
}