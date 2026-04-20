package com.leanowtech.bloge.gateway.resource;

/**
 * Mutable extension of {@link ResourceRegistry} that supports runtime registration,
 * updates, and removal of {@link ResourceDescriptor} entries.
 */
public interface WritableResourceRegistry extends ResourceRegistry {

    /**
     * Registers a new resource descriptor.
     *
     * @param descriptor the descriptor to register
     * @throws IllegalArgumentException if a descriptor with the same {@code resourceId}
     *                                  is already registered
     */
    void register(ResourceDescriptor descriptor);

    /**
     * Replaces an existing resource descriptor.
     *
     * @param descriptor the updated descriptor (matched by {@code resourceId})
     * @throws com.leanowtech.bloge.gateway.exception.ResourceNotFoundException
     *         if no descriptor with the given {@code resourceId} exists
     */
    void update(ResourceDescriptor descriptor);

    /**
     * Removes the descriptor with the given identifier.
     *
     * @param resourceId the logical resource identifier to deregister
     * @throws com.leanowtech.bloge.gateway.exception.ResourceNotFoundException
     *         if no descriptor with the given {@code resourceId} exists
     */
    void deregister(String resourceId);
}
