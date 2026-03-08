package com.leanowtech.bloge.examples.bff;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Backend-for-Frontend (BFF) data aggregation example.
 *
 * <p>This example shows how a single graph node can fan out to <em>five independent
 * remote calls in parallel</em> and then aggregate all results into one composite
 * response object – a classic BFF pattern.
 *
 * <p>Graph layout (all leaf nodes run concurrently):
 * <pre>
 *   ctx.userId ──┬─→ fetchProfile        ─┐
 *                ├─→ fetchOrders         ─┤
 *                ├─→ fetchRecommendations─┤─→ aggregate → BffResponse
 *                ├─→ fetchNotifications  ─┤
 *                └─→ fetchLoyalty        ─┘
 * </pre>
 *
 * <p>Resilience patterns demonstrated:
 * <ul>
 *   <li>{@code fetchProfile}        – 1 retry on failure</li>
 *   <li>{@code fetchOrders}         – fallback to empty order history</li>
 *   <li>{@code fetchRecommendations}– fallback to empty list</li>
 *   <li>{@code fetchNotifications}  – exponential-backoff retry + fallback</li>
 *   <li>{@code fetchLoyalty}        – fallback to unknown tier</li>
 * </ul>
 *
 * <p>Run {@link #main(String[])} to execute the graph against a test user ID.
 */
@SuppressWarnings("preview")
public class BffAggregationExample {

    /** Lightweight user profile fetched from the user/identity service. */
    public record UserProfile(String id, String name, String avatar) {}
    /** Recent order summary fetched from the order service. */
    public record OrderHistory(List<String> recentOrderIds, int totalOrders) {}
    /** Personalised product recommendation IDs from the ML recommendation engine. */
    public record Recommendations(List<String> productIds) {}
    /** In-app notification payload from the notification service. */
    public record Notifications(int unread, List<String> messages) {}
    /** Loyalty programme status fetched from the rewards service. */
    public record LoyaltyPoints(int points, String tier) {}

    /**
     * The aggregated BFF response that is returned to the frontend in a single HTTP response.
     * All five fields are populated in parallel by independent operators.
     */
    public record BffResponse(
            UserProfile profile,
            OrderHistory orders,
            Recommendations recommendations,
            Notifications notifications,
            LoyaltyPoints loyalty
    ) {}

    /** Fetches the authenticated user's profile (name, avatar URL) from the user service. */
    static final Operator<String, UserProfile> FETCH_PROFILE = (userId, ctx) -> {
        Thread.sleep(60);
        return new UserProfile(userId, "Alice", "https://cdn.example.com/alice.jpg");
    };

    /** Retrieves the user's recent order IDs and total order count from the order service. */
    static final Operator<String, OrderHistory> FETCH_ORDERS = (userId, ctx) -> {
        Thread.sleep(120);
        return new OrderHistory(List.of("ORD-001", "ORD-002", "ORD-003"), 42);
    };

    /** Fetches personalised product recommendations from the ML recommendation engine. */
    static final Operator<String, Recommendations> FETCH_RECOMMENDATIONS = (userId, ctx) -> {
        Thread.sleep(200);
        return new Recommendations(List.of("prod-A", "prod-B", "prod-C", "prod-D"));
    };

    /** Fetches unread notification count and recent messages from the notification service. */
    static final Operator<String, Notifications> FETCH_NOTIFICATIONS = (userId, ctx) -> {
        Thread.sleep(80);
        return new Notifications(3, List.of("Your order shipped!", "Flash sale today", "New rewards available"));
    };

    /** Retrieves the user's loyalty point balance and membership tier from the rewards service. */
    static final Operator<String, LoyaltyPoints> FETCH_LOYALTY = (userId, ctx) -> {
        Thread.sleep(40);
        return new LoyaltyPoints(12500, "Gold");
    };

    /**
     * Builds the BFF aggregation graph.
     *
     * <p>All five fetch nodes are independent of each other and are therefore eligible for
     * parallel execution by the engine.  The final {@code aggregate} node depends on all five
     * and performs a simple record assembly – it does NOT contain any business logic.
     *
     * @return a fully configured, immutable {@link Graph} ready for execution
     */
    public static Graph buildGraph() {
        Operator<Object, BffResponse> aggregator = (input, ctx) -> (BffResponse) input;

        var builder = Graph.builder("bffDashboard")
                .node("fetchProfile", FETCH_PROFILE)
                    .input((results, graphCtx) -> graphCtx.get("userId", String.class))
                    .timeout(Duration.ofSeconds(2))
                    .retry(1, Duration.ofMillis(100))
                .node("fetchOrders", FETCH_ORDERS)
                    .input((results, graphCtx) -> graphCtx.get("userId", String.class))
                    .timeout(Duration.ofSeconds(3))
                    .fallback(ex -> new OrderHistory(List.of(), 0))
                .node("fetchRecommendations", FETCH_RECOMMENDATIONS)
                    .input((results, graphCtx) -> graphCtx.get("userId", String.class))
                    .timeout(Duration.ofSeconds(2))
                    .fallback(ex -> new Recommendations(List.of()))
                .node("fetchNotifications", FETCH_NOTIFICATIONS)
                    .input((results, graphCtx) -> graphCtx.get("userId", String.class))
                    .timeout(Duration.ofSeconds(2))
                    .retry(2, Duration.ofMillis(50), BackoffStrategy.EXPONENTIAL)
                    .fallback(ex -> new Notifications(0, List.of()))
                .node("fetchLoyalty", FETCH_LOYALTY)
                    .input((results, graphCtx) -> graphCtx.get("userId", String.class))
                    .timeout(Duration.ofSeconds(1))
                    .fallback(ex -> new LoyaltyPoints(0, "Unknown"))
                .node("aggregate", aggregator)
                    .dependsOn("fetchProfile", "fetchOrders", "fetchRecommendations",
                               "fetchNotifications", "fetchLoyalty")
                    .input((results, graphCtx) -> new BffResponse(
                            results.get("fetchProfile", UserProfile.class),
                            results.get("fetchOrders", OrderHistory.class),
                            results.get("fetchRecommendations", Recommendations.class),
                            results.get("fetchNotifications", Notifications.class),
                            results.get("fetchLoyalty", LoyaltyPoints.class)));

        return builder.build();
    }

    /**
     * Entry point: executes the BFF graph for a hardcoded test user ({@code user-42})
     * and prints each node's status plus the assembled {@link BffResponse}.
     *
     * @param args command-line arguments (not used)
     */
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        var engine = GraphEngine.builder()
                .registry(registry)
                .build();

        Graph graph = buildGraph();

        Operator<Object, BffResponse> aggregator = (input, ctx) -> (BffResponse) input;

        var ctx = new GraphContext(Map.of("userId", "user-42"));

        GraphResult result = engine.executeWithOperators(graph, ctx, Map.of(
                "fetchProfile", FETCH_PROFILE,
                "fetchOrders", FETCH_ORDERS,
                "fetchRecommendations", FETCH_RECOMMENDATIONS,
                "fetchNotifications", FETCH_NOTIFICATIONS,
                "fetchLoyalty", FETCH_LOYALTY,
                "aggregate", aggregator
        ));

        System.out.println("═══ BFF Dashboard Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        BffResponse response = result.getOutput("aggregate", BffResponse.class);
        System.out.println("Profile:         " + response.profile());
        System.out.println("Orders:          " + response.orders());
        System.out.println("Recommendations: " + response.recommendations());
        System.out.println("Notifications:   " + response.notifications());
        System.out.println("Loyalty:         " + response.loyalty());
    }
}
