package com.leanowtech.bloge.gateway.authoring.scenario;

import java.util.List;
import java.util.Optional;

/**
 * Optimistic, scope-isolated repository for payload-free Scenario publication saga state.
 */
public interface ScenarioPublicationRepository {

    /** Resolves the current publication transition in one complete enterprise scope. */
    Optional<StoredScenarioPublication> find(
            ScenarioDraftSet.EnterpriseScope scope,
            String publicationId);

    /** Returns immutable transition history in ascending state-version order. */
    List<StoredScenarioPublication> history(
            ScenarioDraftSet.EnterpriseScope scope,
            String publicationId);

    /**
     * Persists the next transition when the current state version matches.
     *
     * @param expectedStateVersion zero for create, otherwise the observed state version
     * @param report next payload-free report state
     * @return stored next transition, or empty on a competing publisher
     */
    Optional<StoredScenarioPublication> saveIfVersion(
            long expectedStateVersion,
            ScenarioPublicationReport report);
}
