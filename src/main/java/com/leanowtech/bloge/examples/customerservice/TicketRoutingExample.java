package com.leanowtech.bloge.examples.customerservice;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Customer-support ticket routing example using typed Java graph definitions.
 *
 * <p>This example demonstrates enrichment (customer + history), sentiment-based prioritization,
 * and branch routing to specialized handling paths (VIP queue, normal queue, auto-resolution).
 *
 * <p>Graph layout:
 * <pre>
 * fetchCustomer + fetchTicketHistory
 *   -> analyzeSentiment
 *   -> classifyPriority
 *      -> (vip)    assignVipAgent
 *      -> (normal) assignNormalAgent
 *      -> (other)  autoResolve
 * </pre>
 *
 * <p>Run {@link #main(String[])} to execute the workflow with a sample customer message.
 */
public class TicketRoutingExample {

    public record CustomerQuery(String customerId) {}
    public record Customer(String id, String name, String tier, String language) {}

    public record TicketQuery(String customerId) {}
    public record Ticket(String id, String subject, String status) {}
    public record TicketHistory(List<Ticket> recent, int totalTickets) {}

    public record SentimentInput(Customer customer, TicketHistory history, String message) {}
    public record SentimentResult(String sentiment, double score, List<String> keywords) {}

    public record PriorityInput(Customer customer, SentimentResult sentiment) {}
    public record PriorityDecision(String priority, boolean autoResolvable) {}

    public record AssignmentInput(String customerId, String priority) {}
    public record Assignment(String agentId, String agentName, String channel) {}

    public record AutoResolveInput(String customerId, List<String> keywords) {}
    public record AutoResolveResult(String ticketId, String solution) {}

    static final Operator<CustomerQuery, Customer> FETCH_CUSTOMER = (input, ctx) -> {
        Thread.sleep(50);
        return new Customer("C-001", "John VIP", "vip", "en");
    };

    static final Operator<TicketQuery, TicketHistory> FETCH_TICKET_HISTORY = (input, ctx) -> {
        Thread.sleep(60);
        var tickets = List.of(
                new Ticket("TKT-100", "Billing issue", "open"),
                new Ticket("TKT-099", "Login problem", "closed")
        );
        return new TicketHistory(tickets, 15);
    };

    static final Operator<SentimentInput, SentimentResult> ANALYZE_SENTIMENT = (input, ctx) -> {
        Thread.sleep(100);
        return new SentimentResult("negative", 0.85, List.of("billing", "overcharge"));
    };

    static final Operator<PriorityInput, PriorityDecision> CLASSIFY_PRIORITY = (input, ctx) -> {
        Thread.sleep(30);
        if ("vip".equals(input.customer().tier())) {
            return new PriorityDecision("vip", false);
        } else if (input.sentiment().score() > 0.7) {
            return new PriorityDecision("normal", false);
        }
        return new PriorityDecision("low", true);
    };

    static final Operator<AssignmentInput, Assignment> ASSIGN_VIP_AGENT = (input, ctx) -> {
        Thread.sleep(40);
        return new Assignment("A-VIP-01", "Senior Agent Kim", "priority-phone");
    };

    static final Operator<AssignmentInput, Assignment> ASSIGN_NORMAL_AGENT = (input, ctx) -> {
        Thread.sleep(40);
        return new Assignment("A-NRM-05", "Agent Lee", "chat");
    };

    static final Operator<AutoResolveInput, AutoResolveResult> AUTO_RESOLVE = (input, ctx) -> {
        Thread.sleep(30);
        return new AutoResolveResult("TKT-AUTO-001", "Auto-resolved: billing FAQ applied");
    };

    /**
     * Builds the ticket routing graph with branch-based resolution paths.
     *
     * @return configured graph instance
     */
    public static Graph buildGraph() {
        var builder = Graph.builder("ticketRouting")
                .node("fetchCustomer", FETCH_CUSTOMER)
                    .input((results, ctx) -> new CustomerQuery(ctx.get("customerId", String.class)))
                    .timeout(Duration.ofSeconds(3))
                    .retry(2, Duration.ofMillis(200), BackoffStrategy.EXPONENTIAL)
                .node("fetchTicketHistory", FETCH_TICKET_HISTORY)
                    .input((results, ctx) -> new TicketQuery(ctx.get("customerId", String.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("analyzeSentiment", ANALYZE_SENTIMENT)
                    .dependsOn("fetchCustomer", "fetchTicketHistory")
                    .input((results, ctx) -> new SentimentInput(
                            results.get("fetchCustomer", Customer.class),
                            results.get("fetchTicketHistory", TicketHistory.class),
                            ctx.get("message", String.class)))
                    .timeout(Duration.ofSeconds(5))
                    .retry(1, Duration.ofMillis(500), BackoffStrategy.EXPONENTIAL)
                    .fallback(ex -> new SentimentResult("neutral", 0.0, List.of()))
                .node("classifyPriority", CLASSIFY_PRIORITY)
                    .dependsOn("analyzeSentiment")
                    .input((results, ctx) -> new PriorityInput(
                            results.get("fetchCustomer", Customer.class),
                            results.get("analyzeSentiment", SentimentResult.class)))
                .node("assignVipAgent", ASSIGN_VIP_AGENT)
                    .dependsOn("classifyPriority")
                    .input((results, ctx) -> new AssignmentInput(
                            results.get("fetchCustomer", Customer.class).id(),
                            "vip"))
                .node("assignNormalAgent", ASSIGN_NORMAL_AGENT)
                    .dependsOn("classifyPriority")
                    .input((results, ctx) -> new AssignmentInput(
                            results.get("fetchCustomer", Customer.class).id(),
                            "normal"))
                .node("autoResolve", AUTO_RESOLVE)
                    .dependsOn("classifyPriority")
                    .input((results, ctx) -> new AutoResolveInput(
                            results.get("fetchCustomer", Customer.class).id(),
                            results.get("analyzeSentiment", SentimentResult.class).keywords()))
                .branch("classifyPriority")
                    .on("priority")
                    .when(val -> "vip".equals(val), "assignVipAgent")
                    .when(val -> "normal".equals(val), "assignNormalAgent")
                    .otherwise("autoResolve");

        return builder.build();
    }

    @SuppressWarnings("preview")
    /**
     * Executes the ticket routing workflow using sample customer and message input.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();

        Graph graph = buildGraph();

        var ctx = new GraphContext(Map.of(
                "customerId", "C-001",
                "message", "I was overcharged on my last bill and nobody is helping me!"
        ));

        GraphResult result = engine.executeWithOperators(graph, ctx, Map.of(
                "fetchCustomer", FETCH_CUSTOMER,
                "fetchTicketHistory", FETCH_TICKET_HISTORY,
                "analyzeSentiment", ANALYZE_SENTIMENT,
                "classifyPriority", CLASSIFY_PRIORITY,
                "assignVipAgent", ASSIGN_VIP_AGENT,
                "assignNormalAgent", ASSIGN_NORMAL_AGENT,
                "autoResolve", AUTO_RESOLVE
        ));

        System.out.println("\n═══ Ticket Routing Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("assignVipAgent") == NodeStatus.COMPLETED) {
            Assignment assignment = result.getOutput("assignVipAgent", Assignment.class);
            System.out.println("VIP agent assigned: " + assignment);
        } else if (result.getStatus("assignNormalAgent") == NodeStatus.COMPLETED) {
            Assignment assignment = result.getOutput("assignNormalAgent", Assignment.class);
            System.out.println("Normal agent assigned: " + assignment);
        } else if (result.getStatus("autoResolve") == NodeStatus.COMPLETED) {
            AutoResolveResult resolved = result.getOutput("autoResolve", AutoResolveResult.class);
            System.out.println("Auto-resolved: " + resolved);
        }
    }
}
