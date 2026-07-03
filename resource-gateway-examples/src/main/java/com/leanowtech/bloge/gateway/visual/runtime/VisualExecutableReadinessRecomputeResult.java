package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Governed mutation result for applying an executable readiness recompute preview.
 *
 * <p>The request never accepts a caller-supplied operator surface. The server recomputes the
 * preview, writes the candidate operator into the owning user library only after governance
 * acknowledgement, and records the change as a normal operator-library registry revision.</p>
 *
 * @param schemaVersion response contract version
 * @param completedAt server mutation timestamp
 * @param applied true when a library revision was written
 * @param state applied, ack-required, blocked, rejected, or missing
 * @param level UI/control-plane severity
 * @param message human-readable summary
 * @param operatorRef operator reference
 * @param operatorLibraryId owning user library id
 * @param currentOperatorFingerprint current trusted fingerprint before apply
 * @param candidateOperatorFingerprint candidate fingerprint from the preview
 * @param libraryRevision written library revision number when applied
 * @param storedLibrary stored library snapshot when applied
 * @param storedRevision immutable registry revision snapshot when applied
 * @param preview recomputed read-only preview used for the decision
 * @param diagnostics structured mutation diagnostics
 */
public record VisualExecutableReadinessRecomputeResult(
        String schemaVersion,
        Instant completedAt,
        boolean applied,
        String state,
        String level,
        String message,
        String operatorRef,
        String operatorLibraryId,
        String currentOperatorFingerprint,
        String candidateOperatorFingerprint,
        long libraryRevision,
        OperatorLibrary storedLibrary,
        OperatorLibraryRevision storedRevision,
        VisualExecutableReadinessRecomputePreview preview,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualExecutableReadinessRecomputeResult.v1";

    /**
     * Creates a normalized result payload.
     */
    public VisualExecutableReadinessRecomputeResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        completedAt = completedAt == null ? Instant.now() : completedAt;
        state = state == null || state.isBlank() ? "blocked" : state.trim().toLowerCase(Locale.ROOT);
        level = level == null || level.isBlank() ? "info" : level.trim().toLowerCase(Locale.ROOT);
        message = message == null ? "" : message;
        operatorRef = operatorRef == null ? "" : operatorRef.trim();
        operatorLibraryId = operatorLibraryId == null ? "" : operatorLibraryId.trim();
        currentOperatorFingerprint = currentOperatorFingerprint == null ? "" : currentOperatorFingerprint.trim();
        candidateOperatorFingerprint = candidateOperatorFingerprint == null ? "" : candidateOperatorFingerprint.trim();
        libraryRevision = Math.max(0L, libraryRevision);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        applied = applied && diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }

    /**
     * @param preview blocked or missing preview
     * @return rejected mutation result
     */
    public static VisualExecutableReadinessRecomputeResult fromPreview(
            VisualExecutableReadinessRecomputePreview preview) {
        return new VisualExecutableReadinessRecomputeResult(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                preview == null ? "blocked" : preview.state(),
                preview == null ? "error" : preview.level(),
                preview == null
                        ? "Executable readiness recompute could not be previewed."
                        : preview.message(),
                preview == null ? "" : preview.operatorRef(),
                preview == null ? "" : preview.operatorLibraryId(),
                preview == null ? "" : preview.currentOperatorFingerprint(),
                preview == null ? "" : preview.candidateOperatorFingerprint(),
                0L,
                null,
                null,
                preview,
                preview == null ? List.of() : preview.diagnostics()
        );
    }

    /**
     * @param preview recomputable preview that still needs acknowledgement
     * @param diagnostic warning diagnostic
     * @return acknowledgement-required result
     */
    public static VisualExecutableReadinessRecomputeResult ackRequired(
            VisualExecutableReadinessRecomputePreview preview,
            VisualDiagnostic diagnostic) {
        return rejected(preview, "ack-required", "warning", diagnostic.message(), List.of(diagnostic));
    }

    /**
     * @param preview recompute preview
     * @param diagnostics blocking diagnostics
     * @return rejected result
     */
    public static VisualExecutableReadinessRecomputeResult rejected(
            VisualExecutableReadinessRecomputePreview preview,
            List<VisualDiagnostic> diagnostics) {
        String message = diagnostics == null || diagnostics.isEmpty()
                ? "Executable readiness recompute apply was rejected."
                : diagnostics.getFirst().message();
        return rejected(preview, "rejected", "error", message, diagnostics);
    }

    /**
     * @param preview recompute preview
     * @param state result state
     * @param level severity
     * @param message summary
     * @param diagnostics diagnostics
     * @return rejected result
     */
    public static VisualExecutableReadinessRecomputeResult rejected(
            VisualExecutableReadinessRecomputePreview preview,
            String state,
            String level,
            String message,
            List<VisualDiagnostic> diagnostics) {
        return new VisualExecutableReadinessRecomputeResult(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                state,
                level,
                message,
                preview == null ? "" : preview.operatorRef(),
                preview == null ? "" : preview.operatorLibraryId(),
                preview == null ? "" : preview.currentOperatorFingerprint(),
                preview == null ? "" : preview.candidateOperatorFingerprint(),
                0L,
                null,
                null,
                preview,
                diagnostics
        );
    }

    /**
     * @param storedLibrary stored library snapshot
     * @param storedRevision immutable registry revision
     * @param preview recompute preview
     * @return applied result
     */
    public static VisualExecutableReadinessRecomputeResult applied(
            OperatorLibrary storedLibrary,
            OperatorLibraryRevision storedRevision,
            VisualExecutableReadinessRecomputePreview preview) {
        long revision = storedRevision == null ? 0L : storedRevision.revision();
        return new VisualExecutableReadinessRecomputeResult(
                SCHEMA_VERSION,
                Instant.now(),
                true,
                "applied",
                "success",
                "Executable readiness recompute for '%s' was written as operator-library revision %d."
                        .formatted(preview.operatorRef(), revision),
                preview.operatorRef(),
                preview.operatorLibraryId(),
                preview.currentOperatorFingerprint(),
                preview.candidateOperatorFingerprint(),
                revision,
                storedLibrary,
                storedRevision,
                preview,
                List.of()
        );
    }
}
