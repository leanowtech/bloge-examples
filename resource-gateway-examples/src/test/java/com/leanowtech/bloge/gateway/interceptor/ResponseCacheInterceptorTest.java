package com.leanowtech.bloge.gateway.interceptor;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.OperatorInvocation;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component tests for {@link ResponseCacheInterceptor}.
 */
class ResponseCacheInterceptorTest {

    private static final HttpResourceOutput SUCCESS_OUTPUT = new HttpResourceOutput(
            "test.svc", 200, Map.of("key", "value"), "{}", Duration.ofMillis(50), true
    );

    private static final HttpResourceOutput FAILURE_OUTPUT = new HttpResourceOutput(
            "test.svc", 500, null, "error", Duration.ofMillis(50), false
    );

    @Test
    @DisplayName("cache hit short-circuits downstream call")
    void cacheHitShortCircuits() throws Exception {
        var cache = new ResponseCacheInterceptor(Duration.ofMinutes(5));
        var counter = new CallCounter();

        var invocation = invocation(
                new HttpResourceInput("test.svc", Map.of("x", "1")),
                counter, SUCCESS_OUTPUT
        );

        // First call — cache miss
        Object result1 = cache.intercept(invocation);
        assertThat(counter.count).isEqualTo(1);
        assertThat(result1).isEqualTo(SUCCESS_OUTPUT);

        // Second call with same input — cache hit, no downstream call
        var invocation2 = invocation(
                new HttpResourceInput("test.svc", Map.of("x", "1")),
                counter, SUCCESS_OUTPUT
        );
        Object result2 = cache.intercept(invocation2);
        assertThat(counter.count).isEqualTo(1); // still 1
        assertThat(result2).isEqualTo(SUCCESS_OUTPUT);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("only successful outputs are cached")
    void onlySuccessfulOutputsCached() throws Exception {
        var cache = new ResponseCacheInterceptor(Duration.ofMinutes(5));
        var counter = new CallCounter();

        // First call — failure
        var inv1 = invocation(
                new HttpResourceInput("test.svc", Map.of("x", "1")),
                counter, FAILURE_OUTPUT
        );
        cache.intercept(inv1);
        assertThat(counter.count).isEqualTo(1);
        assertThat(cache.size()).isEqualTo(0); // not cached

        // Second call — should call downstream again since failure wasn't cached
        var inv2 = invocation(
                new HttpResourceInput("test.svc", Map.of("x", "1")),
                counter, FAILURE_OUTPUT
        );
        cache.intercept(inv2);
        assertThat(counter.count).isEqualTo(2); // called again
    }

    @Test
    @DisplayName("different inputs produce different cache keys")
    void differentInputsDifferentKeys() throws Exception {
        var cache = new ResponseCacheInterceptor(Duration.ofMinutes(5));
        var counter = new CallCounter();

        cache.intercept(invocation(
                new HttpResourceInput("test.svc", Map.of("x", "1")), counter, SUCCESS_OUTPUT));
        cache.intercept(invocation(
                new HttpResourceInput("test.svc", Map.of("x", "2")), counter, SUCCESS_OUTPUT));

        assertThat(counter.count).isEqualTo(2);
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("non-HttpResourceInput passes through without caching")
    void nonResourceInputPassesThrough() throws Exception {
        var cache = new ResponseCacheInterceptor();
        var counter = new CallCounter();

        var invocation = invocationWithRawInput("plain-string", counter, "result");
        Object result = cache.intercept(invocation);
        assertThat(result).isEqualTo("result");
        assertThat(cache.size()).isEqualTo(0);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private OperatorInvocation invocation(HttpResourceInput input, CallCounter counter, Object response) {
        var graphContext = new GraphContext(new TenantContext("t1", "ns1"));
        var operatorContext = new OperatorContext("cacheNode", "testGraph", graphContext, 0);
        Operator<Object, Object> op = (in, ctx) -> { counter.count++; return response; };
        return new OperatorInvocation(Collections.emptyIterator(), op, input, operatorContext);
    }

    private OperatorInvocation invocationWithRawInput(Object input, CallCounter counter, Object response) {
        var graphContext = new GraphContext(new TenantContext("t1", "ns1"));
        var operatorContext = new OperatorContext("cacheNode", "testGraph", graphContext, 0);
        Operator<Object, Object> op = (in, ctx) -> { counter.count++; return response; };
        return new OperatorInvocation(Collections.emptyIterator(), op, input, operatorContext);
    }

    private static class CallCounter {
        int count = 0;
    }
}
