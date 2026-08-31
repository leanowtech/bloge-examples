package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import java.util.List;

/** Exact private Fixture Set save receipt. */
public record FixtureSetSaveReceipt(String schemaVersion, String fixtureSetId, int revision,
                                    String fingerprint, FixtureSubjectRef subject, List<String> caseIds,
                                    FixtureSetView.Status status, int statusRevision) {
    public static final String SCHEMA_VERSION = "bloge.fixtureSetSaveReceipt.v1";

    public FixtureSetSaveReceipt {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
    }
}
