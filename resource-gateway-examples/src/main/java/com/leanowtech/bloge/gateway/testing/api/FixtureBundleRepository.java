package com.leanowtech.bloge.gateway.testing.api;

import java.util.Optional;

/** Registry boundary for immutable, governed fixture-bundle revisions. */
public interface FixtureBundleRepository {
    /** Creates an immutable revision, or returns the byte-equivalent existing revision. */
    StoredFixtureBundle create(StoredFixtureBundle fixtureBundle);

    /** Resolves one revision in the verified tenant and environment scope. */
    Optional<StoredFixtureBundle> find(String tenantId, String environmentId,
                                       String fixtureBundleId, long revision);
}
