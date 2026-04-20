package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.operators.ai.AiTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolCallingExampleTest {

    @Test
    void fluentExample_executesToolCallAndFinalAnswer() throws Exception {
        GraphResult result = AiToolCallingExample.execute("What is the weather in Paris right now?");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        AiTypes.LlmChatOutput thought = result.getOutput("think", AiTypes.LlmChatOutput.class);
        AiTypes.LlmChatOutput finalReply = result.getOutput("respond", AiTypes.LlmChatOutput.class);

        assertEquals("tool_calls", thought.finishReason());
        assertEquals("lookupWeather", thought.toolCalls().getFirst().name());
        assertEquals("stop", finalReply.finishReason());
        assertTrue(finalReply.content().contains("Paris"));
        assertTrue(finalReply.content().contains("22C"));
    }

    @Test
    void dslExample_executesToolCallAndFinalAnswer() {
        GraphResult result = AiToolCallingDslExample.execute("What is the weather in Paris right now?");

        assertTrue(result.isSuccess(), () -> "Errors: " + result.errors() + " statuses: " + result.statusMap());
        AiTypes.LlmChatOutput finalReply = result.getOutput("respond", AiTypes.LlmChatOutput.class);

        assertEquals("stop", finalReply.finishReason());
        assertTrue(finalReply.content().contains("Paris"));
        assertTrue(finalReply.content().contains("22C"));
    }
}
