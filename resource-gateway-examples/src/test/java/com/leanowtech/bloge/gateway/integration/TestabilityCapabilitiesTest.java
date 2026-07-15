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
                "storedFixtureBundle", "fixtureBundle", "effectiveExecutionPlan", "testRunEvidence",
                "testGraphTargetDescriptor");
        assertThat(enabled.endpoints()).anyMatch(endpoint -> endpoint.path().equals("/api/testing/executions"));
        assertThat(enabled.endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/graphs/{graphName}"));
    }
}
