package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.integration.IntegrationCapabilities;
import com.leanowtech.bloge.gateway.integration.IntegrationEnvelope;
import com.leanowtech.bloge.gateway.integration.MirrorIntegrationController;
import com.leanowtech.bloge.gateway.integration.MirrorRunIntegrationController;
import com.leanowtech.bloge.gateway.integration.MirrorRuntimeAvailability;
import com.leanowtech.bloge.gateway.integration.MirrorDeploymentIsolationAuthorityPublicationController;
import com.leanowtech.bloge.gateway.integration.CapabilityObservationController;
import com.leanowtech.bloge.gateway.integration.CapabilityCorpusGovernanceController;
import com.leanowtech.bloge.gateway.integration.CapabilityCorpusClusterController;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationAdmissionService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusGovernanceService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusClusterGovernanceService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationReviewRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityPublicationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunCommitService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunRequestRepository;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV4;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;
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
                "gateway.testing.mirror.enabled=true",
                "gateway.seed-descriptors=true",
                "gateway.base-url=http://127.0.0.1:1",
                "gateway.integration.identity.environment-id=test",
                "gateway.integration.identity.region=region-a",
                "gateway.integration.identity.groups=resource-gateway-test-runtime-operators",
                "gateway.integration.identity.clearance=RESTRICTED",
                "gateway.integration.identity.allowed-purposes=TEST_EXECUTION,TEST_FIXTURE_READ,TEST_FIXTURE_WRITE,TEST_REPLAY,TEST_SUITE_READ,TEST_SUITE_WRITE,TEST_RUNTIME_MAINTENANCE,MIRROR_REHEARSAL",
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
        assertThat(context.getBeansOfType(MirrorIntegrationController.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorRunIntegrationController.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityPublicationController.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityObservationController.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityCorpusGovernanceController.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityCorpusClusterController.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorPlanIntegrationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorRunIntegrationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityPublicationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityObservationAdmissionService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityCorpusClusterGovernanceService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityObservationRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityCorpusGovernanceService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityObservationReviewRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityCorpusRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorRunCommitService.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorRunRequestRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorRuntimeAvailability.class)).hasSize(1);
        assertThat(context.getBean(MirrorRuntimeAvailability.class)).satisfies(availability -> {
            assertThat(availability.planCompilationApi()).isTrue();
            assertThat(availability.executionApi()).isTrue();
            assertThat(availability.authorityDistributionApi()).isTrue();
            assertThat(availability.authorityDistributionReady()).isFalse();
            assertThat(availability.observationAdmissionApi()).isTrue();
            assertThat(availability.observationAdmissionReady()).isFalse();
            assertThat(availability.corpusGovernanceApi()).isTrue();
            assertThat(availability.corpusGovernanceReady()).isFalse();
            assertThat(availability.corpusClusterApi()).isTrue();
            assertThat(availability.corpusClusterReady()).isFalse();
        });
        assertThat(context.getBeansOfType(TestRunRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestSuiteRunRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestSuiteRunLeaseCoordinator.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestSuiteRunReconciliationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestSuiteRunReconciliationScheduler.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestBoundarySuiteController.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestBoundarySuiteMaterializationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestPropertySuiteController.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestPropertySuiteMaterializationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestMutationSuiteController.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestMutationSuiteMaterializationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestMutationSuiteExecutionService.class)).hasSize(1);
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
        assertThat(capabilities.getBody().payload().testability().recoveryFleet().status())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status
                        .DISABLED);
        assertThat(capabilities.getBody().payload().features())
                .containsEntry("bootstrapRootRecoveryFleetConfigured", false)
                .containsEntry("bootstrapRootRecoveryFleetReady", false)
                .containsEntry("mirrorPlanCompilation", true)
                .containsEntry("mirrorExternalLeafInterception", true)
                .containsEntry("mirrorServing", true)
                .containsEntry("mirrorIsolationAuthorityPublicationProtocol", true)
                .containsEntry("mirrorIsolationAuthorityDistributionApi", true)
                .containsEntry("mirrorIsolationAuthorityDistributionReady", false)
                .containsEntry("mirrorIsolationAttestationTrustProtocol", true)
                .containsEntry("mirrorIsolationAttestationDistributionApi", true)
                .containsEntry("mirrorIsolationAttestationDistributionReady", false)
                .containsEntry("mirrorCorpusGovernanceProtocol", true)
                .containsEntry("mirrorCorpusGovernanceApi", true)
                .containsEntry("mirrorCorpusGovernanceReady", false)
                .containsEntry(
                        "mirrorCorpusClusterPublicationProtocol", true)
                .containsEntry(
                        "mirrorCorpusClusterPublicationApi", true)
                .containsEntry(
                        "mirrorCorpusClusterPublicationReady", false)
                .containsEntry("mirrorCorpusResolverReady", false)
                .containsEntry(
                        "mirrorCorpusTrajectoryResolverReady", false);
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("POST")
                        && endpoint.path().equals("/api/mirror/plans"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("POST")
                        && endpoint.path().equals("/api/mirror/executions"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("GET")
                        && endpoint.path().equals("/api/mirror/runs/{runId}/evidence"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("POST")
                        && endpoint.path().equals(
                        "/api/mirror/trust/deployment-isolation/authority-key-sets"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("POST")
                        && endpoint.path().equals(
                        "/api/mirror/trust/deployment-isolation/attestations"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("POST")
                        && endpoint.path().equals("/api/mirror/corpus-candidates"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("POST")
                        && endpoint.path().equals("/api/mirror/corpus-publications"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("POST")
                        && endpoint.path().equals("/api/mirror/corpus-clusters"));
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
                endpoint.path().equals(
                        "/api/testing/targets/graphs/{graphName}/property-cases"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.path().equals(
                        "/api/testing/targets/graphs/{graphName}/mutation-cases"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.path().equals(
                        "/api/testing/targets/operators/{operatorRef}/property-cases"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("POST") && endpoint.path().equals(
                        "/api/testing/targets/graphs/{graphName}/boundary-suites"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("POST") && endpoint.path().equals(
                        "/api/testing/targets/graphs/{graphName}/property-suites"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("POST") && endpoint.path().equals(
                        "/api/testing/targets/graphs/{graphName}/mutation-suites"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("POST") && endpoint.path().equals(
                        "/api/testing/suites/{suiteId}/mutation-executions"));
        assertThat(capabilities.getBody().payload().features())
                .containsEntry("immutableTestSuiteRegistry", true)
                .containsEntry("immutableTestSuiteExecution", true)
                .containsEntry("suiteSemanticCoverageVerdict", true)
                .containsEntry("builtInGraphSuiteCatalogMaterialization", true)
                .containsEntry("schemaBoundaryCasePlanning", true)
                .containsEntry("schemaBoundarySuiteMaterialization", true)
                .containsEntry("schemaAdmissionSuiteExecution", true)
                .containsEntry("seededPropertyCasePlanning", true)
                .containsEntry("propertySuiteMaterialization", true)
                .containsEntry("propertySuiteExecution", true)
                .containsEntry("pureDslMutationPlanning", true)
                .containsEntry("mutationSuiteMaterialization", true)
                .containsEntry("pureDslMutationExecution", true)
                .containsEntry("mutationScoreEvidence", true);
        assertThat(capabilities.getBody().payload().supportedObjects())
                .containsEntry("testBoundaryCasePlan",
                        List.of(TestBoundaryCasePlan.SCHEMA_VERSION))
                .containsEntry("testPropertyCasePlan",
                        List.of(TestPropertyCasePlan.SCHEMA_VERSION))
                .containsEntry("testMutationCasePlan",
                        List.of(TestMutationCasePlan.SCHEMA_VERSION))
                .containsEntry("testBoundarySuiteMaterializationRequest",
                        List.of(TestBoundarySuiteMaterializationRequest.SCHEMA_VERSION))
                .containsEntry("testBoundarySuiteMaterialization",
                        List.of(TestBoundarySuiteMaterializationResponse.SCHEMA_VERSION))
                .containsEntry("testPropertySuiteMaterializationRequest",
                        List.of(TestPropertySuiteMaterializationRequest.SCHEMA_VERSION))
                .containsEntry("testPropertySuiteMaterialization",
                        List.of(TestPropertySuiteMaterializationResponse.SCHEMA_VERSION))
                .containsEntry("testMutationSuiteMaterializationRequest",
                        List.of(TestMutationSuiteMaterializationRequest.SCHEMA_VERSION))
                .containsEntry("testMutationSuiteMaterialization",
                        List.of(TestMutationSuiteMaterializationResponse.SCHEMA_VERSION))
                .containsEntry("testMutationSuiteExecutionRequest",
                        List.of(TestMutationSuiteExecutionRequest.SCHEMA_VERSION))
                .containsEntry("testSuiteExecutionResponse", List.of(
                        TestSuiteExecutionResponse.SCHEMA_VERSION_V1,
                        TestSuiteExecutionResponse.SCHEMA_VERSION,
                        TestSuiteExecutionResponse.SCHEMA_VERSION_V3,
                        TestSuiteExecutionResponse.SCHEMA_VERSION_V4,
                        TestSuiteExecutionResponse.SCHEMA_VERSION_V5,
                        TestSuiteExecutionResponse.SCHEMA_VERSION_V6))
                .containsEntry("testSuiteRunEvidence", List.of(
                        TestSuiteRunEvidence.SCHEMA_VERSION,
                        com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2.SCHEMA_VERSION,
                        TestSuiteRunEvidenceV3.SCHEMA_VERSION,
                        com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV4.SCHEMA_VERSION,
                        TestSuiteRunEvidenceV5.SCHEMA_VERSION))
                .containsEntry("testSuiteRunAttestation", List.of(
                        TestSuiteRunAttestation.SCHEMA_VERSION,
                        TestSuiteRunAttestation.SCHEMA_VERSION_V2,
                        TestSuiteRunAttestation.SCHEMA_VERSION_V3,
                        TestSuiteRunAttestation.SCHEMA_VERSION_V4,
                        TestSuiteRunAttestation.SCHEMA_VERSION_V5))
                .containsEntry("testSuiteEvidenceBundle", List.of(
                        TestSuiteEvidenceBundle.SCHEMA_VERSION,
                        TestSuiteEvidenceBundle.SCHEMA_VERSION_V2,
                        TestSuiteEvidenceBundle.SCHEMA_VERSION_V3,
                        TestSuiteEvidenceBundle.SCHEMA_VERSION_V4,
                        TestSuiteEvidenceBundle.SCHEMA_VERSION_V5));
        assertThat(capabilities.getBody().payload().features())
                .containsEntry("suiteRunOwnerLease", true)
                .containsEntry("crossReplicaSuiteStabilityExecutionLease", true)
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

        String propertyPlanPath = "/api/testing/targets/graphs/loanDecisionPolicy/property-cases"
                + "?seed=918273645&trials=3&maxShrinkSteps=2";
        var propertyCases = restTemplate.exchange(propertyPlanPath, HttpMethod.GET,
                new HttpEntity<>(headers), TestPropertyCasePlan.class);
        var propertyReplay = restTemplate.exchange(propertyPlanPath, HttpMethod.GET,
                new HttpEntity<>(headers), TestPropertyCasePlan.class);
        assertThat(propertyCases.getStatusCode())
                .withFailMessage("property planning failed: %s", propertyCases.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(propertyCases.getBody()).isNotNull();
        assertThat(propertyCases.getBody()).isEqualTo(propertyReplay.getBody());
        assertThat(propertyCases.getBody().target()).isEqualTo(descriptor.target());
        assertThat(propertyCases.getBody().policy().seed()).isEqualTo(918273645L);
        assertThat(propertyCases.getBody().policy().requestedTrials()).isEqualTo(3);
        assertThat(propertyCases.getBody().quantification())
                .isEqualTo(TestPropertyCasePlan.Quantification.BOUNDED_SAMPLED);
        assertThat(propertyCases.getBody().exhaustive()).isFalse();
        assertThat(propertyCases.getBody().trials()).isNotEmpty();
        assertThat(propertyCases.getBody().planFingerprint()).matches("sha256:[a-f0-9]{64}");

        String mutationPlanPath = "/api/testing/targets/graphs/loanDecisionPolicy/mutation-cases"
                + "?maxMutants=8";
        var mutationCases = restTemplate.exchange(mutationPlanPath, HttpMethod.GET,
                new HttpEntity<>(headers), TestMutationCasePlan.class);
        var mutationReplay = restTemplate.exchange(mutationPlanPath, HttpMethod.GET,
                new HttpEntity<>(headers), TestMutationCasePlan.class);
        assertThat(mutationCases.getStatusCode())
                .withFailMessage("mutation planning failed: %s", mutationCases.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(mutationCases.getBody()).isNotNull();
        assertThat(mutationCases.getBody()).isEqualTo(mutationReplay.getBody());
        assertThat(mutationCases.getBody().target()).isEqualTo(descriptor.target());
        assertThat(mutationCases.getBody().status())
                .isNotEqualTo(TestMutationCasePlan.Status.UNAVAILABLE);
        assertThat(mutationCases.getBody().mutants()).isNotEmpty();
        assertThat(mutationCases.getBody().policy().externalOperatorMutation()).isFalse();
        assertThat(mutationCases.getBody().policy().equivalentMutantDetection()).isFalse();
        assertThat(mutationCases.getBody().planFingerprint()).matches("sha256:[a-f0-9]{64}");

        FixtureBundle propertyFixture = new FixtureBundle("", "loan-property-fixture", 1,
                descriptor.target().fingerprint(), "INTERNAL", null, 918273645L,
                List.of(), List.of(new FixtureBundle.Assertion(
                "OUTPUT_PATH", "decision", "/decision", "EQUALS", "APPROVE", null)),
                Map.of("source", "property-http-integration"));
        String propertyFixtureFingerprint = ProtocolFingerprint.of(objectMapper, propertyFixture);
        fixtureRepository.create(new StoredFixtureBundle("", "tenant-a", "test",
                propertyFixture.fixtureBundleId(), propertyFixture.revision(),
                propertyFixtureFingerprint, propertyFixture, Instant.now(), "integration-test"));

        HttpHeaders suiteWriteHeaders = new HttpHeaders();
        suiteWriteHeaders.setBearerAuth("bloge-aneke-demo-token");
        suiteWriteHeaders.set("X-Purpose", "TEST_SUITE_WRITE");
        TestPropertySuiteMaterializationRequest propertyMaterializationRequest =
                new TestPropertySuiteMaterializationRequest("", "loan-decision-properties",
                        "INTERNAL", propertyCases.getBody().target().fingerprint(),
                        propertyCases.getBody().inputSchemaFingerprint(),
                        propertyCases.getBody().planFingerprint(), 918273645L, 3, 2,
                        new TestSuite.FixtureBundleRef("loan-property-fixture", 1,
                                propertyFixtureFingerprint), true);
        var propertyMaterialized = restTemplate.exchange(
                "/api/testing/targets/graphs/loanDecisionPolicy/property-suites",
                HttpMethod.POST,
                new HttpEntity<>(propertyMaterializationRequest, suiteWriteHeaders),
                TestPropertySuiteMaterializationResponse.class);
        assertThat(propertyMaterialized.getStatusCode())
                .withFailMessage("property suite materialization failed: %s",
                        propertyMaterialized.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(propertyMaterialized.getBody()).isNotNull();
        assertThat(propertyMaterialized.getBody().caseIds())
                .containsExactlyElementsOf(propertyCases.getBody().allCases().stream()
                        .map(TestPropertyCasePlan.PlannedCase::caseId).toList());
        assertThat(propertyMaterialized.getBody().fixtureRef().fingerprint())
                .isEqualTo(propertyFixtureFingerprint);
        assertThat(propertyMaterialized.getBody().generationGapsAccepted()).isEqualTo(
                propertyCases.getBody().status() == TestPropertyCasePlan.Status.PARTIAL);

        HttpHeaders suiteReadHeaders = new HttpHeaders();
        suiteReadHeaders.setBearerAuth("bloge-aneke-demo-token");
        suiteReadHeaders.set("X-Purpose", "TEST_SUITE_READ");
        var propertyStored = restTemplate.exchange(
                "/api/testing/suites/loan-decision-properties?revision="
                        + propertyMaterialized.getBody().suiteRef().revision(),
                HttpMethod.GET, new HttpEntity<>(suiteReadHeaders), StoredTestSuite.class);
        assertThat(propertyStored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(propertyStored.getBody()).isNotNull();
        assertThat(propertyStored.getBody().suite()).isInstanceOf(TestSuiteV4.class);
        TestSuiteV4 storedPropertySuite = (TestSuiteV4) propertyStored.getBody().suite();
        assertThat(storedPropertySuite.propertyPlanFingerprint())
                .isEqualTo(propertyCases.getBody().planFingerprint());
        assertThat(storedPropertySuite.cases()).extracting(TestSuite.TestCase::caseType)
                .containsOnly(TestSuite.CaseType.PROPERTY);

        var catalogMaterialized = restTemplate.exchange(
                "/api/testing/catalogs/gateway-graph-contract-v1",
                HttpMethod.PUT, new HttpEntity<>(suiteWriteHeaders),
                TestSuiteCatalogMaterializationResponse.class);
        assertThat(catalogMaterialized.getStatusCode())
                .withFailMessage("built-in suite catalog materialization failed: %s",
                        catalogMaterialized.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(catalogMaterialized.getBody()).isNotNull();
        TestSuiteCatalogMaterializationResponse.SuiteAsset mutationOracleAsset =
                catalogMaterialized.getBody().suites().stream()
                        .filter(asset -> "loanDecisionPolicy".equals(asset.graphName()))
                        .findFirst()
                        .orElseThrow();
        var mutationOracleStored = restTemplate.exchange(
                "/api/testing/suites/" + mutationOracleAsset.suiteRef().suiteId()
                        + "?revision=" + mutationOracleAsset.suiteRef().revision(),
                HttpMethod.GET, new HttpEntity<>(suiteReadHeaders), StoredTestSuite.class);
        assertThat(mutationOracleStored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mutationOracleStored.getBody()).isNotNull();
        var mutationOracleSuite = mutationOracleStored.getBody().suite();

        TestMutationSuiteMaterializationRequest mutationMaterializationRequest =
                new TestMutationSuiteMaterializationRequest("", "loan-decision-mutations",
                        "INTERNAL", mutationCases.getBody().target().fingerprint(),
                        mutationCases.getBody().sourceFingerprint(),
                        mutationCases.getBody().graphArtifactFingerprint(),
                        mutationCases.getBody().planFingerprint(), 8,
                        mutationOracleAsset.suiteRef(),
                        mutationCases.getBody().status() == TestMutationCasePlan.Status.PARTIAL,
                        new TestSuiteV5.MutationScorePolicy(8_000, 0, false, false));
        var mutationMaterialized = restTemplate.exchange(
                "/api/testing/targets/graphs/loanDecisionPolicy/mutation-suites",
                HttpMethod.POST,
                new HttpEntity<>(mutationMaterializationRequest, suiteWriteHeaders),
                TestMutationSuiteMaterializationResponse.class);
        assertThat(mutationMaterialized.getStatusCode())
                .withFailMessage("mutation suite materialization failed: %s",
                        mutationMaterialized.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(mutationMaterialized.getBody()).isNotNull();
        assertThat(mutationMaterialized.getBody().mutantIds())
                .containsExactlyElementsOf(mutationCases.getBody().mutants().stream()
                        .map(TestMutationCasePlan.PlannedMutant::mutantId).toList());
        assertThat(mutationMaterialized.getBody().oracleCaseIds())
                .containsExactlyElementsOf(mutationOracleSuite.cases().stream()
                        .map(TestSuite.TestCase::caseId).toList());
        assertThat(mutationMaterialized.getBody().mutantCaseExecutions())
                .isEqualTo(mutationCases.getBody().mutants().size()
                        * mutationOracleSuite.cases().size());

        var mutationStored = restTemplate.exchange(
                "/api/testing/suites/loan-decision-mutations?revision="
                        + mutationMaterialized.getBody().suiteRef().revision(),
                HttpMethod.GET, new HttpEntity<>(suiteReadHeaders), StoredTestSuite.class);
        assertThat(mutationStored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mutationStored.getBody()).isNotNull();
        assertThat(mutationStored.getBody().suite()).isInstanceOf(TestSuiteV5.class);
        TestSuiteV5 storedMutationSuite = (TestSuiteV5) mutationStored.getBody().suite();
        assertThat(storedMutationSuite.mutationPlanFingerprint())
                .isEqualTo(mutationCases.getBody().planFingerprint());
        assertThat(storedMutationSuite.oracleSuiteRef().fingerprint())
                .isEqualTo(mutationOracleAsset.suiteRef().fingerprint());

        TestSuiteExecutionRequest propertyExecution = new TestSuiteExecutionRequest("",
                propertyMaterialized.getBody().suiteRef(), "integration-property-run-v4",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL,
                Map.of("source", "spring-http-property-test"));
        var propertyRunWire = restTemplate.exchange(
                "/api/testing/suites/loan-decision-properties/executions",
                HttpMethod.POST, new HttpEntity<>(propertyExecution, headers), JsonNode.class);
        assertThat(propertyRunWire.getStatusCode())
                .withFailMessage("property suite execution failed: %s", propertyRunWire.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(propertyRunWire.getBody()).isNotNull();
        TestSuiteExecutionResponse propertyRun = objectMapper.treeToValue(
                propertyRunWire.getBody(), TestSuiteExecutionResponse.class);
        assertThat(propertyRun.schemaVersion())
                .isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V5);
        assertThat(propertyRun.evidence()).isInstanceOfSatisfying(
                com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV4.class,
                evidence -> {
                    assertThat(evidence.propertyTrialResults())
                            .hasSize(storedPropertySuite.propertyTrials().size());
                    assertThat(evidence.caseResults())
                            .hasSize(storedPropertySuite.cases().size());
                    assertThat(evidence.propertyCoverage().allCasesCompleted()).isTrue();
                    assertThat(evidence.propertyCoverage().globallyMinimal()).isFalse();
                });
        assertThat(propertyRun.attestation().schemaVersion())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V4);

        TestMutationSuiteExecutionRequest mutationExecution =
                new TestMutationSuiteExecutionRequest("",
                        mutationMaterialized.getBody().suiteRef(),
                        "integration-mutation-run-v5",
                        TestMutationSuiteExecutionRequest.Strategy.COLLECT_ALL,
                        Map.of("source", "spring-http-mutation-test"));
        var mutationRunWire = restTemplate.exchange(
                "/api/testing/suites/loan-decision-mutations/mutation-executions",
                HttpMethod.POST, new HttpEntity<>(mutationExecution, headers), JsonNode.class);
        assertThat(mutationRunWire.getStatusCode())
                .withFailMessage("mutation suite execution failed: %s", mutationRunWire.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(mutationRunWire.getBody()).isNotNull();
        TestSuiteExecutionResponse mutationRun = objectMapper.treeToValue(
                mutationRunWire.getBody(), TestSuiteExecutionResponse.class);
        assertThat(mutationRun.schemaVersion())
                .isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V6);
        assertThat(mutationRun.evidence()).isInstanceOfSatisfying(
                TestSuiteRunEvidenceV5.class, evidence -> {
                    assertThat(evidence.status()).isNotEqualTo(TestSuiteRunEvidence.Status.RUNNING);
                    assertThat(evidence.baselineStatus())
                            .isEqualTo(TestSuiteRunEvidenceV5.BaselineStatus.PASSED);
                    assertThat(evidence.mutantResults())
                            .hasSize(storedMutationSuite.mutants().size())
                            .allSatisfy(mutant -> assertThat(mutant.caseResults())
                                    .hasSize(storedMutationSuite.cases().size())
                                    .noneMatch(result -> result.status()
                                            == TestSuiteRunEvidenceV5.MutantCaseStatus.PENDING));
                    assertThat(evidence.mutationScore().plannedMutants())
                            .isEqualTo(storedMutationSuite.mutants().size());
                    assertThat(evidence.mutationScore().status())
                            .isNotIn(TestSuiteRunEvidenceV5.MutationScoreStatus.NOT_EVALUATED,
                                    TestSuiteRunEvidenceV5.MutationScoreStatus.INCOMPLETE);
                });
        assertThat(mutationRun.attestation().schemaVersion())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V5);
        assertThat(mutationRun.attestation().terminallyVerifiable()).isTrue();
        assertThat(mutationRun.attestation().childEvidenceRefs()).hasSize(
                storedMutationSuite.cases().size()
                        * (storedMutationSuite.mutants().size() + 1));

        var mutationRunRead = restTemplate.exchange("/api/testing/suite-executions/"
                        + mutationRun.suiteRunId(), HttpMethod.GET,
                new HttpEntity<>(headers), TestSuiteExecutionResponse.class);
        assertThat(mutationRunRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mutationRunRead.getBody()).isEqualTo(mutationRun);
        var mutationPortable = restTemplate.exchange("/api/testing/suite-executions/"
                        + mutationRun.suiteRunId() + "/evidence-bundle",
                HttpMethod.GET, new HttpEntity<>(headers), TestSuiteEvidenceBundle.class);
        assertThat(mutationPortable.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mutationPortable.getBody()).isNotNull();
        assertThat(mutationPortable.getBody().schemaVersion())
                .isEqualTo(TestSuiteEvidenceBundle.SCHEMA_VERSION_V5);
        assertThat(mutationPortable.getBody().attestation())
                .isEqualTo(mutationRun.attestation());
        assertThat(mutationPortable.getBody().evidence())
                .isInstanceOf(TestSuiteRunEvidenceV5.class);

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
