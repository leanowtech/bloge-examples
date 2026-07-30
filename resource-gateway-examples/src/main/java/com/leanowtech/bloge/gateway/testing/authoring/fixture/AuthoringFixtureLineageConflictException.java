package com.leanowtech.bloge.gateway.testing.authoring.fixture;

/** Raised when a fixture revision attempts to change the identity of its immutable lineage. */
public final class AuthoringFixtureLineageConflictException extends RuntimeException {

    public AuthoringFixtureLineageConflictException() {
        super("Authoring fixture lineage is immutable");
    }
}
