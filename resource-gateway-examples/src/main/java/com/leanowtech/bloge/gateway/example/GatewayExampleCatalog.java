package com.leanowtech.bloge.gateway.example;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory catalog of the resource-gateway examples shown by the browser UI.
 *
 * <p>The catalog deliberately mirrors the six DSL graphs that ship under
 * {@code src/main/resources/bloge/gateway}. It keeps scenario copy, sample
 * inputs, endpoint recipes, and seeded diagram layouts together so the README,
 * tests, and UI do not drift into separate interpretations of the examples.</p>
 */
@Component
public class GatewayExampleCatalog {

    private static final double W = 184;
    private static final double H = 76;

    private final Map<String, ScenarioEntry> scenarios;

    /**
     * Creates the built-in example catalog.
     */
    public GatewayExampleCatalog() {
        Map<String, ScenarioEntry> entries = new LinkedHashMap<>();
        add(entries, userDashboard());
        add(entries, productDetail());
        add(entries, enrichOrderList());
        add(entries, creditScore());
        add(entries, resourceDispatch());
        add(entries, aiEnrichedSearch());
        this.scenarios = Collections.unmodifiableMap(entries);
    }

    /**
     * Lists public scenario metadata in showcase order.
     *
     * @return immutable scenario list
     */
    public List<GatewayExampleScenario> scenarios() {
        return scenarios.values().stream()
                .map(ScenarioEntry::scenario)
                .toList();
    }

    /**
     * Looks up one scenario by graph name.
     *
     * @param graphName graph name such as {@code userDashboard}
     * @return matching scenario metadata
     */
    public Optional<GatewayExampleScenario> scenario(String graphName) {
        return Optional.ofNullable(scenarios.get(graphName)).map(ScenarioEntry::scenario);
    }

    /**
     * Looks up the visual layout for one scenario.
     *
     * @param graphName graph name such as {@code userDashboard}
     * @return matching visual layout
     */
    public Optional<ExampleVisualLayout> diagram(String graphName) {
        return Optional.ofNullable(scenarios.get(graphName)).map(ScenarioEntry::layout);
    }

    private void add(Map<String, ScenarioEntry> entries, ScenarioEntry entry) {
        entries.put(entry.scenario().graphName(), entry);
    }

    private ScenarioEntry userDashboard() {
        String graph = "userDashboard";
        return entry(
                new GatewayExampleScenario(
                        graph,
                        "User Dashboard",
                        "user-dashboard.bloge",
                        "Parallel fan-out aggregation",
                        "Fetches five independent user-facing resources concurrently, then assembles a dashboard response.",
                        List.of("parallel fan-out", "httpResource", "timeout", "retry", "fallback", "aggregation"),
                        Map.of("userId", "u1"),
                        new GatewayExampleRun("request", "GET", "/api/gateway/dashboard/{userId}", Map.of(), Map.of()),
                        null
                ),
                layout(graph,
                        List.of(
                                node("fetchProfile", "resource", "httpResource", "Profile", 80, 80, "parallelFetch",
                                        Map.of("resourceId", "user-service.getProfile", "timeout", "3s", "retryAttempts", 1)),
                                node("fetchOrders", "resource", "httpResource", "Orders", 80, 190, "parallelFetch",
                                        Map.of("resourceId", "order-service.listOrders", "timeout", "3s", "fallback", true)),
                                node("fetchRecommendations", "resource", "httpResource", "Recommendations", 80, 300, "parallelFetch",
                                        Map.of("resourceId", "recommendation-service.forUser", "timeout", "2s", "fallback", true)),
                                node("fetchWallet", "resource", "httpResource", "Wallet", 80, 410, "parallelFetch",
                                        Map.of("resourceId", "wallet-service.getBalance", "timeout", "2s", "retryAttempts", 1, "fallback", true)),
                                node("fetchNotifications", "resource", "httpResource", "Notifications", 80, 520, "parallelFetch",
                                        Map.of("resourceId", "notification-service.unread", "timeout", "2s", "retryAttempts", 2, "fallback", true)),
                                node("assembleDashboard", "transform", null, "Assemble Dashboard", 420, 300, null, Map.of())
                        ),
                        edges(
                                edge("fetchProfile", "assembleDashboard", "profile"),
                                edge("fetchOrders", "assembleDashboard", "orders"),
                                edge("fetchRecommendations", "assembleDashboard", "recommendations"),
                                edge("fetchWallet", "assembleDashboard", "wallet"),
                                edge("fetchNotifications", "assembleDashboard", "notifications")
                        ),
                        List.of(new ExampleVisualLayout.Group("parallelFetch", "Parallel API fan-out", "parallel"))
                )
        );
    }

    private ScenarioEntry productDetail() {
        String graph = "productDetail";
        return entry(
                new GatewayExampleScenario(
                        graph,
                        "Product Detail",
                        "product-detail.bloge",
                        "Conditional branch enrichment",
                        "Loads a base product and routes to physical, digital, or generic enrichment before unifying the response.",
                        List.of("conditional branch", "branch fallback", "resource descriptor", "unified response"),
                        Map.of("productId", "p1"),
                        new GatewayExampleRun("request", "GET", "/api/gateway/products/{productId}", Map.of(), Map.of()),
                        null
                ),
                layout(graph,
                        List.of(
                                node("fetchProduct", "resource", "httpResource", "Base Product", 80, 240, null,
                                        Map.of("resourceId", "catalog-service.getProduct", "timeout", "3s", "retryAttempts", 1)),
                                node("fetchShippingInfo", "resource", "httpResource", "Shipping Info", 360, 90, "physicalBranch",
                                        Map.of("resourceId", "logistics-service.getShipping", "fallback", true)),
                                node("assemblePhysical", "transform", null, "Assemble Physical", 620, 90, "physicalBranch", Map.of()),
                                node("fetchLicenseInfo", "resource", "httpResource", "License Info", 360, 240, "digitalBranch",
                                        Map.of("resourceId", "license-service.getLicense", "fallback", true)),
                                node("assembleDigital", "transform", null, "Assemble Digital", 620, 240, "digitalBranch", Map.of()),
                                node("assembleGeneric", "transform", null, "Assemble Generic", 360, 390, "genericBranch", Map.of()),
                                node("unifyDetail", "transform", null, "Unify Detail", 880, 240, null, Map.of())
                        ),
                        edges(
                                edge("fetchProduct", "fetchShippingInfo", "physical"),
                                edge("fetchProduct", "fetchLicenseInfo", "digital"),
                                edge("fetchProduct", "assembleGeneric", "otherwise"),
                                edge("fetchShippingInfo", "assemblePhysical", "shipping"),
                                edge("fetchLicenseInfo", "assembleDigital", "license"),
                                edge("assemblePhysical", "unifyDetail", "physical"),
                                edge("assembleDigital", "unifyDetail", "digital"),
                                edge("assembleGeneric", "unifyDetail", "generic")
                        ),
                        List.of(
                                new ExampleVisualLayout.Group("physicalBranch", "Physical branch", "branch"),
                                new ExampleVisualLayout.Group("digitalBranch", "Digital branch", "branch"),
                                new ExampleVisualLayout.Group("genericBranch", "Generic branch", "branch")
                        )
                )
        );
    }

    private ScenarioEntry enrichOrderList() {
        String graph = "enrichOrderList";
        return entry(
                new GatewayExampleScenario(
                        graph,
                        "Enrich Order List",
                        "enrich-order-list.bloge",
                        "Foreach enrichment",
                        "Loads orders once, then enriches every order with shipping and invoice data inside a parallel foreach scope.",
                        List.of("foreach", "per-item fallback", "parallel enrichment", "collection transform"),
                        Map.of("userId", "u1"),
                        new GatewayExampleRun("request", "GET", "/api/gateway/orders/{userId}/enriched", Map.of(), Map.of()),
                        null
                ),
                layout(graph,
                        List.of(
                                node("fetchOrderList", "resource", "httpResource", "Fetch Orders", 80, 260, null,
                                        Map.of("resourceId", "order-service.listOrders", "timeout", "4s", "retryAttempts", 1)),
                                node("enrichOrders", "foreach", null, "For Each Order", 320, 260, "foreachEnrich", Map.of("mode", "parallel")),
                                node("fetchShippingStatus", "resource", "httpResource", "Shipping Status", 560, 160, "foreachEnrich",
                                        Map.of("resourceId", "logistics-service.getShipping", "fallback", true)),
                                node("fetchInvoice", "resource", "httpResource", "Invoice", 560, 360, "foreachEnrich",
                                        Map.of("resourceId", "invoice-service.getInvoice", "fallback", true)),
                                node("assembleEnriched", "transform", null, "Assemble Enriched", 820, 260, "foreachEnrich", Map.of()),
                                node("collectEnriched", "transform", null, "Collect Orders", 1080, 260, null, Map.of())
                        ),
                        edges(
                                edge("fetchOrderList", "enrichOrders", "orders"),
                                edge("enrichOrders", "fetchShippingStatus", "order"),
                                edge("enrichOrders", "fetchInvoice", "order"),
                                edge("fetchShippingStatus", "assembleEnriched", "shipping"),
                                edge("fetchInvoice", "assembleEnriched", "invoice"),
                                edge("assembleEnriched", "collectEnriched", "items")
                        ),
                        List.of(new ExampleVisualLayout.Group("foreachEnrich", "Foreach enrichment scope", "foreach"))
                )
        );
    }

    private ScenarioEntry creditScore() {
        String graph = "creditScore";
        return entry(
                new GatewayExampleScenario(
                        graph,
                        "Credit Score",
                        "credit-score.bloge",
                        "Provider degradation",
                        "Tries the primary credit provider first and falls back to a secondary provider when the primary path fails.",
                        List.of("degradation", "fallback", "branch on success", "provider provenance"),
                        Map.of("userId", "u1"),
                        new GatewayExampleRun("request", "GET", "/api/gateway/credit-score/{userId}", Map.of(), Map.of()),
                        null
                ),
                layout(graph,
                        List.of(
                                node("primaryCreditProvider", "resource", "httpResource", "Primary Provider", 80, 210, null,
                                        Map.of("resourceId", "credit-provider.primary", "timeout", "3s", "retryAttempts", 1, "fallback", true)),
                                node("assemblePrimary", "transform", null, "Assemble Primary", 360, 120, "primaryPath", Map.of("provider", "primary")),
                                node("secondaryCreditProvider", "resource", "httpResource", "Secondary Provider", 360, 300, "secondaryPath",
                                        Map.of("resourceId", "credit-provider.secondary", "timeout", "3s", "retryAttempts", 1)),
                                node("assembleSecondary", "transform", null, "Assemble Secondary", 640, 300, "secondaryPath", Map.of("provider", "secondary")),
                                node("assembleResult", "transform", null, "Assemble Result", 920, 210, null, Map.of())
                        ),
                        edges(
                                edge("primaryCreditProvider", "assemblePrimary", "success"),
                                edge("primaryCreditProvider", "secondaryCreditProvider", "fallback"),
                                edge("secondaryCreditProvider", "assembleSecondary", "secondary"),
                                edge("assemblePrimary", "assembleResult", "primary"),
                                edge("assembleSecondary", "assembleResult", "secondary")
                        ),
                        List.of(
                                new ExampleVisualLayout.Group("primaryPath", "Primary success path", "branch"),
                                new ExampleVisualLayout.Group("secondaryPath", "Secondary fallback path", "degradation")
                        )
                )
        );
    }

    private ScenarioEntry resourceDispatch() {
        String graph = "resourceDispatch";
        return entry(
                new GatewayExampleScenario(
                        graph,
                        "Resource Dispatch",
                        "resource-dispatch.bloge",
                        "Generic descriptor-backed execution",
                        "Executes any registered resource by resourceId through the generic httpResource operator.",
                        List.of("descriptor registry", "parameter mapping", "header override", "response protocol"),
                        Map.of("resourceId", "user-service.getProfile", "userId", "u1"),
                        new GatewayExampleRun(
                                "post",
                                "POST",
                                "/api/gateway/resources/execute",
                                Map.of(
                                        "resourceId", "{resourceId}",
                                        "params", Map.of("userId", "{userId}"),
                                        "headerOverrides", Map.of("Accept", "application/json")
                                ),
                                Map.of("Content-Type", "application/json", "X-Tenant-Id", "demo-tenant", "X-Namespace", "local")
                        ),
                        null
                ),
                layout(graph,
                        List.of(
                                node("executeResource", "resource", "httpResource", "Execute Resource", 120, 120, null,
                                        Map.of("resourceId", "ctx.resourceId", "params", "ctx.params", "descriptor", true))
                        ),
                        List.of(),
                        List.of()
                )
        );
    }

    private ScenarioEntry aiEnrichedSearch() {
        String graph = "aiEnrichedSearch";
        return entry(
                new GatewayExampleScenario(
                        graph,
                        "AI Enriched Search",
                        "ai-enriched-search.bloge",
                        "Mixed streaming fan-in",
                        "Runs metadata, token, and citation streams in parallel and routes each stream to a separate SSE event lane.",
                        List.of("stream node", "SSE", "parallel stream fan-in", "citation lane"),
                        Map.of("query", "hello"),
                        new GatewayExampleRun("stream", "GET", "/api/gateway/ai/search/stream?q={query}", Map.of(), Map.of()),
                        null
                ),
                layout(graph,
                        List.of(
                                node("metaStream", "stream", "MockMetaStreamingOperator", "Metadata Stream", 80, 100, "streamFanout",
                                        Map.of("event", "meta", "buffer", 8)),
                                node("llmStream", "stream", "MockLlmTokenStreamingOperator", "LLM Tokens", 80, 240, "streamFanout",
                                        Map.of("event", "token", "buffer", 32)),
                                node("citationStream", "stream", "MockCitationStreamingOperator", "Citations", 80, 380, "streamFanout",
                                        Map.of("event", "citation", "buffer", 8)),
                                node("assembleResult", "transform", null, "Assemble Result", 420, 240, null, Map.of())
                        ),
                        edges(
                                edge("metaStream", "assembleResult", "meta"),
                                edge("llmStream", "assembleResult", "tokens"),
                                edge("citationStream", "assembleResult", "citations")
                        ),
                        List.of(new ExampleVisualLayout.Group("streamFanout", "Parallel stream lanes", "stream"))
                )
        );
    }

    private ScenarioEntry entry(GatewayExampleScenario scenario, ExampleVisualLayout layout) {
        return new ScenarioEntry(scenario, layout);
    }

    private ExampleVisualLayout layout(String rootId,
                                       List<ExampleVisualLayout.Node> nodes,
                                       List<ExampleVisualLayout.Edge> edges,
                                       List<ExampleVisualLayout.Group> groups) {
        return new ExampleVisualLayout(
                ExampleVisualLayout.SCHEMA_VERSION,
                rootId,
                "GRAPH",
                nodes,
                edges,
                groups,
                new ExampleVisualLayout.Viewport(0, 0, 1)
        );
    }

    private ExampleVisualLayout.Node node(String id,
                                          String kind,
                                          String operatorRef,
                                          String label,
                                          double x,
                                          double y,
                                          String group,
                                          Map<String, Object> annotations) {
        return new ExampleVisualLayout.Node(
                id,
                kind,
                operatorRef,
                label,
                new ExampleVisualLayout.Position(x, y),
                new ExampleVisualLayout.Size(W, H),
                group,
                annotations
        );
    }

    private ExampleVisualLayout.Edge edge(String source, String target, String label) {
        return new ExampleVisualLayout.Edge(source + "->" + target + ":" + label, source, target, label);
    }

    private List<ExampleVisualLayout.Edge> edges(ExampleVisualLayout.Edge... edges) {
        List<ExampleVisualLayout.Edge> result = new ArrayList<>();
        Collections.addAll(result, edges);
        return List.copyOf(result);
    }

    private record ScenarioEntry(GatewayExampleScenario scenario, ExampleVisualLayout layout) {
    }
}
