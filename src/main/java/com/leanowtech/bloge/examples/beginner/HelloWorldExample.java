package com.leanowtech.bloge.examples.beginner;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The smallest typed BLOGE example: one node reads a message and echoes it back.
 *
 * <p>The example is intentionally tiny so new users can focus on the essentials:
 * create a graph, assemble node input from {@link GraphContext}, and inspect the
 * resulting node output.</p>
 */
public final class HelloWorldExample {

    /** Request payload for the single echo node. */
    public record EchoRequest(String message) {
    }

    /** Response payload emitted by the echo node. */
    public record EchoResponse(String originalMessage, String echoedMessage) {
    }

    static final Operator<EchoRequest, EchoResponse> ECHO = (input, ctx) ->
            new EchoResponse(input.message(), "Echo: " + input.message());

    private HelloWorldExample() {
    }

    /**
     * Builds the minimal single-node graph.
     */
    public static Graph buildGraph() {
        return Graph.builder("helloWorld")
                .node("echo", ECHO)
                    .input((results, ctx) -> new EchoRequest(ctx.get("message", String.class)))
                    .timeout(Duration.ofSeconds(1))
                .build();
    }

    /**
     * Executes the graph for the supplied message.
     */
    public static GraphResult execute(String message) {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.executeWithOperators(
                buildGraph(),
                new GraphContext(Map.of("message", message)),
                Map.of("echo", ECHO)
        );
    }

    public static void main(String[] args) {
        String message = args.length > 0 ? args[0] : "Hello BLOGE";
        GraphResult result = execute(message);
        EchoResponse response = result.getOutput("echo", EchoResponse.class);
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Echoed message: " + response.echoedMessage());
    }
}
