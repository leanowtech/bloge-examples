package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.visual.model.VisualAuthoringJsonValue;

import java.util.List;

/** Atomic, source-bound command for editing multiple Scenario Matrix cells. */
public record ScenarioBulkEditCommand(
        String schemaVersion,
        String commandId,
        long expectedRevision,
        String expectedDraftFingerprint,
        Atomicity atomicity,
        List<CellEdit> edits
) {
    /** Current bulk-edit command protocol version. */
    public static final String SCHEMA_VERSION = "bloge.scenarioBulkEditCommand.v1";

    /** Freezes edits and normalizes source coordinates. */
    public ScenarioBulkEditCommand {
        schemaVersion = normalized(schemaVersion);
        commandId = normalized(commandId);
        expectedDraftFingerprint = normalized(expectedDraftFingerprint);
        atomicity = atomicity == null ? Atomicity.ALL_OR_NOTHING : atomicity;
        edits = edits == null ? List.of() : List.copyOf(edits);
    }

    /** Partial commits are forbidden because they make retry and conflict reconciliation ambiguous. */
    public enum Atomicity {
        ALL_OR_NOTHING
    }

    /** Editable canonical Matrix fields. */
    public enum Field {
        NAME,
        CASE_TYPE,
        TAGS,
        GIVEN_PATH
    }

    /** Explicit value operation. REMOVE is admitted only for GIVEN_PATH. */
    public enum Operation {
        SET,
        REMOVE
    }

    /** One exact cell mutation guarded by the row fingerprint observed by the author. */
    public record CellEdit(
            String caseId,
            String expectedCaseFingerprint,
            Field field,
            String path,
            Operation operation,
            Object value
    ) {
        /** Normalizes coordinates and deeply freezes the supplied JSON value. */
        public CellEdit {
            caseId = normalized(caseId);
            expectedCaseFingerprint = normalized(expectedCaseFingerprint);
            path = normalized(path);
            operation = operation == null ? Operation.SET : operation;
            value = VisualAuthoringJsonValue.freeze(value);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
