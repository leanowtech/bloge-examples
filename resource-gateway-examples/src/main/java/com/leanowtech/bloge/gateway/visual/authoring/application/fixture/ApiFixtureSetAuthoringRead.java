package com.leanowtech.bloge.gateway.visual.authoring.application.fixture;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetStrongEtag;

/** Exact Fixture read plus the optional validator that grants independent editing. */
public record ApiFixtureSetAuthoringRead(FixtureSetView view, String strongEtag) {
    /** Parent-governed Fixtures intentionally omit a validator and remain read-only. */
    public ApiFixtureSetAuthoringRead {
        if (view == null || strongEtag != null && !FixtureSetStrongEtag.isValid(strongEtag)) {
            throw new IllegalArgumentException("Fixture read authority is incomplete");
        }
    }
}
