package com.leanowtech.bloge.gateway.visual.authoring.fixture;

/** Server-derived TEAM_AVAILABLE Fixture revision and its payload-free review receipt. */
public record FixtureReviewMaterialization(
        GeneratedDefaultFixture generated, FixtureReviewReceipt receipt) {
    public FixtureReviewMaterialization {
        if (generated == null || receipt == null
                || generated.view().status() != FixtureSetView.Status.TEAM_AVAILABLE
                || !generated.view().fixtureSetId().equals(receipt.fixtureSetId())
                || generated.view().revision() != receipt.revision()
                || !generated.view().fingerprint().equals(receipt.fingerprint())
                || generated.view().statusRevision() != receipt.statusRevision()) {
            throw new IllegalArgumentException("Fixture review materialization is invalid");
        }
    }
}
