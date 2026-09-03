package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    @Test
    void discoversRuntimeResourceBindingAndItsPortContract() {
        OperatorDefinition resource = runtimeResource("resource:wallet-service.getBalance");
        VisualOperatorCatalog catalog = new VisualOperatorCatalog() {
            @Override
            public List<OperatorDefinition> list(OperatorCatalogQuery query) {
                return query.resourceOnly() ? List.of(resource) : List.of();
            }

            @Override
            public Optional<OperatorDefinition> find(String operatorRef) {
                return resource.operatorRef().equals(operatorRef) ? Optional.of(resource) : Optional.empty();
            }
        };
        ResourceGatewayAgentTddTools tools = new ResourceGatewayAgentTddTools(
                new InMemoryOperatorLibraryRegistry(), new InMemoryGraphDraftRepository(), mapper,
                null, null, null, null, null, catalog);

        JsonNode listed = mapper.valueToTree(tools.invoke(
                "rg.capability.list", mapper.valueToTree(Map.of("kind", "API")), identity()));
        JsonNode capability = listed.path("data").path("capabilities").get(0);
        JsonNode contract = mapper.valueToTree(tools.invoke(
                "rg.contract.get", mapper.valueToTree(Map.of("assetRef", resource.operatorRef())), identity()));

        assertThat(capability.path("ref").asText()).isEqualTo(resource.operatorRef());
        assertThat(capability.path("bindingRef").asText()).isEqualTo(resource.operatorRef());
        assertThat(capability.path("kind").asText()).isEqualTo("API");
        assertThat(capability.path("inputPorts").get(0).asText()).isEqualTo("params");
        assertThat(capability.path("outputPorts").get(0).asText()).isEqualTo("payload");
        assertThat(capability.path("effect").asText()).isEqualTo("READ_EXTERNAL");
        assertThat(contract.path("ok").asBoolean()).isTrue();
        assertThat(contract.path("data").path("inputs").get(0).path("name").asText()).isEqualTo("params");
        assertThat(contract.path("data").path("outputs").get(0).path("name").asText()).isEqualTo("payload");
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

    private static OperatorDefinition runtimeResource(String ref) {
        return new OperatorDefinition(
                "bloge.visualOperator.v1", ref, "1.0.0",
                new OperatorDefinition.Display("Wallet balance", "", List.of("wallet")),
                new OperatorDefinition.Source("resource-descriptor", "wallet-service.getBalance",
                        "GET", "/wallet/{userId}/balance", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("params", SchemaEnvelope.opaque(), true, "")),
                        List.of(new OperatorDefinition.Port("payload", SchemaEnvelope.opaque(), true, ""))),
                SchemaEnvelope.opaque(),
                new OperatorDefinition.Capabilities("READ_EXTERNAL", "IDEMPOTENT", false, false, false),
                new OperatorDefinition.Lowering("resource-descriptor", "httpResource",
                        Map.of("resourceId", "wallet-service.getBalance")),
                List.of());
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD", "agent-1",
                "", "AGENT_TDD_READ", "corr-1");
    }
}
