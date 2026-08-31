package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

/** Committed standalone Fixture revision together with its opaque strong validator. */
public record StoredStandaloneFixtureSet(StoredFixtureSet stored, String strongEtag) {
    public StoredStandaloneFixtureSet {
        if (stored == null || !FixtureSetStrongEtag.isValid(strongEtag)) {
            throw new IllegalArgumentException("stored standalone Fixture Set is incomplete");
        }
    }
}
