package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.Optional;

/** Atomic revision/head/CAS/idempotency authority for independently authored Fixture Sets. */
public interface StandaloneFixtureSetStore extends FixtureSetAuthorityReader {
    /** Saves one validated materialization or returns its exact committed replay. */
    StandaloneFixtureSetSaveResult save(StandaloneFixtureSetSaveIntent intent);

    /** Derives one governed revision after replay and exact source CAS are closed. */
    StandaloneFixtureSetShareResult share(
            StandaloneFixtureSetShareIntent intent, FixtureSetShareDeriver deriver);

    /** Resolves one exact committed historical revision by opaque strong validator. */
    Optional<StoredStandaloneFixtureSet> findRevisionByStrongEtag(
            AuthoringScope scope, String fixtureSetId, String strongEtag);
}
