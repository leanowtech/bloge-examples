package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareReceipt;

/** Exact committed governed derivation and replay metadata. */
public record StandaloneFixtureSetShareResult(FixtureSetView view, FixtureShareReceipt receipt,
                                              String strongEtag, boolean replayed) {
    public StandaloneFixtureSetShareResult {
        if (view == null || receipt == null || !FixtureSetStrongEtag.isValid(strongEtag)
                || !view.fixtureSetId().equals(receipt.fixtureSetId())
                || view.revision() != receipt.revision()
                || !view.fingerprint().equals(receipt.fingerprint())) {
            throw new IllegalArgumentException("standalone Fixture share result is incomplete");
        }
    }
}
