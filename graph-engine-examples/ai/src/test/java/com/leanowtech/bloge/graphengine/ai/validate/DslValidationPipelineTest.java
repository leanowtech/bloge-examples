package com.leanowtech.bloge.graphengine.ai.validate;

import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.runtime.work.WorkItemNotifier;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.durable.store.memory.InMemoryWorkItemStore;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DslValidationPipelineTest {

    @Test
    void validateReturnsValidGraphResultForBasicGraph() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("echo", new EchoOperator());
        DslValidationPipeline pipeline = DslValidationPipeline.builder()
                .operatorRegistry(registry)
                .build();

        DslValidationResult result = pipeline.validate("""
                /// Greeting workflow.
                graph greet {
                  node hello : echo {
                    input {
                      message = ctx.message
                    }
                    timeout = 1s
                  }
                }
                """);

        assertTrue(result.valid());
        assertEquals(GraphExecutionMode.GRAPH, result.executionMode());
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.severity() == DslDiagnostic.Severity.ERROR));
    }

    @Test
    void validateSurfacesParseFailures() {
        DslValidationPipeline pipeline = DslValidationPipeline.builder().build();

        DslValidationResult result = pipeline.validate("""
                graph broken {
                  node hello : echo {
                """);

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.stage() == DslDiagnostic.Stage.PARSE));
    }

    @Test
    void validateSupportsRemoteGraphsWhenWorkItemStoreIsConfigured() {
        DslValidationPipeline pipeline = DslValidationPipeline.builder()
                .workItemStore(new InMemoryWorkItemStore())
                .workItemNotifier(WorkItemNotifier.NOOP)
                .build();

        DslValidationResult result = pipeline.validate("""
                /// Remote workflow.
                graph classifyOrder {
                  node classify : RemoteClassifier {
                    input {
                      orderId = ctx.orderId
                    }
                    execution_mode = remote
                    worker_topic = "workers.ai"
                    timeout = 30s
                  }
                }
                """);

        assertTrue(result.valid());
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.severity() == DslDiagnostic.Severity.ERROR));
    }

    private static final class EchoOperator implements Operator<String, String> {
        @Override
        public String execute(String input, com.leanowtech.bloge.core.operator.OperatorContext ctx) {
            return input;
        }
    }
}
