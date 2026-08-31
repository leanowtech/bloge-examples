package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;

import java.util.List;
import java.util.Optional;

/** Private Default Fixture child persistence owned by an outer Resource save. */
public interface ApiFixtureSetCommitStore {
    /** Stages one invisible generated Fixture revision for the exact Resource attempt. */
    StagedFixtureSet stage(CommandLease lease, GeneratedDefaultFixture generated);
    /** Commits the child provisionally without making it readable. */
    StoredFixtureSet commitChild(CommandLease lease);
    /** Publishes the exact child only after the canonical outer receipt exists. */
    StoredFixtureSet publishChild(CommandLease lease, CommandReceipt outerReceipt);
    /** Removes only the exact unpublished child or stage; published children remain immutable. */
    void failChild(CommandLease lease);
    /** Reads one committed head in the exact scope. */
    Optional<StoredFixtureSet> findHead(AuthoringScope scope, String fixtureSetId);
    /** Reads one committed immutable revision in the exact scope. */
    Optional<StoredFixtureSet> findRevision(AuthoringScope scope, String fixtureSetId, int revision);
    /** Lists payload-free current summaries for one exact immutable subject. */
    List<FixtureSetSummary> listSummariesBySubject(AuthoringScope scope, FixtureSubjectRef subject);
}
