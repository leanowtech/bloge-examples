package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;

/** Invisibility boundary between stage and commit. */
public record StagedApiResource(CommandLease lease, ApiResourceSpec resource,
                                ReadyApiResourceProjections projections) {
    /** Validates that stage content is bound to its command lease. */
    public StagedApiResource {
        if (lease == null || resource == null || projections == null || !resource.ref().equals(projections.subject())) {
            throw new IllegalArgumentException("staging subject is inconsistent");
        }
    }
}
