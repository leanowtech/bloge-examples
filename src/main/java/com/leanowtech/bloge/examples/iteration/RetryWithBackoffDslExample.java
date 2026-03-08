package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DSL version of the Retry With Backoff example — loads the graph definition from
 * a .bloge DSL string and executes it with Map-based operators.
 * <p>
 * Graph: prepareRequest → retryCall (loop: computeBackoff → callService, until success) → processResponse
 */
@SuppressWarnings("preview")
public class RetryWithBackoffDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> REQUEST_PREPARER = (input, ctx) -> {
        Thread.sleep(10);
        String serviceUrl = (String) input.get("serviceUrl");
        String requestBody = (String) input.get("requestBody");
        return Map.of("serviceUrl", serviceUrl, "requestBody", requestBody);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> BACKOFF_COMPUTER = (input, ctx) -> {
        int iteration = ((Number) input.get("iteration")).intValue();
        long backoffMs = iteration == 0 ? 0 : (long) Math.pow(2, iteration) * 100;
        if (backoffMs > 0) {
            String lastError = input.get("lastError") != null ? input.get("lastError").toString() : "";
            System.out.printf("    [backoff] Waiting %dms before attempt #%d (last error: %s)%n",
                    backoffMs, iteration, lastError);
            Thread.sleep(Math.min(backoffMs, 50));
        }
        return Map.of("backoffMs", backoffMs, "iteration", iteration);
    };

    static final AtomicInteger CALL_COUNT = new AtomicInteger(0);

    static final Operator<Map<String, Object>, Map<String, Object>> SERVICE_CALLER = (input, ctx) -> {
        Thread.sleep(20);
        int attempt = CALL_COUNT.getAndIncrement();
        if (attempt < 2) {
            return Map.of("success", false, "error", "Connection refused (attempt " + attempt + ")");
        }
        return Map.of("success", true, "data", "{ \"result\": \"ok\" }");
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> RESPONSE_PROCESSOR = (input, ctx) -> {
        Thread.sleep(10);
        var callService = (Map<String, Object>) input.get("response");
        String data = callService != null ? (String) callService.get("data") : "no data";
        return Map.of("data", data, "processedAt", "2025-02-24T12:00:00Z");
    };

    public static void main(String[] args) {
        // ── Operator Registrations ─────────────────────────────────────────────
        var registry = new DefaultOperatorRegistry();
        // RequestPreparerOperator: reads ctx.serviceUrl, ctx.requestBody → returns {serviceUrl, requestBody}
        registry.register("RequestPreparerOperator", REQUEST_PREPARER);
        // BackoffComputerOperator: reads loopIteration, carry.lastError → returns {backoffMs, iteration}
        registry.register("BackoffComputerOperator", BACKOFF_COMPUTER);
        // ServiceCallerOperator: reads serviceUrl, requestBody, backoffMs → returns {success, error|data}
        registry.register("ServiceCallerOperator", SERVICE_CALLER);
        // ResponseProcessorOperator: reads retryCall.output.callService → returns {data, processedAt}
        registry.register("ResponseProcessorOperator", RESPONSE_PROCESSOR);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph retryWithBackoff {

                  /// Prepares the service call context
                  node prepareRequest : RequestPreparerOperator {
                    input {
                      serviceUrl = ctx.serviceUrl
                      requestBody = ctx.requestBody
                    }
                  }

                  /// loop-based retry: retries a flaky service call with exponential backoff
                  /// Unlike node-level retry, this allows custom backoff logic and state tracking
                  /// loopIteration — used to compute exponential backoff delay
                  /// carry.lastError — carries the error message from the previous attempt
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
                    /// carry: forwards the last error message to the next retry iteration
                    carry {
                      lastError: callService.output.error
                    }
                    /// until: loop exits when the service call succeeds
                    until callService.output.success == true
                  }

                  /// Processes the successful service response
                  node processResponse : ResponseProcessorOperator {
                    depends_on = [retryCall]
                    input {
                      response = retryCall.output.callService
                    }
                  }
                }
                """;

        // compile DSL; operators resolved by PascalCase name
        Graph graph = loader.load(dsl);

        CALL_COUNT.set(0);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "serviceUrl", "https://api.example.com/process",
                "requestBody", "{ \"action\": \"run\" }"
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Retry With Backoff Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("prepareRequest") == NodeStatus.COMPLETED) {
            System.out.println("Prepared request: " + result.results().getRaw("prepareRequest"));
        }

        if (result.getStatus("retryCall") == NodeStatus.COMPLETED) {
            System.out.println("Loop output: " + result.results().getRaw("retryCall"));
        }

        if (result.getStatus("processResponse") == NodeStatus.COMPLETED) {
            System.out.println("Processed response: " + result.results().getRaw("processResponse"));
        }
    }
}
