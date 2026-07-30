package com.leanowtech.bloge.gateway.visual.authoring.application;

import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Optimistically locked storage for mutable library-authoring sources.
 */
public interface AuthoringDraftRepository {

    Collection<AuthoringDraft> all();

    Optional<AuthoringDraft> find(String draftId);

    List<AuthoringDraft> revisions(String draftId);

    Optional<AuthoringDraft> saveIfRevision(long expectedRevision,
                                            AuthoringDraft candidate,
                                            String actor);
}
