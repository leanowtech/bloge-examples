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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DSL loop example for cursor-based pagination.
 *
 * <p>This example demonstrates a DSL-defined {@code loop} with carry-state fields
 * to fetch paged data until no further cursor is available.
 *
 * <p>Graph layout:
 * <pre>
 * initPagination
 *   -> loop fetchAllPages: fetchPage -> transformPage (carry cursor, totalRecords)
 *   -> finalizeData
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings("preview")
public class CursorPaginationDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> INIT_PAGINATION = (input, ctx) -> {
        Thread.sleep(10);
        String endpoint = (String) input.get("endpoint");
        int pageSize = ((Number) input.get("pageSize")).intValue();
        return Map.of("endpoint", endpoint, "pageSize", pageSize, "initialCursor", "");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_PAGE = (input, ctx) -> {
        Thread.sleep(15);
        String cursor = input.get("cursor") != null ? (String) input.get("cursor") : "";
        int pageSize = ((Number) input.get("pageSize")).intValue();
        if (cursor.isEmpty()) {
            return Map.of(
                    "records", List.of(
                            Map.of("id", "rec-1", "value", "alpha"),
                            Map.of("id", "rec-2", "value", "beta"),
                            Map.of("id", "rec-3", "value", "gamma")),
                    "nextCursor", "page-2",
                    "hasMore", true);
        } else if ("page-2".equals(cursor)) {
            return Map.of(
                    "records", List.of(
                            Map.of("id", "rec-4", "value", "delta"),
                            Map.of("id", "rec-5", "value", "epsilon"),
                            Map.of("id", "rec-6", "value", "zeta")),
                    "nextCursor", "page-3",
                    "hasMore", true);
        } else {
            return Map.of(
                    "records", List.of(
                            Map.of("id", "rec-7", "value", "eta"),
                            Map.of("id", "rec-8", "value", "theta"),
                            Map.of("id", "rec-9", "value", "iota")),
                    "nextCursor", "",
                    "hasMore", false);
        }
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> TRANSFORM_PAGE = (input, ctx) -> {
        Thread.sleep(5);
        var records = (List<Map<String, Object>>) input.get("records");
        int currentTotal = input.get("currentTotal") != null
                ? ((Number) input.get("currentTotal")).intValue() : 0;
        var transformed = new ArrayList<Map<String, Object>>();
        for (var record : records) {
            var enriched = new HashMap<>(record);
            enriched.put("processed", true);
            transformed.add(enriched);
        }
        int runningTotal = currentTotal + transformed.size();
        return Map.of("transformed", List.copyOf(transformed), "runningTotal", runningTotal);
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> FINALIZE_DATA = (input, ctx) -> {
        Thread.sleep(5);
        int totalRecords = input.get("totalRecords") != null
                ? ((Number) input.get("totalRecords")).intValue() : 0;
        String lastCursor = input.get("lastCursor") != null
                ? (String) input.get("lastCursor") : "";
        return Map.of("totalRecords", totalRecords, "lastCursor", lastCursor, "status", "COMPLETED");
    };

    public static void main(String[] args) {
        // ── Operator Registrations ─────────────────────────────────────────────
        var registry = new DefaultOperatorRegistry();
        // PaginationInitOperator: reads ctx.endpoint, ctx.pageSize → returns {endpoint, pageSize, initialCursor}
        registry.register("PaginationInitOperator", INIT_PAGINATION);
        // PageFetcherOperator: reads cursor, endpoint, pageSize → returns {records, nextCursor, hasMore}
        registry.register("PageFetcherOperator", FETCH_PAGE);
        // PageTransformerOperator: reads records, carry.totalRecords → returns {transformed, runningTotal}
        registry.register("PageTransformerOperator", TRANSFORM_PAGE);
        // DataFinalizerOperator: reads totalRecords, lastCursor → returns {totalRecords, lastCursor, status}
        registry.register("DataFinalizerOperator", FINALIZE_DATA);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph cursorPagination {

                  /// Initializes the pagination context
                  node initPagination : PaginationInitOperator {
                    input {
                      endpoint = ctx.endpoint
                      pageSize = ctx.pageSize
                    }
                  }

                  /// loop with carry: passes cursor and running total between iterations
                  loop fetchAllPages {
                    max_iterations = 100
                    delay = 500ms
                    depends_on = [initPagination]
                    node fetchPage : PageFetcherOperator {
                      input {
                        endpoint = ctx.endpoint
                        pageSize = ctx.pageSize
                        cursor   = carry.cursor
                      }
                    }
                    node transformPage : PageTransformerOperator {
                      depends_on = [fetchPage]
                      input {
                        records      = fetchPage.output.records
                        currentTotal = carry.totalRecords
                      }
                    }
                    /// carry: forwards nextCursor and runningTotal to the next iteration
                    carry {
                      cursor:       fetchPage.output.nextCursor
                      totalRecords: transformPage.output.runningTotal
                    }
                    /// until: loop exits when the last page has been fetched (hasMore == false)
                    until fetchPage.output.hasMore == false
                  }

                  /// Produces the final aggregated dataset
                  node finalizeData : DataFinalizerOperator {
                    depends_on = [fetchAllPages]
                    input {
                      totalRecords = fetchAllPages.output.transformPage.runningTotal
                      lastCursor   = fetchAllPages.output.fetchPage.nextCursor
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
                "endpoint", "https://api.example.com/records",
                "pageSize", 3
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Cursor Pagination Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("initPagination") == NodeStatus.COMPLETED) {
            System.out.println("Pagination initialized: " + result.results().getRaw("initPagination"));
        }

        if (result.getStatus("fetchAllPages") == NodeStatus.COMPLETED) {
            System.out.println("Loop output: " + result.results().getRaw("fetchAllPages"));
        }

        if (result.getStatus("finalizeData") == NodeStatus.COMPLETED) {
            System.out.println("Final result: " + result.results().getRaw("finalizeData"));
        }
    }
}
