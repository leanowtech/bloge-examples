package com.leanowtech.bloge.gateway.visual.authoring.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic ownership authority for isolated tests and local composition.
 */
public final class InMemoryAuthoringCatalogOwnershipRepository
        implements AuthoringCatalogOwnershipRepository {

    private final Map<String, Ownership> ownership = new LinkedHashMap<>();

    @Override
    public synchronized Optional<Ownership> find(String libraryId) {
        return Optional.ofNullable(ownership.get(normalized(libraryId)));
    }

    @Override
    public synchronized Ownership claim(
            AuthoringScope scope,
            String libraryId,
            String actor,
            Instant claimedAt) {
        Ownership candidate = new Ownership(scope, libraryId, actor, claimedAt);
        Ownership existing = ownership.get(candidate.libraryId());
        if (existing != null && !existing.scope().equals(candidate.scope())) {
            throw new AuthoringCatalogOwnershipConflictException();
        }
        if (existing != null) {
            return existing;
        }
        ownership.put(candidate.libraryId(), candidate);
        return candidate;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
