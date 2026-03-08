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

public class StatusPollingReplExample {

    private static final String DSL = """

                graph statusPolling {

                  /// Submits an async job for processing
                  node submitJob : JobSubmitterOperator {
                    input {
                      jobType = ctx.jobType
                      payload = ctx.payload
                    }
                    output {
                      jobId: String
                      status: String
                    }
                  }

                  /// loop with until condition: polls job status every 2s, up to 20 iterations
                  /// loopIteration — implicit variable for current iteration count (0-based)
                  loop pollStatus {
                    max_iterations = 20
                    delay = 2s
                    depends_on = [submitJob]
                    node checkStatus : StatusCheckerOperator {
                      input {
                        jobId     = submitJob.output.jobId
                        iteration = loopIteration
                      }
                      output {
                        jobId: String
                        status: String
                        progress: Number
                      }
                    }
                    /// until: loop exits once job status reaches "READY"
                    until checkStatus.output.status == "READY"
                  }

                  /// Retrieves the final result after polling completes
                  node fetchResult : ResultFetcherOperator {
                    depends_on = [pollStatus]
                    input {
                      jobId  = submitJob.output.jobId
                      status = pollStatus.output.checkStatus.status
                    }
                  }
                }
                
            """;

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("JobSubmitterOperator", StatusPollingDslExample.SUBMIT_JOB);
        registry.register("StatusCheckerOperator", StatusPollingDslExample.CHECK_STATUS);
        registry.register("ResultFetcherOperator", StatusPollingDslExample.FETCH_RESULT);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String jobType = ReplHelper.promptString(scanner, "jobType", "DATA_EXPORT");
        String payload = ReplHelper.promptString(scanner, "payload", "export-all-users");
        return Map.of(
                "jobType", jobType,
                "payload", payload
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
                ReplHelper.header("Status Polling REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
