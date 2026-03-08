package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.engine.operators.LoopOperator;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates a loop-based retry mechanism with custom exponential backoff
 * using the LoopOperator's {@code carry} and {@code __loopIteration__} features.
 * <p>
 * Unlike node-level retry (which uses fixed/exponential/jitter strategies),
 * this pattern gives full control over backoff logic and state tracking between attempts.
 * <p>
 * Graph: prepareRequest → retryCall (loop: computeBackoff → callService, until success) → processResponse
 */
@SuppressWarnings("preview")
public class RetryWithBackoffExample {

    // --- Records ---

    public record ServiceRequest(String serviceUrl, String requestBody) {}
    public record BackoffInput(int iteration, String lastError) {}
    public record BackoffResult(long backoffMs, int iteration) {}
    public record ServiceResponse(boolean success, String data, String error) {}
    public record ProcessedResponse(String data, String processedAt) {}

    // --- Operators ---

    static final Operator<Map<String, Object>, ServiceRequest> PREPARE_REQUEST = (input, ctx) -> {
        Thread.sleep(10);
        String serviceUrl = (String) input.get("serviceUrl");
        String requestBody = (String) input.get("requestBody");
        return new ServiceRequest(serviceUrl, requestBody);
    };

    static final Operator<BackoffInput, BackoffResult> COMPUTE_BACKOFF = (input, ctx) -> {
        int iteration = input.iteration();
        long backoffMs = iteration == 0 ? 0 : (long) Math.pow(2, iteration) * 100;
        if (backoffMs > 0) {
            System.out.printf("    [backoff] Waiting %dms before attempt #%d (last error: %s)%n",
                    backoffMs, iteration, input.lastError());
            Thread.sleep(Math.min(backoffMs, 50)); // capped for demo
        }
        return new BackoffResult(backoffMs, iteration);
    };

    static final AtomicInteger CALL_COUNT = new AtomicInteger(0);

    static final Operator<Map<String, Object>, ServiceResponse> CALL_SERVICE = (input, ctx) -> {
        Thread.sleep(20);
        int attempt = CALL_COUNT.getAndIncrement();
        if (attempt < 2) {
            return new ServiceResponse(false, null, "Connection refused (attempt " + attempt + ")");
        }
        return new ServiceResponse(true, "{ \"result\": \"ok\" }", null);
    };

    static final Operator<Map<String, Object>, ProcessedResponse> PROCESS_RESPONSE = (input, ctx) -> {
        Thread.sleep(10);
        @SuppressWarnings("unchecked")
        var callServiceOutput = (ServiceResponse) input.get("callService");
        return new ProcessedResponse(callServiceOutput.data(), "2025-02-24T12:00:00Z");
    };

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        var listener = new LoggingListener();

        // Register sub-graph operators
        registry.register("computeBackoff", COMPUTE_BACKOFF);
        registry.register("callService", CALL_SERVICE);

        // Build loop sub-graph: computeBackoff → callService
        Graph subGraph = Graph.builder("retryCall__subgraph__")
                .node("computeBackoff", COMPUTE_BACKOFF)
                    .input((results, ctx) -> {
                        int iteration = ctx.get("__loopIteration__", Integer.class);
                        @SuppressWarnings("unchecked")
                        var carry = (Map<String, Object>) ctx.get("__carry__", Map.class);
                        String lastError = carry != null && carry.get("lastError") != null
                                ? carry.get("lastError").toString()
                                : "";
                        return new BackoffInput(iteration, lastError);
                    })
                .node("callService", CALL_SERVICE)
                    .dependsOn("computeBackoff")
                    .input((results, ctx) -> {
                        var backoff = results.get("computeBackoff", BackoffResult.class);
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
                5,                               // maxIterations
                null,                            // no delay (backoff is computed per-iteration)
                // untilCondition: stop when callService reports success
                outputs -> {
                    var result = (ServiceResponse) outputs.get("callService");
                    return result.success();
                },
                // carryMapper: pass lastError to next iteration
                outputs -> {
                    var result = (ServiceResponse) outputs.get("callService");
                    return Map.of("lastError", result.error() != null ? result.error() : "");
                },
                null,                            // no checkpoint store
                List.of(listener)
        );

        // Build main graph: prepareRequest → retryCall (loop) → processResponse
        Graph mainGraph = Graph.builder("retryWithBackoff")
                .node("prepareRequest", PREPARE_REQUEST)
                    .input((results, ctx) -> Map.<String, Object>of(
                            "serviceUrl", ctx.get("serviceUrl", String.class),
                            "requestBody", ctx.get("requestBody", String.class)))
                .node("retryCall", loopOp)
                    .dependsOn("prepareRequest")
                    .input((results, ctx) -> {
                        var request = results.get("prepareRequest", ServiceRequest.class);
                        return Map.<String, Object>of(
                                "serviceUrl", request.serviceUrl(),
                                "requestBody", request.requestBody());
                    })
                .node("processResponse", PROCESS_RESPONSE)
                    .dependsOn("retryCall")
                    .input((results, ctx) -> {
                        var loopOutput = (Map<String, Object>) results.getRaw("retryCall");
                        return Map.<String, Object>of("callService", loopOutput.get("callService"));
                    })
                .build();

        // Reset counter before execution
        CALL_COUNT.set(0);

        // Execute
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(listener))
                .build();
        var ctx = new GraphContext(Map.of(
                "serviceUrl", "https://api.example.com/process",
                "requestBody", "{ \"action\": \"run\" }"
        ));

        GraphResult result = engine.executeWithOperators(mainGraph, ctx, Map.of(
                "prepareRequest", PREPARE_REQUEST,
                "retryCall", loopOp,
                "processResponse", PROCESS_RESPONSE
        ));

        // Print results
        System.out.println("\n═══ Retry With Backoff Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("prepareRequest") == NodeStatus.COMPLETED) {
            ServiceRequest request = result.getOutput("prepareRequest", ServiceRequest.class);
            System.out.println("Prepared request: " + request);
        }

        if (result.getStatus("retryCall") == NodeStatus.COMPLETED) {
            var loopOutput = (Map<String, Object>) result.results().getRaw("retryCall");
            System.out.println("Loop output: " + loopOutput);
        }

        if (result.getStatus("processResponse") == NodeStatus.COMPLETED) {
            ProcessedResponse processed = result.getOutput("processResponse", ProcessedResponse.class);
            System.out.println("Processed response: " + processed);
        }
    }
}
