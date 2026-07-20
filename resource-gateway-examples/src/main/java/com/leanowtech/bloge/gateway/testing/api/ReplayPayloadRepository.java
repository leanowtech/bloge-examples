package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Independent, tenant-scoped replay payload vault with immutable revisions and expiring values.
 *
 * <p>Implementations are trust boundaries, not passive object stores. They must detach caller
 * ownership, recompute available-value commitments, bind reads to the complete lookup key, preserve
 * a verifiable payload-free descriptor/envelope commitment after expiry, and return the exact
 * canonical value accepted by {@link #create(StoredReplayPayload)}. A structurally valid value from
 * another scope or revision is an integrity failure, not a cache hit.</p>
 */
public interface ReplayPayloadRepository {

    /**
     * Creates one immutable replay-payload revision or returns an identical existing revision.
     *
     * @param payload fully identified available payload
     * @return stored payload
     * @throws ReplayPayloadConflictException when the identity already has different content
     * @throws ReplayPayloadIntegrityException when the candidate or write receipt is inconsistent
     */
    StoredReplayPayload create(StoredReplayPayload payload);

    /**
     * Finds an exact scoped revision, atomically expiring and removing its value when necessary.
     *
     * @param tenantId verified tenant scope
     * @param environmentId verified environment scope
     * @param replayPayloadId stable payload id
     * @param revision exact revision
     * @return available payload or payload-free lifecycle tombstone
     * @throws ReplayPayloadIntegrityException when stored content, projection, or lookup binding is
     * inconsistent
     */
    Optional<StoredReplayPayload> find(String tenantId, String environmentId,
                                       String replayPayloadId, long revision);

    /**
     * Physically removes values whose retention elapsed while preserving descriptor tombstones.
     *
     * @param limit bounded rows per sweep
     * @return number of newly expired payload values
     */
    int purgeExpired(int limit);

    /**
     * Reads the database clock so all instances make retention decisions against one authority.
     *
     * @return database-authoritative time used by retention decisions
     */
    Instant currentTime();
}
