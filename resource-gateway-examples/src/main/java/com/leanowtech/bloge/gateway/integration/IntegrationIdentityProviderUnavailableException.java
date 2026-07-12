package com.leanowtech.bloge.gateway.integration;

/** Signals that credential validity cannot be decided because the trusted identity authority is unavailable. */
public final class IntegrationIdentityProviderUnavailableException extends RuntimeException {
    public IntegrationIdentityProviderUnavailableException(String message) {
        super(message);
    }

    public IntegrationIdentityProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
