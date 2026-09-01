package com.leanowtech.bloge.gateway.visual.authoring.migration;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import static com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory.Action;
import static com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory.ActionKind;
import static com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory.Item;
import static com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory.Kind;
import static com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory.Status;
import static com.leanowtech.bloge.gateway.visual.authoring.migration.LegacyAssetMigrationInventory.Summary;

/**
 * Projects existing authoring authorities into an explicit, read-only migration inventory.
 *
 * <p>The module never returns descriptor transport details, schemas, fixture values, governed material,
 * or credentials. A READY item is eligible for visible re-authoring only; this module performs no
 * mutation and never invents a Connection, revision, fingerprint, or missing contract.</p>
 */
public final class LegacyAssetMigrationModule {
    private final LegacyResourceDescriptorSource resources;
    private final ResourceDesignContractRegistry contracts;
    private final GraphDraftRepository drafts;
    private final VisualGraphPublicationRepository publications;

    public LegacyAssetMigrationModule(LegacyResourceDescriptorSource resources,
                                      ResourceDesignContractRegistry contracts,
                                      GraphDraftRepository drafts,
                                      VisualGraphPublicationRepository publications) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.publications = Objects.requireNonNull(publications, "publications");
    }

    /** Builds one stable inventory for the verified authoring scope without modifying legacy state. */
    public LegacyAssetMigrationInventory inventory(AuthoringScope scope) {
        Objects.requireNonNull(scope, "scope");
        List<Item> items = new ArrayList<>();
        resourceItems(items);
        drafts.all().stream().filter(draft -> inScope(scope, draft.tenantId(), draft.namespace(), draft.environment()))
                .forEach(draft -> draftItems(items, draft));
        publications.all().stream()
                .filter(value -> inScope(scope, value.tenantId(), value.namespace(), value.environment()))
                .forEach(value -> publicationItem(items, value));
        items.sort(Comparator.comparing(Item::kind).thenComparing(Item::sourceId)
                .thenComparingLong(Item::sourceRevision));
        long ready = count(items, Status.READY_TO_REAUTHOR);
        long repair = count(items, Status.NEEDS_REPAIR);
        long legacy = count(items, Status.LEGACY_ONLY);
        return new LegacyAssetMigrationInventory(null,
                new Summary(items.size(), Math.toIntExact(ready), Math.toIntExact(repair), Math.toIntExact(legacy)),
                items);
    }

    private void resourceItems(List<Item> items) {
        Map<String, ResourceDesignContract> contractById = new LinkedHashMap<>();
        contracts.all().forEach(value -> contractById.put(value.resourceId(), value));
        TreeSet<String> descriptorIds = new TreeSet<>(resources.resourceIds());
        TreeSet<String> ids = new TreeSet<>(descriptorIds);
        ids.addAll(contractById.keySet());
        for (String id : ids) {
            ResourceDesignContract contract = contractById.get(id);
            List<String> reasons = new ArrayList<>();
            if (!descriptorIds.contains(id)) reasons.add("DESCRIPTOR_MISSING");
            if (contract == null) reasons.add("DESIGN_CONTRACT_MISSING");
            if (contract != null && !ResourceDesignContract.STATUS_ACTIVE.equals(contract.status())) {
                reasons.add("DESIGN_CONTRACT_NOT_ACTIVE");
            }
            Status status = reasons.isEmpty() ? Status.READY_TO_REAUTHOR : Status.NEEDS_REPAIR;
            if (reasons.isEmpty()) reasons.add("CONNECTION_SELECTION_REQUIRED");
            String displayName = contract == null ? id : contract.displayName();
            Action action = status == Status.READY_TO_REAUTHOR
                    ? new Action(ActionKind.REAUTHOR_RESOURCE, "/workbench/?create=api")
                    : new Action(ActionKind.REPAIR_SOURCE, "/capabilities/");
            items.add(new Item(Kind.API_RESOURCE, id, 0, displayName, status, 0, reasons, action));
        }
    }

    private static void draftItems(List<Item> items, GraphDraft draft) {
        boolean advanced = hasAdvancedEdges(draft);
        Status status = advanced ? Status.LEGACY_ONLY : Status.READY_TO_REAUTHOR;
        List<String> reasons = List.of(advanced ? "ADVANCED_EDGE_UNSUPPORTED" : "EXPLICIT_REAUTHORING_REQUIRED");
        String path = legacyDraftPath(draft.draftId());
        items.add(new Item(Kind.REUSABLE_FLOW_DRAFT, draft.draftId(), draft.revision(), draft.graphName(), status,
                draft.nodeFixtures().size(), reasons, new Action(ActionKind.OPEN_LEGACY_FLOW, path)));
        if (!draft.nodeFixtures().isEmpty()) {
            boolean governed = draft.nodeFixtures().values().stream()
                    .anyMatch(value -> value.governedRef() != null);
            items.add(new Item(Kind.FIXTURE_SET, draft.draftId(), draft.revision(),
                    draft.graphName() + " fixtures", Status.READY_TO_REAUTHOR, draft.nodeFixtures().size(),
                    List.of(governed ? "GOVERNED_REFERENCE_REVIEW_REQUIRED" : "EXPLICIT_CASE_AUTHORING_REQUIRED"),
                    new Action(ActionKind.REAUTHOR_FIXTURE, path)));
        }
    }

    private static void publicationItem(List<Item> items, VisualGraphPublication publication) {
        boolean advanced = publication.draft() == null || hasAdvancedEdges(publication.draft());
        Status status = advanced ? Status.LEGACY_ONLY : Status.READY_TO_REAUTHOR;
        String draftId = publication.draftId();
        items.add(new Item(Kind.REUSABLE_FLOW_VERSION, publication.publicationId(), publication.draftRevision(),
                publication.graphName(), status, publication.draft() == null ? 0 : publication.draft().nodeFixtures().size(),
                List.of(advanced ? "ADVANCED_EDGE_UNSUPPORTED" : "EXPLICIT_REAUTHORING_REQUIRED"),
                new Action(ActionKind.OPEN_LEGACY_FLOW, legacyDraftPath(draftId))));
    }

    private static boolean hasAdvancedEdges(GraphDraft draft) {
        return draft.edges().stream().anyMatch(edge -> !"data".equals(edge.kind()));
    }

    private static boolean inScope(AuthoringScope scope, String tenant, String project, String environment) {
        return scope.tenantId().equals(tenant) && scope.projectId().equals(project)
                && scope.environmentId().equals(environment);
    }

    private static String legacyDraftPath(String draftId) {
        return "/author/?authorWorkspace=legacy&draftId="
                + URLEncoder.encode(draftId, StandardCharsets.UTF_8);
    }

    private static long count(List<Item> items, Status status) {
        return items.stream().filter(item -> item.status() == status).count();
    }
}
