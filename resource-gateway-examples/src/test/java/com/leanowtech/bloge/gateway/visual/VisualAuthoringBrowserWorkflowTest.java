package com.leanowtech.bloge.gateway.visual;

import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.gateway.GatewayProperties;
import com.leanowtech.bloge.gateway.gateway.ResourceDescriptorBootstrap;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.connection.VisualConnectionCheckRequest;
import com.leanowtech.bloge.gateway.visual.connection.VisualConnectionCheckResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublishRequest;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImportRequest;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualStoredDraftRunRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser-facing smoke coverage for the visual authoring workflow.
 */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.seed-descriptors=false",
                "spring.datasource.url=jdbc:h2:mem:visual-authoring-browser-workflow;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        }
)
class VisualAuthoringBrowserWorkflowTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WritableResourceRegistry resourceRegistry;

    @LocalServerPort
    private int port;

    @BeforeEach
    void seedDemoDescriptorsForRandomPort() {
        resourceRegistry.all().stream()
                .map(descriptor -> descriptor.resourceId())
                .toList()
                .forEach(resourceRegistry::deregister);

        GatewayProperties properties = new GatewayProperties();
        properties.setBaseUrl("http://localhost:" + port + "/demo-upstream");
        properties.setSeedDescriptors(true);
        new ResourceDescriptorBootstrap(resourceRegistry, properties).seedDescriptors();
    }

    @Test
    @SuppressWarnings("unchecked")
    void browserAuthoringWorkflowLoadsCatalogChecksConnectionRunsPublishesAndAudits() {
        assertBrowserAssetsExposeVisualWorkflowEntrypoints();
        assertOpenApiPreviewFeedsTheResourceContractWorkflow();

        OperatorLibrary importedLibrary = importEligibilityLibrary();
        Map<String, Object> catalog = getMap("/api/visual/operators?tenantId=demo-tenant&namespace=local&environment=browser");
        List<Map<String, Object>> operators = (List<Map<String, Object>>) catalog.get("operators");
        assertThat(operators).anySatisfy(operator ->
                assertThat(operator).containsEntry("operatorRef", "resource:loan-applicant-service.getProfile"));
        assertThat(operators).anySatisfy(operator ->
                assertThat(operator).containsEntry("operatorRef", "risk:eligibility"));
        assertThat(operators).anySatisfy(operator -> {
            assertThat(operator).containsEntry("operatorRef", "httpRequest");
            assertThat((Map<String, Object>) operator.get("source")).containsEntry("kind", "java-operator");
        });
        assertThat(importedLibrary.libraryId()).isEqualTo("risk-policy");

        GraphDraft preflightDraft = resourceEligibilityDraft(false);
        ResponseEntity<VisualConnectionCheckResult> connectionResponse = restTemplate.postForEntity(
                "/api/visual/connections/check",
                new VisualConnectionCheckRequest(
                        preflightDraft,
                        new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                        "data"
                ),
                VisualConnectionCheckResult.class
        );
        assertThat(connectionResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(connectionResponse.getBody()).isNotNull();
        assertThat(connectionResponse.getBody().accepted()).isTrue();
        assertThat(connectionResponse.getBody().bindingKey()).isEqualTo("score");
        assertThat(connectionResponse.getBody().diagnostics()).isEmpty();

        ResponseEntity<GraphDraft> createResponse = restTemplate.postForEntity(
                "/api/visual/drafts",
                resourceEligibilityDraft(true),
                GraphDraft.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        GraphDraft storedDraft = createResponse.getBody();
        assertThat(storedDraft).isNotNull();
        assertThat(storedDraft.draftId()).isNotBlank();
        assertThat(storedDraft.revision()).isEqualTo(1);
        assertThat(storedDraft.operatorFingerprints()).containsKeys("fetchApplicant", "eligibility");

        Map<String, Object> draftExport = getMap("/api/visual/drafts/" + storedDraft.draftId() + "/export");
        assertThat(draftExport)
                .containsEntry("schemaVersion", "bloge.visualGraphDraftExport.v1")
                .containsEntry("sourceDraftId", storedDraft.draftId())
                .containsEntry("sourceRevision", 1);
        assertThat((List<Map<String, Object>>) draftExport.get("operatorSnapshots"))
                .extracting(snapshot -> String.valueOf(snapshot.get("operatorRef")))
                .contains("resource:loan-applicant-service.getProfile", "risk:eligibility");
        Map<String, Object> draftImport = postMap("/api/visual/drafts/import", draftExport, HttpStatus.CREATED);
        assertThat(draftImport)
                .containsEntry("schemaVersion", "bloge.visualGraphDraftImportResult.v1")
                .containsEntry("imported", true);
        assertThat((List<Map<String, Object>>) draftImport.get("diagnostics")).isEmpty();
        Map<String, Object> importedDraft = (Map<String, Object>) draftImport.get("draft");
        assertThat(importedDraft)
                .containsEntry("graphName", "browserSmokePolicy")
                .containsEntry("revision", 1);
        assertThat(String.valueOf(importedDraft.get("draftId"))).isNotBlank().isNotEqualTo(storedDraft.draftId());
        assertThat((Map<String, Object>) importedDraft.get("operatorFingerprints"))
                .containsKeys("fetchApplicant", "eligibility");

        ResponseEntity<VisualGraphRunResponse> runResponse = restTemplate.postForEntity(
                "/api/visual/drafts/" + storedDraft.draftId() + "/run",
                new VisualStoredDraftRunRequest(Map.of("applicantId", "standard", "amount", 100_000), "",
                        storedDraft.revision()),
                VisualGraphRunResponse.class
        );
        assertThat(runResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        VisualGraphRunResponse run = runResponse.getBody();
        assertThat(run).isNotNull();
        assertThat(run.validated()).isTrue();
        assertThat(run.compiled()).isTrue();
        assertThat(run.success()).isTrue();
        assertThat(run.errors()).isEmpty();
        assertThat(run.output()).isEqualTo(Boolean.TRUE);
        assertThat(run.statusMap()).containsEntry("fetchApplicant", "COMPLETED")
                .containsEntry("eligibility", "COMPLETED");
        assertThat(run.generatedDsl()).contains("node fetchApplicant : httpResource")
                .contains("transform eligibility");
        assertThat(run.runId()).isNotBlank();

        Map<String, Object> storedRunRecord = getMap("/api/visual/runs/" + run.runId());
        assertThat(storedRunRecord)
                .containsEntry("sourceKind", "STORED_DRAFT")
                .containsEntry("draftId", storedDraft.draftId())
                .containsEntry("success", true);
        assertThat((Map<String, Object>) storedRunRecord.get("outputSummary"))
                .containsEntry("type", "boolean");
        assertThat((Map<String, Object>) storedRunRecord.get("nodeSnapshots"))
                .containsKeys("fetchApplicant", "eligibility");

        Map<String, Object> storedRunTrace = getMap("/api/visual/runs/" + run.runId() + "/trace");
        assertThat(storedRunTrace)
                .containsEntry("schemaVersion", "bloge.visualGraphRunTrace.v1")
                .containsEntry("runId", run.runId());
        List<Map<String, Object>> traceNodes = (List<Map<String, Object>>) storedRunTrace.get("nodes");
        assertThat(traceNodes).anySatisfy(node -> assertThat(node)
                .containsEntry("nodeId", "fetchApplicant")
                .containsEntry("operatorRef", "resource:loan-applicant-service.getProfile")
                .containsEntry("diagnosticCount", 0));
        assertThat(traceNodes).anySatisfy(node -> assertThat(node)
                .containsEntry("nodeId", "eligibility")
                .containsEntry("operatorRef", "risk:eligibility")
                .containsEntry("outputSelected", true));

        Map<String, Object> publishResult = postMap(
                "/api/visual/drafts/" + storedDraft.draftId() + "/publish",
                new VisualGraphPublishRequest(storedDraft.revision()),
                HttpStatus.CREATED
        );
        assertThat(publishResult).containsEntry("published", true);
        Map<String, Object> publication = (Map<String, Object>) publishResult.get("publication");
        assertThat(publication).isNotNull();
        String publicationId = String.valueOf(publication.get("publicationId"));
        assertThat(publicationId).isNotBlank();

        Map<String, Object> publicationRun = postMap(
                "/api/visual/publications/" + publicationId + "/run",
                Map.of("context", Map.of("applicantId", "standard", "amount", 100_000), "outputNode", "")
        );
        assertThat(publicationRun)
                .containsEntry("success", true)
                .containsEntry("output", true);
        assertThat(String.valueOf(publicationRun.get("runId"))).isNotBlank();

        Map<String, Object> goldenCase = postMap("/api/visual/golden-cases", Map.of(
                "publicationId", publicationId,
                "name", "standard applicant",
                "description", "Browser workflow regression fixture",
                "outputNode", "",
                "context", Map.of("applicantId", "standard", "amount", 100_000),
                "expectedOutput", false,
                "assertions", List.of(Map.of(
                        "mode", "OUTPUT_EQUALS",
                        "expectedValue", true
                ))
        ));
        assertThat(goldenCase)
                .containsEntry("schemaVersion", "bloge.visualGraphGoldenCase.v1")
                .containsEntry("publicationId", publicationId);
        assertThat((List<Map<String, Object>>) goldenCase.get("assertions")).singleElement()
                .satisfies(assertion -> assertThat(assertion)
                        .containsEntry("mode", "OUTPUT_EQUALS")
                        .containsEntry("expectedValue", true));
        String goldenCaseId = String.valueOf(goldenCase.get("caseId"));
        assertThat(goldenCaseId).isNotBlank();

        Map<String, Object> goldenResult = postMap("/api/visual/golden-cases/" + goldenCaseId + "/run", Map.of());
        assertThat(goldenResult).containsEntry("passed", true);
        assertThat((Map<String, Object>) goldenResult.get("run"))
                .containsEntry("success", true)
                .containsEntry("output", true);

        Map<String, Object> goldenSuite = postMap(
                "/api/visual/golden-cases/publications/" + publicationId + "/run",
                Map.of()
        );
        assertThat(goldenSuite)
                .containsEntry("passed", true)
                .containsEntry("totalCases", 1)
                .containsEntry("passedCases", 1)
                .containsEntry("failedCases", 0);

        Map<String, Object> certification = postMap(
                "/api/visual/golden-cases/publications/" + publicationId + "/certify",
                Map.of()
        );
        assertThat(certification)
                .containsEntry("schemaVersion", "bloge.visualGraphGoldenCertification.v1")
                .containsEntry("publicationId", publicationId)
                .containsEntry("certified", true)
                .containsEntry("totalCases", 1)
                .containsEntry("passedCases", 1)
                .containsEntry("failedCases", 0);
        assertThat(String.valueOf(certification.get("caseSetFingerprint"))).isNotBlank();
        assertThat((List<String>) certification.get("runIds")).hasSize(1);
        assertThat(getMap("/api/visual/golden-cases/publications/" + publicationId + "/certification"))
                .containsEntry("certified", true)
                .containsEntry("publicationId", publicationId);
        assertThat(getMap("/api/visual/golden-cases/publications/" + publicationId + "/certification/status"))
                .containsEntry("schemaVersion", "bloge.visualGraphGoldenCertificationStatus.v1")
                .containsEntry("publicationId", publicationId)
                .containsEntry("status", "CERTIFIED")
                .containsEntry("promotionReady", true)
                .containsEntry("caseCount", 1);

        Collection<?> runHistory = restTemplate.getForObject("/api/visual/runs", Collection.class);
        assertThat(runHistory).hasSizeGreaterThanOrEqualTo(5);

        Collection<?> storedDraftRuns = restTemplate.getForObject(
                "/api/visual/runs?sourceKind=stored_draft&draftId=" + storedDraft.draftId()
                        + "&success=true&limit=1",
                Collection.class
        );
        assertThat(storedDraftRuns).singleElement().satisfies(record -> {
            Map<String, Object> runRecord = (Map<String, Object>) record;
            assertThat(runRecord)
                    .containsEntry("sourceKind", "STORED_DRAFT")
                    .containsEntry("draftId", storedDraft.draftId())
                    .containsEntry("success", true);
        });

        Collection<?> publicationRuns = restTemplate.getForObject(
                "/api/visual/runs?sourceKind=PUBLICATION&publicationId=" + publicationId + "&limit=1",
                Collection.class
        );
        assertThat(publicationRuns).singleElement().satisfies(record -> {
            Map<String, Object> runRecord = (Map<String, Object>) record;
            assertThat(runRecord)
                    .containsEntry("sourceKind", "PUBLICATION")
                    .containsEntry("publicationId", publicationId);
        });

        Map<String, Object> runStats = getMap("/api/visual/runs/stats?graphName=browserSmokePolicy&limit=10");
        assertThat(runStats)
                .containsEntry("schemaVersion", "bloge.visualGraphRunStats.v1")
                .containsEntry("totalRuns", 5)
                .containsEntry("successfulRuns", 5)
                .containsEntry("failedRuns", 0);
        assertThat((Map<String, Object>) runStats.get("bySourceKind"))
                .containsEntry("STORED_DRAFT", 1)
                .containsEntry("PUBLICATION", 4);

        Map<String, Object> nodeStats = getMap("/api/visual/runs/node-stats?graphName=browserSmokePolicy&limit=10");
        assertThat(nodeStats)
                .containsEntry("schemaVersion", "bloge.visualGraphRunNodeStats.v1")
                .containsEntry("totalRuns", 5);
        Collection<?> nodeStatsRows = (Collection<?>) nodeStats.get("nodes");
        assertThat(nodeStatsRows).isNotEmpty();
        assertThat(nodeStatsRows).anySatisfy(row -> {
            Map<String, Object> node = (Map<String, Object>) row;
            assertThat(((Number) node.get("timingKnownRuns")).intValue()).isGreaterThan(0);
            assertThat(node).containsKey("p95NodeElapsedMs");
        });
    }

    private void assertBrowserAssetsExposeVisualWorkflowEntrypoints() {
        ResponseEntity<String> page = restTemplate.getForEntity("/examples/gateway", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(page.getBody())
                .contains("Resource Gateway Showcase")
                .contains("/examples/gateway/app.js")
                .contains("id=\"scenarios\"")
                .contains("id=\"run-scenario\"");

        ResponseEntity<String> app = restTemplate.getForEntity("/examples/gateway/app.js", String.class);
        assertThat(app.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(app.getBody())
                .contains("loadVisualOperatorCatalog")
                .contains("/api/visual/connections/check")
                .contains("bindingKey")
                .contains("targetWithServerBindingKey")
                .contains("/api/visual/drafts/run")
                .contains("/api/visual/drafts/import")
                .contains("/export")
                .contains("/api/visual/publications")
                .contains("/api/visual/runs")
                .contains("/api/visual/runs/stats")
                .contains("/api/visual/golden-cases")
                .contains("/api/visual/golden-cases/publications/")
                .contains("/certify")
                .contains("/certification")
                .contains("/certification/status")
                .contains("SUPPORTED_SCHEMA_UNION_KEYWORDS")
                .contains("schema-union-summary")
                .contains("targetUnionBranch")
                .contains("binding-union-branch")
                .contains("target oneOf is ambiguous")
                .contains("/admin/visual-operator-libraries")
                .contains("preview-resource-contract")
                .contains("save-resource-contract")
                .contains("save-resource-descriptor")
                .contains("export-draft")
                .contains("import-draft")
                .contains("draft-bundle-json")
                .contains("run-history-list")
                .contains("run-history-stats")
                .contains("run-history-node-stats")
                .contains("/api/visual/runs/${encodeURIComponent(runId)}/trace")
                .contains("/api/visual/runs/node-stats")
                .contains("activeRunTrace")
                .contains("runTraceCanvasCoverage")
                .contains("runTraceCoverageText")
                .contains("node-trace-badge")
                .contains("save-golden-case")
                .contains("golden-assertion-mode")
                .contains("golden-assertion-value")
                .contains("add-golden-assertion")
                .contains("clear-golden-assertions")
                .contains("golden-assertion-list")
                .contains("OUTPUT_EQUALS")
                .contains("OUTPUT_MATCHES_SCHEMA")
                .contains("PATH_APPROX_EQUALS")
                .contains("run-golden-case")
                .contains("run-golden-suite")
                .contains("certify-golden-suite")
                .contains("/admin/resource-design-contracts/from-openapi")
                .contains("/admin/resources")
                .contains("/admin/resource-design-contracts/${encodeURIComponent(contract.resourceId)}");
    }

    @SuppressWarnings("unchecked")
    private void assertOpenApiPreviewFeedsTheResourceContractWorkflow() {
        Map<String, Object> preview = postMap("/admin/resource-design-contracts/from-openapi",
                new OpenApiResourceDesignContractImportRequest(
                        "loan-applicant-service.getProfile",
                        null,
                        "/api/loan-applicants/{applicantId}",
                        "GET",
                        null,
                        null,
                        openApiLoanApplicantYaml()
                ));
        assertThat((Map<String, Object>) preview.get("validation")).containsEntry("valid", true);
        Map<String, Object> contract = (Map<String, Object>) preview.get("contract");
        assertThat(contract).containsEntry("resourceId", "loan-applicant-service.getProfile");
        assertThat((Map<String, Object>) contract.get("requestSchema")).isNotEmpty();
        assertThat((Map<String, Object>) contract.get("responseSchema")).isNotEmpty();
        Map<String, Object> descriptor = (Map<String, Object>) preview.get("descriptorSuggestion");
        assertThat(descriptor)
                .containsEntry("resourceId", "loan-applicant-service.getProfile")
                .containsEntry("urlTemplate", "https://api.example.test/api/loan-applicants/{applicantId}");
        assertThat((Map<String, Object>) descriptor.get("authStrategy"))
                .containsEntry("type", "apiKey")
                .containsEntry("headerName", "X-Api-Key")
                .containsEntry("key", "CHANGE_ME_API_KEY");
        Map<String, Object> parameterMapping = (Map<String, Object>) descriptor.get("parameterMapping");
        assertThat((Map<String, Object>) parameterMapping.get("cookieExpressions"))
                .containsEntry("SESSION", "ctx.params.SESSION");

        Map<String, Object> saved = putMap(
                "/admin/resource-design-contracts/loan-applicant-service.getProfile",
                contract
        );
        assertThat(saved).containsEntry("resourceId", "loan-applicant-service.getProfile");
    }

    private OperatorLibrary importEligibilityLibrary() {
        ResponseEntity<OperatorLibrary> response = restTemplate.postForEntity(
                "/admin/visual-operator-libraries",
                VisualCatalogTestSupport.eligibilityLibrary("integer"),
                OperatorLibrary.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static GraphDraft resourceEligibilityDraft(boolean includeScoreConnection) {
        Map<String, GraphDraft.Binding> eligibilityInputs = new java.util.LinkedHashMap<>();
        if (includeScoreConnection) {
            eligibilityInputs.put("score", GraphDraft.Binding.nodePath(
                    "fetchApplicant",
                    "payload",
                    "score",
                    "inputs",
                    "score"
            ));
        }
        eligibilityInputs.put("amount", GraphDraft.Binding.contextPath("amount", "inputs", "amount"));

        List<GraphDraft.DraftEdge> edges = includeScoreConnection
                ? List.of(new GraphDraft.DraftEdge(
                        "score",
                        "data",
                        new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "score")
                ))
                : List.of();

        return new GraphDraft(
                "",
                "",
                0,
                "browserSmokePolicy",
                "demo-tenant",
                "local",
                "browser",
                "",
                graphInputSchema(),
                List.of(
                        new GraphDraft.DraftNode(
                                "fetchApplicant",
                                "resource:loan-applicant-service.getProfile",
                                "Fetch Applicant",
                                Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                                Map.of(),
                                new GraphDraft.Position(80, 160)
                        ),
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:eligibility",
                                "Eligibility",
                                eligibilityInputs,
                                Map.of(),
                                new GraphDraft.Position(360, 160)
                        )
                ),
                edges,
                Map.of("viewport", Map.of("zoom", 1)),
                new GraphDraft.OutputSelection("eligibility", "eligible")
        );
    }

    private static SchemaEnvelope graphInputSchema() {
        return SchemaEnvelope.object(Map.of(
                "applicantId", Map.of("type", "string"),
                "amount", Map.of("type", "number")
        ), List.of("applicantId", "amount"));
    }

    private Map<String, Object> getMap(String path) {
        ResponseEntity<Map> response = restTemplate.getForEntity(path, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private Map<String, Object> postMap(String path, Object request) {
        return postMap(path, request, HttpStatusCode.valueOf(200));
    }

    private Map<String, Object> postMap(String path, Object request, HttpStatusCode expectedStatus) {
        ResponseEntity<Map> response = restTemplate.postForEntity(path, request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> putMap(String path, Object request) {
        ResponseEntity<Map> response = restTemplate.exchange(
                path,
                HttpMethod.PUT,
                new HttpEntity<>(request),
                Map.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static String openApiLoanApplicantYaml() {
        return """
                openapi: 3.0.3
                info:
                  title: Loan Applicant API
                  version: 1.0.0
                servers:
                  - url: https://api.example.test
                security:
                  - ApiKeyAuth: []
                paths:
                  /api/loan-applicants/{applicantId}:
                    get:
                      operationId: getLoanApplicant
                      parameters:
                        - name: applicantId
                          in: path
                          required: true
                          schema:
                            type: string
                        - name: SESSION
                          in: cookie
                          schema:
                            type: string
                      responses:
                        '200':
                          description: Applicant facts
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  score:
                                    type: integer
                                  segment:
                                    type: string
                                  income:
                                    type: number
                                required:
                                  - score
                components:
                  securitySchemes:
                    ApiKeyAuth:
                      type: apiKey
                      in: header
                      name: X-Api-Key
                """;
    }
}
