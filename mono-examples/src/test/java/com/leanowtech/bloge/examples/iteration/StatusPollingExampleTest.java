package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.engine.operators.LoopOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the StatusPolling iteration example — both Java API and DSL versions.
 * <p>
 * The loop polls an async job status: returns "PROCESSING" for iterations 0-2,
 * "READY" at iteration 3, so it completes after 4 iterations (0,1,2,3).
 */
class StatusPollingExampleTest {

    // ─── Java API helpers ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private GraphResult executeJavaApi() {
        var registry = new DefaultOperatorRegistry();

        // Build loop sub-graph: single node that checks status
        Graph subGraph = Graph.builder("pollStatus__subgraph__")
                .node("checkStatus", StatusPollingExample.CHECK_STATUS)
                    .input((results, ctx) -> new StatusPollingExample.StatusCheck(
                            ctx.get("jobId", String.class),
                            ctx.get("__loopIteration__", Integer.class)))
                .build();

        // Register the operator under its actual operatorRef from the sub-graph NodeSpec.
        // On Java 25, lambda getSimpleName() returns a non-empty name (e.g. "StatusPollingExample$$Lambda/0x..."),
        // so GraphBuilder uses that as the operatorRef instead of falling back to the nodeId.
        // LoopOperator's internal engine resolves operators via registry.lookup(operatorRef).
        String checkStatusRef = subGraph.nodes().get("checkStatus").operatorRef();
        registry.register(checkStatusRef, StatusPollingExample.CHECK_STATUS);

        // Create LoopOperator: max=20, delay=50ms, until status=="READY", no carry
        var loopOp = LoopOperator.withDurability(
                subGraph,
                registry,
                20,
                Duration.ofMillis(50),
                outputs -> {
                    var checkOutput = (StatusPollingExample.StatusResult) outputs.get("checkStatus");
                    return "READY".equals(checkOutput.status());
                },
                outputs -> Map.of(),
                null,
                List.of()
        );

        // Build main graph: submitJob → pollStatus → fetchResult
        Graph mainGraph = Graph.builder("statusPolling")
                .node("submitJob", StatusPollingExample.SUBMIT_JOB)
                    .input((results, ctx) -> new StatusPollingExample.JobSubmission(
                            ctx.get("jobType", String.class),
                            ctx.get("payload", String.class)))
                .node("pollStatus", loopOp)
                    .dependsOn("submitJob")
                    .input((results, ctx) -> {
                        var handle = results.get("submitJob", StatusPollingExample.JobHandle.class);
                        return Map.<String, Object>of("jobId", handle.jobId());
                    })
                .node("fetchResult", StatusPollingExample.FETCH_RESULT)
                    .dependsOn("pollStatus")
                    .input((results, ctx) -> {
                        var loopOutput = (Map<String, Object>) results.getRaw("pollStatus");
                        var statusResult = (StatusPollingExample.StatusResult) loopOutput.get("checkStatus");
                        return Map.<String, Object>of(
                                "jobId", statusResult.jobId(),
                                "status", statusResult.status());
                    })
                .build();

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of(
                "jobType", "DATA_EXPORT",
                "payload", "export-all-users"
        ));

        return engine.executeWithOperators(mainGraph, ctx, Map.of(
                "submitJob", StatusPollingExample.SUBMIT_JOB,
                "pollStatus", loopOp,
                "fetchResult", StatusPollingExample.FETCH_RESULT
        ));
    }

    // ─── DSL helpers ────────────────────────────────────────────────────

    private GraphResult executeDsl() {
        var registry = new DefaultOperatorRegistry();
        registry.register("JobSubmitterOperator", StatusPollingDslExample.SUBMIT_JOB);
        registry.register("StatusCheckerOperator", StatusPollingDslExample.CHECK_STATUS);
        registry.register("ResultFetcherOperator", StatusPollingDslExample.FETCH_RESULT);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph statusPolling {
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
                  loop pollStatus {
                    max_iterations = 20
                    delay = 50ms
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
                    until checkStatus.output.status == "READY"
                  }
                  node fetchResult : ResultFetcherOperator {
                    depends_on = [pollStatus]
                    input {
                      jobId  = submitJob.output.jobId
                      status = pollStatus.output.checkStatus.status
                    }
                  }
                }
                """;

        Graph graph = loader.load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of(
                "jobType", "DATA_EXPORT",
                "payload", "export-all-users"
        ));

        return engine.execute(graph, ctx);
    }

    // ═══ Java API Tests ═════════════════════════════════════════════════

    @Test
    void testJavaApi_graphExecutesSuccessfully() {
        GraphResult result = executeJavaApi();
        assertTrue(result.isSuccess(), "Graph should execute successfully");
    }

    @Test
    void testJavaApi_allNodesCompleted() {
        GraphResult result = executeJavaApi();
        assertEquals(NodeStatus.COMPLETED, result.getStatus("submitJob"), "submitJob should be COMPLETED");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("pollStatus"), "pollStatus should be COMPLETED");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fetchResult"), "fetchResult should be COMPLETED");
    }

    @Test
    void testJavaApi_submitJobOutput() {
        GraphResult result = executeJavaApi();
        var handle = result.getOutput("submitJob", StatusPollingExample.JobHandle.class);
        assertNotNull(handle, "submitJob output should not be null");
        assertTrue(handle.jobId().startsWith("JOB-"), "jobId should start with 'JOB-'");
        assertEquals("JOB-DATA_EXPORT-001", handle.jobId());
        assertEquals("SUBMITTED", handle.status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testJavaApi_loopTerminatesWithReadyStatus() {
        GraphResult result = executeJavaApi();
        var loopOutput = (Map<String, Object>) result.results().getRaw("pollStatus");
        assertNotNull(loopOutput, "pollStatus output should not be null");
        var statusResult = (StatusPollingExample.StatusResult) loopOutput.get("checkStatus");
        assertNotNull(statusResult, "checkStatus output should be present in loop output");
        assertEquals("READY", statusResult.status(), "Loop should terminate with READY status");
        assertEquals(100, statusResult.progress(), "Progress should be 100 at termination");
    }

    @Test
    void testJavaApi_fetchResultOutput() {
        GraphResult result = executeJavaApi();
        var jobResult = result.getOutput("fetchResult", StatusPollingExample.JobResult.class);
        assertNotNull(jobResult, "fetchResult output should not be null");
        assertEquals("JOB-DATA_EXPORT-001", jobResult.jobId(), "fetchResult should have correct jobId");
        assertNotNull(jobResult.data(), "fetchResult data should not be null");
        assertEquals("2025-02-24T12:00:00Z", jobResult.completedAt());
    }

    // ═══ DSL Tests ══════════════════════════════════════════════════════

    @Test
    void testDsl_graphExecutesSuccessfully() {
        GraphResult result = executeDsl();
        assertTrue(result.isSuccess(), "DSL graph should execute successfully");
    }

    @Test
    void testDsl_allNodesCompleted() {
        GraphResult result = executeDsl();
        assertEquals(NodeStatus.COMPLETED, result.getStatus("submitJob"), "submitJob should be COMPLETED");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("pollStatus"), "pollStatus should be COMPLETED");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fetchResult"), "fetchResult should be COMPLETED");
    }
}
