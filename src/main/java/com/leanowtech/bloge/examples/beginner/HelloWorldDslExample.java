package com.leanowtech.bloge.examples.beginner;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;

import java.util.List;
import java.util.Map;

/**
 * DSL counterpart of {@link HelloWorldExample}.
 *
 * <p>This variant keeps the orchestration in a checked-in {@code .bloge} file while binding
 * the node to a small Java operator implementation through the registry.</p>
 */
public final class HelloWorldDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> ECHO = (input, ctx) -> Map.of(
            "originalMessage", input.get("message"),
            "echoedMessage", "Echo: " + input.get("message")
    );

    private HelloWorldDslExample() {
    }

    /**
     * Executes the external DSL graph for the supplied message.
     */
    public static GraphResult execute(String message) {
        var registry = new DefaultOperatorRegistry();
        registry.register("EchoOperator", ECHO);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();

        return engine.execute(
                ExampleDslResources.loadGraph("/bloge/hello-world.bloge", registry),
                new GraphContext(Map.of("message", message))
        );
    }

    public static void main(String[] args) {
        String message = args.length > 0 ? args[0] : "Hello BLOGE";
        GraphResult result = execute(message);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) result.results().getRaw("echo");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Echoed message: " + response.get("echoedMessage"));
    }
}
