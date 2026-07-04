package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphActionReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight asset-list summary for immutable visual graph publications.
 *
 * <p>Publication artifacts freeze full draft, operator snapshots, DSL, validation, and dependency state.
 * This summary keeps list/index APIs small while preserving enough governance and readiness signal for
 * browsers and external control planes to distinguish executable artifacts from design artifacts.</p>
 *
 * @param schemaVersion summary contract version
 * @param publicationId immutable publication id
 * @param draftId source draft id
 * @param draftRevision source draft revision
 * @param graphName graph name
 * @param tenantId tenant id
 * @param namespace namespace
 * @param environment authoring environment
 * @param createdAt publication timestamp
 * @param artifactKind immutable artifact kind
 * @param valid true when frozen validation has no blocking errors
 * @param diagnosticCount frozen validation diagnostic count
 * @param errorCount frozen blocking diagnostic count
 * @param warningCount frozen warning diagnostic count
 * @param readiness frozen graph runtime/design readiness
 * @param actionReadiness frozen compile/run/publication action gates
 * @param nodeCount number of frozen draft nodes
 * @param edgeCount number of frozen draft edges
 * @param operatorDependencyCount distinct operator references used by the publication
 * @param missingOperatorCount number of frozen nodes whose current operator was missing at publish-time review
 * @param scopeMismatchOperatorCount number of frozen nodes unavailable in publication scope
 * @param driftedFingerprintCount number of frozen nodes whose saved fingerprint drifted
 * @param missingFingerprintCount number of frozen nodes without a saved fingerprint
 * @param schemaBreakingDriftCount number of frozen nodes whose operator schema snapshot is incompatible with current catalog schema
 * @param schemaCompatibleDriftCount number of frozen nodes whose operator schema snapshot changed but remains compatible
 * @param schemaCompatibilityStateCounts frozen node counts by schema compatibility state
 * @param schemaBreakingOperatorRefCounts breaking schema drift frozen node counts by operatorRef
 * @param schemaCompatibleOperatorRefCounts compatible schema drift frozen node counts by operatorRef
 * @param sourceKindCounts node counts by source kind
 * @param operatorLibraryIdCounts node counts by owner operator library id
 * @param operatorLibraryIdsByOperatorRef exact operatorRef to owner operator library id map from frozen dependency evidence
 * @param loweringModeCounts node counts by lowering mode
 * @param runtimeReadinessStateCounts node counts by runtime readiness state
 */
public record VisualGraphPublicationSummary(
        String schemaVersion,
        String publicationId,
        String draftId,
        long draftRevision,
        String graphName,
        String tenantId,
        String namespace,
        String environment,
        Instant createdAt,
        String artifactKind,
        boolean valid,
        int diagnosticCount,
        int errorCount,
        int warningCount,
        VisualGraphReadiness readiness,
        VisualGraphActionReadiness actionReadiness,
        int nodeCount,
        int edgeCount,
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
    public static final String SCHEMA_VERSION = "bloge.visualGraphPublicationSummary.v1";

    /**
     * Creates a publication summary.
     */
    public VisualGraphPublicationSummary {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        publicationId = publicationId == null ? "" : publicationId;
        draftId = draftId == null ? "" : draftId;
        graphName = graphName == null ? "" : graphName;
        tenantId = tenantId == null ? "" : tenantId;
        namespace = namespace == null ? "" : namespace;
        environment = environment == null ? "" : environment;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        artifactKind = artifactKind == null || artifactKind.isBlank()
                ? VisualGraphPublication.ARTIFACT_EXECUTABLE
                : artifactKind.trim().toUpperCase(Locale.ROOT);
        readiness = readiness == null ? VisualGraphReadiness.notAssessed() : readiness;
        actionReadiness = actionReadiness == null
                ? derivedActionReadiness(valid, diagnosticCount, errorCount, warningCount, readiness)
                : actionReadiness;
        schemaCompatibilityStateCounts = schemaCompatibilityStateCounts == null
                ? Map.of()
                : new LinkedHashMap<>(schemaCompatibilityStateCounts);
        schemaBreakingOperatorRefCounts = schemaBreakingOperatorRefCounts == null
                ? Map.of()
                : new LinkedHashMap<>(schemaBreakingOperatorRefCounts);
        schemaCompatibleOperatorRefCounts = schemaCompatibleOperatorRefCounts == null
                ? Map.of()
                : new LinkedHashMap<>(schemaCompatibleOperatorRefCounts);
        sourceKindCounts = sourceKindCounts == null ? Map.of() : new LinkedHashMap<>(sourceKindCounts);
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
     * Builds a summary from a frozen immutable publication artifact.
     *
     * @param publication publication artifact
     * @return lightweight publication summary
     */
    public static VisualGraphPublicationSummary from(VisualGraphPublication publication) {
        if (publication == null) {
            return empty();
        }
        VisualValidationResult validation = publication.validation();
        List<VisualDiagnostic> diagnostics = validation == null ? List.of() : validation.diagnostics();
        int errorCount = (int) diagnostics.stream().filter(VisualDiagnostic::error).count();
        int warningCount = (int) diagnostics.stream()
                .filter(diagnostic -> "WARNING".equalsIgnoreCase(diagnostic.level()))
                .count();
        GraphDraftDependencyReport dependencies = publication.dependencyReport() == null
                ? GraphDraftDependencyReport.empty()
                : publication.dependencyReport();
        return new VisualGraphPublicationSummary(
                SCHEMA_VERSION,
                publication.publicationId(),
                publication.draftId(),
                publication.draftRevision(),
                publication.graphName(),
                publication.tenantId(),
                publication.namespace(),
                publication.environment(),
                publication.createdAt(),
                publication.artifactKind(),
                validation == null || validation.valid(),
                diagnostics.size(),
                errorCount,
                warningCount,
                validation == null ? VisualGraphReadiness.notAssessed() : validation.readiness(),
                validation == null ? null : validation.actionReadiness(),
                publication.draft() == null ? 0 : publication.draft().nodes().size(),
                publication.draft() == null ? 0 : publication.draft().edges().size(),
                dependencies.operatorDependencyCount(),
                dependencies.missingOperatorCount(),
                dependencies.scopeMismatchOperatorCount(),
                dependencies.driftedFingerprintCount(),
                dependencies.missingFingerprintCount(),
                dependencies.schemaBreakingDriftCount(),
                dependencies.schemaCompatibleDriftCount(),
                dependencies.schemaCompatibilityStateCounts(),
                schemaOperatorRefCounts(dependencies, "breaking"),
                schemaOperatorRefCounts(dependencies, "compatible"),
                dependencies.sourceKindCounts(),
                dependencies.operatorLibraryIdCounts(),
                operatorLibraryIdsByOperatorRef(dependencies),
                dependencies.loweringModeCounts(),
                dependencies.runtimeReadinessStateCounts()
        );
    }

    /**
     * @return empty placeholder summary
     */
    public static VisualGraphPublicationSummary empty() {
        return new VisualGraphPublicationSummary(
                SCHEMA_VERSION,
                "",
                "",
                0,
                "",
                "",
                "",
                "",
                Instant.EPOCH,
                VisualGraphPublication.ARTIFACT_EXECUTABLE,
                false,
                0,
                0,
                0,
                VisualGraphReadiness.notAssessed(),
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
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
