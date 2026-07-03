package com.leanowtech.bloge.gateway.visual.asset;

import java.util.Collection;
import java.util.Optional;

/**
 * Repository for runtime implementation binding proposals.
 */
public interface VisualRuntimeBindingImplementationRepository {

    /**
     * @return all stored implementation binding proposals
     */
    Collection<VisualRuntimeBindingImplementationBinding> all();

    /**
     * Finds one binding proposal.
     *
     * @param bindingId binding id
     * @return binding proposal when present
     */
    Optional<VisualRuntimeBindingImplementationBinding> find(String bindingId);

    /**
     * Persists a new binding proposal.
     *
     * @param binding proposal to create
     * @return stored proposal with repository identity
     */
    VisualRuntimeBindingImplementationBinding create(VisualRuntimeBindingImplementationBinding binding);

    /**
     * Updates an existing binding proposal lifecycle record.
     *
     * @param binding binding record to replace
     * @return stored binding record
     */
    VisualRuntimeBindingImplementationBinding update(VisualRuntimeBindingImplementationBinding binding);

    /**
     * Finds the active bound implementation for one operator.
     *
     * @param operatorRef operator reference
     * @return active bound implementation when present
     */
    default Optional<VisualRuntimeBindingImplementationBinding> findActiveBound(String operatorRef) {
        String normalized = operatorRef == null ? "" : operatorRef.trim();
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return all().stream()
                .filter(binding -> normalized.equals(binding.operatorRef()))
                .filter(VisualRuntimeBindingImplementationBinding::bound)
                .findFirst();
    }
}
