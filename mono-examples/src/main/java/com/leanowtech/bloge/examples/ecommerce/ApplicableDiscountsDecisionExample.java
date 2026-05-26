package com.leanowtech.bloge.examples.ecommerce;

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
 * Demonstrates COLLECT hit policy by accumulating all discounts that apply to a customer.
 */
public final class ApplicableDiscountsDecisionExample {

    static final String NODE_FETCH_CUSTOMER = "fetchCustomer";
    static final String NODE_DISCOUNTS = "applicableDiscounts";

    private ApplicableDiscountsDecisionExample() {
    }

    public record CustomerQuery(String customerId) {}
    public record Customer(String customerId, int loyaltyScore, boolean hasCoupon, boolean vip) {}

    static final Operator<CustomerQuery, Customer> FETCH_CUSTOMER = (input, ctx) -> switch (input.customerId()) {
        case "vip-high" -> new Customer(input.customerId(), 1_200, true, true);
        case "coupon" -> new Customer(input.customerId(), 100, true, false);
        default -> new Customer(input.customerId(), 0, false, false);
    };

    /**
     * Builds a collect-policy discount graph.
     *
     * @return graph whose decision table returns {@code {items: [...]}}
     */
    public static Graph buildGraph() {
        return Graph.builder("applicableDiscountsDecision")
                .node(NODE_FETCH_CUSTOMER, FETCH_CUSTOMER)
                    .input((results, ctx) -> new CustomerQuery(ctx.get("customerId", String.class)))
                .node(NODE_DISCOUNTS, DecisionTableOperator.INSTANCE)
                    .dependsOn(NODE_FETCH_CUSTOMER)
                    .input((results, ctx) -> {
                        Customer customer = results.get(NODE_FETCH_CUSTOMER, Customer.class);
                        return decisionInput(customer.loyaltyScore(), customer.hasCoupon(), customer.vip());
                    })
                .build();
    }

    /**
     * Executes the discount collection example.
     *
     * @param customerId sample customer id
     * @return result containing collected discount ids under {@code applicableDiscounts.output.items}
     */
    public static GraphResult execute(String customerId) {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.executeWithOperators(buildGraph(), new GraphContext(Map.of("customerId", customerId)), Map.of(
                NODE_FETCH_CUSTOMER, FETCH_CUSTOMER,
                NODE_DISCOUNTS, DecisionTableOperator.INSTANCE
        ));
    }

    static DecisionTableInput decisionInput(int score, boolean hasCoupon, boolean vip) {
        return new DecisionTableInput(HitPolicy.COLLECT, List.of(
                new CompiledDecisionRule(0, params -> intParam(params, "score") >= 500,
                        params -> "loyalty_5pct", false),
                new CompiledDecisionRule(1, params -> intParam(params, "score") >= 1_000,
                        params -> "loyalty_extra_3pct", false),
                new CompiledDecisionRule(2, params -> Boolean.TRUE.equals(params.get("hasCoupon")),
                        params -> "coupon_10pct", false),
                new CompiledDecisionRule(3, params -> Boolean.TRUE.equals(params.get("isVip")),
                        params -> "vip_free_shipping", false)
        ), Map.of("score", score, "hasCoupon", hasCoupon, "isVip", vip));
    }

    @SuppressWarnings("unchecked")
    static List<String> discountItems(GraphResult result) {
        Map<String, Object> output = (Map<String, Object>) result.results().getRaw(NODE_DISCOUNTS);
        return (List<String>) output.get("items");
    }

    private static int intParam(Map<String, Object> params, String name) {
        return ((Number) params.get(name)).intValue();
    }
}