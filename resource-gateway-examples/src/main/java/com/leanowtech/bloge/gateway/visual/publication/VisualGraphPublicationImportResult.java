package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualRuntimeBindingRequirementKey;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Result returned after validating or importing a portable immutable publication bundle.
 *
 * @param schemaVersion import result schema version
 * @param imported whether the target repository stored the publication
 * @param sourceBundleSchemaVersion source export bundle schema version
 * @param sourceBundleFingerprint source export bundle stable fingerprint
 * @param sourcePublicationId source publication id from the bundle
 * @param sourceDraftId source draft id from the bundle
 * @param sourceDraftRevision source draft revision from the bundle
 * @param sourceArtifactKind source artifact kind from the bundle
 * @param importedAt target-environment timestamp for this validation or import attempt
 * @param importedPublicationId target publication id when known
 * @param mutationAction target repository action or intended action
 * @param publication stored or rejected publication snapshot
 * @param sourceDependencyReport source-environment dependency report from the bundle
 * @param targetDependencyReport target-environment dependency report computed without rewriting the publication
 * @param targetRuntimeBindingRequirements runtime binding work handed off by the imported publication
 * @param targetRuntimeBindingRequirementKeys stable keys aligned with targetRuntimeBindingRequirements
 * @param diagnostics target-environment import diagnostics
 */
public record VisualGraphPublicationImportResult(
        String schemaVersion,
        boolean imported,
        String sourceBundleSchemaVersion,
        String sourceBundleFingerprint,
        String sourcePublicationId,
        String sourceDraftId,
        long sourceDraftRevision,
        String sourceArtifactKind,
        Instant importedAt,
        String importedPublicationId,
        String mutationAction,
        VisualGraphPublication publication,
        GraphDraftDependencyReport sourceDependencyReport,
        GraphDraftDependencyReport targetDependencyReport,
        List<VisualGraphReadiness.RuntimeBindingRequirement> targetRuntimeBindingRequirements,
        List<String> targetRuntimeBindingRequirementKeys,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphPublicationImportResult.v1";
    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_REJECTED = "REJECTED";

    /**
     * Creates a normalized import result.
     */
    public VisualGraphPublicationImportResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        sourceBundleSchemaVersion = sourceBundleSchemaVersion == null ? "" : sourceBundleSchemaVersion.trim();
        sourceBundleFingerprint = sourceBundleFingerprint == null ? "" : sourceBundleFingerprint.trim();
        sourcePublicationId = sourcePublicationId == null || sourcePublicationId.isBlank()
                ? publication == null ? "" : publication.publicationId()
                : sourcePublicationId.trim();
        sourceDraftId = sourceDraftId == null || sourceDraftId.isBlank()
                ? publication == null ? "" : publication.draftId()
                : sourceDraftId.trim();
        sourceDraftRevision = Math.max(0, sourceDraftRevision);
        sourceArtifactKind = sourceArtifactKind == null || sourceArtifactKind.isBlank()
                ? publication == null ? "" : publication.artifactKind()
                : sourceArtifactKind.trim().toUpperCase(Locale.ROOT);
        importedAt = importedAt == null ? Instant.now() : importedAt;
        importedPublicationId = importedPublicationId == null || importedPublicationId.isBlank()
                ? publication == null ? "" : publication.publicationId()
                : importedPublicationId.trim();
        mutationAction = mutationAction == null || mutationAction.isBlank()
                ? imported ? ACTION_CREATE : ACTION_REJECTED
                : mutationAction.trim().toUpperCase(Locale.ROOT);
        sourceDependencyReport = sourceDependencyReport == null
                ? GraphDraftDependencyReport.empty()
                : sourceDependencyReport;
        targetDependencyReport = targetDependencyReport == null
                ? GraphDraftDependencyReport.empty()
                : targetDependencyReport;
        targetRuntimeBindingRequirements = targetRuntimeBindingRequirements == null
                ? runtimeBindingRequirements(publication)
                : List.copyOf(targetRuntimeBindingRequirements);
        targetRuntimeBindingRequirementKeys = targetRuntimeBindingRequirementKeys == null
                ? runtimeBindingRequirementKeys(publication, targetRuntimeBindingRequirements)
                : List.copyOf(targetRuntimeBindingRequirementKeys);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        imported = imported && publication != null && diagnostics.stream()
                .noneMatch(diagnostic -> "ERROR".equalsIgnoreCase(diagnostic.level()));
    }

    /**
     * @param bundle source export bundle
     * @param publication stored target publication
     * @param targetDependencyReport target-environment dependency report
     * @return successful import result
     */
    public static VisualGraphPublicationImportResult imported(VisualGraphPublicationExportBundle bundle,
                                                              VisualGraphPublication publication,
                                                              GraphDraftDependencyReport targetDependencyReport) {
        return from(bundle, true, publication, ACTION_CREATE, targetDependencyReport, List.of());
    }

    /**
     * @param bundle source export bundle
     * @param publication target-environment publication preview that was not stored
     * @param targetDependencyReport target-environment dependency report
     * @return non-stored target-environment preflight result with source lineage
     */
    public static VisualGraphPublicationImportResult previewed(VisualGraphPublicationExportBundle bundle,
                                                               VisualGraphPublication publication,
                                                               GraphDraftDependencyReport targetDependencyReport) {
        return from(bundle, false, publication, ACTION_CREATE, targetDependencyReport, List.of());
    }

    /**
     * @param bundle source export bundle
     * @param publication publication snapshot under review
     * @param targetDependencyReport target-environment dependency report when available
     * @param diagnostics target-environment diagnostics
     * @return non-stored import result
     */
    public static VisualGraphPublicationImportResult rejected(VisualGraphPublicationExportBundle bundle,
                                                              VisualGraphPublication publication,
                                                              GraphDraftDependencyReport targetDependencyReport,
                                                              List<VisualDiagnostic> diagnostics) {
        return from(bundle, false, publication, ACTION_REJECTED, targetDependencyReport, diagnostics);
    }

    /**
     * @param bundle source export bundle
     * @param publication publication snapshot under review
     * @param diagnostics target-environment diagnostics
     * @return non-stored import result
     */
    public static VisualGraphPublicationImportResult rejected(VisualGraphPublicationExportBundle bundle,
                                                              VisualGraphPublication publication,
                                                              List<VisualDiagnostic> diagnostics) {
        return rejected(bundle, publication, GraphDraftDependencyReport.empty(), diagnostics);
    }

    /**
     * @param bundle source export bundle
     * @param diagnostics target-environment diagnostics
     * @return rejected result when the bundle has no usable publication snapshot
     */
    public static VisualGraphPublicationImportResult rejected(VisualGraphPublicationExportBundle bundle,
                                                              List<VisualDiagnostic> diagnostics) {
        return from(bundle, false, null, ACTION_REJECTED, GraphDraftDependencyReport.empty(), diagnostics);
    }

    private static VisualGraphPublicationImportResult from(VisualGraphPublicationExportBundle bundle,
                                                           boolean imported,
                                                           VisualGraphPublication publication,
                                                           String mutationAction,
                                                           GraphDraftDependencyReport targetDependencyReport,
                                                           List<VisualDiagnostic> diagnostics) {
        VisualGraphPublicationExportBundle safeBundle = bundle == null
                ? new VisualGraphPublicationExportBundle("", null, "", "", "", 0, "", publication, null, null)
                : bundle;
        List<VisualGraphReadiness.RuntimeBindingRequirement> runtimeBindingRequirements =
                runtimeBindingRequirements(publication);
        return new VisualGraphPublicationImportResult(
                SCHEMA_VERSION,
                imported,
                safeBundle.schemaVersion(),
                safeBundle.bundleFingerprint(),
                safeBundle.sourcePublicationId(),
                safeBundle.sourceDraftId(),
                safeBundle.sourceDraftRevision(),
                safeBundle.sourceArtifactKind(),
                Instant.now(),
                publication == null ? "" : publication.publicationId(),
                mutationAction,
                publication,
                safeBundle.dependencyReport(),
                targetDependencyReport,
                runtimeBindingRequirements,
                runtimeBindingRequirementKeys(publication, runtimeBindingRequirements),
                diagnostics
        );
    }

    private static List<VisualGraphReadiness.RuntimeBindingRequirement> runtimeBindingRequirements(
            VisualGraphPublication publication) {
        return publication == null || publication.validation() == null || publication.validation().readiness() == null
                ? List.of()
                : publication.validation().readiness().runtimeBindingRequirements();
    }

    private static List<String> runtimeBindingRequirementKeys(
            VisualGraphPublication publication,
            List<VisualGraphReadiness.RuntimeBindingRequirement> requirements) {
        if (publication == null || requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        return requirements.stream()
                .map(requirement -> VisualRuntimeBindingRequirementKey.stable(
                        "publication",
                        publication.publicationId(),
                        requirement.nodeId(),
                        requirement.bindingKind(),
                        requirement.bindingTarget(),
                        publication.artifactKind()))
                .toList();
    }
}
