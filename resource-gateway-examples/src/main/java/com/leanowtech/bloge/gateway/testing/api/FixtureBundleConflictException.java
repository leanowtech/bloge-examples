package com.leanowtech.bloge.gateway.testing.api;

/** Signals an attempt to overwrite an immutable fixture revision with different content. */
public final class FixtureBundleConflictException extends RuntimeException {
    public FixtureBundleConflictException(String message) {
        super(message);
    }
}
