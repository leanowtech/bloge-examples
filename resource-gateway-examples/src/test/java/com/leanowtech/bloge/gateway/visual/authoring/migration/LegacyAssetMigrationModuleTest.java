package com.leanowtech.bloge.gateway.visual.authoring.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyAssetMigrationModuleTest {

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
                () -> Set.of("customer.get", "orders.list"), contracts, drafts, publications)
                .inventory(new AuthoringScope("tenant-a", "project-a", "test"));

        assertThat(inventory.summary()).isEqualTo(new LegacyAssetMigrationInventory.Summary(7, 3, 2, 2));
        assertThat(inventory.items()).extracting(LegacyAssetMigrationInventory.Item::sourceId)
                .contains("customer.get", "orders.list", "contract.only", data.draftId(), advanced.draftId(),
                        "publication-advanced")
                .doesNotContain("other-scope");
        assertThat(item(inventory, LegacyAssetMigrationInventory.Kind.API_RESOURCE, "customer.get").status())
                .isEqualTo(LegacyAssetMigrationInventory.Status.READY_TO_REAUTHOR);
        assertThat(item(inventory, LegacyAssetMigrationInventory.Kind.API_RESOURCE, "orders.list").reasonCodes())
                .containsExactly("DESIGN_CONTRACT_MISSING");
        assertThat(item(inventory, LegacyAssetMigrationInventory.Kind.REUSABLE_FLOW_DRAFT, advanced.draftId()).status())
                .isEqualTo(LegacyAssetMigrationInventory.Status.LEGACY_ONLY);
        assertThat(item(inventory, LegacyAssetMigrationInventory.Kind.FIXTURE_SET, data.draftId()).reasonCodes())
                .containsExactly("GOVERNED_REFERENCE_REVIEW_REQUIRED");

        String wire = new ObjectMapper().writeValueAsString(inventory);
        assertThat(wire).doesNotContain("customerName", "Ada", "fixture-a", "https://");
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
        return new ResourceDesignContract(null, id, name, "", List.of(), SchemaEnvelope.opaque(),
                SchemaEnvelope.opaque(), Map.of(), status);
    }

}
