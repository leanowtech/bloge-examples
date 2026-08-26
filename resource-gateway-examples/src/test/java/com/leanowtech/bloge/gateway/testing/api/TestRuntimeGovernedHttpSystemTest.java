package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractCatalog;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceBinding;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.ResponseSemantics;
import com.leanowtech.bloge.gateway.testing.world.StateSpec;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompiler;
import com.leanowtech.bloge.gateway.testing.world.WorldReferenceExecutionPlanner;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioRunService;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.testing.world.access.AuthorizedWorldAssetResolver;
import com.leanowtech.bloge.gateway.testing.world.persistence.DatabaseGovernedCatalogRepository;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedAssetGovernance;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedAssetMetadata;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogKind;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedPayloadOrigin;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedResourceRef;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedSecurityClassification;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Servlet-boundary composition proof for the governed reference execution path. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "gateway.seed-descriptors=true",
                "gateway.base-url=http://127.0.0.1:1",
                "gateway.integration.identity.demo-token=test-token",
                "gateway.integration.identity.environment-id=test",
                "gateway.integration.identity.region=region-a",
                "gateway.integration.identity.allowed-purposes=TEST_EXECUTION",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id=integration-test-v1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring=integration-test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id=integration-request-index-v1",
                "gateway.testing.durable.worker-quarantines.request-key-protection.key-ring=integration-request-index-v1=HyAdHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.write-mode=KEYED_ONLY",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id=integration-replica-a",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "spring.datasource.url=jdbc:h2:mem:governed-http-main;DB_CLOSE_DELAY=-1",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:governed-http-control;DB_CLOSE_DELAY=-1"
        })
@Import(TestRuntimeGovernedHttpSystemTest.TestGraphConfiguration.class)
class TestRuntimeGovernedHttpSystemTest {
    private static final AtomicInteger REAL_OPERATOR_CALLS = new AtomicInteger();

    @BeforeEach
    void resetOperatorCalls() {
        REAL_OPERATOR_CALLS.set(0);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DatabaseGovernedCatalogRepository catalog;

    @Autowired
    private AuthorizedWorldAssetResolver resolver;

    @Autowired
    private WorldReferenceExecutionPlanner planner;

    @Autowired
    private WorldScenarioRunService runner;

    @Autowired
    private GatewayGraphService graphService;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void authenticatedHttpSurfaceUsesRealGovernedWorldComposition() {
        assertThat(catalog).isNotNull();
        assertThat(resolver).isNotNull();
        assertThat(planner).isNotNull();
        assertThat(runner).isNotNull();
        assertThat(mocking(resolver)).isFalse();
        assertThat(mocking(planner)).isFalse();
        assertThat(mocking(runner)).isFalse();

        Graph graph = graphService.requireGraph("productDetail");
        LogicalResourceContract contract = contract("logical.http");
        ResourceWorldModel world = world(contract);
        GovernedResourceRef worldRef = catalog.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world,
                GovernedAssetMetadata.safeDefaults());
        assertThat(catalog.findMetadata(worldRef)).isPresent();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.set("X-Purpose", "TEST_EXECUTION");
        headers.set("X-Correlation-Id", "corr-governed-http");
        headers.set("X-BLOGE-Test-Envelope", envelope(worldRef));
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity("/api/testing/executions",
                new HttpEntity<>("""
                        {"schemaVersion":"bloge.testExecutionRequest.v1",
                         "target":{"kind":"GRAPH","id":"productDetail","fingerprint":""},
                         "executionPurpose":"GRAPH_CONTRACT_TEST",
                         "context":{"productId":"product-a"},
                         "verbosity":"SUMMARY","metadata":{}}
                        """, headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode responseJson = read(response.getBody());
        assertThat(responseJson.at("/target/fingerprint").asText())
                .isEqualTo(GraphArtifactFingerprint.of(mapper, graph));
        assertThat(responseJson.at("/fixtureBundleRef/source").asText()).isEqualTo("STORED");
        assertThat(responseJson.at("/runId").asText()).isNotBlank();
        assertThat(responseJson.at("/evidence/status").asText()).isEqualTo("PASSED");
        assertThat(responseJson.at("/evidence/fixtureConsumptions").isArray()).isTrue();
        assertThat(responseJson.at("/plan/resolvedSites/0/fidelity").asText())
                .isEqualTo("WORLD_DELEGATE");
        assertThat(responseJson.at("/evidence/metadata/worldProvenance").asText())
                .isEqualTo("RESOURCE_WORLD_MODEL");
        assertThat(responseJson.at("/evidence/metadata/worldCompilationFingerprint").asText())
                .startsWith("sha256:");
        assertThat(responseJson.at("/evidence/metadata/assetRevision").asInt()).isEqualTo(1);
        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.setBearerAuth("test-token");
        getHeaders.set("X-Purpose", "TEST_EXECUTION");
        ResponseEntity<String> persisted = rest.exchange(
                "/api/testing/executions/" + responseJson.at("/runId").asText()
                        + "?verbosity=FULL", HttpMethod.GET, new HttpEntity<>(getHeaders), String.class);
        JsonNode persistedJson = read(persisted.getBody());
        assertThat(persisted.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(persistedJson.at("/integrity/signatureStatus").asText()).isEqualTo("VERIFIED");
        assertThat(persistedJson.at("/integrity/independentlyVerifiable").asBoolean()).isTrue();
        assertThat(persistedJson.at("/evidence/nodeTrace").toString()).contains("WORLD_DELEGATE");
        assertThat(REAL_OPERATOR_CALLS).hasValue(0);
    }

    @Test
    void unauthorizedHttpRequestFailsBeforeCatalogPayload() {
        ResponseEntity<String> response = rest.postForEntity("/api/testing/executions",
                new HttpEntity<>("{}", new HttpHeaders()), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).doesNotContain("product-a", "http-world");
        assertThat(REAL_OPERATOR_CALLS).hasValue(0);
    }

    @Test
    void unsafeGovernanceIsDeniedBeforePayloadResolution() {
        ResourceWorldModel unsafeWorld = world("http-world-unsafe", contract("logical.http.unsafe"));
        GovernedResourceRef unsafeRef = catalog.create("tenant-a", GovernedCatalogKind.RESOURCE_WORLD_MODEL,
                unsafeWorld.worldModelId(), unsafeWorld,
                new GovernedAssetMetadata(new GovernedAssetGovernance(
                        GovernedPayloadOrigin.REAL, GovernedSecurityClassification.PUBLIC,
                        Instant.parse("2099-01-01T00:00:00Z"), "custom:policy", "approval-ref")));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.set("X-Purpose", "TEST_EXECUTION");
        headers.set("X-Correlation-Id", "corr-unsafe");
        headers.set("X-BLOGE-Test-Envelope", envelope(unsafeRef, "corr-unsafe"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity("/api/testing/executions",
                new HttpEntity<>(requestBody(), headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).doesNotContain(unsafeRef.id(), unsafeRef.fingerprint());
        assertThat(REAL_OPERATOR_CALLS).hasValue(0);
    }

    private static boolean mocking(Object value) {
        return org.mockito.Mockito.mockingDetails(value).isMock();
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String envelope(GovernedResourceRef ref) {
        String json = "{\"purpose\":\"GRAPH_CONTRACT_TEST\","
                + "\"worldModel\":{\"id\":\"" + ref.id()
                + "\",\"revision\":1,\"fingerprint\":\"sha256:"
                + ref.fingerprint().substring("sha256:".length())
                + "\"},\"correlationId\":\"corr-governed-http\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                json.getBytes(StandardCharsets.UTF_8));
    }

    private static String envelope(GovernedResourceRef ref, String correlationId) {
        String json = "{\"purpose\":\"GRAPH_CONTRACT_TEST\","
                + "\"worldModel\":{\"id\":\"" + ref.id()
                + "\",\"revision\":1,\"fingerprint\":\"" + ref.fingerprint()
                + "\"},\"correlationId\":\"" + correlationId + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                json.getBytes(StandardCharsets.UTF_8));
    }

    private static String requestBody() {
        return """
                {"schemaVersion":"bloge.testExecutionRequest.v1",
                 "target":{"kind":"GRAPH","id":"productDetail","fingerprint":""},
                 "executionPurpose":"GRAPH_CONTRACT_TEST",
                 "context":{"productId":"product-a"},
                 "verbosity":"SUMMARY","metadata":{}}
                """;
    }

    private static LogicalResourceContract contract(String id) {
        return new LogicalResourceContract(id, schema("id"), schema("result"),
                ResponseSemantics.confirmed("http.status in 200..299", Map.of("NONE", List.of("N/A")),
                        ResponseSemantics.Idempotency.IDEMPOTENT,
                        ResponseSemantics.Retryability.CONDITIONAL));
    }

    private static ResourceWorldModel world(LogicalResourceContract contract) {
        return world("http-world", contract);
    }

    private static ResourceWorldModel world(String worldId, LogicalResourceContract contract) {
        ResourceDesignContract design = new ResourceDesignContract(contract.contractId(),
                contract.contractId(), "HTTP test resource", "", List.of(), contract.inputShape(),
                contract.outputShape(), Map.of(), "ACTIVE");
        VisualResourceDescriptor descriptor = new VisualResourceDescriptor(contract.contractId(),
                "http://127.0.0.1:1/never", "GET", Map.of(), null,
                java.time.Duration.ofSeconds(1), VisualResourceParameterMapping.empty(),
                new VisualResourceResponseProtocol.HttpStatus(), "data");
        LogicalResourceBinding binding = LogicalResourceBinding.bind("synthetic", "v1", design,
                descriptor, contract);
        WorldSlice slice = WorldSlice.register(new WorldSlice.Registration("tenant-a", "synthetic", "v1",
                contract.contractId(), contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, BlogeFragmentRef.frozen("http-test.bloge",
                        "graph customerWorld { transform result { value = ctx.id } }"),
                StateSpec.empty());
        return new ResourceWorldModel(worldId, "tenant-a", 1, List.of(slice));
    }

    private static SchemaEnvelope schema(String property) {
        return new SchemaEnvelope("json-schema", "2020-12", Map.of(
                "type", "object", "properties", Map.of(property, Map.of("type", "string")),
                "required", List.of(property), "additionalProperties", false));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestGraphConfiguration {
        @Bean
        @Primary
        GatewayGraphService governedGraphService(GraphEngine engine) {
            Operator<Object, Object> real = (input, context) -> {
                REAL_OPERATOR_CALLS.incrementAndGet();
                return Map.of("result", String.valueOf(context.graphContext().get("productId")));
            };
            String tag = WorldScenarioCompiler.logicalContractTag(
                    "logical.http", contract("logical.http").contractFingerprint());
            Graph graph = new GraphBuilder("productDetail").node("lookup", real)
                    .meta("tags", tag)
                    .input((results, context) -> Map.of("id", context.get("productId")))
                    .build();
            return new GatewayGraphService(engine, List.of(graph),
                    GatewayGraphContractCatalog.builtIn());
        }
    }
}
