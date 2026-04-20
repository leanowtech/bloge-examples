package com.leanowtech.bloge.examples.customerservice;

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

public class TicketRoutingReplExample {

    private static final String DSL = """

                graph ticketRouting {
                  ///  fetchCustomer/fetchTicketHistory execute in parallel; both read ctx.customerId
                  node fetchCustomer : FetchCustomerOperator {
                    input { customerId = ctx.customerId }
                    timeout = 3s
                    retry = { attempts: 2, backoff: 200ms, strategy: exponential }
                  }
                  node fetchTicketHistory : FetchTicketHistoryOperator {
                    input { customerId = ctx.customerId }
                    timeout = 3s
                  }
                  ///  analyzeSentiment: reads customer + history + ctx.message → {sentiment, score, keywords}; fallback: neutral
                  node analyzeSentiment : AnalyzeSentimentOperator {
                    depends_on = [fetchCustomer, fetchTicketHistory]
                    input {
                      customer = fetchCustomer.output
                      history  = fetchTicketHistory.output
                      message  = ctx.message
                    }
                    timeout = 5s
                    retry = { attempts: 1, backoff: 500ms, strategy: exponential }
                    fallback = { sentiment: "neutral", score: 0.0, keywords: [] }
                  }
                  ///  classifyPriority: reads customer.tier + sentiment.score → {priority, autoResolvable}
                  node classifyPriority : ClassifyPriorityOperator {
                    depends_on = [analyzeSentiment]
                    input {
                      customer  = fetchCustomer.output
                      sentiment = analyzeSentiment.output
                    }
                  }
                  ///  branch: "vip" → assignVipAgent; "normal" → assignNormalAgent; otherwise → autoResolve
                  branch on classifyPriority.output.priority {
                    "vip"    -> assignVipAgent
                    "normal" -> assignNormalAgent
                    otherwise -> autoResolve
                  }
                  ///  assignVipAgent: routes VIP customer to senior agent → {agentId, agentName, channel}
                  node assignVipAgent : AssignVipAgentOperator {
                    depends_on = [classifyPriority]
                    input {
                      customerId = fetchCustomer.output.id
                      priority   = "vip"
                    }
                  }
                  ///  assignNormalAgent: routes standard customer to available agent → {agentId, agentName, channel}
                  node assignNormalAgent : AssignNormalAgentOperator {
                    depends_on = [classifyPriority]
                    input {
                      customerId = fetchCustomer.output.id
                      priority   = "normal"
                    }
                  }
                  ///  autoResolve: applies FAQ/KB solution for low-priority tickets → {ticketId, solution}
                  node autoResolve : AutoResolveOperator {
                    depends_on = [classifyPriority]
                    input {
                      customerId = fetchCustomer.output.id
                      keywords   = analyzeSentiment.output.keywords
                    }
                  }
                }
                
            """;

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("FetchCustomerOperator", TicketRoutingDslExample.FETCH_CUSTOMER);
        registry.register("FetchTicketHistoryOperator", TicketRoutingDslExample.FETCH_TICKET_HISTORY);
        registry.register("AnalyzeSentimentOperator", TicketRoutingDslExample.ANALYZE_SENTIMENT);
        registry.register("ClassifyPriorityOperator", TicketRoutingDslExample.CLASSIFY_PRIORITY);
        registry.register("AssignVipAgentOperator", TicketRoutingDslExample.ASSIGN_VIP_AGENT);
        registry.register("AssignNormalAgentOperator", TicketRoutingDslExample.ASSIGN_NORMAL_AGENT);
        registry.register("AutoResolveOperator", TicketRoutingDslExample.AUTO_RESOLVE);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String customerId = ReplHelper.promptString(scanner, "customerId", "C-001");
        String message = ReplHelper.promptString(scanner, "message", "I was overcharged on my last bill and nobody is helping me!");
        return Map.of(
                "customerId", customerId,
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
                ReplHelper.header("Ticket Routing REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
