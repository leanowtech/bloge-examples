package com.leanowtech.bloge.graphengine.model;

import java.util.Set;

/**
 * Simple role-based access-control declaration for product-layer graph metadata.
 *
 * @param viewRoles   roles allowed to inspect metadata and runtime state
 * @param startRoles  roles allowed to start new instances
 * @param deployRoles roles allowed to publish and deploy versions
 * @param adminRoles  roles allowed to perform administrative mutations
 */
public record RbacPolicy(
        Set<String> viewRoles,
        Set<String> startRoles,
        Set<String> deployRoles,
        Set<String> adminRoles
) {
    public RbacPolicy {
        viewRoles = viewRoles == null ? Set.of() : Set.copyOf(viewRoles);
        startRoles = startRoles == null ? Set.of() : Set.copyOf(startRoles);
        deployRoles = deployRoles == null ? Set.of() : Set.copyOf(deployRoles);
        adminRoles = adminRoles == null ? Set.of() : Set.copyOf(adminRoles);
    }
}
