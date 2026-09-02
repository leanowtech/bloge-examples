package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import java.util.List;

/** Immutable authoritative Fixture Set revision. */
public record FixtureSetView(String schemaVersion, String fixtureSetId, int revision, String fingerprint,
                             int statusRevision, String displayName, FixtureSubjectRef subject,
                             List<FixtureSetCommand.Case> cases, Status status) {
    public static final String SCHEMA_VERSION = "bloge.fixtureSet.v1";

    public FixtureSetView {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        cases = cases == null ? List.of() : cases.stream()
                .map(value -> new FixtureSetCommand.Case(value.caseId(), value.name(), value.input(),
                        value.when(), value.controls(), value.expect()))
                .toList();
    }

    @Override public List<FixtureSetCommand.Case> cases() {
        return cases.stream().map(value -> new FixtureSetCommand.Case(value.caseId(), value.name(),
                value.input(), value.when(), value.controls(), value.expect())).toList();
    }

    /** Keeps full Case content out of diagnostics. */
    @Override public String toString() {
        return "FixtureSetView[fixtureSetId=" + fixtureSetId + ", revision=" + revision
                + ", fingerprint=" + fingerprint + ", subject=" + subject + ", cases=" + cases.size()
                + ", status=" + status + ", statusRevision=" + statusRevision + "]";
    }

    /** Content lifecycle; statusRevision changes independently from content revision. */
    public enum Status { PRIVATE_DRAFT, SHARING_PENDING, TEAM_AVAILABLE, STALE, REVOKED }
}
