package com.leanowtech.bloge.gateway.exception;

/**
 * Thrown when a resource call completes but the response indicates a business-level failure.
 *
 * <p>This covers cases such as a non-zero error code in the response body, a failed boolean
 * success flag, or a custom expression evaluating to {@code false}.
 *
 * <p><b>Retry semantics:</b> generally <em>not</em> retryable — the remote service processed
 * the request and explicitly returned a failure status. Callers should inspect
 * {@link #statusCode()} and {@link #errorMessage()} for details.
 */
public class ResourceCallException extends RuntimeException {

    private final String resourceId;
    private final int statusCode;
    private final String errorMessage;
    private final String rawBody;

    /**
     * @param resourceId   the logical resource identifier (e.g. "user-service.getProfile")
     * @param statusCode   the HTTP status code returned by the provider
     * @param errorMessage the error message extracted from the response body
     * @param rawBody      the raw response body for diagnostic purposes
     */
    public ResourceCallException(String resourceId, int statusCode, String errorMessage, String rawBody) {
        super("Resource call failed [%s]: HTTP %d — %s".formatted(resourceId, statusCode, errorMessage));
        this.resourceId = resourceId;
        this.statusCode = statusCode;
        this.errorMessage = errorMessage;
        this.rawBody = rawBody;
    }

    public String resourceId() {
        return resourceId;
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public String rawBody() {
        return rawBody;
    }
}
