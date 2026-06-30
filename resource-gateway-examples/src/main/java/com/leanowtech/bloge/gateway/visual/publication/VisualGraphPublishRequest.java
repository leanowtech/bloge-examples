package com.leanowtech.bloge.gateway.visual.publication;

/**
 * Request body for publishing a stored visual graph draft.
 *
 * @param expectedRevision draft revision observed by the publisher; zero keeps
 *                         legacy unguarded publish semantics
 */
public record VisualGraphPublishRequest(long expectedRevision) {
    /**
     * Creates a publish request.
     */
    public VisualGraphPublishRequest {
        expectedRevision = Math.max(0, expectedRevision);
    }
}
