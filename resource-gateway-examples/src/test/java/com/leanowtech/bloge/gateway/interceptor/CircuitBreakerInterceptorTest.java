package com.leanowtech.bloge.gateway.interceptor;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.OperatorInvocation;
import com.leanowtech.bloge.gateway.exception.CircuitOpenException;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Component tests for {@link CircuitBreakerInterceptor}.
 */
class CircuitBreakerInterceptorTest {

    private static final HttpResourceOutput SUCCESS_OUTPUT = new HttpResourceOutput(
            "test.svc", 200, Map.of(), "{}", Duration.ofMillis(10), true
    );

    @Test
    @DisplayName("repeated failures open the circuit")
    void repeatedFailuresOpenCircuit() throws Exception {
        int threshold = 3;
        var breaker = new CircuitBreakerInterceptor(threshold, Duration.ofSeconds(60));
        var input = new HttpResourceInput("breaker.method", Map.of());

        // Trigger threshold failures
        for (int i = 0; i < threshold; i++) {
            try {
                breaker.intercept(failingInvocation(input));
            } catch (RuntimeException ignored) {
                // expected
            }
        }

        // Next call should be rejected by the open circuit
        assertThatThrownBy(() -> breaker.intercept(successInvocation(input)))
                .isInstanceOf(CircuitOpenException.class)
                .satisfies(ex -> {
                    var coe = (CircuitOpenException) ex;
                    assertThat(coe.resourceId()).isEqualTo("breaker.method");
                });
    }

    @Test
    @DisplayName("open circuit rejects without invoking downstream")
    void openCircuitRejects() throws Exception {
        int threshold = 2;
        var breaker = new CircuitBreakerInterceptor(threshold, Duration.ofSeconds(60));
        var input = new HttpResourceInput("reject.method", Map.of());
        var counter = new AtomicInteger(0);

        // Open the circuit
        for (int i = 0; i < threshold; i++) {
            try {
                breaker.intercept(failingInvocation(input));
            } catch (RuntimeException ignored) {}
        }

        // Attempt with a counting operator — should not be reached
        var invocation = countingInvocation(input, counter);
        assertThatThrownBy(() -> breaker.intercept(invocation))
                .isInstanceOf(CircuitOpenException.class);
        assertThat(counter.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("half-open recovery after cooldown expiry")
    void halfOpenRecovery() throws Exception {
        // Use a very short cooldown so the circuit transitions to half-open quickly
        var breaker = new CircuitBreakerInterceptor(2, Duration.ofMillis(50));
        var input = new HttpResourceInput("recovery.method", Map.of());

        // Open the circuit
        for (int i = 0; i < 2; i++) {
            try {
                breaker.intercept(failingInvocation(input));
            } catch (RuntimeException ignored) {}
        }

        // Wait for cooldown
        Thread.sleep(100);

        // The circuit should now be half-open; a successful probe should close it
        Object result = breaker.intercept(successInvocation(input));
        assertThat(result).isEqualTo(SUCCESS_OUTPUT);

        // Subsequent calls should succeed (circuit closed)
        Object result2 = breaker.intercept(successInvocation(input));
        assertThat(result2).isEqualTo(SUCCESS_OUTPUT);
    }

    @Test
    @DisplayName("successes keep circuit closed")
    void successesKeepCircuitClosed() throws Exception {
        var breaker = new CircuitBreakerInterceptor(5, Duration.ofSeconds(60));
        var input = new HttpResourceInput("ok.method", Map.of());

        for (int i = 0; i < 10; i++) {
            Object result = breaker.intercept(successInvocation(input));
            assertThat(result).isEqualTo(SUCCESS_OUTPUT);
        }
    }

    @Test
    @DisplayName("non-HttpResourceInput passes through without circuit breaking")
    void nonResourceInputPassesThrough() throws Exception {
        var breaker = new CircuitBreakerInterceptor();
        var graphContext = new GraphContext(new TenantContext("t1", "ns1"));
        var opCtx = new OperatorContext("n1", "g1", graphContext, 0);
        Operator<Object, Object> op = (in, ctx) -> "pass";
        var invocation = new OperatorInvocation(Collections.emptyIterator(), op, "not-resource", opCtx);

        assertThat(breaker.intercept(invocation)).isEqualTo("pass");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private OperatorInvocation successInvocation(HttpResourceInput input) {
        var graphContext = new GraphContext(new TenantContext("t1", "ns1"));
        var opCtx = new OperatorContext("n1", "g1", graphContext, 0);
        Operator<Object, Object> op = (in, ctx) -> SUCCESS_OUTPUT;
        return new OperatorInvocation(Collections.emptyIterator(), op, input, opCtx);
    }

    private OperatorInvocation failingInvocation(HttpResourceInput input) {
        var graphContext = new GraphContext(new TenantContext("t1", "ns1"));
        var opCtx = new OperatorContext("n1", "g1", graphContext, 0);
        Operator<Object, Object> op = (in, ctx) -> { throw new RuntimeException("boom"); };
        return new OperatorInvocation(Collections.emptyIterator(), op, input, opCtx);
    }

    private OperatorInvocation countingInvocation(HttpResourceInput input, AtomicInteger counter) {
        var graphContext = new GraphContext(new TenantContext("t1", "ns1"));
        var opCtx = new OperatorContext("n1", "g1", graphContext, 0);
        Operator<Object, Object> op = (in, ctx) -> { counter.incrementAndGet(); return SUCCESS_OUTPUT; };
        return new OperatorInvocation(Collections.emptyIterator(), op, input, opCtx);
    }
}
