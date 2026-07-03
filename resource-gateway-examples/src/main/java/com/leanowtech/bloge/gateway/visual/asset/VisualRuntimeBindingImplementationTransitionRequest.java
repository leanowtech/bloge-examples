package com.leanowtech.bloge.gateway.visual.asset;

/**
 * Request contract for runtime implementation binding lifecycle mutations.
 *
 * @param schemaVersion request contract version
 * @param actor principal or team approving the transition
 * @param reason required lifecycle decision reason
 * @param changeSource client/source system performing the transition
 * @param changeSummary short transition summary
 * @param ackReview true when a requires-review proposal has been explicitly reviewed
 * @param replacementBindingId replacement binding id for supersede operations
 * @param expectedRevision optional binding revision precondition; {@code 0} disables the guard
 * @param expectedReplacementRevision optional replacement binding revision precondition for supersede operations;
 *                                    {@code 0} disables the guard
 */
public record VisualRuntimeBindingImplementationTransitionRequest(
        String schemaVersion,
        String actor,
        String reason,
        String changeSource,
        String changeSummary,
        boolean ackReview,
        String replacementBindingId,
        long expectedRevision,
        long expectedReplacementRevision
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeBindingImplementationTransition.v1";

    public VisualRuntimeBindingImplementationTransitionRequest(String schemaVersion,
                                                               String actor,
                                                               String reason,
                                                               String changeSource,
                                                               String changeSummary,
                                                               boolean ackReview,
                                                               String replacementBindingId) {
        this(schemaVersion, actor, reason, changeSource, changeSummary, ackReview, replacementBindingId, 0, 0);
    }

    public VisualRuntimeBindingImplementationTransitionRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        actor = actor == null ? "" : actor.trim();
        reason = reason == null ? "" : reason.trim();
        changeSource = changeSource == null ? "" : changeSource.trim();
        changeSummary = changeSummary == null ? "" : changeSummary.trim();
        replacementBindingId = replacementBindingId == null ? "" : replacementBindingId.trim();
        expectedRevision = Math.max(0, expectedRevision);
        expectedReplacementRevision = Math.max(0, expectedReplacementRevision);
    }
}
