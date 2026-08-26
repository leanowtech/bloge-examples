package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.model.CompiledGraph;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.runtime.registry.CompiledGraphCatalog;
import com.leanowtech.bloge.core.spi.ExpressionFunction;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractCatalog;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceBinding;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.ResponseSemantics;
import com.leanowtech.bloge.gateway.testing.world.StateSpec;
import com.leanowtech.bloge.gateway.testing.world.StateSpecV2;
import com.leanowtech.bloge.gateway.testing.world.StateKeySpec;
import com.leanowtech.bloge.gateway.testing.world.WorldStateSpec;
import com.leanowtech.bloge.gateway.testing.world.WorldReferenceExecutionPlanner;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioRunService;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.testing.world.access.AuthorizedWorldAssetResolver;
import com.leanowtech.bloge.gateway.testing.function.CompiledFunctionInventoryProvider;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlAsset;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlRule;
import com.leanowtech.bloge.gateway.testing.function.FunctionDeclarationStatus;
import com.leanowtech.bloge.gateway.testing.function.FunctionEffect;
import com.leanowtech.bloge.gateway.testing.function.FunctionInvocationInventory;
import com.leanowtech.bloge.gateway.testing.function.FunctionInvocationSite;
import com.leanowtech.bloge.gateway.testing.function.FunctionLibraryDeclaration;
import com.leanowtech.bloge.gateway.testing.function.FunctionRuntimeFact;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventoryBuilder;
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
import java.util.concurrent.Future;
import java.util.concurrent.Executors;

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
    private CompiledGraphCatalog compiledGraphCatalog;

    @Autowired
    private OperatorRegistry operatorRegistry;

    @Autowired
    private CompiledFunctionInventoryProvider functionInventoryProvider;

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

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("HTTP %s: %s", response.getStatusCode(), response.getBody())
                .isTrue();
        JsonNode responseJson = read(response.getBody());
        assertThat(responseJson.at("/target/fingerprint").asText())
                .isEqualTo(GraphArtifactFingerprint.of(mapper, graph));
        assertThat(responseJson.at("/fixtureBundleRef/source").asText()).isEqualTo("STORED");
        assertThat(responseJson.at("/runId").asText()).isNotBlank();
        assertThat(responseJson.at("/evidence/status").asText())
                .as("execution response: %s", response.getBody())
                .isEqualTo("PASSED");
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
    void functionControlAssetRunsThroughRealHttpAndPersistsPayloadFreeEvidence() {
        Graph graph = graphService.requireGraph("productDetail");
        String targetFingerprint = GraphArtifactFingerprint.of(mapper, graph);
        InvocationInventory inventory = new InvocationInventoryBuilder(operatorRegistry)
                .build(graph, targetFingerprint);
        FunctionInvocationInventory functionInventory = functionInventoryProvider
                .build(graph, inventory);
        FunctionInvocationSite site = functionInventory.sites().stream()
                .filter(candidate -> "uppercase".equals(candidate.functionName()))
                .findFirst()
                .orElseThrow();
        ExpressionFunction runtime = compiledGraphCatalog.functionRegistry().get(site.functionName());
        FunctionRuntimeFact runtimeFact = FunctionRuntimeFact.from(site.functionName(), runtime);
        FunctionLibraryDeclaration declaration = new FunctionLibraryDeclaration(
                site.functionName(), runtimeFact.runtimeName(), runtimeFact.pure(),
                new java.util.LinkedHashSet<>(runtimeFact.requiredExecutionServices()),
                FunctionEffect.PURE_COMPUTATION, Map.of(), Map.of(),
                FunctionDeclarationStatus.CERTAIN, "");
        FunctionControlRule rule = new FunctionControlRule(
                "http-uppercase-control",
                new FunctionControlRule.Selector(site.graphPath(), site.nodeId(), site.functionName(),
                        site.line(), site.column()),
                List.of("product-a"), FunctionControlRule.Behavior.RETURN, "CONTROLLED",
                null, java.time.Duration.ZERO, FunctionControlRule.Consumption.exactly(1),
                true, 0);
        FunctionControlAsset asset = new FunctionControlAsset(
                targetFingerprint, List.of(declaration), List.of(rule));
        GovernedResourceRef functionRef = catalog.create("tenant-a", GovernedCatalogKind.FUNCTION_CONTROL,
                "function-http", asset, GovernedAssetMetadata.safeDefaults());
        ResourceWorldModel world = world("http-function-world", contract("logical.http"));
        GovernedResourceRef worldRef = catalog.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world,
                GovernedAssetMetadata.safeDefaults());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.set("X-Purpose", "TEST_EXECUTION");
        headers.set("X-Correlation-Id", "corr-fn-http");
        headers.set("X-BLOGE-Test-Envelope", envelope(worldRef, functionRef, "corr-fn-http"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity("/api/testing/executions",
                new HttpEntity<>(requestBody(), headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("HTTP %s: %s", response.getStatusCode(), response.getBody())
                .isTrue();
        JsonNode responseJson = read(response.getBody());
        assertThat(responseJson.at("/evidence/status").asText())
                .as("execution response: %s", response.getBody())
                .isEqualTo("PASSED");
        assertThat(response.getBody()).contains("CONTROLLED")
                .doesNotContain("function-http");
        JsonNode controlProjection = responseJson.at(
                "/evidence/metadata/controlEvidenceProjection");
        JsonNode functionProjection = controlProjection.at("/function");
        assertThat(functionProjection.at("/planFingerprint").asText()).startsWith("sha256:");
        JsonNode observation = functionProjection.at("/observations/0");
        assertThat(observation.at("/siteKey").asText()).isEqualTo(site.structuralKey());
        assertThat(observation.at("/ruleId").asText()).isEqualTo("http-uppercase-control");
        assertThat(observation.at("/behavior").asText()).isEqualTo("RETURN");
        assertThat(observation.at("/occurrence").asInt()).isEqualTo(1);
        assertThat(observation.at("/argumentsFingerprint").asText()).startsWith("sha256:");
        assertThat(observation.at("/resultFingerprint").asText()).startsWith("sha256:");
        assertThat(functionProjection.at("/consumptions/0/minimum").asInt()).isEqualTo(1);
        assertThat(functionProjection.at("/consumptions/0/maximum").asInt()).isEqualTo(1);
        assertThat(functionProjection.at("/consumptions/0/used").asInt()).isEqualTo(1);
        assertThat(response.getBody()).contains("bloge.testRunControlEvidence.v1")
                .doesNotContain("product-a");

        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.setBearerAuth("test-token");
        getHeaders.set("X-Purpose", "TEST_EXECUTION");
        ResponseEntity<String> persisted = rest.exchange(
                "/api/testing/executions/" + responseJson.at("/runId").asText()
                        + "?verbosity=FULL", HttpMethod.GET, new HttpEntity<>(getHeaders), String.class);
        assertThat(persisted.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode persistedJson = read(persisted.getBody());
        JsonNode persistedControlProjection = persistedJson.at(
                "/evidence/metadata/controlEvidenceProjection");
        assertThat(persistedControlProjection.at("/schemaVersion").asText())
                .isEqualTo("bloge.testRunControlEvidence.v1");
        assertThat(persistedControlProjection.toString())
                .doesNotContain("product-a", "returnValue", "errorMessage");
    }

    @Test
    void stateAndFunctionControlsShareOneHttpRunAndEvidenceProjection() {
        Graph graph = graphService.requireGraph("productDetail");
        String targetFingerprint = GraphArtifactFingerprint.of(mapper, graph);
        FunctionControlAsset asset = functionAsset(graph, targetFingerprint);
        GovernedResourceRef functionRef = catalog.create("tenant-a", GovernedCatalogKind.FUNCTION_CONTROL,
                "function-http-state", asset, GovernedAssetMetadata.safeDefaults());
        ResourceWorldModel world = statefulWorld(contract("logical.http"));
        GovernedResourceRef worldRef = catalog.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world,
                GovernedAssetMetadata.safeDefaults());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.set("X-Purpose", "TEST_EXECUTION");
        headers.set("X-Correlation-Id", "corr-state-fn-http");
        headers.set("X-BLOGE-Test-Envelope",
                envelope(worldRef, functionRef, "corr-state-fn-http"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity("/api/testing/executions",
                new HttpEntity<>(requestBody().replace("\"verbosity\":\"SUMMARY\"",
                        "\"verbosity\":\"FULL\""), headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("HTTP %s: %s", response.getStatusCode(), response.getBody())
                .isTrue();
        JsonNode responseJson = read(response.getBody());
        assertThat(responseJson.at("/evidence/status").asText()).isEqualTo("PASSED");
        assertThat(responseJson.at("/evidence/nodeTrace/1/output/value").asText())
                .isEqualTo("CONTROLLED");
        JsonNode projection = responseJson.at("/evidence/metadata/controlEvidenceProjection");
        assertThat(projection.at("/state/revision").asInt()).isEqualTo(1);
        assertThat(projection.at("/state/transactions/0/coordinate/nodeId").asText())
                .isEqualTo("lookup");
        assertThat(projection.at("/state/transactions/0/writeKeys/0").asText())
                .isEqualTo("/calls");
        assertThat(projection.at("/function/observations/0/ruleId").asText())
                .isEqualTo("http-uppercase-control");
        assertThat(projection.at("/function/consumptions/0/used").asInt()).isEqualTo(1);
        assertThat(projection.at("/state").toString()).doesNotContain("product-a");
        assertThat(projection.at("/function").toString()).doesNotContain("product-a", "returnValue", "errorMessage");

        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.setBearerAuth("test-token");
        getHeaders.set("X-Purpose", "TEST_EXECUTION");
        ResponseEntity<String> persisted = rest.exchange(
                "/api/testing/executions/" + responseJson.at("/runId").asText()
                        + "?verbosity=FULL", HttpMethod.GET, new HttpEntity<>(getHeaders), String.class);
        assertThat(persisted.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode persistedProjection = read(persisted.getBody())
                .at("/evidence/metadata/controlEvidenceProjection");
        assertThat(persistedProjection.at("/state/revision").asInt()).isEqualTo(1);
        assertThat(persistedProjection.at("/function/observations/0/occurrence").asInt())
                .isEqualTo(1);
        assertThat(persistedProjection.toString()).doesNotContain("product-a", "returnValue", "errorMessage");
        assertThat(REAL_OPERATOR_CALLS).hasValue(0);
    }

    @Test
    void functionControlHttpEvidenceIsStableAcrossTwentyIndependentRuns() {
        HttpAssetRefs refs = functionRefs("twenty");
        List<ResponseEntity<String>> responses = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> postFunctionRefs(refs, "corr-fn-20-" + index, false))
                .toList();

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            JsonNode json = read(response.getBody());
            assertThat(json.at("/evidence/status").asText()).isEqualTo("PASSED");
            assertThat(json.at("/evidence/metadata/controlEvidenceProjection/function/observations/0/occurrence")
                    .asInt()).isEqualTo(1);
        });
        assertThat(responses.stream().map(response -> read(response.getBody()).at(
                "/evidence/metadata/controlEvidenceProjection/function/evidenceFingerprint").asText()).toList())
                .hasSize(20).containsOnly(responses.getFirst() == null ? "" : read(responses.getFirst().getBody())
                        .at("/evidence/metadata/controlEvidenceProjection/function/evidenceFingerprint").asText());
        assertThat(REAL_OPERATOR_CALLS).hasValue(0);
    }

    @Test
    void concurrentFunctionControlHttpRunsRemainIsolated() throws Exception {
        HttpAssetRefs refs = functionRefs("concurrent");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ResponseEntity<String>>> futures = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(index -> executor.submit(
                            () -> postFunctionRefs(refs, "corr-fn-concurrent-" + index, false)))
                    .toList();
            List<ResponseEntity<String>> responses = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception failure) {
                    throw new AssertionError("concurrent HTTP execution failed", failure);
                }
            }).toList();
            assertThat(responses).allSatisfy(response -> {
                assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
                JsonNode json = read(response.getBody());
                assertThat(json.at("/evidence/status").asText()).isEqualTo("PASSED");
                assertThat(json.at(
                        "/evidence/metadata/controlEvidenceProjection/function/consumptions/0/used")
                        .asInt()).isEqualTo(1);
                assertThat(json.at(
                        "/evidence/metadata/controlEvidenceProjection/function/observations/0/occurrence")
                        .asInt()).isEqualTo(1);
            });
        }
        assertThat(REAL_OPERATOR_CALLS).hasValue(0);
    }

    @Test
    void malformedAndUnresolvableFunctionReferencesFailBeforeExecution() {
        HttpAssetRefs refs = functionRefs("negative");
        GovernedResourceRef missing = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.FUNCTION_CONTROL, "missing-function", 1, sha("missing"));
        ResponseEntity<String> unknown = postFunctionRefs(
                new HttpAssetRefs(refs.world(), missing), "corr-fn-missing", false);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(unknown.getBody()).doesNotContain("product-a", "rules", "returnValue");

        GovernedResourceRef tampered = new GovernedResourceRef("tenant-a",
                GovernedCatalogKind.FUNCTION_CONTROL, refs.function().id(), 1, sha("tampered"));
        ResponseEntity<String> tamper = postFunctionRefs(
                new HttpAssetRefs(refs.world(), tampered), "corr-fn-tampered", false);
        assertThat(tamper.getStatusCode().value()).isEqualTo(404);
        assertThat(tamper.getBody()).doesNotContain("product-a", "rules", "returnValue");

        String inline = "{\"purpose\":\"GRAPH_CONTRACT_TEST\",\"worldModel\":{\"id\":\""
                + refs.world().id() + "\",\"revision\":1,\"fingerprint\":\""
                + refs.world().fingerprint() + "\"},\"functionControl\":{\"id\":\"inline\","
                + "\"revision\":1,\"fingerprint\":\"" + sha("inline")
                + "\",\"rules\":[]},\"correlationId\":\"corr-fn-inline\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.set("X-Purpose", "TEST_EXECUTION");
        headers.set("X-Correlation-Id", "corr-fn-inline");
        headers.set("X-BLOGE-Test-Envelope", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(inline.getBytes(StandardCharsets.UTF_8)));
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> inlineResponse = rest.postForEntity("/api/testing/executions",
                new HttpEntity<>(requestBody(), headers), String.class);
        assertThat(inlineResponse.getStatusCode().is4xxClientError()).isTrue();
        assertThat(inlineResponse.getBody()).doesNotContain("rules", "returnValue", "product-a");
        assertThat(REAL_OPERATOR_CALLS).hasValue(0);
    }

    @Test
    void functionControlTenantGovernanceAndExpiryAreDeniedBeforeDecode() {
        HttpAssetRefs base = functionRefs("governance");
        Graph graph = graphService.requireGraph("productDetail");
        String targetFingerprint = GraphArtifactFingerprint.of(mapper, graph);
        FunctionControlAsset asset = functionAsset(graph, targetFingerprint);

        GovernedResourceRef otherTenant = catalog.create("tenant-b", GovernedCatalogKind.FUNCTION_CONTROL,
                "function-http-cross-tenant", asset, GovernedAssetMetadata.safeDefaults());
        ResponseEntity<String> crossTenant = postFunctionRefs(
                new HttpAssetRefs(base.world(), otherTenant), "corr-fn-cross-tenant", false);
        assertThat(crossTenant.getStatusCode().value()).isEqualTo(404);
        assertThat(crossTenant.getBody()).doesNotContain("rules", "returnValue", "product-a");

        GovernedResourceRef unsafe = catalog.create("tenant-a", GovernedCatalogKind.FUNCTION_CONTROL,
                "function-http-unsafe", asset, new GovernedAssetMetadata(new GovernedAssetGovernance(
                GovernedPayloadOrigin.REAL, GovernedSecurityClassification.PUBLIC,
                Instant.parse("2099-01-01T00:00:00Z"), "custom:policy", "approval-ref")));
        ResponseEntity<String> unsafeResponse = postFunctionRefs(
                new HttpAssetRefs(base.world(), unsafe), "corr-fn-unsafe", false);
        assertThat(unsafeResponse.getStatusCode().value()).isEqualTo(403);
        assertThat(unsafeResponse.getBody()).doesNotContain("rules", "returnValue", "product-a");

        GovernedResourceRef expired = catalog.create("tenant-a", GovernedCatalogKind.FUNCTION_CONTROL,
                "function-http-expired", asset, new GovernedAssetMetadata(new GovernedAssetGovernance(
                GovernedPayloadOrigin.SYNTHETIC, GovernedSecurityClassification.PUBLIC,
                Instant.parse("2000-01-01T00:00:00Z"),
                GovernedAssetGovernance.BUILTIN_SYNTHETIC_PUBLIC_POLICY, null)));
        ResponseEntity<String> expiredResponse = postFunctionRefs(
                new HttpAssetRefs(base.world(), expired), "corr-fn-expired", false);
        assertThat(expiredResponse.getStatusCode().value()).isEqualTo(503);
        assertThat(expiredResponse.getBody()).doesNotContain("rules", "returnValue", "product-a");
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

    private static String envelope(GovernedResourceRef worldRef,
                                   GovernedResourceRef functionRef,
                                   String correlationId) {
        String json = "{\"purpose\":\"GRAPH_CONTRACT_TEST\","
                + "\"worldModel\":{\"id\":\"" + worldRef.id()
                + "\",\"revision\":1,\"fingerprint\":\"" + worldRef.fingerprint()
                + "\"},\"functionControl\":{\"id\":\"" + functionRef.id()
                + "\",\"revision\":1,\"fingerprint\":\"" + functionRef.fingerprint()
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

    private static ResourceWorldModel statefulWorld(LogicalResourceContract contract) {
        return world("http-stateful-world", contract,
                StateSpecV2.of(List.of(new StateKeySpec("/calls", StateKeySpec.Access.READ_WRITE,
                        Map.of("type", "integer"), 0))),
                """
                graph customerWorld {
                    transform result {
                        response = { value: "product-a" }
                        stateWrites = { calls: ctx.state.calls + 1 }
                    }
                }
                """);
    }

    private static ResourceWorldModel world(String worldId, LogicalResourceContract contract) {
        return world(worldId, contract, StateSpec.empty(),
                "graph customerWorld { transform result { value = \"product-a\" } }");
    }

    private static ResourceWorldModel world(String worldId, LogicalResourceContract contract,
                                            WorldStateSpec state, String fragmentSource) {
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
                contract, binding, BlogeFragmentRef.frozen("http-test.bloge", fragmentSource), state);
        return new ResourceWorldModel(worldId, "tenant-a", 1, List.of(slice));
    }

    private HttpAssetRefs functionRefs(String suffix) {
        Graph graph = graphService.requireGraph("productDetail");
        String targetFingerprint = GraphArtifactFingerprint.of(mapper, graph);
        FunctionControlAsset asset = functionAsset(graph, targetFingerprint);
        GovernedResourceRef functionRef = catalog.create("tenant-a", GovernedCatalogKind.FUNCTION_CONTROL,
                "function-http-" + suffix, asset, GovernedAssetMetadata.safeDefaults());
        ResourceWorldModel world = world("http-function-world-" + suffix, contract("logical.http"));
        GovernedResourceRef worldRef = catalog.create("tenant-a",
                GovernedCatalogKind.RESOURCE_WORLD_MODEL, world.worldModelId(), world,
                GovernedAssetMetadata.safeDefaults());
        return new HttpAssetRefs(worldRef, functionRef);
    }

    private ResponseEntity<String> postFunctionRefs(HttpAssetRefs refs, String correlationId,
                                                     boolean full) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.set("X-Purpose", "TEST_EXECUTION");
        headers.set("X-Correlation-Id", correlationId);
        headers.set("X-BLOGE-Test-Envelope", envelope(refs.world(), refs.function(), correlationId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = full ? requestBody().replace("\"verbosity\":\"SUMMARY\"", "\"verbosity\":\"FULL\"")
                : requestBody();
        return rest.postForEntity("/api/testing/executions", new HttpEntity<>(body, headers), String.class);
    }

    private record HttpAssetRefs(GovernedResourceRef world, GovernedResourceRef function) {
    }

    private FunctionControlAsset functionAsset(Graph graph, String targetFingerprint) {
        InvocationInventory inventory = new InvocationInventoryBuilder(operatorRegistry)
                .build(graph, targetFingerprint);
        FunctionInvocationInventory functionInventory = functionInventoryProvider
                .build(graph, inventory);
        FunctionInvocationSite site = functionInventory.sites().stream()
                .filter(candidate -> "uppercase".equals(candidate.functionName()))
                .findFirst()
                .orElseThrow();
        ExpressionFunction runtime = compiledGraphCatalog.functionRegistry().get(site.functionName());
        FunctionRuntimeFact runtimeFact = FunctionRuntimeFact.from(site.functionName(), runtime);
        FunctionLibraryDeclaration declaration = new FunctionLibraryDeclaration(
                site.functionName(), runtimeFact.runtimeName(), runtimeFact.pure(),
                new java.util.LinkedHashSet<>(runtimeFact.requiredExecutionServices()),
                FunctionEffect.PURE_COMPUTATION, Map.of(), Map.of(),
                FunctionDeclarationStatus.CERTAIN, "");
        FunctionControlRule rule = new FunctionControlRule(
                "http-uppercase-control",
                new FunctionControlRule.Selector(site.graphPath(), site.nodeId(), site.functionName(),
                        site.line(), site.column()),
                List.of("product-a"), FunctionControlRule.Behavior.RETURN, "CONTROLLED",
                null, java.time.Duration.ZERO, FunctionControlRule.Consumption.exactly(1),
                true, 0);
        return new FunctionControlAsset(targetFingerprint, List.of(declaration), List.of(rule));
    }

    private static String sha(String value) {
        String hex = Integer.toHexString(value.hashCode());
        return "sha256:" + hex.repeat((64 / hex.length()) + 1).substring(0, 64);
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
        CompiledGraphCatalog governedCompiledGraphCatalog(GraphLoader loader) {
            CompiledGraph artifact = loader.loadArtifact("""
                    graph productDetail {
                        transform lookup {
                            value = "product-a"
                        }
                        transform format {
                            value = uppercase(lookup.output.value)
                        }
                    }
                    """);
            return new CompiledGraphCatalog(List.of(addContractTag(artifact)),
                    loader.functionRegistrySnapshot());
        }

        @Bean
        @Primary
        GatewayGraphService governedGraphService(GraphEngine engine, CompiledGraphCatalog catalog) {
            return new GatewayGraphService(engine, catalog.graphs(),
                    GatewayGraphContractCatalog.builtIn());
        }

        private static CompiledGraph addContractTag(CompiledGraph artifact) {
            Graph graph = artifact.graph();
            NodeSpec resource = graph.nodes().get("lookup");
            if (resource == null) {
                throw new IllegalStateException("productDetail fixture lost lookup");
            }
            String tag = com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompiler
                    .logicalContractTag("logical.http", contract("logical.http").contractFingerprint());
            Map<String, NodeSpec> nodes = new java.util.LinkedHashMap<>(graph.nodes());
            nodes.put(resource.id(), resource.toBuilder()
                    .metadata(resource.metadata().toBuilder().put("tags", tag).build())
                    .build());
            Graph tagged = new Graph(graph.name(), nodes, graph.edges(), graph.sourceNodes(),
                    graph.terminalNodes(), graph.schemaValidationLevel(), graph.embeddedOperators(),
                    graph.declaredInputSchema(), graph.declaredOutputSchema(), graph.sagaConfig(),
                    graph.definitionSource(), graph.streamingOutputNodeId(), graph.streamingInputs());
            return new CompiledGraph(tagged, artifact.functionCalls(), artifact.nestedGraphs());
        }
    }
}
