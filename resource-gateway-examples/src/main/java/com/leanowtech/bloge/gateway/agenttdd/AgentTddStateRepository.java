package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * Durable store for Agent TDD overlays and exact idempotency responses.
 */
public interface AgentTddStateRepository {

    /** Finds the current overlay for one exact server-derived scope. */
    Optional<AgentTddStoredAsset> find(String scopeKey, String kind, String assetRef);

    /** Lists current overlays of one kind inside one exact scope. */
    List<AgentTddStoredAsset> list(String scopeKey, String kind);

    /** Stores the next overlay revision and returns its server-owned envelope. */
    AgentTddStoredAsset save(String scopeKey, String kind, String assetRef, JsonNode data);

    /**
     * Stores the next overlay revision only when the durable current revision exactly matches.
     *
     * <p>An expected revision of {@code 0} means that the asset must not exist. Implementations
     * must make the comparison and write atomically; this is the human-review revision fence.</p>
     *
     * @throws AgentTddToolException when the asset changed after it was reviewed
     */
    AgentTddStoredAsset saveIfRevision(String scopeKey,
                                       String kind,
                                       String assetRef,
                                       long expectedRevision,
                                       JsonNode data);

    /**
     * Replays the exact response for a matching idempotency request.
     *
     * @throws AgentTddToolException when the key was already used for different request material
     */
    Optional<JsonNode> replay(String scopeKey,
                              String operation,
                              String idempotencyKey,
                              String requestFingerprint);

    /** Records the exact successful response for subsequent idempotent replay. */
    void record(String scopeKey,
                String operation,
                String idempotencyKey,
                String requestFingerprint,
                JsonNode response);
}
