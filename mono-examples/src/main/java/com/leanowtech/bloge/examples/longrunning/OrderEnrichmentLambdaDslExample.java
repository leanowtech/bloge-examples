package com.leanowtech.bloge.examples.longrunning;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates the <em>lambda / collection-ops DSL</em> feature using the
 * {@code order-enrichment-lambda.bloge} graph.
 *
 * <p>Lambda expressions can appear inside {@code input { }} blocks of any
 * operator node.  They are evaluated by the engine's data-access layer at
 * input-assembly time — no custom operator or foreach sub-graph is needed
 * for simple projection and aggregation.
 *
 * <h2>DSL lambda ops demonstrated</h2>
 * <ul>
 *   <li>{@code .map(o -> expr)}     — project each element</li>
 *   <li>{@code .filter(o -> pred)}  — keep matching elements</li>
 *   <li>{@code .reduce(init, (a,b) -> acc)} — fold to scalar</li>
 *   <li>{@code .sortBy(o -> key)}   — stable sort</li>
 *   <li>{@code .associate(k -> kE, v -> vE)} — build map from list</li>
 *   <li>Chained ops — {@code .filter(...).map(...)}</li>
 *   <li>Object-literal body — {@code x -> \{ id: x.id, total: x.price * x.qty \}}</li>
 * </ul>
 *
 * <h2>Graph layout</h2>
 * <pre>
 * fetchOrders ─┬─► enrichOrders ─► generateReport
 * fetchProducts┘
 * </pre>
 */
@SuppressWarnings({"preview", "unchecked"})
public class OrderEnrichmentLambdaDslExample {

    // ── Operators ─────────────────────────────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_ORDERS = (input, ctx) -> {
        Thread.sleep(40);
        String customerId = (String) input.get("customerId");
        System.out.println("  [fetchOrders] loading orders for customer=" + customerId);
        var orders = List.of(
                Map.<String, Object>of("orderId", "ORD-001", "price", 120.0, "quantity", 2),
                Map.<String, Object>of("orderId", "ORD-002", "price",  30.0, "quantity", 5),
                Map.<String, Object>of("orderId", "ORD-003", "price", 500.0, "quantity", 1),
                Map.<String, Object>of("orderId", "ORD-004", "price",  15.0, "quantity", 3),
                Map.<String, Object>of("orderId", "ORD-005", "price", 200.0, "quantity", 2)
        );
        return Map.of("orders", orders);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_PRODUCTS = (input, ctx) -> {
        Thread.sleep(30);
        System.out.println("  [fetchProducts] loading product catalogue");
        var catalogue = List.of(
                Map.<String, Object>of("id", "ORD-001", "name", "Pro Keyboard"),
                Map.<String, Object>of("id", "ORD-002", "name", "USB Hub"),
                Map.<String, Object>of("id", "ORD-003", "name", "4K Monitor"),
                Map.<String, Object>of("id", "ORD-004", "name", "Mouse Pad"),
                Map.<String, Object>of("id", "ORD-005", "name", "Webcam")
        );
        return Map.of("catalogue", catalogue);
    };

    /**
     * Receives pre-assembled {@code enrichedOrders}, {@code totalRevenue}, and
     * {@code orderCount} — all computed by lambda expressions in the DSL
     * {@code input} block rather than inside this operator.
     */
    static final Operator<Map<String, Object>, Map<String, Object>> ENRICH_ORDERS = (input, ctx) -> {
        Thread.sleep(20);
        List<Map<String, Object>> enriched = (List<Map<String, Object>>) input.get("enrichedOrders");
        double revenue = ((Number) input.getOrDefault("totalRevenue", 0.0)).doubleValue();
        int count = ((Number) input.getOrDefault("orderCount", 0L)).intValue();
        System.out.printf("  [enrichOrders]  qualifying=%d  revenue=%.2f%n", count, revenue);
        return Map.of("enrichedOrders", enriched, "totalRevenue", revenue, "orderCount", count);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> GENERATE_REPORT = (input, ctx) -> {
        Thread.sleep(20);
        String customerId = (String) input.get("customerId");
        List<String> orderIds = (List<String>) input.get("orderIds");
        double revenue = ((Number) input.getOrDefault("totalRevenue", 0.0)).doubleValue();
        int count = ((Number) input.getOrDefault("orderCount", 0L)).intValue();
        System.out.printf("  [generateReport] customerId=%s orders=%s revenue=%.2f%n",
                customerId, orderIds, revenue);
        return Map.of("reportId", "RPT-001", "customerId", customerId,
                "qualifyingOrders", count, "totalRevenue", revenue);
    };

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        // FetchOrdersOperator  : input {customerId} → output {orders: [{orderId, price, quantity}]}
        registry.register("FetchOrdersOperator",   FETCH_ORDERS);
        // FetchProductsOperator: input {orderIds}   → output {catalogue: [{id, name}]}
        registry.register("FetchProductsOperator", FETCH_PRODUCTS);
        // EnrichOrdersOperator : input {enrichedOrders, totalRevenue, orderCount} assembled by lambdas
        registry.register("EnrichOrdersOperator",  ENRICH_ORDERS);
        // GenerateReportOperator: input {customerId, orders, totalRevenue, orderCount, orderIds}
        registry.register("GenerateReportOperator", GENERATE_REPORT);

        var engine = GraphEngine.builder()
                .registry(registry)
                .listeners(List.of(new LoggingListener()))
                .build();
        var loader = new GraphLoader(registry);

        // Load from .bloge resource file
        Graph graph;
        try (var stream = OrderEnrichmentLambdaDslExample.class
                .getResourceAsStream("/bloge/order-enrichment-lambda.bloge")) {
            if (stream != null) {
                graph = loader.load(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } else {
                // Fallback: inline DSL matching order-enrichment-lambda.bloge
                graph = loader.load(buildInlineDsl());
            }
        } catch (Exception e) {
            System.out.println("Loading from .bloge file failed (" + e.getMessage() + "), using inline DSL");
            graph = loader.load(buildInlineDsl());
        }

        var ctx = new GraphContext(Map.of(
                "customerId", "CUST-LAMBDA-42",
                "minValue",   100.0    // only orders with price*qty > 100 qualify
        ));

        System.out.println("\n═══ Lambda Collection Ops: Order Enrichment ═══");
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ Result ═══");
        System.out.println("Success: " + result.isSuccess());
        for (var e : result.statusMap().entrySet()) {
            System.out.printf("  %-22s → %s%n", e.getKey(), e.getValue());
        }
        if (result.getStatus("generateReport") == NodeStatus.COMPLETED) {
            System.out.println("Report: " + result.results().getRaw("generateReport"));
        }
    }

    /** Inline DSL that exactly mirrors order-enrichment-lambda.bloge. */
    private static String buildInlineDsl() {
        return """
                graph orderEnrichmentLambda {

                  node fetchOrders : FetchOrdersOperator {
                    input {
                      customerId = ctx.customerId
                    }
                    timeout = 3s
                  }

                  node fetchProducts : FetchProductsOperator {
                    input {
                      orderIds = fetchOrders.output.orders.map(o -> o.orderId)
                    }
                    depends_on = [fetchOrders]
                    timeout = 3s
                  }

                  node enrichOrders : EnrichOrdersOperator {
                    depends_on = [fetchOrders, fetchProducts]
                    input {
                      enrichedOrders = fetchOrders.output.orders
                                         .filter(o -> o.price * o.quantity > ctx.minValue)
                                         .map(o -> {
                                           orderId:    o.orderId,
                                           totalValue: o.price * o.quantity,
                                           taxAmount:  o.price * o.quantity * 0.1
                                         })
                                         .sortBy(o -> o.totalValue)

                      totalRevenue = fetchOrders.output.orders
                                       .filter(o -> o.price * o.quantity > ctx.minValue)
                                       .reduce(0, (acc, o) -> acc + o.price * o.quantity)

                      orderCount = fetchOrders.output.orders
                                     .filter(o -> o.price * o.quantity > ctx.minValue)
                                     .map(o -> 1)
                                     .reduce(0, (acc, x) -> acc + x)
                    }
                  }

                  node generateReport : GenerateReportOperator {
                    depends_on = [enrichOrders]
                    input {
                      customerId   = ctx.customerId
                      orders       = enrichOrders.output.enrichedOrders
                      totalRevenue = enrichOrders.output.totalRevenue
                      orderCount   = enrichOrders.output.orderCount
                      orderIds     = enrichOrders.output.enrichedOrders.map(o -> o.orderId)
                    }
                  }
                }
                """;
    }
}
