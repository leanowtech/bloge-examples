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
import com.leanowtech.bloge.gateway.gateway.ResourceExecuteController;
import com.leanowtech.bloge.gateway.gateway.ResourceExecuteRequest;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.operator.PayloadExtractor;
import com.leanowtech.bloge.gateway.operator.ResponseValidator;
import com.leanowtech.bloge.gateway.operator.UrlTemplateRenderer;
import com.leanowtech.bloge.gateway.resource.DatabaseResourceRegistry;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
import com.leanowtech.bloge.operators.http.HttpRequestOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the unified resource-execution endpoint.
 *
 * <p>Uses the same manual-wiring approach as {@link GatewayIntegrationTest} — no Spring
 * context — to exercise the real execution path:
 * {@code ResourceExecuteController} → {@code GatewayGraphService} →
 * {@code GraphEngine} → {@code HttpResourceOperator} → WireMock.
 */
class ResourceExecuteIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static ObjectMapper objectMapper;
    private static ResourceExecuteController controller;

    @BeforeAll
    static void wireContext() throws Exception {
        String baseUrl = "http://localhost:" + wireMock.getPort();

        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        var evaluator = new BlgeExpressionEvaluator();

        // H2 in-memory registry
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:execute-integration;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS resource_descriptors (
                    resource_id VARCHAR(255) PRIMARY KEY,
                    descriptor_json CLOB NOT NULL
                )""");
        var registry = new DatabaseResourceRegistry(jdbc, objectMapper, evaluator);

        // Seed descriptors
        var props = new GatewayProperties();
        props.setBaseUrl(baseUrl);
        var bootstrap = new ResourceDescriptorBootstrap(registry, props);
        bootstrap.seedDescriptors();

        // Operator stack
        var renderer = new UrlTemplateRenderer();
        var extractor = new PayloadExtractor();
        var validator = new ResponseValidator(evaluator);
        var httpOp = new HttpRequestOperator();
        var resourceOp = new HttpResourceOperator(
                httpOp, registry, evaluator, renderer, extractor, validator);

        // Compile the resource-dispatch graph
        var opRegistry = new DefaultOperatorRegistry();
        opRegistry.register("httpResource", resourceOp);

        var loader = new GraphLoader(opRegistry);
        List<Graph> graphs = new ArrayList<>();
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("bloge/gateway/resource-dispatch.bloge")) {
            if (is == null) throw new IOException("resource-dispatch.bloge not found on classpath");
            graphs.add(loader.load(new String(is.readAllBytes(), StandardCharsets.UTF_8)));
        }

        var engine = GraphEngine.builder().registry(opRegistry).build();
        var graphService = new GatewayGraphService(engine, graphs);
        controller = new ResourceExecuteController(graphService);
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    // ── Successful execution by resourceId ──────────────────────────────

    @Test
    @DisplayName("execute resolves resource from registry and returns execution envelope")
    void execute_happyPath_returnsExecutionEnvelope() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/users/u42/profile"))
                .willReturn(okJson(fixture("user-profile-success.json"))));

        var request = new ResourceExecuteRequest(
                "user-service.getProfile",
                Map.of("userId", "u42"),
                Map.of(),
                null,
                null
        );

        ResponseEntity<GatewayResponse> response =
                controller.execute(request, "tenant-X", "ns-prod", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        GatewayResponse gw = response.getBody();
        assertThat(gw).isNotNull();
        assertThat(gw.success()).isTrue();

        JsonNode data = objectMapper.valueToTree(gw.data());
        assertThat(data.get("resourceId").asText()).isEqualTo("user-service.getProfile");
        assertThat(data.get("statusCode").asInt()).isEqualTo(200);
        assertThat(data.get("success").asBoolean()).isTrue();
        // user-service.getProfile uses BodyCode protocol with payloadPath="data"
        assertThat(data.get("payload").get("name").asText()).isEqualTo("Alice");
        assertThat(data.get("payload").get("tier").asText()).isEqualTo("premium");
    }

    // ── Tenant/namespace propagation ────────────────────────────────────

    @Test
    @DisplayName("tenant and namespace headers are propagated to outgoing request")
    void execute_propagatesTenantAndNamespace() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/users/u1/profile"))
                .willReturn(okJson(fixture("user-profile-success.json"))));

        var request = new ResourceExecuteRequest(
                "user-service.getProfile",
                Map.of("userId", "u1"),
                Map.of(),
                null,
                null
        );

        controller.execute(request, "acme-corp", "staging", null);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/users/u1/profile"))
                .withHeader("X-Tenant-Id", equalTo("acme-corp"))
                .withHeader("X-Namespace", equalTo("staging")));
    }

    // ── Forwarded Authorization header ──────────────────────────────────

    @Test
    @DisplayName("Authorization header is forwarded to outgoing request")
    void execute_forwardsAuthorizationHeader() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/users/u1/profile"))
                .willReturn(okJson(fixture("user-profile-success.json"))));

        var request = new ResourceExecuteRequest(
                "user-service.getProfile",
                Map.of("userId", "u1"),
                Map.of(),
                null,
                null
        );

        controller.execute(request, "tenant-A", "ns-1", "Bearer my-jwt-token");

        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/users/u1/profile"))
                .withHeader("Authorization", equalTo("Bearer my-jwt-token")));
    }

    // ── Header overrides from request body ──────────────────────────────

    @Test
    @DisplayName("header overrides from request body are applied to outgoing request")
    void execute_appliesHeaderOverrides() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/users/u1/profile"))
                .willReturn(okJson(fixture("user-profile-success.json"))));

        var request = new ResourceExecuteRequest(
                "user-service.getProfile",
                Map.of("userId", "u1"),
                Map.of("X-Custom-Header", "custom-value", "Accept", "text/plain"),
                null,
                null
        );

        controller.execute(request, "tenant-A", "ns-1", null);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/users/u1/profile"))
                .withHeader("X-Custom-Header", equalTo("custom-value"))
                .withHeader("Accept", equalTo("text/plain")));
    }

    // ── Request body Authorization override takes precedence ────────────

    @Test
    @DisplayName("body headerOverrides Authorization takes precedence over forwarded header")
    void execute_bodyAuthorizationOverridesForwardedHeader() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/users/u1/profile"))
                .willReturn(okJson(fixture("user-profile-success.json"))));

        var request = new ResourceExecuteRequest(
                "user-service.getProfile",
                Map.of("userId", "u1"),
                Map.of("Authorization", "Bearer body-token"),
                null,
                null
        );

        controller.execute(request, "tenant-A", "ns-1", "Bearer forwarded-token");

        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/users/u1/profile"))
                .withHeader("Authorization", equalTo("Bearer body-token")));
    }

    // ── Structured authOverride takes precedence ─────────────────────────

    @Test
    @DisplayName("authOverride takes precedence over forwarded Authorization header")
    void execute_authOverrideTakesPrecedenceOverForwardedHeader() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/users/u1/profile"))
                .willReturn(okJson(fixture("user-profile-success.json"))));

        var request = new ResourceExecuteRequest(
                "user-service.getProfile",
                Map.of("userId", "u1"),
                Map.of(),
                new HttpRequestInput.BearerAuth("override-token"),
                null
        );

        controller.execute(request, "tenant-A", "ns-1", "Bearer forwarded-token");

        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/users/u1/profile"))
                .withHeader("Authorization", equalTo("Bearer override-token")));
    }

    // ── Registry-driven resolution (not hard-coded URLs) ────────────────

    @Test
    @DisplayName("different resourceIds resolve to different upstream URLs")
    void execute_registryDriven_resolvesDifferentResources() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/recommendations/u1"))
                .willReturn(okJson("{\"entries\":[\"rec-1\"]}")));

        var request = new ResourceExecuteRequest(
                "recommendation-service.forUser",
                Map.of("userId", "u1"),
                Map.of(),
                null,
                null
        );

        ResponseEntity<GatewayResponse> response =
                controller.execute(request, "tenant-A", "ns-1", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        GatewayResponse gw = response.getBody();
        assertThat(gw).isNotNull();
        assertThat(gw.success()).isTrue();

        JsonNode data = objectMapper.valueToTree(gw.data());
        assertThat(data.get("resourceId").asText()).isEqualTo("recommendation-service.forUser");
        assertThat(data.get("payload").get("entries")).hasSize(1);
    }

    // ── Unknown resourceId returns 404 ──────────────────────────────────

    @Test
    @DisplayName("unknown resourceId returns 404 with error message")
    void execute_unknownResourceId_returns404() {
        var request = new ResourceExecuteRequest(
                "nonexistent.service",
                Map.of(),
                Map.of(),
                null,
                null
        );

        ResponseEntity<GatewayResponse> response =
                controller.execute(request, "tenant-A", "ns-1", null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        GatewayResponse gw = response.getBody();
        assertThat(gw).isNotNull();
        assertThat(gw.success()).isFalse();
        assertThat(gw.error()).contains("nonexistent.service");
    }

    // ── Fixture loading ─────────────────────────────────────────────────

    private static String fixture(String name) {
        try (InputStream is = ResourceExecuteIntegrationTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (is == null) throw new IllegalArgumentException("Fixture not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
