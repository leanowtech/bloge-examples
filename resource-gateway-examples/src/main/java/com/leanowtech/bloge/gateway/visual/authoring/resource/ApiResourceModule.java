package com.leanowtech.bloge.gateway.visual.authoring.resource;

import java.util.Optional;

/**
 * Pure authoritative API Resource domain seam. Implementations own revision,
 * CAS, validation, and exact subject identity; HTTP, storage, and projections
 * remain outside this module. A failed operation is reported with one stable
 * {@link ApiResourceAuthoringException.Code}: {@code VALIDATION} for malformed
 * input or an unsafe effect contract, {@code ALREADY_EXISTS} for a create
 * against an existing id, {@code NOT_FOUND} for a match against a missing id,
 * and {@code CAS_MISMATCH} for a stale match revision.
 */
public interface ApiResourceModule {

    /**
     * Creates or updates one resource using the supplied exact connection identity.
     *
     * @param resourceId stable resource identifier
     * @param connectionId exact existing connection identifier; connection creation is outside this seam
     * @param command validated-wire-shaped resource content
     * @param expected create or exact-match expectation
     * @return newly committed immutable, flattened resource revision with status {@code DRAFT}
     */
    ApiResourceSpec save(String resourceId, String connectionId,
                         ApiResourceCommand command, ExpectedRevision expected);

    /**
     * Reads the latest immutable revision.
     *
     * @param resourceId stable resource identifier
     * @return defensive copy when present
     */
    Optional<ApiResourceSpec> get(String resourceId);
}
