package com.leanowtech.bloge.examples.customerservice;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.exception.DecisionTableViolationException;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.CompiledDecisionRule;
import com.leanowtech.bloge.core.operator.DecisionTableInput;
import com.leanowtech.bloge.core.operator.DecisionTableOperator;
import com.leanowtech.bloge.core.operator.HitPolicy;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates the decision-table {@code in} membership operator for customer routing tiers.
 */
public final class CustomerTierDecisionExample {

    static final String NODE_FETCH_CUSTOMER = "fetchCustomer";
    static final String NODE_CUSTOMER_TIER = "customerTier";

    private CustomerTierDecisionExample() {
    }

    public record CustomerQuery(String customerId) {}
    public record Customer(String customerId, String accountType) {}

    static final Operator<CustomerQuery, Customer> FETCH_CUSTOMER = (input, ctx) -> switch (input.customerId()) {
        case "vip" -> new Customer(input.customerId(), "vip");
        case "partner" -> new Customer(input.customerId(), "partner");
        default -> new Customer(input.customerId(), "standard");
    };

    /**
     * Builds a FIRST decision table with static and dynamic membership checks.
     *
     * @return customer-tier routing graph
     */
    public static Graph buildGraph() {
        return Graph.builder("customerTierDecision")
                .node(NODE_FETCH_CUSTOMER, FETCH_CUSTOMER)
                    .input((results, ctx) -> new CustomerQuery(ctx.get("customerId", String.class)))
                .node(NODE_CUSTOMER_TIER, DecisionTableOperator.INSTANCE)
                    .dependsOn(NODE_FETCH_CUSTOMER)
                    .input((results, ctx) -> decisionInput(
                            results.get(NODE_FETCH_CUSTOMER, Customer.class).accountType(),
                            ctx.get("priorityTypes", Object.class)))
                .build();
    }

    /**
     * Executes the tier-routing graph.
     *
     * @param customerId sample customer id
     * @param priorityTypes dynamic membership list
     * @return result containing {@code customerTier.output.value}
     */
    public static GraphResult execute(String customerId, Object priorityTypes) {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.executeWithOperators(buildGraph(), new GraphContext(Map.of(
                "customerId", customerId,
                "priorityTypes", priorityTypes
        )), Map.of(
                NODE_FETCH_CUSTOMER, FETCH_CUSTOMER,
                NODE_CUSTOMER_TIER, DecisionTableOperator.INSTANCE
        ));
    }

    static DecisionTableInput decisionInput(String type, Object allowed) {
        return new DecisionTableInput(HitPolicy.FIRST, List.of(
                new CompiledDecisionRule(0, params -> List.of("vip", "enterprise", "gov").contains(params.get("type")),
                        params -> Map.of("value", "priority"), false),
                new CompiledDecisionRule(1, params -> collectionParam(params.get("allowed")).contains(params.get("type")),
                        params -> Map.of("value", "preferred"), false),
                new CompiledDecisionRule(2, null, params -> Map.of("value", "standard"), true)
        ), Map.of("type", type, "allowed", allowed));
    }

    static DecisionTableViolationException invalidCollectionViolation() {
        try {
            DecisionTableOperator.INSTANCE.execute(decisionInput("partner", "not-a-list"),
                    OperatorContext.builder()
                            .nodeId(NODE_CUSTOMER_TIER)
                            .graphName("customerTierDecision")
                            .graphContext(new GraphContext(Map.of()))
                            .build());
            throw new AssertionError("Expected invalid dynamic membership parameter to fail");
        } catch (DecisionTableViolationException violation) {
            return violation;
        }
    }

    @SuppressWarnings("unchecked")
    static String tier(GraphResult result) {
        return String.valueOf(((Map<String, Object>) result.results().getRaw(NODE_CUSTOMER_TIER)).get("value"));
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> collectionParam(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            throw new DecisionTableViolationException(
                    DecisionTableViolationException.CODE_INVALID_COLLECTION_PARAM,
                    "Decision-table 'in' parameter 'allowed' must be a collection");
        }
        return (Collection<Object>) collection;
    }
}