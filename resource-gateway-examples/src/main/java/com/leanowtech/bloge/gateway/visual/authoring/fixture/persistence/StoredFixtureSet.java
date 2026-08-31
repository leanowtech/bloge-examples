package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

/** Committed private Fixture Set authority in one exact authoring scope. */
public record StoredFixtureSet(AuthoringScope scope, GeneratedDefaultFixture generated) {
    /** Rejects incomplete persisted values. */
    public StoredFixtureSet {
        if (scope == null || generated == null) {
            throw new IllegalArgumentException("stored Fixture Set is incomplete");
        }
    }

    /** Keeps full Case material out of diagnostics. */
    @Override public String toString() {
        return "StoredFixtureSet[scope=" + scope + ", fixtureSetId=" + generated.view().fixtureSetId()
                + ", revision=" + generated.view().revision() + "]";
    }
}
