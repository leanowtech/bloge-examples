package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;

import java.util.Objects;

/** Frozen request for publishing one exact reusable Flow draft revision. */
public record ReusableFlowPublishCommand(String schemaVersion, FixtureSubjectRef.FlowDraft source) {
    public static final String SCHEMA_VERSION = "bloge.reusableFlowPublishCommand.v1";

    public ReusableFlowPublishCommand {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        source = Objects.requireNonNull(source, "source");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("reusable Flow publish command is invalid");
        }
    }
}
