package com.leanowtech.bloge.gateway.visual.authoring.connection;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.Objects;

/** Opaque, scope-bound handle returned by a future Secret Store adapter. */
public record SecretReference(AuthoringScope scope, String ref) {
    public SecretReference {
        Objects.requireNonNull(scope, "scope");
        if (ref == null || !ref.matches("^vault://[A-Za-z0-9][A-Za-z0-9._:/~-]*$")) {
            throw new IllegalArgumentException("secret reference is invalid");
        }
    }

    /** Never put the vault handle in an exception, log or diagnostic. */
    @Override
    public String toString() {
        return "SecretReference[REDACTED]";
    }
}
