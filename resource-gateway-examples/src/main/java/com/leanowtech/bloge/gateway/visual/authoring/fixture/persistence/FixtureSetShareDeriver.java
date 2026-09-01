package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareMaterialization;

/** Protected-material callback invoked once under an exact locked private source. */
@FunctionalInterface
public interface FixtureSetShareDeriver {
    /** Derives governed content; implementations must join the store transaction. */
    FixtureShareMaterialization derive(StoredFixtureSet source, int derivedRevision,
                                       int derivedStatusRevision, String reviewRequestId);
}
