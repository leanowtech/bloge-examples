package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioImportMaterializationRequest;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioImportMaterializationResult;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioImportCapabilityTest {

    private static final IntegrationCapabilities.Endpoint ENDPOINT =
            new IntegrationCapabilities.Endpoint(
                    "POST", "/api/visual/scenario-imports/materialize");

    @Test
    void separatesThePortableProtocolFromNonProductionApiReadiness() {
        IntegrationCapabilities disabled = IntegrationCapabilities.current();
        IntegrationCapabilities enabled = IntegrationCapabilities.current(
                VisualEvidenceSigner.unavailable().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(),
                false,
                null,
                true);

        assertThat(disabled.supportedObjects())
                .containsEntry("scenarioImportMaterializationRequest",
                        List.of(ScenarioImportMaterializationRequest.SCHEMA_VERSION))
                .containsEntry("scenarioImportMaterializationPlan",
                        List.of("bloge.scenarioMaterializationPlan.v1"))
                .containsEntry("scenarioImportMaterializationResult",
                        List.of(ScenarioImportMaterializationResult.SCHEMA_VERSION))
                .containsEntry("scenarioImportMaterializationReceipt",
                        List.of("bloge.scenarioMaterializationReceipt.v1"));
        assertThat(disabled.features())
                .containsEntry("scenarioImportMaterializationProtocol", true)
                .containsEntry("scenarioImportMaterializationApi", false);
        assertThat(disabled.endpoints()).doesNotContain(ENDPOINT);
        assertThat(enabled.features()).containsEntry("scenarioImportMaterializationApi", true);
        assertThat(enabled.endpoints()).contains(ENDPOINT);
    }
}
