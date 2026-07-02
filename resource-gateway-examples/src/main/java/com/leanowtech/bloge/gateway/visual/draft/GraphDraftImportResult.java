package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualRuntimeBindingRequirementKey;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.util.List;

/**
 * Result returned after importing a portable visual graph draft bundle.
 *
 * @param schemaVersion import result schema version
 * @param imported whether a new draft was stored
 * @param sourceBundleSchemaVersion source export bundle schema version
 * @param sourceDraftId source draft id from the bundle
 * @param sourceRevision source draft revision from the bundle
 * @param draft stored draft when import succeeded
 * @param diagnostics import contract or target-environment compatibility diagnostics
 * @param validation target-environment validation and readiness when available
 * @param dependencyReport target-environment dependency report for the stored draft, retained as a legacy alias
 * @param sourceDependencyReport source-environment dependency report from the export bundle
 * @param targetDependencyReport target-environment dependency report for the stored draft
 * @param targetRuntimeBindingRequirements target-environment runtime binding work needed before executable promotion
 * @param targetRuntimeBindingRequirementKeys stable keys aligned with targetRuntimeBindingRequirements
 */
public record GraphDraftImportResult(
        String schemaVersion,
        boolean imported,
        String sourceBundleSchemaVersion,
        String sourceDraftId,
        long sourceRevision,
        GraphDraft draft,
        List<VisualDiagnostic> diagnostics,
        VisualValidationResult validation,
        GraphDraftDependencyReport dependencyReport,
        GraphDraftDependencyReport sourceDependencyReport,
        GraphDraftDependencyReport targetDependencyReport,
        List<VisualGraphReadiness.RuntimeBindingRequirement> targetRuntimeBindingRequirements,
        List<String> targetRuntimeBindingRequirementKeys
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphDraftImportResult.v1";

    /**
     * Creates an import result.
     */
    public GraphDraftImportResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        sourceBundleSchemaVersion = sourceBundleSchemaVersion == null ? "" : sourceBundleSchemaVersion.trim();
        sourceDraftId = sourceDraftId == null ? "" : sourceDraftId.trim();
        sourceRevision = Math.max(0, sourceRevision);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        validation = validation == null ? new VisualValidationResult(false, diagnostics) : validation;
        sourceDependencyReport = sourceDependencyReport == null
                ? GraphDraftDependencyReport.empty()
                : sourceDependencyReport;
        GraphDraftDependencyReport normalizedTargetDependencyReport = targetDependencyReport == null
                ? dependencyReport == null ? GraphDraftDependencyReport.empty() : dependencyReport
                : targetDependencyReport;
        targetDependencyReport = normalizedTargetDependencyReport;
        dependencyReport = normalizedTargetDependencyReport;
        targetRuntimeBindingRequirements = targetRuntimeBindingRequirements == null
                ? runtimeBindingRequirements(validation)
                : List.copyOf(targetRuntimeBindingRequirements);
        targetRuntimeBindingRequirementKeys = targetRuntimeBindingRequirementKeys == null
                ? runtimeBindingRequirementKeys(draft, targetRuntimeBindingRequirements)
                : List.copyOf(targetRuntimeBindingRequirementKeys);
    }

    /**
     * Backward-compatible constructor for callers that do not return source identity or a dependency report.
     */
    public GraphDraftImportResult(String schemaVersion,
                                  boolean imported,
                                  GraphDraft draft,
                                  List<VisualDiagnostic> diagnostics,
                                  VisualValidationResult validation) {
        this(schemaVersion, imported, "", "", 0, draft, diagnostics, validation,
                GraphDraftDependencyReport.empty(), GraphDraftDependencyReport.empty(),
                GraphDraftDependencyReport.empty(), null, null);
    }

    /**
     * Backward-compatible constructor for callers that only return diagnostics.
     */
    public GraphDraftImportResult(String schemaVersion,
                                  boolean imported,
                                  GraphDraft draft,
                                  List<VisualDiagnostic> diagnostics) {
        this(schemaVersion, imported, "", "", 0, draft, diagnostics, new VisualValidationResult(false, diagnostics),
                GraphDraftDependencyReport.empty(), GraphDraftDependencyReport.empty(),
                GraphDraftDependencyReport.empty(), null, null);
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
        return imported(null, draft, validation, dependencyReport);
    }

    /**
     * @param bundle source export bundle
     * @param draft stored draft
     * @param validation target-environment validation and readiness for the stored draft
     * @param dependencyReport target-environment dependency report for the stored draft
     * @return successful import result with source lineage
     */
    public static GraphDraftImportResult imported(GraphDraftExportBundle bundle,
                                                  GraphDraft draft,
                                                  VisualValidationResult validation,
                                                  GraphDraftDependencyReport dependencyReport) {
        VisualValidationResult safeValidation = validation == null
                ? new VisualValidationResult(false, List.of())
                : validation;
        return from(bundle, true, draft, safeValidation.diagnostics(), safeValidation,
                sourceDependencyReport(bundle), dependencyReport);
    }

    /**
     * @param diagnostics blocking import diagnostics
     * @return rejected import result
     */
    public static GraphDraftImportResult rejected(List<VisualDiagnostic> diagnostics) {
        return rejected(null, diagnostics);
    }

    /**
     * @param bundle source export bundle
     * @param diagnostics blocking import diagnostics
     * @return rejected import result with source lineage when available
     */
    public static GraphDraftImportResult rejected(GraphDraftExportBundle bundle, List<VisualDiagnostic> diagnostics) {
        return from(bundle, false, null, diagnostics, new VisualValidationResult(false, diagnostics),
                sourceDependencyReport(bundle), GraphDraftDependencyReport.empty());
    }

    private static GraphDraftImportResult from(GraphDraftExportBundle bundle,
                                               boolean imported,
                                               GraphDraft draft,
                                               List<VisualDiagnostic> diagnostics,
                                               VisualValidationResult validation,
                                               GraphDraftDependencyReport sourceDependencyReport,
                                               GraphDraftDependencyReport targetDependencyReport) {
        List<VisualGraphReadiness.RuntimeBindingRequirement> runtimeBindingRequirements =
                runtimeBindingRequirements(validation);
        return new GraphDraftImportResult(
                SCHEMA_VERSION,
                imported,
                bundle == null ? "" : bundle.schemaVersion(),
                bundle == null ? "" : bundle.sourceDraftId(),
                bundle == null ? 0 : bundle.sourceRevision(),
                draft,
                diagnostics,
                validation,
                targetDependencyReport,
                sourceDependencyReport,
                targetDependencyReport,
                runtimeBindingRequirements,
                runtimeBindingRequirementKeys(draft, runtimeBindingRequirements)
        );
    }

    private static List<VisualGraphReadiness.RuntimeBindingRequirement> runtimeBindingRequirements(
            VisualValidationResult validation) {
        return validation == null || validation.readiness() == null
                ? List.of()
                : validation.readiness().runtimeBindingRequirements();
    }

    private static List<String> runtimeBindingRequirementKeys(GraphDraft draft,
                                                              List<VisualGraphReadiness.RuntimeBindingRequirement>
                                                                      requirements) {
        if (draft == null || requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        return requirements.stream()
                .map(requirement -> VisualRuntimeBindingRequirementKey.stable(
                        "draft",
                        draft.draftId(),
                        requirement.nodeId(),
                        requirement.bindingKind(),
                        requirement.bindingTarget(),
                        ""))
                .toList();
    }

    private static GraphDraftDependencyReport sourceDependencyReport(GraphDraftExportBundle bundle) {
        return bundle == null || bundle.dependencyReport() == null
                ? GraphDraftDependencyReport.empty()
                : bundle.dependencyReport();
    }
}
