package com.leanowtech.bloge.gateway.visual.draft;

import java.util.List;

/**
 * Request to explicitly rebase stored operator fingerprint snapshots against the current catalog.
 *
 * @param expectedRevision revision observed by the client
 * @param nodeIds optional node ids to rebase; empty means all draft nodes
 * @param actor user or system actor requesting the rebase
 * @param changeSource UI surface or integration source requesting the rebase
 * @param changeSummary human-readable rebase summary
 * @param reason operator-facing reason for audit and drift review
 */
public record GraphDraftOperatorFingerprintRebaseRequest(
        long expectedRevision,
        List<String> nodeIds,
        String actor,
        String changeSource,
        String changeSummary,
        String reason
) {
    private static final String DEFAULT_ACTOR = "visual-canvas";
    private static final String DEFAULT_SOURCE = "operator-fingerprint-rebase";
    private static final String DEFAULT_SUMMARY = "Rebased operator fingerprint snapshot(s).";

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
        actor = actor == null ? "" : actor.trim();
        changeSource = changeSource == null ? "" : changeSource.trim();
        changeSummary = changeSummary == null ? "" : changeSummary.trim();
        reason = reason == null ? "" : reason.trim();
    }

    /**
     * Backward-compatible constructor for callers created before audit reason was first-class.
     */
    public GraphDraftOperatorFingerprintRebaseRequest(long expectedRevision, List<String> nodeIds) {
        this(expectedRevision, nodeIds, "", "", "", "");
    }

    /**
     * @return actor with visual canvas fallback
     */
    public String effectiveActor() {
        return actor.isBlank() ? DEFAULT_ACTOR : actor;
    }

    /**
     * @return source with operator fingerprint rebase fallback
     */
    public String effectiveChangeSource() {
        return changeSource.isBlank() ? DEFAULT_SOURCE : changeSource;
    }

    /**
     * @return summary with operator fingerprint rebase fallback
     */
    public String effectiveChangeSummary() {
        return changeSummary.isBlank() ? DEFAULT_SUMMARY : changeSummary;
    }
}
