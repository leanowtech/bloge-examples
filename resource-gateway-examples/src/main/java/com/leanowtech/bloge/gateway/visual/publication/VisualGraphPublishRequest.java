package com.leanowtech.bloge.gateway.visual.publication;

/**
 * Request body for publishing a stored visual graph draft.
 *
 * @param expectedRevision draft revision observed by the publisher; zero keeps
 *                         legacy unguarded publish semantics
 * @param ackWarnings true when the publisher already reviewed non-blocking validation warnings
 */
public record VisualGraphPublishRequest(long expectedRevision, boolean ackWarnings) {
    /**
     * Creates a publish request.
     */
    public VisualGraphPublishRequest {
        expectedRevision = Math.max(0, expectedRevision);
    }

    /**
     * Backward-compatible constructor for callers that only guard by revision.
     *
     * @param expectedRevision draft revision observed by the publisher
     */
    public VisualGraphPublishRequest(long expectedRevision) {
        this(expectedRevision, false);
    }
}
