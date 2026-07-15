package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestabilityCapabilitiesTest {

    @Test
    void executionEndpointIsAdvertisedOnlyWhenProfileMarkerEnablesIt() {
        IntegrationCapabilities disabled = IntegrationCapabilities.current();
        IntegrationCapabilities enabled = IntegrationCapabilities.current(
                VisualEvidenceSigner.unavailable().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(), false, null, true);

        assertThat(disabled.testability().executionEndpointEnabled()).isFalse();
        assertThat(disabled.endpoints()).noneMatch(endpoint -> endpoint.path().startsWith("/api/testing/"));
        assertThat(enabled.testability().executionEndpointEnabled()).isTrue();
        assertThat(enabled.supportedObjects()).containsKeys("testExecutionRequest", "testExecutionResponse",
                "testExecutionBatchRequest", "testExecutionBatchResponse", "fixtureBundleRegistrationRequest",
                "storedFixtureBundle", "testSuite", "testSuiteRegistrationRequest", "storedTestSuite",
                "testSuiteExecutionRequest", "testSuiteExecutionResponse", "testSuiteRunEvidence",
                "testSuiteCatalogMaterialization",
                "fixtureBundle", "effectiveExecutionPlan", "testRunEvidence",
                "testGraphTargetDescriptor", "testOperatorExecutionRequest", "testOperatorTargetDescriptor");
        assertThat(enabled.features()).containsEntry("operatorMicroGraphExecution", true)
                .containsEntry("immutableTestSuiteRegistry", true)
                .containsEntry("immutableTestSuiteExecution", true)
                .containsEntry("suiteSemanticCoverageVerdict", true)
                .containsEntry("suitePromotionEligibilityVerdict", true)
                .containsEntry("builtInGraphSuiteCatalogMaterialization", true)
                .containsEntry("streamingOperatorTestExecution", false)
                .containsEntry("suspendableOperatorTestExecution", false);
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.path().equals("/api/testing/executions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/graphs/{graphName}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/operators/{operatorRef}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/operators/{operatorRef}/executions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("PUT") && endpoint.path().equals("/api/testing/suites/{suiteId}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.method().equals("GET") && endpoint.path().equals("/api/testing/suites/{suiteId}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("POST")
                && endpoint.path().equals("/api/testing/suites/{suiteId}/executions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("GET")
                && endpoint.path().equals("/api/testing/suite-executions/{suiteRunId}"));
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.method().equals("PUT")
                && endpoint.path().equals("/api/testing/catalogs/gateway-graph-contract-v1"));
    }
}
