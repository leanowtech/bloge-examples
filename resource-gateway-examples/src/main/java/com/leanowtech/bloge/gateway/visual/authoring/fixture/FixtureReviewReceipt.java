package com.leanowtech.bloge.gateway.visual.authoring.fixture;

/** Payload-free receipt for one completed Fixture Set review. */
public record FixtureReviewReceipt(
        String schemaVersion, String reviewRequestId, String fixtureSetId,
        int derivedFromRevision, int revision, String fingerprint,
        FixtureSetView.Status status, int statusRevision, int activatedAssetCount) {
    public static final String SCHEMA_VERSION = "bloge.fixtureReviewReceipt.v1";

    public FixtureReviewReceipt {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion) || reviewRequestId == null
                || reviewRequestId.isBlank() || fixtureSetId == null || fixtureSetId.isBlank()
                || derivedFromRevision < 1 || revision <= derivedFromRevision
                || fingerprint == null || !fingerprint.matches("sha256:[0-9a-f]{64}")
                || status != FixtureSetView.Status.TEAM_AVAILABLE || statusRevision < 1
                || activatedAssetCount < 1) {
            throw new IllegalArgumentException("Fixture review receipt is invalid");
        }
    }
}
