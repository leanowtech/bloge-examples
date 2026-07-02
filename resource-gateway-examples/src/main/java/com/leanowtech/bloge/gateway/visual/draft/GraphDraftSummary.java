package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight asset-list summary for active and retained visual graph drafts.
 *
 * <p>The summary is computed in the API layer from repository history plus the
 * server-side validator and dependency report. It intentionally avoids making
 * the repository depend on the operator catalog.</p>
 *
 * @param schemaVersion summary contract version
 * @param draftId draft id
 * @param graphName latest known graph name
 * @param tenantId tenant scope of the summarized snapshot
 * @param namespace namespace scope of the summarized snapshot
 * @param environment authoring environment of the summarized snapshot
 * @param active true when the draft has a current working copy
 * @param currentRevision current working revision, or zero when deleted
 * @param latestRevision latest retained revision
 * @param revisionCount number of retained immutable revision snapshots
 * @param updatedAt latest revision timestamp
 * @param updatedBy latest revision actor
 * @param changeSource latest revision change source
 * @param changeSummary latest revision summary
 * @param reason latest revision reason for audit and migration review
 * @param nodeCount number of draft nodes in the summarized snapshot
 * @param edgeCount number of draft edges in the summarized snapshot
 * @param valid true when server-side draft validation has no blocking errors
 * @param diagnosticCount validation diagnostic count
 * @param errorCount blocking validation diagnostic count
 * @param warningCount warning validation diagnostic count
 * @param readiness server-derived runtime/design readiness for the summarized snapshot
 * @param operatorDependencyCount distinct operator references used by the draft
 * @param missingOperatorCount number of nodes whose current operator is absent
 * @param scopeMismatchOperatorCount number of nodes unavailable in draft scope
 * @param driftedFingerprintCount number of nodes whose saved fingerprint drifted
 * @param missingFingerprintCount number of nodes without a saved fingerprint
 * @param sourceKindCounts node counts by operator source kind
 * @param loweringModeCounts node counts by operator lowering mode
 * @param runtimeReadinessStateCounts node counts by operator runtime readiness state
 */
public record GraphDraftSummary(
        String schemaVersion,
        String draftId,
        String graphName,
        String tenantId,
        String namespace,
        String environment,
        boolean active,
        long currentRevision,
        long latestRevision,
        int revisionCount,
        String updatedAt,
        String updatedBy,
        String changeSource,
        String changeSummary,
        String reason,
        int nodeCount,
        int edgeCount,
        boolean valid,
        int diagnosticCount,
        int errorCount,
        int warningCount,
        VisualGraphReadiness readiness,
        int operatorDependencyCount,
        int missingOperatorCount,
        int scopeMismatchOperatorCount,
        int driftedFingerprintCount,
        int missingFingerprintCount,
        Map<String, Integer> sourceKindCounts,
        Map<String, Integer> loweringModeCounts,
        Map<String, Integer> runtimeReadinessStateCounts
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphDraftSummary.v1";

    /**
     * Creates a draft summary.
     */
    public GraphDraftSummary {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        draftId = draftId == null ? "" : draftId;
        graphName = graphName == null ? "" : graphName;
        tenantId = tenantId == null ? "" : tenantId;
        namespace = namespace == null ? "" : namespace;
        environment = environment == null ? "" : environment;
        updatedAt = updatedAt == null ? "" : updatedAt;
        updatedBy = updatedBy == null ? "" : updatedBy;
        changeSource = changeSource == null ? "" : changeSource;
        changeSummary = changeSummary == null ? "" : changeSummary;
        reason = reason == null ? "" : reason;
        readiness = readiness == null ? VisualGraphReadiness.notAssessed() : readiness;
        sourceKindCounts = sourceKindCounts == null ? Map.of() : new LinkedHashMap<>(sourceKindCounts);
        loweringModeCounts = loweringModeCounts == null ? Map.of() : new LinkedHashMap<>(loweringModeCounts);
        runtimeReadinessStateCounts = runtimeReadinessStateCounts == null
                ? Map.of()
                : new LinkedHashMap<>(runtimeReadinessStateCounts);
    }

    /**
     * Builds a summary from repository history, validation, and dependency reports.
     *
     * @param history repository-level history summary
     * @param draft current or latest retained draft snapshot
     * @param validation server-side validation result for the snapshot
     * @param dependencies current catalog dependency report for the snapshot
     * @return asset-list summary
     */
    public static GraphDraftSummary from(GraphDraftHistorySummary history,
                                         GraphDraft draft,
                                         VisualValidationResult validation,
                                         GraphDraftDependencyReport dependencies) {
        VisualValidationResult safeValidation = validation == null
                ? new VisualValidationResult(false, List.of(), VisualGraphReadiness.notAssessed())
                : validation;
        GraphDraftDependencyReport safeDependencies = dependencies == null
                ? GraphDraftDependencyReport.empty()
                : dependencies;
        List<VisualDiagnostic> diagnostics = safeValidation.diagnostics();
        int errorCount = (int) diagnostics.stream().filter(VisualDiagnostic::error).count();
        int warningCount = (int) diagnostics.stream()
                .filter(diagnostic -> "WARNING".equalsIgnoreCase(diagnostic.level()))
                .count();
        return new GraphDraftSummary(
                SCHEMA_VERSION,
                history == null ? draft == null ? "" : draft.draftId() : history.draftId(),
                history == null ? draft == null ? "" : draft.graphName() : history.graphName(),
                draft == null ? "" : draft.tenantId(),
                draft == null ? "" : draft.namespace(),
                draft == null ? "" : draft.environment(),
                history != null && history.active(),
                history == null ? draft == null ? 0 : draft.revision() : history.currentRevision(),
                history == null ? draft == null ? 0 : draft.revision() : history.latestRevision(),
                history == null ? 0 : history.revisionCount(),
                history == null ? "" : history.updatedAt(),
                history == null ? "" : history.updatedBy(),
                history == null ? "" : history.changeSource(),
                history == null ? "" : history.changeSummary(),
                history == null ? "" : history.reason(),
                draft == null ? 0 : draft.nodes().size(),
                draft == null ? 0 : draft.edges().size(),
                safeValidation.valid(),
                diagnostics.size(),
                errorCount,
                warningCount,
                safeValidation.readiness(),
                safeDependencies.operatorDependencyCount(),
                safeDependencies.missingOperatorCount(),
                safeDependencies.scopeMismatchOperatorCount(),
                safeDependencies.driftedFingerprintCount(),
                safeDependencies.missingFingerprintCount(),
                safeDependencies.sourceKindCounts(),
                safeDependencies.loweringModeCounts(),
                safeDependencies.runtimeReadinessStateCounts()
        );
    }
}
