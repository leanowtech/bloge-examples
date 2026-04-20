package com.leanowtech.bloge.examples.customerservice;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.util.List;
import java.util.Map;

/**
 * DSL-based customer-support ticket routing example.
 *
 * <p>This example compiles the ticket-routing graph from DSL, then executes it with
 * Map-based operators registered in the runtime registry.
 *
 * <p>Graph layout:
 * <pre>
 * fetchCustomer + fetchTicketHistory
 *   -> analyzeSentiment
 *   -> classifyPriority
 *      -> assignVipAgent | assignNormalAgent | autoResolve
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings("preview")
public class TicketRoutingDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_CUSTOMER = (input, ctx) -> {
        Thread.sleep(50);
        return Map.of("id", "C-001", "name", "John VIP", "tier", "vip", "language", "en");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_TICKET_HISTORY = (input, ctx) -> {
        Thread.sleep(60);
        var tickets = List.<Map<String, Object>>of(
                Map.of("id", "TKT-100", "subject", "Billing issue", "status", "open"),
                Map.of("id", "TKT-099", "subject", "Login problem", "status", "closed")
        );
        return Map.of("recent", tickets, "totalTickets", 15);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ANALYZE_SENTIMENT = (input, ctx) -> {
        Thread.sleep(100);
        return Map.of("sentiment", "negative", "score", 0.85, "keywords", List.of("billing", "overcharge"));
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> CLASSIFY_PRIORITY = (input, ctx) -> {
        Thread.sleep(30);
        var customer = (Map<String, Object>) input.get("customer");
        var sentiment = (Map<String, Object>) input.get("sentiment");
        String tier = (String) customer.get("tier");
        double score = ((Number) sentiment.get("score")).doubleValue();
        if ("vip".equals(tier)) {
            return Map.of("priority", "vip", "autoResolvable", false);
        } else if (score > 0.7) {
            return Map.of("priority", "normal", "autoResolvable", false);
        }
        return Map.of("priority", "low", "autoResolvable", true);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ASSIGN_VIP_AGENT = (input, ctx) -> {
        Thread.sleep(40);
        return Map.of("agentId", "A-VIP-01", "agentName", "Senior Agent Kim", "channel", "priority-phone");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ASSIGN_NORMAL_AGENT = (input, ctx) -> {
        Thread.sleep(40);
        return Map.of("agentId", "A-NRM-05", "agentName", "Agent Lee", "channel", "chat");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> AUTO_RESOLVE = (input, ctx) -> {
        Thread.sleep(30);
        return Map.of("ticketId", "TKT-AUTO-001", "solution", "Auto-resolved: billing FAQ applied");
    };

    /**
     * Loads and executes the ticket-routing DSL graph with sample ticket text.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // ── Operator Registrations ─────────────────────────────────────────────
        // FETCH_CUSTOMER: reads ctx.customerId → {id, name, tier, language}
        registry.register("FetchCustomerOperator", FETCH_CUSTOMER);
        // FETCH_TICKET_HISTORY: reads ctx.customerId → {recent: List<Ticket>, totalTickets}
        registry.register("FetchTicketHistoryOperator", FETCH_TICKET_HISTORY);
        // ANALYZE_SENTIMENT: reads customer + history + ctx.message → {sentiment, score, keywords}
        registry.register("AnalyzeSentimentOperator", ANALYZE_SENTIMENT);
        // CLASSIFY_PRIORITY: reads customer.tier + sentiment.score → {priority, autoResolvable}
        registry.register("ClassifyPriorityOperator", CLASSIFY_PRIORITY);
        // ASSIGN_VIP_AGENT: reads customerId/priority → {agentId, agentName, channel}
        registry.register("AssignVipAgentOperator", ASSIGN_VIP_AGENT);
        // ASSIGN_NORMAL_AGENT: reads customerId/priority → {agentId, agentName, channel}
        registry.register("AssignNormalAgentOperator", ASSIGN_NORMAL_AGENT);
        // AUTO_RESOLVE: reads customerId + sentiment.keywords → {ticketId, solution}
        registry.register("AutoResolveOperator", AUTO_RESOLVE);

        var loader = new GraphLoader(registry);

        String dsl = """
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

        // compile DSL; operators resolved by PascalCase name
        Graph graph = loader.load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "customerId", "C-001",
                "message", "I was overcharged on my last bill and nobody is helping me!"
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Ticket Routing Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        // getRaw returns Object; cast to Map for structured access
        if (result.getStatus("assignVipAgent") == NodeStatus.COMPLETED) {
            System.out.println("VIP agent assigned: " + result.results().getRaw("assignVipAgent"));
        } else if (result.getStatus("assignNormalAgent") == NodeStatus.COMPLETED) {
            System.out.println("Normal agent assigned: " + result.results().getRaw("assignNormalAgent"));
        } else if (result.getStatus("autoResolve") == NodeStatus.COMPLETED) {
            System.out.println("Auto-resolved: " + result.results().getRaw("autoResolve"));
        }
    }
}
