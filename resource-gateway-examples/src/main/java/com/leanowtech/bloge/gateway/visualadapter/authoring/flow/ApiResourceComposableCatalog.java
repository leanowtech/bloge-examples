package com.leanowtech.bloge.gateway.visualadapter.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableCatalog;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableCatalogItem;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableDefinition;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StoredApiResource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Resolves exact reusable Flow dependencies from committed API Resource authority. */
public final class ApiResourceComposableCatalog implements ComposableCatalog {
    private final ApiResourceCommitStore resources;
    private final ReusableFlowPublicationStore flows;

    public ApiResourceComposableCatalog(ApiResourceCommitStore resources) {
        this(resources, null);
    }

    public ApiResourceComposableCatalog(ApiResourceCommitStore resources,
                                        ReusableFlowPublicationStore flows) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.flows = flows;
    }

    @Override public Optional<ComposableDefinition> resolve(
            AuthoringScope scope, ReusableFlowCommand.ComposableRef reference) {
        if (reference instanceof ReusableFlowCommand.ComposableRef.FlowVersion flow) {
            if (flows == null) return Optional.empty();
            Optional<ReusableFlowVersion> stored = flows.findVersion(
                    scope, flow.publicationId(), flow.revision());
            if (stored.isEmpty() || !stored.get().subject().equals(
                    new com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef.FlowVersion(
                            flow.publicationId(), flow.revision(), flow.fingerprint()))) {
                return Optional.empty();
            }
            return Optional.of(new ComposableDefinition(reference,
                    stored.get().contract().input(), stored.get().contract().output()));
        }
        if (!(reference instanceof ReusableFlowCommand.ComposableRef.ApiResource resource)) return Optional.empty();
        Optional<StoredApiResource> stored = resources.findRevision(
                scope, resource.resourceId(), resource.revision());
        if (stored.isEmpty()
                || !stored.get().resource().resourceId().equals(resource.resourceId())
                || stored.get().resource().revision() != resource.revision()
                || !stored.get().resource().fingerprint().equals(resource.fingerprint())) {
            return Optional.empty();
        }
        return Optional.of(new ComposableDefinition(reference,
                stored.get().resource().contract().input(),
                stored.get().resource().contract().output()));
    }

    @Override public List<ComposableCatalogItem> list(
            AuthoringScope scope, Set<Kind> kinds, int limit) {
        if (scope == null || kinds == null || kinds.isEmpty() || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("catalog query is invalid");
        }
        ArrayList<ComposableCatalogItem> items = new ArrayList<>();
        if (kinds.contains(Kind.API_RESOURCE)) {
            resources.listHeads(scope, limit).forEach(stored -> items.add(new ComposableCatalogItem(
                    ComposableCatalogItem.SCHEMA_VERSION, stored.resource().displayName(),
                    new ReusableFlowCommand.ComposableRef.ApiResource(stored.resource().resourceId(),
                            Math.toIntExact(stored.resource().revision()), stored.resource().fingerprint()),
                    new ReusableFlowCommand.Contract(stored.resource().contract().input(),
                            stored.resource().contract().output()))));
        }
        if (kinds.contains(Kind.FLOW_VERSION) && flows != null) {
            flows.listLatestVersions(scope, limit).forEach(version -> items.add(new ComposableCatalogItem(
                    ComposableCatalogItem.SCHEMA_VERSION, version.displayName(),
                    new ReusableFlowCommand.ComposableRef.FlowVersion(version.publicationId(),
                            version.revision(), version.fingerprint()), version.contract())));
        }
        return items.stream().sorted(Comparator
                        .comparing((ComposableCatalogItem item) -> item.reference() instanceof
                                ReusableFlowCommand.ComposableRef.ApiResource ? 0 : 1)
                        .thenComparing(ComposableCatalogItem::displayName)
                        .thenComparing(item -> item.reference().id()))
                .limit(limit).toList();
    }
}
