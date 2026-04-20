package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.engine.operators.LoopOperator;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import org.junit.jupiter.api.Test;

import java.time.Duration;


import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the CursorPagination iteration example — both Java API and DSL versions.
 * <p>
 * The loop fetches 3 pages of 3 records each (9 total), carrying cursor and
 * totalRecords between iterations. It terminates when hasMore == false.
 * <p>
 * <b>Work-around for LoopOperator terminal-only output collection:</b>
 * LoopOperator only exposes terminal node outputs to the untilCondition and carryMapper.
 * In the original example's sub-graph (fetchPage → transformPage), fetchPage has an outgoing
 * edge so it is NOT a terminal node — meaning its output is invisible to the loop predicates.
 * We work around this by using a single combined "fetchAndTransform" node that internally
 * performs both operations. As the sole (and therefore terminal) node, its full output is
 * available to untilCondition, carryMapper, and downstream nodes.
 */
class CursorPaginationExampleTest {

    // ─── Combined operator for Java API ─────────────────────────────────

    @SuppressWarnings("unchecked")
    private static final Operator<Map<String, Object>, Map<String, Object>> FETCH_AND_TRANSFORM = (input, ctx) -> {

        String cursor = "";
        var carry = (Map<String, Object>) ctx.graphContext().get("__carry__", Map.class);
        if (carry != null && carry.containsKey("cursor")) {
            cursor = (String) carry.get("cursor");
        }


        var pageReq = new CursorPaginationExample.PageRequest(
                ctx.graphContext().get("endpoint", String.class),
                ctx.graphContext().get("pageSize", Integer.class),
                cursor);
        var pageResp = CursorPaginationExample.FETCH_PAGE.execute(pageReq, ctx);


        int currentTotal = carry != null && carry.containsKey("totalRecords")
                ? ((Number) carry.get("totalRecords")).intValue() : 0;
        var transformInput = new CursorPaginationExample.TransformInput(pageResp.records(), currentTotal);
        var transformResult = CursorPaginationExample.TRANSFORM_PAGE.execute(transformInput, ctx);


        return Map.of(
                "fetchPage", pageResp,
                "transformPage", transformResult);
    };

    // ─── Combined operator for DSL ──────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static final Operator<Map<String, Object>, Map<String, Object>> DSL_FETCH_AND_TRANSFORM = (input, ctx) -> {
        String endpoint = (String) input.get("endpoint");
        int pageSize = ((Number) input.get("pageSize")).intValue();
        String cursor = input.get("cursor") != null ? (String) input.get("cursor") : "";
        int currentTotal = input.get("currentTotal") != null
                ? ((Number) input.get("currentTotal")).intValue() : 0;


        var fetchInput = Map.<String, Object>of("endpoint", endpoint, "pageSize", pageSize, "cursor", cursor);
        var fetchResult = CursorPaginationDslExample.FETCH_PAGE.execute(fetchInput, ctx);


        var transformInput = Map.<String, Object>of(
                "records", fetchResult.get("records"),
                "currentTotal", currentTotal);
        var transformResult = CursorPaginationDslExample.TRANSFORM_PAGE.execute(transformInput, ctx);


        return Map.of(
                "records", fetchResult.get("records"),
                "nextCursor", fetchResult.get("nextCursor"),
                "hasMore", fetchResult.get("hasMore"),
                "transformed", transformResult.get("transformed"),
                "runningTotal", transformResult.get("runningTotal"));
    };

    // ─── Java API helpers ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private GraphResult executeJavaApi() {
        var registry = new DefaultOperatorRegistry();


        Graph subGraph = Graph.builder("fetchAllPages__subgraph__")
                .node("fetchAndTransform", FETCH_AND_TRANSFORM)
                    .input((results, ctx) -> Map.<String, Object>of())
                .build();

        // Register operator under its operatorRef for LoopOperator's internal engine
        String ref = subGraph.nodes().get("fetchAndTransform").operatorRef();
        registry.register(ref, FETCH_AND_TRANSFORM);


        var loopOp = LoopOperator.withDurability(
                subGraph,
                registry,
                100,
                Duration.ofMillis(10),

                outputs -> {
                    var combined = (Map<String, Object>) outputs.get("fetchAndTransform");
                    var fetchOutput = (CursorPaginationExample.PageResponse) combined.get("fetchPage");
                    return !fetchOutput.hasMore();
                },

                outputs -> {
                    var combined = (Map<String, Object>) outputs.get("fetchAndTransform");
                    var fetchOutput = (CursorPaginationExample.PageResponse) combined.get("fetchPage");
                    var transformOutput = (CursorPaginationExample.TransformResult) combined.get("transformPage");
                    return Map.of(
                            "cursor", fetchOutput.nextCursor(),
                            "totalRecords", transformOutput.runningTotal());
                },
                null,
                List.of()
        );


        Graph mainGraph = Graph.builder("cursorPagination")
                .node("initPagination", CursorPaginationExample.INIT_PAGINATION)
                    .input((results, ctx) -> new CursorPaginationExample.PaginationConfig(
                            ctx.get("endpoint", String.class),
                            ctx.get("pageSize", Integer.class)))
                .node("fetchAllPages", loopOp)
                    .dependsOn("initPagination")
                    .input((results, ctx) -> {
                        var init = results.get("initPagination", CursorPaginationExample.PaginationInit.class);
                        return Map.of(
                                "cursor", init.initialCursor(),
                                "totalRecords", 0);
                    })
                .node("finalizeData", CursorPaginationExample.FINALIZE_DATA)
                    .dependsOn("fetchAllPages")
                    .input((results, ctx) -> {
                        var loopOutput = (Map<String, Object>) results.getRaw("fetchAllPages");
                        var combined = (Map<String, Object>) loopOutput.get("fetchAndTransform");
                        var transformResult = (CursorPaginationExample.TransformResult) combined.get("transformPage");
                        var fetchResult = (CursorPaginationExample.PageResponse) combined.get("fetchPage");
                        return new CursorPaginationExample.FinalizeInput(
                                transformResult.runningTotal(),
                                fetchResult.nextCursor());
                    })
                .build();

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of(
                "endpoint", "https://api.example.com/records",
                "pageSize", 3
        ));

        return engine.executeWithOperators(mainGraph, ctx, Map.of(
                "initPagination", CursorPaginationExample.INIT_PAGINATION,
                "fetchAllPages", loopOp,
                "finalizeData", CursorPaginationExample.FINALIZE_DATA
        ));
    }

    // ─── DSL helpers ────────────────────────────────────────────────────

    private GraphResult executeDsl() {
        var registry = new DefaultOperatorRegistry();
        registry.register("PaginationInitOperator", CursorPaginationDslExample.INIT_PAGINATION);
        registry.register("FetchAndTransformOperator", DSL_FETCH_AND_TRANSFORM);
        registry.register("DataFinalizerOperator", CursorPaginationDslExample.FINALIZE_DATA);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph cursorPagination {
                  node initPagination : PaginationInitOperator {
                    input {
                      endpoint = ctx.endpoint
                      pageSize = ctx.pageSize
                    }
                  }
                  loop fetchAllPages {
                    max_iterations = 100
                    delay = 10ms
                    depends_on = [initPagination]
                    node fetchAndTransform : FetchAndTransformOperator {
                      input {
                        endpoint     = ctx.endpoint
                        pageSize     = ctx.pageSize
                        cursor       = carry.cursor
                        currentTotal = carry.totalRecords
                      }
                    }
                    carry {
                      cursor:       fetchAndTransform.output.nextCursor
                      totalRecords: fetchAndTransform.output.runningTotal
                    }
                    until fetchAndTransform.output.hasMore == false
                  }
                  node finalizeData : DataFinalizerOperator {
                    depends_on = [fetchAllPages]
                    input {
                      totalRecords = fetchAllPages.output.fetchAndTransform.runningTotal
                      lastCursor   = fetchAllPages.output.fetchAndTransform.nextCursor
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
                "endpoint", "https://api.example.com/records",
                "pageSize", 3
        ));

        return engine.execute(graph, ctx);
    }

    // ═══ Java API Tests ═════════════════════════════════════════════════

    @Test
    void testJavaApi_graphExecutesSuccessfully() {
        GraphResult result = executeJavaApi();
        assertTrue(result.isSuccess(), "Graph should execute successfully: " + result.errors());
    }

    @Test
    void testJavaApi_allNodesCompleted() {
        GraphResult result = executeJavaApi();
        assertEquals(NodeStatus.COMPLETED, result.getStatus("initPagination"), "initPagination should be COMPLETED");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fetchAllPages"), "fetchAllPages should be COMPLETED");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("finalizeData"), "finalizeData should be COMPLETED");
    }

    @Test
    void testJavaApi_loopPaginates3Pages() {
        GraphResult result = executeJavaApi();
        var summary = result.getOutput("finalizeData", CursorPaginationExample.PaginationSummary.class);
        assertNotNull(summary, "finalizeData output should not be null");
        assertEquals(9, summary.totalRecords(), "Should have 9 total records (3 pages x 3 records)");
    }

    @Test
    void testJavaApi_finalSummaryIsCorrect() {
        GraphResult result = executeJavaApi();
        var summary = result.getOutput("finalizeData", CursorPaginationExample.PaginationSummary.class);
        assertNotNull(summary);
        assertEquals("COMPLETED", summary.status(), "Status should be COMPLETED");
        assertEquals("", summary.lastCursor(), "Last cursor should be empty (final page)");
        assertEquals(9, summary.totalRecords());
    }

    // ═══ DSL Tests ══════════════════════════════════════════════════════

    @Test
    void testDsl_graphExecutesSuccessfully() {
        GraphResult result = executeDsl();
        assertTrue(result.isSuccess(), "DSL graph should execute successfully: " + result.errors());
    }

    @Test
    void testDsl_allNodesCompleted() {
        GraphResult result = executeDsl();
        assertEquals(NodeStatus.COMPLETED, result.getStatus("initPagination"), "initPagination should be COMPLETED");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fetchAllPages"), "fetchAllPages should be COMPLETED");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("finalizeData"), "finalizeData should be COMPLETED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDsl_loopPaginates3Pages() {
        GraphResult result = executeDsl();
        var finalizeOutput = (Map<String, Object>) result.results().getRaw("finalizeData");
        assertNotNull(finalizeOutput, "finalizeData output should not be null");
        int totalRecords = ((Number) finalizeOutput.get("totalRecords")).intValue();
        assertEquals(9, totalRecords, "Should have 9 total records (3 pages x 3 records)");
    }
}
