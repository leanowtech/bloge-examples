package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.context.TenantContextHolder;

/**
 * Resolves the tenant and namespace scope bound to the current HTTP request.
 */
public final class GraphEngineRequestScopeResolver {

    /**
     * Returns the current request scope, falling back to BLOGE's default tenant
     * context when no request-bound tenant has been configured.
     *
     * @return request tenant context
     */
    public TenantContext currentScope() {
        return TenantContextHolder.current();
    }
}
