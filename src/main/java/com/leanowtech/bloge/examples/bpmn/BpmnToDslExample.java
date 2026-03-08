package com.leanowtech.bloge.examples.bpmn;

import com.leanowtech.bloge.bpmn.api.BpmnTranslator;
import com.leanowtech.bloge.bpmn.api.TranslationResult;
import com.leanowtech.bloge.bpmn.diagnostic.TranslationDiagnostic;
import com.leanowtech.bloge.bpmn.mapping.OperatorMappingConfig;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;

import java.io.InputStream;
import java.util.Map;

/**
 * Demonstrates BPMN -> BLOGE DSL translation with execution.
 */
@SuppressWarnings("preview")
public final class BpmnToDslExample {

    private static final Operator<Object, Map<String, Object>> FETCH_USER = (input, ctx) ->
            Map.of("id", ctx.graphContext().get("userId", String.class), "name", "Alice");

    private static final Operator<Object, Map<String, Object>> FETCH_PRODUCTS = (input, ctx) ->
            Map.of("count", 3, "subtotal", 780.0);

    private static final Operator<Object, Map<String, Object>> CALC_PRICE = (input, ctx) ->
            Map.of("total", 780.0);

    private static final Operator<Object, Map<String, Object>> CHECK_CREDIT = (input, ctx) -> {
        Number requestAmount = ctx.graphContext().get("requestAmount", Number.class);
        double amount = requestAmount == null ? 0.0 : requestAmount.doubleValue();
        return Map.of("approved", amount <= 500.0, "reason", "Amount exceeds limit");
    };

    private static final Operator<Object, Map<String, Object>> CREATE_ORDER = (input, ctx) ->
            Map.of("status", "CONFIRMED", "orderId", "ORD-12345");

    private static final Operator<Object, Map<String, Object>> REJECT_ORDER = (input, ctx) ->
            Map.of("status", "REJECTED", "reason", "Credit check failed");

    private BpmnToDslExample() {
    }

    public static void main(String[] args) throws Exception {
        TranslationResult<String> translated = translateOrderProcessBpmn();
        printDiagnostics(translated.diagnostics());

        System.out.println("\n═══ Translated DSL ═══");
        System.out.println(translated.result());

        DefaultOperatorRegistry registry = createRegistry();
        Graph graph = new GraphLoader(registry).load(translated.result());

        GraphEngine engine = GraphEngine.builder().registry(registry).build();
        GraphResult result = engine.execute(graph, new GraphContext(Map.of(
                "userId", "user-42",
                "requestAmount", 780
        )));

        System.out.println("\n═══ Execution Result ═══");
        System.out.println("Success: " + result.isSuccess());
        result.statusMap().forEach((nodeId, status) -> System.out.printf("  %-15s → %s%n", nodeId, status));
        if (result.getStatus("createOrder") == NodeStatus.COMPLETED) {
            System.out.println("Order:  " + result.results().getRaw("createOrder"));
        } else if (result.getStatus("rejectOrder") == NodeStatus.COMPLETED) {
            System.out.println("Reject: " + result.results().getRaw("rejectOrder"));
        }
    }

    private static TranslationResult<String> translateOrderProcessBpmn() throws Exception {
        BpmnTranslator translator = new BpmnTranslator(OperatorMappingConfig.EMPTY);
        try (InputStream bpmn = BpmnToDslExample.class.getResourceAsStream("/bpmn/order-process.bpmn")) {
            if (bpmn == null) {
                throw new IllegalStateException("Missing classpath resource: /bpmn/order-process.bpmn");
            }
            return translator.translateToDsl(bpmn);
        }
    }

    private static DefaultOperatorRegistry createRegistry() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("FetchUserOperator", FETCH_USER);
        registry.register("FetchProductsOperator", FETCH_PRODUCTS);
        registry.register("CalcPriceOperator", CALC_PRICE);
        registry.register("CreditCheckOperator", CHECK_CREDIT);
        registry.register("CreateOrderOperator", CREATE_ORDER);
        registry.register("RejectOrderOperator", REJECT_ORDER);
        return registry;
    }

    private static void printDiagnostics(Iterable<TranslationDiagnostic> diagnostics) {
        for (TranslationDiagnostic diagnostic : diagnostics) {
            System.out.printf("[%s] %s %s%n",
                    diagnostic.severity(), diagnostic.code(), diagnostic.message());
        }
    }
}
