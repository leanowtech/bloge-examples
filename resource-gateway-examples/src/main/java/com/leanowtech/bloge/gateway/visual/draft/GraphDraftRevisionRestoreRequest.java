package com.leanowtech.bloge.gateway.visual.draft;

/**
 * Request to restore one immutable visual draft revision as a new latest draft revision.
 *
 * @param expectedRevision optional current draft revision observed by the client
 * @param actor user or system actor requesting the restore
 * @param changeSource UI surface or integration source requesting the restore
 * @param changeSummary human-readable restore reason
 * @param reason operator-facing reason for audit and migration review
 */
public record GraphDraftRevisionRestoreRequest(
        long expectedRevision,
        String actor,
        String changeSource,
        String changeSummary,
        String reason
) {
    private static final String DEFAULT_ACTOR = "visual-canvas";
    private static final String DEFAULT_SOURCE = "revision-restore";

    /**
     * Creates a restore request.
     */
    public GraphDraftRevisionRestoreRequest {
        expectedRevision = Math.max(0, expectedRevision);
        actor = actor == null ? "" : actor.trim();
        changeSource = changeSource == null ? "" : changeSource.trim();
        changeSummary = changeSummary == null ? "" : changeSummary.trim();
        reason = reason == null ? "" : reason.trim();
    }

    /**
     * Backward-compatible constructor for callers created before reason was first-class.
     */
    public GraphDraftRevisionRestoreRequest(long expectedRevision,
                                            String actor,
                                            String changeSource,
                                            String changeSummary) {
        this(expectedRevision, actor, changeSource, changeSummary, "");
    }

    /**
     * @return empty request using server defaults
     */
    public static GraphDraftRevisionRestoreRequest empty() {
        return new GraphDraftRevisionRestoreRequest(0, "", "", "", "");
    }

    /**
     * @return actor with visual canvas fallback
     */
    public String effectiveActor() {
        return actor.isBlank() ? DEFAULT_ACTOR : actor;
    }

    /**
     * @return source with revision restore fallback
     */
    public String effectiveChangeSource() {
        return changeSource.isBlank() ? DEFAULT_SOURCE : changeSource;
    }

    /**
     * @param restoredRevision immutable revision selected as restore source
     * @return summary with revision-specific fallback
     */
    public String effectiveChangeSummary(long restoredRevision) {
        return changeSummary.isBlank()
                ? "Restored draft revision @" + restoredRevision + "."
                : changeSummary;
    }

    /**
     * @return reason when supplied by the caller
     */
    public String effectiveReason() {
        return reason;
    }
}
