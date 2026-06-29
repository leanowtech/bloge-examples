package com.leanowtech.bloge.gateway.visual.resource;

import java.util.Collection;
import java.util.Optional;

/**
 * Registry for visual design contracts attached to resource descriptors.
 */
public interface ResourceDesignContractRegistry {

    /**
     * @return all registered contracts
     */
    Collection<ResourceDesignContract> all();

    /**
     * Finds a contract by descriptor resource id.
     *
     * @param resourceId descriptor id
     * @return contract when present
     */
    Optional<ResourceDesignContract> findByResourceId(String resourceId);

    /**
     * Registers or replaces a contract.
     *
     * @param contract contract to store
     * @return stored contract
     */
    ResourceDesignContract upsert(ResourceDesignContract contract);

    /**
     * Deletes a contract by descriptor resource id.
     *
     * @param resourceId descriptor id
     */
    void deleteByResourceId(String resourceId);
}
