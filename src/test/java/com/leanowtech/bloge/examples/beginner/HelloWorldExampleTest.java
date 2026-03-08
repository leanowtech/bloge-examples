package com.leanowtech.bloge.examples.beginner;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.NodeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelloWorldExampleTest {

    GraphResult executeJavaApi(String message) {
        return HelloWorldExample.execute(message);
    }

    GraphResult executeDsl(String message) {
        return HelloWorldDslExample.execute(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hello BLOGE", "hello from tests"})
    void javaApi_echoesMessage(String message) {
        GraphResult result = executeJavaApi(message);
        HelloWorldExample.EchoResponse response = result.getOutput("echo", HelloWorldExample.EchoResponse.class);

        assertTrue(result.isSuccess());
        assertEquals(NodeStatus.COMPLETED, result.getStatus("echo"));
        assertEquals(message, response.originalMessage());
        assertEquals("Echo: " + message, response.echoedMessage());
    }

    @Test
    void dsl_echoesMessage() {
        GraphResult result = executeDsl("Hello from DSL");
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) result.results().getRaw("echo");

        assertTrue(result.isSuccess());
        assertEquals(NodeStatus.COMPLETED, result.getStatus("echo"));
        assertEquals("Hello from DSL", response.get("originalMessage"));
        assertEquals("Echo: Hello from DSL", response.get("echoedMessage"));
    }
}
