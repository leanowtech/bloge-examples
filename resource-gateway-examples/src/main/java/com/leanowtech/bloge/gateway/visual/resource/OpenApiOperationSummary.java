package com.leanowtech.bloge.gateway.visual.resource;

import java.util.List;

/**
 * Lightweight OpenAPI operation metadata used before projecting a resource contract.
 *
 * @param operationId OpenAPI operationId, blank when the operation omits one
 * @param path OpenAPI path template
 * @param method HTTP method in uppercase form
 * @param summary short operation summary
 * @param description operation description
 * @param tags operation tags
 * @param hasRequestBody true when the operation declares a request body
 * @param requestMediaTypes declared request body media types
 * @param responseMediaTypes declared 2xx response media types
 * @param projectionLevel READY, WARNING, or BLOCKED for contract projection
 * @param projectionMessage short explanation of the projection level
 */
public record OpenApiOperationSummary(
        String operationId,
        String path,
        String method,
        String summary,
        String description,
        List<String> tags,
        boolean hasRequestBody,
        List<String> requestMediaTypes,
        List<String> responseMediaTypes,
        String projectionLevel,
        String projectionMessage
) {
    /**
     * Canonicalizes nullable fields for a stable wire contract.
     */
    public OpenApiOperationSummary {
        operationId = operationId == null ? "" : operationId;
        path = path == null ? "" : path;
        method = method == null ? "" : method.toUpperCase();
        summary = summary == null ? "" : summary;
        description = description == null ? "" : description;
        tags = tags == null ? List.of() : List.copyOf(tags);
        requestMediaTypes = requestMediaTypes == null ? List.of() : List.copyOf(requestMediaTypes);
        responseMediaTypes = responseMediaTypes == null ? List.of() : List.copyOf(responseMediaTypes);
        projectionLevel = projectionLevel == null || projectionLevel.isBlank()
                ? "READY"
                : projectionLevel.toUpperCase();
        projectionMessage = projectionMessage == null ? "" : projectionMessage;
    }
}
