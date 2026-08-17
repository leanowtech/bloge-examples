package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.OperatorExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorMicroGraphRunner;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void timeoutCaseRecordsTimeoutAttemptThenRunsDecisionThroughBlogeFallbackWithoutDelegateEgress() {
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
                        Map.of("orderId", "DEMO-ORDER-20260818-001",
                                "caseId", "case-compensation-history-timeout"),
                        service.toolFixture("case-compensation-history-timeout", TARGET_FINGERPRINT),
                        "CAPABILITY_STUDIO_GOVERNED_SPIKE",
                        TestExecutionRequest.FixtureSource.STORED,
                        false,
                        Map.of("scenarioId", "case-compensation-history-timeout")));

        TestRunEvidence evidence = result.execution().evidence();
        assertThat(evidence.status()).as(
                "timeout evidence: status=%s, nodes=%s, output=%s, diagnostics=%s",
                evidence.status(), evidence.nodeTrace(),
                result.execution().graphResult().getOutput("subject", Map.class),
                evidence.diagnostics()).isEqualTo(TestRunEvidence.Status.PASSED);
        Map<?, ?> featureOutput = result.execution().graphResult()
                .getOutput("subject", Map.class);
        assertThat(featureOutput.containsKey("cancellationDecision")).isTrue();
        Map<?, ?> decision = (Map<?, ?>) featureOutput.get("cancellationDecision");
        assertThat(decision.get("action")).isEqualTo("MANUAL_REVIEW");
        assertThat(decision.get("informationGap"))
                .isEqualTo("COMPENSATION_HISTORY_TIMEOUT");
        assertThat(evidence.nodeTrace())
                .filteredOn(node -> node.nodeId().equals("compensationHistoryLookup"))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.status()).isEqualTo("MOCKED");
                    assertThat(node.attempts()).anySatisfy(attempt -> {
                        assertThat(attempt.status()).isEqualTo("TIMEOUT");
                        assertThat(attempt.errorCode()).isEqualTo("COMPENSATION_HISTORY_TIMEOUT");
                    });
                });
        assertThat(evidence.nodeTrace())
                .filteredOn(node -> node.nodeId().equals("aggregateCancellationContext")
                        || node.nodeId().equals("cancellationDecision"))
                .extracting(TestRunEvidence.NodeTrace::status)
                .containsOnly("SUCCESS");
        assertThat(asset.realExternalCalls()).hasValue(0);
        assertThat(evidence.fixtureConsumptions()).allSatisfy(consumption ->
                assertThat(consumption.uses()).isEqualTo(1));
    }

    @Test
    void exposesTheExactDeclaredDependencyManifestAndStableConditionalCertification() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        CapabilityStudioFeatureRehearsalService.RuntimeAsset asset = service(mapper, registry).runtimeAsset();
        registry.register(CapabilityStudioFeatureRehearsalService.TOOL_REF, asset.operator());

        OperatorExecutionTargetSnapshot snapshot = OperatorExecutionTargetSnapshot.capture(
                mapper,
                CapabilityStudioFeatureRehearsalService.TOOL_REF,
                registry,
                null);
        OperatorExecutionTargetSnapshot repeated = OperatorExecutionTargetSnapshot.capture(
                mapper,
                CapabilityStudioFeatureRehearsalService.TOOL_REF,
                registry,
                null);

        assertThat(snapshot.runtimeBindingStateFingerprint()).startsWith("sha256:");
        assertThat(snapshot.dependencyPolicy()).isEqualTo("DECLARED");
        assertThat(snapshot.composabilityManifest()).containsEntry("dependencyMode", "DECLARED");
        List<?> dependencyEntries = (List<?>) snapshot.composabilityManifest().get("dependencies");
        List<String> dependencyRefs = dependencyEntries.stream()
                .map(dependency -> String.valueOf(((Map<?, ?>) dependency).get("ref")))
                .toList();
        assertThat(dependencyRefs).containsExactlyInAnyOrder(
                        "api-order-lookup",
                        "api-cancellation-responsibility",
                        "api-city-pricing-policy",
                        "api-compensation-history");
        assertThat(dependencyEntries).allSatisfy(dependency -> {
            Map<?, ?> entry = (Map<?, ?>) dependency;
            assertThat(entry.get("kind")).isEqualTo("RESOURCE");
            assertThat(entry.get("controlBoundary")).isEqualTo("RESOURCE_BINDING");
        });
        assertThat(snapshot.composabilityManifest())
                .containsEntry("conformanceSuiteRef",
                        "capability-studio:feature-cancellation-dispute-context")
                .containsEntry("conformanceFingerprint", asset.graphFingerprint());
        assertThat(snapshot.certificationEligible()).isTrue();
        assertThat(snapshot.certificationGaps()).isEmpty();
        assertThat(snapshot.certificationRequirements())
                .hasSize(8)
                .allMatch(requirement -> requirement.contains("declared dependency ref"));
        assertThat(repeated.composabilityFingerprint()).isEqualTo(snapshot.composabilityFingerprint());
        assertThat(repeated.fingerprint()).isEqualTo(snapshot.fingerprint());
    }

    private static CapabilityStudioFeatureRehearsalService service(
            ObjectMapper mapper,
            DefaultOperatorRegistry registry) {
        return new CapabilityStudioFeatureRehearsalService(
                new CapabilityStudioGoldenDemoPackLoader().load(mapper), mapper, registry);
    }
}
