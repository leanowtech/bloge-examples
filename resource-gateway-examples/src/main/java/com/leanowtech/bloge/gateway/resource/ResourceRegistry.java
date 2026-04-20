package com.leanowtech.bloge.gateway.resource;

import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;

import java.util.Collection;

/**
 * Read-only registry of {@link ResourceDescriptor} entries.
 *
 * <p>Implementations look up descriptors by {@code resourceId} and throw
 * {@link ResourceNotFoundException} when a descriptor cannot be found.
 */
public interface ResourceRegistry {

    /**
     * Resolves a resource descriptor by its unique identifier.
     *
     * @param resourceId the logical resource identifier
     * @return the matching descriptor
     * @throws ResourceNotFoundException if no descriptor is registered for the given id
     */
    ResourceDescriptor resolve(String resourceId);

    /**
     * Returns {@code true} if a descriptor is registered for the given identifier.
     *
     * @param resourceId the logical resource identifier
     */
    boolean contains(String resourceId);

    /**
     * Returns an unmodifiable view of all registered descriptors.
     */
    Collection<ResourceDescriptor> all();
}
