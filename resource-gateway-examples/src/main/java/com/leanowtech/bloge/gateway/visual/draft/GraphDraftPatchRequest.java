package com.leanowtech.bloge.gateway.visual.draft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Optimistic-locking JSON patch request for a visual graph draft.
 *
 * @param expectedRevision revision observed by the client
 * @param actor user or system actor producing this patch
 * @param changeSource UI surface or integration source producing this patch
 * @param changeSummary human-readable change reason
 * @param patch JSON patch operations
 */
public record GraphDraftPatchRequest(
        long expectedRevision,
        String actor,
        String changeSource,
        String changeSummary,
        List<PatchOperation> patch
) {
    /**
     * Creates a patch request.
     */
    public GraphDraftPatchRequest {
        expectedRevision = Math.max(0, expectedRevision);
        actor = actor == null ? "" : actor;
        changeSource = changeSource == null ? "" : changeSource;
        changeSummary = changeSummary == null ? "" : changeSummary;
        patch = patch == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(patch));
    }

    /**
     * Backward-compatible constructor for callers that only send patch operations.
     */
    public GraphDraftPatchRequest(long expectedRevision, List<PatchOperation> patch) {
        this(expectedRevision, "", "", "", patch);
    }

    /**
     * @return JSON pointer paths touched by this patch
     */
    public List<String> changedPaths() {
        return patch.stream()
                .filter(operation -> operation != null)
                .map(PatchOperation::path)
                .map(path -> path == null || path.isBlank() ? "/" : path)
                .distinct()
                .toList();
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
