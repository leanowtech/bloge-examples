package com.leanowtech.bloge.gateway.integration;

/** Authoring read model for the latest ANEKE gate result and its snapshot freshness. */
public record GovernanceGateView(
        String schemaVersion,
        String draftId,
        long currentRevision,
        String currentDraftFingerprint,
        String freshness,
        GovernanceGateResult result
) {
    public static final String SCHEMA_VERSION = "bloge.visualGovernanceGateView.v1";

    public GovernanceGateView {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        draftId = draftId == null ? "" : draftId;
        currentDraftFingerprint = currentDraftFingerprint == null ? "" : currentDraftFingerprint;
        freshness = freshness == null || freshness.isBlank() ? "MISSING" : freshness;
    }
}
