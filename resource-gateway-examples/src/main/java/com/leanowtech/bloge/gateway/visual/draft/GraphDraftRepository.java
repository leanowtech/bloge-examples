package com.leanowtech.bloge.gateway.visual.draft;

import java.util.Collection;
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
    void delete(String draftId);
}
