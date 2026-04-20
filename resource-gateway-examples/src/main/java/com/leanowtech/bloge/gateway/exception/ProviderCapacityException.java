package com.leanowtech.bloge.gateway.exception;

/**
 * Thrown when the upstream provider is at capacity (e.g. HTTP 503 Service Unavailable).
 *
 * <p><b>Retry semantics:</b> <em>may</em> retry with exponential back-off.
 * The provider is temporarily unable to serve the request, but subsequent attempts may succeed.
 */
public class ProviderCapacityException extends RuntimeException {

    private final String resourceId;
    private final String provider;
    private final int statusCode;

    /**
     * @param resourceId the logical resource identifier
     * @param provider   the upstream provider or host that reported capacity exhaustion
     * @param statusCode the HTTP status code (typically 503 or 429 from the provider)
     */
    public ProviderCapacityException(String resourceId, String provider, int statusCode) {
        super("Provider '%s' at capacity for resource '%s' (HTTP %d)".formatted(provider, resourceId, statusCode));
        this.resourceId = resourceId;
        this.provider = provider;
        this.statusCode = statusCode;
    }

    public String resourceId() {
        return resourceId;
    }

    public String provider() {
        return provider;
    }

    public int statusCode() {
        return statusCode;
    }
}
