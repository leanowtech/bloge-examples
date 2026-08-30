package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;

/** Committed API Resource head or historical revision, with its receipt. */
public record StoredApiResource(AuthoringScope scope, ApiResourceSpec resource,
                               ReadyApiResourceProjections projections,
                               CommandReceipt receipt) {
    /** Ensures the stored object and projections are exact and committed. */
    public StoredApiResource {
        if (scope == null || resource == null || projections == null || receipt == null
                || !resource.ref().equals(projections.subject())) throw new IllegalArgumentException("stored resource is inconsistent");
    }
}
