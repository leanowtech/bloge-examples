package com.leanowtech.bloge.gateway.testing.authoring.fixture;

import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.api.TestingArtifactScope;

import java.time.Instant;
import java.util.Optional;

/**
 * Enterprise-scoped immutable authoring-fixture vault boundary.
 */
public interface AuthoringFixtureRepository {

    StoredAuthoringFixture create(
            TestingArtifactScope scope,
            StoredAuthoringFixture fixture,
            long expectedRevision,
            TestRuntimeTransactionMutation audit);

    Optional<StoredAuthoringFixture> find(
            TestingArtifactScope scope, String fixtureId, long revision);

    long latestRevision(TestingArtifactScope scope, String fixtureId);

    int expireDue(Instant observedAt, int limit);
}
