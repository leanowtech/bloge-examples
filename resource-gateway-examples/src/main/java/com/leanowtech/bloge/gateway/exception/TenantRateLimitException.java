package com.leanowtech.bloge.gateway.exception;

/**
 * Thrown when a tenant has exceeded its rate limit or request quota.
 *
 * <p><b>Retry semantics:</b> do <em>not</em> retry — the tenant must wait until
 * the rate-limit window resets. Callers should back off or return a 429 response.
 */
public class TenantRateLimitException extends RuntimeException {

    private final String tenantId;
    private final String resourceId;

    /**
     * @param tenantId   the tenant that exceeded its quota
     * @param resourceId the resource the tenant attempted to call
     */
    public TenantRateLimitException(String tenantId, String resourceId) {
        super("Tenant '%s' rate-limited for resource '%s'".formatted(tenantId, resourceId));
        this.tenantId = tenantId;
        this.resourceId = resourceId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String resourceId() {
        return resourceId;
    }
}
