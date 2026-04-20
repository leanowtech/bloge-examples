package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.NodeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
class DynamicSubGraphExampleTest {

    @Test
    void example_executesGeneratedDslAndReturnsNestedTerminalOutputs() {
        GraphResult result = DynamicSubGraphExample.execute(
                "Draft a refund follow-up for order #42",
                List.of("Customer opened a refund request", "Support asked for order details")
        );

        assertTrue(result.isSuccess());
        assertEquals(NodeStatus.COMPLETED, result.getStatus("plan"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("executePlan"));

        Map<String, Object> nestedOutputs = result.getOutput("executePlan", Map.class);
        DynamicSubGraphExample.DynamicReply reply =
                (DynamicSubGraphExample.DynamicReply) nestedOutputs.get("draftReply");

        assertEquals("refund", reply.planKind());
        assertEquals(2, reply.historySize());
        assertTrue(reply.summary().contains("refund"));
    }
}
