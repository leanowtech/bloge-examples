package com.leanowtech.bloge.gateway.example;

import java.util.Map;

/**
 * Browser execution recipe for one resource-gateway example scenario.
 *
 * <p>The run recipe lets the static showcase call the existing public gateway
 * endpoints without introducing a parallel execution API. Path placeholders are
 * resolved from the scenario's sample input in the browser.</p>
 *
 * @param mode execution mode: {@code request}, {@code post}, or {@code stream}
 * @param method HTTP method used for non-streaming calls
 * @param pathTemplate URL path template with placeholders such as {@code {userId}}
 * @param bodyTemplate optional POST body template
 * @param headers optional HTTP headers
 */
public record GatewayExampleRun(
        String mode,
        String method,
        String pathTemplate,
        Map<String, Object> bodyTemplate,
        Map<String, String> headers
) {
    /**
     * Creates a run recipe.
     */
    public GatewayExampleRun {
        mode = (mode == null || mode.isBlank()) ? "request" : mode;
        method = (method == null || method.isBlank()) ? "GET" : method;
        if (pathTemplate == null || pathTemplate.isBlank()) {
            throw new IllegalArgumentException("pathTemplate must not be blank");
        }
        bodyTemplate = bodyTemplate == null ? Map.of() : Map.copyOf(bodyTemplate);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
