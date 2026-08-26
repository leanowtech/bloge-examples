package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.function.CompiledFunctionInventoryProvider;
import com.leanowtech.bloge.gateway.testing.protocol.TestAssetReference;
import com.leanowtech.bloge.gateway.testing.protocol.TestControlEnvelope;
import com.leanowtech.bloge.gateway.testing.world.WorldReferenceExecutionPlanner;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioRunService;
import com.leanowtech.bloge.gateway.testing.world.access.AuthorizedWorldAssetResolver;
import com.leanowtech.bloge.gateway.testing.world.access.AuthorizedFunctionControlAssetResolver;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Proves the server-side production boundary on the real envelope ingress, without the filter. */
class TestExecutionProductionIngressBypassTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void productionAdmittedEnvelopeWithFunctionReferenceStopsBeforeEveryControlDependency() {
        GatewayGraphService graphService = mock(GatewayGraphService.class);
        OperatorRegistry operatorRegistry = mock(OperatorRegistry.class);
        ResourceRegistry resourceRegistry = mock(ResourceRegistry.class);
        FixtureBundleRepository fixtures = mock(FixtureBundleRepository.class);
        TestRunRepository runs = mock(TestRunRepository.class);
        TestSecurityEventRepository securityEvents = mock(TestSecurityEventRepository.class);
        TestReplayPayloadService replayPayloads = mock(TestReplayPayloadService.class);
        TestEvidenceIntegrityService evidenceIntegrity = mock(TestEvidenceIntegrityService.class);
        WorldReferenceExecutionPlanner planner = mock(WorldReferenceExecutionPlanner.class);
        WorldScenarioRunService runner = mock(WorldScenarioRunService.class);
        CompiledFunctionInventoryProvider inventoryProvider = mock(CompiledFunctionInventoryProvider.class);
        AuthorizedWorldAssetResolver worldResolver = mock(AuthorizedWorldAssetResolver.class);
        GovernedCatalogRepository functionCatalog = mock(GovernedCatalogRepository.class);
        AuthorizedFunctionControlAssetResolver functionResolver =
                new AuthorizedFunctionControlAssetResolver(functionCatalog,
                        (context, ref) -> { throw new AssertionError("function authorization reached"); },
                        (context, metadata) -> { throw new AssertionError("function metadata reached"); },
                        null);
        AtomicInteger admissionCalls = new AtomicInteger();
        TestRuntimeAdmissionGate admissions = (identity, intent) -> {
            admissionCalls.incrementAndGet();
            throw new AssertionError("admission reached");
        };

        TestExecutionApiService service = new TestExecutionApiService(
                graphService, operatorRegistry, resourceRegistry, new BlgeExpressionEvaluator(),
                new ObjectMapper().findAndRegisterModules(), fixtures, runs, securityEvents,
                Duration.ofDays(1), replayPayloads, evidenceIntegrity, admissions, null,
                worldResolver,
                planner, runner, functionResolver, inventoryProvider);
        TestExecutionApiRequest request = new TestExecutionApiRequest(
                TestExecutionApiRequest.SCHEMA_VERSION,
                new TestExecutionApiRequest.Target("GRAPH", "must-not-run", ""),
                TestExecutionApiService.AUTHORIZED_PURPOSE, Map.of("business", "value"),
                null, null, TestExecutionApiRequest.Verbosity.SUMMARY, Map.of());
        TestControlEnvelope envelope = new TestControlEnvelope(
                TestExecutionApiService.AUTHORIZED_PURPOSE, null,
                new TestAssetReference("world-ref", 1, FINGERPRINT), "production-correlation",
                new TestAssetReference("function-ref", 1, FINGERPRINT));

        assertThatThrownBy(() -> service.executeAdmittedIngress(
                new TestExecutionIngress(request, "SUMMARY", "scope", envelope),
                productionIdentity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(403);
                    assertThat(failure.problem().code()).isEqualTo("RG.TEST.ENVIRONMENT_FORBIDDEN");
                    assertThat(failure.getMessage()).doesNotContain("function-ref", "business", FINGERPRINT);
                });

        assertThat(admissionCalls).hasValue(0);
        verifyNoInteractions(graphService, operatorRegistry, resourceRegistry, fixtures, runs,
                replayPayloads, evidenceIntegrity, planner, runner, inventoryProvider, functionCatalog);
        verifyNoInteractions(worldResolver);
        verify(securityEvents).append(any());
    }

    private static IntegrationRequestContext productionIdentity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "production",
                "local", "WORKLOAD", "test-runner", "", "TEST_EXECUTION",
                "production-correlation", Set.of("quality"), "CONFIDENTIAL", "");
    }
}
