package com.leanowtech.bloge.graphengine.store;

import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.context.TenantContextHolder;

import java.util.Objects;
import java.util.Optional;

/**
 * Shared tenant-visibility rules for graph-engine product stores.
 */
public final class GraphTenantSupport {
    private GraphTenantSupport() {
    }

    /**
     * Returns the tenant context currently bound on this thread, if any.
     *
     * @return the bound tenant context, or empty when no tenant is bound
     */
    public static Optional<TenantContext> currentTenant() {
        return TenantContextHolder.currentIfBound();
    }

    /**
     * Returns {@code true} when no explicit tenant scope is bound or when the
     * bound scope matches the given tenant and namespace identifiers.
     *
     * @param tenantId  tenant to check
     * @param namespace namespace to check
     * @return {@code true} when the record is visible to the current tenant scope
     */
    public static boolean matchesCurrentTenant(String tenantId, String namespace) {
        return currentTenant()
                .map(tenant -> Objects.equals(tenant.tenantId(), tenantId)
                        && Objects.equals(tenant.namespace(), namespace))
                .orElse(true);
    }
}
