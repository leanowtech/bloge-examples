package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.agent.compiler.AgentDslCompiler;
import com.leanowtech.bloge.agent.engine.StreamingAgentLoopOperator;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;

import java.util.List;
import java.util.Map;

/**
 * DSL-backed streaming agent example for the data-analysis assistant.
 */
@SuppressWarnings("preview")
public final class StreamingAnalysisAgentDslExample {

    private static final String DSL_RESOURCE = "/bloge/streaming-analysis-agent.bloge";

    private StreamingAnalysisAgentDslExample() {
    }

    /**
     * Builds a graph whose streaming agent definition is compiled from DSL.
     *
     * @param registry registry used for tool and model operators
     * @return graph containing the compiled streaming agent node
     */
    public static Graph buildGraph(DefaultOperatorRegistry registry) {
        StreamingAnalysisAgentExample.registerSharedRuntime(registry);
        StreamingAgentLoopOperator agent = new StreamingAgentLoopOperator(
                "DataAnalystAgentDsl",
                new AgentDslCompiler(registry).compile(ExampleDslResources.readResource(DSL_RESOURCE)),
                registry
        );
        registry.registerRaw(StreamingAnalysisAgentExample.NODE_ID, agent);
        return StreamingAnalysisAgentExample.buildGraph();
    }

    /**
     * Executes the DSL streaming agent.
     *
     * @param question user analysis question
     * @return result whose {@code analyze} output is a list of streaming chunks
     */
    public static GraphResult execute(String question) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.execute(graph, new GraphContext(Map.of(StreamingAnalysisAgentExample.K_QUESTION, question)));
    }
}