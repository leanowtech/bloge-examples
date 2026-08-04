package com.leanowtech.bloge.gateway.authoring.scenario;

import java.time.Instant;
import java.util.List;

/** Payload-free receipt for one atomic Scenario Matrix bulk edit. */
public record ScenarioBulkEditResult(
        String schemaVersion,
        String commandId,
        String scenarioDraftSetId,
        long sourceRevision,
        String sourceDraftFingerprint,
        long storedRevision,
        String storedDraftFingerprint,
        int touchedCells,
        List<String> editedCaseIds,
        Instant committedAt,
        String committedBy
) {
    /** Current bulk-edit receipt protocol version. */
    public static final String SCHEMA_VERSION = "bloge.scenarioBulkEditResult.v1";

    /** Freezes payload-free coordinates. */
    public ScenarioBulkEditResult {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        commandId = normalized(commandId);
        scenarioDraftSetId = normalized(scenarioDraftSetId);
        sourceDraftFingerprint = normalized(sourceDraftFingerprint);
        storedDraftFingerprint = normalized(storedDraftFingerprint);
        touchedCells = Math.max(0, touchedCells);
        editedCaseIds = editedCaseIds == null ? List.of() : editedCaseIds.stream()
                .map(ScenarioBulkEditResult::normalized).filter(value -> !value.isBlank())
                .distinct().toList();
        committedBy = normalized(committedBy);
        if (committedAt == null) {
            throw new IllegalArgumentException("Scenario bulk-edit receipt requires committedAt");
        }
    }

    private static String defaulted(String value, String fallback) {
        String normalized = normalized(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
