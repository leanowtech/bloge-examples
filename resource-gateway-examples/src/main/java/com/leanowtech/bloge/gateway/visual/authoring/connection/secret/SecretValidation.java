package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import java.util.regex.Pattern;

final class SecretValidation {
    /** Same bounded identifier grammar as the authoring V001-V003 schemas. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private SecretValidation() { }

    static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a valid identifier");
        }
        return value;
    }

    static String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + max + " characters");
        }
        return value;
    }
}
