package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.agent.compiler.AgentDslCompiler;
import com.leanowtech.bloge.agent.engine.AgentLoopOperator;
import com.leanowtech.bloge.agent.model.AgentOutput;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;

import java.util.List;
import java.util.Map;

/**
 * DSL version of {@link AgentExample}.
 *
 * <p>The top-level {@code agent} definition lives in
 * {@code /bloge/agent-customer-support.bloge}. This example compiles that
 * resource into an {@link com.leanowtech.bloge.agent.model.AgentDef}, wraps it
 * as one embedded node, and executes it with the same mock LLM/tool runtime as
 * the fluent Java example.</p>
 */
@SuppressWarnings("preview")
public final class AgentDslExample {

    private static final String DSL_RESOURCE = "/bloge/agent-customer-support.bloge";

    private AgentDslExample() {
    }

    /**
     * Builds the one-node wrapper graph around the DSL-defined agent.
     *
     * @param registry operator registry used for both DSL compilation and runtime lookup
     * @return graph containing the compiled agent node
     */
    public static Graph buildGraph(DefaultOperatorRegistry registry) {
        AgentExample.registerRuntime(registry);
        AgentLoopOperator operator = new AgentLoopOperator(
                "CustomerSupportAgentDsl",
                new AgentDslCompiler(registry).compile(ExampleDslResources.readResource(DSL_RESOURCE))
        );
        return Graph.builder("customerSupportAgentDsl")
                .node(AgentExample.NODE_ID, operator)
                    .input((results, ctx) -> ctx.get(AgentExample.K_MESSAGE, String.class))
                .build();
    }

    /**
     * Executes the DSL-defined agent for one user message.
     *
     * @param message support question or escalation request
     * @return graph result containing the final {@link AgentOutput}
     */
    public static GraphResult execute(String message) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.execute(graph, new GraphContext(Map.of(AgentExample.K_MESSAGE, message)));
    }

    /**
     * Runs the DSL example with a default password-reset question.
     *
     * @param args optional first argument overrides the default message
     */
    public static void main(String[] args) {
        String message = args.length > 0 ? args[0] : "How do I reset my password?";
        GraphResult result = execute(message);
        AgentOutput output = result.getOutput(AgentExample.NODE_ID, AgentOutput.class);

        System.out.println("Success      : " + result.isSuccess());
        System.out.println("Finish reason: " + output.finishReason());
        System.out.println("Turns used   : " + output.turnsUsed());
        System.out.println("Content      : " + output.content());
        System.out.println("Tool results : " + output.toolResults());
    }
}
