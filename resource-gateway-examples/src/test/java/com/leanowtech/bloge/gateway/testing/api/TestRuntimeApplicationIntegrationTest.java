package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.integration.IntegrationCapabilities;
import com.leanowtech.bloge.gateway.integration.IntegrationEnvelope;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Full application proof for profile-gated testing control-plane assembly. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "gateway.seed-descriptors=true",
                "gateway.base-url=http://127.0.0.1:1",
                "gateway.integration.identity.environment-id=test",
                "gateway.integration.identity.region=region-a",
                "gateway.integration.identity.groups=resource-gateway-test-runtime-operators",
                "gateway.integration.identity.clearance=RESTRICTED",
                "gateway.integration.identity.allowed-purposes=TEST_EXECUTION,TEST_FIXTURE_READ,TEST_FIXTURE_WRITE,TEST_REPLAY,TEST_SUITE_READ,TEST_SUITE_WRITE,TEST_RUNTIME_MAINTENANCE",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id=integration-test-v1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring=integration-test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id=integration-request-index-v1",
                "gateway.testing.durable.worker-quarantines.request-key-protection.key-ring=integration-request-index-v1=HyAdHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.write-mode=KEYED_ONLY",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id=integration-replica-a",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "spring.datasource.url=jdbc:h2:mem:testing-app-main;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:testing-app-control;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        }
)
class TestRuntimeApplicationIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FixtureBundleRepository fixtureRepository;

    @Autowired
    private VisualEvidenceSigner evidenceSigner;

    @Test
    void realApplicationAdvertisesAndServesTheProfileGatedTargetProtocol() throws Exception {
        assertThat(context.getBeansOfType(TestExecutionController.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestRunRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestSuiteRunRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestSuiteRunLeaseCoordinator.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestSuiteRunReconciliationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestSuiteRunReconciliationScheduler.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestBoundarySuiteController.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestBoundarySuiteMaterializationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                DurableStateProjectionReconciliationScheduler.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                DatabaseDurableStateProjectionControlPlane.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                DurableStateProjectionFindingService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                DatabaseDurableWorkerQuarantineControlPlane.class)).hasSize(1);
        assertThat(context.getBeansOfType(DurableWorkerQuarantineService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                WorkerQuarantineChangeAuthorizationTrustStore.class)).hasSize(1);
        assertThat(context.getBeansOfType(DurableWorkerQuarantineController.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                WorkerQuarantineRequestIndexRolloutService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                WorkerQuarantineRequestIndexRolloutController.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                DurableWorkerQuarantineRetentionScheduler.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                DurableWorkerQuarantineRetentionTelemetry.class)).hasSize(1);
        assertThat(context.getBeansOfType(ReplayPayloadRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestReplayPayloadService.class)).hasSize(1);
        assertThat(context.getBeansOfType(ReplayPayloadRetentionScheduler.class)).hasSize(1);

        var capabilities = restTemplate.exchange("/api/integration/capabilities", HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<IntegrationEnvelope<IntegrationCapabilities>>() { });
        assertThat(capabilities.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capabilities.getBody()).isNotNull();
        assertThat(capabilities.getBody().payload().testability().executionEndpointEnabled()).isTrue();
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/graphs/{graphName}"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/operators/{operatorRef}"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.path().equals(
                        "/api/testing/targets/graphs/{graphName}/boundary-cases"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.path().equals(
                        "/api/testing/targets/operators/{operatorRef}/boundary-cases"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("POST") && endpoint.path().equals(
                        "/api/testing/targets/graphs/{graphName}/boundary-suites"));
        assertThat(capabilities.getBody().payload().features())
                .containsEntry("immutableTestSuiteRegistry", true)
                .containsEntry("immutableTestSuiteExecution", true)
                .containsEntry("suiteSemanticCoverageVerdict", true)
                .containsEntry("builtInGraphSuiteCatalogMaterialization", true)
                .containsEntry("schemaBoundaryCasePlanning", true)
                .containsEntry("schemaBoundarySuiteMaterialization", true)
                .containsEntry("schemaAdmissionSuiteExecution", true);
        assertThat(capabilities.getBody().payload().supportedObjects())
                .containsEntry("testBoundaryCasePlan",
                        List.of(TestBoundaryCasePlan.SCHEMA_VERSION))
                .containsEntry("testBoundarySuiteMaterializationRequest",
                        List.of(TestBoundarySuiteMaterializationRequest.SCHEMA_VERSION))
                .containsEntry("testBoundarySuiteMaterialization",
                        List.of(TestBoundarySuiteMaterializationResponse.SCHEMA_VERSION))
                .containsEntry("testSuiteExecutionResponse", List.of(
                        TestSuiteExecutionResponse.SCHEMA_VERSION_V1,
                        TestSuiteExecutionResponse.SCHEMA_VERSION,
                        TestSuiteExecutionResponse.SCHEMA_VERSION_V3,
                        TestSuiteExecutionResponse.SCHEMA_VERSION_V4))
                .containsEntry("testSuiteRunEvidence", List.of(
                        TestSuiteRunEvidence.SCHEMA_VERSION,
                        com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2.SCHEMA_VERSION,
                        TestSuiteRunEvidenceV3.SCHEMA_VERSION))
                .containsEntry("testSuiteRunAttestation", List.of(
                        TestSuiteRunAttestation.SCHEMA_VERSION,
                        TestSuiteRunAttestation.SCHEMA_VERSION_V2,
                        TestSuiteRunAttestation.SCHEMA_VERSION_V3))
                .containsEntry("testSuiteEvidenceBundle", List.of(
                        TestSuiteEvidenceBundle.SCHEMA_VERSION,
                        TestSuiteEvidenceBundle.SCHEMA_VERSION_V2,
                        TestSuiteEvidenceBundle.SCHEMA_VERSION_V3));
        assertThat(capabilities.getBody().payload().features())
                .containsEntry("suiteRunOwnerLease", true)
                .containsEntry("abandonedSuiteRunReconciliation", true)
                .containsEntry("databaseAuthoritativeTestRuntimeAdmission", true)
                .containsEntry("boundedCardinalityTestRuntimeAdmissionMetrics", true)
                .containsEntry("durableStateProjectionAntiEntropy", true)
                .containsEntry("durableStateProjectionSweepLease", true)
                .containsEntry("durableStateProjectionFindingQueue", true)
                .containsEntry("authenticatedDurableStateProjectionOperations", true)
                .containsEntry("immutableDurableStateProjectionActionAudit", true)
                .containsEntry("durableTestWorkerQuarantineMaintenance", true)
                .containsEntry("immutableDurableWorkerQuarantineHistory", true)
                .containsEntry("twoPersonDurableWorkerQuarantineDiscard", true)
                .containsEntry("externalWorkerQuarantineChangeAuthorization", false)
                .containsEntry("immutableApprovedWorkerQuarantineDiscardHistory", true)
                .containsEntry("encryptedDurableWorkerQuarantineClaimReplay", true)
                .containsEntry("hashedDurableWorkerQuarantineActiveFence", true)
                .containsEntry("keyedDurableWorkerQuarantineRequestIndex", true)
                .containsEntry("stagedDurableWorkerQuarantineRequestIndexUpgrade", true)
                .containsEntry("signedWorkerQuarantineRequestIndexReplicaProof", true)
                .containsEntry("durableWorkerQuarantineRequestIndexLegacyReadWrite", false)
                .containsEntry("durableWorkerQuarantineRequestIndexDualReadKeyedWrite", false)
                .containsEntry("durableWorkerQuarantineRequestIndexKeyedOnly", true)
                .containsEntry("boundedDurableWorkerQuarantineMaintenanceRetention", true);
        assertThat(capabilities.getBody().payload().testability()
                .workerQuarantineChangeAuthorizationTrust().available()).isFalse();
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/durable-state/projection-findings"));
        assertThat(capabilities.getBody().payload().supportedObjects())
                .containsEntry("durableStateProjectionFindingClaimRequest",
                        List.of(DurableStateProjectionFindingClaimRequest.SCHEMA_VERSION))
                .containsEntry("durableStateProjectionFindingResolutionResponse",
                        List.of(DurableStateProjectionFindingResolutionResponse.SCHEMA_VERSION))
                .containsEntry("durableWorkerQuarantineClaimRequest",
                        List.of(DurableWorkerQuarantineClaimRequest.SCHEMA_VERSION))
                .containsEntry("durableWorkerQuarantineResolutionResponse",
                        List.of(DurableWorkerQuarantineResolutionResponse.SCHEMA_VERSION))
                .containsEntry("durableWorkerQuarantineDiscardApprovalRequest",
                        List.of(DurableWorkerQuarantineDiscardApprovalRequest.SCHEMA_VERSION))
                .containsEntry("durableWorkerQuarantineApprovedDiscardResponse",
                        List.of(DurableWorkerQuarantineApprovedDiscardResponse.SCHEMA_VERSION))
                .containsEntry("workerQuarantineRequestIndexReplicaProofRequest",
                        List.of(WorkerQuarantineRequestIndexReplicaProofRequest.SCHEMA_VERSION))
                .containsEntry("workerQuarantineRequestIndexReplicaProof",
                        List.of(WorkerQuarantineRequestIndexReplicaProof.SCHEMA_VERSION));
        assertThat(capabilities.getBody().payload().endpoints())
                .anyMatch(endpoint -> endpoint.method().equals("GET")
                        && endpoint.path().equals(
                        "/api/testing/durable-state/worker-quarantines"))
                .anyMatch(endpoint -> endpoint.method().equals("GET")
                        && endpoint.path().equals(
                        "/api/testing/durable-state/worker-quarantines/history"))
                .anyMatch(endpoint -> endpoint.method().equals("POST")
                        && endpoint.path().equals(
                        "/api/testing/durable-state/worker-quarantines/claims"))
                .anyMatch(endpoint -> endpoint.method().equals("POST")
                        && endpoint.path().equals(
                        "/api/testing/durable-state/worker-quarantines/resolutions"))
                .anyMatch(endpoint -> endpoint.method().equals("POST")
                        && endpoint.path().equals(
                        "/api/testing/durable-state/worker-quarantines/discard-approvals"))
                .anyMatch(endpoint -> endpoint.method().equals("POST")
                        && endpoint.path().equals(
                        "/api/testing/durable-state/worker-quarantines/approved-discards"))
                .anyMatch(endpoint -> endpoint.method().equals("GET")
                        && endpoint.path().equals(
                        "/api/testing/durable-state/worker-quarantines/approved-discards/history"))
                .anyMatch(endpoint -> endpoint.method().equals("POST")
                        && endpoint.path().equals(
                        "/api/testing/durable-state/worker-quarantines/request-index/replica-proofs"));

        HttpHeaders rolloutHeaders = new HttpHeaders();
        rolloutHeaders.setBearerAuth("bloge-aneke-demo-token");
        rolloutHeaders.set("X-Purpose", "TEST_RUNTIME_MAINTENANCE");
        var rolloutProof = restTemplate.exchange(
                "/api/testing/durable-state/worker-quarantines/request-index/replica-proofs",
                HttpMethod.POST,
                new HttpEntity<>(new WorkerQuarantineRequestIndexReplicaProofRequest(
                        WorkerQuarantineRequestIndexReplicaProofRequest.SCHEMA_VERSION,
                        "integration_rollout_challenge_0001",
                        com.leanowtech.bloge.gateway.testing.domain
                                .WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE),
                        rolloutHeaders),
                WorkerQuarantineRequestIndexReplicaProof.class);
        assertThat(rolloutProof.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rolloutProof.getBody()).isNotNull();
        assertThat(rolloutProof.getBody().material().instanceId())
                .isEqualTo("integration-replica-a");
        assertThat(rolloutProof.getBody().material().currentMode().name())
                .isEqualTo("KEYED_ONLY");
        assertThat(rolloutProof.getBody().material().transitionAllowed()).isFalse();
        assertThat(rolloutProof.getBody().material().blockers())
                .contains("CURRENT_MODE_NOT_PREDECESSOR");
        assertThat(evidenceSigner.verify(rolloutProof.getBody().seal(),
                rolloutProof.getBody().materialFingerprint()).valid()).isTrue();
        assertThat(capabilities.getBody().payload().features())
                .containsEntry("governedTestReplayPayloadCapture", true)
                .containsEntry("testReplayBehavior", true)
                .containsEntry("signedTestRunEvidence", true)
                .containsEntry("suiteSignedChildEvidenceGate", true);
        assertThat(capabilities.getBody().payload().supportedObjects())
                .containsEntry("testExecutionResponse", List.of(
                        TestExecutionApiResponse.SCHEMA_VERSION_V1,
                        TestExecutionApiResponse.SCHEMA_VERSION))
                .containsEntry("testEvidenceIntegrity", List.of(TestEvidenceIntegrity.SCHEMA_VERSION));
        assertThat(capabilities.getBody().payload().supportedObjects())
                .containsEntry("testSuiteCatalogMaterialization",
                        List.of(TestSuiteCatalogMaterializationResponse.SCHEMA_VERSION));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("PUT")
                        && endpoint.path().equals("/api/testing/catalogs/gateway-graph-contract-v1"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("bloge-aneke-demo-token");
        headers.set("X-Purpose", "TEST_EXECUTION");
        var target = restTemplate.exchange("/api/testing/targets/graphs/loanDecisionPolicy",
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(target.getStatusCode())
                .withFailMessage("target discovery failed: %s", target.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(target.getBody()).isNotNull();
        TestGraphTargetDescriptor descriptor = objectMapper.treeToValue(
                target.getBody(), TestGraphTargetDescriptor.class);
        assertThat(descriptor.target().id()).isEqualTo("loanDecisionPolicy");
        assertThat(descriptor.target().fingerprint()).startsWith("sha256:");
        assertThat(descriptor.contract().inputSchema().schema()).isNotEmpty();
        assertThat(descriptor.certificationEligible()).isTrue();

        var boundaryCases = restTemplate.exchange(
                "/api/testing/targets/graphs/loanDecisionPolicy/boundary-cases",
                HttpMethod.GET, new HttpEntity<>(headers), TestBoundaryCasePlan.class);
        assertThat(boundaryCases.getStatusCode())
                .withFailMessage("boundary planning failed: %s", boundaryCases.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(boundaryCases.getBody()).isNotNull();
        assertThat(boundaryCases.getBody().target()).isEqualTo(descriptor.target());
        assertThat(boundaryCases.getBody().cases()).isNotEmpty();
        assertThat(boundaryCases.getBody().planFingerprint())
                .matches("sha256:[a-f0-9]{64}");

        HttpHeaders suiteWriteHeaders = new HttpHeaders();
        suiteWriteHeaders.setBearerAuth("bloge-aneke-demo-token");
        suiteWriteHeaders.set("X-Purpose", "TEST_SUITE_WRITE");
        List<String> selectedBoundaryCases = boundaryCases.getBody().cases().stream()
                .limit(3).map(TestBoundaryCasePlan.BoundaryCase::caseId).toList();
        TestBoundarySuiteMaterializationRequest materializationRequest =
                new TestBoundarySuiteMaterializationRequest("",
                        "loan-decision-schema-boundaries", "INTERNAL",
                        boundaryCases.getBody().target().fingerprint(),
                        boundaryCases.getBody().inputSchemaFingerprint(),
                        boundaryCases.getBody().planFingerprint(), selectedBoundaryCases, true);
        var materialized = restTemplate.exchange(
                "/api/testing/targets/graphs/loanDecisionPolicy/boundary-suites",
                HttpMethod.POST, new HttpEntity<>(materializationRequest, suiteWriteHeaders),
                TestBoundarySuiteMaterializationResponse.class);
        assertThat(materialized.getStatusCode())
                .withFailMessage("boundary suite materialization failed: %s", materialized.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(materialized.getBody()).isNotNull();
        assertThat(materialized.getBody().selectedCaseIds()).isEqualTo(selectedBoundaryCases);
        assertThat(materialized.getBody().coverageGapsAccepted()).isEqualTo(
                materialized.getBody().sourcePlanStatus() == TestBoundaryCasePlan.Status.PARTIAL);
        assertThat(materialized.getBody().materializationFingerprint())
                .matches("sha256:[a-f0-9]{64}");

        HttpHeaders suiteReadHeaders = new HttpHeaders();
        suiteReadHeaders.setBearerAuth("bloge-aneke-demo-token");
        suiteReadHeaders.set("X-Purpose", "TEST_SUITE_READ");
        var materializedSuite = restTemplate.exchange(
                "/api/testing/suites/loan-decision-schema-boundaries?revision="
                        + materialized.getBody().suiteRef().revision(),
                HttpMethod.GET, new HttpEntity<>(suiteReadHeaders), StoredTestSuite.class);
        assertThat(materializedSuite.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(materializedSuite.getBody()).isNotNull();
        assertThat(materializedSuite.getBody().fingerprint())
                .isEqualTo(materialized.getBody().suiteRef().fingerprint());
        assertThat(materializedSuite.getBody().suite()).isInstanceOf(TestSuiteV3.class);
        TestSuiteV3 admissionSuite = (TestSuiteV3) materializedSuite.getBody().suite();
        assertThat(admissionSuite.evaluationMode())
                .isEqualTo(TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION);
        assertThat(admissionSuite.admissionExpectations()).hasSize(selectedBoundaryCases.size());

        TestSuiteExecutionRequest admissionExecution = new TestSuiteExecutionRequest("",
                materialized.getBody().suiteRef(), "integration-schema-admission-run-1",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL,
                Map.of("source", "spring-http-schema-admission-test"));
        var admissionRun = restTemplate.exchange(
                "/api/testing/suites/loan-decision-schema-boundaries/executions",
                HttpMethod.POST, new HttpEntity<>(admissionExecution, headers),
                TestSuiteExecutionResponse.class);
        assertThat(admissionRun.getStatusCode())
                .withFailMessage("schema-admission execution failed: %s", admissionRun.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(admissionRun.getBody()).isNotNull();
        assertThat(admissionRun.getBody().schemaVersion())
                .isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V4);
        assertThat(admissionRun.getBody().evidence())
                .isInstanceOfSatisfying(TestSuiteRunEvidenceV3.class, evidence -> {
                    assertThat(evidence.status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED);
                    assertThat(evidence.admissionCoverage().status())
                            .isEqualTo(TestSuiteRunEvidenceV3.AdmissionCoverageStatus.SATISFIED);
                    assertThat(evidence.admissionResults())
                            .extracting(TestSuiteRunEvidenceV3.AdmissionCaseResult::status)
                            .containsOnly(TestSuiteRunEvidenceV3.AdmissionCaseStatus.MATCHED);
                    assertThat(evidence.caseResults()).allSatisfy(result -> {
                        assertThat(result.runId()).isBlank();
                        assertThat(result.evidenceStatus()).isNull();
                        assertThat(result.evidenceClass()).isNull();
                    });
                    assertThat(evidence.metadata()).containsEntry("businessTargetInvoked", false)
                            .containsEntry("childRunCount", 0);
                });
        assertThat(admissionRun.getBody().attestation().schemaVersion())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V3);
        assertThat(admissionRun.getBody().attestation().terminallyVerifiable()).isTrue();
        assertThat(admissionRun.getBody().attestation().childEvidenceRefs()).isEmpty();

        var admissionPortable = restTemplate.exchange("/api/testing/suite-executions/"
                        + admissionRun.getBody().suiteRunId() + "/evidence-bundle",
                HttpMethod.GET, new HttpEntity<>(headers), TestSuiteEvidenceBundle.class);
        assertThat(admissionPortable.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admissionPortable.getBody()).isNotNull();
        assertThat(admissionPortable.getBody().schemaVersion())
                .isEqualTo(TestSuiteEvidenceBundle.SCHEMA_VERSION_V3);
        assertThat(admissionPortable.getBody().attestation())
                .isEqualTo(admissionRun.getBody().attestation());
        assertThat(admissionPortable.getBody().evidence())
                .isInstanceOf(TestSuiteRunEvidenceV3.class);
        assertThat(objectMapper.valueToTree(admissionPortable.getBody()).toString())
                .doesNotContain("spring-http-schema-admission-test", "\"input\":");

        var admissionRunRead = restTemplate.exchange("/api/testing/suite-executions/"
                        + admissionRun.getBody().suiteRunId(), HttpMethod.GET,
                new HttpEntity<>(headers), TestSuiteExecutionResponse.class);
        assertThat(admissionRunRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admissionRunRead.getBody()).isEqualTo(admissionRun.getBody());

        var nestedTarget = restTemplate.exchange("/api/testing/targets/graphs/enrichOrderList",
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(nestedTarget.getStatusCode()).isEqualTo(HttpStatus.OK);
        TestGraphTargetDescriptor nestedDescriptor = objectMapper.treeToValue(
                nestedTarget.getBody(), TestGraphTargetDescriptor.class);
        assertThat(nestedDescriptor.certificationEligible()).isTrue();
        assertThat(nestedDescriptor.certificationGaps()).isEmpty();

        var operatorTarget = restTemplate.exchange("/api/testing/targets/operators/httpResource",
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(operatorTarget.getStatusCode())
                .withFailMessage("operator target discovery failed: %s", operatorTarget.getBody())
                .isEqualTo(HttpStatus.OK);
        TestOperatorTargetDescriptor operatorDescriptor = objectMapper.treeToValue(
                operatorTarget.getBody(), TestOperatorTargetDescriptor.class);
        assertThat(operatorDescriptor.target().kind()).isEqualTo("OPERATOR");
        assertThat(operatorDescriptor.target().id()).isEqualTo("httpResource");
        assertThat(operatorDescriptor.implementationFingerprint()).startsWith("sha256:");
        assertThat(operatorDescriptor.composabilityFingerprint()).startsWith("sha256:");
        assertThat(operatorDescriptor.composabilityManifest())
                .containsEntry("dependencyMode", "DECLARED")
                .containsEntry("globalStateFree", true);
        assertThat(operatorDescriptor.testabilityClass()).isEqualTo("CONDITIONAL_TRANSPORT");
        assertThat(operatorDescriptor.certificationEligible()).isTrue();
        assertThat(operatorDescriptor.certificationRequirements())
                .anyMatch(requirement -> requirement.contains("TRANSPORT"));

        FixtureBundle fixture = new FixtureBundle("", "suite-fixture", 1,
                descriptor.target().fingerprint(), "INTERNAL", null, null,
                List.of(new FixtureRule("", "applicant-profile",
                        FixtureRule.Selector.resource("loan-applicant-service.getProfile")
                                .matching(FixtureRule.Match.pathEquals(
                                        "/params", Map.of("applicantId", "prime"))),
                        FixtureRule.Behavior.protocolResponse(
                                "{\"code\":0,\"message\":\"OK\",\"data\":{\"applicantId\":\"prime\",\"score\":780,\"segment\":\"private-bank\"}}",
                                200, Map.of(), FixtureRule.DoubleBoundary.TRANSPORT),
                        new FixtureRule.Consumption(true, 1, 2,
                                FixtureRule.ExhaustedAction.FAIL,
                                FixtureRule.UnmatchedAction.FAIL),
                        FixtureRule.SchemaCheck.strict())),
                List.of(), Map.of());
        String fixtureFingerprint = ProtocolFingerprint.of(objectMapper, fixture);
        fixtureRepository.create(new StoredFixtureBundle("", "tenant-a", "test", "suite-fixture", 1,
                fixtureFingerprint, fixture, Instant.now(), "integration-test"));
        TestSuite suite = new TestSuite("", "suite-integration", 1,
                new TestSuite.Target("GRAPH", descriptor.target().id(), descriptor.target().fingerprint()),
                "INTERNAL", List.of(new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN,
                Map.of("applicantId", "prime", "requestedAmount", 450_000.0), new TestSuite.FixtureBundleRef(
                "suite-fixture", 1, fixtureFingerprint), List.of("integration"), Map.of())),
                TestSuite.CoveragePolicy.defaults(), TestSuite.PromotionPolicy.defaults(), Map.of());
        var registered = restTemplate.exchange("/api/testing/suites/suite-integration", HttpMethod.PUT,
                new HttpEntity<>(new TestSuiteRegistrationRequest("", suite), suiteWriteHeaders),
                StoredTestSuite.class);
        assertThat(registered.getStatusCode())
                .withFailMessage("suite registration failed: %s", registered.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(registered.getBody()).isNotNull();
        assertThat(registered.getBody().fingerprint()).startsWith("sha256:");

        var found = restTemplate.exchange("/api/testing/suites/suite-integration?revision=1",
                HttpMethod.GET, new HttpEntity<>(suiteReadHeaders), StoredTestSuite.class);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).isEqualTo(registered.getBody());

        TestSuiteExecutionRequest suiteExecution = new TestSuiteExecutionRequest("",
                new TestSuiteExecutionRequest.SuiteRef("suite-integration", 1,
                        registered.getBody().fingerprint()), "integration-suite-run-1",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL, Map.of("source", "spring-http-test"));
        var suiteRun = restTemplate.exchange("/api/testing/suites/suite-integration/executions",
                HttpMethod.POST, new HttpEntity<>(suiteExecution, headers),
                TestSuiteExecutionResponse.class);
        assertThat(suiteRun.getStatusCode())
                .withFailMessage("suite execution failed: %s", suiteRun.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(suiteRun.getBody()).isNotNull();
        String childRunId = suiteRun.getBody().evidence().caseResults().getFirst().runId();
        var childRun = restTemplate.exchange("/api/testing/executions/" + childRunId
                        + "?verbosity=FULL", HttpMethod.GET, new HttpEntity<>(headers),
                TestExecutionApiResponse.class);
        assertThat(childRun.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(childRun.getBody()).isNotNull();
        assertThat(childRun.getBody().schemaVersion()).isEqualTo(TestExecutionApiResponse.SCHEMA_VERSION);
        assertThat(childRun.getBody().integrity().signatureStatus())
                .isEqualTo(TestEvidenceIntegrity.SignatureStatus.VERIFIED);
        assertThat(childRun.getBody().integrity().projection())
                .isEqualTo(TestEvidenceIntegrity.Projection.FULL);
        assertThat(childRun.getBody().integrity().independentlyVerifiable()).isTrue();
        assertThat(suiteRun.getBody().evidence().status())
                .withFailMessage("suite evidence was not passing: %s; child evidence: %s",
                        suiteRun.getBody().evidence(), childRun.getBody().evidence())
                .isEqualTo(com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence.Status.PASSED);
        assertThat(suiteRun.getBody().evidence().promotion().status())
                .isEqualTo(com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence.PromotionStatus.ELIGIBLE);
        assertThat(suiteRun.getBody().evidence().caseResults().getFirst().runId()).isNotBlank();
        assertThat(suiteRun.getBody().schemaVersion())
                .isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION);
        assertThat(suiteRun.getBody().attestation().terminallyVerifiable()).isTrue();
        assertThat(suiteRun.getBody().attestation().childEvidenceRefs()).singleElement()
                .satisfies(child -> {
                    assertThat(child.runId()).isEqualTo(childRunId);
                    assertThat(child.evidenceFingerprint())
                            .isEqualTo(ProtocolFingerprint.of(objectMapper, childRun.getBody().evidence()));
                });

        var portable = restTemplate.exchange("/api/testing/suite-executions/"
                        + suiteRun.getBody().suiteRunId() + "/evidence-bundle",
                HttpMethod.GET, new HttpEntity<>(headers), TestSuiteEvidenceBundle.class);
        assertThat(portable.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(portable.getBody()).isNotNull();
        assertThat(portable.getBody().bundleFingerprint()).startsWith("sha256:");
        assertThat(portable.getBody().attestation()).isEqualTo(suiteRun.getBody().attestation());
        assertThat(objectMapper.valueToTree(portable.getBody()).toString())
                .doesNotContain("spring-http-test", "applicantId", "requestedAmount");

        var suiteRunRetry = restTemplate.exchange("/api/testing/suites/suite-integration/executions",
                HttpMethod.POST, new HttpEntity<>(suiteExecution, headers),
                TestSuiteExecutionResponse.class);
        assertThat(suiteRunRetry.getBody()).isEqualTo(suiteRun.getBody());
        var suiteRunRead = restTemplate.exchange("/api/testing/suite-executions/"
                        + suiteRun.getBody().suiteRunId(), HttpMethod.GET, new HttpEntity<>(headers),
                TestSuiteExecutionResponse.class);
        assertThat(suiteRunRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suiteRunRead.getBody()).isEqualTo(suiteRun.getBody());
    }
}
