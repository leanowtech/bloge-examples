package com.leanowtech.bloge.examples.customerservice;

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

public class SmartTicketHandlingReplExample {

    private static final String DSL = """

                graph smartTicketHandling {
                  ///  receiveTicket: reads ctx.ticketId/customerId/channel/message → {ticketId, customerId, channel, message, status}
                  node receiveTicket : ReceiveTicketOperator {
                    input {
                      ticketId   = ctx.ticketId
                      customerId = ctx.customerId
                      channel    = ctx.channel
                      message    = ctx.message
                    }
                    timeout = 3s
                  }
                  ///  classifyIntent: reads receiveTicket.message → {ticketId, intent, confidence, category}
                  node classifyIntent : ClassifyIntentOperator {
                    depends_on = [receiveTicket]
                    input {
                      ticketId = receiveTicket.output.ticketId
                      message  = receiveTicket.output.message
                    }
                    timeout = 5s
                  }
                  ///  sentimentAnalysis: sub-graph textPreprocessing → nlpClassification → sentimentScoring → priorityAssignment
                  node sentimentAnalysis : subgraph("sentiment-analysis") {
                    depends_on = [classifyIntent]
                    input {
                      ticketId = receiveTicket.output.ticketId
                      message  = receiveTicket.output.message
                      intent   = classifyIntent.output.intent
                    }
                    timeout = 30s
                  }
                  ///  determinePriority: reads sentimentAnalysis.priorityAssignment + classifyIntent.intent → {ticketId, priority, reason}
                  node determinePriority : DeterminePriorityOperator {
                    depends_on = [sentimentAnalysis, classifyIntent]
                    input {
                      ticketId  = classifyIntent.output.ticketId
                      intent    = classifyIntent.output.intent
                      sentiment = sentimentAnalysis.output.priorityAssignment
                    }
                  }
                  ///  escalationWorkflow: sub-graph supervisorNotification → slaCheck → escalationRouting → customerCallbackSchedule
                  node escalationWorkflow : subgraph("escalation-workflow") {
                    depends_on = [determinePriority]
                    input {
                      ticketId   = determinePriority.output.ticketId
                      customerId = ctx.customerId
                      priority   = determinePriority.output.priority
                      reason     = determinePriority.output.reason
                    }
                    timeout = 30s
                  }
                  ///  generateReply: reads ticketId/intent/priority/resolution → {ticketId, replyText, channel, status}
                  node generateReply : GenerateReplyOperator {
                    depends_on = [determinePriority]
                    input {
                      ticketId   = determinePriority.output.ticketId
                      customerId = ctx.customerId
                      intent     = classifyIntent.output.intent
                      priority   = determinePriority.output.priority
                      resolution = determinePriority.output.reason
                    }
                  }
                  ///  branch: "high" priority → escalationWorkflow sub-graph; otherwise → generateReply
                  branch on determinePriority.output.priority {
                    "high"    -> escalationWorkflow
                    otherwise -> generateReply
                  }
                }
                
            """;

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("ReceiveTicketOperator", SmartTicketHandlingDslExample.RECEIVE_TICKET);
        registry.register("ClassifyIntentOperator", SmartTicketHandlingDslExample.CLASSIFY_INTENT);
        registry.register("DeterminePriorityOperator", SmartTicketHandlingDslExample.DETERMINE_PRIORITY);
        registry.register("GenerateReplyOperator", SmartTicketHandlingDslExample.GENERATE_REPLY);
        registry.register("textPreprocessing", SmartTicketHandlingDslExample.TEXT_PREPROCESSING);
        registry.register("nlpClassification", SmartTicketHandlingDslExample.NLP_CLASSIFICATION);
        registry.register("sentimentScoring", SmartTicketHandlingDslExample.SENTIMENT_SCORING);
        registry.register("priorityAssignment", SmartTicketHandlingDslExample.PRIORITY_ASSIGNMENT);
        registry.register("supervisorNotification", SmartTicketHandlingDslExample.SUPERVISOR_NOTIFICATION);
        registry.register("slaCheck", SmartTicketHandlingDslExample.SLA_CHECK);
        registry.register("escalationRouting", SmartTicketHandlingDslExample.ESCALATION_ROUTING);
        registry.register("customerCallbackSchedule", SmartTicketHandlingDslExample.CUSTOMER_CALLBACK_SCHEDULE);
        var compiler = new DslCompiler(registry);
        compiler.registerSubGraph("sentiment-analysis", SmartTicketHandlingDslExample.buildSentimentAnalysisSubGraph());
        compiler.registerSubGraph("escalation-workflow", SmartTicketHandlingDslExample.buildEscalationWorkflowSubGraph());

        var tokens = new Lexer(DSL).tokenize();
        GraphDef ast = new Parser(tokens).parse();
        return compiler.compile(ast);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String ticketId = ReplHelper.promptString(scanner, "ticketId", "TKT-20250115-001");
        String customerId = ReplHelper.promptString(scanner, "customerId", "CUST-8842");
        String message = ReplHelper.promptString(
                scanner,
                "message",
                "My order arrived broken and I want a refund immediately! "
                        + "This is terrible service.");
        return Map.of(
                "ticketId", ticketId,
                "customerId", customerId,
                "channel", "web-chat",
                "message", message
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
                ReplHelper.header("Smart Ticket Handling REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
