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
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.StagedBlogeDurableStateStore;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestRuntimeResources;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestTerminalRecoveryRuntime;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import org.junit.jupiter.api.Test;
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
            assertThat(context.getBeansOfType(DurableTestOwnerClaimService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryHeartbeatController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryHeartbeatService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryController.class)).isEmpty();
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
            assertThat(context.getBeansOfType(DurableTestOwnerClaimService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryHeartbeatController.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryHeartbeatService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryController.class)).hasSize(1);
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
            assertThat(context.getBean(TestabilityAvailability.class).executionEndpointEnabled()).isTrue();
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            TestRunEvidence evidence = TestSemanticResultFingerprint.attach(mapper,
                    new TestRunEvidence("", "profile-run",
                            TestRunEvidence.Status.EVIDENCE_INCOMPLETE,
                            TestRunEvidence.EvidenceClass.EXPLORATORY,
                            "TEST", "", "", "", Instant.EPOCH, Instant.EPOCH,
                            List.of(), List.of(), List.of(), List.of(), List.of(), Map.of()));
            TestEvidenceIntegrityService.SealResult seal = context
                    .getBean(TestEvidenceIntegrityService.class).seal(evidence);
            assertThat(seal.verified()).isFalse();
            assertThat(seal.failureCode()).isEqualTo(TestEvidenceIntegrityService.SIGNER_UNAVAILABLE);
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
            assertThat(context.getBeansOfType(DurableTestOwnerClaimService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryHeartbeatController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestRecoveryHeartbeatService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryController.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestTerminalRecoveryRuntime.class)).isEmpty();
            assertThat(context.getBeansOfType(TestExecutionApiService.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DurableTestExecutionCheckpointRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(DurableTestRuntimeResources.class)).isEmpty();
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
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-runtime", Map.of(
                "gateway.testing.store.jdbc-url", "jdbc:h2:mem:profile-" + profile + ";DB_CLOSE_DELAY=-1",
                "gateway.testing.store.retention-days", "1")));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(GatewayGraphService.class, () -> mock(GatewayGraphService.class));
        context.registerBean(OperatorRegistry.class, () -> mock(OperatorRegistry.class));
        context.registerBean(ResourceRegistry.class, () -> mock(ResourceRegistry.class));
        context.registerBean(VisualGraphRunRepository.class, () -> mock(VisualGraphRunRepository.class));
        context.registerBean(BlgeExpressionEvaluator.class, () -> new BlgeExpressionEvaluator());
        context.registerBean(IntegrationRequestAuthenticator.class,
                () -> mock(IntegrationRequestAuthenticator.class));
        context.register(TestRuntimeConfiguration.class, TestExecutionController.class,
                DurableTestExecutionQueryController.class,
                DurableTestOwnerClaimController.class,
                DurableTestRecoveryHeartbeatController.class,
                DurableTestTerminalRecoveryController.class);
        context.refresh();
        return context;
    }
}
