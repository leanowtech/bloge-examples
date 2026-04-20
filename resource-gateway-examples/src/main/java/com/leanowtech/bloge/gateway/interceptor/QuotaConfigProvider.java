package com.leanowtech.bloge.gateway.interceptor;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds mutable quota configuration for the two-level rate-limiting model used by
 * {@link TenantRateLimiterInterceptor}.
 *
 * <h3>Two-level quota model</h3>
 * <ol>
 *   <li><b>Provider-global quota</b> — caps the total requests-per-second the gateway
 *       will send to a given upstream provider, regardless of which tenant is calling.
 *       This protects the provider from being overwhelmed.</li>
 *   <li><b>Tenant-per-provider quota</b> — caps the requests-per-second a single tenant
 *       may consume for a given provider. This ensures fair sharing among tenants.</li>
 * </ol>
 *
 * <p>Missing entries fall back to reasonable defaults: 100 RPS for providers and
 * 20 RPS for tenants. Quotas can be updated at runtime via the {@code update*} methods.
 *
 * <p>Thread-safe — backed by {@link ConcurrentHashMap}.
 */
@Component
public class QuotaConfigProvider {

    /** Default provider-level quota when none is explicitly configured. */
    private static final ProviderQuota DEFAULT_PROVIDER_QUOTA = new ProviderQuota(100);

    /** Default tenant-per-provider quota when none is explicitly configured. */
    private static final TenantQuota DEFAULT_TENANT_QUOTA = new TenantQuota(20);

    /**
     * Provider-global quota configuration.
     *
     * @param maxRequestsPerSecond maximum requests per second the gateway will send to this provider
     */
    public record ProviderQuota(int maxRequestsPerSecond) {
        public ProviderQuota {
            if (maxRequestsPerSecond <= 0) {
                throw new IllegalArgumentException("maxRequestsPerSecond must be positive");
            }
        }
    }

    /**
     * Tenant-per-provider quota configuration.
     *
     * @param maxRequestsPerSecond maximum requests per second one tenant may consume for one provider
     */
    public record TenantQuota(int maxRequestsPerSecond) {
        public TenantQuota {
            if (maxRequestsPerSecond <= 0) {
                throw new IllegalArgumentException("maxRequestsPerSecond must be positive");
            }
        }
    }

    private final ConcurrentHashMap<String, ProviderQuota> providerQuotas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TenantQuota> tenantQuotas = new ConcurrentHashMap<>();

    /**
     * Returns the quota for the given provider. Falls back to a default (100 RPS) if none
     * has been explicitly configured.
     *
     * @param provider the upstream provider name (e.g. {@code "user-service"})
     * @return the effective provider quota
     */
    public ProviderQuota providerQuota(String provider) {
        return providerQuotas.getOrDefault(provider, DEFAULT_PROVIDER_QUOTA);
    }

    /**
     * Returns the quota for the given tenant on the given provider. Falls back to a
     * default (20 RPS) if none has been explicitly configured.
     *
     * @param tenantId the tenant identifier
     * @param provider the upstream provider name
     * @return the effective tenant-per-provider quota
     */
    public TenantQuota tenantQuota(String tenantId, String provider) {
        String key = tenantId + ":" + provider;
        return tenantQuotas.getOrDefault(key, DEFAULT_TENANT_QUOTA);
    }

    /**
     * Sets or updates the provider-global quota for the given provider.
     *
     * @param provider the upstream provider name
     * @param quota    the new quota
     */
    public void updateProviderQuota(String provider, ProviderQuota quota) {
        providerQuotas.put(provider, quota);
    }

    /**
     * Sets or updates the per-tenant quota for the given tenant on the given provider.
     *
     * @param tenantId the tenant identifier
     * @param provider the upstream provider name
     * @param quota    the new quota
     */
    public void updateTenantQuota(String tenantId, String provider, TenantQuota quota) {
        tenantQuotas.put(tenantId + ":" + provider, quota);
    }
}
