package com.leanowtech.bloge.gateway.exception;

/**
 * Thrown when the circuit breaker for a resource is in the <em>open</em> state,
 * meaning recent calls have failed frequently enough to trip the breaker.
 *
 * <p><b>Retry semantics:</b> do <em>not</em> retry immediately. Callers should
 * use a fallback value or wait until the circuit transitions to half-open.
 */
public class CircuitOpenException extends RuntimeException {

    private final String resourceId;

    /**
     * @param resourceId the resource whose circuit breaker is open
     */
    public CircuitOpenException(String resourceId) {
        super("Circuit breaker open for resource: " + resourceId);
        this.resourceId = resourceId;
    }

    /**
     * @param resourceId the resource whose circuit breaker is open
     * @param cause      the underlying failure that triggered the circuit breaker
     */
    public CircuitOpenException(String resourceId, Throwable cause) {
        super("Circuit breaker open for resource: " + resourceId, cause);
        this.resourceId = resourceId;
    }

    public String resourceId() {
        return resourceId;
    }
}
