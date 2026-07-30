package com.leanowtech.bloge.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractCatalog;
import com.leanowtech.bloge.gateway.gateway.GatewayProperties;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractTestBatchResult;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractTestService;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphContractTestSuiteRepository;
import com.leanowtech.bloge.gateway.gateway.ResourceDescriptorBootstrap;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.mirror.BuiltInCapabilityClosureService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityProjectionContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityProjectionException;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringCommitResult;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftService;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringProblem;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceApplyRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceRequest;
import com.leanowtech.bloge.gateway.visual.authoring.model.SampleInferenceResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionBindingStatus;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionDraft;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionDraftRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionRunEvidence;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionRunRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorDraft;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorDraftRequest;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorRunEvidence;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorRunRequest;
import com.leanowtech.bloge.gateway.visual.authoring.transport.VisualLibraryAuthoringDraftController;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestDraftRequest;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Autowired
    private BuiltInCapabilityClosureService builtInCapabilityClosures;

    @Autowired
    private GatewayGraphContractCatalog graphContracts;

    @Autowired
    private ObjectMapper objectMapper;

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
        assertThat(result.totalCases()).isEqualTo(14);
        assertThat(result.coverage().mockedResourceCalls()).isEqualTo(28);
        assertThat(result.coverage().assertionCount()).isEqualTo(37);
        assertThat(result.results()).allSatisfy(suite ->
                assertThat(suite.result().results()).allSatisfy(testCase -> {
                    assertThat(testCase.evidence().status()).isEqualTo(TestRunEvidence.Status.PASSED);
                    assertThat(testCase.evidence().evidenceClass())
                            .isEqualTo(TestRunEvidence.EvidenceClass.CERTIFIABLE);
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
    void visualLibraryAuthoringDraftLifecycleEnforcesEtagAndPreviewFences() throws Exception {
        String draftId = "integration-authoring-library";
        VisualLibraryAuthoringDocument document = new YAMLMapper().findAndRegisterModules()
                .readValue("""
                        schemaVersion: bloge.visualLibraryAuthoring.v1
                        library:
                          id: integration-authoring-library
                          owner: integration-team
                        operators:
                          integration:echo:
                            input: {value: string}
                            output: {value: string}
                        functions:
                          trim:
                            signatures:
                              - "(text: string) -> string"
                        """, VisualLibraryAuthoringDocument.class);
        HttpHeaders createHeaders = new HttpHeaders();
        createHeaders.setContentType(MediaType.APPLICATION_JSON);
        createHeaders.setIfMatch("\"0\"");
        var created = restTemplate.exchange(
                "/admin/visual-operator-library-authoring/drafts/" + draftId,
                HttpMethod.PUT,
                new HttpEntity<>(
                        new VisualLibraryAuthoringDraftController.DraftSaveRequest(
                                "QUICK", document, "integration-test"),
                        createHeaders),
                AuthoringDraft.class
        );

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(created.getBody()).satisfies(draft -> {
            assertThat(draft.draftId()).isEqualTo(draftId);
            assertThat(draft.revision()).isEqualTo(1);
        });

        var staleSave = restTemplate.exchange(
                "/admin/visual-operator-library-authoring/drafts/" + draftId,
                HttpMethod.PUT,
                new HttpEntity<>(
                        new VisualLibraryAuthoringDraftController.DraftSaveRequest(
                                "QUICK", document, "stale-writer"),
                        createHeaders),
                AuthoringProblem.class
        );
        assertThat(staleSave.getStatusCode().value()).isEqualTo(412);
        assertThat(staleSave.getBody()).satisfies(problem ->
                assertThat(problem.code()).isEqualTo("RG.AUTHORING.DRAFT_REVISION_STALE"));

        HttpHeaders revisionHeaders = new HttpHeaders();
        revisionHeaders.setIfMatch("\"1\"");
        var previewed = restTemplate.exchange(
                "/admin/visual-operator-library-authoring/drafts/" + draftId + "/preview",
                HttpMethod.POST,
                new HttpEntity<>(revisionHeaders),
                AuthoringCompileResult.class
        );
        assertThat(previewed.getStatusCode().is2xxSuccessful()).isTrue();
        AuthoringCompileResult preview = previewed.getBody();
        assertThat(preview).isNotNull();
        assertThat(preview.importable()).isTrue();
        assertThat(preview.draftId()).isEqualTo(draftId);

        revisionHeaders.setContentType(MediaType.APPLICATION_JSON);
        var operatorDraft = restTemplate.exchange(
                "/admin/visual-operator-library-authoring/drafts/" + draftId
                        + "/tests/operators/draft",
                HttpMethod.POST,
                new HttpEntity<>(
                        new OperatorDraftRequest(
                                OperatorDraftRequest.SCHEMA_VERSION,
                                new VisualOperatorContractTestDraftRequest(
                                        VisualOperatorContractTestDraftRequest.SCHEMA_VERSION,
                                        "integration:echo",
                                        "integration echo",
                                        true,
                                        Map.of(),
                                        Map.of(),
                                        Map.of())),
                        revisionHeaders),
                OperatorDraft.class
        );
        assertThat(operatorDraft.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(operatorDraft.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(operatorDraft.getBody()).satisfies(generated -> {
            assertThat(generated.authoringRevision()).isEqualTo(1);
            assertThat(generated.suite().cases()).hasSize(1);
            assertThat(generated.payloadPersisted()).isFalse();
        });

        var operatorRun = restTemplate.exchange(
                "/admin/visual-operator-library-authoring/drafts/" + draftId
                        + "/tests/operators/run",
                HttpMethod.POST,
                new HttpEntity<>(
                        new OperatorRunRequest(
                                OperatorRunRequest.SCHEMA_VERSION,
                                operatorDraft.getBody().suite()),
                        revisionHeaders),
                OperatorRunEvidence.class
        );
        assertThat(operatorRun.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(operatorRun.getBody()).satisfies(evidence -> {
            assertThat(evidence.result().passed()).isTrue();
            assertThat(evidence.evidenceFingerprint()).startsWith("sha256:");
            assertThat(evidence.payloadPersisted()).isFalse();
        });

        var functionDraft = restTemplate.exchange(
                "/admin/visual-operator-library-authoring/drafts/" + draftId
                        + "/tests/functions/draft",
                HttpMethod.POST,
                new HttpEntity<>(
                        new FunctionDraftRequest(
                                FunctionDraftRequest.SCHEMA_VERSION,
                                "trim"),
                        revisionHeaders),
                FunctionDraft.class
        );
        assertThat(functionDraft.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(functionDraft.getBody()).satisfies(generated -> {
            assertThat(generated.bindingStatus()).isEqualTo(FunctionBindingStatus.BOUND);
            assertThat(generated.suite().cases()).hasSize(1);
            assertThat(generated.payloadPersisted()).isFalse();
        });

        var functionRun = restTemplate.exchange(
                "/admin/visual-operator-library-authoring/drafts/" + draftId
                        + "/tests/functions/run",
                HttpMethod.POST,
                new HttpEntity<>(
                        new FunctionRunRequest(
                                FunctionRunRequest.SCHEMA_VERSION,
                                functionDraft.getBody().suite()),
                        revisionHeaders),
                FunctionRunEvidence.class
        );
        assertThat(functionRun.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(functionRun.getBody()).satisfies(evidence -> {
            assertThat(evidence.passed()).isTrue();
            assertThat(evidence.results()).singleElement()
                    .satisfies(result -> assertThat(result.actual()).isEqualTo("sample"));
            assertThat(evidence.payloadPersisted()).isFalse();
        });

        SampleInferenceRequest inferenceRequest = new SampleInferenceRequest(
                SampleInferenceRequest.SCHEMA_VERSION,
                new SampleInferenceRequest.Target(
                        "OPERATOR", "integration:echo", "INPUT", "value"),
                List.of(
                        objectMapper.readTree(
                                "{\"id\":\"one\",\"privateToken\":\"sensitive-A\"}"),
                        objectMapper.readTree(
                                "{\"id\":\"two\",\"privateToken\":\"sensitive-B\"}")
                ),
                SampleInferenceRequest.Options.defaults(),
                "integration-inference"
        );
        var inferred = restTemplate.exchange(
                "/admin/visual-operator-library-authoring/drafts/" + draftId + "/infer/samples",
                HttpMethod.POST,
                new HttpEntity<>(inferenceRequest, revisionHeaders),
                SampleInferenceResult.class
        );
        assertThat(inferred.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(inferred.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(inferred.getBody()).satisfies(result -> {
            assertThat(result.draftId()).isEqualTo(draftId);
            assertThat(result.authoringRevision()).isEqualTo(1);
            assertThat(result.payloadPersisted()).isFalse();
            assertThat(result.observations())
                    .filteredOn(observation -> observation.authoringPath().endsWith("privateToken"))
                    .allSatisfy(observation -> assertThat(observation.sensitive()).isTrue());
            assertThat(objectMapper.writeValueAsString(result))
                    .doesNotContain("sensitive-A")
                    .doesNotContain("sensitive-B");
        });

        SampleInferenceResult inference = inferred.getBody();
        SampleInferenceApplyRequest apply = new SampleInferenceApplyRequest(
                SampleInferenceApplyRequest.SCHEMA_VERSION,
                inferenceRequest,
                inference.evidenceFingerprint(),
                inference.confirmationRequests().stream()
                        .map(confirmation -> new SampleInferenceApplyRequest.Decision(
                                confirmation.confirmationId(),
                                confirmation.recommendedValue()
                        ))
                        .toList(),
                "integration-test"
        );
        var applied = restTemplate.exchange(
                "/admin/visual-operator-library-authoring/drafts/" + draftId
                        + "/infer/samples/apply",
                HttpMethod.POST,
                new HttpEntity<>(apply, revisionHeaders),
                AuthoringDraft.class
        );
        assertThat(applied.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(applied.getHeaders().getETag()).isEqualTo("\"2\"");
        assertThat(applied.getBody()).satisfies(draft -> {
            assertThat(draft.revision()).isEqualTo(2);
            assertThat(draft.evidence()).hasSize(1);
            assertThat(draft.confirmations())
                    .hasSize(inference.confirmationRequests().size());
            assertThat(objectMapper.writeValueAsString(draft))
                    .doesNotContain("\"samples\"")
                    .doesNotContain("sensitive-A")
                    .doesNotContain("sensitive-B");
        });

        HttpHeaders appliedHeaders = new HttpHeaders();
        appliedHeaders.setContentType(MediaType.APPLICATION_JSON);
        appliedHeaders.setIfMatch("\"2\"");
        var promotedPreviewResponse = restTemplate.exchange(
                "/admin/visual-operator-library-authoring/drafts/" + draftId + "/preview",
                HttpMethod.POST,
                new HttpEntity<>(appliedHeaders),
                AuthoringCompileResult.class
        );
        AuthoringCompileResult promotedPreview = promotedPreviewResponse.getBody();
        assertThat(promotedPreview).isNotNull();
        assertThat(promotedPreview.authoringRevision()).isEqualTo(2);
        assertThat(promotedPreview.importable()).isTrue();

        AuthoringDraftService.CommitRequest commitRequest =
                new AuthoringDraftService.CommitRequest(
                        promotedPreview.authoringFingerprint(),
                        promotedPreview.compileFingerprint(),
                        promotedPreview.catalogFingerprint(),
                        promotedPreview.canonicalFingerprint(),
                        promotedPreview.diff().baseRevision(),
                        "integration-test",
                        "Verified lifecycle integration"
                );
        var committed = restTemplate.exchange(
                "/admin/visual-operator-library-authoring/drafts/" + draftId + "/commit",
                HttpMethod.POST,
                new HttpEntity<>(commitRequest, appliedHeaders),
                AuthoringCommitResult.class
        );
        assertThat(committed.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(committed.getBody()).satisfies(result -> {
            assertThat(result.draftId()).isEqualTo(draftId);
            assertThat(result.targetRevision()).isEqualTo(1);
            assertThat(result.library().libraryId()).isEqualTo(draftId);
        });
    }

    @Test
    void everyBuiltInGraphProducesAStableCompleteCapabilityClosure() {
        CapabilityProjectionContext context = capabilityProjectionContext();
        Map<String, Integer> expectedDependencies = Map.of(
                "aiEnrichedSearch", 3,
                "creditScore", 2,
                "enrichOrderList", 3,
                "loanDecisionPolicy", 1,
                "productDetail", 3,
                "resourceDispatch", 1,
                "userDashboard", 5);

        Map<String, CapabilityClosure> first = builtInCapabilityClosures.projectAll(context);
        Map<String, CapabilityClosure> second = builtInCapabilityClosures.projectAll(context);

        assertThat(first.keySet()).containsExactlyInAnyOrderElementsOf(expectedDependencies.keySet());
        assertThat(first).allSatisfy((graphName, closure) -> {
            CapabilityClosureIntegrity.verify(objectMapper, closure);
            CapabilitySnapshot root = closure.snapshots().stream()
                    .filter(snapshot -> snapshot.capabilityId().equals(closure.rootRef().id()))
                    .findFirst().orElseThrow();
            assertThat(root.source().sourceKind()).isEqualTo(CapabilitySnapshot.SourceKind.GRAPH);
            assertThat(root.contract().inputSchema()).isEqualTo(graphContracts.require(graphName).inputSchema());
            assertThat(root.contract().outputSchema()).isEqualTo(graphContracts.require(graphName).outputSchema());
            assertThat(root.dependencies()).hasSize(expectedDependencies.get(graphName));
            assertThat(closure.snapshots()).hasSize(expectedDependencies.get(graphName) + 1);
            if (graphName.equals("enrichOrderList")) {
                assertThat(root.dependencies()).filteredOn(dependency -> !dependency.required())
                        .extracting(CapabilitySnapshot.Dependency::nodeId)
                        .containsExactlyInAnyOrder("enrichOrders_fetchInvoice",
                                "enrichOrders_fetchShippingStatus");
                assertThat(root.dependencies()).filteredOn(dependency -> !dependency.required())
                        .allSatisfy(dependency -> assertThat(dependency.conditions())
                                .containsExactly("foreach:enrichOrders"));
            }
            if (graphName.equals("resourceDispatch")) {
                assertThat(root.contract().effect().mode()).isEqualTo(EffectContract.Mode.UNKNOWN);
                assertThat(root.runtime().ready()).isFalse();
                assertThat(root.dependencies().getFirst().capabilityRef().id())
                        .isEqualTo("operator:httpResource");
            } else {
                assertThat(root.contract().effect().mode()).isEqualTo(EffectContract.Mode.READ_ONLY);
                if (graphName.equals("aiEnrichedSearch")) {
                    assertThat(root.runtime().ready()).isFalse();
                    assertThat(root.runtime().limitations()).allMatch(value -> value.contains("RUNTIME_BLOCKED"));
                } else {
                    assertThat(root.runtime().ready()).isTrue();
                }
            }
        });
        assertThat(second).allSatisfy((graphName, closure) ->
                assertThat(closure.fingerprint()).isEqualTo(first.get(graphName).fingerprint()));
    }

    @Test
    void builtInClosureProjectionNormalizesMissingResourceFailure() {
        registry.deregister("order-service.listOrders");

        assertThatThrownBy(() -> builtInCapabilityClosures.project("enrichOrderList",
                capabilityProjectionContext()))
                .isInstanceOfSatisfying(CapabilityProjectionException.Failure.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo("RG.MIRROR.RESOURCE_DESCRIPTOR_MISSING"));
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

    private static CapabilityProjectionContext capabilityProjectionContext() {
        return new CapabilityProjectionContext(1, "demo-tenant", "support", "gateway-examples",
                "test", "sg", "MIRROR_REHEARSAL",
                new CapabilitySnapshot.Ownership("gateway-owner", "gateway-examples", "pager-gateway"),
                CapabilitySnapshot.Lifecycle.DRAFT, CapabilityContract.DataClassification.INTERNAL,
                List.of("sg"), false, "", null, Instant.parse("2026-08-22T00:00:00Z"),
                Instant.parse("2026-07-22T08:00:00Z"));
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
