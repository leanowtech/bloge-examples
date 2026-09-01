package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;

/** Committed TEAM_AVAILABLE Fixture revision and exact idempotency outcome. */
public record StandaloneFixtureSetReviewResult(
        FixtureSetView view, FixtureReviewReceipt receipt, String strongEtag, boolean replayed) {
    public StandaloneFixtureSetReviewResult {
        if (view == null || receipt == null || strongEtag == null || strongEtag.isBlank()
                || view.status() != FixtureSetView.Status.TEAM_AVAILABLE
                || view.revision() != receipt.revision()
                || !view.fingerprint().equals(receipt.fingerprint())) {
            throw new IllegalArgumentException("Fixture review result is invalid");
        }
    }
}
