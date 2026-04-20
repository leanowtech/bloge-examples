package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.operators.http.HttpRequestInput.HttpAuth;

import java.time.Duration;
import java.util.Map;

/**
 * JSON request body for the unified resource-execution endpoint.
 *
 * <p>Carries everything the gateway needs to dispatch a single resource call through
 * BLOGE's graph engine: the target resource, call parameters, and optional overrides
 * for headers, authentication, and timeout.
 *
 * @param resourceId      the logical resource identifier to resolve from the registry
 * @param params          parameters available to parameter mapping expressions
 * @param headerOverrides additional headers that override the descriptor's defaults
 * @param authOverride    optional authentication override; takes precedence over the
 *                        descriptor's stored auth strategy when non-{@code null}
 * @param timeoutOverride per-call timeout override in ISO-8601 duration format;
 *                        {@code null} means use the descriptor's default
 */
public record ResourceExecuteRequest(
    String resourceId,
    Map<String, Object> params,
    Map<String, String> headerOverrides,
    HttpAuth authOverride,
    Duration timeoutOverride
) {
    public ResourceExecuteRequest {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId must not be blank");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
        headerOverrides = headerOverrides == null ? Map.of() : Map.copyOf(headerOverrides);
    }
}
