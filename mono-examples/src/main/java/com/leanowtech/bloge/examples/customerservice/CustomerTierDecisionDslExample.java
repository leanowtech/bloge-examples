package com.leanowtech.bloge.examples.customerservice;

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
 * DSL-backed customer-tier decision table using static and dynamic {@code in} expressions.
 */
@SuppressWarnings("preview")
public final class CustomerTierDecisionDslExample {

    private static final String DSL_RESOURCE = "/bloge/customer-tier-decision.bloge";

    private CustomerTierDecisionDslExample() {
    }

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_CUSTOMER = (input, ctx) -> {
        String customerId = String.valueOf(input.get("customerId"));
        String accountType = switch (customerId) {
            case "vip" -> "vip";
            case "partner" -> "partner";
            default -> "standard";
        };
        return Map.of("customerId", customerId, "accountType", accountType);
    };

    /**
     * Compiles the customer-tier DSL resource.
     *
     * @param registry operator registry used by the compiler
     * @return compiled graph
     */
    public static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("CustomerLookupOperator", FETCH_CUSTOMER);
        return ExampleDslResources.loadGraph(DSL_RESOURCE, registry);
    }

    /**
     * Executes the DSL customer-tier graph.
     *
     * @param customerId sample customer id
     * @param priorityTypes dynamic membership list
     * @return result containing {@code customerTier.output.value}
     */
    public static GraphResult execute(String customerId, Object priorityTypes) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.execute(graph, new GraphContext(Map.of(
                "customerId", customerId,
                "priorityTypes", priorityTypes
        )));
    }
}