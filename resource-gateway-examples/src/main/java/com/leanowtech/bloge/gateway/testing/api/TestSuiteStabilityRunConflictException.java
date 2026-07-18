package com.leanowtech.bloge.gateway.testing.api;

/** Raised when a stability id or scoped parent idempotency key already exists. */
public final class TestSuiteStabilityRunConflictException extends RuntimeException {
    /** @param message bounded persistence conflict description */
    public TestSuiteStabilityRunConflictException(String message) {
        super(message);
    }
}
