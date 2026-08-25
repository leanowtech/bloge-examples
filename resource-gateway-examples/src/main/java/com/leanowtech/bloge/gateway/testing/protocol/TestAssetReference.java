package com.leanowtech.bloge.gateway.testing.protocol;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable reference to a governed scenario or world-model revision. */
public record TestAssetReference(String id, long revision, String fingerprint) {
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[0-9a-f]{64}");

    public TestAssetReference {
        id = requireText(id);
        fingerprint = requireText(fingerprint);
        if (revision <= 0) {
            throw new IllegalArgumentException("invalid asset reference");
        }
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("invalid asset reference");
        }
    }

    private static String requireText(String value) {
        Objects.requireNonNull(value, "asset reference value is required");
        if (value.isBlank() || value.codePointCount(0, value.length()) > TestControlProtocolLimits.MAX_STRING_CHARS) {
            throw new IllegalArgumentException("invalid asset reference");
        }
        return value;
    }
}
