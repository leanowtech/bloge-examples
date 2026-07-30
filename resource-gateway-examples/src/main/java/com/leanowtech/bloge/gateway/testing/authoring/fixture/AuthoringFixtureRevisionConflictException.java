package com.leanowtech.bloge.gateway.testing.authoring.fixture;

/** Exact fixture revision fence did not match the repository head. */
public final class AuthoringFixtureRevisionConflictException extends RuntimeException {
    private final long currentRevision;

    public AuthoringFixtureRevisionConflictException(long currentRevision) {
        super("Authoring fixture revision is stale");
        this.currentRevision = currentRevision;
    }

    public long currentRevision() {
        return currentRevision;
    }
}
