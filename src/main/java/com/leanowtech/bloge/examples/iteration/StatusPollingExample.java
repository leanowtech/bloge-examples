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

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates "Status Polling" using the LoopOperator — a loop that polls
 * an async job status until READY.
 * <p>
 * Graph: submitJob → pollStatus (loop: checkStatus until READY) → fetchResult
 */
@SuppressWarnings("preview")
public class StatusPollingExample {

    // --- Records ---

    public record JobSubmission(String jobType, String payload) {}
    public record JobHandle(String jobId, String status) {}
    public record StatusCheck(String jobId, int iteration) {}
    public record StatusResult(String jobId, String status, int progress) {}
    public record JobResult(String jobId, Object data, String completedAt) {}

    // --- Operators ---

    static final Operator<JobSubmission, JobHandle> SUBMIT_JOB = (input, ctx) -> {
        Thread.sleep(30);
        return new JobHandle("JOB-" + input.jobType() + "-001", "SUBMITTED");
    };

    /** Simulates polling: returns "PROCESSING" for iterations 0-2, "READY" for 3+. */
    static final Operator<StatusCheck, StatusResult> CHECK_STATUS = (input, ctx) -> {
        Thread.sleep(20);
        int iteration = input.iteration();
        String status = iteration >= 3 ? "READY" : "PROCESSING";
        int progress = Math.min(100, (iteration + 1) * 25);
        return new StatusResult(input.jobId(), status, progress);
    };

    static final Operator<Map<String, Object>, JobResult> FETCH_RESULT = (input, ctx) -> {
        Thread.sleep(20);
        String jobId = (String) input.get("jobId");
        String status = (String) input.get("status");
        return new JobResult(jobId, Map.of("payload", "processed-data", "status", status), "2025-02-24T12:00:00Z");
    };

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        var listener = new LoggingListener();

        // Register sub-graph operator
        registry.register("checkStatus", CHECK_STATUS);

        // Build loop sub-graph: single node that checks status
        Graph subGraph = Graph.builder("pollStatus__subgraph__")
                .node("checkStatus", CHECK_STATUS)
                    .input((results, ctx) -> new StatusCheck(
                            ctx.get("jobId", String.class),
                            ctx.get("__loopIteration__", Integer.class)))
                .build();

        // Create LoopOperator
        var loopOp = new LoopOperator(
                subGraph,
                registry,
                20,                          // maxIterations
                Duration.ofMillis(50),       // delay (fast for example)
                // untilCondition: check terminal output for status == "READY"
                outputs -> {
                    var checkOutput = (StatusResult) outputs.get("checkStatus");
                    return "READY".equals(checkOutput.status());
                },
                // carryMapper: no carry needed, return empty map
                outputs -> Map.of(),
                null,                        // no checkpoint store
                List.of(listener)
        );

        // Build main graph
        Graph mainGraph = Graph.builder("statusPolling")
                .node("submitJob", SUBMIT_JOB)
                    .input((results, ctx) -> new JobSubmission(
                            ctx.get("jobType", String.class),
                            ctx.get("payload", String.class)))
                .node("pollStatus", loopOp)
                    .dependsOn("submitJob")
                    .input((results, ctx) -> {
                        var handle = results.get("submitJob", JobHandle.class);
                        return Map.<String, Object>of("jobId", handle.jobId());
                    })
                .node("fetchResult", FETCH_RESULT)
                    .dependsOn("pollStatus")
                    .input((results, ctx) -> {
                        var loopOutput = (Map<String, Object>) results.getRaw("pollStatus");
                        var statusResult = (StatusResult) loopOutput.get("checkStatus");
                        return Map.<String, Object>of(
                                "jobId", statusResult.jobId(),
                                "status", statusResult.status());
                    })
                .build();

        // Execute
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(listener))
                .build();
        var ctx = new GraphContext(Map.of(
                "jobType", "DATA_EXPORT",
                "payload", "export-all-users"
        ));

        GraphResult result = engine.executeWithOperators(mainGraph, ctx, Map.of(
                "submitJob", SUBMIT_JOB,
                "pollStatus", loopOp,
                "fetchResult", FETCH_RESULT
        ));

        // Print results
        System.out.println("\n═══ Status Polling Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-15s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("submitJob") == NodeStatus.COMPLETED) {
            JobHandle handle = result.getOutput("submitJob", JobHandle.class);
            System.out.println("Job submitted: " + handle);
        }

        if (result.getStatus("pollStatus") == NodeStatus.COMPLETED) {
            System.out.println("Poll loop output: " + result.results().getRaw("pollStatus"));
        }

        if (result.getStatus("fetchResult") == NodeStatus.COMPLETED) {
            JobResult jobResult = result.getOutput("fetchResult", JobResult.class);
            System.out.println("Final result: " + jobResult);
        }
    }
}
