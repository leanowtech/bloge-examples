package com.leanowtech.bloge.gateway.testing.correctness.fixture;

/**
 * Bounded metadata-only attestation used to verify a proposed Fixture before approval.
 *
 * <p>The request deliberately contains no material, lifecycle, owner, or reference fields;
 * those values remain server-owned and are copied from the exact catalog head.</p>
 *
 * @param redactionReviewed explicit reviewer acknowledgement of redaction review
 * @param redactionVerified explicit reviewer acknowledgement that redaction was verified
 * @param comment bounded human-readable review evidence
 */
public record FixtureReviewVerificationRequest(
        boolean redactionReviewed,
        boolean redactionVerified,
        String comment) {

    /** Maximum review comment size accepted by the metadata command. */
    public static final int MAX_COMMENT_LENGTH = 4096;

    public FixtureReviewVerificationRequest {
        comment = comment == null ? "" : comment.trim();
    }

    /** Rejects incomplete attestations before any catalog mutation is attempted. */
    public void requireComplete() {
        if (!redactionReviewed || !redactionVerified || comment.isBlank()
                || comment.length() > MAX_COMMENT_LENGTH) {
            throw new FixtureCatalogCommandException(
                    "RG.CORRECTNESS.FIXTURE_REVIEW_INVALID",
                    "Fixture review requires redaction acknowledgements and a bounded comment");
        }
    }
}
