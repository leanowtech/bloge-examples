package com.leanowtech.bloge.examples.ecommerce;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.ast.AstNode.GraphDef;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.dsl.lexer.Lexer;
import com.leanowtech.bloge.dsl.parser.Parser;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class OrderFullPipelineReplExample {

    private static final String DSL = """

                graph orderFullPipeline {
                  ///  validateOrder: reads ctx.orderId/amount/customerId → {orderId, amount, customerId, status}
                  node validateOrder : ValidateOrderOperator {
                    input {
                      orderId    = ctx.orderId
                      amount     = ctx.amount
                      customerId = ctx.customerId
                    }
                    timeout = 3s
                  }
                  ///  paymentProcessing/inventoryFulfillment run in parallel after validateOrder
                  ///  paymentProcessing: sub-graph fraudDetection → paymentGateway → paymentConfirmation → receiptGeneration
                  node paymentProcessing : subgraph("payment-processing") {
                    depends_on = [validateOrder]
                    input {
                      orderId    = validateOrder.output.orderId
                      amount     = validateOrder.output.amount
                      customerId = validateOrder.output.customerId
                    }
                    timeout = 30s
                  }
                  ///  inventoryFulfillment: sub-graph inventoryCheck → warehouseAllocation → shipmentCreation
                  node inventoryFulfillment : subgraph("inventory-fulfillment") {
                    depends_on = [validateOrder]
                    input {
                      orderId    = validateOrder.output.orderId
                      customerId = validateOrder.output.customerId
                    }
                    timeout = 30s
                  }
                  ///  confirmOrder: joins payment + inventory sub-graph outputs → {orderId, status, receiptId, trackingId}
                  node confirmOrder : ConfirmOrderOperator {
                    depends_on = [paymentProcessing, inventoryFulfillment]
                    input {
                      orderId   = validateOrder.output.orderId
                      payment   = paymentProcessing.output.receiptGeneration
                      inventory = inventoryFulfillment.output.shipmentCreation
                    }
                  }
                  ///  notifyCustomer: reads confirmOrder.orderId/trackingId → {orderId, customerId, channel, message}
                  node notifyCustomer : NotifyCustomerOperator {
                    depends_on = [confirmOrder]
                    input {
                      orderId    = confirmOrder.output.orderId
                      customerId = ctx.customerId
                      trackingId = confirmOrder.output.trackingId
                    }
                  }
                }
                
            """;

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("ValidateOrderOperator", OrderFullPipelineDslExample.VALIDATE_ORDER);
        registry.register("ConfirmOrderOperator", OrderFullPipelineDslExample.CONFIRM_ORDER);
        registry.register("NotifyCustomerOperator", OrderFullPipelineDslExample.NOTIFY_CUSTOMER);
        registry.register("fraudDetection", OrderFullPipelineDslExample.FRAUD_DETECTION);
        registry.register("paymentGateway", OrderFullPipelineDslExample.PAYMENT_GATEWAY);
        registry.register("paymentConfirmation", OrderFullPipelineDslExample.PAYMENT_CONFIRMATION);
        registry.register("receiptGeneration", OrderFullPipelineDslExample.RECEIPT_GENERATION);
        registry.register("inventoryCheck", OrderFullPipelineDslExample.INVENTORY_CHECK);
        registry.register("warehouseAllocation", OrderFullPipelineDslExample.WAREHOUSE_ALLOCATION);
        registry.register("shipmentCreation", OrderFullPipelineDslExample.SHIPMENT_CREATION);
        var compiler = new DslCompiler(registry);
        compiler.registerSubGraph("payment-processing", OrderFullPipelineDslExample.buildPaymentSubGraph());
        compiler.registerSubGraph("inventory-fulfillment", OrderFullPipelineDslExample.buildInventorySubGraph());

        var tokens = new Lexer(DSL).tokenize();
        GraphDef ast = new Parser(tokens).parse();
        return compiler.compile(ast);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String orderId = ReplHelper.promptString(scanner, "orderId", "ORD-7890");
        double amount = ReplHelper.promptDouble(scanner, "amount", 249.99);
        String customerId = ReplHelper.promptString(scanner, "customerId", "CUST-42");
        return Map.of(
                "orderId", orderId,
                "amount", amount,
                "customerId", customerId
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
                ReplHelper.header("Order Full Pipeline REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
