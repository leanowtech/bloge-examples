package com.leanowtech.bloge.gateway.testing.authoring.fixture;

/** Encrypted fixture storage or immutable binding failed verification. */
public final class AuthoringFixtureIntegrityException extends RuntimeException {
    public AuthoringFixtureIntegrityException() {
        super("Authoring fixture integrity verification failed");
    }

    public AuthoringFixtureIntegrityException(Throwable cause) {
        super("Authoring fixture integrity verification failed", cause);
    }
}
