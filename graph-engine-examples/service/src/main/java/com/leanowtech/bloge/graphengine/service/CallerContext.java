package com.leanowtech.bloge.graphengine.service;

import java.util.Set;

/**
 * Immutable snapshot of the caller's identity and granted roles for the current
 * graph-engine service invocation.
 *
 * <p>When no caller context is bound (i.e. the {@link CallerContextHolder} holds
 * {@code null}), the service treats the call as a system/internal invocation that
 * bypasses RBAC enforcement.  This preserves backward-compatible behaviour for
 * embedded and non-HTTP deployments.</p>
 *
 * @param roles the set of roles granted to the caller; never {@code null}
 */
public record CallerContext(Set<String> roles) {

    /** A caller context with no granted roles. */
    public static final CallerContext ANONYMOUS = new CallerContext(Set.of());

    public CallerContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /**
     * Returns {@code true} when the caller has at least one role from
     * {@code requiredRoles}, or when {@code requiredRoles} is empty
     * (unrestricted access).
     *
     * @param requiredRoles the set of roles that grant access
     * @return {@code true} if the caller is authorised
     */
    public boolean hasAnyRole(Set<String> requiredRoles) {
        if (requiredRoles == null || requiredRoles.isEmpty()) {
            return true;
        }
        for (String role : requiredRoles) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
