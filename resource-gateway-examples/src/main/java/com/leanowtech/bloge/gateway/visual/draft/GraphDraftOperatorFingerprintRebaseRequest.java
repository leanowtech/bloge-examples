package com.leanowtech.bloge.gateway.visual.draft;

import java.util.List;

/**
 * Request to explicitly rebase stored operator fingerprint snapshots against the current catalog.
 *
 * @param expectedRevision revision observed by the client
 * @param nodeIds optional node ids to rebase; empty means all draft nodes
 */
public record GraphDraftOperatorFingerprintRebaseRequest(
        long expectedRevision,
        List<String> nodeIds
) {
    /**
     * Creates a rebase request.
     */
    public GraphDraftOperatorFingerprintRebaseRequest {
        expectedRevision = Math.max(0, expectedRevision);
        nodeIds = nodeIds == null ? List.of() : nodeIds.stream()
                .filter(nodeId -> nodeId != null && !nodeId.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
