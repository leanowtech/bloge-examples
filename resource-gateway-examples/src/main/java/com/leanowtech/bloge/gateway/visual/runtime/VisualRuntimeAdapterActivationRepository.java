package com.leanowtech.bloge.gateway.visual.runtime;

import java.util.Collection;
import java.util.Optional;

/**
 * Repository for runtime adapter activation facts.
 */
public interface VisualRuntimeAdapterActivationRepository {

    /**
     * @return all stored adapter activations
     */
    Collection<VisualRuntimeAdapterActivation> all();

    /**
     * Finds one activation.
     *
     * @param activationId activation id
     * @return activation when present
     */
    Optional<VisualRuntimeAdapterActivation> find(String activationId);

    /**
     * Persists a new adapter activation.
     *
     * @param activation activation to create
     * @return stored activation with repository identity
     */
    VisualRuntimeAdapterActivation create(VisualRuntimeAdapterActivation activation);

    /**
     * Updates an existing adapter activation.
     *
     * @param activation activation record to replace
     * @return stored activation record
     */
    VisualRuntimeAdapterActivation update(VisualRuntimeAdapterActivation activation);

    /**
     * Finds the active adapter activation for one implementation binding.
     *
     * @param bindingId implementation binding id
     * @return active adapter activation when present
     */
    default Optional<VisualRuntimeAdapterActivation> findActiveByBindingId(String bindingId) {
        String normalized = bindingId == null ? "" : bindingId.trim();
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return all().stream()
                .filter(activation -> normalized.equals(activation.bindingId()))
                .filter(VisualRuntimeAdapterActivation::active)
                .findFirst();
    }
}
