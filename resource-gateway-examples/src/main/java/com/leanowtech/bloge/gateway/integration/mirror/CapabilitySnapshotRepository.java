package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Optional;

/**
 * Append-only persistence boundary for exact capability snapshot revisions.
 *
 * <p>All lookup methods require the full enterprise scope. The repository never offers a
 * tenant-wide id lookup because doing so would make organization and environment isolation a
 * caller convention instead of a storage invariant.</p>
 */
public interface CapabilitySnapshotRepository {
    /**
     * Appends a sealed snapshot revision or returns an identical already-stored revision.
     *
     * @param snapshot sealed immutable snapshot
     * @return persisted snapshot
     */
    CapabilitySnapshot create(CapabilitySnapshot snapshot);

    /**
     * Resolves one exact revision inside an exact scope.
     *
     * @param scope full enterprise scope
     * @param capabilityId capability id inside the scope
     * @param revision positive revision
     * @return exact verified snapshot when present
     */
    Optional<CapabilitySnapshot> find(CapabilitySnapshot.Scope scope,
                                      String capabilityId,
                                      long revision);

    /**
     * Resolves the highest stored revision inside an exact scope.
     *
     * @param scope full enterprise scope
     * @param capabilityId capability id inside the scope
     * @return latest verified snapshot when present
     */
    Optional<CapabilitySnapshot> findLatest(CapabilitySnapshot.Scope scope, String capabilityId);
}
