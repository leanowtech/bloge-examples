package com.leanowtech.bloge.gateway.visual.authoring.model;

import java.time.Instant;
import java.util.List;
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
        List<AuthoringEvidence> evidence,
        List<AuthoringConfirmation> confirmations,
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
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        confirmations = confirmations == null ? List.of() : List.copyOf(confirmations);
        fingerprint = normalized(fingerprint, "");
        savedBy = normalized(savedBy, "");
        if (!SOURCE_MODE_QUICK.equals(sourceMode) && !SOURCE_MODE_CANONICAL.equals(sourceMode)) {
            throw new IllegalArgumentException("Unsupported authoring sourceMode: " + sourceMode);
        }
    }

    public AuthoringDraft(String schemaVersion,
                          String draftId,
                          long revision,
                          String sourceMode,
                          VisualLibraryAuthoringDocument document,
                          String fingerprint,
                          Instant createdAt,
                          Instant updatedAt,
                          String savedBy) {
        this(
                schemaVersion,
                draftId,
                revision,
                sourceMode,
                document,
                List.of(),
                List.of(),
                fingerprint,
                createdAt,
                updatedAt,
                savedBy
        );
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
                evidence,
                confirmations,
                storedFingerprint,
                originalCreatedAt,
                storedAt,
                actor
        );
    }

    public static AuthoringDraft unsaved(String draftId,
                                         String sourceMode,
                                         VisualLibraryAuthoringDocument document) {
        return unsaved(draftId, sourceMode, document, List.of(), List.of());
    }

    public static AuthoringDraft unsaved(String draftId,
                                         String sourceMode,
                                         VisualLibraryAuthoringDocument document,
                                         List<AuthoringEvidence> evidence,
                                         List<AuthoringConfirmation> confirmations) {
        return new AuthoringDraft(
                SCHEMA_VERSION,
                draftId,
                0,
                sourceMode,
                document,
                evidence,
                confirmations,
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
