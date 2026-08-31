package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.Optional;

/** Read seam for resolving exact API Resource or published Flow dependencies. */
@FunctionalInterface
public interface ComposableCatalog {
    /** Returns only an exact readable dependency in the trusted scope. */
    Optional<ComposableDefinition> resolve(AuthoringScope scope,
                                           ReusableFlowCommand.ComposableRef reference);
}
