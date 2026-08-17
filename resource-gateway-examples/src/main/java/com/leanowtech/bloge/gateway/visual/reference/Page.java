package com.leanowtech.bloge.gateway.visual.reference;

import java.util.List;

/** Versioned bounded page. Items contain metadata only. */
public record Page(
        String schemaVersion,
        List<ReferenceCandidate> items,
        String nextCursor,
        String queryFingerprint,
        long catalogGeneration
) {
    public static final String SCHEMA_VERSION = "bloge.referencePage.v1";

    public Page {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Page schemaVersion: " + schemaVersion);
        }
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null ? "" : nextCursor;
        if (queryFingerprint == null || queryFingerprint.isBlank()) {
            throw new IllegalArgumentException("queryFingerprint must not be blank");
        }
        if (catalogGeneration < 0) {
            throw new IllegalArgumentException("catalogGeneration must not be negative");
        }
    }

    public boolean hasNext() {
        return !nextCursor.isEmpty();
    }
}
