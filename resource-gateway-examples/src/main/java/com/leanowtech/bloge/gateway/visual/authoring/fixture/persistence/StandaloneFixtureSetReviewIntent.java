package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureReviewCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

/** Exact idempotent request for completing one pending standalone Fixture review. */
public record StandaloneFixtureSetReviewIntent(
        AuthoringScope scope, String actorId, String fixtureSetId, String sourceStrongEtag,
        String idempotencyKey, String requestFingerprint, FixtureReviewCommand command) {
    public StandaloneFixtureSetReviewIntent {
        if (scope == null || actorId == null || actorId.isBlank() || fixtureSetId == null
                || fixtureSetId.isBlank() || sourceStrongEtag == null || sourceStrongEtag.isBlank()
                || idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~:/-]{1,160}")
                || requestFingerprint == null
                || !requestFingerprint.matches("sha256:[0-9a-f]{64}") || command == null
                || !fixtureSetId.equals(command.source().fixtureSetId())) {
            throw new IllegalArgumentException("Fixture review intent is invalid");
        }
    }
}
