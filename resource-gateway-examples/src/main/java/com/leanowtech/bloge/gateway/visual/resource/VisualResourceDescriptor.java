package com.leanowtech.bloge.gateway.visual.resource;

import java.time.Duration;
import java.util.Map;

/**
 * Visual-owned HTTP resource descriptor shape used by catalog projection and OpenAPI previews.
 *
 * <p>This record mirrors the fields the canvas needs to present a resource-backed operator while
 * avoiding a dependency on the resource-gateway runtime descriptor type. Runtime-specific code
 * performs the final translation through an adapter.</p>
 *
 * @param resourceId unique logical resource identifier
 * @param urlTemplate URL template with path placeholders
 * @param method HTTP method
 * @param defaultHeaders headers suggested for every request
 * @param authStrategy optional visual authentication descriptor
 * @param defaultTimeout default request timeout
 * @param parameterMapping expressions mapping operator input to request parts
 * @param responseProtocol response success/payload interpretation strategy
 * @param payloadPath optional dot-path for extracting the response payload
 */
public record VisualResourceDescriptor(
        String resourceId,
        String urlTemplate,
        String method,
        Map<String, String> defaultHeaders,
        VisualResourceAuth authStrategy,
        Duration defaultTimeout,
        VisualResourceParameterMapping parameterMapping,
        VisualResourceResponseProtocol responseProtocol,
        String payloadPath
) {
    public VisualResourceDescriptor {
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
        defaultTimeout = defaultTimeout == null ? Duration.ofSeconds(30) : defaultTimeout;
        parameterMapping = parameterMapping == null ? VisualResourceParameterMapping.empty() : parameterMapping;
        responseProtocol = responseProtocol == null ? new VisualResourceResponseProtocol.HttpStatus()
                : responseProtocol;
    }
}
