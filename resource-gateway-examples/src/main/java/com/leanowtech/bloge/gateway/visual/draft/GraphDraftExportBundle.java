package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.time.Instant;
import java.util.List;

/**
 * Portable export package for one visual graph draft.
 *
 * @param schemaVersion export package schema version
 * @param exportedAt export timestamp
 * @param sourceDraftId original draft id
 * @param sourceRevision original draft revision
 * @param draft draft snapshot
 * @param operatorSnapshots operator definitions referenced by the exported draft when available
 * @param diagnostics validation diagnostics captured at export time
 * @param validation full export-time validation and readiness snapshot
 * @param dependencyReport source-environment dependency report captured at export time
 */
public record GraphDraftExportBundle(
        String schemaVersion,
        Instant exportedAt,
        String sourceDraftId,
        long sourceRevision,
        GraphDraft draft,
        List<OperatorDefinition> operatorSnapshots,
        List<VisualDiagnostic> diagnostics,
        VisualValidationResult validation,
        GraphDraftDependencyReport dependencyReport
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphDraftExport.v1";

    /**
     * Creates an export package.
     */
    public GraphDraftExportBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        exportedAt = exportedAt == null ? Instant.now() : exportedAt;
        sourceDraftId = sourceDraftId == null || sourceDraftId.isBlank()
                ? draft == null ? "" : draft.draftId()
                : sourceDraftId;
        sourceRevision = sourceRevision > 0 ? sourceRevision : draft == null ? 0 : draft.revision();
        operatorSnapshots = operatorSnapshots == null ? List.of() : List.copyOf(operatorSnapshots);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        validation = validation == null ? new VisualValidationResult(false, diagnostics) : validation;
        dependencyReport = dependencyReport == null ? GraphDraftDependencyReport.empty() : dependencyReport;
    }

    /**
     * Backward-compatible constructor for callers that do not return a dependency report.
     */
    public GraphDraftExportBundle(String schemaVersion,
                                  Instant exportedAt,
                                  String sourceDraftId,
                                  long sourceRevision,
                                  GraphDraft draft,
                                  List<OperatorDefinition> operatorSnapshots,
                                  List<VisualDiagnostic> diagnostics,
                                  VisualValidationResult validation) {
        this(schemaVersion, exportedAt, sourceDraftId, sourceRevision, draft, operatorSnapshots, diagnostics,
                validation, GraphDraftDependencyReport.empty());
    }

    /**
     * Backward-compatible constructor for callers that only captured diagnostics.
     */
    public GraphDraftExportBundle(String schemaVersion,
                                  Instant exportedAt,
                                  String sourceDraftId,
                                  long sourceRevision,
                                  GraphDraft draft,
                                  List<OperatorDefinition> operatorSnapshots,
                                  List<VisualDiagnostic> diagnostics) {
        this(schemaVersion, exportedAt, sourceDraftId, sourceRevision, draft, operatorSnapshots, diagnostics,
                new VisualValidationResult(false, diagnostics), GraphDraftDependencyReport.empty());
    }

    /**
     * Creates a bundle from a stored draft snapshot.
     *
     * @param draft stored draft
     * @param operatorSnapshots referenced operator snapshots
     * @param diagnostics export-time diagnostics
     * @return portable export bundle
     */
    public static GraphDraftExportBundle from(GraphDraft draft,
                                              List<OperatorDefinition> operatorSnapshots,
                                              List<VisualDiagnostic> diagnostics) {
        return from(draft, operatorSnapshots, new VisualValidationResult(false, diagnostics));
    }

    /**
     * Creates a bundle from a stored draft snapshot.
     *
     * @param draft stored draft
     * @param operatorSnapshots referenced operator snapshots
     * @param validation export-time validation and readiness
     * @return portable export bundle
     */
    public static GraphDraftExportBundle from(GraphDraft draft,
                                              List<OperatorDefinition> operatorSnapshots,
                                              VisualValidationResult validation) {
        return from(draft, operatorSnapshots, validation, GraphDraftDependencyReport.empty());
    }

    /**
     * Creates a bundle from a stored draft snapshot.
     *
     * @param draft stored draft
     * @param operatorSnapshots referenced operator snapshots
     * @param validation export-time validation and readiness
     * @param dependencyReport source-environment dependency report
     * @return portable export bundle
     */
    public static GraphDraftExportBundle from(GraphDraft draft,
                                              List<OperatorDefinition> operatorSnapshots,
                                              VisualValidationResult validation,
                                              GraphDraftDependencyReport dependencyReport) {
        VisualValidationResult safeValidation = validation == null
                ? new VisualValidationResult(false, List.of())
                : validation;
        return new GraphDraftExportBundle("", null, draft == null ? "" : draft.draftId(),
                draft == null ? 0 : draft.revision(), draft, operatorSnapshots, safeValidation.diagnostics(),
                safeValidation, dependencyReport);
    }
}
