package com.leanowtech.bloge.gateway.visual.authoring.resource;

import java.util.Optional;

/**
 * Pure authoritative API Resource domain seam. Implementations own revision,
 * CAS, validation, and exact subject identity; HTTP, storage, and projections
 * remain outside this module.
 */
public interface ApiResourceModule {

    /**
     * Creates or updates one resource using the supplied exact connection identity.
     *
     * @param resourceId stable resource identifier
     * @param resolvedConnectionId already-resolved connection identifier
     * @param command validated-wire-shaped resource content
     * @param expected create or exact-match expectation
     * @return newly committed immutable resource revision
     */
    ApiResourceSpec save(String resourceId, String resolvedConnectionId,
                         ApiResourceCommand command, ExpectedRevision expected);

    /**
     * Reads the latest immutable revision.
     *
     * @param resourceId stable resource identifier
     * @return defensive copy when present
     */
    Optional<ApiResourceSpec> get(String resourceId);
}
