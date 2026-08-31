package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

/** Complete non-secret authority for one standalone Fixture Set save. */
public record StandaloneFixtureSetSaveIntent(
        AuthoringScope scope, String actorId, String fixtureSetId,
        ExpectedRevision expectedRevision, String idempotencyKey,
        String requestFingerprint, GeneratedDefaultFixture generated) {
    public StandaloneFixtureSetSaveIntent {
        if (scope == null || expectedRevision == null || generated == null
                || invalid(actorId, 256) || invalid(fixtureSetId, 128)
                || invalid(idempotencyKey, 160) || !fingerprint(requestFingerprint)
                || !fixtureSetId.equals(generated.view().fixtureSetId())) {
            throw new IllegalArgumentException("standalone Fixture Set save intent is invalid");
        }
    }

    private static boolean invalid(String value, int maximum) {
        return value == null || value.isBlank() || value.length() > maximum;
    }

    private static boolean fingerprint(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }
}
