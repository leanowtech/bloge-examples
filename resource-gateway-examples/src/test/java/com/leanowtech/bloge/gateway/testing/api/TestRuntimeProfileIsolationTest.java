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
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionCoordinator;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionPolicy;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionRetentionScheduler;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionTelemetry;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeSloControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl;
import com.leanowtech.bloge.gateway.testing.persistence.RecoverySequenceRequestKeyProtector;
import com.leanowtech.bloge.gateway.testing.persistence.StagedBlogeDurableStateStore;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestRuntimeProfileIsolationTest {

    @Test
    void productionProfileHasNoTestingControllerStoreOrCapabilityMarker() {
        try (AnnotationConfigApplicationContext context = context("production")) {
            assertThat(context.getBeansOfType(TestExecutionController.class)).isEmpty();
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
            assertThat(context.getBeansOfType(TestabilityAvailability.class)).isEmpty();
        }
    }

    @Test
    void testProfileAssemblesIndependentStoreControllerAndCapabilityMarker() {
        try (AnnotationConfigApplicationContext context = context("test")) {
            assertThat(context.getBeansOfType(TestExecutionController.class)).hasSize(1);
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
                    .workerQuarantineRequestIndexMode())
                    .isEqualTo(WorkerQuarantineRequestIndexMode.KEYED_ONLY);
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
    void productionProfileVetoesTestingBeansEvenWhenTestIsAlsoActive() {
        try (AnnotationConfigApplicationContext context = context("production", "test")) {
            assertThat(context.getBeansOfType(TestExecutionController.class)).isEmpty();
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
            assertThat(context.getBeansOfType(TestabilityAvailability.class)).isEmpty();
        }
    }

    private static AnnotationConfigApplicationContext context(String... profiles) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        String profile = String.join("-", profiles);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "test-runtime", Map.of(
                "gateway.testing.store.jdbc-url",
                "jdbc:h2:mem:profile-" + profile + ";DB_CLOSE_DELAY=-1",
                "gateway.testing.store.retention-days", "1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id",
                "profile-test-v1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring",
                "profile-test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id",
                "profile-request-index-v1",
                "gateway.testing.durable.worker-quarantines.request-key-protection.key-ring",
                "profile-request-index-v1=HyAdHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.write-mode",
                "KEYED_ONLY",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id",
                "profile-replica-a",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint",
                "sha256:" + "f".repeat(64))));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(GatewayGraphService.class, () -> mock(GatewayGraphService.class));
        context.registerBean(OperatorRegistry.class, () -> mock(OperatorRegistry.class));
        context.registerBean(ResourceRegistry.class, () -> mock(ResourceRegistry.class));
        context.registerBean(VisualGraphRunRepository.class, () -> mock(VisualGraphRunRepository.class));
        context.registerBean(BlgeExpressionEvaluator.class, () -> new BlgeExpressionEvaluator());
        context.registerBean(IntegrationRequestAuthenticator.class,
                () -> mock(IntegrationRequestAuthenticator.class));
        context.registerBean(VisualEvidenceSigner.class, InMemoryVisualEvidenceSigner::new);
        context.register(TestRuntimeConfiguration.class, TestExecutionController.class,
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
        context.refresh();
        return context;
    }
}
