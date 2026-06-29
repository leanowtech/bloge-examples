package com.leanowtech.bloge.gateway.visual.publication;

import java.util.Collection;
import java.util.Optional;

/**
 * Repository for immutable visual graph publication artifacts.
 */
public interface VisualGraphPublicationRepository {

    /**
     * @return all publications
     */
    Collection<VisualGraphPublication> all();

    /**
     * Finds a publication.
     *
     * @param publicationId publication id
     * @return publication when present
     */
    Optional<VisualGraphPublication> find(String publicationId);

    /**
     * Creates a new immutable publication.
     *
     * @param publication publication artifact
     * @return stored publication with assigned id
     */
    VisualGraphPublication create(VisualGraphPublication publication);
}
