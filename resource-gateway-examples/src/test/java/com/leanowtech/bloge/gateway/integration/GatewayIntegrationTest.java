package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.gateway.GatewayProperties;
import com.leanowtech.bloge.gateway.gateway.GatewayResponse;
import com.leanowtech.bloge.gateway.gateway.ResourceDescriptorBootstrap;
import com.leanowtech.bloge.gateway.gateway.UserDashboardController;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.operator.PayloadExtractor;
import com.leanowtech.bloge.gateway.operator.ResponseValidator;
import com.leanowtech.bloge.gateway.operator.UrlTemplateRenderer;
import com.leanowtech.bloge.gateway.resource.DatabaseResourceRegistry;
import com.leanowtech.bloge.operators.http.HttpRequestOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 4 integration tests — manually wired context with WireMock backing all
 * upstream HTTP services.  Exercises the real execution path end-to-end:
 * {@code UserDashboardController} → {@code GatewayGraphService} → {@code GraphEngine}
 * → {@code HttpResourceOperator} → {@code HttpRequestOperator} → WireMock.
 *
 * <p>Uses manual bean wiring instead of {@code @SpringBootTest} to keep the test focused on
 * the real controller/graph/operator path without pulling in unrelated Spring Boot
 * auto-configuration for this standalone example module.
 */
class GatewayIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static ObjectMapper objectMapper;
    private static UserDashboardController controller;

    @BeforeAll
    static void wireContext() throws Exception {
        String baseUrl = "http://localhost:" + wireMock.getPort();

        // ── Serialization ───────────────────────────────────────────────
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // ── Expression evaluator (no-arg; self-contained) ───────────────
        var evaluator = new BlgeExpressionEvaluator();

        // ── H2 in-memory DataSource + registry ──────────────────────────
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:gateway-integration;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS resource_descriptors (
                    resource_id VARCHAR(255) PRIMARY KEY,
                    descriptor_json CLOB NOT NULL
                )""");
        var registry = new DatabaseResourceRegistry(jdbc, objectMapper, evaluator);

        // ── Seed descriptors with WireMock base URL ─────────────────────
        var props = new GatewayProperties();
        props.setBaseUrl(baseUrl);
        var bootstrap = new ResourceDescriptorBootstrap(registry, props);
        bootstrap.seedDescriptors();

        // ── Operator stack ──────────────────────────────────────────────
        var renderer = new UrlTemplateRenderer();
        var extractor = new PayloadExtractor();
        var validator = new ResponseValidator(evaluator);
        var httpOp = new HttpRequestOperator();
        var resourceOp = new HttpResourceOperator(
                httpOp, registry, evaluator, renderer, extractor, validator);

        // ── Operator registry + graph compilation ───────────────────────
        var opRegistry = new DefaultOperatorRegistry();
        opRegistry.register("httpResource", resourceOp);

        var loader = new GraphLoader(opRegistry);
        List<Graph> graphs = new ArrayList<>();
        for (String name : List.of("user-dashboard", "product-detail",
                "enrich-order-list", "credit-score")) {
            try (InputStream is = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream("bloge/gateway/" + name + ".bloge")) {
                if (is == null) throw new IOException(name + ".bloge not found on classpath");
                graphs.add(loader.load(new String(is.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }

        // ── Engine + service + controller ───────────────────────────────
        var engine = GraphEngine.builder().registry(opRegistry).build();
        var graphService = new GatewayGraphService(engine, graphs);
        controller = new UserDashboardController(graphService);
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    // ── Fixture loading ─────────────────────────────────────────────────

    private static String fixture(String name) {
        try (InputStream is = GatewayIntegrationTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (is == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ── userDashboard: happy path ───────────────────────────────────────

    @Test
    void dashboard_happyPath_aggregatesAllFiveUpstreams() throws Exception {
        stubAllDashboardUpstreams();

        ResponseEntity<GatewayResponse> response = controller.dashboard("u1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        GatewayResponse gw = response.getBody();
        assertThat(gw).isNotNull();
        assertThat(gw.success()).isTrue();

        JsonNode data = objectMapper.valueToTree(gw.data());
        // Profile: BodyCode protocol, payloadPath="data" → inner data object
        assertThat(data.get("profile").get("name").asText()).isEqualTo("Alice");
        assertThat(data.get("profile").get("tier").asText()).isEqualTo("premium");
        // Orders: BodyFlag protocol, payloadPath="data" → {orders: [...]}
        assertThat(data.get("orders").get("orders")).hasSize(2);
        // Recommendations: HttpStatus protocol, payloadPath=null → full body
        assertThat(data.get("recommendations").get("entries")).hasSize(2);
        // Wallet: StatusCodes protocol, payloadPath="balance" → extracted number
        assertThat(data.get("wallet").asDouble()).isEqualTo(100.5);
        // Notifications: HttpStatus protocol, payloadPath=null → full body
        assertThat(data.get("notifications").get("unread").asInt()).isEqualTo(3);
    }

    // ── userDashboard: partial degradation ──────────────────────────────

    @Test
    void dashboard_partialDegradation_returnsFallbacksForFailedUpstreams() throws Exception {
        stubProfileAndOrders();
        // Recommendations service down → fallback {entries: []}
        wireMock.stubFor(get(urlPathEqualTo("/api/recommendations/u1"))
                .willReturn(serverError()));
        // Wallet service down → retry once then fallback {balance: 0, currency: "USD"}
        wireMock.stubFor(get(urlPathEqualTo("/api/wallet/u1/balance"))
                .willReturn(serverError()));
        wireMock.stubFor(get(urlPathEqualTo("/api/notifications/u1/unread"))
                .willReturn(okJson("{\"unread\":5,\"entries\":[]}")));

        ResponseEntity<GatewayResponse> response = controller.dashboard("u1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        GatewayResponse gw = response.getBody();
        assertThat(gw).isNotNull();
        assertThat(gw.success()).isTrue();

        JsonNode data = objectMapper.valueToTree(gw.data());
        // Successful upstreams remain unaffected
        assertThat(data.get("profile").get("name").asText()).isEqualTo("Alice");
        assertThat(data.get("orders").get("orders")).hasSize(2);
        assertThat(data.get("notifications").get("unread").asInt()).isEqualTo(5);
        // Fallback value from the .bloge definition
        assertThat(data.get("recommendations").get("entries")).isEmpty();
        // Wallet fallback produces a map (unlike the happy-path extracted number)
        assertThat(data.get("wallet")).isNotNull();
    }

    // ── productDetail: physical branch ──────────────────────────────────

    @Test
    void productDetail_physicalProduct_enrichesWithShipping() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/products/p1"))
                .willReturn(okJson(fixture("product-physical.json"))));
        wireMock.stubFor(get(urlPathEqualTo("/api/shipping/p1"))
                .willReturn(okJson(fixture("shipping-info.json"))));

        ResponseEntity<GatewayResponse> response = controller.productDetail("p1");

        GatewayResponse gw = response.getBody();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(gw).isNotNull();
        assertThat(gw.success()).isTrue();

        // Falls through to assemblePhysical output (unifyDetail is cancelled by branch routing)
        JsonNode data = objectMapper.valueToTree(gw.data());
        assertThat(data.get("productType").asText()).isEqualTo("physical");
        assertThat(data.get("product").get("name").asText()).isEqualTo("Wireless Mouse");
        assertThat(data.get("product").get("price").asDouble()).isEqualTo(29.99);
        assertThat(data.get("shipping").get("estimatedDays").asInt()).isEqualTo(3);
        assertThat(data.get("shipping").get("carrier").asText()).isEqualTo("FedEx");
    }

    // ── productDetail: digital branch ───────────────────────────────────

    @Test
    void productDetail_digitalProduct_enrichesWithLicense() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/products/p2"))
                .willReturn(okJson(fixture("product-digital.json"))));
        wireMock.stubFor(get(urlPathEqualTo("/api/licenses/p2"))
                .willReturn(okJson(fixture("license-info.json"))));

        ResponseEntity<GatewayResponse> response = controller.productDetail("p2");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        GatewayResponse gw = response.getBody();
        assertThat(gw).isNotNull();
        assertThat(gw.success()).isTrue();

        JsonNode data = objectMapper.valueToTree(gw.data());
        assertThat(data.get("productType").asText()).isEqualTo("digital");
        assertThat(data.get("product").get("name").asText()).isEqualTo("Photo Editor Pro");
        assertThat(data.get("license").get("licenseType").asText()).isEqualTo("perpetual");
        assertThat(data.get("license").get("downloadUrl").asText())
                .contains("cdn.example.com");
    }

    // ── enrichOrderList: foreach enrichment ──────────────────────────────

    @Test
    void enrichOrderList_enrichesEachOrderWithShippingAndInvoice() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/orders"))
                .withQueryParam("userId", equalTo("u1"))
                .willReturn(okJson(fixture("order-list-success.json"))));

        // Shipping stubs per order (logistics path expression: productId ?? orderId)
        wireMock.stubFor(get(urlPathEqualTo("/api/shipping/ord-1"))
                .willReturn(okJson("{\"status\":\"shipped\",\"trackingNumber\":\"TRK-001\"}")));
        wireMock.stubFor(get(urlPathEqualTo("/api/shipping/ord-2"))
                .willReturn(okJson("{\"status\":\"processing\"}")));

        // Invoice stubs per order (BodyCode: status=OK, payloadPath="invoice")
        wireMock.stubFor(get(urlPathEqualTo("/api/invoices"))
                .withQueryParam("orderId", equalTo("ord-1"))
                .willReturn(okJson(
                        "{\"status\":\"OK\",\"invoice\":{\"invoiceId\":\"inv-1\",\"amount\":29.99}}")));
        wireMock.stubFor(get(urlPathEqualTo("/api/invoices"))
                .withQueryParam("orderId", equalTo("ord-2"))
                .willReturn(okJson(
                        "{\"status\":\"OK\",\"invoice\":{\"invoiceId\":\"inv-2\",\"amount\":59.00}}")));

        ResponseEntity<GatewayResponse> response = controller.enrichedOrders("u1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        GatewayResponse gw = response.getBody();
        assertThat(gw).isNotNull();
        assertThat(gw.success()).isTrue();

        JsonNode data = objectMapper.valueToTree(gw.data());
        JsonNode orders = data.get("orders");
        assertThat(orders).hasSize(2);

        // Foreach wraps each iteration's output under the terminal node name
        for (JsonNode entry : orders) {
            JsonNode enriched = entry.get("assembleEnriched");
            assertThat(enriched).as("assembleEnriched wrapper").isNotNull();
            assertThat(enriched.has("orderData")).isTrue();
            assertThat(enriched.has("shipping")).isTrue();
            assertThat(enriched.has("invoice")).isTrue();
            assertThat(enriched.get("orderData").has("orderId")).isTrue();
        }
    }

    // ── creditScore: secondary fallback when primary fails ──────────────

    @Test
    void creditScore_fallsBackToSecondaryWhenPrimaryFails() throws Exception {
        // Primary returns 500 → BlgeExpression fails → retry → fallback → branch to secondary
        wireMock.stubFor(get(urlPathEqualTo("/api/credit/primary/u1"))
                .willReturn(serverError()));
        wireMock.stubFor(get(urlPathEqualTo("/api/credit/secondary/u1"))
                .willReturn(okJson(fixture("credit-score-secondary.json"))));

        ResponseEntity<GatewayResponse> response = controller.creditScore("u1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        GatewayResponse gw = response.getBody();
        assertThat(gw).isNotNull();
        assertThat(gw.success()).isTrue();

        // Falls through to assembleSecondary output (assembleResult cancelled by branch routing)
        JsonNode data = objectMapper.valueToTree(gw.data());
        assertThat(data.get("provider").asText()).isEqualTo("secondary");
        assertThat(data.get("score").get("score").asInt()).isEqualTo(740);
        assertThat(data.get("score").get("provider").asText()).isEqualTo("transunion");
    }

    // ── Shared stub helpers ─────────────────────────────────────────────

    private void stubAllDashboardUpstreams() {
        stubProfileAndOrders();
        wireMock.stubFor(get(urlPathEqualTo("/api/recommendations/u1"))
                .willReturn(okJson("{\"entries\":[\"rec-1\",\"rec-2\"]}")));
        wireMock.stubFor(get(urlPathEqualTo("/api/wallet/u1/balance"))
                .willReturn(okJson("{\"balance\":100.50,\"currency\":\"USD\"}")));
        wireMock.stubFor(get(urlPathEqualTo("/api/notifications/u1/unread"))
                .willReturn(okJson("{\"unread\":3,\"entries\":[\"n1\"]}")));
    }

    private void stubProfileAndOrders() {
        wireMock.stubFor(get(urlPathEqualTo("/api/users/u1/profile"))
                .willReturn(okJson(fixture("user-profile-success.json"))));
        wireMock.stubFor(get(urlPathEqualTo("/api/orders"))
                .withQueryParam("userId", equalTo("u1"))
                .willReturn(okJson(fixture("order-list-success.json"))));
    }
}
