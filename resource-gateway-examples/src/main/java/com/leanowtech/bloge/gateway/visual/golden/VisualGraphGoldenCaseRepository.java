package com.leanowtech.bloge.gateway.visual.golden;

import java.util.Collection;
import java.util.Optional;

/**
 * Repository for visual graph golden regression cases.
 */
public interface VisualGraphGoldenCaseRepository {

    /**
     * @return all golden cases, newest first
     */
    Collection<VisualGraphGoldenCase> all();

    /**
     * Finds cases for one publication.
     *
     * @param publicationId publication id
     * @return matching cases, newest first
     */
    default Collection<VisualGraphGoldenCase> findByPublicationId(String publicationId) {
        String normalized = publicationId == null ? "" : publicationId.trim();
        return all().stream()
                .filter(testCase -> normalized.isBlank() || normalized.equals(testCase.publicationId()))
                .toList();
    }

    /**
     * Finds one case.
     *
     * @param caseId case id
     * @return golden case when present
     */
    Optional<VisualGraphGoldenCase> find(String caseId);

    /**
     * Saves or replaces a golden case.
     *
     * @param testCase golden case
     * @return stored case with assigned identity
     */
    VisualGraphGoldenCase save(VisualGraphGoldenCase testCase);

    /**
     * Deletes one case.
     *
     * @param caseId case id
     * @return true when a stored case was removed
     */
    boolean delete(String caseId);
}
