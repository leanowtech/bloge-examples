package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import java.util.List;

/** Metadata-only Fixture Set projection for exact-subject discovery. */
public record FixtureSetSummary(String schemaVersion, String fixtureSetId, int revision, String fingerprint,
                                String displayName, FixtureSubjectRef subject, List<CaseSummary> cases,
                                FixtureSetView.Status status, int statusRevision) {
    public static final String SCHEMA_VERSION = "bloge.fixtureSetSummary.v1";

    public FixtureSetSummary {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /** Payload-free Case identity. */
    public record CaseSummary(String caseId, String name) { }
}
