package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;

/** Private Default Fixture child persistence owned by an outer Resource save. */
public interface ApiFixtureSetCommitStore extends FixtureSetAuthorityReader {
    /** Stages one invisible generated Fixture revision for the exact Resource attempt. */
    StagedFixtureSet stage(CommandLease lease, GeneratedDefaultFixture generated);
    /** Commits the child provisionally without making it readable. */
    StoredFixtureSet commitChild(CommandLease lease);
    /** Publishes the exact child only after the canonical outer receipt exists. */
    StoredFixtureSet publishChild(CommandLease lease, CommandReceipt outerReceipt);
    /** Removes only the exact unpublished child or stage; published children remain immutable. */
    void failChild(CommandLease lease);
}
