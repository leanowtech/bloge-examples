package com.leanowtech.bloge.examples.rag;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for the RAG pipeline example.
 */
class RagPipelineExampleTest {

    private GraphResult run(String query) throws Exception {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of(RagPipelineExample.K_QUERY, query));
        return engine.executeWithOperators(
                RagPipelineExample.buildGraph(), ctx, RagPipelineExample.operatorMap());
    }

    @Test
    void pipeline_runsAllFourNodes_withNoErrors() throws Exception {
        var result = run("What is RAG?");

        assertTrue(result.errors().isEmpty(),
                () -> "Unexpected errors: " + result.errors());
    }

    @Test
    void jsonOutput_containsMockLlmAnswer() throws Exception {
        var result = run("Explain RAG in one sentence.");

        assertTrue(result.results().hasResult("jsonOutput"));
        String answer = result.results().get("jsonOutput", String.class);
        assertNotNull(answer);
        assertTrue(answer.contains("[MockLLM]"),
                "Expected mock LLM prefix in answer, got: " + answer);
    }

    @Test
    void allNodes_succeed() throws Exception {
        var result = run("What is a vector store?");

        assertTrue(result.results().hasResult("ragRetrieve"));
        assertTrue(result.results().hasResult("formatContext"));
        assertTrue(result.results().hasResult("llmChat"));
        assertTrue(result.results().hasResult("jsonOutput"));
    }
}
