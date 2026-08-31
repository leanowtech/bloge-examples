package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;

/** Exact standalone Fixture save response and replay marker. */
public record StandaloneFixtureSetSaveResult(
        FixtureSetView view, FixtureSetSaveReceipt receipt,
        String strongEtag, boolean replayed) {
    public StandaloneFixtureSetSaveResult {
        if (view == null || receipt == null || !FixtureSetStrongEtag.isValid(strongEtag)
                || !view.fixtureSetId().equals(receipt.fixtureSetId())
                || view.revision() != receipt.revision()
                || !view.fingerprint().equals(receipt.fingerprint())) {
            throw new IllegalArgumentException("standalone Fixture Set save result is invalid");
        }
    }
}
