package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.agent.model.AgentOutput;
import com.leanowtech.bloge.agent.model.AgentStreamChunk;
import com.leanowtech.bloge.core.engine.GraphResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingAnalysisAgentExampleTest {

    @Test
    void fluentApi_streamsToolLifecycleAndFinalAnswer() {
        GraphResult result = StreamingAnalysisAgentExample.execute("How is revenue trending this week?");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertTrue(StreamingAnalysisAgentExample.chunks(result).stream().anyMatch(AgentStreamChunk.ToolStart.class::isInstance));
        assertTrue(StreamingAnalysisAgentExample.chunks(result).stream().anyMatch(AgentStreamChunk.ToolEnd.class::isInstance));
        assertTrue(StreamingAnalysisAgentExample.chunks(result).stream().anyMatch(AgentStreamChunk.Token.class::isInstance));
        AgentOutput output = StreamingAnalysisAgentExample.finalOutput(result);
        assertEquals("stop", output.finishReason());
        assertTrue(output.content().contains("Revenue"));
    }

    @Test
    void dsl_streamsTokenAndDoneChunks() {
        GraphResult result = StreamingAnalysisAgentDslExample.execute("How is revenue trending this week?");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        assertInstanceOf(AgentStreamChunk.Done.class,
                StreamingAnalysisAgentExample.chunks(result).getLast());
        assertEquals("stop", StreamingAnalysisAgentExample.finalOutput(result).finishReason());
    }
}