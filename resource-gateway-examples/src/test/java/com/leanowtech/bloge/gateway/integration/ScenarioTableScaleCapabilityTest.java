package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioBulkEditCommand;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioBulkEditResult;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioTablePage;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioTablePageQuery;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioTableScaleCapabilityTest {

    private static final IntegrationCapabilities.Endpoint QUERY =
            new IntegrationCapabilities.Endpoint("POST",
                    "/api/visual/scenario-draft-sets/{scenarioDraftSetId}/matrix/query");
    private static final IntegrationCapabilities.Endpoint EDIT =
            new IntegrationCapabilities.Endpoint("POST",
                    "/api/visual/scenario-draft-sets/{scenarioDraftSetId}/matrix/bulk-edits");

    @Test
    void advertisesPortableScaleProtocolsSeparatelyFromNonProductionEndpoints() {
        IntegrationCapabilities disabled = IntegrationCapabilities.current();
        IntegrationCapabilities enabled = IntegrationCapabilities.current(
                VisualEvidenceSigner.unavailable().descriptor(),
                IntegrationIdentityResolver.unavailable().descriptor(),
                false, null, true);

        assertThat(disabled.supportedObjects())
                .containsEntry("scenarioTablePageQuery", List.of(ScenarioTablePageQuery.SCHEMA_VERSION))
                .containsEntry("scenarioTablePage", List.of(ScenarioTablePage.SCHEMA_VERSION))
                .containsEntry("scenarioBulkEditCommand", List.of(ScenarioBulkEditCommand.SCHEMA_VERSION))
                .containsEntry("scenarioBulkEditResult", List.of(ScenarioBulkEditResult.SCHEMA_VERSION));
        assertThat(disabled.features())
                .containsEntry("scenarioTableScaleProtocol", true)
                .containsEntry("scenarioTableScaleApi", false);
        assertThat(disabled.endpoints()).doesNotContain(QUERY, EDIT);
        assertThat(enabled.features()).containsEntry("scenarioTableScaleApi", true);
        assertThat(enabled.endpoints()).contains(QUERY, EDIT);
    }
}
