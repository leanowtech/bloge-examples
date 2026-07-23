package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonPointer;

import java.util.regex.Pattern;

/** Shared strict scalar validation for stateful mirror protocol records. */
final class MirrorStateProtocolSupport {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern ERROR_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,191}");

    private MirrorStateProtocolSupport() {
    }

    static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }

    static String optionalFingerprint(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isEmpty() && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or a canonical SHA-256 value");
        }
        return normalized;
    }

    static String nonRootPointer(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.startsWith("/") || normalized.endsWith("/")) {
            throw new IllegalArgumentException(field + " must be a non-root JSON Pointer");
        }
        try {
            JsonPointer.compile(normalized);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " must be a non-root JSON Pointer");
        }
        return normalized;
    }

    static String errorCode(String value) {
        String normalized = required(value, "errorCode");
        if (!ERROR_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("errorCode is invalid");
        }
        return normalized;
    }
}
