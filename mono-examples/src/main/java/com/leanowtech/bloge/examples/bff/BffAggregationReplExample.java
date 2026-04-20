package com.leanowtech.bloge.examples.bff;

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

public class BffAggregationReplExample {

    private static final String DSL = """

                graph bffDashboard {

                  /// Fetches the user's profile; retried once with 100 ms backoff on failure
                  node fetchProfile : FetchProfileOperator {
                    input {
                      userId = ctx.userId
                    }
                    timeout = 2s
                    retry = { attempts: 1, backoff: 100ms }
                  }

                  /// Fetches recent order history; falls back to empty list on timeout or failure
                  node fetchOrders : FetchOrdersOperator {
                    input {
                      userId = ctx.userId
                    }
                    timeout = 3s
                    fallback = { recentOrderIds: [], totalOrders: 0 }
                  }

                  /// Fetches personalised recommendations; falls back to empty list
                  node fetchRecommendations : FetchRecommendationsOperator {
                    input {
                      userId = ctx.userId
                    }
                    timeout = 2s
                    fallback = { productIds: [] }
                  }

                  /// Fetches unread notifications; 2× exponential retry then fallback to zero
                  node fetchNotifications : FetchNotificationsOperator {
                    input {
                      userId = ctx.userId
                    }
                    timeout = 2s
                    retry = { attempts: 2, backoff: 50ms, strategy: exponential }
                    fallback = { unread: 0, messages: [] }
                  }

                  /// Fetches loyalty points and tier; falls back to zero / "Unknown"
                  node fetchLoyalty : FetchLoyaltyOperator {
                    input {
                      userId = ctx.userId
                    }
                    timeout = 1s
                    fallback = { points: 0, tier: "Unknown" }
                  }

                  /// Merges all five fetch outputs into a single dashboard response payload
                  node aggregate : AggregateOperator {
                    depends_on = [fetchProfile, fetchOrders, fetchRecommendations, fetchNotifications, fetchLoyalty]
                    input {
                      profile         = fetchProfile.output
                      orders          = fetchOrders.output
                      recommendations = fetchRecommendations.output
                      notifications   = fetchNotifications.output
                      loyalty         = fetchLoyalty.output
                    }
                  }
                }
                
            """;

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("FetchProfileOperator", BffAggregationDslExample.FETCH_PROFILE);
        registry.register("FetchOrdersOperator", BffAggregationDslExample.FETCH_ORDERS);
        registry.register("FetchRecommendationsOperator", BffAggregationDslExample.FETCH_RECOMMENDATIONS);
        registry.register("FetchNotificationsOperator", BffAggregationDslExample.FETCH_NOTIFICATIONS);
        registry.register("FetchLoyaltyOperator", BffAggregationDslExample.FETCH_LOYALTY);
        registry.register("AggregateOperator", BffAggregationDslExample.AGGREGATE);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String userId = ReplHelper.promptString(scanner, "userId", "user-42");
        return Map.of("userId", userId);
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
          .requestResponseDefaults()
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();

        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            boolean runAgain;
            do {
                ReplHelper.header("BFF Aggregation REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
