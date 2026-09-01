package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureShareCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.regex.Pattern;

/** Exact source, idempotency, and policy authority for one share derivation. */
public record StandaloneFixtureSetShareIntent(
        AuthoringScope scope, String actorId, String fixtureSetId, String sourceStrongEtag,
        String idempotencyKey, String requestFingerprint, FixtureShareCommand command) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern IDEMPOTENCY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    public StandaloneFixtureSetShareIntent {
        if (scope == null || actorId == null || actorId.isBlank() || actorId.length() > 256
                || fixtureSetId == null || !IDENTIFIER.matcher(fixtureSetId).matches()
                || !FixtureSetStrongEtag.isValid(sourceStrongEtag)
                || idempotencyKey == null || !IDEMPOTENCY.matcher(idempotencyKey).matches()
                || requestFingerprint == null || !FINGERPRINT.matcher(requestFingerprint).matches()
                || command == null || !fixtureSetId.equals(command.source().fixtureSetId())) {
            throw new IllegalArgumentException("standalone Fixture share intent is incomplete");
        }
    }
}
