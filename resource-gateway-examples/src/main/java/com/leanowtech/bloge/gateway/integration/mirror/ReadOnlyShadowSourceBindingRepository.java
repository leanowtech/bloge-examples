package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Optional;

/**
 * Append-only exact-revision store for verified detached Shadow source bindings.
 *
 * <p>Implementations must verify content addressing and authority signatures on both writes and
 * reads. Scope, id, revision, and fingerprint form one exact lookup closure; latest-by-id fallback
 * is deliberately absent.</p>
 */
public interface ReadOnlyShadowSourceBindingRepository {
    /**
     * Persists a verified immutable binding or returns an identical existing revision.
     *
     * @param binding signed source binding
     * @return persisted canonical binding
     */
    ReadOnlyShadowSourceBinding create(
            ReadOnlyShadowSourceBinding binding);

    /**
     * Resolves one exact binding revision inside a complete scope.
     *
     * @param scope complete enterprise namespace
     * @param bindingId stable binding identity
     * @param revision exact positive revision
     * @return verified binding when present
     */
    Optional<ReadOnlyShadowSourceBinding> find(
            CapabilitySnapshot.Scope scope,
            String bindingId,
            long revision);
}
