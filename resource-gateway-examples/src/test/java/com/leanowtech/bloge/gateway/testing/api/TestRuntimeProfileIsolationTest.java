package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpointStore;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStore;
import com.leanowtech.bloge.core.runtime.wait.WaitStore;
import com.leanowtech.bloge.core.runtime.work.WorkItemStore;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.TestabilityAvailability;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionCoordinator;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionPolicy;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionRetentionScheduler;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionTelemetry;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeSloControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl;
import com.leanowtech.bloge.gateway.testing.persistence.RecoverySequenceRequestKeyProtector;
import com.leanowtech.bloge.gateway.testing.persistence.StagedBlogeDurableStateStore;
import com.leanowtech.bloge.gateway.testing.persistence.TestSuiteStabilityJobRequestKeyProtector;
import com.leanowtech.bloge.gateway.testing.persistence.WorkerQuarantineClaimTokenProtector;
import com.leanowtech.bloge.gateway.testing.persistence.WorkerQuarantineRequestKeyProtector;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestRuntimeResources;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestTerminalRecoveryRuntime;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestRuntimeProfileIsolationTest {

    @Test
    void productionProfileHasNoTestingControllerStoreOrCapabilityMarker() {
        try (AnnotationConfigApplicationContext context = context("production")) {
            assertThat(context.getBeansOfType(TestExecutionController.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityCrossRetentionTrendController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecycleController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecycleArchiveController.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveAuthority.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveHealth.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationFloorRetirementService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                            .class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobController.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestExecutionQueryController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestExecutionQueryService.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableTestOwnerClaimController.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableTestWorkerAcquisitionController.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableTestWorkerAcquisitionService.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableTestOwnerClaimService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryHeartbeatController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryHeartbeatService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryStepController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestRecoverySequenceController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestRecoverySequenceService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableRecoverySequenceRetentionScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableRecoverySequenceRetentionTelemetry.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableRecoverySequenceRetentionSloMonitor.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    RecoverySequenceRequestKeyProtector.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryRuntime.class)).isEmpty();
            assertThat(context.getBeansOfType(TestExecutionApiService.class)).isEmpty();
            assertThat(context.getBeansOfType(TestRunRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(FixtureBundleRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestExecutionCheckpointRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableTestRuntimeResources.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableStateProjectionReconciliationScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableStateProjectionFindingRetentionScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableStateProjectionSloMonitor.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableStateProjectionTelemetry.class)).isEmpty();
            assertThat(context.getBeansOfType(TestRuntimeSloMonitor.class)).isEmpty();
            assertThat(context.getBeansOfType(TestRuntimeSloTelemetry.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DatabaseTestRuntimeSloControlPlane.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DatabaseTestRuntimeAdmissionControl.class)).isEmpty();
            assertThat(context.getBeansOfType(TestRuntimeAdmissionCoordinator.class)).isEmpty();
            assertThat(context.getBeansOfType(TestRuntimeAdmissionPolicy.class)).isEmpty();
            assertThat(context.getBeansOfType(TestRuntimeAdmissionTelemetry.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestRuntimeAdmissionRetentionScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DatabaseDurableStateProjectionControlPlane.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableStateProjectionFindingController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableStateProjectionFindingService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DatabaseDurableWorkerQuarantineControlPlane.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    WorkerQuarantineClaimTokenProtector.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    WorkerQuarantineRequestKeyProtector.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableWorkerQuarantineRetentionScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableWorkerQuarantineRetentionTelemetry.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableWorkerQuarantineController.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableWorkerQuarantineService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    WorkerQuarantineRequestIndexRolloutService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    WorkerQuarantineRequestIndexRolloutController.class)).isEmpty();
            assertThat(context.getBeansOfType(StagedBlogeDurableStateStore.class)).isEmpty();
            assertThat(context.getBeansOfType(ExecutionStore.class)).isEmpty();
            assertThat(context.getBeansOfType(ExecutionCheckpointStore.class)).isEmpty();
            assertThat(context.getBeansOfType(WaitStore.class)).isEmpty();
            assertThat(context.getBeansOfType(WorkItemStore.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobParentAuthority.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRequestKeyProtector.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRetentionScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRetentionTelemetry.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRetentionSloMonitor.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityQueuePolicy.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobTelemetry.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobSloMonitor.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobExecutionCoordinator.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityAuthorityTrustStore.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityAuthorityTrustHealth.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DynamicTestSuiteStabilityServingInventoryTrustRootAuthority.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityServingInventoryTrustRootFloor.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityServingInventoryTrustRootHealth.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityExternalSequenceAnchor.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityExternalSequenceAnchorHealth.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    HttpTestSuiteStabilityJobAuthorizer.class)).isEmpty();
            assertThat(context.getBeansOfType(TestabilityAvailability.class)).isEmpty();
        }
    }

    @Test
    void testProfileAssemblesIndependentStoreControllerAndCapabilityMarker() {
        try (AnnotationConfigApplicationContext context = context("test")) {
            assertThat(context.getBeansOfType(TestExecutionController.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestSuiteStabilityController.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityCrossRetentionTrendController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityCrossRetentionTrendAnalysisService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecycleController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecyclePageService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecycleArchiveController.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecycleArchivePageService.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobController.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestSuiteStabilityJobService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestExecutionQueryController.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestExecutionQueryService.class)).hasSize(1);
            assertThat(context.getBeansOfType(DurableTestOwnerClaimController.class)).hasSize(1);
            assertThat(context.getBeansOfType(DurableTestWorkerAcquisitionController.class)).hasSize(1);
            assertThat(context.getBeansOfType(DurableTestWorkerAcquisitionService.class)).hasSize(1);
            assertThat(context.getBeansOfType(DurableTestOwnerClaimService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryHeartbeatController.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryHeartbeatService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryController.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryStepController.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestRecoverySequenceController.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestRecoverySequenceService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableRecoverySequenceRetentionScheduler.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableRecoverySequenceRetentionTelemetry.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableRecoverySequenceRetentionSloMonitor.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    RecoverySequenceRequestKeyProtector.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryRuntime.class)).hasSize(1);
            assertThat(context.getBeansOfType(DurableTestRecoveryAuthorizer.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestExecutionApiService.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestRunRepository.class)).hasSize(1);
            assertThat(context.getBeansOfType(FixtureBundleRepository.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestExecutionCheckpointRepository.class)).hasSize(1);
            assertThat(context.getBeansOfType(ReplayPayloadRepository.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestReplayPayloadService.class)).hasSize(1);
            assertThat(context.getBeansOfType(DurableTestRuntimeResources.class)).hasSize(1);
            assertThat(context.getBeansOfType(StagedBlogeDurableStateStore.class)).isEmpty();
            assertThat(context.getBeansOfType(ExecutionStore.class)).isEmpty();
            assertThat(context.getBeansOfType(ExecutionCheckpointStore.class)).isEmpty();
            assertThat(context.getBeansOfType(WaitStore.class)).isEmpty();
            assertThat(context.getBeansOfType(WorkItemStore.class)).isEmpty();
            assertThat(context.getBean(DurableTestRuntimeResources.class)
                    .engineFactory().configuration().durableStores()).isTrue();
            assertThat(context.getBeansOfType(
                    DatabaseDurableStateProjectionControlPlane.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableStateProjectionFindingController.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableStateProjectionFindingService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DatabaseDurableWorkerQuarantineControlPlane.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    WorkerQuarantineClaimTokenProtector.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    WorkerQuarantineRequestKeyProtector.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableWorkerQuarantineRetentionScheduler.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableWorkerQuarantineRetentionTelemetry.class)).hasSize(1);
            assertThat(context.getBeansOfType(DurableWorkerQuarantineController.class)).hasSize(1);
            assertThat(context.getBeansOfType(DurableWorkerQuarantineService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    WorkerQuarantineRequestIndexRolloutService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    WorkerQuarantineRequestIndexRolloutController.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableStateProjectionReconciliationScheduler.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableStateProjectionFindingRetentionScheduler.class)).hasSize(1);
            assertThat(context.getBeansOfType(DurableStateProjectionSloMonitor.class)).hasSize(1);
            assertThat(context.getBeansOfType(DurableStateProjectionTelemetry.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestRuntimeSloMonitor.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestRuntimeSloTelemetry.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DatabaseTestRuntimeSloControlPlane.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DatabaseTestRuntimeAdmissionControl.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestRuntimeAdmissionCoordinator.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestRuntimeAdmissionPolicy.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestRuntimeAdmissionTelemetry.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestRuntimeAdmissionRetentionScheduler.class)).hasSize(1);
            TestRuntimeSloMonitor runtimeSlo = context.getBean(TestRuntimeSloMonitor.class);
            runtimeSlo.refresh();
            assertThat(runtimeSlo.health().getStatus()).isEqualTo(Status.UP);
            assertThat(context.getBean(TestabilityAvailability.class).executionEndpointEnabled())
                    .isTrue();
            assertThat(context.getBean(TestabilityAvailability.class)
                    .suiteStabilityJobSubmissionEnabled()).isFalse();
            assertThat(context.getBean(TestabilityAvailability.class)
                    .workerQuarantineRequestIndexMode())
                    .isEqualTo(WorkerQuarantineRequestIndexMode.KEYED_ONLY);
            assertThat(context.getBeansOfType(
                    DynamicTestSuiteStabilityServingInventoryTrustRootAuthority.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityServingInventoryTrustRootFloor.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityServingInventoryTrustRootHealth.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityExternalSequenceAnchor.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityExternalSequenceAnchorHealth.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobParentAuthority.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRepository.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRequestKeyProtector.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRetentionScheduler.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRetentionTelemetry.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRetentionSloMonitor.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestSuiteStabilityQueuePolicy.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestSuiteStabilityJobTelemetry.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestSuiteStabilityJobSloMonitor.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobExecutionCoordinator.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityAuthorityTrustStore.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityAuthorityTrustHealth.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    HttpTestSuiteStabilityJobAuthorizer.class)).isEmpty();
            assertThat(context.getBean(TestabilityAvailability.class)
                    .suiteStabilityCurrentAuthority().available()).isFalse();
            TestSuiteStabilityJobSloMonitor stabilityQueueSlo =
                    context.getBean(TestSuiteStabilityJobSloMonitor.class);
            stabilityQueueSlo.refresh();
            assertThat(stabilityQueueSlo.health().getStatus()).isEqualTo(Status.UP);
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            TestRunEvidence evidence = TestSemanticResultFingerprint.attach(mapper,
                    new TestRunEvidence("", "profile-run",
                            TestRunEvidence.Status.EVIDENCE_INCOMPLETE,
                            TestRunEvidence.EvidenceClass.EXPLORATORY,
                            "TEST", "", "", "", Instant.EPOCH, Instant.EPOCH,
                            List.of(), List.of(), List.of(), List.of(), List.of(), Map.of()));
            TestEvidenceIntegrityService.SealResult seal = context
                    .getBean(TestEvidenceIntegrityService.class).seal(evidence);
            assertThat(seal.verified()).isTrue();
            assertThat(seal.failureCode()).isEmpty();
        }
    }

    @Test
    void crossRetentionPreviewRequiresExplicitNonProductionOptIn() {
        Map<String, Object> enabled = Map.of(
                "gateway.testing.stability-cross-retention-preview-enabled", "true");
        try (AnnotationConfigApplicationContext context = context(enabled, 0, "test")) {
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityCrossRetentionTrendAnalysisService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityCrossRetentionTrendController.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecyclePageService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecycleController.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecycleArchivePageService.class))
                    .hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecycleArchiveController.class))
                    .hasSize(1);
        }
        try (AnnotationConfigApplicationContext context = context(
                enabled, 0, "production", "test")) {
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityCrossRetentionTrendAnalysisService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityCrossRetentionTrendController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecyclePageService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecycleController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecycleArchivePageService.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationLedgerLifecycleArchiveController.class))
                    .isEmpty();
        }
    }

    @Test
    void externalObservationArchiveRequiresExplicitNonProductionHttpsConfiguration()
            throws Exception {
        Map<String, Object> enabled = externalObservationArchiveProperties();
        try (AnnotationConfigApplicationContext context = context(enabled, 0, "test")) {
            assertThat(context.getBeansOfType(
                    HttpTestSuiteStabilityObservationExternalArchiveAuthority.class))
                    .hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveHealth.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationFloorRetirementService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveReconciliationService.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveFindingRetentionScheduler.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveReconciliationHealth.class))
                    .isEmpty();
        }
        try (AnnotationConfigApplicationContext context = context(
                enabled, 0, "production", "test")) {
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveAuthority.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveHealth.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationFloorRetirementService.class)).isEmpty();
        }
        try (AnnotationConfigApplicationContext context = context(
                externalObservationArchiveStagingProperties(), 0, "staging")) {
            var descriptor = context.getBean(
                    TestSuiteStabilityObservationExternalArchiveAuthority.class).descriptor();
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.requiredCopies()).isEqualTo(2);
            assertThat(descriptor.independentFailureDomainCount()).isEqualTo(2);
        }

        AnnotationConfigApplicationContext staging = unrefreshedContext(
                enabled, 0, "staging");
        try {
            assertThatThrownBy(staging::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires HTTPS and two copies");
        } finally {
            staging.close();
        }
    }

    @Test
    void externalArchiveReconciliationRequiresCompleteExplicitNonProductionConfiguration()
            throws Exception {
        Map<String, Object> enabled = externalObservationArchiveReconciliationProperties();
        try (AnnotationConfigApplicationContext context = context(enabled, 0, "test")) {
            assertThat(context.getBeansOfType(
                    DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                            .class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                            .class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.class))
                    .hasSize(1);
            assertThat(context.getBeansOfType(
                    DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                            .class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                            .class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveReconciliationService.class))
                    .hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.class))
                    .hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveFindingRetentionScheduler.class))
                    .hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.class))
                    .hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveReconciliationHealth.class))
                    .hasSize(1);
        }
        try (AnnotationConfigApplicationContext context = context(
                enabled, 0, "production", "test")) {
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveReconciliationService.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveFindingRetentionScheduler.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.class))
                    .isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityObservationExternalArchiveReconciliationHealth.class))
                    .isEmpty();
        }

        Map<String, Object> noAuthority = new LinkedHashMap<>();
        noAuthority.put(reconciliationPrefix() + "enabled", "true");
        noAuthority.put(reconciliationPrefix() + "instance-id", "profile-replica-a");
        AnnotationConfigApplicationContext missingAuthority = unrefreshedContext(
                noAuthority, 0, "test");
        try {
            assertThatThrownBy(missingAuthority::refresh)
                    .rootCause()
                    .hasMessageContaining(
                            "TestSuiteStabilityObservationExternalArchiveInventoryAuthority");
        } finally {
            missingAuthority.close();
        }

        Map<String, Object> noInstance = new LinkedHashMap<>(
                externalObservationArchiveProperties());
        noInstance.put(reconciliationPrefix() + "enabled", "true");
        AnnotationConfigApplicationContext missingInstance = unrefreshedContext(
                noInstance, 0, "test");
        try {
            assertThatThrownBy(missingInstance::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("stable instance ID");
        } finally {
            missingInstance.close();
        }
    }

    @Test
    void externalArchiveReconciliationRejectsUnsafeScheduleAndPageConfiguration()
            throws Exception {
        Map<String, Object> unsafeSchedule =
                externalObservationArchiveReconciliationProperties();
        unsafeSchedule.put(reconciliationPrefix() + "interval-ms", "999");
        AnnotationConfigApplicationContext scheduleContext = unrefreshedContext(
                unsafeSchedule, 0, "test");
        try {
            assertThatThrownBy(scheduleContext::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("1 second..7 day interval");
        } finally {
            scheduleContext.close();
        }

        Map<String, Object> unboundedPage =
                externalObservationArchiveReconciliationProperties();
        unboundedPage.put(reconciliationPrefix() + "inventory-page-size", "501");
        AnnotationConfigApplicationContext pageContext = unrefreshedContext(
                unboundedPage, 0, "test");
        try {
            assertThatThrownBy(pageContext::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1 through 500");
        } finally {
            pageContext.close();
        }

        Map<String, Object> blindHealth =
                externalObservationArchiveReconciliationProperties();
        blindHealth.put(reconciliationPrefix() + "health-startup-grace-seconds", "60");
        AnnotationConfigApplicationContext healthContext = unrefreshedContext(
                blindHealth, 0, "test");
        try {
            assertThatThrownBy(healthContext::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startupGrace");
        } finally {
            healthContext.close();
        }

        Map<String, Object> unboundedSourcePage =
                externalObservationArchiveReconciliationProperties();
        unboundedSourcePage.put(reconciliationPrefix() + "source-retention-page-size", "501");
        AnnotationConfigApplicationContext sourcePageContext = unrefreshedContext(
                unboundedSourcePage, 0, "test");
        try {
            assertThatThrownBy(sourcePageContext::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("source retention page")
                    .hasMessageContaining("1 through 500");
        } finally {
            sourcePageContext.close();
        }

        Map<String, Object> blindSourceHealth =
                externalObservationArchiveReconciliationProperties();
        blindSourceHealth.put(reconciliationPrefix()
                + "health-maximum-source-retention-staleness-seconds", "3599");
        AnnotationConfigApplicationContext sourceHealthContext = unrefreshedContext(
                blindSourceHealth, 0, "test");
        try {
            assertThatThrownBy(sourceHealthContext::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maximumSourceRetentionStaleness");
        } finally {
            sourceHealthContext.close();
        }
    }

    @Test
    void explicitlyEnabledStabilityWorkerRequiresExactlyOneCurrentAuthority() {
        for (int authorizerCount : List.of(0, 2)) {
            AnnotationConfigApplicationContext context = unrefreshedContext(
                    enabledStabilityWorkerProperties(), authorizerCount, "test");
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("requires exactly one")
                        .hasMessageContaining("TestSuiteStabilityJobAuthorizer");
            } finally {
                context.close();
            }
        }
    }

    @Test
    void explicitlyEnabledStabilityWorkerAssemblesGuardedBoundedLifecycle() {
        try (AnnotationConfigApplicationContext context = context(
                enabledStabilityWorkerProperties(), 1, "test")) {
            assertThat(context.getBeansOfType(TestSuiteStabilityJobAuthorizer.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobExecutionCoordinator.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestSuiteStabilityJobWorker.class)).hasSize(1);
            assertThat(context.getBeansOfType(TestSuiteStabilityJobScheduler.class)).hasSize(1);
            assertThat(context.getBean(TestSuiteStabilityJobScheduler.class).closed()).isFalse();
            assertThat(context.getBean(TestabilityAvailability.class)
                    .suiteStabilityJobSubmissionEnabled()).isTrue();
        }
    }

    @Test
    void testSecretAuthorityDefaultsUnavailableAndBuiltInSignedHttpsIsExplicit() throws Exception {
        try (AnnotationConfigApplicationContext context = context("test")) {
            assertThat(context.getBeansOfType(TestSecretAuthority.class))
                    .hasSize(1).allSatisfy((name, authority) ->
                            assertThat(authority.descriptor().available()).isFalse());
            assertThat(context.getBeansOfType(TestSecretAuthorityTrustStore.class)).isEmpty();
        }

        try (AnnotationConfigApplicationContext context = context(
                builtInTestSecretAuthorityProperties(), 0, "test")) {
            assertThat(context.getBeansOfType(TestSecretAuthority.class))
                    .hasSize(1).allSatisfy((name, authority) -> {
                        assertThat(authority).isInstanceOf(HttpTestSecretAuthority.class);
                        assertThat(authority.descriptor().available()).isTrue();
                        assertThat(authority.descriptor().properties())
                                .containsEntry("signedResponses", true)
                                .containsEntry("challengeBound", true)
                                .containsEntry("credentialFree", true)
                                .doesNotContainKeys("baseUri", "publicKey", "privateKey");
                    });
            assertThat(context.getBeansOfType(TestSecretAuthorityTrustStore.class))
                    .hasSize(1).allSatisfy((name, trust) -> {
                        assertThat(trust).isInstanceOf(
                                ConfiguredTestSecretAuthorityTrustStore.class);
                        assertThat(trust.descriptor().available()).isTrue();
                    });
            assertThat(context.getBean(TestSecretResolutionService.class).descriptor().available())
                    .isTrue();
        }
    }

    @Test
    void incompleteOrAmbiguousBuiltInTestSecretAuthorityFailsStartup() throws Exception {
        Map<String, Object> incomplete = new LinkedHashMap<>();
        incomplete.put("gateway.testing.test-secrets.authority.http.enabled", "true");
        incomplete.put("gateway.testing.test-secrets.authority.http.base-uri",
                "http://127.0.0.1:18082");
        incomplete.put("gateway.testing.test-secrets.authority.http.allow-insecure-loopback",
                "true");
        incomplete.put("gateway.testing.test-secrets.authority.http.expected-authority-id",
                "secret-authority.example");
        AnnotationConfigApplicationContext missingKeys =
                unrefreshedContext(incomplete, 0, "test");
        try {
            assertThatThrownBy(missingKeys::refresh).rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Authority keys");
        } finally {
            missingKeys.close();
        }

        AnnotationConfigApplicationContext ambiguous = unrefreshedContext(
                builtInTestSecretAuthorityProperties(), 0, "test");
        ambiguous.registerBean("deploymentTestSecretAuthority", TestSecretAuthority.class,
                () -> context -> { throw new TestSecretAuthority.ResolutionException(
                        TestSecretAuthority.Reason.UNAVAILABLE); });
        try {
            assertThatThrownBy(ambiguous::refresh).rootCause()
                    .isInstanceOf(org.springframework.beans.factory.NoUniqueBeanDefinitionException.class)
                    .hasMessageContaining("TestSecretAuthority");
        } finally {
            ambiguous.close();
        }

        Map<String, Object> staticWithCohort = new LinkedHashMap<>(
                builtInTestSecretAuthorityProperties());
        staticWithCohort.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.enabled", "true");
        AnnotationConfigApplicationContext incompatible =
                unrefreshedContext(staticWithCohort, 0, "test");
        try {
            assertThatThrownBy(incompatible::refresh).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires exactly one gate");
        } finally {
            incompatible.close();
        }
    }

    @Test
    void dynamicTestSecretJwksBootstrapsAndPublishesRefreshTruth() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] document = authorityJwks(keyPair, "secret-key-dynamic")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/jwk-set+json");
            exchange.getResponseHeaders().add("ETag", "secret-generation-1");
            exchange.sendResponseHeaders(200, document.length);
            try (var body = exchange.getResponseBody()) {
                body.write(document);
            }
        });
        server.start();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("gateway.testing.test-secrets.authority.http.enabled", "true");
        properties.put("gateway.testing.test-secrets.authority.http.base-uri",
                "http://127.0.0.1:18082");
        properties.put("gateway.testing.test-secrets.authority.http.allow-insecure-loopback",
                "true");
        properties.put("gateway.testing.test-secrets.authority.http.expected-authority-id",
                "secret-authority.example");
        properties.put("gateway.testing.test-secrets.authority.http.jwks.enabled", "true");
        properties.put("gateway.testing.test-secrets.authority.http.jwks.uri",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.allow-insecure-loopback",
                "true");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.refresh-interval-seconds",
                "3600");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.maximum-snapshot-age-seconds",
                "3610");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.enabled", "true");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.scope-id",
                "test-secret-scope");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.cohort-id",
                "deployment-a");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.instance-id",
                "replica-a");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.artifact-fingerprint",
                "sha256:" + "a".repeat(64));
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.expected-instance-ids",
                "replica-a");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.heartbeat-interval-seconds",
                "1");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.lease-duration-seconds",
                "3");
        try {
            try (AnnotationConfigApplicationContext context = context(properties, 0, "test")) {
                assertThat(context.getBeansOfType(TestSecretAuthorityTrustStore.class))
                        .hasSize(1).allSatisfy((name, trust) -> {
                            assertThat(trust).isInstanceOf(
                                    DynamicJwksTestSecretAuthorityTrustStore.class);
                            assertThat(trust.descriptor()).satisfies(descriptor -> {
                                assertThat(descriptor.available()).isTrue();
                                assertThat(descriptor.providerType())
                                        .isEqualTo("DYNAMIC_JWKS_ED25519");
                                assertThat(descriptor.properties())
                                        .containsEntry("refreshState", "HEALTHY")
                                        .containsEntry("automaticRefresh", true)
                                        .containsEntry("conditionalRequests", true)
                                        .containsEntry("failClosedOnRefreshFailure", true);
                            });
                        });
                assertThat(context.getBean(TestSecretAuthority.class).descriptor())
                        .satisfies(descriptor -> {
                            assertThat(descriptor.available()).isTrue();
                            assertThat(descriptor.properties())
                                    .containsEntry("trustProviderType",
                                            "DYNAMIC_JWKS_ED25519")
                                    .containsEntry("trustRefreshState", "HEALTHY")
                                    .containsEntry("trustAutomaticRefresh", true)
                                    .containsEntry("trustRefreshIntervalSeconds", 3600L)
                                    .containsEntry("trustMaximumSnapshotAgeSeconds", 3610L)
                                    .containsEntry("trustConditionalRequests", true)
                                    .containsEntry("trustFailClosedOnRefreshFailure", true)
                                    .containsEntry("trustCohortConfigured", true)
                                    .containsEntry("trustCohortAvailable", true)
                                    .containsEntry("trustCohortStatus", "CONVERGED")
                                    .containsEntry("trustCohortExpectedReplicaCount", 1)
                                    .containsEntry("trustCohortLiveReplicaCount", 1)
                                    .containsEntry("trustCohortHealthyReplicaCount", 1)
                                    .containsEntry("trustCohortDistinctGenerationCount", 1)
                                    .containsEntry("trustCohortDatabaseAuthority", true)
                                    .containsEntry("trustCohortExactConfiguredInventory", true)
                                    .doesNotContainKeys("jwksUri", "baseUri", "etag",
                                            "keyId", "publicKey", "privateKey", "instanceId",
                                            "startupId", "snapshotFingerprint");
                        });
                assertThat(context.getBean(TestSecretAuthorityTrustHealth.class)
                        .health().getStatus()).isEqualTo(Status.UP);
                assertThat(context.getBean(TestSecretAuthorityTrustCohortMonitor.class)
                        .descriptor().available()).isTrue();
                assertThat(context.getBean(TestSecretAuthorityTrustCohortHealth.class)
                        .health().getStatus()).isEqualTo(Status.UP);
                assertThat(context.getBeansOfType(
                        ConfiguredTestSecretAuthorityTrustStore.class)).isEmpty();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void signedTestSecretInventoryBootstrapsExternalCohortAndPublishesReadiness()
            throws Exception {
        KeyPair trustKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] document = authorityJwks(trustKey, "secret-key-dynamic")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/jwk-set+json");
            exchange.sendResponseHeaders(200, document.length);
            try (var body = exchange.getResponseBody()) {
                body.write(document);
            }
        });
        server.start();
        KeyPair inventoryKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Map<String, Object> properties = dynamicTestSecretAuthorityProperties(server);
        properties.putAll(signedTestSecretInventoryProperties(inventoryKey));
        properties.put("gateway.testing.store.jdbc-url",
                "jdbc:h2:mem:profile-signed-test-secret;DB_CLOSE_DELAY=-1");
        try {
            try (AnnotationConfigApplicationContext context = context(properties, 0, "test")) {
                assertThat(context.getBean(TestSecretAuthorityServingInventoryAuthority.class)
                        .observation()).satisfies(observation -> {
                            assertThat(observation.available()).isTrue();
                            assertThat(observation.externallyAttested()).isTrue();
                            assertThat(observation.expectedInstanceIds())
                                    .containsExactly("replica-a");
                        });
                assertThat(context.getBean(TestSecretAuthorityTrustCohortPolicy.class)
                        .servingInventory().externallyAttested()).isTrue();
                assertThat(context.getBean(TestSecretAuthorityTrustCohortMonitor.class)
                        .descriptor()).satisfies(descriptor -> {
                            assertThat(descriptor.available()).isTrue();
                            assertThat(descriptor.externallyAttestedInventory()).isTrue();
                            assertThat(descriptor.distinctServingInventoryGenerationCount())
                                    .isEqualTo(1);
                        });
                assertThat(context.getBean(TestSecretAuthority.class).descriptor().properties())
                        .containsEntry("trustCohortExternallyAttestedInventory", true)
                        .containsEntry("trustCohortDistinctInventoryGenerationCount", 1);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dynamicWitnessedTestSecretInventoryAssemblesFloorHealthAndResolutionGate()
            throws Exception {
        KeyPair trustKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] jwks = authorityJwks(trustKey, "secret-key-dynamic")
                .getBytes(StandardCharsets.UTF_8);
        KeyPair deploymentKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair witnessKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair deploymentRootKey = KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair();
        KeyPair witnessRootKey = KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair();
        DynamicTestSecretInventoryFixture inventory = dynamicTestSecretInventoryFixture(
                deploymentKey, witnessKey);
        DynamicTestSecretTrustRootFixture trustRoots = dynamicTestSecretTrustRootFixture(
                deploymentRootKey, witnessRootKey, deploymentKey, witnessKey,
                inventory.policyFingerprint());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/jwk-set+json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var body = exchange.getResponseBody()) {
                body.write(jwks);
            }
        });
        server.createContext("/test-secret-inventory", exchange -> {
            exchange.getResponseHeaders().add("Content-Type",
                    DynamicTestSecretAuthorityServingInventoryAuthority.MEDIA_TYPE);
            exchange.getResponseHeaders().add(
                    DynamicTestSecretAuthorityServingInventoryAuthority.PROTOCOL_HEADER,
                    TestSecretAuthorityServingInventoryPublication.SCHEMA_VERSION);
            exchange.getResponseHeaders().add("ETag", "profile-generation-1");
            exchange.sendResponseHeaders(200, inventory.document().length);
            try (var body = exchange.getResponseBody()) {
                body.write(inventory.document());
            }
        });
        server.createContext("/test-secret-inventory-trust-roots", exchange -> {
            exchange.getResponseHeaders().add("Content-Type",
                    DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.MEDIA_TYPE);
            exchange.getResponseHeaders().add(
                    DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.PROTOCOL_HEADER,
                    TestSecretAuthorityServingInventoryTrustRootPublication.SCHEMA_VERSION);
            exchange.getResponseHeaders().add("ETag", "profile-root-generation-1");
            exchange.sendResponseHeaders(200, trustRoots.document().length);
            try (var body = exchange.getResponseBody()) {
                body.write(trustRoots.document());
            }
        });
        server.start();
        Map<String, Object> properties = dynamicTestSecretAuthorityProperties(server);
        properties.putAll(inventory.managedProperties(
                server.getAddress().getPort(), trustRoots));
        properties.put("gateway.testing.store.jdbc-url",
                "jdbc:h2:mem:profile-dynamic-test-secret-inventory;DB_CLOSE_DELAY=-1");
        try {
            try (AnnotationConfigApplicationContext context = context(properties, 0, "test")) {
                assertThat(context.getBean(TestSecretAuthorityServingInventoryAuthority.class))
                        .isInstanceOf(
                                DynamicTestSecretAuthorityServingInventoryAuthority.class);
                assertThat(context.getBean(
                        TestSecretAuthorityServingInventoryTrustRootFloor.class).durable())
                        .isTrue();
                assertThat(context.getBean(
                        DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.class)
                        .snapshot().available()).isTrue();
                assertThat(context.getBean(TestSecretAuthorityServingInventoryTrustRootHealth.class)
                        .health().getStatus()).isEqualTo(Status.UP);
                assertThat(context.getBean(TestSecretAuthorityServingInventoryHealth.class)
                        .health()).satisfies(health -> {
                            assertThat(health.getStatus()).isEqualTo(Status.UP);
                            assertThat(health.getDetails())
                                    .containsEntry("publicationState", "ACTIVE")
                                    .containsEntry("durablePublicationFloor", true)
                                    .doesNotContainKeys("etag", "uri", "authorityId",
                                            "fingerprint", "instanceIds", "publicKey");
                        });
                assertThat(context.getBean(TestSecretAuthority.class).descriptor())
                        .satisfies(descriptor -> {
                            assertThat(descriptor.available()).isTrue();
                            assertThat(descriptor.properties())
                                    .containsEntry("servingInventorySourceType",
                                            DynamicTestSecretAuthorityServingInventoryAuthority
                                                    .SOURCE_TYPE)
                                    .containsEntry("servingInventorySignedRevocation", true)
                                    .containsEntry("servingInventoryWitnessedPublications", true)
                                    .containsEntry("servingInventoryDurablePublicationFloor",
                                            true)
                                    .containsEntry("servingInventoryManagedTrustRootRefresh", true)
                                    .containsEntry("servingInventoryAtomicDualTrustRootPublication",
                                            true)
                                    .containsEntry("servingInventoryDurableTrustRootFloor", true)
                                    .containsEntry(
                                            "servingInventoryExternallyAnchoredTrustRootFloor",
                                            false);
                        });
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stagingCohortRejectsUnsignedTestSecretServingInventory() throws Exception {
        KeyPair trustKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] document = authorityJwks(trustKey, "secret-key-dynamic")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/jwk-set+json");
            exchange.sendResponseHeaders(200, document.length);
            try (var body = exchange.getResponseBody()) {
                body.write(document);
            }
        });
        server.start();
        Map<String, Object> properties = dynamicTestSecretAuthorityProperties(server);
        properties.put("gateway.testing.store.jdbc-url",
                "jdbc:h2:mem:profile-staging-unsigned-test-secret;DB_CLOSE_DELAY=-1");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.signed-inventory.required",
                "true");
        AnnotationConfigApplicationContext context =
                unrefreshedContext(properties, 0, "staging");
        try {
            assertThatThrownBy(context::refresh).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "requires deployment-signed test-secret serving inventory");
        } finally {
            context.close();
            server.stop(0);
        }
    }

    @Test
    void managedTestSecretInventoryRootsRejectDisabledRemoteInventoryBeforeRootIo()
            throws Exception {
        KeyPair trustKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] document = authorityJwks(trustKey, "secret-key-dynamic")
                .getBytes(StandardCharsets.UTF_8);
        AtomicInteger rootCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/jwk-set+json");
            exchange.sendResponseHeaders(200, document.length);
            try (var body = exchange.getResponseBody()) {
                body.write(document);
            }
        });
        server.createContext("/roots", exchange -> {
            rootCalls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        Map<String, Object> properties = dynamicTestSecretAuthorityProperties(server);
        String prefix =
                "gateway.testing.test-secrets.authority.http.jwks.cohort.signed-inventory.";
        properties.put(prefix + "enabled", "true");
        properties.put(prefix + "required", "true");
        properties.put(prefix + "remote.enabled", "false");
        properties.put(prefix + "remote.trust-roots.enabled", "true");
        properties.put(prefix + "remote.trust-roots.uri",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/roots");
        properties.put("gateway.testing.store.jdbc-url",
                "jdbc:h2:mem:profile-invalid-test-secret-roots;DB_CLOSE_DELAY=-1");
        AnnotationConfigApplicationContext context = unrefreshedContext(properties, 0, "test");
        try {
            assertThatThrownBy(context::refresh).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "Managed test-secret inventory trust roots require remote inventory");
            assertThat(rootCalls).hasValue(0);
        } finally {
            context.close();
            server.stop(0);
        }
    }

    @Test
    void enabledDynamicTestSecretJwksRejectsMissingRemoteConfiguration() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("gateway.testing.test-secrets.authority.http.enabled", "true");
        properties.put("gateway.testing.test-secrets.authority.http.base-uri",
                "http://127.0.0.1:18082");
        properties.put("gateway.testing.test-secrets.authority.http.allow-insecure-loopback",
                "true");
        properties.put("gateway.testing.test-secrets.authority.http.expected-authority-id",
                "secret-authority.example");
        properties.put("gateway.testing.test-secrets.authority.http.jwks.enabled", "true");
        AnnotationConfigApplicationContext context =
                unrefreshedContext(properties, 0, "test");
        try {
            assertThatThrownBy(context::refresh).rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("JWKS URI");
        } finally {
            context.close();
        }
    }

    @Test
    void builtInSignedHttpAuthorityAssemblesWithoutADeploymentAuthorizer() throws Exception {
        Map<String, Object> properties = builtInAuthorityProperties();
        try (AnnotationConfigApplicationContext context = context(properties, 0, "test")) {
            assertThat(context.getBeansOfType(TestSuiteStabilityJobAuthorizer.class))
                    .hasSize(1).allSatisfy((name, authorizer) -> {
                        assertThat(authorizer).isInstanceOf(
                                HttpTestSuiteStabilityJobAuthorizer.class);
                        assertThat(authorizer.descriptor().available()).isTrue();
                        assertThat(authorizer.descriptor().properties())
                                .containsEntry("signedDecisions", true)
                                .containsEntry("challengeBound", true)
                                .doesNotContainKeys("baseUri", "publicKey", "privateKey");
                    });
            assertThat(context.getBean(TestabilityAvailability.class)
                    .suiteStabilityCurrentAuthority().providerType())
                    .isEqualTo("HTTPS_SIGNED_PDP");
            assertThat(context.getBean(TestabilityAvailability.class)
                    .suiteStabilityJobSubmissionEnabled()).isTrue();
        }
    }

    @Test
    void dynamicJwksAuthorityBootstrapsAtomicallyAndPublishesRefreshTruth()
            throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] document = authorityJwks(keyPair, "iam-key-dynamic")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/jwk-set+json");
            exchange.getResponseHeaders().add("ETag", "generation-1");
            exchange.sendResponseHeaders(200, document.length);
            try (var body = exchange.getResponseBody()) {
                body.write(document);
            }
        });
        server.start();
        Map<String, Object> properties = new LinkedHashMap<>(
                enabledStabilityWorkerProperties());
        properties.put("gateway.testing.stability-jobs.authority.http.enabled", "true");
        properties.put("gateway.testing.stability-jobs.authority.http.base-uri",
                "http://127.0.0.1:18080");
        properties.put(
                "gateway.testing.stability-jobs.authority.http.allow-insecure-loopback", "true");
        properties.put(
                "gateway.testing.stability-jobs.authority.http.expected-authority-id",
                "iam.example");
        properties.put("gateway.testing.stability-jobs.authority.http.jwks.enabled", "true");
        properties.put("gateway.testing.stability-jobs.authority.http.jwks.uri",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
        properties.put(
                "gateway.testing.stability-jobs.authority.http.jwks.allow-insecure-loopback",
                "true");
        properties.put(
                "gateway.testing.stability-jobs.authority.http.jwks.refresh-interval-seconds",
                "3600");
        properties.put(
                "gateway.testing.stability-jobs.authority.http.jwks.maximum-snapshot-age-seconds",
                "3610");
        properties.put(
                "gateway.testing.stability-jobs.authority.http.jwks.cohort.enabled", "true");
        properties.put(
                "gateway.testing.stability-jobs.authority.http.jwks.cohort.scope-id",
                "profile-stability-authority");
        properties.put(
                "gateway.testing.stability-jobs.authority.http.jwks.cohort.cohort-id",
                "deployment-profile-a");
        properties.put(
                "gateway.testing.stability-jobs.authority.http.jwks.cohort.instance-id",
                "profile-replica-a");
        properties.put(
                "gateway.testing.stability-jobs.authority.http.jwks.cohort.artifact-fingerprint",
                "sha256:" + "f".repeat(64));
        properties.put(
                "gateway.testing.stability-jobs.authority.http.jwks.cohort.expected-instance-ids",
                "profile-replica-a");
        properties.put(
                "gateway.testing.stability-jobs.authority.http.jwks.cohort.heartbeat-interval-seconds",
                "1");
        properties.put(
                "gateway.testing.stability-jobs.authority.http.jwks.cohort.lease-duration-seconds",
                "3");
        try {
            try (AnnotationConfigApplicationContext context = context(properties, 0, "test")) {
                assertThat(context.getBeansOfType(
                        TestSuiteStabilityAuthorityTrustStore.class))
                        .hasSize(1).allSatisfy((name, trust) -> {
                            assertThat(trust).isInstanceOf(
                                    DynamicJwksTestSuiteStabilityAuthorityTrustStore.class);
                            assertThat(trust.descriptor()).satisfies(descriptor -> {
                                assertThat(descriptor.available()).isTrue();
                                assertThat(descriptor.providerType())
                                        .isEqualTo("DYNAMIC_JWKS_ED25519");
                                assertThat(descriptor.properties())
                                        .containsEntry("refreshState", "HEALTHY")
                                        .containsEntry("automaticRefresh", true)
                                        .containsEntry("failClosedOnRefreshFailure", true);
                            });
                        });
                assertThat(context.getBean(TestSuiteStabilityJobAuthorizer.class)
                        .descriptor().properties())
                        .containsEntry("trustProviderType", "DYNAMIC_JWKS_ED25519")
                        .containsEntry("trustRefreshState", "HEALTHY")
                        .containsEntry("trustAutomaticRefresh", true)
                        .containsEntry("trustLocalAvailable", true)
                        .containsEntry("trustCohortConfigured", true)
                        .containsEntry("trustCohortConverged", true)
                        .containsEntry("trustCohortStatus", "CONVERGED")
                        .doesNotContainKeys("jwksUri", "baseUri", "publicKey", "privateKey");
                assertThat(context.getBean(TestSuiteStabilityAuthorityTrustHealth.class)
                        .health().getStatus()).isEqualTo(Status.UP);
                assertThat(context.getBean(TestSuiteStabilityAuthorityCohortHealth.class)
                        .health().getStatus()).isEqualTo(Status.UP);
                assertThat(context.getBeansOfType(
                        TestSuiteStabilityAuthorityCohortGate.class)).hasSize(1);
                assertThat(context.getBean(TestabilityAvailability.class)
                        .suiteStabilityJobSubmissionEnabled()).isTrue();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void incompleteOrAmbiguousBuiltInAuthorityConfigurationFailsStartup() throws Exception {
        Map<String, Object> incomplete = new LinkedHashMap<>(
                enabledStabilityWorkerProperties());
        incomplete.put("gateway.testing.stability-jobs.authority.http.enabled", "true");
        incomplete.put("gateway.testing.stability-jobs.authority.http.base-uri",
                "http://127.0.0.1:18080");
        incomplete.put("gateway.testing.stability-jobs.authority.http.allow-insecure-loopback",
                "true");
        incomplete.put("gateway.testing.stability-jobs.authority.http.expected-authority-id",
                "iam.example");
        AnnotationConfigApplicationContext missingKeys =
                unrefreshedContext(incomplete, 0, "test");
        try {
            assertThatThrownBy(missingKeys::refresh).rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Authority keys");
        } finally {
            missingKeys.close();
        }

        AnnotationConfigApplicationContext ambiguous =
                unrefreshedContext(builtInAuthorityProperties(), 1, "test");
        try {
            assertThatThrownBy(ambiguous::refresh).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires exactly one")
                    .hasMessageContaining("TestSuiteStabilityJobAuthorizer");
        } finally {
            ambiguous.close();
        }
    }

    @Test
    void invalidWorkerTimingAndEnvironmentCapacityFailDuringStartup() {
        Map<String, Object> invalidHeartbeat = new LinkedHashMap<>(
                enabledStabilityWorkerProperties());
        invalidHeartbeat.put(
                "gateway.testing.stability-jobs.worker.heartbeat-interval-seconds", "11");
        assertWorkerStartupRootCause(invalidHeartbeat,
                "heartbeat must be at most one-third");

        Map<String, Object> starvedEnvironment = new LinkedHashMap<>(
                enabledStabilityWorkerProperties());
        starvedEnvironment.put(
                "gateway.testing.stability-jobs.worker.environments", "test,staging");
        starvedEnvironment.put(
                "gateway.testing.stability-jobs.worker.maximum-pollers", "1");
        assertWorkerStartupRootCause(starvedEnvironment,
                "Every enabled stability queue requires at least one polling lane");
    }

    @Test
    void invalidStabilityQueuePolicyFailsStartupWhileWorkerIsDisabled() {
        AnnotationConfigApplicationContext context = unrefreshedContext(Map.of(
                "gateway.testing.stability-jobs.queue.maximum-running", "0"),
                0, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid suite-stability queue policy");
        } finally {
            context.close();
        }

        AnnotationConfigApplicationContext invalidSlo = unrefreshedContext(Map.of(
                "gateway.testing.stability-jobs.slo.maximum-queued-jobs", "-1"),
                0, "test");
        try {
            assertThatThrownBy(invalidSlo::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 0 and 100000");
        } finally {
            invalidSlo.close();
        }

        AnnotationConfigApplicationContext inertSlo = unrefreshedContext(Map.of(
                "gateway.testing.stability-jobs.slo.maximum-queued-jobs", "1001"),
                0, "test");
        try {
            assertThatThrownBy(inertSlo::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed hard queue capacity");
        } finally {
            inertSlo.close();
        }
    }

    @Test
    void invalidStabilityJobApiRetryHintFailsStartupWhileWorkerIsDisabled() {
        AnnotationConfigApplicationContext context = unrefreshedContext(Map.of(
                "gateway.testing.stability-jobs.api.retry-after-seconds", "0"),
                0, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("retryAfter must be whole seconds");
        } finally {
            context.close();
        }
    }

    @Test
    void invalidStabilityJobRequestIndexKeyFailsStartupWhileWorkerIsDisabled() {
        AnnotationConfigApplicationContext context = unrefreshedContext(Map.of(
                "gateway.testing.stability-jobs.retention.request-key-protection.key-ring",
                "profile-stability-job-v1=AAAAAAAAAAAAAAAAAAAAAA=="),
                0, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("32 bytes");
        } finally {
            context.close();
        }
    }

    @Test
    void invalidStabilityJobRetentionLifecycleFailsStartupWhileWorkerIsDisabled() {
        assertRetentionStartupRootCause(Map.of(
                "gateway.testing.stability-jobs.retention.lease-duration-seconds", "0"),
                "retentionLeaseDuration");
        assertRetentionStartupRootCause(Map.of(
                "gateway.testing.stability-jobs.retention.page-size", "1001"),
                "pageSize");
        assertRetentionStartupRootCause(Map.of(
                "gateway.testing.stability-jobs.retention.interval-ms", "999"),
                "scheduleInterval");
        assertRetentionStartupRootCause(Map.of(
                "gateway.testing.stability-jobs.retention.interval-ms", "3600000",
                "gateway.testing.stability-jobs.retention.lease-duration-seconds", "120",
                "gateway.testing.stability-jobs.retention.slo.max-retention-staleness-seconds",
                "3600"),
                "must cover one schedule and lease window");
    }

    @Test
    void productionProfileVetoesTestingBeansEvenWhenTestIsAlsoActive() {
        try (AnnotationConfigApplicationContext context = context("production", "test")) {
            assertThat(context.getBeansOfType(TestExecutionController.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityController.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobController.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestExecutionQueryController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestExecutionQueryService.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableTestOwnerClaimController.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableTestWorkerAcquisitionController.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableTestWorkerAcquisitionService.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableTestOwnerClaimService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryHeartbeatController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryHeartbeatService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryStepController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableRecoverySequenceRetentionScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableRecoverySequenceRetentionTelemetry.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableRecoverySequenceRetentionSloMonitor.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    RecoverySequenceRequestKeyProtector.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryRuntime.class)).isEmpty();
            assertThat(context.getBeansOfType(TestExecutionApiService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestExecutionCheckpointRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableTestRuntimeResources.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableStateProjectionReconciliationScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableStateProjectionFindingRetentionScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableStateProjectionSloMonitor.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableStateProjectionTelemetry.class)).isEmpty();
            assertThat(context.getBeansOfType(TestRuntimeSloMonitor.class)).isEmpty();
            assertThat(context.getBeansOfType(TestRuntimeSloTelemetry.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DatabaseTestRuntimeSloControlPlane.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DatabaseTestRuntimeAdmissionControl.class)).isEmpty();
            assertThat(context.getBeansOfType(TestRuntimeAdmissionCoordinator.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DatabaseDurableStateProjectionControlPlane.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableStateProjectionFindingController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableStateProjectionFindingService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DatabaseDurableWorkerQuarantineControlPlane.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    WorkerQuarantineClaimTokenProtector.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    WorkerQuarantineRequestKeyProtector.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableWorkerQuarantineRetentionScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableWorkerQuarantineRetentionTelemetry.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableWorkerQuarantineController.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableWorkerQuarantineService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    WorkerQuarantineRequestIndexRolloutService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    WorkerQuarantineRequestIndexRolloutController.class)).isEmpty();
            assertThat(context.getBeansOfType(StagedBlogeDurableStateStore.class)).isEmpty();
            assertThat(context.getBeansOfType(ExecutionStore.class)).isEmpty();
            assertThat(context.getBeansOfType(ExecutionCheckpointStore.class)).isEmpty();
            assertThat(context.getBeansOfType(WaitStore.class)).isEmpty();
            assertThat(context.getBeansOfType(WorkItemStore.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobParentAuthority.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRetentionScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRetentionTelemetry.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobRetentionSloMonitor.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityQueuePolicy.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobTelemetry.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobSloMonitor.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityJobExecutionCoordinator.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobWorker.class)).isEmpty();
            assertThat(context.getBeansOfType(TestSuiteStabilityJobScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(TestabilityAvailability.class)).isEmpty();
        }
    }

    private static AnnotationConfigApplicationContext context(String... profiles) {
        return context(Map.of(), 0, profiles);
    }

    private static AnnotationConfigApplicationContext context(
            Map<String, Object> overrides,
            int authorizerCount,
            String... profiles) {
        AnnotationConfigApplicationContext context =
                unrefreshedContext(overrides, authorizerCount, profiles);
        context.refresh();
        return context;
    }

    private static AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, Object> overrides,
            int authorizerCount,
            String... profiles) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        String profile = String.join("-", profiles);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("gateway.testing.store.jdbc-url",
                "jdbc:h2:mem:profile-" + profile + ";DB_CLOSE_DELAY=-1");
        properties.put("gateway.testing.store.retention-days", "1");
        properties.put(
                "gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id",
                "profile-test-v1");
        properties.put(
                "gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring",
                "profile-test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        properties.put(
                "gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id",
                "profile-request-index-v1");
        properties.put(
                "gateway.testing.durable.worker-quarantines.request-key-protection.key-ring",
                "profile-request-index-v1=HyAdHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=");
        properties.put(
                "gateway.testing.durable.worker-quarantines.request-key-protection.write-mode",
                "KEYED_ONLY");
        properties.put(
                "gateway.testing.stability-jobs.retention.request-key-protection.active-key-id",
                "profile-stability-job-v1");
        properties.put(
                "gateway.testing.stability-jobs.retention.request-key-protection.key-ring",
                "profile-stability-job-v1=QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8=");
        properties.put(
                "gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id",
                "profile-replica-a");
        properties.put(
                "gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint",
                "sha256:" + "f".repeat(64));
        properties.putAll(overrides);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "test-runtime", properties));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(GatewayGraphService.class, () -> mock(GatewayGraphService.class));
        context.registerBean(OperatorRegistry.class, () -> mock(OperatorRegistry.class));
        context.registerBean(ResourceRegistry.class, () -> mock(ResourceRegistry.class));
        context.registerBean(VisualGraphRunRepository.class, () -> mock(VisualGraphRunRepository.class));
        context.registerBean(BlgeExpressionEvaluator.class, () -> new BlgeExpressionEvaluator());
        context.registerBean(IntegrationRequestAuthenticator.class,
                () -> mock(IntegrationRequestAuthenticator.class));
        context.registerBean(VisualEvidenceSigner.class, InMemoryVisualEvidenceSigner::new);
        for (int index = 0; index < authorizerCount; index++) {
            context.registerBean("testSuiteStabilityJobAuthorizer" + index,
                    TestSuiteStabilityJobAuthorizer.class,
                    TestRuntimeProfileIsolationTest::readyTestAuthorizer);
        }
        context.register(TestRuntimeConfiguration.class, TestExecutionController.class,
                TestSuiteStabilityController.class,
                TestSuiteStabilityCrossRetentionTrendController.class,
                TestSuiteStabilityObservationLedgerLifecycleController.class,
                TestSuiteStabilityObservationLedgerLifecycleArchiveController.class,
                TestSuiteStabilityJobController.class,
                DurableTestExecutionQueryController.class,
                DurableTestOwnerClaimController.class,
                DurableTestWorkerAcquisitionController.class,
                DurableStateProjectionFindingController.class,
                DurableWorkerQuarantineController.class,
                WorkerQuarantineRequestIndexRolloutController.class,
                DurableTestRecoveryHeartbeatController.class,
                DurableTestTerminalRecoveryController.class,
                DurableTestRecoveryStepController.class,
                DurableTestRecoverySequenceController.class);
        return context;
    }

    private static Map<String, Object> enabledStabilityWorkerProperties() {
        return Map.of(
                "gateway.testing.stability-jobs.worker.enabled", "true",
                "gateway.testing.stability-jobs.worker.environments", "test",
                "gateway.testing.stability-jobs.worker.maximum-pollers", "1",
                "gateway.testing.stability-jobs.worker.initial-delay-ms", "300000",
                "gateway.testing.stability-jobs.worker.poll-interval-ms", "60000");
    }

    private static Map<String, Object> builtInAuthorityProperties() throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>(
                enabledStabilityWorkerProperties());
        String publicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPublic().getEncoded());
        properties.put("gateway.testing.stability-jobs.authority.http.enabled", "true");
        properties.put("gateway.testing.stability-jobs.authority.http.base-uri",
                "http://127.0.0.1:18080");
        properties.put("gateway.testing.stability-jobs.authority.http.allow-insecure-loopback",
                "true");
        properties.put("gateway.testing.stability-jobs.authority.http.expected-authority-id",
                "iam.example");
        properties.put("gateway.testing.stability-jobs.authority.http.authority-keys-json",
                "[{\"keyId\":\"iam-key-1\",\"algorithm\":\"Ed25519\","
                        + "\"publicKeyBase64\":\"" + publicKey + "\"}]");
        return properties;
    }

    private static Map<String, Object> builtInTestSecretAuthorityProperties() throws Exception {
        String publicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPublic().getEncoded());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("gateway.testing.test-secrets.authority.http.enabled", "true");
        properties.put("gateway.testing.test-secrets.authority.http.base-uri",
                "http://127.0.0.1:18082");
        properties.put("gateway.testing.test-secrets.authority.http.allow-insecure-loopback",
                "true");
        properties.put("gateway.testing.test-secrets.authority.http.expected-authority-id",
                "secret-authority.example");
        properties.put("gateway.testing.test-secrets.authority.http.authority-keys-json",
                "[{\"keyId\":\"secret-key-1\",\"algorithm\":\"Ed25519\","
                        + "\"publicKeyBase64\":\"" + publicKey + "\"}]");
        return properties;
    }

    private static Map<String, Object> dynamicTestSecretAuthorityProperties(HttpServer server) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("gateway.testing.test-secrets.authority.http.enabled", "true");
        properties.put("gateway.testing.test-secrets.authority.http.base-uri",
                "http://127.0.0.1:18082");
        properties.put("gateway.testing.test-secrets.authority.http.allow-insecure-loopback",
                "true");
        properties.put("gateway.testing.test-secrets.authority.http.expected-authority-id",
                "secret-authority.example");
        properties.put("gateway.testing.test-secrets.authority.http.jwks.enabled", "true");
        properties.put("gateway.testing.test-secrets.authority.http.jwks.uri",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.allow-insecure-loopback",
                "true");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.refresh-interval-seconds",
                "3600");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.maximum-snapshot-age-seconds",
                "3610");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.enabled", "true");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.scope-id",
                "test-secret-scope");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.cohort-id",
                "deployment-a");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.instance-id",
                "replica-a");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.artifact-fingerprint",
                "sha256:" + "a".repeat(64));
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.heartbeat-interval-seconds",
                "1");
        properties.put(
                "gateway.testing.test-secrets.authority.http.jwks.cohort.lease-duration-seconds",
                "3");
        return properties;
    }

    private static Map<String, Object> signedTestSecretInventoryProperties(KeyPair keyPair)
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String policy = "sha256:" + "b".repeat(64);
        var material = new TestSecretAuthorityServingInventory.Material(
                TestSecretAuthorityServingInventory.Material.SCHEMA_VERSION,
                "inventory.example", "inventory-profile-test", 1,
                "test-secret-scope", "deployment-a", "sha256:" + "a".repeat(64),
                TestSecretAuthorityResponse.SCHEMA_VERSION, "secret-authority.example",
                List.of("replica-a"), policy, now.minusSeconds(30), now.minusSeconds(30),
                now.plusSeconds(3600));
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        var signature = new TestSecretAuthorityServingInventory.AuthoritySignature(
                "deployment-inventory-a", "inventory-key-a", "Ed25519", now,
                Base64.getEncoder().encodeToString(signer.sign()));
        var inventory = new TestSecretAuthorityServingInventory(
                TestSecretAuthorityServingInventory.SCHEMA_VERSION,
                material, fingerprint, List.of(signature));
        String publicKey = Base64.getEncoder().encodeToString(
                keyPair.getPublic().getEncoded());
        String keysJson = "[{\"authorityId\":\"deployment-inventory-a\"," +
                "\"keyId\":\"inventory-key-a\",\"publicKeyBase64\":\"" + publicKey +
                "\",\"notBefore\":\"" + now.minusSeconds(60) +
                "\",\"expiresAt\":\"" + now.plusSeconds(7200) +
                "\",\"enabled\":true,\"revoked\":false}]";
        Map<String, Object> properties = new LinkedHashMap<>();
        String prefix =
                "gateway.testing.test-secrets.authority.http.jwks.cohort.signed-inventory.";
        properties.put(prefix + "enabled", "true");
        properties.put(prefix + "required", "true");
        properties.put(prefix + "trust-domain", "inventory.example");
        properties.put(prefix + "accepted-policy-fingerprints", policy);
        properties.put(prefix + "signature-threshold", "1");
        properties.put(prefix + "authority-keys-json", keysJson);
        properties.put(prefix + "inventory-json", objectMapper.writeValueAsString(inventory));
        return properties;
    }

    private static DynamicTestSecretInventoryFixture dynamicTestSecretInventoryFixture(
            KeyPair deploymentKey, KeyPair witnessKey) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String policy = "sha256:" + "b".repeat(64);
        var inventoryMaterial = new TestSecretAuthorityServingInventory.Material(
                TestSecretAuthorityServingInventory.Material.SCHEMA_VERSION,
                "inventory.example", "inventory-profile-dynamic", 1,
                "test-secret-scope", "deployment-a", "sha256:" + "a".repeat(64),
                TestSecretAuthorityResponse.SCHEMA_VERSION, "secret-authority.example",
                List.of("replica-a"), policy, now.minusSeconds(30), now.minusSeconds(30),
                now.plusSeconds(3600));
        String inventoryFingerprint = ProtocolFingerprint.of(objectMapper, inventoryMaterial);
        var inventory = new TestSecretAuthorityServingInventory(
                TestSecretAuthorityServingInventory.SCHEMA_VERSION,
                inventoryMaterial, inventoryFingerprint, List.of(testSecretInventorySignature(
                deploymentKey, "deployment-inventory-a", "inventory-key-a",
                inventoryFingerprint, now)));
        var publicationMaterial = new TestSecretAuthorityServingInventoryPublication.Material(
                TestSecretAuthorityServingInventoryPublication.Material.SCHEMA_VERSION,
                "inventory.example", "publication-profile-1", 1,
                inventoryFingerprint,
                TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                policy, "", now.minusSeconds(20), now.minusSeconds(20),
                now.plusSeconds(600), "");
        String publicationFingerprint = ProtocolFingerprint.of(
                objectMapper, publicationMaterial);
        var witnessMaterial =
                new TestSecretAuthorityServingInventoryPublication.WitnessMaterial(
                        TestSecretAuthorityServingInventoryPublication.WitnessMaterial
                                .SCHEMA_VERSION,
                        "inventory-witness.example", "checkpoint-profile-1", 1,
                        publicationFingerprint, "", now.minusSeconds(10),
                        now.minusSeconds(10), now.plusSeconds(600));
        String witnessFingerprint = ProtocolFingerprint.of(objectMapper, witnessMaterial);
        var witness = new TestSecretAuthorityServingInventoryPublication.WitnessCheckpoint(
                TestSecretAuthorityServingInventoryPublication.WitnessCheckpoint.SCHEMA_VERSION,
                witnessMaterial, witnessFingerprint, List.of(testSecretInventorySignature(
                witnessKey, "inventory-witness-a", "witness-key-a",
                witnessFingerprint, now)));
        var publication = new TestSecretAuthorityServingInventoryPublication(
                TestSecretAuthorityServingInventoryPublication.SCHEMA_VERSION,
                inventory, publicationMaterial, publicationFingerprint,
                List.of(testSecretInventorySignature(deploymentKey,
                        "deployment-inventory-a", "inventory-key-a",
                        publicationFingerprint, now)), witness);
        String deploymentPublic = Base64.getEncoder().encodeToString(
                deploymentKey.getPublic().getEncoded());
        String witnessPublic = Base64.getEncoder().encodeToString(
                witnessKey.getPublic().getEncoded());
        String deploymentKeys = "[{\"authorityId\":\"deployment-inventory-a\"," +
                "\"keyId\":\"inventory-key-a\",\"publicKeyBase64\":\"" +
                deploymentPublic + "\",\"enabled\":true,\"revoked\":false}]";
        String witnessKeys = "[{\"authorityId\":\"inventory-witness-a\"," +
                "\"keyId\":\"witness-key-a\",\"publicKeyBase64\":\"" +
                witnessPublic + "\",\"enabled\":true,\"revoked\":false}]";
        return new DynamicTestSecretInventoryFixture(
                objectMapper.writeValueAsBytes(publication), policy,
                deploymentKeys, witnessKeys);
    }

    private static TestSecretAuthorityServingInventory.AuthoritySignature
            testSecretInventorySignature(
            KeyPair keyPair, String authorityId, String keyId,
            String fingerprint, Instant signedAt) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        return new TestSecretAuthorityServingInventory.AuthoritySignature(
                authorityId, keyId, "Ed25519", signedAt,
                Base64.getEncoder().encodeToString(signer.sign()));
    }

    private static DynamicTestSecretTrustRootFixture dynamicTestSecretTrustRootFixture(
            KeyPair deploymentRootKey,
            KeyPair witnessRootKey,
            KeyPair deploymentKey,
            KeyPair witnessKey,
            String policyFingerprint) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var deploymentMaterial =
                new TestSecretAuthorityServingInventoryTrustRootPublication.AuthorityKeyMaterial(
                        "deployment-inventory-a", "inventory-key-a",
                        Base64.getEncoder().encodeToString(
                                deploymentKey.getPublic().getEncoded()),
                        now.minusSeconds(60), now.plusSeconds(7200), true, false);
        var witnessMaterial =
                new TestSecretAuthorityServingInventoryTrustRootPublication.AuthorityKeyMaterial(
                        "inventory-witness-a", "witness-key-a",
                        Base64.getEncoder().encodeToString(
                                witnessKey.getPublic().getEncoded()),
                        now.minusSeconds(60), now.plusSeconds(7200), true, false);
        var material = new TestSecretAuthorityServingInventoryTrustRootPublication.Material(
                TestSecretAuthorityServingInventoryTrustRootPublication.Material.SCHEMA_VERSION,
                "test-secret-inventory-roots", 1, "", "test-secret-scope",
                ToolStudioResourceGatewayProtocol.VERSION,
                "inventory-deployment-root.example", "inventory-witness-root.example",
                "inventory.example", "inventory-witness.example", 1, 1,
                List.of(deploymentMaterial), List.of(witnessMaterial), policyFingerprint,
                now.minusSeconds(30), now.minusSeconds(30), now.plusSeconds(3600));
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        var publication = new TestSecretAuthorityServingInventoryTrustRootPublication(
                TestSecretAuthorityServingInventoryTrustRootPublication.SCHEMA_VERSION,
                material, fingerprint,
                List.of(testSecretInventorySignature(deploymentRootKey,
                        "inventory-deployment-root-a", "deployment-root-key-a",
                        fingerprint, now)),
                List.of(testSecretInventorySignature(witnessRootKey,
                        "inventory-witness-root-a", "witness-root-key-a",
                        fingerprint, now)));
        return new DynamicTestSecretTrustRootFixture(
                objectMapper.writeValueAsBytes(publication),
                authorityKeysJson(deploymentRootKey, "inventory-deployment-root-a",
                        "deployment-root-key-a", now),
                authorityKeysJson(witnessRootKey, "inventory-witness-root-a",
                        "witness-root-key-a", now));
    }

    private static String authorityKeysJson(
            KeyPair keyPair, String authorityId, String keyId, Instant now) {
        return "[{\"authorityId\":\"" + authorityId + "\",\"keyId\":\"" + keyId
                + "\",\"publicKeyBase64\":\""
                + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\",\"notBefore\":\"" + now.minusSeconds(60)
                + "\",\"expiresAt\":\"" + now.plusSeconds(7200)
                + "\",\"enabled\":true,\"revoked\":false}]";
    }

    private record DynamicTestSecretInventoryFixture(
            byte[] document,
            String policyFingerprint,
            String deploymentKeys,
            String witnessKeys) {

        private Map<String, Object> managedProperties(
                int port, DynamicTestSecretTrustRootFixture trustRoots) {
            String prefix =
                    "gateway.testing.test-secrets.authority.http.jwks.cohort.signed-inventory.";
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put(prefix + "enabled", "true");
            properties.put(prefix + "required", "true");
            properties.put(prefix + "accepted-policy-fingerprints", policyFingerprint);
            properties.put(prefix + "remote.enabled", "true");
            properties.put(prefix + "remote.required", "true");
            properties.put(prefix + "remote.uri",
                    "http://127.0.0.1:" + port + "/test-secret-inventory");
            properties.put(prefix + "remote.refresh-interval-seconds", "3600");
            properties.put(prefix + "remote.request-timeout-ms", "1000");
            properties.put(prefix + "remote.maximum-snapshot-age-seconds", "7200");
            properties.put(prefix + "remote.allow-insecure-loopback", "true");
            String roots = prefix + "remote.trust-roots.";
            properties.put(roots + "enabled", "true");
            properties.put(roots + "required", "true");
            properties.put(roots + "uri",
                    "http://127.0.0.1:" + port + "/test-secret-inventory-trust-roots");
            properties.put(roots + "trust-root-set-id", "test-secret-inventory-roots");
            properties.put(roots + "accepted-policy-fingerprints", policyFingerprint);
            properties.put(roots + "deployment-root-domain",
                    "inventory-deployment-root.example");
            properties.put(roots + "deployment-root-signature-threshold", "1");
            properties.put(roots + "deployment-root-authority-keys-json",
                    trustRoots.deploymentRootKeys());
            properties.put(roots + "witness-root-domain", "inventory-witness-root.example");
            properties.put(roots + "witness-root-signature-threshold", "1");
            properties.put(roots + "witness-root-authority-keys-json",
                    trustRoots.witnessRootKeys());
            properties.put(roots + "refresh-interval-seconds", "3600");
            properties.put(roots + "request-timeout-ms", "1000");
            properties.put(roots + "maximum-snapshot-age-seconds", "7200");
            properties.put(roots + "allow-insecure-loopback", "true");
            return properties;
        }

        @Override
        public byte[] document() {
            return document.clone();
        }
    }

    private record DynamicTestSecretTrustRootFixture(
            byte[] document,
            String deploymentRootKeys,
            String witnessRootKeys) {

        @Override
        public byte[] document() {
            return document.clone();
        }
    }

    private static Map<String, Object> externalObservationArchiveProperties()
            throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(
                keyPair.getPublic().getEncoded());
        Map<String, Object> properties = new LinkedHashMap<>();
        String prefix =
                "gateway.testing.stability-observation-lifecycle.external-archive.http.";
        properties.put(prefix + "enabled", "true");
        properties.put(prefix + "trust-domain", "archive.example");
        properties.put(prefix + "archive-set-id", "archive-set-a");
        properties.put(prefix + "required-copies", "1");
        properties.put(prefix + "minimum-retention-days", "1");
        properties.put(prefix + "request-timeout-ms", "1000");
        properties.put(prefix + "maximum-receipt-lifetime-seconds", "10");
        properties.put(prefix + "allow-insecure-loopback", "true");
        properties.put(prefix + "authority-keys-json",
                "[{\"authorityId\":\"archive-a\",\"keyId\":\"archive-key-a\","
                        + "\"publicKeyBase64\":\"" + publicKey + "\","
                        + "\"notBefore\":\"2020-01-01T00:00:00Z\","
                        + "\"expiresAt\":\"2099-01-01T00:00:00Z\","
                        + "\"enabled\":true,\"revoked\":false}]");
        properties.put(prefix + "endpoints-json",
                "[{\"authorityId\":\"archive-a\","
                        + "\"failureDomain\":\"region-a\","
                        + "\"uri\":\"http://127.0.0.1:18081/archive\"}]");
        return properties;
    }

    private static Map<String, Object> externalObservationArchiveStagingProperties()
            throws Exception {
        KeyPair first = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair second = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String firstPublic = Base64.getEncoder().encodeToString(first.getPublic().getEncoded());
        String secondPublic = Base64.getEncoder().encodeToString(
                second.getPublic().getEncoded());
        Map<String, Object> properties = new LinkedHashMap<>();
        String prefix =
                "gateway.testing.stability-observation-lifecycle.external-archive.http.";
        properties.put(prefix + "enabled", "true");
        properties.put(prefix + "trust-domain", "archive.example");
        properties.put(prefix + "archive-set-id", "archive-set-a");
        properties.put(prefix + "required-copies", "2");
        properties.put(prefix + "minimum-retention-days", "365");
        properties.put(prefix + "request-timeout-ms", "1000");
        properties.put(prefix + "maximum-receipt-lifetime-seconds", "10");
        properties.put(prefix + "allow-insecure-loopback", "false");
        properties.put(prefix + "authority-keys-json",
                "[{\"authorityId\":\"archive-a\",\"keyId\":\"key-a\","
                        + "\"publicKeyBase64\":\"" + firstPublic + "\","
                        + "\"notBefore\":\"2020-01-01T00:00:00Z\","
                        + "\"expiresAt\":\"2099-01-01T00:00:00Z\","
                        + "\"enabled\":true,\"revoked\":false},"
                        + "{\"authorityId\":\"archive-b\",\"keyId\":\"key-b\","
                        + "\"publicKeyBase64\":\"" + secondPublic + "\","
                        + "\"notBefore\":\"2020-01-01T00:00:00Z\","
                        + "\"expiresAt\":\"2099-01-01T00:00:00Z\","
                        + "\"enabled\":true,\"revoked\":false}]");
        properties.put(prefix + "endpoints-json",
                "[{\"authorityId\":\"archive-a\","
                        + "\"failureDomain\":\"region-a\","
                        + "\"uri\":\"https://archive-a.example/v1/objects\"},"
                        + "{\"authorityId\":\"archive-b\","
                        + "\"failureDomain\":\"region-b\","
                        + "\"uri\":\"https://archive-b.example/v1/objects\"}]");
        return properties;
    }

    private static Map<String, Object> externalObservationArchiveReconciliationProperties()
            throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>(
                externalObservationArchiveProperties());
        properties.put(reconciliationPrefix() + "enabled", "true");
        properties.put(reconciliationPrefix() + "instance-id", "profile-replica-a");
        properties.put(reconciliationPrefix() + "initial-delay-ms", "300000");
        properties.put(reconciliationPrefix() + "retention-initial-delay-ms", "300000");
        properties.put(reconciliationPrefix() + "source-retention-initial-delay-ms", "300000");
        return properties;
    }

    private static String reconciliationPrefix() {
        return "gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.";
    }

    private static String authorityJwks(KeyPair keyPair, String keyId) {
        byte[] encoded = keyPair.getPublic().getEncoded();
        String coordinate = Base64.getUrlEncoder().withoutPadding().encodeToString(
                Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
        return "{\"keys\":[{\"kid\":\"" + keyId + "\",\"kty\":\"OKP\","
                + "\"crv\":\"Ed25519\",\"alg\":\"EdDSA\",\"use\":\"sig\","
                + "\"key_ops\":[\"verify\"],\"x\":\"" + coordinate + "\"}]}";
    }

    private static TestSuiteStabilityJobAuthorizer readyTestAuthorizer() {
        return new TestSuiteStabilityJobAuthorizer() {
            @Override
            public Authorization reauthorize(TestSuiteStabilityJobRecord job) {
                return Authorization.authorized();
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor("", true, "TEST", "test-authority", Map.of(
                        "signedDecisions", false,
                        "challengeBound", false,
                        "privateMaterialPresent", false));
            }
        };
    }

    private static void assertWorkerStartupRootCause(
            Map<String, Object> properties, String message) {
        AnnotationConfigApplicationContext context =
                unrefreshedContext(properties, 1, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(message);
        } finally {
            context.close();
        }
    }

    private static void assertRetentionStartupRootCause(
            Map<String, Object> properties, String message) {
        AnnotationConfigApplicationContext context =
                unrefreshedContext(properties, 0, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(message);
        } finally {
            context.close();
        }
    }
}
