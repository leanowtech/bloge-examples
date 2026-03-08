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

/**
 * DSL version of the Status Polling example — loads the graph definition from
 * a .bloge DSL string and executes it with Map-based operators.
 * <p>
 * Graph: submitJob → pollStatus (loop: checkStatus until READY) → fetchResult
 */
@SuppressWarnings("preview")
public class StatusPollingDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> SUBMIT_JOB = (input, ctx) -> {
        Thread.sleep(30);
        String jobType = (String) input.get("jobType");
        return Map.of("jobId", "JOB-" + jobType + "-001", "status", "SUBMITTED");
    };

    /** Simulates polling: returns "PROCESSING" for iterations 0-2, "READY" for 3+. */
    static final Operator<Map<String, Object>, Map<String, Object>> CHECK_STATUS = (input, ctx) -> {
        Thread.sleep(20);
        int iteration = ((Number) input.get("iteration")).intValue();
        String status = iteration >= 3 ? "READY" : "PROCESSING";
        int progress = Math.min(100, (iteration + 1) * 25);
        String jobId = (String) input.get("jobId");
        return Map.of("jobId", jobId, "status", status, "progress", progress);
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_RESULT = (input, ctx) -> {
        Thread.sleep(20);
        String jobId = (String) input.get("jobId");
        var checkStatus = (Map<String, Object>) input.get("checkStatus");
        String status = checkStatus != null ? (String) checkStatus.get("status") : "UNKNOWN";
        return Map.of(
                "jobId", jobId,
                "data", Map.of("payload", "processed-data", "status", status),
                "completedAt", "2025-02-24T12:00:00Z"
        );
    };

    public static void main(String[] args) {
        // ── Operator Registrations ─────────────────────────────────────────────
        var registry = new DefaultOperatorRegistry();
        // JobSubmitterOperator: reads ctx.jobType, ctx.payload → returns {jobId, status}
        registry.register("JobSubmitterOperator", SUBMIT_JOB);
        // StatusCheckerOperator: reads jobId, loopIteration → returns {jobId, status, progress}
        registry.register("StatusCheckerOperator", CHECK_STATUS);
        // ResultFetcherOperator: reads jobId, checkStatus (loop output) → returns {jobId, data, completedAt}
        registry.register("ResultFetcherOperator", FETCH_RESULT);

        var loader = new GraphLoader(registry);

        String dsl = """
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

        // compile DSL; operators resolved by PascalCase name
        Graph graph = loader.load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "jobType", "DATA_EXPORT",
                "payload", "export-all-users"
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Status Polling Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-15s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("submitJob") == NodeStatus.COMPLETED) {
            System.out.println("Job submitted: " + result.results().getRaw("submitJob"));
        }

        if (result.getStatus("pollStatus") == NodeStatus.COMPLETED) {
            System.out.println("Poll loop output: " + result.results().getRaw("pollStatus"));
        }

        if (result.getStatus("fetchResult") == NodeStatus.COMPLETED) {
            System.out.println("Final result: " + result.results().getRaw("fetchResult"));
        }
    }
}
