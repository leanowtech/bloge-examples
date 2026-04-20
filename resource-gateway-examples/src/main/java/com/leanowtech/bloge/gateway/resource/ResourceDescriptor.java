package com.leanowtech.bloge.gateway.resource;

import com.leanowtech.bloge.operators.http.HttpRequestInput.HttpAuth;

import java.time.Duration;
import java.util.Map;

/**
 * Immutable descriptor for an external HTTP resource (API endpoint).
 *
 * <p>A descriptor is registered once in the {@link ResourceRegistry} and then looked up
 * by {@code resourceId} at call time. It captures everything the gateway needs to construct,
 * send, and interpret an HTTP request — URL template, method, headers, authentication,
 * timeout, parameter mapping, response protocol, and payload extraction path.
 *
 * @param resourceId       unique logical identifier (e.g. "user-service.getProfile")
 * @param urlTemplate      URL with {@code {placeholder}} path variables
 *                         (e.g. "https://{host}/users/{userId}/profile")
 * @param method           HTTP method — GET, POST, PUT, or DELETE
 * @param defaultHeaders   headers to include on every request (may be overridden per-call)
 * @param authStrategy     authentication strategy (Bearer, Basic, or API key), or {@code null}
 * @param defaultTimeout   default request timeout; may be overridden per-call
 * @param parameterMapping expressions mapping operator input to path/query/body parameters
 * @param responseProtocol how to determine success or failure from the HTTP response
 * @param payloadPath      dot-notation JSON path to extract the payload from the response body
 *                         (e.g. "data" or "result.items"); {@code null} means use the full body
 */
public record ResourceDescriptor(
    String resourceId,
    String urlTemplate,
    String method,
    Map<String, String> defaultHeaders,
    HttpAuth authStrategy,
    Duration defaultTimeout,
    ParameterMapping parameterMapping,
    ResponseProtocol responseProtocol,
    String payloadPath
) {
    public ResourceDescriptor {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId must not be blank");
        }
        if (urlTemplate == null || urlTemplate.isBlank()) {
            throw new IllegalArgumentException("urlTemplate must not be blank");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        method = method.toUpperCase();
        defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
        if (defaultTimeout == null) {
            defaultTimeout = Duration.ofSeconds(30);
        }
        if (parameterMapping == null) {
            parameterMapping = ParameterMapping.empty();
        }
        if (responseProtocol == null) {
            responseProtocol = new ResponseProtocol.HttpStatus();
        }
    }
}
