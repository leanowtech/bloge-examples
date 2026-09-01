package com.leanowtech.bloge.gateway.visual.authoring.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyAssetMigrationModuleTest {

    @Test
    void previewsOneSafeLegacyResourceWithoutTransportOrCredentialFields() throws Exception {
        InMemoryResourceDesignContractRegistry contracts = new InMemoryResourceDesignContractRegistry();
        contracts.upsert(new ResourceDesignContract(null, "customer.get", "Get customer", "Reads one customer",
                List.of(), SchemaEnvelope.object(Map.of("customerId", Map.of(
                        "type", "string", "description", "Customer id")), List.of("customerId")),
                SchemaEnvelope.object(Map.of("name", Map.of(
                        "type", "string", "description", "Customer name")), List.of("name")),
                Map.of(), ResourceDesignContract.STATUS_ACTIVE));
        LegacyResourceDescriptorSource descriptors = source(new LegacyResourceDescriptorSource.Descriptor(
                "customer.get", "GET", "/customers/{customerId}",
                new VisualResourceParameterMapping(Map.of("customerId", "ctx.params.customerId"),
                        Map.of(), Map.of(), null),
                new VisualResourceResponseProtocol.BodyCode("code", Set.of(0), "message"), "data"));
        LegacyAssetMigrationModule module = new LegacyAssetMigrationModule(descriptors, contracts,
                new InMemoryGraphDraftRepository(), new InMemoryVisualGraphPublicationRepository());

        LegacyApiResourceReauthorPreview preview = module.previewResource("customer.get");

        assertThat(preview.source()).isEqualTo(new LegacyApiResourceReauthorPreview.Source(
                "API_RESOURCE", "customer.get", 0));
        assertThat(preview.suggestedResource().operation()).isEqualTo(new ApiResourceCommand.Operation(
                "GET", "/customers/{customerId}", List.of(new ApiResourceCommand.Binding(
                        "$.customerId", new ApiResourceCommand.Location("PATH", "customerId")))));
        assertThat(preview.suggestedResource().response()).isEqualTo(new ApiResourceCommand.Response(
                new ApiResourceCommand.BodyMatch("$.code", List.of(new ObjectMapper().valueToTree(0))), "$.data"));
        assertThat(preview.suggestedResource().effect()).isEqualTo(ApiResourceCommand.Effect.readOnly());
        assertThat(preview.suggestedResource().examples()).extracting(ApiResourceCommand.Example::name)
                .containsExactly("legacy-example");
        assertThat(preview.diagnostics()).extracting(LegacyApiResourceReauthorPreview.Diagnostic::code)
                .containsExactly("CONNECTION_SELECTION_REQUIRED", "LEGACY_SCHEMA_SIMPLIFIED");

        String wire = new ObjectMapper().writeValueAsString(preview);
        assertThat(wire).doesNotContain("https://", "defaultHeaders", "authStrategy", "credential", "token");
    }

    @Test
    void rejectsLegacyShapesThatCannotBeReauthoredWithoutChangingTheirMeaning() {
        InMemoryResourceDesignContractRegistry contracts = new InMemoryResourceDesignContractRegistry();
        contracts.upsert(contract("unsafe.get", "Unsafe", ResourceDesignContract.STATUS_ACTIVE));
        LegacyResourceDescriptorSource descriptors = source(new LegacyResourceDescriptorSource.Descriptor(
                "unsafe.get", "GET", "/unsafe", new VisualResourceParameterMapping(
                Map.of(), Map.of("id", "ctx.params.id ?? ctx.params.fallback"), Map.of(), null),
                new VisualResourceResponseProtocol.HttpStatus(), null));
        LegacyAssetMigrationModule module = new LegacyAssetMigrationModule(descriptors, contracts,
                new InMemoryGraphDraftRepository(), new InMemoryVisualGraphPublicationRepository());

        assertThatThrownBy(() -> module.previewResource("unsafe.get"))
                .isInstanceOf(LegacyAssetMigrationFailure.class)
                .extracting("code").isEqualTo(LegacyAssetMigrationFailure.Code.NEEDS_REPAIR);

        LegacyAssetMigrationInventory.Item item = item(module.inventory(
                new AuthoringScope("tenant-a", "project-a", "test")),
                LegacyAssetMigrationInventory.Kind.API_RESOURCE, "unsafe.get");
        assertThat(item.status()).isEqualTo(LegacyAssetMigrationInventory.Status.NEEDS_REPAIR);
        assertThat(item.reasonCodes()).containsExactly("UNSAFE_LEGACY_RESOURCE_SHAPE");
        assertThat(item.action().kind()).isEqualTo(LegacyAssetMigrationInventory.ActionKind.REPAIR_SOURCE);
    }

    @Test
    void previewWireAuthorityRejectsInvalidCoordinatesAndDiagnostics() {
        assertThatThrownBy(() -> new LegacyApiResourceReauthorPreview.Source(
                "API_RESOURCE", "invalid resource", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegacyApiResourceReauthorPreview(
                "bloge.legacyApiResourceReauthorPreview.v0",
                new LegacyApiResourceReauthorPreview.Source("API_RESOURCE", "customer.get", 0),
                command(), List.of(new LegacyApiResourceReauthorPreview.Diagnostic("REVIEW", "Review it."))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegacyApiResourceReauthorPreview(
                null, new LegacyApiResourceReauthorPreview.Source("API_RESOURCE", "customer.get", 0),
                command(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inventoriesPairsOrphansScopedFlowsAndFixturesWithoutReturningLegacyPayloads() throws Exception {
        InMemoryResourceDesignContractRegistry contracts = new InMemoryResourceDesignContractRegistry();
        contracts.upsert(contract("customer.get", "Get customer", ResourceDesignContract.STATUS_ACTIVE));
        contracts.upsert(contract("contract.only", "Contract only", ResourceDesignContract.STATUS_ACTIVE));

        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        GraphDraft data = drafts.save(draft("data-flow", "tenant-a", "project-a", "test", "data",
                Map.of("customer", new GraphDraft.NodeFixture(Map.of("customerName", "Ada"),
                        null, new GraphDraft.GovernedFixtureRef("fixture-a", 2, "sha256:" + "a".repeat(64)),
                        GraphDraft.NodeFixture.ResourceFidelity.OUTPUT_LEVEL))));
        GraphDraft advanced = drafts.save(draft("advanced-flow", "tenant-a", "project-a", "test", "route", Map.of()));
        drafts.save(draft("other-scope", "tenant-b", "project-a", "test", "data", Map.of()));

        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        publications.create(VisualGraphPublication.design(advanced, List.of(), null, null)
                .withIdentity("publication-advanced", Instant.parse("2026-09-01T00:00:00Z")));

        LegacyAssetMigrationInventory inventory = new LegacyAssetMigrationModule(
                source(new LegacyResourceDescriptorSource.Descriptor("customer.get", "GET", "/customers",
                                VisualResourceParameterMapping.empty(), new VisualResourceResponseProtocol.HttpStatus(), null),
                        new LegacyResourceDescriptorSource.Descriptor("orders.list", "GET", "/orders",
                                VisualResourceParameterMapping.empty(), new VisualResourceResponseProtocol.HttpStatus(), null)),
                contracts, drafts, publications)
                .inventory(new AuthoringScope("tenant-a", "project-a", "test"));

        assertThat(inventory.summary()).isEqualTo(new LegacyAssetMigrationInventory.Summary(7, 3, 2, 2));
        assertThat(inventory.items()).extracting(LegacyAssetMigrationInventory.Item::sourceId)
                .contains("customer.get", "orders.list", "contract.only", data.draftId(), advanced.draftId(),
                        "publication-advanced")
                .doesNotContain("other-scope");
        assertThat(item(inventory, LegacyAssetMigrationInventory.Kind.API_RESOURCE, "customer.get").status())
                .isEqualTo(LegacyAssetMigrationInventory.Status.READY_TO_REAUTHOR);
        assertThat(item(inventory, LegacyAssetMigrationInventory.Kind.API_RESOURCE, "customer.get").action().path())
                .isEqualTo("/workbench/?create=api&legacyResourceId=customer.get");
        assertThat(item(inventory, LegacyAssetMigrationInventory.Kind.API_RESOURCE, "orders.list").reasonCodes())
                .containsExactly("DESIGN_CONTRACT_MISSING");
        assertThat(item(inventory, LegacyAssetMigrationInventory.Kind.REUSABLE_FLOW_DRAFT, advanced.draftId()).status())
                .isEqualTo(LegacyAssetMigrationInventory.Status.LEGACY_ONLY);
        assertThat(item(inventory, LegacyAssetMigrationInventory.Kind.FIXTURE_SET, data.draftId()).reasonCodes())
                .containsExactly("GOVERNED_REFERENCE_REVIEW_REQUIRED");

        String wire = new ObjectMapper().writeValueAsString(inventory);
        assertThat(wire).doesNotContain("customerName", "Ada", "fixture-a", "https://");
    }

    private static LegacyResourceDescriptorSource source(LegacyResourceDescriptorSource.Descriptor... values) {
        Map<String, LegacyResourceDescriptorSource.Descriptor> descriptors = new java.util.LinkedHashMap<>();
        for (LegacyResourceDescriptorSource.Descriptor value : values) descriptors.put(value.resourceId(), value);
        return new LegacyResourceDescriptorSource() {
            @Override public Set<String> resourceIds() { return Set.copyOf(descriptors.keySet()); }
            @Override public java.util.Optional<Descriptor> find(String resourceId) {
                return java.util.Optional.ofNullable(descriptors.get(resourceId));
            }
        };
    }

    private static LegacyAssetMigrationInventory.Item item(
            LegacyAssetMigrationInventory inventory, LegacyAssetMigrationInventory.Kind kind, String sourceId) {
        return inventory.items().stream().filter(value -> value.kind() == kind && value.sourceId().equals(sourceId))
                .findFirst().orElseThrow();
    }

    private static GraphDraft draft(String name, String tenant, String project, String environment,
                                    String edgeKind, Map<String, GraphDraft.NodeFixture> fixtures) {
        GraphDraft.DraftEdge edge = new GraphDraft.DraftEdge("a-b", edgeKind,
                new GraphDraft.Endpoint("a", "out", ""), new GraphDraft.Endpoint("b", "in", ""));
        return new GraphDraft(null, name, 0, name, tenant, project, environment, null,
                SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(), List.of(edge), Map.of(), fixtures,
                new GraphDraft.OutputSelection("b", ""), Map.of(), Map.of(), GraphDraft.RevisionMetadata.empty());
    }

    private static ResourceDesignContract contract(String id, String name, String status) {
        return new ResourceDesignContract(null, id, name, "", List.of(),
                SchemaEnvelope.object(Map.of(), List.of()), SchemaEnvelope.object(Map.of(), List.of()),
                Map.of(), status);
    }

    private static ApiResourceCommand command() {
        SchemaEnvelope schema = SchemaEnvelope.object(Map.of(), List.of());
        return new ApiResourceCommand("Customer", null,
                new ApiResourceCommand.Operation("GET", "/customers", List.of()),
                new ApiResourceCommand.Contract(schema, schema),
                new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                ApiResourceCommand.Effect.readOnly(), List.of(new ApiResourceCommand.Example(
                "legacy-example", new ObjectMapper().createObjectNode(),
                new ObjectMapper().createObjectNode())));
    }

}
