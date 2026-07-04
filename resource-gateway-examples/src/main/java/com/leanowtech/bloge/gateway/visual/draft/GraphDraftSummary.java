package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphActionReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.util.ArrayList;
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
 * @param actionReadiness server-derived compile/run/publication action gates for the summarized snapshot
 * @param operatorDependencyCount distinct operator references used by the draft
 * @param missingOperatorCount number of nodes whose current operator is absent
 * @param scopeMismatchOperatorCount number of nodes unavailable in draft scope
 * @param driftedFingerprintCount number of nodes whose saved fingerprint drifted
 * @param missingFingerprintCount number of nodes without a saved fingerprint
 * @param schemaBreakingDriftCount number of nodes whose frozen operator schema snapshot is incompatible with current catalog schema
 * @param schemaCompatibleDriftCount number of nodes whose frozen operator schema snapshot changed but remains compatible
 * @param schemaCompatibilityStateCounts node counts by frozen-vs-current operator schema compatibility state
 * @param schemaBreakingOperatorRefCounts breaking schema drift node counts by operatorRef
 * @param schemaCompatibleOperatorRefCounts compatible schema drift node counts by operatorRef
 * @param sourceKindCounts node counts by operator source kind
 * @param operatorLibraryIdCounts node counts by owner operator library id
 * @param operatorLibraryIdsByOperatorRef exact operatorRef to owner operator library id map from dependency evidence
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
        VisualGraphActionReadiness actionReadiness,
        int operatorDependencyCount,
        int missingOperatorCount,
        int scopeMismatchOperatorCount,
        int driftedFingerprintCount,
        int missingFingerprintCount,
        int schemaBreakingDriftCount,
        int schemaCompatibleDriftCount,
        Map<String, Integer> schemaCompatibilityStateCounts,
        Map<String, Integer> schemaBreakingOperatorRefCounts,
        Map<String, Integer> schemaCompatibleOperatorRefCounts,
        Map<String, Integer> sourceKindCounts,
        Map<String, Integer> operatorLibraryIdCounts,
        Map<String, String> operatorLibraryIdsByOperatorRef,
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
        actionReadiness = actionReadiness == null
                ? derivedActionReadiness(valid, diagnosticCount, errorCount, warningCount, readiness)
                : actionReadiness;
        sourceKindCounts = sourceKindCounts == null ? Map.of() : new LinkedHashMap<>(sourceKindCounts);
        schemaCompatibilityStateCounts = schemaCompatibilityStateCounts == null
                ? Map.of()
                : new LinkedHashMap<>(schemaCompatibilityStateCounts);
        schemaBreakingOperatorRefCounts = schemaBreakingOperatorRefCounts == null
                ? Map.of()
                : new LinkedHashMap<>(schemaBreakingOperatorRefCounts);
        schemaCompatibleOperatorRefCounts = schemaCompatibleOperatorRefCounts == null
                ? Map.of()
                : new LinkedHashMap<>(schemaCompatibleOperatorRefCounts);
        operatorLibraryIdCounts = operatorLibraryIdCounts == null
                ? Map.of()
                : new LinkedHashMap<>(operatorLibraryIdCounts);
        operatorLibraryIdsByOperatorRef = normalizeOperatorLibraryIds(operatorLibraryIdsByOperatorRef);
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
                safeValidation.actionReadiness(),
                safeDependencies.operatorDependencyCount(),
                safeDependencies.missingOperatorCount(),
                safeDependencies.scopeMismatchOperatorCount(),
                safeDependencies.driftedFingerprintCount(),
                safeDependencies.missingFingerprintCount(),
                safeDependencies.schemaBreakingDriftCount(),
                safeDependencies.schemaCompatibleDriftCount(),
                safeDependencies.schemaCompatibilityStateCounts(),
                schemaOperatorRefCounts(safeDependencies, "breaking"),
                schemaOperatorRefCounts(safeDependencies, "compatible"),
                safeDependencies.sourceKindCounts(),
                safeDependencies.operatorLibraryIdCounts(),
                operatorLibraryIdsByOperatorRef(safeDependencies),
                safeDependencies.loweringModeCounts(),
                safeDependencies.runtimeReadinessStateCounts()
        );
    }

    private static Map<String, String> operatorLibraryIdsByOperatorRef(GraphDraftDependencyReport dependencies) {
        if (dependencies == null || dependencies.operators() == null) {
            return Map.of();
        }
        Map<String, String> ownerIds = new LinkedHashMap<>();
        for (GraphDraftDependencyReport.OperatorDependency operator : dependencies.operators()) {
            if (operator == null) {
                continue;
            }
            String operatorRef = operator.operatorRef() == null ? "" : operator.operatorRef().trim();
            String operatorLibraryId = operator.operatorLibraryId() == null ? "" : operator.operatorLibraryId().trim();
            if (!operatorRef.isBlank() && !operatorLibraryId.isBlank()) {
                ownerIds.putIfAbsent(operatorRef, operatorLibraryId);
            }
        }
        return ownerIds;
    }

    private static Map<String, Integer> schemaOperatorRefCounts(GraphDraftDependencyReport dependencies,
                                                                String state) {
        if (dependencies == null || dependencies.nodes() == null || state == null || state.isBlank()) {
            return Map.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GraphDraftDependencyReport.NodeDependency node : dependencies.nodes()) {
            if (node == null || !state.equals(node.schemaCompatibilityState())) {
                continue;
            }
            String operatorRef = node.operatorRef() == null ? "" : node.operatorRef().trim();
            if (!operatorRef.isBlank()) {
                counts.merge(operatorRef, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, String> normalizeOperatorLibraryIds(Map<String, String> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : ownerIds.entrySet()) {
            String operatorRef = entry.getKey() == null ? "" : entry.getKey().trim();
            String operatorLibraryId = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!operatorRef.isBlank() && !operatorLibraryId.isBlank()) {
                normalized.put(operatorRef, operatorLibraryId);
            }
        }
        return normalized;
    }

    private static VisualGraphActionReadiness derivedActionReadiness(boolean valid,
                                                                     int diagnosticCount,
                                                                     int errorCount,
                                                                     int warningCount,
                                                                     VisualGraphReadiness readiness) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        int safeErrorCount = Math.max(0, errorCount);
        int safeWarningCount = Math.max(0, warningCount);
        int safeDiagnosticCount = Math.max(0, diagnosticCount);
        for (int i = 0; i < safeErrorCount; i++) {
            diagnostics.add(VisualDiagnostic.error("visual.summary.error",
                    "Summary contains blocking validation diagnostics.", ""));
        }
        for (int i = 0; i < safeWarningCount; i++) {
            diagnostics.add(VisualDiagnostic.warning("visual.summary.warning",
                    "Summary contains warning validation diagnostics.", ""));
        }
        for (int i = diagnostics.size(); i < safeDiagnosticCount; i++) {
            diagnostics.add(new VisualDiagnostic("INFO", "visual.summary.info",
                    "Summary contains informational validation diagnostics.", "", -1, -1));
        }
        return VisualGraphActionReadiness.from(valid, diagnostics, readiness);
    }
}
