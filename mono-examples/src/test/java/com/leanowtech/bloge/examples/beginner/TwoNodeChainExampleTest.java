package com.leanowtech.bloge.examples.beginner;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.NodeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoNodeChainExampleTest {

    GraphResult executeJavaApi(String name) {
        return TwoNodeChainExample.execute(name);
    }

    GraphResult executeDsl(String name) {
        return TwoNodeChainDslExample.execute(name);
    }

    @Test
    void javaApi_runsSequentialChain() {
        GraphResult result = executeJavaApi("  aLiCe  ");
        TwoNodeChainExample.GreetingMessage output = result.getOutput("buildGreeting", TwoNodeChainExample.GreetingMessage.class);

        assertTrue(result.isSuccess());
        assertEquals(NodeStatus.COMPLETED, result.getStatus("normalizeName"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("buildGreeting"));
        assertEquals("Hello, Alice!", output.text());
        assertEquals(List.of("normalized", "formatted"), output.stages());
    }

    @Test
    void dsl_runsSequentialChain() {
        GraphResult result = executeDsl("  bOB  ");
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.results().getRaw("buildGreeting");

        assertTrue(result.isSuccess());
        assertEquals(NodeStatus.COMPLETED, result.getStatus("normalizeName"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("buildGreeting"));
        assertEquals("Hello, Bob!", output.get("text"));
    }
}
