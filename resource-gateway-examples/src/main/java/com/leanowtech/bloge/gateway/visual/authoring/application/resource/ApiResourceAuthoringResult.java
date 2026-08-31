package com.leanowtech.bloge.gateway.visual.authoring.application.resource;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StoredApiResource;

/** Exact committed Resource authority and receipt returned by one save. */
public record ApiResourceAuthoringResult(StoredApiResource stored, boolean replayed) {
    /** Rejects incomplete application results. */
    public ApiResourceAuthoringResult {
        if (stored == null) throw new IllegalArgumentException("stored Resource is required");
    }
}
