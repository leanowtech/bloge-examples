package com.leanowtech.bloge.examples.iteration;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class RetryWithBackoffReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("RequestPreparerOperator", RetryWithBackoffDslExample.REQUEST_PREPARER);
        registry.register("BackoffComputerOperator", RetryWithBackoffDslExample.BACKOFF_COMPUTER);
        registry.register("ServiceCallerOperator", RetryWithBackoffDslExample.SERVICE_CALLER);
        registry.register("ResponseProcessorOperator", RetryWithBackoffDslExample.RESPONSE_PROCESSOR);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String serviceUrl = ReplHelper.promptString(scanner, "serviceUrl", "https://api.example.com/process");
        String requestBody = ReplHelper.promptString(scanner, "requestBody", "{ \"action\": \"run\" }");
        return Map.of(
                "serviceUrl", serviceUrl,
                "requestBody", requestBody
        );
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();

        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            boolean runAgain;
            do {
                ReplHelper.header("Retry With Backoff REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
