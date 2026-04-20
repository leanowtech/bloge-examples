package com.leanowtech.bloge.gateway.exception;

/**
 * Thrown when a requested resource identifier cannot be found in the {@code ResourceRegistry}.
 *
 * <p><b>Retry semantics:</b> <em>not</em> retryable — the resource descriptor does not exist.
 * This typically indicates a configuration error or a mistyped resource identifier.
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceId;

    /**
     * @param resourceId the resource identifier that was not found in the registry
     */
    public ResourceNotFoundException(String resourceId) {
        super("Resource descriptor not found: " + resourceId);
        this.resourceId = resourceId;
    }

    public String resourceId() {
        return resourceId;
    }
}
