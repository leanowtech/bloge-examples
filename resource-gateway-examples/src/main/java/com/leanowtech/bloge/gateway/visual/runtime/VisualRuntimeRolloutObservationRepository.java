package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.Collection;
import java.util.Optional;

/**
 * Repository for runtime rollout observation facts.
 */
public interface VisualRuntimeRolloutObservationRepository {

    /**
     * @return all stored rollout observations
     */
    Collection<VisualRuntimeRolloutObservation> all();

    /**
     * Finds one rollout observation.
     *
     * @param observationId observation id
     * @return observation when present
     */
    Optional<VisualRuntimeRolloutObservation> find(String observationId);

    /**
     * Persists a new rollout observation.
     *
     * @param observation observation to create
     * @return stored observation with repository identity
     */
    VisualRuntimeRolloutObservation create(VisualRuntimeRolloutObservation observation);
}
