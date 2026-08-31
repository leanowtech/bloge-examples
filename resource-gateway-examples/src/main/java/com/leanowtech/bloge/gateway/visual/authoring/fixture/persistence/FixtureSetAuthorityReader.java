package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.List;
import java.util.Optional;

/** Read-only union seam for every committed private Fixture Set authority. */
public interface FixtureSetAuthorityReader {
    /** Reads one unambiguous committed head in the exact trusted scope. */
    Optional<StoredFixtureSet> findHead(AuthoringScope scope, String fixtureSetId);

    /** Reads one unambiguous immutable revision in the exact trusted scope. */
    Optional<StoredFixtureSet> findRevision(AuthoringScope scope, String fixtureSetId, int revision);

    /** Lists payload-free current summaries for one exact immutable subject. */
    List<FixtureSetSummary> listSummariesBySubject(AuthoringScope scope, FixtureSubjectRef subject);
}
