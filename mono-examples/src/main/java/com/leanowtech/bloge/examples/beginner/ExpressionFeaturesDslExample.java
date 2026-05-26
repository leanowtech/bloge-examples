package com.leanowtech.bloge.examples.beginner;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DSL-only example for BLOGE expression features added in 0.8.3-RC3.
 *
 * <p>The graph demonstrates list indexing, negative indexes, safe indexes, map-key lookup,
 * string interpolation, and {@code when} expressions inside one transform node.</p>
 */
@SuppressWarnings("preview")
public final class ExpressionFeaturesDslExample {

    private static final String DSL_RESOURCE = "/bloge/expression-features.bloge";
    static final String NODE_RESULTS = "results";

    private ExpressionFeaturesDslExample() {
    }

    static final Operator<Map<String, Object>, Map<String, Object>> CONTEXT_BUILDER = (input, ctx) -> {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", List.of("apple", "banana", "cherry"));
        payload.put("scores", Map.of("gold", 750, "silver", 650));
        payload.put("user", String.valueOf(input.getOrDefault("seed", "Ada")));
        payload.put("count", 3);
        payload.put("tier", "gold");
        payload.put("maybeItems", null);
        return payload;
    };

    /**
     * Compiles the expression showcase DSL resource.
     *
     * @param registry registry used for operator resolution
     * @return compiled graph
     */
    public static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("ContextBuilderOperator", CONTEXT_BUILDER);
        return ExampleDslResources.loadGraph(DSL_RESOURCE, registry);
    }

    /**
     * Executes the expression feature showcase.
     *
     * @param seed user name used in the interpolation example
     * @return graph result containing transform output under {@code results}
     */
    public static GraphResult execute(String seed) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.execute(graph, new GraphContext(Map.of("seed", seed)));
    }

    /**
     * Extracts the transform result map.
     *
     * @param result graph result
     * @return expression output map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> values(GraphResult result) {
        return (Map<String, Object>) result.results().getRaw(NODE_RESULTS);
    }

    public static void main(String[] args) {
        String seed = args.length > 0 ? args[0] : "Ada";
        GraphResult result = execute(seed);
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Values : " + values(result));
    }
}