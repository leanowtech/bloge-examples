package com.leanowtech.bloge.gateway.interceptor;

import com.leanowtech.bloge.core.spi.OperatorInterceptor;
import com.leanowtech.bloge.core.spi.OperatorInvocation;
import com.leanowtech.bloge.gateway.exception.CircuitOpenException;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link OperatorInterceptor} implementing a provider-scoped circuit breaker with
 * a classic three-state machine: {@code CLOSED → OPEN → HALF_OPEN}.
 *
 * <h3>State transitions</h3>
 * <ul>
 *   <li><b>CLOSED</b> — requests flow normally. Consecutive failures are counted; when the
 *       count reaches {@link #failureThreshold}, the circuit opens.</li>
 *   <li><b>OPEN</b> — all requests are rejected immediately with a {@link CircuitOpenException}.
 *       After {@link #coolDown} elapses, the circuit transitions to HALF_OPEN.</li>
 *   <li><b>HALF_OPEN</b> — a single probe request is allowed through. If it succeeds, the
 *       circuit closes and the failure counter resets. If it fails, the circuit reopens.</li>
 * </ul>
 *
 * <h3>Provider derivation</h3>
 * <p>The provider name is derived from the {@link HttpResourceInput#resourceId()} prefix
 * before the first {@code '.'} character, identical to the rate-limiter strategy.
 *
 * <p>Only applies to operators whose input is {@link HttpResourceInput}; all other
 * operators pass through unintercepted.
 *
 * <p>This is an example-oriented, in-memory implementation. A production system would
 * externalise circuit state to a shared store (Redis, Consul) for multi-instance
 * consistency.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CircuitBreakerInterceptor implements OperatorInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerInterceptor.class);

    private final int failureThreshold;
    private final Duration coolDown;

    private final ConcurrentHashMap<String, CircuitState> circuits = new ConcurrentHashMap<>();

    /**
     * Creates a circuit breaker with default thresholds (5 consecutive failures,
     * 30-second cool-down).
     */
    public CircuitBreakerInterceptor() {
        this(5, Duration.ofSeconds(30));
    }

    /**
     * Creates a circuit breaker with custom thresholds.
     *
     * @param failureThreshold number of consecutive failures before opening the circuit
     * @param coolDown         how long the circuit stays open before transitioning to half-open
     */
    public CircuitBreakerInterceptor(int failureThreshold, Duration coolDown) {
        this.failureThreshold = failureThreshold;
        this.coolDown = coolDown;
    }

    /**
     * Intercepts operator invocations to enforce circuit-breaker logic.
     *
     * @param invocation the operator invocation context
     * @return the operator result if the circuit allows the request
     * @throws CircuitOpenException if the circuit is open and still cooling down
     * @throws Exception            if the downstream operator or interceptor fails
     */
    @Override
    public Object intercept(OperatorInvocation invocation) throws Exception {
        if (!(invocation.input() instanceof HttpResourceInput input)) {
            return invocation.proceed();
        }

        String provider = TenantRateLimiterInterceptor.extractProvider(input.resourceId());
        CircuitState state = circuits.computeIfAbsent(provider, _ -> new CircuitState());

        switch (state.phase()) {
            case OPEN -> {
                if (state.coolDownExpired(coolDown)) {
                    state.transitionToHalfOpen();
                    log.info("Circuit for provider '{}' moved to HALF_OPEN", provider);
                } else {
                    throw new CircuitOpenException(input.resourceId());
                }
            }
            case HALF_OPEN -> {
                // Allow the probe request — fall through
            }
            case CLOSED -> {
                // Normal flow
            }
        }

        try {
            Object result = invocation.proceed();
            state.recordSuccess();
            if (state.phase() == Phase.HALF_OPEN) {
                state.transitionToClosed();
                log.info("Circuit for provider '{}' closed after successful probe", provider);
            }
            return result;
        } catch (Exception ex) {
            state.recordFailure();
            if (state.consecutiveFailures() >= failureThreshold) {
                state.transitionToOpen();
                log.warn("Circuit for provider '{}' opened after {} consecutive failures",
                        provider, failureThreshold);
            }
            throw ex;
        }
    }

    // ── State machine ───────────────────────────────────────────────────

    enum Phase { CLOSED, OPEN, HALF_OPEN }

    /**
     * Mutable circuit state for a single provider. Synchronized on the instance
     * to avoid races between concurrent requests — acceptable for an example project.
     */
    static final class CircuitState {
        private Phase phase = Phase.CLOSED;
        private int consecutiveFailures;
        private Instant openedAt;

        synchronized Phase phase() {
            return phase;
        }

        synchronized int consecutiveFailures() {
            return consecutiveFailures;
        }

        synchronized boolean coolDownExpired(Duration coolDown) {
            return openedAt != null && Instant.now().isAfter(openedAt.plus(coolDown));
        }

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
        }

        synchronized void recordFailure() {
            consecutiveFailures++;
        }

        synchronized void transitionToOpen() {
            phase = Phase.OPEN;
            openedAt = Instant.now();
        }

        synchronized void transitionToHalfOpen() {
            phase = Phase.HALF_OPEN;
        }

        synchronized void transitionToClosed() {
            phase = Phase.CLOSED;
            consecutiveFailures = 0;
            openedAt = null;
        }
    }
}
