package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies READ tools against the existing RG catalog and draft authorities. */
class ResourceGatewayAgentTddToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void listsLibraryContractsWithServerDerivedSpeccingState() {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(new OperatorLibrary("", "ride", "Ride", "0.1.0", "cx", "ACTIVE",
                List.of(designOperator("ride:lookup"))));
        ResourceGatewayAgentTddTools tools = new ResourceGatewayAgentTddTools(
                libraries, new InMemoryGraphDraftRepository(), mapper);

        JsonNode response = mapper.valueToTree(tools.invoke(
                "rg.library.list", mapper.createObjectNode(), identity()));

        assertThat(response.path("ok").asBoolean()).isTrue();
        assertThat(response.path("data").path("libraries")).hasSize(1);
        assertThat(response.path("data").path("libraries").get(0).path("libraryId").asText())
                .isEqualTo("ride");
        assertThat(response.path("data").path("libraries").get(0).path("speccing").asBoolean())
                .isTrue();
    }

    @Test
    void returnsStablePayloadFreeNotFoundEnvelope() {
        ResourceGatewayAgentTddTools tools = new ResourceGatewayAgentTddTools(
                new InMemoryOperatorLibraryRegistry(), new InMemoryGraphDraftRepository(), mapper);

        JsonNode response = mapper.valueToTree(tools.invoke(
                "rg.library.get", mapper.valueToTree(Map.of("libraryId", "missing")), identity()));

        assertThat(response.path("ok").asBoolean()).isFalse();
        assertThat(response.path("error").path("code").asText()).isEqualTo("LIBRARY_NOT_FOUND");
        assertThat(response.toString()).doesNotContain("payload").doesNotContain("credential");
    }

    private static OperatorDefinition designOperator(String ref) {
        return new OperatorDefinition(
                "bloge.visualOperator.v1", ref, "1.0.0",
                new OperatorDefinition.Display(ref, "", List.of()),
                new OperatorDefinition.Source("user-library", "", "", "", false, "ride"),
                new OperatorDefinition.Ports(List.of(), List.of()),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("design", "", Map.of()),
                List.of());
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD", "agent-1",
                "", "AGENT_TDD_READ", "corr-1");
    }
}
