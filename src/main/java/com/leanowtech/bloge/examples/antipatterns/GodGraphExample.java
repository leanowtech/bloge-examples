package com.leanowtech.bloge.examples.antipatterns;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.engine.operators.SubGraphOperator;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;
import com.leanowtech.bloge.lint.LintDiagnostic;
import com.leanowtech.bloge.lint.LintRunner;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Demonstrates the "God graph" antipattern and the preferred alternative.
 *
 * <p>The bad version fans a single ticket node into too many unrelated responsibilities,
 * making the orchestration hard to read and evolve. The good version groups the same work
 * into two cohesive sub-graphs and keeps the top-level orchestration focused on the business flow.</p>
 */
public final class GodGraphExample {

    public record TicketRequest(String ticketId, String customerId, String message, String regionHint) {
    }

    public record CustomerProfile(String customerId, boolean vip) {
    }

    public record Entitlements(boolean premiumSupport) {
    }

    public record Region(String value) {
    }

    public record Sentiment(String tone) {
    }

    public record Intent(String value) {
    }

    public record Skills(List<String> values) {
    }

    public record SlaTarget(String value) {
    }

    public record RoutingPlan(String queue, List<String> skills, String slaTarget, String explanation) {
    }

    static final Operator<TicketRequest, TicketRequest> INGEST_TICKET = (input, ctx) -> input;
    static final Operator<TicketRequest, CustomerProfile> FETCH_CUSTOMER = (input, ctx) ->
            new CustomerProfile(input.customerId(), input.customerId().toUpperCase(Locale.ROOT).startsWith("VIP"));
    static final Operator<TicketRequest, Entitlements> LOAD_ENTITLEMENTS = (input, ctx) ->
            new Entitlements(input.message().toLowerCase(Locale.ROOT).contains("outage"));
    static final Operator<TicketRequest, Region> DERIVE_REGION = (input, ctx) -> new Region(input.regionHint());
    static final Operator<TicketRequest, Sentiment> ANALYZE_SENTIMENT = (input, ctx) ->
            new Sentiment(input.message().toLowerCase(Locale.ROOT).contains("urgent") ? "frustrated" : "calm");
    static final Operator<TicketRequest, Intent> DETECT_INTENT = (input, ctx) ->
            new Intent(input.message().toLowerCase(Locale.ROOT).contains("refund") ? "refund" : "support");
    static final Operator<TicketRequest, SlaTarget> DETERMINE_SLA = (input, ctx) ->
            new SlaTarget(input.message().toLowerCase(Locale.ROOT).contains("urgent") ? "15m" : "4h");
    static final Operator<Intent, Skills> PLAN_SKILLS = (input, ctx) ->
            new Skills(input.value().equals("refund") ? List.of("billing", "policy") : List.of("support", "triage"));

    static final Operator<Map<String, Object>, String> SELECT_QUEUE = (input, ctx) -> {
        CustomerProfile customer = (CustomerProfile) input.get("customer");
        Region region = (Region) input.get("region");
        Sentiment sentiment = (Sentiment) input.get("sentiment");
        if (customer.vip() || "frustrated".equals(sentiment.tone())) {
            return "vip-" + region.value();
        }
        return "general-" + region.value();
    };

    static final Operator<Map<String, Object>, RoutingPlan> PUBLISH_PLAN = (input, ctx) -> new RoutingPlan(
            (String) input.get("queue"),
            ((Skills) input.get("skills")).values(),
            ((SlaTarget) input.get("sla")).value(),
            (String) input.get("explanation")
    );

    private GodGraphExample() {
    }

    /**
     * Builds the overly broad graph where one root node fans out into too many unrelated steps.
     */
    public static Graph buildBadGraph() {
        return Graph.builder("ticketGodGraph")
                .node("ingestTicket", INGEST_TICKET)
                    .input((results, ctx) -> ctx.get("ticket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .node("fetchCustomer", FETCH_CUSTOMER)
                    .dependsOn("ingestTicket")
                    .input((results, ctx) -> results.get("ingestTicket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .node("loadEntitlements", LOAD_ENTITLEMENTS)
                    .dependsOn("ingestTicket")
                    .input((results, ctx) -> results.get("ingestTicket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .node("deriveRegion", DERIVE_REGION)
                    .dependsOn("ingestTicket")
                    .input((results, ctx) -> results.get("ingestTicket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .node("analyzeSentiment", ANALYZE_SENTIMENT)
                    .dependsOn("ingestTicket")
                    .input((results, ctx) -> results.get("ingestTicket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .node("detectIntent", DETECT_INTENT)
                    .dependsOn("ingestTicket")
                    .input((results, ctx) -> results.get("ingestTicket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .node("determineSla", DETERMINE_SLA)
                    .dependsOn("ingestTicket")
                    .input((results, ctx) -> results.get("ingestTicket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .node("planSkills", PLAN_SKILLS)
                    .dependsOn("detectIntent")
                    .input((results, ctx) -> results.get("detectIntent", Intent.class))
                    .timeout(Duration.ofSeconds(1))
                .node("selectQueue", SELECT_QUEUE)
                    .dependsOn("fetchCustomer", "deriveRegion", "analyzeSentiment")
                    .input((results, ctx) -> Map.of(
                            "customer", results.get("fetchCustomer", CustomerProfile.class),
                            "region", results.get("deriveRegion", Region.class),
                            "sentiment", results.get("analyzeSentiment", Sentiment.class)))
                    .timeout(Duration.ofSeconds(1))
                .node("publishPlan", PUBLISH_PLAN)
                    .dependsOn("selectQueue", "planSkills", "determineSla")
                    .input((results, ctx) -> Map.of(
                            "queue", results.get("selectQueue", String.class),
                            "skills", results.get("planSkills", Skills.class),
                            "sla", results.get("determineSla", SlaTarget.class),
                            "explanation", "One graph owns every ticket-routing responsibility"))
                    .timeout(Duration.ofSeconds(1))
                .build();
    }

    /**
     * Builds the preferred graph composed from smaller sub-graphs.
     */
    public static Graph buildComposedGraph(DefaultOperatorRegistry registry) {
        // SubGraphOperator exposes terminal node outputs only, so each sub-graph keeps the
        // data we need as parallel terminal nodes instead of chaining unrelated responsibilities.
        Graph customerEnrichment = Graph.builder("customerEnrichment")
                .node("fetchCustomer", FETCH_CUSTOMER)
                    .input((results, ctx) -> ctx.get("ticket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .node("loadEntitlements", LOAD_ENTITLEMENTS)
                    .input((results, ctx) -> ctx.get("ticket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .node("deriveRegion", DERIVE_REGION)
                    .input((results, ctx) -> ctx.get("ticket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .build();

        Graph ticketAnalysis = Graph.builder("ticketAnalysis")
                .node("analyzeSentiment", ANALYZE_SENTIMENT)
                    .input((results, ctx) -> ctx.get("ticket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .node("detectIntent", DETECT_INTENT)
                    .input((results, ctx) -> ctx.get("ticket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .node("determineSla", DETERMINE_SLA)
                    .input((results, ctx) -> ctx.get("ticket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .node("planSkills", PLAN_SKILLS)
                    .dependsOn("detectIntent")
                    .input((results, ctx) -> results.get("detectIntent", Intent.class))
                    .timeout(Duration.ofSeconds(1))
                .build();

        registry.register("fetchCustomer", FETCH_CUSTOMER);
        registry.register("loadEntitlements", LOAD_ENTITLEMENTS);
        registry.register("deriveRegion", DERIVE_REGION);
        registry.register("analyzeSentiment", ANALYZE_SENTIMENT);
        registry.register("detectIntent", DETECT_INTENT);
        registry.register("determineSla", DETERMINE_SLA);
        registry.register("planSkills", PLAN_SKILLS);

        SubGraphOperator customerEnrichmentOperator = new SubGraphOperator(customerEnrichment, registry);
        SubGraphOperator ticketAnalysisOperator = new SubGraphOperator(ticketAnalysis, registry);

        return Graph.builder("ticketComposedGraph")
                .node("ingestTicket", INGEST_TICKET)
                    .input((results, ctx) -> ctx.get("ticket", TicketRequest.class))
                    .timeout(Duration.ofSeconds(1))
                .node("customerEnrichment", customerEnrichmentOperator)
                    .dependsOn("ingestTicket")
                    .input((results, ctx) -> Map.of("ticket", results.get("ingestTicket", TicketRequest.class)))
                    .timeout(Duration.ofSeconds(2))
                .node("ticketAnalysis", ticketAnalysisOperator)
                    .dependsOn("ingestTicket")
                    .input((results, ctx) -> Map.of("ticket", results.get("ingestTicket", TicketRequest.class)))
                    .timeout(Duration.ofSeconds(2))
                .node("selectQueue", SELECT_QUEUE)
                    .dependsOn("customerEnrichment", "ticketAnalysis")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> enrichment = (Map<String, Object>) results.getRaw("customerEnrichment");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> analysis = (Map<String, Object>) results.getRaw("ticketAnalysis");
                        return Map.of(
                                "customer", enrichment.get("fetchCustomer"),
                                "region", enrichment.get("deriveRegion"),
                                "sentiment", analysis.get("analyzeSentiment"));
                    })
                    .timeout(Duration.ofSeconds(1))
                .node("publishPlan", PUBLISH_PLAN)
                    .dependsOn("selectQueue", "ticketAnalysis")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> analysis = (Map<String, Object>) results.getRaw("ticketAnalysis");
                        return Map.of(
                                "queue", results.get("selectQueue", String.class),
                                "skills", analysis.get("planSkills"),
                                "sla", analysis.get("determineSla"),
                                "explanation", "Sub-graphs isolate customer enrichment and ticket analysis responsibilities");
                    })
                    .timeout(Duration.ofSeconds(1))
                .build();
    }

    /**
     * Executes the bad graph.
     */
    public static GraphResult executeBadScenario(TicketRequest ticket) {
        return execute(buildBadGraph(), ticket, Map.of(
                "ingestTicket", INGEST_TICKET,
                "fetchCustomer", FETCH_CUSTOMER,
                "loadEntitlements", LOAD_ENTITLEMENTS,
                "deriveRegion", DERIVE_REGION,
                "analyzeSentiment", ANALYZE_SENTIMENT,
                "detectIntent", DETECT_INTENT,
                "determineSla", DETERMINE_SLA,
                "planSkills", PLAN_SKILLS,
                "selectQueue", SELECT_QUEUE,
                "publishPlan", PUBLISH_PLAN
        ));
    }

    /**
     * Executes the refactored graph that uses sub-graphs for cohesion.
     */
    public static GraphResult executeComposedScenario(TicketRequest ticket) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildComposedGraph(registry);
        Map<String, Object> operators = new LinkedHashMap<>(graph.embeddedOperators());
        operators.put("ingestTicket", INGEST_TICKET);
        operators.put("selectQueue", SELECT_QUEUE);
        operators.put("publishPlan", PUBLISH_PLAN);
        return execute(graph, ticket, operators);
    }

    /**
     * Runs the DSL example through the linter so readers can see why the graph shape is suspicious.
     */
    public static List<LintDiagnostic> lintDslExample() {
        return new LintRunner().lintSource(ExampleDslResources.readResource("/bloge/antipatterns/god-graph.bloge"));
    }

    private static GraphResult execute(Graph graph, TicketRequest ticket, Map<String, ?> operators) {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.executeWithOperators(graph, new GraphContext(Map.of("ticket", ticket)), operators);
    }

    public static void main(String[] args) {
        TicketRequest ticket = new TicketRequest("T-100", "vip-1", "Urgent outage in the refund flow", "apac");
        GraphResult bad = executeBadScenario(ticket);
        GraphResult good = executeComposedScenario(ticket);
        System.out.println("Bad graph plan: " + bad.getOutput("publishPlan", RoutingPlan.class));
        System.out.println("Refactored graph plan: " + good.getOutput("publishPlan", RoutingPlan.class));
        System.out.println("Lint diagnostics: " + lintDslExample().size());
    }
}
