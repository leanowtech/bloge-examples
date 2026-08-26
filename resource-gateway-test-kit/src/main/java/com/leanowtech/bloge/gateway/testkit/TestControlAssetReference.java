package com.leanowtech.bloge.gateway.testkit;

import java.util.Objects;
import java.util.regex.Pattern;

/** Exact, immutable reference to one governed test-control asset.
 * @param id exact governed asset id
 * @param revision positive immutable asset revision
 * @param fingerprint exact asset fingerprint
 */
public record TestControlAssetReference(String id, long revision, String fingerprint) {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    /** Validates and freezes the exact reference. */
    public TestControlAssetReference {
        id = text(id);
        fingerprint = text(fingerprint);
        if (revision < 1 || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw invalid();
        }
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "invalid control asset reference");
        if (value.isBlank() || value.length() > TestControlProtocolLimits.MAX_STRING_CHARS
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return value.trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid test-control asset reference");
    }
}
