package com.leanowtech.bloge.gateway.visual.golden;

import java.util.Optional;

/**
 * Repository for latest visual graph golden certifications.
 */
public interface VisualGraphGoldenCertificationRepository {

    /**
     * Finds the latest certification for one publication.
     *
     * @param publicationId publication id
     * @return certification when present
     */
    Optional<VisualGraphGoldenCertification> find(String publicationId);

    /**
     * Saves or replaces the latest certification for one publication.
     *
     * @param certification certification record
     * @return stored certification
     */
    VisualGraphGoldenCertification save(VisualGraphGoldenCertification certification);
}
