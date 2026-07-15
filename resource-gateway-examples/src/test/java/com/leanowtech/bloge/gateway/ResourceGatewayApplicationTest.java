package com.leanowtech.bloge.gateway;

import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.gateway.gateway.GatewayProperties;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractTestBatchResult;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractTestService;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractTestSuiteRepository;
import com.leanowtech.bloge.gateway.gateway.ResourceDescriptorBootstrap;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.seed-descriptors=false",
                "spring.datasource.url=jdbc:h2:mem:resource-gateway-startup;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        }
)
class ResourceGatewayApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WritableResourceRegistry registry;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private IntegrationAccessAuditRepository integrationAccessAudit;

    @Autowired
    private GatewayGraphContractTestService graphContractTests;

    @Autowired
    private GatewayGraphContractTestSuiteRepository graphContractTestSuites;

    @LocalServerPort
    private int port;

    @BeforeEach
    void seedDemoDescriptors() {
        seedDemoDescriptors("http://localhost:" + port + "/demo-upstream");
    }

    private void seedDemoDescriptors(String baseUrl) {
        registry.all().stream()
                .map(descriptor -> descriptor.resourceId())
                .toList()
                .forEach(registry::deregister);

        var properties = new GatewayProperties();
        properties.setBaseUrl(baseUrl);
        properties.setSeedDescriptors(true);
        new ResourceDescriptorBootstrap(registry, properties).seedDescriptors();
    }

    @Test
    void everyBuiltInGraphSuiteRunsThroughRealWiringWithoutUncontrolledResourceCalls() {
        seedDemoDescriptors("http://127.0.0.1:1/unreachable");

        GatewayGraphContractTestBatchResult result = graphContractTests.runAll(graphContractTestSuites.all());

        assertThat(result.passed()).as("built-in suite batch: %s", result).isTrue();
        assertThat(result.totalSuites()).isEqualTo(7);
        assertThat(result.totalCases()).isEqualTo(13);
        assertThat(result.coverage().mockedResourceCalls()).isEqualTo(23);
        assertThat(result.coverage().assertionCount()).isEqualTo(33);
        assertThat(result.results()).allSatisfy(suite ->
                assertThat(suite.result().results()).allSatisfy(testCase -> {
                    assertThat(testCase.evidence().status()).isEqualTo(TestRunEvidence.Status.PASSED);
                    if (suite.graphName().equals("enrichOrderList")) {
                        assertThat(testCase.evidence().evidenceClass())
                                .isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
                    } else {
                        assertThat(testCase.evidence().evidenceClass())
                                .isEqualTo(TestRunEvidence.EvidenceClass.CERTIFIABLE);
                    }
                    assertThat(testCase.evidence().nodes())
                            .filteredOn(node -> node.operatorRef().equals("httpResource")
                                    && !node.status().equals("SKIPPED"))
                            .allSatisfy(node -> assertThat(node.fidelity())
                                    .isEqualTo("TRANSPORT_LEVEL"));
                }));
    }

    @Test
    void contextStarts() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.containsBeanDefinition("gatewayGraphRuntimeConfiguration")).isFalse();
    }

    @Test
    void starterAutoConfigurationProvidesGatewayRuntime() {
        GraphEngine graphEngine = applicationContext.getBean(GraphEngine.class);

        assertThat(applicationContext.getBean("blogeGraphs", List.class)).isNotEmpty();
        assertThat(graphEngineField(graphEngine, "inMemorySuspendTtl", Duration.class))
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resourceGraphContractsAreExposedForSystemIntegration() {
        var contract = restTemplate.getForEntity(
                "/api/gateway/graphs/contracts/loanDecisionPolicy",
                Map.class);

        assertThat(contract.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(contract.getBody()).containsEntry("graphName", "loanDecisionPolicy");
        var inputSchema = (Map<String, Object>) contract.getBody().get("inputSchema");
        var inputBody = (Map<String, Object>) inputSchema.get("schema");
        assertThat((Map<String, Object>) inputBody.get("properties"))
                .containsKeys("applicantId", "requestedAmount");

        var outputSchema = (Map<String, Object>) contract.getBody().get("outputSchema");
        var outputBody = (Map<String, Object>) outputSchema.get("schema");
        assertThat((Map<String, Object>) outputBody.get("properties"))
                .containsKeys("applicant", "requestedAmount", "policy", "explanation");
    }

    @Test
    @SuppressWarnings("unchecked")
    void builtInDemoUpstreamMakesReadmeGatewayExamplesSucceed() {
        var dashboard = restTemplate.getForEntity("/api/gateway/dashboard/u1", Map.class);
        assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(dashboard.getBody()).containsEntry("success", true);
        assertThat((Map<String, Object>) dashboard.getBody().get("data"))
                .containsKeys("profile", "orders", "recommendations", "wallet", "notifications");

        var product = restTemplate.getForEntity("/api/gateway/products/p1", Map.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(product.getBody()).containsEntry("success", true);
        assertThat((Map<String, Object>) product.getBody().get("data"))
                .containsEntry("productType", "physical");

        var orders = restTemplate.getForEntity("/api/gateway/orders/u1/enriched", Map.class);
        assertThat(orders.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(orders.getBody()).containsEntry("success", true);

        var credit = restTemplate.getForEntity("/api/gateway/credit-score/u1", Map.class);
        assertThat(credit.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(credit.getBody()).containsEntry("success", true);
        assertThat((Map<String, Object>) credit.getBody().get("data"))
                .containsEntry("provider", "primary");

        var loanPolicy = restTemplate.getForEntity("/api/gateway/loan-policy/prime?amount=450000", Map.class);
        assertThat(loanPolicy.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(loanPolicy.getBody()).containsEntry("success", true);
        assertThat((Map<String, Object>) ((Map<String, Object>) loanPolicy.getBody().get("data")).get("policy"))
                .containsEntry("ruleId", "R1")
                .containsEntry("decision", "approved");

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Tenant-Id", "demo-tenant");
        headers.add("X-Namespace", "local");

        var executeRequest = new HttpEntity<>(Map.of(
                "resourceId", "user-service.getProfile",
                "params", Map.of("userId", "u1")
        ), headers);

        var execute = restTemplate.postForEntity("/api/gateway/resources/execute", executeRequest, Map.class);
        assertThat(execute.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(execute.getBody()).containsEntry("success", true);
        assertThat((Map<String, Object>) execute.getBody().get("data"))
                .containsEntry("resourceId", "user-service.getProfile");
        assertThat((Map<String, Object>) ((Map<String, Object>) execute.getBody().get("data")).get("payload"))
                .containsEntry("name", "Alice");
    }

    @Test
    @SuppressWarnings("unchecked")
    void integrationSurfaceUsesVerifiedServerIdentityAndAuditsDenials() {
        Map<String, Object> capabilities = restTemplate.getForObject("/api/integration/capabilities", Map.class);
        Map<String, Object> capabilityPayload = (Map<String, Object>) capabilities.get("payload");
        assertThat((Map<String, Object>) capabilityPayload.get("features"))
                .containsEntry("trustedWorkloadIdentity", true)
                .containsEntry("demoIdentityMode", true);
        assertThat((Map<String, Object>) capabilityPayload.get("identityProvider"))
                .containsEntry("providerType", "STATIC_BEARER_REGISTRY")
                .containsEntry("claimsSource", "SERVER_REGISTRY");

        HttpHeaders spoofed = integrationHeaders();
        var missingCredential = restTemplate.exchange("/api/integration/reconciliation", HttpMethod.GET,
                new HttpEntity<>(spoofed), Map.class);
        assertThat(missingCredential.getStatusCode().value()).isEqualTo(401);
        assertThat(missingCredential.getBody()).containsEntry(
                "code", "RG.INTEGRATION.AUTHENTICATION_REQUIRED");

        HttpHeaders authorized = integrationHeaders();
        authorized.setBearerAuth("bloge-aneke-demo-token");
        var allowed = restTemplate.exchange("/api/integration/reconciliation", HttpMethod.GET,
                new HttpEntity<>(authorized), Map.class);
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
        assertThat((Map<String, Object>) allowed.getBody().get("payload"))
                .containsEntry("tenantId", "tenant-a")
                .containsEntry("environmentId", "prod");

        authorized.set("X-Tenant-Id", "tenant-b");
        var mismatched = restTemplate.exchange("/api/integration/reconciliation", HttpMethod.GET,
                new HttpEntity<>(authorized), Map.class);
        assertThat(mismatched.getStatusCode().value()).isEqualTo(403);
        assertThat(mismatched.getBody()).containsEntry(
                "code", "RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH");

        assertThat(integrationAccessAudit.recent(20))
                .extracting(value -> value.outcome() + ":" + value.reasonCode())
                .contains("DENIED:RG.INTEGRATION.AUTHENTICATION_REQUIRED",
                        "ALLOWED:", "DENIED:RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH");
    }

    private static HttpHeaders integrationHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "tenant-a");
        headers.set("X-Organization-Id", "knowledge-governance");
        headers.set("X-Project-Id", "tool-studio");
        headers.set("X-Environment-Id", "prod");
        headers.set("X-Actor-Id", "aneke-sync");
        headers.set("X-Purpose", "CHANGE_SYNC");
        headers.set("X-Correlation-Id", "startup-auth-proof");
        return headers;
    }

    private static <T> T graphEngineField(GraphEngine graphEngine, String fieldName, Class<T> fieldType) {
        try {
            Field field = GraphEngine.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return fieldType.cast(field.get(graphEngine));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to inspect GraphEngine field '" + fieldName + "'", exception);
        }
    }
}
