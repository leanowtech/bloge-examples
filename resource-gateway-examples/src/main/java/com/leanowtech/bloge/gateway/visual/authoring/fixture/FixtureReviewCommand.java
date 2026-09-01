package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import java.util.regex.Pattern;

/** Frozen reviewer command for activating one exact pending Fixture Set review. */
public record FixtureReviewCommand(String schemaVersion, Source source, Attestations attestations) {
    public static final String SCHEMA_VERSION = "bloge.fixtureReviewCommand.v1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    public FixtureReviewCommand {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion) || source == null || attestations == null) {
            throw new IllegalArgumentException("Fixture review command is invalid");
        }
    }

    /** Exact pending review and Fixture Set source coordinates. */
    public record Source(String reviewRequestId, String fixtureSetId, int revision,
                         String fingerprint, int statusRevision) {
        public Source {
            if (reviewRequestId == null || !IDENTIFIER.matcher(reviewRequestId).matches()
                    || fixtureSetId == null || !IDENTIFIER.matcher(fixtureSetId).matches()
                    || revision < 1 || fingerprint == null
                    || !FINGERPRINT.matcher(fingerprint).matches() || statusRevision < 1) {
                throw new IllegalArgumentException("Fixture review source is invalid");
            }
        }
    }

    /** Explicit reviewer-owned evidence; all three checks are required to publish. */
    public record Attestations(boolean redactionReviewed, boolean schemaValid,
                               boolean redactionVerified, String comment) {
        public Attestations {
            comment = comment == null ? "" : comment.trim();
            if (!redactionReviewed || !schemaValid || !redactionVerified
                    || comment.isEmpty() || comment.length() > 4096) {
                throw new IllegalArgumentException("Fixture review attestations are incomplete");
            }
        }
    }
}
