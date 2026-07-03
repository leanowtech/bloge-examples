package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory adapter activation repository for tests and local overrides.
 */
public class InMemoryVisualRuntimeAdapterActivationRepository
        implements VisualRuntimeAdapterActivationRepository {

    private final ConcurrentHashMap<String, VisualRuntimeAdapterActivation> activations =
            new ConcurrentHashMap<>();

    @Override
    public Collection<VisualRuntimeAdapterActivation> all() {
        return activations.values().stream()
                .sorted(Comparator.comparing(VisualRuntimeAdapterActivation::createdAt)
                        .reversed()
                        .thenComparing(VisualRuntimeAdapterActivation::activationId))
                .toList();
    }

    @Override
    public Optional<VisualRuntimeAdapterActivation> find(String activationId) {
        return Optional.ofNullable(activations.get(activationId));
    }

    @Override
    public VisualRuntimeAdapterActivation create(VisualRuntimeAdapterActivation activation) {
        if (activation == null) {
            throw new IllegalArgumentException("Runtime adapter activation is required.");
        }
        String activationId = activation.activationId().isBlank()
                ? UUID.randomUUID().toString()
                : activation.activationId();
        if (findActiveByBindingId(activation.bindingId()).isPresent()) {
            throw new IllegalArgumentException(
                    "Runtime adapter activation already exists for binding: " + activation.bindingId());
        }
        Instant now = Instant.now();
        VisualRuntimeAdapterActivation stored = activation.withIdentity(activationId, 1, now, now);
        VisualRuntimeAdapterActivation previous = activations.putIfAbsent(activationId, stored);
        if (previous != null) {
            throw new IllegalArgumentException("Runtime adapter activation already exists: " + activationId);
        }
        return stored;
    }

    @Override
    public VisualRuntimeAdapterActivation update(VisualRuntimeAdapterActivation activation) {
        if (activation == null || activation.activationId().isBlank()) {
            throw new IllegalArgumentException("Runtime adapter activation id is required for update.");
        }
        if (!activations.containsKey(activation.activationId())) {
            throw new IllegalArgumentException(
                    "Runtime adapter activation does not exist: " + activation.activationId());
        }
        activations.put(activation.activationId(), activation);
        return activation;
    }
}
