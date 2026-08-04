package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.authoring.scenario.TableSuiteRunBatch;
import com.leanowtech.bloge.gateway.authoring.scenario.TableSuiteRunCommand;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableSuiteRunCapabilityTest {

    @Test
    void advertisesPortableProtocolsSeparatelyFromNonProductionRuntimeEndpoints() {
        IntegrationCapabilities disabled = IntegrationCapabilities.current();
        IntegrationCapabilities enabled = IntegrationCapabilities.current(
                VisualEvidenceSigner.unavailable().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(),
                false, null, true);

        assertThat(disabled.supportedObjects())
                .containsEntry("tableSuiteRunCommand", List.of(TableSuiteRunCommand.SCHEMA_VERSION))
                .containsEntry("tableSuiteRunBatch", List.of(TableSuiteRunBatch.SCHEMA_VERSION))
                .containsEntry("tableSuiteRunDelta", List.of(TableSuiteRunBatch.Delta.SCHEMA_VERSION));
        assertThat(disabled.features())
                .containsEntry("tableSuiteRunProtocol", true)
                .containsEntry("tableSuiteRunApi", false);
        assertThat(disabled.endpoints()).doesNotContain(
                new IntegrationCapabilities.Endpoint("POST", "/api/visual/table-suite-runs"));
        assertThat(enabled.features()).containsEntry("tableSuiteRunApi", true);
        assertThat(enabled.endpoints()).contains(
                new IntegrationCapabilities.Endpoint("POST", "/api/visual/table-suite-runs"),
                new IntegrationCapabilities.Endpoint("GET", "/api/visual/table-suite-runs/{batchId}"),
                new IntegrationCapabilities.Endpoint("GET", "/api/visual/table-suite-runs/{batchId}/events"),
                new IntegrationCapabilities.Endpoint("POST", "/api/visual/table-suite-runs/{batchId}/cancel"),
                new IntegrationCapabilities.Endpoint("POST", "/api/visual/table-suite-runs/{batchId}/retry-failed"));
    }
}
