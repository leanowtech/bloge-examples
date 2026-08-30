package com.leanowtech.bloge.gateway.visual.authoring.resource;

import java.util.Optional;

/** Stateless decision seam for validating and producing an API Resource revision. */
public final class ApiResourceDecisions {
    /** Creates the stateless decision dependency used by persistence adapters. */
    public ApiResourceDecisions() { }

    /**
     * Applies validation, fingerprinting and the optimistic-concurrency rule to one command.
     * The current value is never mutated.
     *
     * @param current current scoped head, if any
     * @param resourceId resource identifier
     * @param connectionId exact connection identifier
     * @param command resource command
     * @param expected expected create or revision match
     * @return the next immutable resource revision
     */
    public static ApiResourceSpec next(Optional<ApiResourceSpec> current, String resourceId,
                                       String connectionId, ApiResourceCommand command,
                                       ExpectedRevision expected) {
        return InMemoryApiResourceModule.decide(current, resourceId, connectionId, command, expected);
    }
}
