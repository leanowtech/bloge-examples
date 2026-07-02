package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.util.List;

/**
 * Result returned after importing a portable visual graph draft bundle.
 *
 * @param schemaVersion import result schema version
 * @param imported whether a new draft was stored
 * @param draft stored draft when import succeeded
 * @param diagnostics import contract or target-environment compatibility diagnostics
 * @param validation target-environment validation and readiness when available
 * @param dependencyReport target-environment dependency report for the stored draft
 */
public record GraphDraftImportResult(
        String schemaVersion,
        boolean imported,
        GraphDraft draft,
        List<VisualDiagnostic> diagnostics,
        VisualValidationResult validation,
        GraphDraftDependencyReport dependencyReport
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphDraftImportResult.v1";

    /**
     * Creates an import result.
     */
    public GraphDraftImportResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        validation = validation == null ? new VisualValidationResult(false, diagnostics) : validation;
        dependencyReport = dependencyReport == null ? GraphDraftDependencyReport.empty() : dependencyReport;
    }

    /**
     * Backward-compatible constructor for callers that do not return a dependency report.
     */
    public GraphDraftImportResult(String schemaVersion,
                                  boolean imported,
                                  GraphDraft draft,
                                  List<VisualDiagnostic> diagnostics,
                                  VisualValidationResult validation) {
        this(schemaVersion, imported, draft, diagnostics, validation, GraphDraftDependencyReport.empty());
    }

    /**
     * Backward-compatible constructor for callers that only return diagnostics.
     */
    public GraphDraftImportResult(String schemaVersion,
                                  boolean imported,
                                  GraphDraft draft,
                                  List<VisualDiagnostic> diagnostics) {
        this(schemaVersion, imported, draft, diagnostics, new VisualValidationResult(false, diagnostics),
                GraphDraftDependencyReport.empty());
    }

    /**
     * @param draft stored draft
     * @param diagnostics target-environment diagnostics for the stored draft
     * @return successful import result
     */
    public static GraphDraftImportResult imported(GraphDraft draft, List<VisualDiagnostic> diagnostics) {
        return imported(draft, new VisualValidationResult(false, diagnostics));
    }

    /**
     * @param draft stored draft
     * @param validation target-environment validation and readiness for the stored draft
     * @return successful import result
     */
    public static GraphDraftImportResult imported(GraphDraft draft, VisualValidationResult validation) {
        VisualValidationResult safeValidation = validation == null
                ? new VisualValidationResult(false, List.of())
                : validation;
        return imported(draft, safeValidation, GraphDraftDependencyReport.empty());
    }

    /**
     * @param draft stored draft
     * @param validation target-environment validation and readiness for the stored draft
     * @param dependencyReport target-environment dependency report for the stored draft
     * @return successful import result
     */
    public static GraphDraftImportResult imported(GraphDraft draft,
                                                  VisualValidationResult validation,
                                                  GraphDraftDependencyReport dependencyReport) {
        VisualValidationResult safeValidation = validation == null
                ? new VisualValidationResult(false, List.of())
                : validation;
        return new GraphDraftImportResult("", true, draft, safeValidation.diagnostics(), safeValidation,
                dependencyReport);
    }

    /**
     * @param diagnostics blocking import diagnostics
     * @return rejected import result
     */
    public static GraphDraftImportResult rejected(List<VisualDiagnostic> diagnostics) {
        return new GraphDraftImportResult("", false, null, diagnostics, new VisualValidationResult(false,
                diagnostics), GraphDraftDependencyReport.empty());
    }
}
