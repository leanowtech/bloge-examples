package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

/** HTTP-neutral create-or-update precondition for standalone Fixture Sets. */
public sealed interface FixtureSetPrecondition
        permits FixtureSetPrecondition.Create, FixtureSetPrecondition.MatchStrongEtag {
    record Create() implements FixtureSetPrecondition { }

    record MatchStrongEtag(String strongEtag) implements FixtureSetPrecondition {
        public MatchStrongEtag {
            if (!FixtureSetStrongEtag.isValid(strongEtag)) {
                throw new IllegalArgumentException("Fixture Set strong ETag is invalid");
            }
        }
    }

    static FixtureSetPrecondition create() { return new Create(); }
    static FixtureSetPrecondition match(String strongEtag) { return new MatchStrongEtag(strongEtag); }
}
