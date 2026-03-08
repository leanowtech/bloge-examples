package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;

import java.util.List;
import java.util.Map;

/**
 * DSL-based ecommerce order processing example.
 *
 * <p>This example compiles the order-processing graph from DSL and executes it through
 * registry-bound Map operators that mirror the typed Java workflow.
 *
 * <p>Graph layout:
 * <pre>
 * fetchUser + fetchProducts
 *   -> calcPrice
 *   -> checkCredit
 *      -> createOrder | rejectOrder
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings("preview")
public class OrderProcessingDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_USER = (input, ctx) -> {
        Thread.sleep(50);
        String userId = (String) input.get("userId");
        return Map.of("id", userId, "name", "Alice", "email", "alice@example.com", "creditScore", 750);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_PRODUCTS = (input, ctx) -> {
        Thread.sleep(80);
        @SuppressWarnings("unchecked")
        var productIds = (List<String>) input.get("productIds");
        var products = productIds.stream()
                .map(id -> Map.<String, Object>of("id", id, "name", "Product-" + id, "price", 29.99))
                .toList();
        return Map.of("items", products);
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> CALC_PRICE = (input, ctx) -> {
        var products = (Map<String, Object>) input.get("products");
        var items = (List<Map<String, Object>>) products.get("items");
        double subtotal = items.stream()
                .mapToDouble(item -> ((Number) item.get("price")).doubleValue())
                .sum();
        double tax = subtotal * 0.08;
        return Map.of("subtotal", subtotal, "tax", tax, "total", subtotal + tax);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CHECK_CREDIT = (input, ctx) -> {
        Thread.sleep(30);
        double amount = ((Number) input.get("amount")).doubleValue();
        return Map.of("approved", amount < 500, "reason", "Amount exceeds limit");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CREATE_ORDER = (input, ctx) -> {
        var user = (Map<String, Object>) input.get("user");
        var price = (Map<String, Object>) input.get("price");
        return Map.of(
                "orderId", "ORD-12345",
                "userId", user.get("id"),
                "total", price.get("total"),
                "status", "CONFIRMED"
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> REJECT_ORDER = (input, ctx) -> {
        return Map.of(
                "userId", input.get("userId"),
                "reason", input.get("reason")
        );
    };

    /**
     * Loads and executes the order-processing DSL graph with sample user and product input.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // ── Operator Registrations ─────────────────────────────────────────────
        // FETCH_USER: reads ctx.userId → {id, name, email, creditScore}
        registry.register("FetchUserOperator", FETCH_USER);
        // FETCH_PRODUCTS: reads ctx.productIds → {items: List<{id, name, price}>}
        registry.register("FetchProductsOperator", FETCH_PRODUCTS);
        // CALC_PRICE: reads fetchProducts.items → {subtotal, tax, total}
        registry.register("CalcPriceOperator", CALC_PRICE);
        // CHECK_CREDIT: reads fetchUser.id + calcPrice.total → {approved, reason}; fallback: denied
        registry.register("CreditCheckOperator", CHECK_CREDIT);
        // CREATE_ORDER: reads fetchUser + calcPrice → {orderId, userId, total, status}
        registry.register("CreateOrderOperator", CREATE_ORDER);
        // REJECT_ORDER: reads fetchUser.id + checkCredit.reason → {userId, reason}
        registry.register("RejectOrderOperator", REJECT_ORDER);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph orderProcess {
                  ///  fetchUser/fetchProducts execute in parallel; fetchUser reads ctx.userId, fetchProducts reads ctx.productIds
                  node fetchUser : FetchUserOperator {
                    input { userId = ctx.userId }
                    timeout = 3s
                    retry = { attempts: 2, backoff: 200ms, strategy: exponential }
                  }
                  node fetchProducts : FetchProductsOperator {
                    input { productIds = ctx.productIds }
                    timeout = 5s
                  }
                  ///  calcPrice: reads fetchUser + fetchProducts → {subtotal, tax, total}
                  node calcPrice : CalcPriceOperator {
                    depends_on = [fetchUser, fetchProducts]
                    input {
                      user     = fetchUser.output
                      products = fetchProducts.output
                    }
                  }
                  ///  checkCredit: reads fetchUser.id + calcPrice.total → {approved, reason}; fallback: denied
                  node checkCredit : CreditCheckOperator {
                    depends_on = [fetchUser, calcPrice]
                    input {
                      userId = fetchUser.output.id
                      amount = calcPrice.output.total
                    }
                    retry = { attempts: 3, backoff: 100ms, strategy: jitter }
                    fallback = { approved: false, reason: "credit service unavailable" }
                  }
                  ///  branch: approved → createOrder; denied → rejectOrder
                  branch on checkCredit.output.approved {
                    true  -> createOrder
                    false -> rejectOrder
                  }
                  ///  createOrder: reads fetchUser + calcPrice → {orderId, userId, total, status}
                  node createOrder : CreateOrderOperator {
                    depends_on = [calcPrice]
                    input {
                      user  = fetchUser.output
                      price = calcPrice.output
                    }
                  }
                  ///  rejectOrder: reads fetchUser.id + checkCredit.reason → {userId, reason}
                  node rejectOrder : RejectOrderOperator {
                    depends_on = [checkCredit]
                    input {
                      userId = fetchUser.output.id
                      reason = checkCredit.output.reason
                    }
                  }
                }
                """;

        // compile DSL; operators resolved by PascalCase name
        Graph graph = loader.load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "userId", "user-42",
                "productIds", List.of("prod-1", "prod-2", "prod-3")
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Order Processing Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-15s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        // getRaw returns Object; cast to Map for structured access
        if (result.getStatus("createOrder") == NodeStatus.COMPLETED) {
            System.out.println("Order created: " + result.results().getRaw("createOrder"));
        } else if (result.getStatus("rejectOrder") == NodeStatus.COMPLETED) {
            System.out.println("Order rejected: " + result.results().getRaw("rejectOrder"));
        }
    }
}
