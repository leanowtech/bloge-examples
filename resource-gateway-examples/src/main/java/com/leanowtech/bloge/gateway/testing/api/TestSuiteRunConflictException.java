package com.leanowtech.bloge.gateway.testing.api;

/** Raised when a scoped suite-run idempotency key already belongs to another request intent. */
public final class TestSuiteRunConflictException extends RuntimeException {
    /** Creates a conflict with a bounded persistence diagnostic. */
    public TestSuiteRunConflictException(String message) {
        super(message);
    }
}
