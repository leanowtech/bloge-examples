package com.leanowtech.bloge.gateway.testing.api;

/**
 * Signals that a stored test-run envelope, evidence value, or integrity manifest is inconsistent.
 *
 * <p>The exception is deliberately payload-free. Service boundaries may emit a bounded security
 * event and stable error code without exposing tenant identity, run existence, or sanitized business
 * values through an error message.</p>
 */
public final class TestRunIntegrityException extends RuntimeException {

    /** Creates the common payload-free test-run integrity failure. */
    public TestRunIntegrityException() {
        super("Stored test-run integrity verification failed");
    }

    /** Retains an internal canonicalization cause without changing the public-safe message. */
    public TestRunIntegrityException(Throwable cause) {
        super("Stored test-run integrity verification failed", cause);
    }
}
