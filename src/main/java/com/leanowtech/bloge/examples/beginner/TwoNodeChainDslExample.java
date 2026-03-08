package com.leanowtech.bloge.examples.beginner;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DSL variant of the two-node chain example.
 */
public final class TwoNodeChainDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> NORMALIZE_NAME = (input, ctx) -> {
        String raw = String.valueOf(input.get("name")).trim();
        String normalized = raw.isEmpty()
                ? "Anonymous"
                : Character.toUpperCase(raw.charAt(0)) + raw.substring(1).toLowerCase(Locale.ROOT);
        return Map.of("value", normalized);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> BUILD_GREETING = (input, ctx) -> Map.of(
            "text", "Hello, " + input.get("value") + '!',
            "stages", List.of("normalized", "formatted")
    );

    private TwoNodeChainDslExample() {
    }

    /**
     * Executes the DSL graph for the supplied name.
     */
    public static GraphResult execute(String name) {
        var registry = new DefaultOperatorRegistry();
        registry.register("NormalizeNameOperator", NORMALIZE_NAME);
        registry.register("BuildGreetingOperator", BUILD_GREETING);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();

        return engine.execute(
                ExampleDslResources.loadGraph("/bloge/two-node-chain.bloge", registry),
                new GraphContext(Map.of("name", name))
        );
    }

    public static void main(String[] args) {
        String name = args.length > 0 ? args[0] : "alice";
        GraphResult result = execute(name);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.results().getRaw("buildGreeting");
        System.out.println("Success: " + result.isSuccess());
        System.out.println(output.get("text"));
    }
}
