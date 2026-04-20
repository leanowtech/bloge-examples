package com.leanowtech.bloge.examples.ecommerce;

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

public class OrderProcessingReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("FetchUserOperator", OrderProcessingDslExample.FETCH_USER);
        registry.register("FetchProductsOperator", OrderProcessingDslExample.FETCH_PRODUCTS);
        registry.register("CalcPriceOperator", OrderProcessingDslExample.CALC_PRICE);
        registry.register("CreditCheckOperator", OrderProcessingDslExample.CHECK_CREDIT);
        registry.register("CreateOrderOperator", OrderProcessingDslExample.CREATE_ORDER);
        registry.register("RejectOrderOperator", OrderProcessingDslExample.REJECT_ORDER);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String userId = ReplHelper.promptString(scanner, "userId", "user-42");
        List<String> productIds = ReplHelper.promptList(scanner, "productIds (comma separated)", List.of("prod-1", "prod-2", "prod-3"));
        return Map.of(
                "userId", userId,
                "productIds", productIds
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
                ReplHelper.header("Order Processing REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
