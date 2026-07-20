package com.leanowtech.bloge.gateway.testing.api;

import java.util.Optional;

/**
 * Registry boundary for immutable, governed fixture-bundle revisions.
 *
 * <p>Implementations must reject an envelope whose canonical bundle fingerprint or embedded
 * id/revision differs from its indexed fields, both on create and read. Authorized consumers
 * independently repeat the verification at their repository trust transition.</p>
 */
public interface FixtureBundleRepository {
    /** Creates a verified immutable revision, or returns the byte-equivalent existing revision. */
    StoredFixtureBundle create(StoredFixtureBundle fixtureBundle);

    /** Resolves one integrity-verified revision in the requested tenant and environment scope. */
    Optional<StoredFixtureBundle> find(String tenantId, String environmentId,
                                       String fixtureBundleId, long revision);
}
