package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import java.util.regex.Pattern;

/** Payload-free receipt for one governed Fixture Set derivation. */
public record FixtureShareReceipt(String schemaVersion, String fixtureSetId, int derivedFromRevision,
                                  int revision, String fingerprint, FixtureSetView.Status status,
                                  int statusRevision, String reviewRequestId) {
    public static final String SCHEMA_VERSION = "bloge.fixtureShareReceipt.v1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    public FixtureShareReceipt {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion) || fixtureSetId == null
                || !IDENTIFIER.matcher(fixtureSetId).matches() || derivedFromRevision < 1
                || revision <= derivedFromRevision || fingerprint == null
                || !FINGERPRINT.matcher(fingerprint).matches()
                || status != FixtureSetView.Status.SHARING_PENDING || statusRevision < 1
                || reviewRequestId == null || !IDENTIFIER.matcher(reviewRequestId).matches()) {
            throw new IllegalArgumentException("Fixture share receipt is incomplete");
        }
    }
}
