package com.leanowtech.bloge.examples.beginner;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shows the next step after hello world: one node normalises data and a second node
 * consumes that output to produce the final greeting.
 */
public final class TwoNodeChainExample {

    /** Raw request from the caller. */
    public record GreetingRequest(String name) {
    }

    /** Canonicalised intermediate result that the next node can depend on. */
    public record NormalizedName(String value) {
    }

    /** Final output of the graph. */
    public record GreetingMessage(String text, List<String> stages) {
    }

    static final Operator<GreetingRequest, NormalizedName> NORMALIZE_NAME = (input, ctx) -> {
        String trimmed = input.name().trim();
        String normalized = trimmed.isEmpty()
                ? "Anonymous"
                : Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1).toLowerCase(Locale.ROOT);
        return new NormalizedName(normalized);
    };

    static final Operator<NormalizedName, GreetingMessage> BUILD_GREETING = (input, ctx) ->
            new GreetingMessage("Hello, " + input.value() + '!', List.of("normalized", "formatted"));

    private TwoNodeChainExample() {
    }

    /**
     * Builds a graph with an explicit dependency edge from {@code normalizeName} to {@code buildGreeting}.
     */
    public static Graph buildGraph() {
        return Graph.builder("twoNodeChain")
                .node("normalizeName", NORMALIZE_NAME)
                    .input((results, ctx) -> new GreetingRequest(ctx.get("name", String.class)))
                    .timeout(Duration.ofSeconds(1))
                .node("buildGreeting", BUILD_GREETING)
                    .dependsOn("normalizeName")
                    .input((results, ctx) -> results.get("normalizeName", NormalizedName.class))
                    .timeout(Duration.ofSeconds(1))
                .build();
    }

    /**
     * Executes the chain for the supplied name.
     */
    public static GraphResult execute(String name) {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.executeWithOperators(
                buildGraph(),
                new GraphContext(Map.of("name", name)),
                Map.of(
                        "normalizeName", NORMALIZE_NAME,
                        "buildGreeting", BUILD_GREETING
                )
        );
    }

    public static void main(String[] args) {
        String name = args.length > 0 ? args[0] : "alice";
        GraphResult result = execute(name);
        GreetingMessage output = result.getOutput("buildGreeting", GreetingMessage.class);
        System.out.println("Success: " + result.isSuccess());
        System.out.println(output.text());
    }
}
