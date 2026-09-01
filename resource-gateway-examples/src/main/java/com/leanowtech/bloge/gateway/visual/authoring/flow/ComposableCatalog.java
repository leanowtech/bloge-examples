package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Read seam for resolving exact API Resource or published Flow dependencies. */
@FunctionalInterface
public interface ComposableCatalog {
    enum Kind { API_RESOURCE, FLOW_VERSION }

    /** Returns only an exact readable dependency in the trusted scope. */
    Optional<ComposableDefinition> resolve(AuthoringScope scope,
                                           ReusableFlowCommand.ComposableRef reference);

    /** Lists a bounded, payload-free selection snapshot; exact references remain immutable. */
    default List<ComposableCatalogItem> list(AuthoringScope scope, Set<Kind> kinds, int limit) {
        return List.of();
    }
}
