package com.leanowtech.bloge.gateway.interceptor;

import com.leanowtech.bloge.core.context.TenantContextHolder;
import com.leanowtech.bloge.core.spi.OperatorInterceptor;
import com.leanowtech.bloge.core.spi.OperatorInvocation;
import com.leanowtech.bloge.gateway.exception.ProviderCapacityException;
import com.leanowtech.bloge.gateway.exception.TenantRateLimitException;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link OperatorInterceptor} that enforces two-level token-bucket rate limiting
 * on {@code HttpResourceOperator} invocations.
 *
 * <h3>Bucket layout</h3>
 * <ol>
 *   <li><b>Tenant + provider bucket</b> (key {@code tenantId:provider}) — limits how many
 *       requests a single tenant can send to one upstream provider per second.</li>
 *   <li><b>Global provider bucket</b> (key {@code *:provider}) — limits total requests to
 *       an upstream provider across all tenants.</li>
 * </ol>
 *
 * <h3>Provider derivation</h3>
 * <p>The provider name is derived from the {@link HttpResourceInput#resourceId()} prefix
 * before the first {@code '.'} character. For example, {@code "user-service.getProfile"}
 * maps to provider {@code "user-service"}.
 *
 * <h3>Retry semantics</h3>
 * <ul>
 *   <li><b>{@link TenantRateLimitException}</b> — the tenant has exceeded its quota and
 *       should <em>not</em> retry. The client must wait for the rate-limit window to reset.</li>
 *   <li><b>{@link ProviderCapacityException}</b> — the provider's global capacity is
 *       exhausted. This <em>may</em> be retried with exponential back-off because other
 *       tenants' traffic may subside.</li>
 * </ul>
 *
 * <p>Only applies to operators whose input is {@link HttpResourceInput}; all other
 * operators pass through unintercepted.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TenantRateLimiterInterceptor implements OperatorInterceptor {

    private final QuotaConfigProvider quotaProvider;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param quotaProvider supplies the configured quotas for providers and tenants
     */
    public TenantRateLimiterInterceptor(QuotaConfigProvider quotaProvider) {
        this.quotaProvider = quotaProvider;
    }

    /**
     * Intercepts operator invocations to enforce rate limits.
     *
     * <p>If the input is not an {@link HttpResourceInput}, the invocation proceeds
     * without any rate-limit check.
     *
     * @param invocation the operator invocation context
     * @return the operator result if rate limits are satisfied
     * @throws TenantRateLimitException  if the tenant's per-provider bucket is exhausted
     * @throws ProviderCapacityException if the provider's global bucket is exhausted
     * @throws Exception                 if the downstream operator or interceptor fails
     */
    @Override
    public Object intercept(OperatorInvocation invocation) throws Exception {
        if (!(invocation.input() instanceof HttpResourceInput input)) {
            return invocation.proceed();
        }

        String resourceId = input.resourceId();
        String provider = extractProvider(resourceId);
        String tenantId = TenantContextHolder.currentIfBound()
                .map(tc -> tc.tenantId())
                .orElse("default");

        // 1. Check tenant + provider bucket
        var tenantQuota = quotaProvider.tenantQuota(tenantId, provider);
        String tenantKey = tenantId + ":" + provider;
        TokenBucket tenantBucket = buckets.compute(tenantKey,
                (_, existing) -> existing == null || existing.maxTokens() != tenantQuota.maxRequestsPerSecond()
                        ? new TokenBucket(tenantQuota.maxRequestsPerSecond())
                        : existing);
        if (!tenantBucket.tryConsume()) {
            throw new TenantRateLimitException(tenantId, resourceId);
        }

        // 2. Check global provider bucket
        var providerQuota = quotaProvider.providerQuota(provider);
        String globalKey = "*:" + provider;
        TokenBucket globalBucket = buckets.compute(globalKey,
                (_, existing) -> existing == null || existing.maxTokens() != providerQuota.maxRequestsPerSecond()
                        ? new TokenBucket(providerQuota.maxRequestsPerSecond())
                        : existing);
        if (!globalBucket.tryConsume()) {
            throw new ProviderCapacityException(resourceId, provider, 429);
        }

        return invocation.proceed();
    }

    /**
     * Extracts the provider name from a resource identifier.
     * Uses the prefix before the first {@code '.'}, or the full ID if there is no dot.
     */
    static String extractProvider(String resourceId) {
        int dot = resourceId.indexOf('.');
        return dot > 0 ? resourceId.substring(0, dot) : resourceId;
    }

    // ── Lightweight in-memory token bucket ──────────────────────────────

    /**
     * Simple token-bucket implementation using {@link System#nanoTime()} for refill timing.
     *
     * <p>Tokens refill continuously at the configured rate. The bucket capacity equals
     * the tokens-per-second rate, allowing short bursts up to one second of capacity.
     * Thread-safe via {@code synchronized} — acceptable for an example project.
     */
    static final class TokenBucket {

        private final int maxTokens;
        private final long refillIntervalNanos;
        private double tokens;
        private long lastRefillNanos;

        /**
         * @param tokensPerSecond maximum sustained request rate
         */
        TokenBucket(int tokensPerSecond) {
            this.maxTokens = tokensPerSecond;
            this.refillIntervalNanos = 1_000_000_000L / tokensPerSecond;
            this.tokens = tokensPerSecond;
            this.lastRefillNanos = System.nanoTime();
        }

        /**
         * Attempts to consume one token from the bucket.
         *
         * @return {@code true} if a token was available; {@code false} if the bucket is empty
         */
        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        int maxTokens() {
            return maxTokens;
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed <= 0) return;
            double newTokens = (double) elapsed / refillIntervalNanos;
            tokens = Math.min(maxTokens, tokens + newTokens);
            lastRefillNanos = now;
        }
    }
}
