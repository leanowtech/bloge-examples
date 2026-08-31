package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;

/** Invisible private Fixture Set revision bound to one outer Resource attempt. */
public record StagedFixtureSet(CommandLease lease, GeneratedDefaultFixture generated) {
    /** Closes the generated subject and revision over the outer Resource CAS. */
    public StagedFixtureSet {
        if (lease == null || generated == null
                || lease.key().endpoint() != AuthoringEndpoint.API_RESOURCE_SAVE
                || !(generated.view().subject() instanceof FixtureSubjectRef.ApiResource subject)
                || !lease.key().targetId().equals(subject.resourceId())
                || subject.revision() != nextRevision(lease.expectedRevision())) {
            throw new IllegalArgumentException("staged Fixture Set authority is inconsistent");
        }
    }

    private static int nextRevision(ExpectedRevision expected) {
        if (expected instanceof ExpectedRevision.Create) return 1;
        if (expected instanceof ExpectedRevision.Match match && match.revision() < Integer.MAX_VALUE) {
            return (int) match.revision() + 1;
        }
        return -1;
    }
}
