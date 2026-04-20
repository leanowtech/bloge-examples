package com.leanowtech.bloge.gateway.operator;

import com.leanowtech.bloge.operators.http.HttpRequestInput.HttpAuth;

import java.time.Duration;
import java.util.Map;

/**
 * Input record for the {@link HttpResourceOperator}.
 *
 * <p>Specifies which resource to call (via {@code resourceId} lookup in the registry),
 * parameters for expression evaluation, optional header overrides, an optional
 * authentication override, and an optional timeout override.
 *
 * @param resourceId      the logical resource identifier to resolve from the registry
 * @param params          parameters available to parameter mapping expressions
 * @param headerOverrides additional headers that override the descriptor's defaults
 * @param authOverride    per-call authentication override; when non-{@code null}, takes
 *                        precedence over the descriptor's stored {@code authStrategy}
 * @param timeoutOverride per-call timeout; {@code null} means use the descriptor's default
 */
public record HttpResourceInput(
    String resourceId,
    Map<String, Object> params,
    Map<String, String> headerOverrides,
    HttpAuth authOverride,
    Duration timeoutOverride
) {
    public HttpResourceInput {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId must not be blank");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
        headerOverrides = headerOverrides == null ? Map.of() : Map.copyOf(headerOverrides);
    }

    /**
     * Convenience constructor for a simple call with only parameters.
     *
     * @param resourceId the resource to call
     * @param params     parameters for expression evaluation
     */
    public HttpResourceInput(String resourceId, Map<String, Object> params) {
        this(resourceId, params, Map.of(), null, null);
    }

    /**
     * Convenience constructor without auth override (backward compatible).
     *
     * @param resourceId      the resource to call
     * @param params          parameters for expression evaluation
     * @param headerOverrides additional per-call header overrides
     * @param timeoutOverride per-call timeout override
     */
    public HttpResourceInput(String resourceId, Map<String, Object> params,
                             Map<String, String> headerOverrides, Duration timeoutOverride) {
        this(resourceId, params, headerOverrides, null, timeoutOverride);
    }
}
