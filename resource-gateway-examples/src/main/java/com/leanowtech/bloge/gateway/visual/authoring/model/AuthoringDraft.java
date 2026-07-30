package com.leanowtech.bloge.gateway.visual.authoring.model;

import java.time.Instant;
import java.util.Locale;

/**
 * Durable mutable source used by the visual library workbench.
 */
public record AuthoringDraft(
        String schemaVersion,
        String draftId,
        long revision,
        String sourceMode,
        VisualLibraryAuthoringDocument document,
        String fingerprint,
        Instant createdAt,
        Instant updatedAt,
        String savedBy
) {
    public static final String SCHEMA_VERSION = "bloge.visualLibraryAuthoringDraft.v1";
    public static final String SOURCE_MODE_QUICK = "QUICK";
    public static final String SOURCE_MODE_CANONICAL = "CANONICAL";

    public AuthoringDraft {
        schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
        draftId = normalized(draftId, "");
        revision = Math.max(0, revision);
        sourceMode = normalized(sourceMode, SOURCE_MODE_QUICK).toUpperCase(Locale.ROOT);
        fingerprint = normalized(fingerprint, "");
        savedBy = normalized(savedBy, "");
        if (!SOURCE_MODE_QUICK.equals(sourceMode) && !SOURCE_MODE_CANONICAL.equals(sourceMode)) {
            throw new IllegalArgumentException("Unsupported authoring sourceMode: " + sourceMode);
        }
    }

    public AuthoringDraft withStorageIdentity(String id,
                                              long storedRevision,
                                              String storedFingerprint,
                                              Instant originalCreatedAt,
                                              Instant storedAt,
                                              String actor) {
        return new AuthoringDraft(
                schemaVersion,
                id,
                storedRevision,
                sourceMode,
                document,
                storedFingerprint,
                originalCreatedAt,
                storedAt,
                actor
        );
    }

    public static AuthoringDraft unsaved(String draftId,
                                         String sourceMode,
                                         VisualLibraryAuthoringDocument document) {
        return new AuthoringDraft(
                SCHEMA_VERSION,
                draftId,
                0,
                sourceMode,
                document,
                "",
                null,
                null,
                ""
        );
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
