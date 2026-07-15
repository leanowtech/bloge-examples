package com.leanowtech.bloge.gateway.testing.api;

/** Signals an attempt to overwrite an immutable suite revision with different content. */
public final class TestSuiteConflictException extends RuntimeException {
    /** Creates an immutable-revision conflict with a stable diagnostic. */
    public TestSuiteConflictException(String message) {
        super(message);
    }
}
