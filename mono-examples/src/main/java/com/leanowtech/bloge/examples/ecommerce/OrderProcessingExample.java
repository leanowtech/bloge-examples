package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorLayer;
import com.leanowtech.bloge.core.operator.OperatorMeta;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.ExecutionListener;
import com.leanowtech.bloge.core.spi.event.NodeEvent.NodeCompleteEvent;
import com.leanowtech.bloge.core.spi.event.NodeEvent.NodeFailedEvent;
import com.leanowtech.bloge.core.spi.event.NodeEvent.NodeSkippedEvent;
import com.leanowtech.bloge.core.spi.event.NodeEvent.NodeStartEvent;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Canonical ecommerce order-processing example built with typed Java operators.
 *
 * <p>This example demonstrates parallel enrichment (user + product data), pricing,
 * credit evaluation, and branch-based continuation into order creation or rejection.
 *
 * <p>Graph layout:
 * <pre>
 * fetchUser + fetchProducts
 *   -> calcPrice
 *   -> checkCredit
 *      -> (approved=true)  createOrder
 *      -> (approved=false) rejectOrder
 * </pre>
 *
 * <p>Run {@link #main(String[])} to execute the graph with sample checkout input.
 */
public class OrderProcessingExample {

    public record UserQuery(String userId) {}
    public record User(String id, String name, String email, int creditScore) {}

    public record ProductQuery(List<String> productIds) {}
    public record Product(String id, String name, double price) {}
    public record ProductList(List<Product> items) {}

    public record PriceInput(User user, ProductList products) {}
    public record PriceResult(double subtotal, double tax, double total) {}

    public record CreditRequest(String userId, double amount) {}
    public record CreditResult(boolean approved, String reason) {}

    public record OrderInput(User user, PriceResult price) {}
    public record Order(String orderId, String userId, double total, String status) {}

    public record RejectionInput(String userId, String reason) {}
    public record Rejection(String userId, String reason) {}

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"ecommerce", "user"},
            description = "Fetches user profile from the user service", owner = "ecommerce-team")
    static final Operator<UserQuery, User> FETCH_USER = (input, ctx) -> {
        Thread.sleep(50);
        return new User(input.userId(), "Alice", "alice@example.com", 750);
    };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"ecommerce", "catalog"},
            description = "Fetches product details from the catalog service", owner = "ecommerce-team")
    static final Operator<ProductQuery, ProductList> FETCH_PRODUCTS = (input, ctx) -> {
        Thread.sleep(80);
        var products = input.productIds().stream()
                .map(id -> new Product(id, "Product-" + id, 29.99))
                .toList();
        return new ProductList(products);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ecommerce", "pricing"},
            description = "Calculates subtotal, tax, and total price", owner = "ecommerce-team")
    static final Operator<PriceInput, PriceResult> CALC_PRICE = (input, ctx) -> {
        double subtotal = input.products().items().stream()
                .mapToDouble(Product::price)
                .sum();
        double tax = subtotal * 0.08;
        return new PriceResult(subtotal, tax, subtotal + tax);
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"ecommerce", "credit"},
            description = "Checks user credit eligibility via external credit service", owner = "payments-team")
    static final Operator<CreditRequest, CreditResult> CHECK_CREDIT = (input, ctx) -> {
        Thread.sleep(30);
        return new CreditResult(input.amount() < 500, "Amount exceeds limit");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ecommerce", "order"},
            description = "Creates and persists a confirmed order", owner = "ecommerce-team")
    static final Operator<OrderInput, Order> CREATE_ORDER = (input, ctx) -> {
        return new Order("ORD-12345", input.user().id(), input.price().total(), "CONFIRMED");
    };

    static final Operator<RejectionInput, Rejection> REJECT_ORDER = (input, ctx) -> {
        return new Rejection(input.userId(), input.reason());
    };

    /**
     * Builds the order-processing graph with reliability controls and branch routing.
     *
     * @return configured graph instance
     */
    public static Graph buildGraph() {
        var builder = Graph.builder("orderProcess")
                .node("fetchUser", FETCH_USER)
                    .input((results, ctx) -> new UserQuery(ctx.get("userId", String.class)))
                    .timeout(Duration.ofSeconds(3))
                    .retry(2, Duration.ofMillis(200), BackoffStrategy.EXPONENTIAL)
                .node("fetchProducts", FETCH_PRODUCTS)
                    .input((results, ctx) -> new ProductQuery(ctx.get("productIds", List.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("calcPrice", CALC_PRICE)
                    .dependsOn("fetchUser", "fetchProducts")
                    .input((results, ctx) -> new PriceInput(
                            results.get("fetchUser", User.class),
                            results.get("fetchProducts", ProductList.class)))
                .node("checkCredit", CHECK_CREDIT)
                    .dependsOn("fetchUser", "calcPrice")
                    .input((results, ctx) -> new CreditRequest(
                            results.get("fetchUser", User.class).id(),
                            results.get("calcPrice", PriceResult.class).total()))
                    .retry(3, Duration.ofMillis(100), BackoffStrategy.JITTER)
                    .fallback(ex -> new CreditResult(false, "credit service unavailable"))
                .node("createOrder", CREATE_ORDER)
                    .dependsOn("calcPrice")
                    .input((results, ctx) -> new OrderInput(
                            results.get("fetchUser", User.class),
                            results.get("calcPrice", PriceResult.class)))
                .node("rejectOrder", REJECT_ORDER)
                    .dependsOn("checkCredit")
                    .input((results, ctx) -> new RejectionInput(
                            results.get("fetchUser", User.class).id(),
                            results.get("checkCredit", CreditResult.class).reason()))
                .branch("checkCredit")
                    .on("approved")
                    .when(val -> Boolean.TRUE.equals(val), "createOrder")
                    .otherwise("rejectOrder");

        return builder.build();
    }

    @SuppressWarnings("preview")
    /**
     * Executes the order-processing graph using sample checkout context values.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();

        Graph graph = buildGraph();

        var ctx = new GraphContext(Map.of(
                "userId", "user-42",
                "productIds", List.of("prod-1", "prod-2", "prod-3")
        ));

        GraphResult result = engine.executeWithOperators(graph, ctx, Map.of(
                "fetchUser", FETCH_USER,
                "fetchProducts", FETCH_PRODUCTS,
                "calcPrice", CALC_PRICE,
                "checkCredit", CHECK_CREDIT,
                "createOrder", CREATE_ORDER,
                "rejectOrder", REJECT_ORDER
        ));

        System.out.println("\n═══ Order Processing Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-15s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("createOrder") == NodeStatus.COMPLETED) {
            Order order = result.getOutput("createOrder", Order.class);
            System.out.println("Order created: " + order);
        } else if (result.getStatus("rejectOrder") == NodeStatus.COMPLETED) {
            Rejection rejection = result.getOutput("rejectOrder", Rejection.class);
            System.out.println("Order rejected: " + rejection);
        }
    }

    public static class LoggingListener implements ExecutionListener {
        @Override
        public void onGraphStart(String graphName, GraphContext ctx) {
            System.out.println("[START] Graph: " + graphName);
        }

        @Override
        public void onNodeStart(NodeStartEvent event) {
            System.out.printf("  [→] %s starting%n", event.nodeId());
        }

        @Override
        public void onNodeComplete(NodeCompleteEvent event) {
            System.out.printf("  [✓] %s completed in %dms%n", event.nodeId(), event.elapsed().toMillis());
        }

        @Override
        public void onNodeFailed(NodeFailedEvent event) {
            System.out.printf("  [✗] %s failed (attempt %d): %s%n", event.nodeId(), event.retryAttempt(), event.error().getMessage());
        }

        @Override
        public void onNodeSkipped(NodeSkippedEvent event) {
            System.out.printf("  [⊘] %s skipped: %s%n", event.nodeId(), event.reason());
        }

        @Override
        public void onGraphComplete(String graphName, GraphResult result) {
            System.out.printf("[END] Graph: %s (%dms, %s)%n",
                    graphName, result.elapsed().toMillis(),
                    result.isSuccess() ? "SUCCESS" : "FAILED");
        }
    }
}
