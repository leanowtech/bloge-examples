package com.leanowtech.bloge.gateway.testing.api;

/**
 * Signals that a stored test-suite envelope no longer matches its canonical suite content.
 *
 * <p>The exception deliberately carries no suite identifier, fingerprint, or business input.
 * Authorized service boundaries may emit a bounded security event and map it to a stable public
 * error without turning storage corruption into a payload or existence oracle.</p>
 */
public final class TestSuiteIntegrityException extends RuntimeException {

    /** Creates the payload-free integrity failure used by all suite consumers. */
    public TestSuiteIntegrityException() {
        super("Stored test-suite integrity verification failed");
    }

    /** Creates the payload-free integrity failure while retaining an internal encoding cause. */
    public TestSuiteIntegrityException(Throwable cause) {
        super("Stored test-suite integrity verification failed", cause);
    }
}
