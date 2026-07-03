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
}
