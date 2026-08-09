package com.leanowtech.bloge.gateway.visual.draft;

import java.time.Instant;

/** Exact durable result bound to one canonical Graph draft save request. */
public record StoredGraphDraftSaveReceipt(
        String schemaVersion,
        String requestFingerprint,
        GraphDraft draft,
        Instant completedAt) {

    public static final String SCHEMA_VERSION = "bloge.graphDraftSaveReceipt.v1";

    public StoredGraphDraftSaveReceipt {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        requestFingerprint = requestFingerprint == null ? "" : requestFingerprint.trim();
        completedAt = completedAt == null ? Instant.now() : completedAt;
        if (!SCHEMA_VERSION.equals(schemaVersion) || requestFingerprint.isBlank() || draft == null) {
            throw new IllegalArgumentException("Graph draft save receipt is incomplete or unsupported");
        }
    }

    public static StoredGraphDraftSaveReceipt completed(String requestFingerprint, GraphDraft draft) {
        return new StoredGraphDraftSaveReceipt(SCHEMA_VERSION, requestFingerprint, draft, Instant.now());
    }
}
