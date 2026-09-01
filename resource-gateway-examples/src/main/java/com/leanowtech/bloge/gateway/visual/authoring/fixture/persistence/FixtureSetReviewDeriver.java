package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewMaterialization;

/** Transaction-scoped callback invoked only after replay and exact pending-review CAS close. */
@FunctionalInterface
public interface FixtureSetReviewDeriver {
    FixtureReviewMaterialization derive(
            StoredFixtureSet source, int revision, int statusRevision);
}
