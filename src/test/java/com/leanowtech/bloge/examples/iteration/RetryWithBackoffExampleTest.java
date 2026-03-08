package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.engine.operators.LoopOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"preview", "unchecked"})
class RetryWithBackoffExampleTest {

    @BeforeEach
    void resetCallCounters() {
        RetryWithBackoffExample.CALL_COUNT.set(0);
        RetryWithBackoffDslExample.CALL_COUNT.set(0);
    }

    // ═══════════════════════════════════════════════════════════
    //  Java API tests
    // ═══════════════════════════════════════════════════════════

    private GraphResult executeJavaApi() {
        var registry = new DefaultOperatorRegistry();
        // Register sub-graph operators under their node IDs so that
        // the LoopOperator's internal engine can resolve them via operatorRef.
        // operatorRef is set to the node ID for synthetic (lambda) operators (see GraphBuilder).
        registry.register("computeBackoff", RetryWithBackoffExample.COMPUTE_BACKOFF);
        registry.register("callService", RetryWithBackoffExample.CALL_SERVICE);

        // Build loop sub-graph: computeBackoff → callService
        Graph subGraph = Graph.builder("retryCall__subgraph__")
                .node("computeBackoff", RetryWithBackoffExample.COMPUTE_BACKOFF)
                    .input((results, ctx) -> {
                        int iteration = ctx.get("__loopIteration__", Integer.class);
                        var carry = (Map<String, Object>) ctx.get("__carry__", Map.class);
                        String lastError = carry != null && carry.get("lastError") != null
                                ? carry.get("lastError").toString()
                                : "";
                        return new RetryWithBackoffExample.BackoffInput(iteration, lastError);
                    })
                .node("callService", RetryWithBackoffExample.CALL_SERVICE)
                    .dependsOn("computeBackoff")
                    .input((results, ctx) -> {
                        var backoff = results.get("computeBackoff", RetryWithBackoffExample.BackoffResult.class);
                        return Map.<String, Object>of(
                                "serviceUrl", ctx.get("serviceUrl", String.class),
                                "requestBody", ctx.get("requestBody", String.class),
                                "backoffMs", backoff.backoffMs());
                    })
                .build();

        // Create LoopOperator
        var loopOp = new LoopOperator(
                subGraph,
                registry,
                5,
                null,
                outputs -> {
                    var result = (RetryWithBackoffExample.ServiceResponse) outputs.get("callService");
                    return result.success();
                },
                outputs -> {
                    var result = (RetryWithBackoffExample.ServiceResponse) outputs.get("callService");
                    return Map.of("lastError", result.error() != null ? result.error() : "");
                },
                null,
                List.of()
        );

        // Build main graph: prepareRequest → retryCall → processResponse
        Graph mainGraph = Graph.builder("retryWithBackoff")
                .node("prepareRequest", RetryWithBackoffExample.PREPARE_REQUEST)
                    .input((results, ctx) -> Map.<String, Object>of(
                            "serviceUrl", ctx.get("serviceUrl", String.class),
                            "requestBody", ctx.get("requestBody", String.class)))
                .node("retryCall", loopOp)
                    .dependsOn("prepareRequest")
                    .input((results, ctx) -> {
                        var request = results.get("prepareRequest", RetryWithBackoffExample.ServiceRequest.class);
                        return Map.<String, Object>of(
                                "serviceUrl", request.serviceUrl(),
                                "requestBody", request.requestBody());
                    })
                .node("processResponse", RetryWithBackoffExample.PROCESS_RESPONSE)
                    .dependsOn("retryCall")
                    .input((results, ctx) -> {
                        var loopOutput = (Map<String, Object>) results.getRaw("retryCall");
                        return Map.<String, Object>of("callService", loopOutput.get("callService"));
                    })
                .build();

        RetryWithBackoffExample.CALL_COUNT.set(0);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of(
                "serviceUrl", "https://api.example.com/process",
                "requestBody", "{ \"action\": \"run\" }"
        ));

        return engine.executeWithOperators(mainGraph, ctx, Map.of(
                "prepareRequest", RetryWithBackoffExample.PREPARE_REQUEST,
                "retryCall", loopOp,
                "processResponse", RetryWithBackoffExample.PROCESS_RESPONSE
        ));
    }

    @Test
    void testJavaApi_graphExecutesSuccessfully() {
        GraphResult result = executeJavaApi();
        assertTrue(result.isSuccess(), "Graph should execute successfully");
    }

    @Test
    void testJavaApi_allNodesCompleted() {
        GraphResult result = executeJavaApi();
        assertEquals(NodeStatus.COMPLETED, result.getStatus("prepareRequest"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("retryCall"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("processResponse"));
    }

    @Test
    void testJavaApi_retrySucceedsAfter3Attempts() {
        executeJavaApi();
        assertEquals(3, RetryWithBackoffExample.CALL_COUNT.get(),
                "Service should have been called exactly 3 times (attempts 0, 1, 2)");
    }

    @Test
    void testJavaApi_processedResponseOutput() {
        GraphResult result = executeJavaApi();
        var processed = result.getOutput("processResponse", RetryWithBackoffExample.ProcessedResponse.class);
        assertNotNull(processed, "Processed response should not be null");
        assertEquals("{ \"result\": \"ok\" }", processed.data(),
                "Processed response data should contain the successful service response");
    }

    // ═══════════════════════════════════════════════════════════
    //  DSL tests
    // ═══════════════════════════════════════════════════════════

    private GraphResult executeDsl() {
        var registry = new DefaultOperatorRegistry();
        registry.register("RequestPreparerOperator", RetryWithBackoffDslExample.REQUEST_PREPARER);
        registry.register("BackoffComputerOperator", RetryWithBackoffDslExample.BACKOFF_COMPUTER);
        registry.register("ServiceCallerOperator", RetryWithBackoffDslExample.SERVICE_CALLER);
        registry.register("ResponseProcessorOperator", RetryWithBackoffDslExample.RESPONSE_PROCESSOR);

        String dsl = """
                graph retryWithBackoff {
                  node prepareRequest : RequestPreparerOperator {
                    input {
                      serviceUrl = ctx.serviceUrl
                      requestBody = ctx.requestBody
                    }
                  }
                  loop retryCall {
                    max_iterations = 5
                    depends_on = [prepareRequest]
                    node computeBackoff : BackoffComputerOperator {
                      input {
                        iteration = loopIteration
                        lastError = carry.lastError
                      }
                      output {
                        backoffMs: Number
                        iteration: Int
                      }
                    }
                    node callService : ServiceCallerOperator {
                      depends_on = [computeBackoff]
                      input {
                        serviceUrl = ctx.serviceUrl
                        requestBody = ctx.requestBody
                        backoffMs = computeBackoff.output.backoffMs
                      }
                    }
                    carry {
                      lastError: callService.output.error
                    }
                    until callService.output.success == true
                  }
                  node processResponse : ResponseProcessorOperator {
                    depends_on = [retryCall]
                    input {
                      response = retryCall.output.callService
                    }
                  }
                }
                """;

        Graph graph = new GraphLoader(registry).load(dsl);

        RetryWithBackoffDslExample.CALL_COUNT.set(0);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of(
                "serviceUrl", "https://api.example.com/process",
                "requestBody", "{ \"action\": \"run\" }"
        ));

        return engine.execute(graph, ctx);
    }

    @Test
    void testDsl_graphExecutesSuccessfully() {
        GraphResult result = executeDsl();
        assertTrue(result.isSuccess(), "DSL graph should execute successfully");
    }

    @Test
    void testDsl_allNodesCompleted() {
        GraphResult result = executeDsl();
        assertEquals(NodeStatus.COMPLETED, result.getStatus("prepareRequest"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("retryCall"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("processResponse"));
    }

    @Test
    void testDsl_retrySucceedsAfter3Attempts() {
        executeDsl();
        assertEquals(3, RetryWithBackoffDslExample.CALL_COUNT.get(),
                "DSL service should have been called exactly 3 times (attempts 0, 1, 2)");
    }
}
