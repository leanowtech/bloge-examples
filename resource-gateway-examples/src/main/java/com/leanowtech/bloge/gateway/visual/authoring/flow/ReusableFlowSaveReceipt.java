package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;

/** Exact receipt returned after one validated Flow draft revision commits. */
public record ReusableFlowSaveReceipt(String schemaVersion, String flowId,
                                      FixtureSubjectRef.FlowDraft draft, Validation validation) {
    public static final String SCHEMA_VERSION = "bloge.reusableFlowSaveReceipt.v1";
    public enum Validation { VALID }

    public ReusableFlowSaveReceipt {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (!SCHEMA_VERSION.equals(schemaVersion) || flowId == null || flowId.isBlank()
                || flowId.length() > 128 || draft == null || validation != Validation.VALID) {
            throw new IllegalArgumentException("reusable Flow receipt is invalid");
        }
    }
}
