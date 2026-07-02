package com.leanowtech.bloge.gateway.visual.draft;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for visual graph drafts.
 */
public interface GraphDraftRepository {

    /**
     * @return all drafts
     */
    Collection<GraphDraft> all();

    /**
     * Finds a draft.
     *
     * @param draftId draft id
     * @return draft when present
     */
    Optional<GraphDraft> find(String draftId);

    /**
     * Lists stored revisions for a draft, newest first.
     *
     * @param draftId draft id
     * @return immutable draft snapshots by revision
     */
    List<GraphDraft> revisions(String draftId);

    /**
     * Finds one stored draft revision.
     *
     * @param draftId draft id
     * @param revision revision number
     * @return draft snapshot when present
     */
    Optional<GraphDraft> findRevision(String draftId, long revision);

    /**
     * Lists active and retained draft history summaries, including deleted drafts with preserved revisions.
     *
     * @return history summaries ordered by latest revision descending
     */
    List<GraphDraftHistorySummary> history();

    /**
     * Creates or updates a draft.
     *
     * @param draft draft to store
     * @return stored draft with assigned id/revision
     */
    GraphDraft save(GraphDraft draft);

    /**
     * Updates a draft only when the stored revision still matches the expected revision.
     *
     * @param draftId draft id
     * @param expectedRevision revision observed by the caller
     * @param draft draft to store
     * @return updated draft when the revision matched
     */
    Optional<GraphDraft> saveIfRevision(String draftId, long expectedRevision, GraphDraft draft);

    /**
     * Deletes a draft.
     *
     * @param draftId draft id
     */
    default void delete(String draftId) {
        delete(draftId, GraphDraft.RevisionMetadata.empty());
    }

    /**
     * Deletes the current draft while allowing implementations to preserve revision audit history.
     *
     * @param draftId draft id
     * @param metadata deletion audit metadata
     */
    void delete(String draftId, GraphDraft.RevisionMetadata metadata);
}
