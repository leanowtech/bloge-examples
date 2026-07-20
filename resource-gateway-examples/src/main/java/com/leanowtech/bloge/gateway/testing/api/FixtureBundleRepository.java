package com.leanowtech.bloge.gateway.testing.api;

import java.util.Optional;

/**
 * Registry boundary for immutable, governed fixture-bundle revisions.
 *
 * <p>Implementations must reject an envelope whose canonical bundle fingerprint or embedded
 * id/revision differs from its indexed fields, both on create and read. Returned values must be
 * independently owned canonical snapshots: nested JSON containers cannot retain caller or storage
 * aliases. Read results must match the complete tenant/environment/id/revision lookup key, and
 * create results must match the submitted immutable identity and content. An idempotent create
 * preserves the original registry timestamp and author. Authorized consumers independently repeat
 * these checks at their repository trust transition.</p>
 */
public interface FixtureBundleRepository {
    /** Creates and returns a detached canonical revision, or its byte-equivalent existing revision. */
    StoredFixtureBundle create(StoredFixtureBundle fixtureBundle);

    /** Resolves one detached canonical revision bound to the complete requested lookup key. */
    Optional<StoredFixtureBundle> find(String tenantId, String environmentId,
                                       String fixtureBundleId, long revision);
}
