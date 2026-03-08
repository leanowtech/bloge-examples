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

public class CursorPaginationReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("PaginationInitOperator", CursorPaginationDslExample.INIT_PAGINATION);
        registry.register("PageFetcherOperator", CursorPaginationDslExample.FETCH_PAGE);
        registry.register("PageTransformerOperator", CursorPaginationDslExample.TRANSFORM_PAGE);
        registry.register("DataFinalizerOperator", CursorPaginationDslExample.FINALIZE_DATA);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String endpoint = ReplHelper.promptString(scanner, "endpoint", "https://api.example.com/records");
        int pageSize = ReplHelper.promptInt(scanner, "pageSize", 3);
        return Map.of(
                "endpoint", endpoint,
                "pageSize", pageSize
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
                ReplHelper.header("Cursor Pagination REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
