package com.leanowtech.bloge.gateway.gateway;

import java.util.List;
import java.util.Map;

/**
 * Executable contract-test catalog for every built-in Resource Gateway graph.
 *
 * <p>Resource responses are supplied at the transport boundary so the production URL mapping,
 * response protocol, payload extraction, branch, retry, fallback, decision-table, and aggregation
 * behavior remains under test. The foreach example deliberately uses an empty outer collection
 * until nested invocation sites become addressable; its suite is tagged as exploratory instead of
 * creating false certification evidence.</p>
 */
final class BuiltInGatewayGraphContractTestSuites {

    private BuiltInGatewayGraphContractTestSuites() {
    }

    /**
     * Returns one stable, coverage-gated suite for each built-in graph.
     *
     * @return immutable built-in suite catalog
     */
    static List<GatewayGraphContractTestSuite> all() {
        return List.of(
                aiEnrichedSearch(),
                creditScore(),
                enrichOrderList(),
                loanDecisionPolicy(),
                productDetail(),
                resourceDispatch(),
                userDashboard());
    }

    private static GatewayGraphContractTestSuite aiEnrichedSearch() {
        GatewayGraphContractTestCase search = testCase(
                "materializes metadata tokens and citations",
                Map.of("query", "How does BLOGE stream graph output?"),
                List.of(),
                "assembleResult",
                exists("/meta"), exists("/tokens"), exists("/citations"));
        return suite(
                "ai-enriched-search-streams",
                "AI-enriched search streams",
                "Materializes all three independently streamed result channels.",
                List.of("built-in", "streaming", "ai", "certifiable"),
                "aiEnrichedSearch",
                List.of(search),
                policy(1, 1, 1, 0, 3, "assembleResult"));
    }

    private static GatewayGraphContractTestSuite creditScore() {
        GatewayGraphContractTestCase primary = testCase(
                "primary provider wins",
                Map.of("userId", "primary-user"),
                List.of(transport(
                        "credit-provider.primary",
                        Map.of("userId", "primary-user"),
                        "{\"score\":812,\"provider\":\"equifax\",\"riskBand\":\"LOW\"}")),
                "assemblePrimary",
                equal("/provider", "primary"), equal("/score/score", 812));
        GatewayGraphResourceMock unavailablePrimary = transport(
                "credit-provider.primary",
                Map.of("userId", "secondary-user"),
                "{\"errorMessage\":\"primary capacity exhausted\"}",
                503).expectingUses(2, 2);
        GatewayGraphContractTestCase secondary = testCase(
                "secondary provider serves after primary retry exhaustion",
                Map.of("userId", "secondary-user"),
                List.of(
                        unavailablePrimary,
                        transport(
                                "credit-provider.secondary",
                                Map.of("userId", "secondary-user"),
                                "{\"score\":740,\"provider\":\"transunion\",\"riskBand\":\"MEDIUM\"}")),
                "assembleSecondary",
                equal("/provider", "secondary"), equal("/score/score", 740));
        return suite(
                "credit-score-provider-routing",
                "Credit score provider routing",
                "Covers primary success and deterministic retry-to-secondary degradation.",
                List.of("built-in", "resource", "retry", "fallback", "branch", "certifiable"),
                "creditScore",
                List.of(primary, secondary),
                policy(2, 2, 2, 3, 4, "assemblePrimary", "assembleSecondary"));
    }

    private static GatewayGraphContractTestSuite enrichOrderList() {
        GatewayGraphContractTestCase emptyList = testCase(
                "empty order list completes without nested resource calls",
                Map.of("userId", "no-orders"),
                List.of(transport(
                        "order-service.listOrders",
                        Map.of("userId", "no-orders"),
                        "{\"success\":true,\"data\":{\"orders\":[]}}")),
                "collectEnriched",
                equal("/orders", List.of()));
        return suite(
                "enrich-order-list-outer-boundary",
                "Order enrichment outer boundary",
                "Exercises the foreach outer boundary without pretending nested invocation control exists.",
                List.of("built-in", "foreach", "resource", "exploratory", "nested-invocation-gap"),
                "enrichOrderList",
                List.of(emptyList),
                policy(1, 1, 1, 1, 1, "collectEnriched"));
    }

    private static GatewayGraphContractTestSuite loanDecisionPolicy() {
        GatewayGraphContractTestCase prime = testCase(
                "prime applicant approves through R1",
                Map.of("applicantId", "prime", "requestedAmount", 450_000.0),
                List.of(transport(
                        "loan-applicant-service.getProfile",
                        Map.of("applicantId", "prime"),
                        "{\"code\":0,\"message\":\"OK\",\"data\":{\"applicantId\":\"prime\",\"score\":780,\"segment\":\"private-bank\"}}")),
                "assembleLoanDecision",
                equal("/policy/ruleId", "R1"), equal("/policy/decision", "approved"));
        GatewayGraphContractTestCase declined = testCase(
                "declined applicant falls through R4",
                Map.of("applicantId", "decline", "requestedAmount", 120_000.0),
                List.of(transport(
                        "loan-applicant-service.getProfile",
                        Map.of("applicantId", "decline"),
                        "{\"code\":0,\"message\":\"OK\",\"data\":{\"applicantId\":\"decline\",\"score\":590,\"segment\":\"new\"}}")),
                "assembleLoanDecision",
                equal("/policy/ruleId", "R4"), equal("/policy/decision", "declined"));
        return suite(
                "loan-decision-policy-smoke",
                "Loan decision policy smoke",
                "Covers the prime approval and decline lanes through the real BodyCode protocol.",
                List.of("built-in", "loan", "decision-table", "resource", "certifiable"),
                "loanDecisionPolicy",
                List.of(prime, declined),
                policy(2, 2, 2, 2, 4, "assembleLoanDecision"));
    }

    private static GatewayGraphContractTestSuite productDetail() {
        GatewayGraphContractTestCase physical = testCase(
                "physical product receives shipping details",
                Map.of("productId", "physical-1"),
                List.of(
                        transport("catalog-service.getProduct", Map.of("productId", "physical-1"),
                                "{\"productId\":\"physical-1\",\"name\":\"Wireless Mouse\",\"price\":29.99,\"type\":\"physical\"}"),
                        transport("logistics-service.getShipping", Map.of("productId", "physical-1"),
                                "{\"shippable\":true,\"estimatedDays\":3,\"carrier\":\"FedEx\"}")),
                "assemblePhysical",
                equal("/productType", "physical"), equal("/shipping/estimatedDays", 3));
        GatewayGraphContractTestCase digital = testCase(
                "digital product receives license details",
                Map.of("productId", "digital-1"),
                List.of(
                        transport("catalog-service.getProduct", Map.of("productId", "digital-1"),
                                "{\"productId\":\"digital-1\",\"name\":\"Photo Editor Pro\",\"price\":79.0,\"type\":\"digital\"}"),
                        transport("license-service.getLicense", Map.of("productId", "digital-1"),
                                "{\"valid\":true,\"license\":{\"licenseType\":\"perpetual\",\"downloadUrl\":\"https://cdn.example.com/editor\"}}")),
                "assembleDigital",
                equal("/productType", "digital"), equal("/license/licenseType", "perpetual"));
        GatewayGraphContractTestCase generic = testCase(
                "unknown product type uses generic branch",
                Map.of("productId", "service-1"),
                List.of(transport("catalog-service.getProduct", Map.of("productId", "service-1"),
                        "{\"productId\":\"service-1\",\"name\":\"Consulting\",\"price\":500.0,\"type\":\"service\"}")),
                "assembleGeneric",
                equal("/productType", "generic"), equal("/product/productId", "service-1"));
        return suite(
                "product-detail-all-branches",
                "Product detail all branches",
                "Covers physical, digital, and generic conditional routes.",
                List.of("built-in", "resource", "branch", "certifiable"),
                "productDetail",
                List.of(physical, digital, generic),
                policy(3, 3, 3, 5, 6,
                        "assemblePhysical", "assembleDigital", "assembleGeneric"));
    }

    private static GatewayGraphContractTestSuite resourceDispatch() {
        GatewayGraphContractTestCase profile = testCase(
                "dispatches a BodyCode profile descriptor",
                Map.of("resourceId", "user-service.getProfile", "params", Map.of("userId", "dispatch-user")),
                List.of(transport(
                        "user-service.getProfile",
                        Map.of("userId", "dispatch-user"),
                        "{\"code\":0,\"message\":\"OK\",\"data\":{\"userId\":\"dispatch-user\",\"name\":\"Ada\",\"tier\":\"gold\"}}")),
                "executeResource",
                equal("/resourceId", "user-service.getProfile"),
                equal("/payload/name", "Ada"),
                equal("/success", true));
        GatewayGraphContractTestCase invoice = testCase(
                "dispatches a query-mapped invoice descriptor",
                Map.of("resourceId", "invoice-service.getInvoice", "params", Map.of("orderId", "order-42")),
                List.of(transport(
                        "invoice-service.getInvoice",
                        Map.of("orderId", "order-42"),
                        "{\"status\":\"OK\",\"invoice\":{\"invoiceId\":\"invoice-42\",\"amount\":42.5}}")),
                "executeResource",
                equal("/resourceId", "invoice-service.getInvoice"),
                equal("/payload/invoiceId", "invoice-42"),
                equal("/success", true));
        return suite(
                "resource-dispatch-descriptor-protocols",
                "Resource dispatch descriptor protocols",
                "Proves one generic graph can dispatch resources with different response protocols.",
                List.of("built-in", "resource", "dynamic-dispatch", "certifiable"),
                "resourceDispatch",
                List.of(profile, invoice),
                policy(2, 2, 2, 2, 6, "executeResource"));
    }

    private static GatewayGraphContractTestSuite userDashboard() {
        GatewayGraphContractTestCase happy = testCase(
                "all five dashboard resources succeed",
                Map.of("userId", "dashboard-user"),
                dashboardFixtures("dashboard-user", false),
                "assembleDashboard",
                equal("/profile/name", "Alice"),
                equal("/orders/orders/0/orderId", "order-1"),
                equal("/recommendations/entries/0", "rec-1"),
                equal("/wallet", 100.5),
                equal("/notifications/unread", 3));
        GatewayGraphContractTestCase degraded = testCase(
                "optional services degrade after bounded retries",
                Map.of("userId", "degraded-user"),
                dashboardFixtures("degraded-user", true),
                "assembleDashboard",
                equal("/profile/name", "Alice"),
                equal("/recommendations/entries", List.of()),
                equal("/wallet/balance", 0),
                equal("/notifications/unread", 0));
        return suite(
                "user-dashboard-happy-and-degraded",
                "User dashboard happy and degraded",
                "Covers parallel aggregation plus deterministic retry and fallback behavior.",
                List.of("built-in", "resource", "parallel", "retry", "fallback", "certifiable"),
                "userDashboard",
                List.of(happy, degraded),
                policy(2, 2, 2, 10, 9, "assembleDashboard"));
    }

    private static List<GatewayGraphResourceMock> dashboardFixtures(String userId, boolean degraded) {
        GatewayGraphResourceMock recommendations = degraded
                ? transport("recommendation-service.forUser", Map.of("userId", userId),
                        "{\"error\":\"recommendations unavailable\"}", 503)
                : transport("recommendation-service.forUser", Map.of("userId", userId),
                        "{\"entries\":[\"rec-1\",\"rec-2\"]}");
        GatewayGraphResourceMock wallet = degraded
                ? transport("wallet-service.getBalance", Map.of("userId", userId),
                        "{\"error\":\"wallet unavailable\"}", 503).expectingUses(2, 2)
                : transport("wallet-service.getBalance", Map.of("userId", userId),
                        "{\"balance\":100.5,\"currency\":\"USD\"}");
        GatewayGraphResourceMock notifications = degraded
                ? transport("notification-service.unread", Map.of("userId", userId),
                        "{\"error\":\"notifications unavailable\"}", 503).expectingUses(3, 3)
                : transport("notification-service.unread", Map.of("userId", userId),
                        "{\"unread\":3,\"entries\":[\"notification-1\"]}");
        return List.of(
                transport("user-service.getProfile", Map.of("userId", userId),
                        "{\"code\":0,\"message\":\"OK\",\"data\":{\"userId\":\"" + userId
                                + "\",\"name\":\"Alice\",\"tier\":\"premium\"}}"),
                transport("order-service.listOrders", Map.of("userId", userId),
                        "{\"success\":true,\"data\":{\"orders\":[{\"orderId\":\"order-1\",\"amount\":29.99}]}}"),
                recommendations,
                wallet,
                notifications);
    }

    private static GatewayGraphContractTestSuite suite(String suiteId,
                                                       String displayName,
                                                       String description,
                                                       List<String> tags,
                                                       String graphName,
                                                       List<GatewayGraphContractTestCase> cases,
                                                       GatewayGraphContractTestCoveragePolicy policy) {
        return new GatewayGraphContractTestSuite(suiteId, displayName, description, tags,
                new GatewayGraphContractTestSuiteRequest(graphName, cases), policy);
    }

    private static GatewayGraphContractTestCase testCase(String name,
                                                        Map<String, Object> context,
                                                        List<GatewayGraphResourceMock> mocks,
                                                        String outputNode,
                                                        GatewayGraphTestAssertion... assertions) {
        return new GatewayGraphContractTestCase(name, context, mocks, outputNode, List.of(assertions));
    }

    private static GatewayGraphContractTestCoveragePolicy policy(int cases,
                                                                 int inputSchemas,
                                                                 int outputSchemas,
                                                                 int resourceCalls,
                                                                 int assertions,
                                                                 String... outputNodes) {
        return new GatewayGraphContractTestCoveragePolicy(cases, inputSchemas, outputSchemas,
                resourceCalls, assertions, List.of(outputNodes));
    }

    private static GatewayGraphResourceMock transport(String resourceId,
                                                      Map<String, Object> expectedParams,
                                                      String rawBody) {
        return transport(resourceId, expectedParams, rawBody, 200);
    }

    private static GatewayGraphResourceMock transport(String resourceId,
                                                      Map<String, Object> expectedParams,
                                                      String rawBody,
                                                      int statusCode) {
        return GatewayGraphResourceMock.transportResponse(
                resourceId, expectedParams, rawBody, statusCode, Map.of(), true);
    }

    private static GatewayGraphTestAssertion equal(String path, Object value) {
        return new GatewayGraphTestAssertion(GatewayGraphTestAssertion.Mode.PATH_EQUALS, path, value);
    }

    private static GatewayGraphTestAssertion exists(String path) {
        return new GatewayGraphTestAssertion(GatewayGraphTestAssertion.Mode.PATH_EXISTS, path, null);
    }
}
