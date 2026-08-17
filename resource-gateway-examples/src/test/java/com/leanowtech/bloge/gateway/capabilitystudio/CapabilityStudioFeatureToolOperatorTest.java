package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.OperatorExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorMicroGraphRunner;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioFeatureToolOperatorTest {

    private static final String CASE_ID = "case-standard-cancellation-fee";
    private static final String TARGET_FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void executesTheCanonicalFeatureGraphThroughTheExistingOperatorMicroGraphRuntime() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        CapabilityStudioFeatureRehearsalService service = service(mapper, registry);
        CapabilityStudioFeatureRehearsalService.RuntimeAsset asset = service.runtimeAsset();
        registry.register(CapabilityStudioFeatureRehearsalService.TOOL_REF, asset.operator());

        OperatorMicroGraphRunner.Result result = new OperatorMicroGraphRunner(
                new TestRunService(registry, mapper, null)).execute(
                new OperatorMicroGraphRunner.Request(
                        CapabilityStudioFeatureRehearsalService.TOOL_REF,
                        asset.operator(),
                        TARGET_FINGERPRINT,
                        Map.of("orderId", "DEMO-ORDER-20260818-001", "caseId", CASE_ID),
                        service.toolFixture(CASE_ID, TARGET_FINGERPRINT),
                        "CAPABILITY_STUDIO_GOVERNED_SPIKE",
                        TestExecutionRequest.FixtureSource.STORED,
                        false,
                        Map.of("scenarioId", CASE_ID)));

        assertThat(result.execution().evidence().status()).isEqualTo(TestRunEvidence.Status.PASSED);
        assertThat(result.execution().graphResult().getOutput("subject", Map.class))
                .containsKey("cancellationDecision");
        assertThat(asset.realExternalCalls()).hasValue(0);
        assertThat(result.execution().evidence().nodeTrace())
                .extracting(TestRunEvidence.NodeTrace::graphPath)
                .contains("/root/subject/feature-cancellation-dispute-context");
        assertThat(result.execution().evidence().fixtureConsumptions())
                .allSatisfy(consumption -> assertThat(consumption.uses()).isEqualTo(1));
    }

    @Test
    void exposesAStableBindingSnapshotWithoutClaimingCertificationBeforeDependencyClosure() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        CapabilityStudioFeatureRehearsalService.RuntimeAsset asset = service(mapper, registry).runtimeAsset();
        registry.register(CapabilityStudioFeatureRehearsalService.TOOL_REF, asset.operator());

        OperatorExecutionTargetSnapshot snapshot = OperatorExecutionTargetSnapshot.capture(
                mapper,
                CapabilityStudioFeatureRehearsalService.TOOL_REF,
                registry,
                null);

        assertThat(snapshot.runtimeBindingStateFingerprint()).startsWith("sha256:");
        assertThat(snapshot.certificationEligible()).isFalse();
        assertThat(snapshot.certificationGaps())
                .contains("Binding has no formal operator composability manifest; hidden dependencies cannot be excluded.");
    }

    private static CapabilityStudioFeatureRehearsalService service(
            ObjectMapper mapper,
            DefaultOperatorRegistry registry) {
        return new CapabilityStudioFeatureRehearsalService(
                new CapabilityStudioGoldenDemoPackLoader().load(mapper), mapper, registry);
    }
}
