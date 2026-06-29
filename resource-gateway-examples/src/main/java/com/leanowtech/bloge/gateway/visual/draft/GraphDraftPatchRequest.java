package com.leanowtech.bloge.gateway.visual.draft;

import java.util.List;

/**
 * Optimistic-locking JSON patch request for a visual graph draft.
 *
 * @param expectedRevision revision observed by the client
 * @param patch JSON patch operations
 */
public record GraphDraftPatchRequest(
        long expectedRevision,
        List<PatchOperation> patch
) {
    /**
     * Creates a patch request.
     */
    public GraphDraftPatchRequest {
        expectedRevision = Math.max(0, expectedRevision);
        patch = patch == null ? List.of() : List.copyOf(patch);
    }

    /**
     * JSON patch operation.
     *
     * @param op add, replace, or remove
     * @param path JSON pointer path
     * @param value operation value
     */
    public record PatchOperation(String op, String path, Object value) {
        /**
         * Creates a patch operation.
         */
        public PatchOperation {
            op = op == null ? "" : op;
            path = path == null ? "" : path;
        }
    }
}
