package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.agent.model.AgentOutput;
import com.leanowtech.bloge.core.engine.GraphResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExampleTest {

    @Test
    void fluentExample_answersFromKnowledgeBaseTool() {
        GraphResult result = AgentExample.execute("How do I reset my password?");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        AgentOutput output = result.getOutput(AgentExample.NODE_ID, AgentOutput.class);

        assertEquals("stop", output.finishReason());
        assertTrue(output.content().contains("KB-RESET-001"));
        assertTrue(output.content().contains("Reset the password"));
    }

    @Test
    void fluentExample_exitsAfterEscalationToolCall() {
        GraphResult result = AgentExample.execute("I want a human agent right now.");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        AgentOutput output = result.getOutput(AgentExample.NODE_ID, AgentOutput.class);

        assertEquals("tool_calls", output.finishReason());
        assertEquals(1, output.toolResults().size());
        Object toolResult = output.toolResults().values().iterator().next();
        assertTrue(toolResult instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> escalation = (Map<String, Object>) toolResult;
        assertEquals("human-support", escalation.get("queue"));
    }

    @Test
    void dslExample_answersFromKnowledgeBaseTool() {
        GraphResult result = AgentDslExample.execute("How do I reset my password?");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        AgentOutput output = result.getOutput(AgentExample.NODE_ID, AgentOutput.class);

        assertEquals("stop", output.finishReason());
        assertTrue(output.content().contains("KB-RESET-001"));
        assertTrue(output.content().contains("Reset the password"));
    }
}
