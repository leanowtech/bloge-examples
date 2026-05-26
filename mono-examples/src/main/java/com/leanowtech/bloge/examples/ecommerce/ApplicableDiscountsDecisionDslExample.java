package com.leanowtech.bloge.examples.ecommerce;

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
 * DSL-backed COLLECT decision-table discount example.
 */
@SuppressWarnings("preview")
public final class ApplicableDiscountsDecisionDslExample {

    private static final String DSL_RESOURCE = "/bloge/ecommerce/applicable-discounts-decision.bloge";

    private ApplicableDiscountsDecisionDslExample() {
    }

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_CUSTOMER = (input, ctx) -> {
        String customerId = String.valueOf(input.get("customerId"));
        return switch (customerId) {
            case "vip-high" -> Map.of("customerId", customerId, "loyaltyScore", 1_200, "hasCoupon", true, "isVip", true);
            case "coupon" -> Map.of("customerId", customerId, "loyaltyScore", 100, "hasCoupon", true, "isVip", false);
            default -> Map.of("customerId", customerId, "loyaltyScore", 0, "hasCoupon", false, "isVip", false);
        };
    };

    /**
     * Compiles the DSL discount resource.
     *
     * @param registry registry used for operator resolution
     * @return compiled graph
     */
    public static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("CustomerFetchOperator", FETCH_CUSTOMER);
        return ExampleDslResources.loadGraph(DSL_RESOURCE, registry);
    }

    /**
     * Executes the DSL discount example.
     *
     * @param customerId sample customer id
     * @return graph result with collected discount ids
     */
    public static GraphResult execute(String customerId) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.execute(graph, new GraphContext(Map.of("customerId", customerId)));
    }
}