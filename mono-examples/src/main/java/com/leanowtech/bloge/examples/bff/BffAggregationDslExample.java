package com.leanowtech.bloge.examples.bff;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.List;
import java.util.Map;

/**
 * DSL version of the BFF dashboard aggregation example.
 *
 * <p>Compiles the graph from an inline DSL string, registers six {@code Map<String,Object>}
 * operators by the PascalCase names declared in that DSL, then executes the graph and prints
 * the assembled dashboard payload.
 *
 * <p>Graph layout (all fetch nodes run concurrently):
 * <pre>
 *   ctx.userId ──┬──→ fetchProfile        ─┐
 *                ├──→ fetchOrders         ─┤
 *                ├──→ fetchRecommendations─┤──→ aggregate ──→ BffResponse
 *                ├──→ fetchNotifications  ─┤
 *                └──→ fetchLoyalty        ─┘
 * </pre>
 *
 * <p>Resilience patterns declared in the DSL string:
 * <ul>
 *   <li>{@code fetchProfile}         – {@code retry = 1}, 100 ms backoff</li>
 *   <li>{@code fetchOrders}          – {@code fallback} to empty order history</li>
 *   <li>{@code fetchRecommendations} – {@code fallback} to empty list</li>
 *   <li>{@code fetchNotifications}   – 2× exponential retry + {@code fallback}</li>
 *   <li>{@code fetchLoyalty}         – {@code fallback} to zero / "Unknown"</li>
 * </ul>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings("preview")
public class BffAggregationDslExample {

    // ── Operators (Map-based for DSL interop) ─────────────────────────────────

    /** FetchProfileOperator — reads userId from input, returns {id, name, avatar}. */
    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_PROFILE = (input, ctx) -> {
        Thread.sleep(60);
        String userId = (String) input.get("userId");
        return Map.of("id", userId, "name", "Alice", "avatar", "https://cdn.example.com/alice.jpg");
    };

    /** FetchOrdersOperator — reads userId from input, returns {recentOrderIds, totalOrders}. */
    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_ORDERS = (input, ctx) -> {
        Thread.sleep(120);
        return Map.of("recentOrderIds", List.of("ORD-001", "ORD-002", "ORD-003"), "totalOrders", 42);
    };

    /** FetchRecommendationsOperator — reads userId from input, returns {productIds}. */
    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_RECOMMENDATIONS = (input, ctx) -> {
        Thread.sleep(200);
        return Map.of("productIds", List.of("prod-A", "prod-B", "prod-C", "prod-D"));
    };

    /** FetchNotificationsOperator — reads userId from input, returns {unread, messages}. */
    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_NOTIFICATIONS = (input, ctx) -> {
        Thread.sleep(80);
        return Map.of("unread", 3,
                "messages", List.of("Your order shipped!", "Flash sale today", "New rewards available"));
    };

    /** FetchLoyaltyOperator — reads userId from input, returns {points, tier}. */
    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_LOYALTY = (input, ctx) -> {
        Thread.sleep(40);
        return Map.of("points", 12500, "tier", "Gold");
    };

    /**
     * AggregateOperator — receives {profile, orders, recommendations, notifications, loyalty}
     * assembled by the DSL input block; returns the map unchanged as the dashboard payload.
     */
    static final Operator<Map<String, Object>, Map<String, Object>> AGGREGATE =
            (input, ctx) -> input;

    // ── Main ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Operator names match the PascalCase refs in the DSL below
        registry.register("FetchProfileOperator",         FETCH_PROFILE);
        registry.register("FetchOrdersOperator",          FETCH_ORDERS);
        registry.register("FetchRecommendationsOperator", FETCH_RECOMMENDATIONS);
        registry.register("FetchNotificationsOperator",   FETCH_NOTIFICATIONS);
        registry.register("FetchLoyaltyOperator",         FETCH_LOYALTY);
        registry.register("AggregateOperator",            AGGREGATE);

        String dsl = """
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

        // compile DSL; operators resolved by PascalCase name
        Graph graph = new GraphLoader(registry).load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
          .requestResponseDefaults()
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx    = new GraphContext(Map.of("userId", "user-42"));

        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL BFF Dashboard Result ═══");
        System.out.println("Success : " + result.isSuccess());
        System.out.println("Elapsed : " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        var dashboard = (Map<String, Object>) result.results().getRaw("aggregate");
        System.out.println("Profile         : " + dashboard.get("profile"));
        System.out.println("Orders          : " + dashboard.get("orders"));
        System.out.println("Recommendations : " + dashboard.get("recommendations"));
        System.out.println("Notifications   : " + dashboard.get("notifications"));
        System.out.println("Loyalty         : " + dashboard.get("loyalty"));
    }
}
