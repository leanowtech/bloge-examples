package com.leanowtech.bloge.gateway.visual.simulation;

import java.util.regex.Pattern;

/** Payload-free exact identity of an ACTIVE governed Fixture usable by simulation. */
public record GovernedFixtureRef(String fixtureAssetId, long revision, String schemaFingerprint) {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    /** Creates an exact governed Fixture reference. */
    public GovernedFixtureRef {
        fixtureAssetId = required(fixtureAssetId, "fixtureAssetId");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        schemaFingerprint = required(schemaFingerprint, "schemaFingerprint");
        if (!FINGERPRINT.matcher(schemaFingerprint).matches()) {
            throw new IllegalArgumentException("schemaFingerprint must be an exact sha256 fingerprint");
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
