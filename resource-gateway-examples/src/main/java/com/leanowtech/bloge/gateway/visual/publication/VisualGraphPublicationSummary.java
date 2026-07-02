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
 * @param sourceKindCounts node counts by source kind
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
        Map<String, Integer> sourceKindCounts,
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
        sourceKindCounts = sourceKindCounts == null ? Map.of() : new LinkedHashMap<>(sourceKindCounts);
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
                dependencies.sourceKindCounts(),
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
                Map.of(),
                Map.of(),
                Map.of()
        );
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
