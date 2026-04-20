package com.leanowtech.bloge.gateway.interceptor;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.context.TenantContextHolder;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.OperatorInvocation;
import com.leanowtech.bloge.gateway.exception.ProviderCapacityException;
import com.leanowtech.bloge.gateway.exception.TenantRateLimitException;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Component tests for {@link TenantRateLimiterInterceptor}.
 */
class TenantRateLimiterInterceptorTest {

    private static final HttpResourceOutput DUMMY_OUTPUT = new HttpResourceOutput(
            "test.svc", 200, Map.of(), "{}", Duration.ofMillis(10), true
    );

    @Test
    @DisplayName("tenant bucket exhaustion throws TenantRateLimitException")
    void tenantBucketExhaustion() throws Exception {
        var quotaProvider = new QuotaConfigProvider();
        // Allow only 2 requests per second for tenant t1 on provider "test"
        quotaProvider.updateTenantQuota("t1", "test", new QuotaConfigProvider.TenantQuota(2));
        quotaProvider.updateProviderQuota("test", new QuotaConfigProvider.ProviderQuota(100));

        var interceptor = new TenantRateLimiterInterceptor(quotaProvider);
        var input = new HttpResourceInput("test.method", Map.of());

        TenantContextHolder.runWith(new TenantContext("t1", "ns1"), () -> {
            try {
                // Consume all 2 tokens
                interceptor.intercept(invocation(input));
                interceptor.intercept(invocation(input));

                // Third call should be rate-limited
                assertThatThrownBy(() -> interceptor.intercept(invocation(input)))
                        .isInstanceOf(TenantRateLimitException.class)
                        .satisfies(ex -> {
                            var tle = (TenantRateLimitException) ex;
                            assertThat(tle.tenantId()).isEqualTo("t1");
                            assertThat(tle.resourceId()).isEqualTo("test.method");
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    @DisplayName("provider bucket exhaustion throws ProviderCapacityException")
    void providerBucketExhaustion() throws Exception {
        var quotaProvider = new QuotaConfigProvider();
        // Generous tenant quota, but provider only allows 1 request
        quotaProvider.updateTenantQuota("t1", "prov", new QuotaConfigProvider.TenantQuota(100));
        quotaProvider.updateProviderQuota("prov", new QuotaConfigProvider.ProviderQuota(1));

        var interceptor = new TenantRateLimiterInterceptor(quotaProvider);
        var input = new HttpResourceInput("prov.method", Map.of());

        TenantContextHolder.runWith(new TenantContext("t1", "ns1"), () -> {
            try {
                // Consume the single provider token
                interceptor.intercept(invocation(input));

                // Next call should hit provider capacity
                assertThatThrownBy(() -> interceptor.intercept(invocation(input)))
                        .isInstanceOf(ProviderCapacityException.class)
                        .satisfies(ex -> {
                            var pce = (ProviderCapacityException) ex;
                            assertThat(pce.provider()).isEqualTo("prov");
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    @DisplayName("non-HttpResourceInput passes through without rate limiting")
    void nonResourceInputPassesThrough() throws Exception {
        var interceptor = new TenantRateLimiterInterceptor(new QuotaConfigProvider());
        var graphContext = new GraphContext(new TenantContext("t1", "ns1"));
        var opCtx = new OperatorContext("n1", "g1", graphContext, 0);
        Operator<Object, Object> op = (in, ctx) -> "passthrough";
        var invocation = new OperatorInvocation(Collections.emptyIterator(), op, "not-resource-input", opCtx);

        Object result = interceptor.intercept(invocation);
        assertThat(result).isEqualTo("passthrough");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private OperatorInvocation invocation(HttpResourceInput input) {
        var graphContext = new GraphContext(new TenantContext("t1", "ns1"));
        var opCtx = new OperatorContext("n1", "g1", graphContext, 0);
        Operator<Object, Object> op = (in, ctx) -> DUMMY_OUTPUT;
        return new OperatorInvocation(Collections.emptyIterator(), op, input, opCtx);
    }
}
