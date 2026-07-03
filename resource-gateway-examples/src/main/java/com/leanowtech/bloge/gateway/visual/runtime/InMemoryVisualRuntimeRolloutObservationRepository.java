package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rollout observation repository for tests and local overrides.
 */
public class InMemoryVisualRuntimeRolloutObservationRepository
        implements VisualRuntimeRolloutObservationRepository {

    private final ConcurrentHashMap<String, VisualRuntimeRolloutObservation> observations =
            new ConcurrentHashMap<>();

    @Override
    public Collection<VisualRuntimeRolloutObservation> all() {
        return observations.values().stream()
                .sorted(Comparator.comparing(VisualRuntimeRolloutObservation::observedAt)
                        .reversed()
                        .thenComparing(VisualRuntimeRolloutObservation::observationId))
                .toList();
    }

    @Override
    public Optional<VisualRuntimeRolloutObservation> find(String observationId) {
        return Optional.ofNullable(observations.get(observationId));
    }

    @Override
    public VisualRuntimeRolloutObservation create(VisualRuntimeRolloutObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("Runtime rollout observation is required.");
        }
        String observationId = observation.observationId().isBlank()
                ? UUID.randomUUID().toString()
                : observation.observationId();
        Instant now = Instant.now();
        VisualRuntimeRolloutObservation stored = observation.withIdentity(observationId, 1, now, now);
        VisualRuntimeRolloutObservation previous = observations.putIfAbsent(observationId, stored);
        if (previous != null) {
            throw new IllegalArgumentException("Runtime rollout observation already exists: " + observationId);
        }
        return stored;
    }
}
