package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Optional;

/**
 * Append-only authorization index for full-scope mirror fixture consumption.
 *
 * <p>Implementations store no fixture or replay value. An exact retry is idempotent only when the
 * fixture fingerprint is unchanged; moving the same id/revision inside a scope is forbidden.</p>
 */
public interface MirrorFixtureScopeRepository {
    /** Persists one exact server-owned scope binding or returns its identical predecessor. */
    MirrorFixtureScopeBinding create(MirrorFixtureScopeBinding binding);

    /** Finds one binding by complete scope and immutable fixture coordinate. */
    Optional<MirrorFixtureScopeBinding> find(
            CapabilitySnapshot.Scope scope, String fixtureBundleId, long revision);
}
