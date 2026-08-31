package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;

import java.util.Objects;

/** Exact immutable publication receipt returned by the authoring module. */
public record ReusableFlowPublishReceipt(String schemaVersion, FixtureSubjectRef.FlowDraft source,
                                         FixtureSubjectRef.FlowVersion version, Catalog catalog) {
    public static final String SCHEMA_VERSION = "bloge.reusableFlowPublishReceipt.v1";

    public enum Catalog { AVAILABLE }

    public ReusableFlowPublishReceipt {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        source = Objects.requireNonNull(source, "source");
        version = Objects.requireNonNull(version, "version");
        catalog = Objects.requireNonNull(catalog, "catalog");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("reusable Flow publish receipt is invalid");
        }
    }
}
