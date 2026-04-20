package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.engine.operators.LoopOperator;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorLayer;
import com.leanowtech.bloge.core.operator.OperatorMeta;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates cursor-based pagination using LoopOperator with carry state.
 * <p>
 * Main graph: initPagination → fetchAllPages (loop) → finalizeData
 * <p>
 * Loop sub-graph: fetchPage → transformPage
 * <p>
 * The loop carries two fields between iterations:
 * <ul>
 *   <li>{@code cursor} — the page cursor from the previous fetch</li>
 *   <li>{@code totalRecords} — accumulated record count across all pages</li>
 * </ul>
 * The loop terminates when {@code fetchPage.output.hasMore == false}.
 */
@SuppressWarnings("preview")
public class CursorPaginationExample {

    // --- Records ---

    public record PaginationConfig(String endpoint, int pageSize) {}
    public record PaginationInit(String endpoint, int pageSize, String initialCursor) {}
    public record PageRequest(String endpoint, int pageSize, String cursor) {}
    public record PageResponse(List<Map<String, Object>> records, String nextCursor, boolean hasMore) {}
    public record TransformInput(List<Map<String, Object>> records, int currentTotal) {}
    public record TransformResult(List<Map<String, Object>> transformed, int runningTotal) {}
    public record FinalizeInput(int totalRecords, String lastCursor) {}
    public record PaginationSummary(int totalRecords, String lastCursor, String status) {}

    // --- Operators ---

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"pagination", "init"},
            description = "Initializes pagination context with endpoint and page size", owner = "platform-team")
    static final Operator<PaginationConfig, PaginationInit> INIT_PAGINATION = (input, ctx) -> {
        Thread.sleep(10);
        return new PaginationInit(input.endpoint(), input.pageSize(), "");
    };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"pagination", "fetch"},
            description = "Fetches a page of records from the API using a cursor", owner = "platform-team")
    static final Operator<PageRequest, PageResponse> FETCH_PAGE = (input, ctx) -> {
        Thread.sleep(15);
        String cursor = input.cursor();
        if (cursor == null || cursor.isEmpty()) {
            // Page 1
            return new PageResponse(
                    List.of(
                            Map.of("id", "rec-1", "value", "alpha"),
                            Map.of("id", "rec-2", "value", "beta"),
                            Map.of("id", "rec-3", "value", "gamma")),
                    "page-2",
                    true);
        } else if ("page-2".equals(cursor)) {
            // Page 2
            return new PageResponse(
                    List.of(
                            Map.of("id", "rec-4", "value", "delta"),
                            Map.of("id", "rec-5", "value", "epsilon"),
                            Map.of("id", "rec-6", "value", "zeta")),
                    "page-3",
                    true);
        } else {
            // Page 3 (final)
            return new PageResponse(
                    List.of(
                            Map.of("id", "rec-7", "value", "eta"),
                            Map.of("id", "rec-8", "value", "theta"),
                            Map.of("id", "rec-9", "value", "iota")),
                    "",
                    false);
        }
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"pagination", "transform"},
            description = "Transforms page records and updates running total", owner = "platform-team")
    static final Operator<TransformInput, TransformResult> TRANSFORM_PAGE = (input, ctx) -> {
        Thread.sleep(5);
        var transformed = new ArrayList<Map<String, Object>>();
        for (var record : input.records()) {
            var enriched = new HashMap<>(record);
            enriched.put("processed", true);
            transformed.add(enriched);
        }
        int runningTotal = input.currentTotal() + transformed.size();
        return new TransformResult(List.copyOf(transformed), runningTotal);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"pagination", "finalize"},
            description = "Produces the final pagination summary", owner = "platform-team")
    static final Operator<FinalizeInput, PaginationSummary> FINALIZE_DATA = (input, ctx) -> {
        Thread.sleep(5);
        return new PaginationSummary(input.totalRecords(), input.lastCursor(), "COMPLETED");
    };

    // --- Sub-graph construction ---

    @SuppressWarnings("unchecked")
    public static Graph buildLoopSubGraph() {
        return Graph.builder("fetchAllPages__subgraph__")
                .node("fetchPage", FETCH_PAGE)
                    .input((results, ctx) -> {
                        String cursor = "";
                        var carry = (Map<String, Object>) ctx.get("__carry__", Map.class);
                        if (carry != null && carry.containsKey("cursor")) {
                            cursor = (String) carry.get("cursor");
                        }
                        return new PageRequest(
                                ctx.get("endpoint", String.class),
                                ctx.get("pageSize", Integer.class),
                                cursor);
                    })
                .node("transformPage", TRANSFORM_PAGE)
                    .dependsOn("fetchPage")
                    .input((results, ctx) -> {
                        var page = results.get("fetchPage", PageResponse.class);
                        var carry = (Map<String, Object>) ctx.get("__carry__", Map.class);
                        int currentTotal = carry != null && carry.containsKey("totalRecords")
                                ? ((Number) carry.get("totalRecords")).intValue() : 0;
                        return new TransformInput(page.records(), currentTotal);
                    })
                .build();
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Register sub-graph operators
        registry.register("fetchPage", FETCH_PAGE);
        registry.register("transformPage", TRANSFORM_PAGE);

        var listener = new LoggingListener();

        // Build loop sub-graph
        Graph subGraph = buildLoopSubGraph();

        // Create LoopOperator with carry
        var loopOp = LoopOperator.withDurability(
                subGraph,
                registry,
                100,  // maxIterations
                Duration.ofMillis(10),  // small delay for example
                // untilCondition: stop when hasMore == false
                outputs -> {
                    var fetchOutput = (PageResponse) outputs.get("fetchPage");
                    return !fetchOutput.hasMore();
                },
                // carryMapper: pass cursor and running total to next iteration
                outputs -> {
                    var fetchOutput = (PageResponse) outputs.get("fetchPage");
                    var transformOutput = (TransformResult) outputs.get("transformPage");
                    return Map.of(
                            "cursor", fetchOutput.nextCursor(),
                            "totalRecords", transformOutput.runningTotal());
                },
                null,
                List.of(listener)
        );

        // Build main graph: initPagination → fetchAllPages (loop) → finalizeData
        Graph mainGraph = Graph.builder("cursorPagination")
                .node("initPagination", INIT_PAGINATION)
                    .input((results, ctx) -> new PaginationConfig(
                            ctx.get("endpoint", String.class),
                            ctx.get("pageSize", Integer.class)))
                .node("fetchAllPages", loopOp)
                    .dependsOn("initPagination")
                    .input((results, ctx) -> {
                        var init = results.get("initPagination", PaginationInit.class);
                        return Map.of(
                                "cursor", init.initialCursor(),
                                "totalRecords", 0);
                    })
                .node("finalizeData", FINALIZE_DATA)
                    .dependsOn("fetchAllPages")
                    .input((results, ctx) -> {
                        var loopOutput = (Map<String, Object>) results.getRaw("fetchAllPages");
                        var transformResult = (TransformResult) loopOutput.get("transformPage");
                        var fetchResult = (PageResponse) loopOutput.get("fetchPage");
                        return new FinalizeInput(
                                transformResult.runningTotal(),
                                fetchResult.nextCursor());
                    })
                .build();

        // Execute
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(listener))
                .build();
        var ctx = new GraphContext(Map.of(
                "endpoint", "https://api.example.com/records",
                "pageSize", 3
        ));

        GraphResult result = engine.executeWithOperators(mainGraph, ctx, Map.of(
                "initPagination", INIT_PAGINATION,
                "fetchAllPages", loopOp,
                "finalizeData", FINALIZE_DATA
        ));

        // Print results
        System.out.println("\n═══ Cursor Pagination Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("initPagination") == NodeStatus.COMPLETED) {
            PaginationInit init = result.getOutput("initPagination", PaginationInit.class);
            System.out.println("Pagination initialized: endpoint=" + init.endpoint()
                    + ", pageSize=" + init.pageSize());
        }

        if (result.getStatus("fetchAllPages") == NodeStatus.COMPLETED) {
            var loopOutput = (Map<String, Object>) result.results().getRaw("fetchAllPages");
            System.out.println("Loop output: " + loopOutput);
        }

        if (result.getStatus("finalizeData") == NodeStatus.COMPLETED) {
            PaginationSummary summary = result.getOutput("finalizeData", PaginationSummary.class);
            System.out.println("Final summary: totalRecords=" + summary.totalRecords()
                    + ", lastCursor=" + summary.lastCursor()
                    + ", status=" + summary.status());
        }
    }
}
