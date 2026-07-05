package com.leanowtech.bloge.gateway.visual.resource;

import java.util.Collection;

/**
 * Visual-owned read-only registry for resource descriptors projected into canvas operators.
 */
public interface VisualResourceRegistry {

    /**
     * Resolves a visual resource descriptor by identifier.
     *
     * @param resourceId logical resource identifier
     * @return matching visual descriptor
     */
    VisualResourceDescriptor resolve(String resourceId);

    /**
     * @param resourceId logical resource identifier
     * @return true when the descriptor is available
     */
    boolean contains(String resourceId);

    /**
     * @return all descriptors visible to the canvas
     */
    Collection<VisualResourceDescriptor> all();

    /**
     * @return an empty registry useful for tests and optional integrations
     */
    static VisualResourceRegistry empty() {
        return new VisualResourceRegistry() {
            @Override
            public VisualResourceDescriptor resolve(String resourceId) {
                throw new java.util.NoSuchElementException(resourceId);
            }

            @Override
            public boolean contains(String resourceId) {
                return false;
            }

            @Override
            public Collection<VisualResourceDescriptor> all() {
                return java.util.List.of();
            }
        };
    }
}
