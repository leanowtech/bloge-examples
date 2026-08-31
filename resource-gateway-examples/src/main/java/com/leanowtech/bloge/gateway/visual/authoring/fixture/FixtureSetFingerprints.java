package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;

import java.util.List;

/** Single canonical fingerprint authority for immutable Fixture Set content. */
final class FixtureSetFingerprints {
    private static final ObjectMapper JSON = new ObjectMapper();

    private FixtureSetFingerprints() { }

    static String of(String displayName, FixtureSubjectRef subject,
                     List<FixtureSetCommand.Case> cases) {
        FixtureSetCommand authority = new FixtureSetCommand(
                FixtureSetCommand.SCHEMA_VERSION, displayName, subject, cases);
        return AuthoringFingerprints.of(JSON.valueToTree(authority));
    }
}
