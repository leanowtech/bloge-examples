package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

final class SecretValidation {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,127}");
    private static final Pattern SCOPE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:/._-]{0,127}");
    private SecretValidation() { }

    static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a valid identifier");
        }
        return value;
    }

    static String scope(String value) {
        if (value == null || !SCOPE.matcher(value).matches()) {
            throw new IllegalArgumentException("scope must be valid");
        }
        return value;
    }

    static String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + max + " characters");
        }
        return value;
    }

    static Instant expiry(Instant value) {
        Objects.requireNonNull(value, "expiry");
        if (!value.isAfter(Instant.now())) throw new IllegalArgumentException("leaseUntil must be in the future");
        return value;
    }
}
