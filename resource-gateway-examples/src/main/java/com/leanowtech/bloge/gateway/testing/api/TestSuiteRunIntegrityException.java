package com.leanowtech.bloge.gateway.testing.api;

/**
 * Signals an inconsistent suite-run envelope, aggregate evidence value, or attestation.
 *
 * <p>The message is deliberately stable and payload-free so callers can audit the rejection
 * without exposing tenant identity, run existence, or business values.</p>
 */
public final class TestSuiteRunIntegrityException extends IllegalArgumentException {

    private static final String MESSAGE =
            "Suite-run persistence requires a structurally valid signed attestation";

    /** Creates the common payload-free suite-run integrity failure. */
    public TestSuiteRunIntegrityException() {
        super(MESSAGE);
    }

    /** Retains an internal canonicalization cause without changing the safe message. */
    public TestSuiteRunIntegrityException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
