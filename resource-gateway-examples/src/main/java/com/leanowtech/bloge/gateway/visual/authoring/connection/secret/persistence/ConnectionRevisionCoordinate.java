package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import java.util.Objects;
import java.util.regex.Pattern;

/** Exact authority coordinate for one connection revision. */
public record ConnectionRevisionCoordinate(AuthoringScope scope, String connectionId, long revision) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    /** Validates the immutable scope and revision coordinate. */
    public ConnectionRevisionCoordinate {
        Objects.requireNonNull(scope, "scope");
        if (connectionId == null || !IDENTIFIER.matcher(connectionId).matches()) {
            throw new IllegalArgumentException("connectionId is invalid");
        }
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
    }

    /** Keeps this non-secret coordinate easy to inspect without exposing any lease material. */
    @Override public String toString() {
        return "ConnectionRevisionCoordinate[scope=" + scope + ", connectionId=" + connectionId
                + ", revision=" + revision + "]";
    }
}
