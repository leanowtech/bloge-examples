package com.leanowtech.bloge.gateway.visual.authoring.resource;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory authoritative adapter backed by {@link ApiResourceDecisions}. */
public final class InMemoryApiResourceModule implements ApiResourceModule {
    private final ApiResourceDecisions decisions;
    private final Map<String, ApiResourceSpec> resources = new ConcurrentHashMap<>();

    /** Creates an adapter with a fresh decision engine. */
    public InMemoryApiResourceModule() { this(new ApiResourceDecisions()); }

    /** @param mapper mapper used for canonical content fingerprints */
    public InMemoryApiResourceModule(ObjectMapper mapper) { this(new ApiResourceDecisions(mapper)); }

    /** @param decisions injected stateless decision engine */
    InMemoryApiResourceModule(ApiResourceDecisions decisions) { this.decisions = java.util.Objects.requireNonNull(decisions, "decisions"); }

    @Override
    public synchronized ApiResourceSpec save(String resourceId, String connectionId, ApiResourceCommand command, ExpectedRevision expected) {
        ApiResourceSpec next = decisions.next(Optional.ofNullable(resources.get(resourceId)), resourceId, connectionId, command, expected);
        resources.put(resourceId, next);
        return copy(next);
    }

    @Override
    public synchronized Optional<ApiResourceSpec> get(String resourceId) {
        // Validation of read identifiers remains in the same decision seam.
        decisions.validateResourceId(resourceId);
        return Optional.ofNullable(resources.get(resourceId)).map(this::copy);
    }

    private ApiResourceSpec copy(ApiResourceSpec spec) {
        return new ApiResourceSpec(spec.schemaVersion(), spec.resourceId(), spec.revision(), spec.fingerprint(), spec.displayName(), spec.description(), spec.connectionId(), spec.operation(), spec.contract(), spec.response(), spec.effect(), spec.examples(), spec.status());
    }
}
