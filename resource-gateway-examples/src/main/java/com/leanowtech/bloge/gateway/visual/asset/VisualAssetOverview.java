package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogFacets;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftSummary;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationSummary;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableLoweringIntegration;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeRolloutObservation;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphActionReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphReadiness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Environment-level visual authoring asset overview.
 *
 * <p>This is a control-plane read model for browser dashboards and external governance
 * automation. It summarizes drafts, immutable publications, and the current operator
 * catalog without returning full draft/publication payloads.</p>
 *
 * @param schemaVersion overview contract version
 * @param generatedAt server timestamp when this overview was derived
 * @param scope authoring scope used to derive this overview
 * @param drafts draft asset aggregate
 * @param publications publication asset aggregate
 * @param catalog operator catalog aggregate
 * @param runtimeEvidence runtime implementation and executor evidence aggregate
 * @param actionQueue server-derived next-action queue for control-plane triage
 */
public record VisualAssetOverview(
        String schemaVersion,
        Instant generatedAt,
        AuthoringScope scope,
        DraftAssets drafts,
        PublicationAssets publications,
        CatalogAssets catalog,
        RuntimeEvidenceAssets runtimeEvidence,
        ActionQueue actionQueue
) {
    public static final String SCHEMA_VERSION = "bloge.visualAssetOverview.v1";
    public static final int DEFAULT_ACTION_ITEM_LIMIT = 12;
    public static final int MAX_ACTION_ITEM_LIMIT = 100;

    /**
     * Creates an overview.
     */
    public VisualAssetOverview {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        scope = scope == null ? AuthoringScope.all() : scope;
        drafts = drafts == null ? DraftAssets.empty() : drafts;
        publications = publications == null ? PublicationAssets.empty() : publications;
        catalog = catalog == null ? CatalogAssets.empty() : catalog;
        runtimeEvidence = runtimeEvidence == null ? RuntimeEvidenceAssets.empty() : runtimeEvidence;
        actionQueue = actionQueue == null ? ActionQueue.empty() : actionQueue;
    }

    /**
     * Builds an overview from already-derived asset summaries and catalog rows.
     *
     * @param drafts draft summaries in scope
     * @param publications publication summaries in scope
     * @param operators catalog operators in scope
     * @param catalogDiagnostics catalog diagnostics in scope
     * @return visual asset overview
     */
    public static VisualAssetOverview from(List<GraphDraftSummary> drafts,
                                           List<VisualGraphPublicationSummary> publications,
                                           List<OperatorDefinition> operators,
                                           List<VisualDiagnostic> catalogDiagnostics) {
        return from(drafts, publications, operators, catalogDiagnostics, "", "", "");
    }

    /**
     * Builds an overview from already-derived asset summaries, catalog rows, and request scope.
     *
     * @param drafts draft summaries in scope
     * @param publications publication summaries in scope
     * @param operators catalog operators in scope
     * @param catalogDiagnostics catalog diagnostics in scope
     * @param tenantId tenant scope used for the read model, empty for all
     * @param namespace namespace scope used for the read model, empty for all
     * @param environment environment scope used for the read model, empty for all
     * @return visual asset overview
     */
    public static VisualAssetOverview from(List<GraphDraftSummary> drafts,
                                           List<VisualGraphPublicationSummary> publications,
                                           List<OperatorDefinition> operators,
                                           List<VisualDiagnostic> catalogDiagnostics,
                                           String tenantId,
                                           String namespace,
                                           String environment) {
        return from(
                drafts,
                publications,
                operators,
                catalogDiagnostics,
                tenantId,
                namespace,
                environment,
                DEFAULT_ACTION_ITEM_LIMIT
        );
    }

    /**
     * Builds an overview from asset summaries and catalog rows with a bounded action item window.
     *
     * @param drafts draft summaries in scope
     * @param publications publication summaries in scope
     * @param operators catalog operators in scope
     * @param catalogDiagnostics catalog diagnostics in scope
     * @param tenantId tenant scope used for the read model, empty for all
     * @param namespace namespace scope used for the read model, empty for all
     * @param environment environment scope used for the read model, empty for all
     * @param actionItemLimit requested number of action item details, clamped to the overview contract bounds
     * @return visual asset overview
     */
    public static VisualAssetOverview from(List<GraphDraftSummary> drafts,
                                           List<VisualGraphPublicationSummary> publications,
                                           List<OperatorDefinition> operators,
                                           List<VisualDiagnostic> catalogDiagnostics,
                                           String tenantId,
                                           String namespace,
                                           String environment,
                                           int actionItemLimit) {
        return from(
                drafts,
                publications,
                operators,
                catalogDiagnostics,
                tenantId,
                namespace,
                environment,
                actionItemLimit,
                0,
                "",
                "",
                ""
        );
    }

    /**
     * Builds an overview from asset summaries and catalog rows with a queryable action item window.
     *
     * @param drafts draft summaries in scope
     * @param publications publication summaries in scope
     * @param operators catalog operators in scope
     * @param catalogDiagnostics catalog diagnostics in scope
     * @param tenantId tenant scope used for the read model, empty for all
     * @param namespace namespace scope used for the read model, empty for all
     * @param environment environment scope used for the read model, empty for all
     * @param actionItemLimit requested number of action item details, clamped to the overview contract bounds
     * @param actionOffset zero-based action item offset after filtering
     * @param actionSeverity optional severity filter
     * @param actionType optional action type filter
     * @param actionTargetKind optional target kind filter
     * @param actionOperatorRef optional operator reference filter
     * @return visual asset overview
     */
    public static VisualAssetOverview from(List<GraphDraftSummary> drafts,
                                           List<VisualGraphPublicationSummary> publications,
                                           List<OperatorDefinition> operators,
                                           List<VisualDiagnostic> catalogDiagnostics,
                                           String tenantId,
                                           String namespace,
                                           String environment,
                                           int actionItemLimit,
                                           int actionOffset,
                                           String actionSeverity,
                                           String actionType,
                                           String actionTargetKind,
                                           String actionOperatorRef) {
        return from(
                drafts,
                publications,
                operators,
                catalogDiagnostics,
                Map.of(),
                tenantId,
                namespace,
                environment,
                actionItemLimit,
                actionOffset,
                actionSeverity,
                actionType,
                actionTargetKind,
                actionOperatorRef,
                ""
        );
    }

    /**
     * Builds an overview with operator-library ownership context for action routing.
     *
     * @param drafts draft summaries in scope
     * @param publications publication summaries in scope
     * @param operators catalog operators in scope
     * @param catalogDiagnostics catalog diagnostics in scope
     * @param operatorLibraryIdsByOperatorRef catalog-derived operatorRef to library owner map
     * @param tenantId tenant scope used for the read model, empty for all
     * @param namespace namespace scope used for the read model, empty for all
     * @param environment environment scope used for the read model, empty for all
     * @param actionItemLimit requested number of action item details, clamped to the overview contract bounds
     * @param actionOffset zero-based action item offset after filtering
     * @param actionSeverity optional severity filter
     * @param actionType optional action type filter
     * @param actionTargetKind optional target kind filter
     * @param actionOperatorRef optional operator reference filter
     * @param actionOperatorLibraryId optional owner operator library id filter
     * @return visual asset overview
     */
    public static VisualAssetOverview from(List<GraphDraftSummary> drafts,
                                           List<VisualGraphPublicationSummary> publications,
                                           List<OperatorDefinition> operators,
                                           List<VisualDiagnostic> catalogDiagnostics,
                                           Map<String, String> operatorLibraryIdsByOperatorRef,
                                           String tenantId,
                                           String namespace,
                                           String environment,
                                           int actionItemLimit,
                                           int actionOffset,
                                           String actionSeverity,
                                           String actionType,
                                           String actionTargetKind,
                                           String actionOperatorRef,
                                           String actionOperatorLibraryId) {
        return from(
                drafts,
                publications,
                operators,
                catalogDiagnostics,
                operatorLibraryIdsByOperatorRef,
                tenantId,
                namespace,
                environment,
                actionItemLimit,
                actionOffset,
                actionSeverity,
                actionType,
                actionTargetKind,
                actionOperatorRef,
                actionOperatorLibraryId,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    /**
     * Builds an overview with runtime evidence chain aggregates.
     *
     * @param drafts draft summaries in scope
     * @param publications publication summaries in scope
     * @param operators catalog operators in scope
     * @param catalogDiagnostics catalog diagnostics in scope
     * @param operatorLibraryIdsByOperatorRef catalog-derived operatorRef to library owner map
     * @param tenantId tenant scope used for the read model, empty for all
     * @param namespace namespace scope used for the read model, empty for all
     * @param environment environment scope used for the read model, empty for all
     * @param actionItemLimit requested number of action item details, clamped to the overview contract bounds
     * @param actionOffset zero-based action item offset after filtering
     * @param actionSeverity optional action severity filter
     * @param actionType optional action type filter
     * @param actionTargetKind optional action target kind filter
     * @param actionOperatorRef optional operator reference filter
     * @param actionOperatorLibraryId optional owner operator library id filter
     * @param actionHandoffLane optional runtime-plane responsibility lane filter
     * @param actionHandoffKind optional runtime-plane work kind filter
     * @param actionHandoffTarget optional runtime-plane routing target filter
     * @param actionReadinessState optional graph/operator readiness state filter
     * @param actionArtifactKind optional publication artifact kind filter
     * @param implementationBindings submitted runtime implementation binding records in scope
     * @param adapterActivations runtime adapter activation records in scope
     * @param rolloutObservations rollout observation records in scope
     * @param executableLoweringIntegrations executable lowering integration records in scope
     * @return visual asset overview
     */
    public static VisualAssetOverview from(List<GraphDraftSummary> drafts,
                                           List<VisualGraphPublicationSummary> publications,
                                           List<OperatorDefinition> operators,
                                           List<VisualDiagnostic> catalogDiagnostics,
                                           Map<String, String> operatorLibraryIdsByOperatorRef,
                                           String tenantId,
                                           String namespace,
                                           String environment,
                                           int actionItemLimit,
                                           int actionOffset,
                                           String actionSeverity,
                                           String actionType,
                                           String actionTargetKind,
                                           String actionOperatorRef,
                                           String actionOperatorLibraryId,
                                           String actionHandoffLane,
                                           String actionHandoffKind,
                                           String actionHandoffTarget,
                                           String actionReadinessState,
                                           String actionArtifactKind,
                                           List<VisualRuntimeBindingImplementationBinding> implementationBindings,
                                           List<VisualRuntimeAdapterActivation> adapterActivations,
                                           List<VisualRuntimeRolloutObservation> rolloutObservations,
                                           List<VisualExecutableLoweringIntegration> executableLoweringIntegrations) {
        return new VisualAssetOverview(
                SCHEMA_VERSION,
                Instant.now(),
                new AuthoringScope(tenantId, namespace, environment),
                DraftAssets.from(drafts),
                PublicationAssets.from(publications),
                CatalogAssets.from(operators, catalogDiagnostics),
                RuntimeEvidenceAssets.from(
                        implementationBindings,
                        adapterActivations,
                        rolloutObservations,
                        executableLoweringIntegrations),
                ActionQueue.from(
                        drafts,
                        publications,
                        operators,
                        operatorLibraryIdsByOperatorRef,
                        actionItemLimit,
                        actionOffset,
                        actionSeverity,
                        actionType,
                        actionTargetKind,
                        actionOperatorRef,
                        actionOperatorLibraryId,
                        actionHandoffLane,
                        actionHandoffKind,
                        actionHandoffTarget,
                        actionReadinessState,
                        actionArtifactKind,
                        implementationBindings,
                        adapterActivations,
                        rolloutObservations,
                        executableLoweringIntegrations
                )
        );
    }

    public static VisualAssetOverview from(List<GraphDraftSummary> drafts,
                                           List<VisualGraphPublicationSummary> publications,
                                           List<OperatorDefinition> operators,
                                           List<VisualDiagnostic> catalogDiagnostics,
                                           Map<String, String> operatorLibraryIdsByOperatorRef,
                                           String tenantId,
                                           String namespace,
                                           String environment,
                                           int actionItemLimit,
                                           int actionOffset,
                                           String actionSeverity,
                                           String actionType,
                                           String actionTargetKind,
                                           String actionOperatorRef,
                                           String actionOperatorLibraryId,
                                           List<VisualRuntimeBindingImplementationBinding> implementationBindings,
                                           List<VisualRuntimeAdapterActivation> adapterActivations,
                                           List<VisualRuntimeRolloutObservation> rolloutObservations,
                                           List<VisualExecutableLoweringIntegration> executableLoweringIntegrations) {
        return from(
                drafts,
                publications,
                operators,
                catalogDiagnostics,
                operatorLibraryIdsByOperatorRef,
                tenantId,
                namespace,
                environment,
                actionItemLimit,
                actionOffset,
                actionSeverity,
                actionType,
                actionTargetKind,
                actionOperatorRef,
                actionOperatorLibraryId,
                "",
                "",
                "",
                "",
                "",
                implementationBindings,
                adapterActivations,
                rolloutObservations,
                executableLoweringIntegrations
        );
    }

    public static VisualAssetOverview from(List<GraphDraftSummary> drafts,
                                           List<VisualGraphPublicationSummary> publications,
                                           List<OperatorDefinition> operators,
                                           List<VisualDiagnostic> catalogDiagnostics,
                                           String tenantId,
                                           String namespace,
                                           String environment,
                                           int actionItemLimit,
                                           int actionOffset,
                                           String actionSeverity,
                                           String actionType,
                                           String actionTargetKind) {
        return from(
                drafts,
                publications,
                operators,
                catalogDiagnostics,
                tenantId,
                namespace,
                environment,
                actionItemLimit,
                actionOffset,
                actionSeverity,
                actionType,
                actionTargetKind,
                ""
        );
    }

    /**
     * Authoring scope used to derive an overview.
     *
     * @param tenantId tenant filter, empty when unfiltered
     * @param namespace namespace filter, empty when unfiltered
     * @param environment environment filter, empty when unfiltered
     * @param filtered true when at least one scope dimension was requested
     */
    public record AuthoringScope(
            String tenantId,
            String namespace,
            String environment,
            boolean filtered
    ) {
        public AuthoringScope(String tenantId, String namespace, String environment) {
            this(
                    tenantId == null ? "" : tenantId.trim(),
                    namespace == null ? "" : namespace.trim(),
                    environment == null ? "" : environment.trim(),
                    !isBlank(tenantId) || !isBlank(namespace) || !isBlank(environment)
            );
        }

        public AuthoringScope {
            tenantId = tenantId == null ? "" : tenantId.trim();
            namespace = namespace == null ? "" : namespace.trim();
            environment = environment == null ? "" : environment.trim();
            filtered = !tenantId.isBlank() || !namespace.isBlank() || !environment.isBlank();
        }

        static AuthoringScope all() {
            return new AuthoringScope("", "", "");
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    /**
     * Draft asset aggregate.
     */
    public record DraftAssets(
            int total,
            int activeCount,
            int recoverableDeletedCount,
            int validCount,
            int invalidCount,
            int diagnosticCount,
            int errorCount,
            int warningCount,
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
            Map<String, Integer> graphReadinessStateCounts,
            Map<String, Integer> publishableArtifactKindCounts,
            Map<String, Integer> sourceKindCounts,
            Map<String, Integer> operatorLibraryIdCounts,
            Map<String, Integer> loweringModeCounts,
            Map<String, Integer> operatorRuntimeReadinessStateCounts
    ) {
        public DraftAssets {
            schemaCompatibilityStateCounts = immutableCounts(schemaCompatibilityStateCounts);
            graphReadinessStateCounts = immutableCounts(graphReadinessStateCounts);
            publishableArtifactKindCounts = immutableCounts(publishableArtifactKindCounts);
            sourceKindCounts = immutableCounts(sourceKindCounts);
            operatorLibraryIdCounts = immutableCounts(operatorLibraryIdCounts);
            loweringModeCounts = immutableCounts(loweringModeCounts);
            operatorRuntimeReadinessStateCounts = immutableCounts(operatorRuntimeReadinessStateCounts);
        }

        static DraftAssets from(List<GraphDraftSummary> summaries) {
            List<GraphDraftSummary> safeSummaries = summaries == null ? List.of() : summaries.stream()
                    .filter(summary -> summary != null)
                    .toList();
            Map<String, Integer> graphReadiness = new LinkedHashMap<>();
            Map<String, Integer> publishableKinds = new LinkedHashMap<>();
            Map<String, Integer> sourceKinds = new LinkedHashMap<>();
            Map<String, Integer> operatorLibraries = new LinkedHashMap<>();
            Map<String, Integer> loweringModes = new LinkedHashMap<>();
            Map<String, Integer> runtimeReadiness = new LinkedHashMap<>();
            Map<String, Integer> schemaCompatibilityStates = new LinkedHashMap<>();
            int activeCount = 0;
            int recoverableDeletedCount = 0;
            int validCount = 0;
            int invalidCount = 0;
            int diagnosticCount = 0;
            int errorCount = 0;
            int warningCount = 0;
            int nodeCount = 0;
            int edgeCount = 0;
            int operatorDependencyCount = 0;
            int missingOperatorCount = 0;
            int scopeMismatchOperatorCount = 0;
            int driftedFingerprintCount = 0;
            int missingFingerprintCount = 0;
            int schemaBreakingDriftCount = 0;
            int schemaCompatibleDriftCount = 0;
            for (GraphDraftSummary summary : safeSummaries) {
                if (summary.active()) {
                    activeCount++;
                } else {
                    recoverableDeletedCount++;
                }
                if (summary.valid()) {
                    validCount++;
                } else {
                    invalidCount++;
                }
                diagnosticCount += summary.diagnosticCount();
                errorCount += summary.errorCount();
                warningCount += summary.warningCount();
                nodeCount += summary.nodeCount();
                edgeCount += summary.edgeCount();
                operatorDependencyCount += summary.operatorDependencyCount();
                missingOperatorCount += summary.missingOperatorCount();
                scopeMismatchOperatorCount += summary.scopeMismatchOperatorCount();
                driftedFingerprintCount += summary.driftedFingerprintCount();
                missingFingerprintCount += summary.missingFingerprintCount();
                schemaBreakingDriftCount += summary.schemaBreakingDriftCount();
                schemaCompatibleDriftCount += summary.schemaCompatibleDriftCount();
                VisualGraphReadiness readiness = summary.readiness();
                incrementNormalized(graphReadiness, readiness == null ? "" : readiness.state());
                if (readiness != null) {
                    for (String kind : readiness.artifactKinds()) {
                        incrementUpper(publishableKinds, kind);
                    }
                }
                mergeNormalized(sourceKinds, summary.sourceKindCounts());
                mergeText(operatorLibraries, summary.operatorLibraryIdCounts());
                mergeNormalized(loweringModes, summary.loweringModeCounts());
                mergeNormalized(runtimeReadiness, summary.runtimeReadinessStateCounts());
                mergeNormalized(schemaCompatibilityStates, summary.schemaCompatibilityStateCounts());
            }
            return new DraftAssets(
                    safeSummaries.size(),
                    activeCount,
                    recoverableDeletedCount,
                    validCount,
                    invalidCount,
                    diagnosticCount,
                    errorCount,
                    warningCount,
                    nodeCount,
                    edgeCount,
                    operatorDependencyCount,
                    missingOperatorCount,
                    scopeMismatchOperatorCount,
                    driftedFingerprintCount,
                    missingFingerprintCount,
                    schemaBreakingDriftCount,
                    schemaCompatibleDriftCount,
                    schemaCompatibilityStates,
                    graphReadiness,
                    publishableKinds,
                    sourceKinds,
                    operatorLibraries,
                    loweringModes,
                    runtimeReadiness
            );
        }

        static DraftAssets empty() {
            return from(List.of());
        }
    }

    /**
     * Immutable publication asset aggregate.
     */
    public record PublicationAssets(
            int total,
            int executableArtifactCount,
            int designArtifactCount,
            int validCount,
            int invalidCount,
            int diagnosticCount,
            int errorCount,
            int warningCount,
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
            Map<String, Integer> artifactKindCounts,
            Map<String, Integer> graphReadinessStateCounts,
            Map<String, Integer> sourceKindCounts,
            Map<String, Integer> operatorLibraryIdCounts,
            Map<String, Integer> loweringModeCounts,
            Map<String, Integer> operatorRuntimeReadinessStateCounts
    ) {
        public PublicationAssets {
            schemaCompatibilityStateCounts = immutableCounts(schemaCompatibilityStateCounts);
            artifactKindCounts = immutableCounts(artifactKindCounts);
            graphReadinessStateCounts = immutableCounts(graphReadinessStateCounts);
            sourceKindCounts = immutableCounts(sourceKindCounts);
            operatorLibraryIdCounts = immutableCounts(operatorLibraryIdCounts);
            loweringModeCounts = immutableCounts(loweringModeCounts);
            operatorRuntimeReadinessStateCounts = immutableCounts(operatorRuntimeReadinessStateCounts);
        }

        static PublicationAssets from(List<VisualGraphPublicationSummary> summaries) {
            List<VisualGraphPublicationSummary> safeSummaries = summaries == null ? List.of() : summaries.stream()
                    .filter(summary -> summary != null)
                    .toList();
            Map<String, Integer> artifactKinds = new LinkedHashMap<>();
            Map<String, Integer> graphReadiness = new LinkedHashMap<>();
            Map<String, Integer> sourceKinds = new LinkedHashMap<>();
            Map<String, Integer> operatorLibraries = new LinkedHashMap<>();
            Map<String, Integer> loweringModes = new LinkedHashMap<>();
            Map<String, Integer> runtimeReadiness = new LinkedHashMap<>();
            Map<String, Integer> schemaCompatibilityStates = new LinkedHashMap<>();
            int executableArtifactCount = 0;
            int designArtifactCount = 0;
            int validCount = 0;
            int invalidCount = 0;
            int diagnosticCount = 0;
            int errorCount = 0;
            int warningCount = 0;
            int nodeCount = 0;
            int edgeCount = 0;
            int operatorDependencyCount = 0;
            int missingOperatorCount = 0;
            int scopeMismatchOperatorCount = 0;
            int driftedFingerprintCount = 0;
            int missingFingerprintCount = 0;
            int schemaBreakingDriftCount = 0;
            int schemaCompatibleDriftCount = 0;
            for (VisualGraphPublicationSummary summary : safeSummaries) {
                String artifactKind = normalizeArtifactKind(summary.artifactKind());
                if (VisualGraphPublication.ARTIFACT_DESIGN.equals(artifactKind)) {
                    designArtifactCount++;
                } else {
                    executableArtifactCount++;
                }
                incrementUpper(artifactKinds, artifactKind);
                if (summary.valid()) {
                    validCount++;
                } else {
                    invalidCount++;
                }
                diagnosticCount += summary.diagnosticCount();
                errorCount += summary.errorCount();
                warningCount += summary.warningCount();
                nodeCount += summary.nodeCount();
                edgeCount += summary.edgeCount();
                operatorDependencyCount += summary.operatorDependencyCount();
                missingOperatorCount += summary.missingOperatorCount();
                scopeMismatchOperatorCount += summary.scopeMismatchOperatorCount();
                driftedFingerprintCount += summary.driftedFingerprintCount();
                missingFingerprintCount += summary.missingFingerprintCount();
                schemaBreakingDriftCount += summary.schemaBreakingDriftCount();
                schemaCompatibleDriftCount += summary.schemaCompatibleDriftCount();
                VisualGraphReadiness readiness = summary.readiness();
                incrementNormalized(graphReadiness, readiness == null ? "" : readiness.state());
                mergeNormalized(sourceKinds, summary.sourceKindCounts());
                mergeText(operatorLibraries, summary.operatorLibraryIdCounts());
                mergeNormalized(loweringModes, summary.loweringModeCounts());
                mergeNormalized(runtimeReadiness, summary.runtimeReadinessStateCounts());
                mergeNormalized(schemaCompatibilityStates, summary.schemaCompatibilityStateCounts());
            }
            return new PublicationAssets(
                    safeSummaries.size(),
                    executableArtifactCount,
                    designArtifactCount,
                    validCount,
                    invalidCount,
                    diagnosticCount,
                    errorCount,
                    warningCount,
                    nodeCount,
                    edgeCount,
                    operatorDependencyCount,
                    missingOperatorCount,
                    scopeMismatchOperatorCount,
                    driftedFingerprintCount,
                    missingFingerprintCount,
                    schemaBreakingDriftCount,
                    schemaCompatibleDriftCount,
                    schemaCompatibilityStates,
                    artifactKinds,
                    graphReadiness,
                    sourceKinds,
                    operatorLibraries,
                    loweringModes,
                    runtimeReadiness
            );
        }

        static PublicationAssets empty() {
            return from(List.of());
        }
    }

    /**
     * Operator catalog aggregate.
     */
    public record CatalogAssets(
            int totalOperators,
            int diagnosticCount,
            int errorCount,
            int warningCount,
            OperatorCatalogFacets facets
    ) {
        public CatalogAssets {
            facets = facets == null ? OperatorCatalogFacets.from(List.of()) : facets;
        }

        static CatalogAssets from(List<OperatorDefinition> operators, List<VisualDiagnostic> diagnostics) {
            List<OperatorDefinition> safeOperators = operators == null ? List.of() : operators;
            List<VisualDiagnostic> safeDiagnostics = diagnostics == null ? List.of() : diagnostics;
            int errorCount = (int) safeDiagnostics.stream().filter(VisualDiagnostic::error).count();
            int warningCount = (int) safeDiagnostics.stream()
                    .filter(diagnostic -> "WARNING".equalsIgnoreCase(diagnostic.level()))
                    .count();
            OperatorCatalogFacets facets = OperatorCatalogFacets.from(safeOperators);
            return new CatalogAssets(
                    facets.total(),
                    safeDiagnostics.size(),
                    errorCount,
                    warningCount,
                    facets
            );
        }

        static CatalogAssets empty() {
            return from(List.of(), List.of());
        }
    }

    /**
     * Runtime evidence aggregate for schema-only/design-only operators moving toward executable binding.
     *
     * @param implementationBindingCount total runtime implementation binding records
     * @param activeBoundImplementationCount currently bound implementation records
     * @param readyToBindImplementationCount accepted implementation records waiting for bind
     * @param reviewRequiredImplementationCount implementation records that still require review
     * @param supersededImplementationCount superseded implementation records
     * @param unboundImplementationCount unbound implementation records
     * @param failedImplementationCount failed implementation records
     * @param adapterActivationCount total adapter activation records
     * @param activeAdapterActivationCount active adapter activation records
     * @param failedAdapterActivationCount failed adapter activation records
     * @param rolloutObservationCount total rollout observation records
     * @param degradedRolloutObservationCount degraded rollout observations
     * @param failedRolloutObservationCount failed rollout observations
     * @param rolledBackRolloutObservationCount rolled-back rollout observations
     * @param rollbackTriggeredObservationCount rollout observations with rollbackTriggered=true
     * @param executableLoweringIntegrationCount total executable lowering integration records
     * @param activeExecutableLoweringIntegrationCount active executable lowering integration records
     * @param failedExecutableLoweringIntegrationCount failed executable lowering integration records
     * @param completeEvidenceChainCount active bound implementation chains with matching active activation and integration
     * @param activeBindingMissingActivationCount active bound implementations without matching active activation
     * @param activeActivationMissingIntegrationCount active adapter activations without matching active lowering integration
     * @param orphanActiveActivationCount active activations whose binding is missing, inactive, or revision/fingerprint mismatched
     * @param orphanActiveIntegrationCount active integrations whose activation is missing, inactive, or revision/fingerprint mismatched
     * @param failedEvidenceRecordCount failed records across implementation, activation, rollout, and integration evidence
     * @param evidenceChainHealthCounts normalized runtime evidence chain health condition counts
     * @param implementationStateCounts implementation records by lifecycle state
     * @param activationStateCounts adapter activation records by lifecycle state
     * @param rolloutStateCounts rollout observations by state
     * @param integrationStateCounts executable lowering integration records by lifecycle state
     * @param operatorRefCounts evidence records by operatorRef
     * @param bindingIdCounts evidence records by bindingId
     * @param activationIdCounts activation/integration/rollout records by activationId
     * @param adapterKindCounts evidence records by adapter kind
     * @param runtimeEnvironmentCounts runtime evidence records by runtime environment
     * @param loweringModeCounts executable lowering integration records by lowering mode
     * @param rolloutSignalCounts rollout observation guardrail signals by signal name
     * @param breachedRolloutSignalCounts breached rollout observation guardrail signals by signal name
     */
    public record RuntimeEvidenceAssets(
            int implementationBindingCount,
            int activeBoundImplementationCount,
            int readyToBindImplementationCount,
            int reviewRequiredImplementationCount,
            int supersededImplementationCount,
            int unboundImplementationCount,
            int failedImplementationCount,
            int adapterActivationCount,
            int activeAdapterActivationCount,
            int failedAdapterActivationCount,
            int rolloutObservationCount,
            int degradedRolloutObservationCount,
            int failedRolloutObservationCount,
            int rolledBackRolloutObservationCount,
            int rollbackTriggeredObservationCount,
            int executableLoweringIntegrationCount,
            int activeExecutableLoweringIntegrationCount,
            int failedExecutableLoweringIntegrationCount,
            int completeEvidenceChainCount,
            int activeBindingMissingActivationCount,
            int activeActivationMissingIntegrationCount,
            int orphanActiveActivationCount,
            int orphanActiveIntegrationCount,
            int failedEvidenceRecordCount,
            Map<String, Integer> evidenceChainHealthCounts,
            Map<String, Integer> implementationStateCounts,
            Map<String, Integer> activationStateCounts,
            Map<String, Integer> rolloutStateCounts,
            Map<String, Integer> integrationStateCounts,
            Map<String, Integer> operatorRefCounts,
            Map<String, Integer> bindingIdCounts,
            Map<String, Integer> activationIdCounts,
            Map<String, Integer> adapterKindCounts,
            Map<String, Integer> runtimeEnvironmentCounts,
            Map<String, Integer> loweringModeCounts,
            Map<String, Integer> rolloutSignalCounts,
            Map<String, Integer> breachedRolloutSignalCounts
    ) {
        public RuntimeEvidenceAssets {
            evidenceChainHealthCounts = immutableCounts(evidenceChainHealthCounts);
            implementationStateCounts = immutableCounts(implementationStateCounts);
            activationStateCounts = immutableCounts(activationStateCounts);
            rolloutStateCounts = immutableCounts(rolloutStateCounts);
            integrationStateCounts = immutableCounts(integrationStateCounts);
            operatorRefCounts = immutableCounts(operatorRefCounts);
            bindingIdCounts = immutableCounts(bindingIdCounts);
            activationIdCounts = immutableCounts(activationIdCounts);
            adapterKindCounts = immutableCounts(adapterKindCounts);
            runtimeEnvironmentCounts = immutableCounts(runtimeEnvironmentCounts);
            loweringModeCounts = immutableCounts(loweringModeCounts);
            rolloutSignalCounts = immutableCounts(rolloutSignalCounts);
            breachedRolloutSignalCounts = immutableCounts(breachedRolloutSignalCounts);
        }

        static RuntimeEvidenceAssets from(
                List<VisualRuntimeBindingImplementationBinding> implementationBindings,
                List<VisualRuntimeAdapterActivation> adapterActivations,
                List<VisualRuntimeRolloutObservation> rolloutObservations,
                List<VisualExecutableLoweringIntegration> executableLoweringIntegrations) {
            List<VisualRuntimeBindingImplementationBinding> safeBindings = implementationBindings == null
                    ? List.of()
                    : implementationBindings.stream().filter(binding -> binding != null).toList();
            List<VisualRuntimeAdapterActivation> safeActivations = adapterActivations == null
                    ? List.of()
                    : adapterActivations.stream().filter(activation -> activation != null).toList();
            List<VisualRuntimeRolloutObservation> safeObservations = rolloutObservations == null
                    ? List.of()
                    : rolloutObservations.stream().filter(observation -> observation != null).toList();
            List<VisualExecutableLoweringIntegration> safeIntegrations = executableLoweringIntegrations == null
                    ? List.of()
                    : executableLoweringIntegrations.stream().filter(integration -> integration != null).toList();

            Map<String, Integer> implementationStates = new LinkedHashMap<>();
            Map<String, Integer> activationStates = new LinkedHashMap<>();
            Map<String, Integer> rolloutStates = new LinkedHashMap<>();
            Map<String, Integer> integrationStates = new LinkedHashMap<>();
            Map<String, Integer> operatorRefs = new LinkedHashMap<>();
            Map<String, Integer> bindingIds = new LinkedHashMap<>();
            Map<String, Integer> activationIds = new LinkedHashMap<>();
            Map<String, Integer> adapterKinds = new LinkedHashMap<>();
            Map<String, Integer> runtimeEnvironments = new LinkedHashMap<>();
            Map<String, Integer> loweringModes = new LinkedHashMap<>();
            Map<String, Integer> rolloutSignals = new LinkedHashMap<>();
            Map<String, Integer> breachedRolloutSignals = new LinkedHashMap<>();
            Map<String, VisualRuntimeBindingImplementationBinding> activeBindingsById = new LinkedHashMap<>();
            Map<String, VisualRuntimeAdapterActivation> activeActivationsById = new LinkedHashMap<>();
            Map<String, VisualRuntimeAdapterActivation> activeActivationsByBindingId = new LinkedHashMap<>();
            Map<String, VisualExecutableLoweringIntegration> activeIntegrationsByActivationId = new LinkedHashMap<>();

            int activeBoundImplementationCount = 0;
            int readyToBindImplementationCount = 0;
            int reviewRequiredImplementationCount = 0;
            int supersededImplementationCount = 0;
            int unboundImplementationCount = 0;
            int failedImplementationCount = 0;
            for (VisualRuntimeBindingImplementationBinding binding : safeBindings) {
                String state = normalizeFacetValue(binding.state());
                incrementNormalized(implementationStates, state);
                incrementText(operatorRefs, binding.operatorRef());
                incrementText(bindingIds, binding.bindingId());
                if (binding.bound()) {
                    activeBoundImplementationCount++;
                    activeBindingsById.put(binding.bindingId(), binding);
                } else if (binding.readyToBind()) {
                    readyToBindImplementationCount++;
                } else if (binding.requiresReview()) {
                    reviewRequiredImplementationCount++;
                } else if (binding.superseded()) {
                    supersededImplementationCount++;
                } else if (binding.unbound()) {
                    unboundImplementationCount++;
                } else if (binding.failed()) {
                    failedImplementationCount++;
                }
            }

            int activeAdapterActivationCount = 0;
            int failedAdapterActivationCount = 0;
            for (VisualRuntimeAdapterActivation activation : safeActivations) {
                incrementNormalized(activationStates, activation.state());
                incrementText(operatorRefs, activation.operatorRef());
                incrementText(bindingIds, activation.bindingId());
                incrementText(activationIds, activation.activationId());
                incrementNormalized(adapterKinds, activation.adapterKind());
                incrementText(runtimeEnvironments, activation.runtimeEnvironment());
                if (VisualRuntimeAdapterActivation.STATE_ACTIVE.equals(activation.state())) {
                    activeAdapterActivationCount++;
                    activeActivationsById.put(activation.activationId(), activation);
                    activeActivationsByBindingId.putIfAbsent(activation.bindingId(), activation);
                } else if (VisualRuntimeAdapterActivation.STATE_FAILED.equals(activation.state())) {
                    failedAdapterActivationCount++;
                }
            }

            int degradedRolloutObservationCount = 0;
            int failedRolloutObservationCount = 0;
            int rolledBackRolloutObservationCount = 0;
            int rollbackTriggeredObservationCount = 0;
            int rolloutRiskObservationCount = 0;
            for (VisualRuntimeRolloutObservation observation : safeObservations) {
                incrementNormalized(rolloutStates, observation.state());
                incrementText(operatorRefs, observation.operatorRef());
                incrementText(bindingIds, observation.bindingId());
                incrementText(activationIds, observation.activationId());
                incrementNormalized(adapterKinds, observation.adapterKind());
                incrementText(runtimeEnvironments, observation.runtimeEnvironment());
                if (VisualRuntimeRolloutObservation.STATE_DEGRADED.equals(observation.state())) {
                    degradedRolloutObservationCount++;
                } else if (VisualRuntimeRolloutObservation.STATE_FAILED.equals(observation.state())) {
                    failedRolloutObservationCount++;
                } else if (VisualRuntimeRolloutObservation.STATE_ROLLED_BACK.equals(observation.state())) {
                    rolledBackRolloutObservationCount++;
                }
                if (observation.rollbackTriggered()) {
                    rollbackTriggeredObservationCount++;
                }
                boolean signalBreached = observation.rolloutSignals().stream()
                        .filter(signal -> signal != null)
                        .anyMatch(VisualRuntimeRolloutObservation.RolloutSignal::breached);
                if (VisualRuntimeRolloutObservation.STATE_DEGRADED.equals(observation.state())
                        || VisualRuntimeRolloutObservation.STATE_FAILED.equals(observation.state())
                        || VisualRuntimeRolloutObservation.STATE_ROLLED_BACK.equals(observation.state())
                        || observation.rollbackTriggered()
                        || signalBreached) {
                    rolloutRiskObservationCount++;
                }
                for (VisualRuntimeRolloutObservation.RolloutSignal signal : observation.rolloutSignals()) {
                    if (signal == null || signal.name().isBlank()) {
                        continue;
                    }
                    incrementNormalized(rolloutSignals, signal.name());
                    if (signal.breached()) {
                        incrementNormalized(breachedRolloutSignals, signal.name());
                    }
                }
            }

            int activeExecutableLoweringIntegrationCount = 0;
            int failedExecutableLoweringIntegrationCount = 0;
            for (VisualExecutableLoweringIntegration integration : safeIntegrations) {
                incrementNormalized(integrationStates, integration.state());
                incrementText(operatorRefs, integration.operatorRef());
                incrementText(bindingIds, integration.bindingId());
                incrementText(activationIds, integration.activationId());
                incrementNormalized(adapterKinds, integration.adapterKind());
                incrementText(runtimeEnvironments, integration.runtimeEnvironment());
                incrementNormalized(loweringModes, integration.loweringMode());
                if (VisualExecutableLoweringIntegration.STATE_ACTIVE.equals(integration.state())) {
                    activeExecutableLoweringIntegrationCount++;
                    activeIntegrationsByActivationId.putIfAbsent(integration.activationId(), integration);
                } else if (VisualExecutableLoweringIntegration.STATE_FAILED.equals(integration.state())) {
                    failedExecutableLoweringIntegrationCount++;
                }
            }

            int completeEvidenceChainCount = 0;
            int activeBindingMissingActivationCount = 0;
            for (VisualRuntimeBindingImplementationBinding binding : activeBindingsById.values()) {
                VisualRuntimeAdapterActivation activation = activeActivationsByBindingId.get(binding.bindingId());
                if (!activationMatchesBinding(activation, binding)) {
                    activeBindingMissingActivationCount++;
                    continue;
                }
                VisualExecutableLoweringIntegration integration =
                        activeIntegrationsByActivationId.get(activation.activationId());
                if (integrationMatchesActivationAndBinding(integration, activation, binding)) {
                    completeEvidenceChainCount++;
                }
            }

            int activeActivationMissingIntegrationCount = 0;
            int orphanActiveActivationCount = 0;
            for (VisualRuntimeAdapterActivation activation : activeActivationsById.values()) {
                VisualRuntimeBindingImplementationBinding binding = activeBindingsById.get(activation.bindingId());
                if (!activationMatchesBinding(activation, binding)) {
                    orphanActiveActivationCount++;
                }
                VisualExecutableLoweringIntegration integration =
                        activeIntegrationsByActivationId.get(activation.activationId());
                if (!integrationMatchesActivationAndBinding(integration, activation, binding)) {
                    activeActivationMissingIntegrationCount++;
                }
            }

            int orphanActiveIntegrationCount = 0;
            for (VisualExecutableLoweringIntegration integration : activeIntegrationsByActivationId.values()) {
                VisualRuntimeAdapterActivation activation = activeActivationsById.get(integration.activationId());
                VisualRuntimeBindingImplementationBinding binding =
                        activation == null ? null : activeBindingsById.get(activation.bindingId());
                if (!integrationMatchesActivationAndBinding(integration, activation, binding)) {
                    orphanActiveIntegrationCount++;
                }
            }

            int failedEvidenceRecordCount = failedImplementationCount
                    + failedAdapterActivationCount
                    + failedRolloutObservationCount
                    + failedExecutableLoweringIntegrationCount;
            Map<String, Integer> evidenceChainHealth = evidenceChainHealthCounts(
                    completeEvidenceChainCount,
                    activeBindingMissingActivationCount,
                    activeActivationMissingIntegrationCount,
                    orphanActiveActivationCount,
                    orphanActiveIntegrationCount,
                    failedEvidenceRecordCount,
                    rolloutRiskObservationCount
            );

            return new RuntimeEvidenceAssets(
                    safeBindings.size(),
                    activeBoundImplementationCount,
                    readyToBindImplementationCount,
                    reviewRequiredImplementationCount,
                    supersededImplementationCount,
                    unboundImplementationCount,
                    failedImplementationCount,
                    safeActivations.size(),
                    activeAdapterActivationCount,
                    failedAdapterActivationCount,
                    safeObservations.size(),
                    degradedRolloutObservationCount,
                    failedRolloutObservationCount,
                    rolledBackRolloutObservationCount,
                    rollbackTriggeredObservationCount,
                    safeIntegrations.size(),
                    activeExecutableLoweringIntegrationCount,
                    failedExecutableLoweringIntegrationCount,
                    completeEvidenceChainCount,
                    activeBindingMissingActivationCount,
                    activeActivationMissingIntegrationCount,
                    orphanActiveActivationCount,
                    orphanActiveIntegrationCount,
                    failedEvidenceRecordCount,
                    evidenceChainHealth,
                    implementationStates,
                    activationStates,
                    rolloutStates,
                    integrationStates,
                    operatorRefs,
                    bindingIds,
                    activationIds,
                    adapterKinds,
                    runtimeEnvironments,
                    loweringModes,
                    rolloutSignals,
                    breachedRolloutSignals
            );
        }

        private static Map<String, Integer> evidenceChainHealthCounts(int completeEvidenceChainCount,
                                                                      int activeBindingMissingActivationCount,
                                                                      int activeActivationMissingIntegrationCount,
                                                                      int orphanActiveActivationCount,
                                                                      int orphanActiveIntegrationCount,
                                                                      int failedEvidenceRecordCount,
                                                                      int rolloutRiskObservationCount) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            putPositive(counts, "complete", completeEvidenceChainCount);
            putPositive(counts, "partial", activeBindingMissingActivationCount
                    + activeActivationMissingIntegrationCount
                    + orphanActiveActivationCount
                    + orphanActiveIntegrationCount);
            putPositive(counts, "missing-activation", activeBindingMissingActivationCount);
            putPositive(counts, "missing-integration", activeActivationMissingIntegrationCount);
            putPositive(counts, "orphan-activation", orphanActiveActivationCount);
            putPositive(counts, "orphan-integration", orphanActiveIntegrationCount);
            putPositive(counts, "failed-record", failedEvidenceRecordCount);
            putPositive(counts, "rollout-risk", rolloutRiskObservationCount);
            return counts;
        }

        private static void putPositive(Map<String, Integer> counts, String key, int value) {
            if (value > 0) {
                counts.put(key, value);
            }
        }

        static RuntimeEvidenceAssets empty() {
            return from(List.of(), List.of(), List.of(), List.of());
        }

        private static boolean activationMatchesBinding(VisualRuntimeAdapterActivation activation,
                                                        VisualRuntimeBindingImplementationBinding binding) {
            return activation != null
                    && binding != null
                    && binding.bound()
                    && VisualRuntimeAdapterActivation.STATE_ACTIVE.equals(activation.state())
                    && activation.bindingId().equals(binding.bindingId())
                    && activation.bindingRevision() == binding.revision()
                    && activation.operatorRef().equals(binding.operatorRef())
                    && activation.operatorFingerprint().equals(binding.operatorFingerprint());
        }

        private static boolean integrationMatchesActivationAndBinding(
                VisualExecutableLoweringIntegration integration,
                VisualRuntimeAdapterActivation activation,
                VisualRuntimeBindingImplementationBinding binding) {
            return integration != null
                    && activationMatchesBinding(activation, binding)
                    && VisualExecutableLoweringIntegration.STATE_ACTIVE.equals(integration.state())
                    && integration.activationId().equals(activation.activationId())
                    && integration.activationRevision() == activation.revision()
                    && integration.bindingId().equals(binding.bindingId())
                    && integration.bindingRevision() == binding.revision()
                    && integration.operatorRef().equals(binding.operatorRef())
                    && integration.operatorFingerprint().equals(binding.operatorFingerprint());
        }
    }

    /**
     * Server-derived next-action queue for the visual control plane.
     *
     * @param total action items after query filtering and before display limiting
     * @param unfilteredTotal total action items in the authoring scope before action filters
     * @param displayedCount returned action item count
     * @param itemLimit normalized maximum number of action item details returned
     * @param offset zero-based offset after query filtering
     * @param hasMore true when more filtered action items exist after the returned window
     * @param filter normalized query filter applied to the action queue
     * @param errorCount error-severity action count
     * @param warningCount warning-severity action count
     * @param infoCount info-severity action count
     * @param actionTypeCounts action counts by machine-readable action type
     * @param operatorRefCounts action counts by related operator reference
     * @param operatorLibraryIdCounts action counts by owner operator library id
     * @param handoffLaneCounts action counts by runtime-plane handoff lane
     * @param handoffKindCounts action counts by runtime-plane handoff kind
     * @param handoffTargetCounts action counts by runtime-plane handoff target
     * @param readinessStateCounts action counts by graph/operator readiness state
     * @param artifactKindCounts action counts by publication artifact kind
     * @param items first high-priority action items
     */
    public record ActionQueue(
            int total,
            int unfilteredTotal,
            int displayedCount,
            int itemLimit,
            int offset,
            boolean hasMore,
            ActionFilter filter,
            int errorCount,
            int warningCount,
            int infoCount,
            Map<String, Integer> actionTypeCounts,
            Map<String, Integer> operatorRefCounts,
            Map<String, Integer> operatorLibraryIdCounts,
            Map<String, Integer> handoffLaneCounts,
            Map<String, Integer> handoffKindCounts,
            Map<String, Integer> handoffTargetCounts,
            Map<String, Integer> readinessStateCounts,
            Map<String, Integer> artifactKindCounts,
            List<ActionItem> items
    ) {
        public ActionQueue(int total,
                           int displayedCount,
                           int itemLimit,
                           int errorCount,
                           int warningCount,
                           int infoCount,
                           Map<String, Integer> actionTypeCounts,
                           List<ActionItem> items) {
            this(
                    total,
                    total,
                    displayedCount,
                    itemLimit,
                    0,
                    false,
                    ActionFilter.all(),
                    errorCount,
                    warningCount,
                    infoCount,
                    actionTypeCounts,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    items
            );
        }

        public ActionQueue {
            total = Math.max(0, total);
            unfilteredTotal = Math.max(total, unfilteredTotal);
            offset = Math.max(0, offset);
            itemLimit = normalizeActionItemLimit(itemLimit);
            filter = filter == null ? ActionFilter.all() : filter;
            actionTypeCounts = immutableCounts(actionTypeCounts);
            operatorRefCounts = immutableCounts(operatorRefCounts);
            operatorLibraryIdCounts = immutableCounts(operatorLibraryIdCounts);
            handoffLaneCounts = immutableCounts(handoffLaneCounts);
            handoffKindCounts = immutableCounts(handoffKindCounts);
            handoffTargetCounts = immutableCounts(handoffTargetCounts);
            readinessStateCounts = immutableCounts(readinessStateCounts);
            artifactKindCounts = immutableCounts(artifactKindCounts);
            items = items == null ? List.of() : List.copyOf(items);
            displayedCount = items.size();
            hasMore = offset + displayedCount < total;
        }

        static ActionQueue from(List<GraphDraftSummary> drafts,
                                List<VisualGraphPublicationSummary> publications,
                                List<OperatorDefinition> operators) {
            return from(drafts, publications, operators, DEFAULT_ACTION_ITEM_LIMIT);
        }

        static ActionQueue from(List<GraphDraftSummary> drafts,
                                List<VisualGraphPublicationSummary> publications,
                                List<OperatorDefinition> operators,
                                int actionItemLimit) {
            return from(drafts, publications, operators, actionItemLimit, 0, "", "", "");
        }

        static ActionQueue from(List<GraphDraftSummary> drafts,
                                List<VisualGraphPublicationSummary> publications,
                                List<OperatorDefinition> operators,
                                int actionItemLimit,
                                int actionOffset,
                                String actionSeverity,
                                String actionType,
                                String actionTargetKind) {
            return from(drafts, publications, operators, actionItemLimit, actionOffset, actionSeverity,
                    actionType, actionTargetKind, "");
        }

        static ActionQueue from(List<GraphDraftSummary> drafts,
                                List<VisualGraphPublicationSummary> publications,
                                List<OperatorDefinition> operators,
                                int actionItemLimit,
                                int actionOffset,
                                String actionSeverity,
                                String actionType,
                                String actionTargetKind,
                                String actionOperatorRef) {
            return from(drafts, publications, operators, Map.of(), actionItemLimit, actionOffset, actionSeverity,
                    actionType, actionTargetKind, actionOperatorRef, "");
        }

        static ActionQueue from(List<GraphDraftSummary> drafts,
                                List<VisualGraphPublicationSummary> publications,
                                List<OperatorDefinition> operators,
                                Map<String, String> operatorLibraryIdsByOperatorRef,
                                int actionItemLimit,
                                int actionOffset,
                                String actionSeverity,
                                String actionType,
                                String actionTargetKind,
                                String actionOperatorRef,
                                String actionOperatorLibraryId) {
            return from(drafts, publications, operators, operatorLibraryIdsByOperatorRef, actionItemLimit,
                    actionOffset, actionSeverity, actionType, actionTargetKind, actionOperatorRef,
                    actionOperatorLibraryId, List.of(), List.of(), List.of(), List.of());
        }

        static ActionQueue from(List<GraphDraftSummary> drafts,
                                List<VisualGraphPublicationSummary> publications,
                                List<OperatorDefinition> operators,
                                Map<String, String> operatorLibraryIdsByOperatorRef,
                                int actionItemLimit,
                                int actionOffset,
                                String actionSeverity,
                                String actionType,
                                String actionTargetKind,
                                String actionOperatorRef,
                                String actionOperatorLibraryId,
                                String actionHandoffLane,
                                String actionHandoffKind,
                                String actionHandoffTarget,
                                String actionReadinessState,
                                String actionArtifactKind,
                                List<VisualRuntimeBindingImplementationBinding> implementationBindings,
                                List<VisualRuntimeAdapterActivation> adapterActivations,
                                List<VisualRuntimeRolloutObservation> rolloutObservations,
                                List<VisualExecutableLoweringIntegration> executableLoweringIntegrations) {
            List<ActionItem> generated = new ArrayList<>();
            Map<String, String> currentOwnerIdsByOperatorRef = mergedOperatorLibraryIds(
                    Map.of(),
                    operatorLibraryIdsByOperatorRef
            );
            for (GraphDraftSummary draft : drafts == null ? List.<GraphDraftSummary>of() : drafts) {
                addDraftAction(generated, draft, mergedOperatorLibraryIds(
                        draft == null ? Map.of() : draft.operatorLibraryIdsByOperatorRef(),
                        currentOwnerIdsByOperatorRef
                ));
            }
            for (VisualGraphPublicationSummary publication : publications == null
                    ? List.<VisualGraphPublicationSummary>of()
                    : publications) {
                addPublicationAction(generated, publication, mergedOperatorLibraryIds(
                        publication == null ? Map.of() : publication.operatorLibraryIdsByOperatorRef(),
                        currentOwnerIdsByOperatorRef
                ));
            }
            for (OperatorDefinition operator : operators == null ? List.<OperatorDefinition>of() : operators) {
                addCatalogAction(generated, operator, currentOwnerIdsByOperatorRef);
            }
            addRuntimeEvidenceActions(
                    generated,
                    implementationBindings,
                    adapterActivations,
                    rolloutObservations,
                    executableLoweringIntegrations,
                    currentOwnerIdsByOperatorRef
            );
            generated.sort((left, right) -> {
                int severity = Integer.compare(severityRank(left.severity()), severityRank(right.severity()));
                if (severity != 0) {
                    return severity;
                }
                int targetKind = left.targetKind().compareTo(right.targetKind());
                if (targetKind != 0) {
                    return targetKind;
                }
                return left.targetId().compareTo(right.targetId());
            });
            ActionFilter filter = new ActionFilter(actionSeverity, actionType, actionTargetKind, actionOperatorRef,
                    actionOperatorLibraryId, actionHandoffLane, actionHandoffKind, actionHandoffTarget,
                    actionReadinessState, actionArtifactKind);
            List<ActionItem> filtered = generated.stream()
                    .filter(filter::matches)
                    .toList();
            Map<String, Integer> actionTypes = new LinkedHashMap<>();
            Map<String, Integer> operatorRefs = countBy(filtered, ActionItem::operatorRef);
            Map<String, Integer> operatorLibraries = countBy(filtered, ActionItem::operatorLibraryId);
            Map<String, Integer> handoffLanes = countBy(filtered, ActionItem::handoffLane);
            Map<String, Integer> handoffKinds = countBy(filtered, ActionItem::handoffKind);
            Map<String, Integer> handoffTargets = countBy(filtered, ActionItem::handoffTarget);
            Map<String, Integer> readinessStates = countBy(filtered, ActionItem::readinessState);
            Map<String, Integer> artifactKinds = countBy(filtered, ActionItem::artifactKind);
            int errorCount = 0;
            int warningCount = 0;
            int infoCount = 0;
            for (ActionItem item : filtered) {
                actionTypes.merge(item.actionType(), 1, Integer::sum);
                switch (item.severity()) {
                    case "error" -> errorCount++;
                    case "warning" -> warningCount++;
                    default -> infoCount++;
                }
            }
            int normalizedLimit = normalizeActionItemLimit(actionItemLimit);
            int normalizedOffset = normalizeActionOffset(actionOffset);
            return new ActionQueue(
                    filtered.size(),
                    generated.size(),
                    Math.min(Math.max(0, filtered.size() - normalizedOffset), normalizedLimit),
                    normalizedLimit,
                    normalizedOffset,
                    false,
                    filter,
                    errorCount,
                    warningCount,
                    infoCount,
                    actionTypes,
                    operatorRefs,
                    operatorLibraries,
                    handoffLanes,
                    handoffKinds,
                    handoffTargets,
                    readinessStates,
                    artifactKinds,
                    filtered.stream().skip(normalizedOffset).limit(normalizedLimit).toList()
            );
        }

        static ActionQueue from(List<GraphDraftSummary> drafts,
                                List<VisualGraphPublicationSummary> publications,
                                List<OperatorDefinition> operators,
                                Map<String, String> operatorLibraryIdsByOperatorRef,
                                int actionItemLimit,
                                int actionOffset,
                                String actionSeverity,
                                String actionType,
                                String actionTargetKind,
                                String actionOperatorRef,
                                String actionOperatorLibraryId,
                                List<VisualRuntimeBindingImplementationBinding> implementationBindings,
                                List<VisualRuntimeAdapterActivation> adapterActivations,
                                List<VisualRuntimeRolloutObservation> rolloutObservations,
                                List<VisualExecutableLoweringIntegration> executableLoweringIntegrations) {
            return from(
                    drafts,
                    publications,
                    operators,
                    operatorLibraryIdsByOperatorRef,
                    actionItemLimit,
                    actionOffset,
                    actionSeverity,
                    actionType,
                    actionTargetKind,
                    actionOperatorRef,
                    actionOperatorLibraryId,
                    "",
                    "",
                    "",
                    "",
                    "",
                    implementationBindings,
                    adapterActivations,
                    rolloutObservations,
                    executableLoweringIntegrations
            );
        }

        static ActionQueue empty() {
            return new ActionQueue(0, 0, DEFAULT_ACTION_ITEM_LIMIT, 0, 0, 0, Map.of(), List.of());
        }
    }

    /**
     * Normalized action queue query filter.
     *
     * @param severity severity filter, empty when not filtered
     * @param actionType action type filter, empty when not filtered
     * @param targetKind target kind filter, empty when not filtered
     * @param operatorRef operator reference filter, empty when not filtered
     * @param operatorLibraryId owner operator library id filter, empty when not filtered
     * @param handoffLane runtime-plane handoff lane filter, empty when not filtered
     * @param handoffKind runtime-plane handoff kind filter, empty when not filtered
     * @param handoffTarget runtime-plane handoff target filter, empty when not filtered
     * @param readinessState readiness state filter, empty when not filtered
     * @param artifactKind artifact kind filter, empty when not filtered
     * @param filtered true when at least one action filter is active
     */
    public record ActionFilter(
            String severity,
            String actionType,
            String targetKind,
            String operatorRef,
            String operatorLibraryId,
            String handoffLane,
            String handoffKind,
            String handoffTarget,
            String readinessState,
            String artifactKind,
            boolean filtered
    ) {
        public ActionFilter(String severity, String actionType, String targetKind) {
            this(severity, actionType, targetKind, "");
        }

        public ActionFilter(String severity, String actionType, String targetKind, String operatorRef) {
            this(severity, actionType, targetKind, operatorRef, "");
        }

        public ActionFilter(String severity,
                            String actionType,
                            String targetKind,
                            String operatorRef,
                            String operatorLibraryId) {
            this(severity, actionType, targetKind, operatorRef, operatorLibraryId, "", "", "", "", "");
        }

        public ActionFilter(String severity,
                            String actionType,
                            String targetKind,
                            String operatorRef,
                            String operatorLibraryId,
                            String handoffLane,
                            String handoffKind,
                            String handoffTarget,
                            String readinessState,
                            String artifactKind) {
            this(
                    normalizeSeverityFilter(severity),
                    normalizeActionTypeFilter(actionType),
                    normalizeTargetKindFilter(targetKind),
                    normalizeTextValue(operatorRef),
                    normalizeTextValue(operatorLibraryId),
                    normalizeFacetValue(handoffLane),
                    normalizeFacetValue(handoffKind),
                    normalizeTextValue(handoffTarget),
                    normalizeFacetValue(readinessState),
                    normalizeArtifactKindFilter(artifactKind),
                    !normalizeSeverityFilter(severity).isBlank()
                            || !normalizeActionTypeFilter(actionType).isBlank()
                            || !normalizeTargetKindFilter(targetKind).isBlank()
                            || !normalizeTextValue(operatorRef).isBlank()
                            || !normalizeTextValue(operatorLibraryId).isBlank()
                            || !normalizeFacetValue(handoffLane).isBlank()
                            || !normalizeFacetValue(handoffKind).isBlank()
                            || !normalizeTextValue(handoffTarget).isBlank()
                            || !normalizeFacetValue(readinessState).isBlank()
                            || !normalizeArtifactKindFilter(artifactKind).isBlank()
            );
        }

        public ActionFilter {
            severity = normalizeSeverityFilter(severity);
            actionType = normalizeActionTypeFilter(actionType);
            targetKind = normalizeTargetKindFilter(targetKind);
            operatorRef = normalizeTextValue(operatorRef);
            operatorLibraryId = normalizeTextValue(operatorLibraryId);
            handoffLane = normalizeFacetValue(handoffLane);
            handoffKind = normalizeFacetValue(handoffKind);
            handoffTarget = normalizeTextValue(handoffTarget);
            readinessState = normalizeFacetValue(readinessState);
            artifactKind = normalizeArtifactKindFilter(artifactKind);
            filtered = !severity.isBlank() || !actionType.isBlank() || !targetKind.isBlank()
                    || !operatorRef.isBlank()
                    || !operatorLibraryId.isBlank()
                    || !handoffLane.isBlank()
                    || !handoffKind.isBlank()
                    || !handoffTarget.isBlank()
                    || !readinessState.isBlank()
                    || !artifactKind.isBlank();
        }

        static ActionFilter all() {
            return new ActionFilter("", "", "", "", "", "", "", "", "", "");
        }

        boolean matches(ActionItem item) {
            return item != null
                    && (severity.isBlank() || severity.equals(item.severity()))
                    && (actionType.isBlank() || actionType.equals(item.actionType()))
                    && (targetKind.isBlank() || targetKind.equals(item.targetKind()))
                    && (operatorRef.isBlank() || operatorRef.equals(item.operatorRef()))
                    && (operatorLibraryId.isBlank() || operatorLibraryId.equals(item.operatorLibraryId()))
                    && (handoffLane.isBlank() || handoffLane.equals(item.handoffLane()))
                    && (handoffKind.isBlank() || handoffKind.equals(item.handoffKind()))
                    && (handoffTarget.isBlank() || handoffTarget.equals(item.handoffTarget()))
                    && (readinessState.isBlank() || readinessState.equals(item.readinessState()))
                    && (artifactKind.isBlank() || artifactKind.equals(item.artifactKind()));
        }
    }

    private static int normalizeActionItemLimit(int actionItemLimit) {
        return Math.max(0, Math.min(actionItemLimit, MAX_ACTION_ITEM_LIMIT));
    }

    private static int normalizeActionOffset(int actionOffset) {
        return Math.max(0, actionOffset);
    }

    /**
     * One server-derived action suggestion.
     *
     * @param actionKey stable machine-readable key for deduplication and audit
     * @param severity normalized UI severity
     * @param actionType stable machine-readable action type
     * @param targetKind target asset kind
     * @param targetId target asset id
     * @param targetLabel human-readable target label
     * @param operatorRef related operator reference when the action is operator-scoped
     * @param operatorLibraryId owner operator library id when the related operator comes from an imported library
     * @param readinessState graph/operator readiness state when known
     * @param artifactKind publication artifact kind when known
     * @param handoffLane runtime-plane responsibility lane when this is a binding action
     * @param handoffKind runtime-plane work kind when this is a binding action
     * @param handoffTarget runtime-plane routing target when this is a binding action
     * @param summary concise reason for the queue item
     * @param recommendedAction concrete next action
     */
    public record ActionItem(
            String actionKey,
            String severity,
            String actionType,
            String targetKind,
            String targetId,
            String targetLabel,
            String operatorRef,
            String operatorLibraryId,
            String readinessState,
            String artifactKind,
            String handoffLane,
            String handoffKind,
            String handoffTarget,
            String summary,
            String recommendedAction
    ) {
        public ActionItem(String severity,
                          String actionType,
                          String targetKind,
                          String targetId,
                          String targetLabel,
                          String readinessState,
                          String artifactKind,
                          String summary,
                          String recommendedAction) {
            this(
                    "",
                    severity,
                    actionType,
                    targetKind,
                    targetId,
                    targetLabel,
                    "",
                    "",
                    readinessState,
                    artifactKind,
                    "",
                    "",
                    "",
                    summary,
                    recommendedAction
            );
        }

        public ActionItem(String actionKey,
                          String severity,
                          String actionType,
                          String targetKind,
                          String targetId,
                          String targetLabel,
                          String readinessState,
                          String artifactKind,
                          String summary,
                          String recommendedAction) {
            this(
                    actionKey,
                    severity,
                    actionType,
                    targetKind,
                    targetId,
                    targetLabel,
                    "",
                    "",
                    readinessState,
                    artifactKind,
                    "",
                    "",
                    "",
                    summary,
                    recommendedAction
            );
        }

        public ActionItem(String actionKey,
                          String severity,
                          String actionType,
                          String targetKind,
                          String targetId,
                          String targetLabel,
                          String operatorRef,
                          String readinessState,
                          String artifactKind,
                          String handoffLane,
                          String handoffKind,
                          String handoffTarget,
                          String summary,
                          String recommendedAction) {
            this(
                    actionKey,
                    severity,
                    actionType,
                    targetKind,
                    targetId,
                    targetLabel,
                    operatorRef,
                    "",
                    readinessState,
                    artifactKind,
                    handoffLane,
                    handoffKind,
                    handoffTarget,
                    summary,
                    recommendedAction
            );
        }

        public ActionItem {
            severity = normalizeSeverity(severity);
            actionType = actionType == null || actionType.isBlank()
                    ? "REVIEW_ASSET"
                    : actionType.trim().toUpperCase(Locale.ROOT);
            targetKind = targetKind == null ? "" : targetKind.trim().toLowerCase(Locale.ROOT);
            targetId = targetId == null ? "" : targetId;
            targetLabel = targetLabel == null ? "" : targetLabel;
            operatorRef = normalizeTextValue(operatorRef);
            operatorLibraryId = normalizeTextValue(operatorLibraryId);
            readinessState = normalizeFacetValue(readinessState);
            artifactKind = artifactKind == null || artifactKind.isBlank() ? "" : artifactKind.trim().toUpperCase(Locale.ROOT);
            handoffLane = normalizeFacetValue(handoffLane);
            handoffKind = normalizeFacetValue(handoffKind);
            handoffTarget = handoffTarget == null ? "" : handoffTarget;
            summary = summary == null ? "" : summary;
            recommendedAction = recommendedAction == null ? "" : recommendedAction;
            actionKey = actionKey == null || actionKey.isBlank()
                    ? stableActionKey(actionType, targetKind, targetId, readinessState, artifactKind)
                    : actionKey.trim();
        }
    }

    private static void addRuntimeEvidenceActions(
            List<ActionItem> items,
            List<VisualRuntimeBindingImplementationBinding> implementationBindings,
            List<VisualRuntimeAdapterActivation> adapterActivations,
            List<VisualRuntimeRolloutObservation> rolloutObservations,
            List<VisualExecutableLoweringIntegration> executableLoweringIntegrations,
            Map<String, String> operatorLibraryIdsByOperatorRef) {
        List<VisualRuntimeBindingImplementationBinding> safeBindings = implementationBindings == null
                ? List.of()
                : implementationBindings.stream().filter(binding -> binding != null).toList();
        List<VisualRuntimeAdapterActivation> safeActivations = adapterActivations == null
                ? List.of()
                : adapterActivations.stream().filter(activation -> activation != null).toList();
        List<VisualRuntimeRolloutObservation> safeObservations = rolloutObservations == null
                ? List.of()
                : rolloutObservations.stream().filter(observation -> observation != null).toList();
        List<VisualExecutableLoweringIntegration> safeIntegrations = executableLoweringIntegrations == null
                ? List.of()
                : executableLoweringIntegrations.stream().filter(integration -> integration != null).toList();

        Map<String, VisualRuntimeBindingImplementationBinding> activeBindingsById = new LinkedHashMap<>();
        for (VisualRuntimeBindingImplementationBinding binding : safeBindings) {
            if (binding.bound()) {
                activeBindingsById.put(binding.bindingId(), binding);
            }
            addRuntimeBindingEvidenceAction(items, binding, operatorLibraryIdsByOperatorRef);
        }

        Map<String, VisualRuntimeAdapterActivation> activeActivationsById = new LinkedHashMap<>();
        Map<String, VisualRuntimeAdapterActivation> activeActivationsByBindingId = new LinkedHashMap<>();
        for (VisualRuntimeAdapterActivation activation : safeActivations) {
            if (activation.active()) {
                activeActivationsById.put(activation.activationId(), activation);
                activeActivationsByBindingId.putIfAbsent(activation.bindingId(), activation);
            } else if (VisualRuntimeAdapterActivation.STATE_FAILED.equals(activation.state())) {
                addRuntimeEvidenceAction(
                        items,
                        "error",
                        "REVIEW_RUNTIME_ADAPTER_FAILURE",
                        activation.operatorRef(),
                        operatorLibraryId(activation.operatorRef(), operatorLibraryIdsByOperatorRef),
                        "activation",
                        activation.activationId(),
                        "runtime-evidence-failed",
                        "runtime-platform",
                        "adapter-activation",
                        activation.bindingId(),
                        "Runtime adapter activation '%s' is failed for operator '%s'."
                                .formatted(activation.activationId(), activation.operatorRef()),
                        "Review adapter health/deployment evidence, submit a healthy activation, or deactivate the stale record."
                );
            }
        }

        Map<String, VisualExecutableLoweringIntegration> activeIntegrationsByActivationId =
                new LinkedHashMap<>();
        for (VisualExecutableLoweringIntegration integration : safeIntegrations) {
            if (integration.active()) {
                activeIntegrationsByActivationId.putIfAbsent(integration.activationId(), integration);
            } else if (VisualExecutableLoweringIntegration.STATE_FAILED.equals(integration.state())) {
                addRuntimeEvidenceAction(
                        items,
                        "error",
                        "REVIEW_EXECUTABLE_LOWERING_FAILURE",
                        integration.operatorRef(),
                        operatorLibraryId(integration.operatorRef(), operatorLibraryIdsByOperatorRef),
                        "integration",
                        integration.integrationId(),
                        "runtime-evidence-failed",
                        "operator-platform",
                        "executable-lowering",
                        integration.activationId(),
                        "Executable lowering integration '%s' is failed for operator '%s'."
                                .formatted(integration.integrationId(), integration.operatorRef()),
                        "Repair executor bridge evidence, then resubmit or deactivate the failed integration."
                );
            }
        }

        for (VisualRuntimeBindingImplementationBinding binding : activeBindingsById.values()) {
            VisualRuntimeAdapterActivation activation = activeActivationsByBindingId.get(binding.bindingId());
            if (!runtimeActivationMatchesBinding(activation, binding)) {
                String adapterKind = binding.implementation() == null ? "" : binding.implementation().adapterKind();
                addRuntimeEvidenceAction(
                        items,
                        "warning",
                        "ACTIVATE_RUNTIME_ADAPTER",
                        binding.operatorRef(),
                        operatorLibraryId(binding.operatorRef(), operatorLibraryIdsByOperatorRef),
                        "binding",
                        binding.bindingId(),
                        "runtime-evidence-partial",
                        "runtime-platform",
                        "adapter-activation",
                        defaultIfBlank(adapterKind, binding.operatorRef()),
                        "Bound implementation '%s' has no matching active runtime adapter activation."
                                .formatted(binding.bindingId()),
                        "Submit a healthy adapter activation for this binding before executable lowering or production rollout."
                );
            }
        }

        for (VisualRuntimeAdapterActivation activation : activeActivationsById.values()) {
            VisualRuntimeBindingImplementationBinding binding = activeBindingsById.get(activation.bindingId());
            if (!runtimeActivationMatchesBinding(activation, binding)) {
                addRuntimeEvidenceAction(
                        items,
                        "error",
                        "REPAIR_RUNTIME_ACTIVATION_CHAIN",
                        activation.operatorRef(),
                        operatorLibraryId(activation.operatorRef(), operatorLibraryIdsByOperatorRef),
                        "activation",
                        activation.activationId(),
                        "runtime-evidence-orphaned",
                        "runtime-platform",
                        "adapter-activation",
                        activation.bindingId(),
                        "Active adapter activation '%s' no longer matches an active bound implementation."
                                .formatted(activation.activationId()),
                        "Deactivate or refresh this activation against the current bound implementation fingerprint/revision."
                );
                continue;
            }
            VisualExecutableLoweringIntegration integration =
                    activeIntegrationsByActivationId.get(activation.activationId());
            if (!runtimeIntegrationMatchesActivationAndBinding(integration, activation, binding)) {
                addRuntimeEvidenceAction(
                        items,
                        "warning",
                        "INTEGRATE_EXECUTABLE_LOWERING",
                        activation.operatorRef(),
                        operatorLibraryId(activation.operatorRef(), operatorLibraryIdsByOperatorRef),
                        "activation",
                        activation.activationId(),
                        "runtime-evidence-partial",
                        "operator-platform",
                        "executable-lowering",
                        activation.operatorRef(),
                        "Active adapter activation '%s' has no matching executable lowering integration."
                                .formatted(activation.activationId()),
                        "Submit executor/lowering bridge evidence before recomputing executable readiness."
                );
            }
        }

        for (VisualExecutableLoweringIntegration integration : activeIntegrationsByActivationId.values()) {
            VisualRuntimeAdapterActivation activation = activeActivationsById.get(integration.activationId());
            VisualRuntimeBindingImplementationBinding binding =
                    activation == null ? null : activeBindingsById.get(activation.bindingId());
            if (!runtimeIntegrationMatchesActivationAndBinding(integration, activation, binding)) {
                addRuntimeEvidenceAction(
                        items,
                        "error",
                        "REPAIR_EXECUTABLE_LOWERING_CHAIN",
                        integration.operatorRef(),
                        operatorLibraryId(integration.operatorRef(), operatorLibraryIdsByOperatorRef),
                        "integration",
                        integration.integrationId(),
                        "runtime-evidence-orphaned",
                        "operator-platform",
                        "executable-lowering",
                        integration.activationId(),
                        "Active executable lowering integration '%s' no longer matches an active activation chain."
                                .formatted(integration.integrationId()),
                        "Deactivate or refresh this integration after adapter activation and binding evidence are current."
                );
            }
        }

        for (VisualRuntimeRolloutObservation observation : safeObservations) {
            addRuntimeRolloutEvidenceAction(items, observation, operatorLibraryIdsByOperatorRef);
        }
    }

    private static void addRuntimeBindingEvidenceAction(
            List<ActionItem> items,
            VisualRuntimeBindingImplementationBinding binding,
            Map<String, String> operatorLibraryIdsByOperatorRef) {
        if (binding == null) {
            return;
        }
        String operatorLibraryId = operatorLibraryId(binding.operatorRef(), operatorLibraryIdsByOperatorRef);
        if (binding.failed()) {
            addRuntimeEvidenceAction(
                    items,
                    "error",
                    "REVIEW_RUNTIME_IMPLEMENTATION_FAILURE",
                    binding.operatorRef(),
                    operatorLibraryId,
                    "binding",
                    binding.bindingId(),
                    "runtime-evidence-failed",
                    "operator-platform",
                    "operator-implementation",
                    binding.operatorRef(),
                    "Runtime implementation binding '%s' is failed for operator '%s'."
                            .formatted(binding.bindingId(), binding.operatorRef()),
                    "Review implementation validation evidence, fix the proposal, and resubmit a new binding record."
            );
        } else if (binding.requiresReview()) {
            addRuntimeEvidenceAction(
                    items,
                    "warning",
                    "REVIEW_RUNTIME_IMPLEMENTATION_BINDING",
                    binding.operatorRef(),
                    operatorLibraryId,
                    "binding",
                    binding.bindingId(),
                    "runtime-evidence-review-required",
                    "operator-platform",
                    "operator-implementation",
                    binding.operatorRef(),
                    "Runtime implementation binding '%s' requires review before it can become active."
                            .formatted(binding.bindingId()),
                    "Review test, policy, rollback, and rollout evidence; then bind it with actor/reason audit."
            );
        } else if (binding.readyToBind()) {
            addRuntimeEvidenceAction(
                    items,
                    "info",
                    "BIND_RUNTIME_IMPLEMENTATION",
                    binding.operatorRef(),
                    operatorLibraryId,
                    "binding",
                    binding.bindingId(),
                    "runtime-evidence-ready",
                    "operator-platform",
                    "operator-implementation",
                    binding.operatorRef(),
                    "Runtime implementation binding '%s' is ready to bind."
                            .formatted(binding.bindingId()),
                    "Bind this implementation with actor/reason evidence, or supersede it if newer evidence exists."
            );
        }
    }

    private static void addRuntimeRolloutEvidenceAction(
            List<ActionItem> items,
            VisualRuntimeRolloutObservation observation,
            Map<String, String> operatorLibraryIdsByOperatorRef) {
        if (observation == null) {
            return;
        }
        String state = normalizeFacetValue(observation.state());
        List<String> breachedSignalNames = observation.rolloutSignals().stream()
                .filter(signal -> signal != null && signal.breached() && !signal.name().isBlank())
                .map(VisualRuntimeRolloutObservation.RolloutSignal::name)
                .distinct()
                .toList();
        boolean failed = VisualRuntimeRolloutObservation.STATE_FAILED.equals(state)
                || VisualRuntimeRolloutObservation.STATE_ROLLED_BACK.equals(state);
        boolean risky = failed
                || VisualRuntimeRolloutObservation.STATE_DEGRADED.equals(state)
                || observation.rollbackTriggered()
                || !breachedSignalNames.isEmpty();
        if (!risky) {
            return;
        }
        String actionType = failed ? "REVIEW_RUNTIME_ROLLOUT_FAILURE" : "REVIEW_RUNTIME_ROLLOUT_RISK";
        String severity = failed ? "error" : "warning";
        String recommendedAction = failed
                ? "Review rollback evidence, keep the chain out of promotion, and submit fresh rollout evidence after remediation."
                : "Review canary/degradation evidence before promoting this runtime binding or recomputing executable readiness.";
        addRuntimeEvidenceAction(
                items,
                severity,
                actionType,
                observation.operatorRef(),
                operatorLibraryId(observation.operatorRef(), operatorLibraryIdsByOperatorRef),
                "rollout",
                observation.observationId(),
                failed ? "runtime-rollout-failed" : "runtime-rollout-risk",
                "runtime-platform",
                "rollout-observation",
                observation.runtimeEnvironment(),
                "Runtime rollout observation '%s' is %s%s for operator '%s'."
                        .formatted(
                                observation.observationId(),
                                state,
                                rolloutRiskSuffix(observation.rollbackTriggered(), breachedSignalNames),
                                observation.operatorRef()),
                recommendedAction
        );
    }

    private static String rolloutRiskSuffix(boolean rollbackTriggered, List<String> breachedSignalNames) {
        List<String> suffixes = new ArrayList<>();
        if (rollbackTriggered) {
            suffixes.add("rollback triggered");
        }
        if (breachedSignalNames != null && !breachedSignalNames.isEmpty()) {
            suffixes.add("breached signals %s".formatted(String.join(", ", breachedSignalNames)));
        }
        return suffixes.isEmpty() ? "" : " with " + String.join(" and ", suffixes);
    }

    private static void addRuntimeEvidenceAction(List<ActionItem> items,
                                                 String severity,
                                                 String actionType,
                                                 String operatorRef,
                                                 String operatorLibraryId,
                                                 String evidenceKind,
                                                 String evidenceId,
                                                 String readinessState,
                                                 String handoffLane,
                                                 String handoffKind,
                                                 String handoffTarget,
                                                 String summary,
                                                 String recommendedAction) {
        String targetId = "%s:%s".formatted(evidenceKind, evidenceId == null ? "" : evidenceId);
        items.add(new ActionItem(
                runtimeEvidenceActionKey(actionType, evidenceKind, evidenceId),
                severity,
                actionType,
                "runtime-evidence",
                targetId,
                runtimeEvidenceTargetLabel(evidenceKind, evidenceId, operatorRef),
                operatorRef,
                operatorLibraryId,
                readinessState,
                "",
                handoffLane,
                handoffKind,
                handoffTarget,
                summary,
                recommendedAction
        ));
    }

    private static String runtimeEvidenceActionKey(String actionType,
                                                   String evidenceKind,
                                                   String evidenceId) {
        return String.join("|",
                actionType,
                "runtime-evidence",
                evidenceKind == null ? "" : evidenceKind,
                evidenceId == null ? "" : evidenceId);
    }

    private static String runtimeEvidenceTargetLabel(String evidenceKind,
                                                     String evidenceId,
                                                     String operatorRef) {
        String kind = normalizeFacetValue(evidenceKind);
        String id = evidenceId == null ? "" : evidenceId;
        String operator = operatorRef == null ? "" : operatorRef;
        if (operator.isBlank()) {
            return "%s %s".formatted(kind, id).trim();
        }
        return "%s / %s %s".formatted(operator, kind, id).trim();
    }

    private static boolean runtimeActivationMatchesBinding(VisualRuntimeAdapterActivation activation,
                                                           VisualRuntimeBindingImplementationBinding binding) {
        return activation != null
                && binding != null
                && binding.bound()
                && activation.active()
                && activation.bindingId().equals(binding.bindingId())
                && activation.bindingRevision() == binding.revision()
                && activation.operatorRef().equals(binding.operatorRef())
                && activation.operatorFingerprint().equals(binding.operatorFingerprint());
    }

    private static boolean runtimeIntegrationMatchesActivationAndBinding(
            VisualExecutableLoweringIntegration integration,
            VisualRuntimeAdapterActivation activation,
            VisualRuntimeBindingImplementationBinding binding) {
        return integration != null
                && runtimeActivationMatchesBinding(activation, binding)
                && integration.active()
                && integration.activationId().equals(activation.activationId())
                && integration.activationRevision() == activation.revision()
                && integration.bindingId().equals(binding.bindingId())
                && integration.bindingRevision() == binding.revision()
                && integration.operatorRef().equals(binding.operatorRef())
                && integration.operatorFingerprint().equals(binding.operatorFingerprint());
    }

    private static Map<String, Integer> immutableCounts(Map<String, Integer> counts) {
        return counts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    private static Map<String, Integer> countBy(List<ActionItem> items,
                                                Function<ActionItem, String> classifier) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ActionItem item : items == null ? List.<ActionItem>of() : items) {
            String key = classifier.apply(item);
            if (key == null || key.isBlank()) {
                continue;
            }
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private static String operatorLibraryId(String operatorRef, Map<String, String> operatorLibraryIdsByOperatorRef) {
        if (operatorRef == null || operatorLibraryIdsByOperatorRef == null) {
            return "";
        }
        return normalizeTextValue(operatorLibraryIdsByOperatorRef.get(operatorRef));
    }

    private static Map<String, String> mergedOperatorLibraryIds(Map<String, String> snapshotOwnerIdsByOperatorRef,
                                                               Map<String, String> currentOwnerIdsByOperatorRef) {
        Map<String, String> merged = new LinkedHashMap<>();
        putOperatorLibraryIds(merged, snapshotOwnerIdsByOperatorRef);
        putOperatorLibraryIds(merged, currentOwnerIdsByOperatorRef);
        return merged;
    }

    private static void putOperatorLibraryIds(Map<String, String> target,
                                              Map<String, String> ownerIdsByOperatorRef) {
        if (ownerIdsByOperatorRef == null || ownerIdsByOperatorRef.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : ownerIdsByOperatorRef.entrySet()) {
            String operatorRef = normalizeTextValue(entry.getKey());
            String operatorLibraryId = normalizeTextValue(entry.getValue());
            if (!operatorRef.isBlank() && !operatorLibraryId.isBlank()) {
                target.put(operatorRef, operatorLibraryId);
            }
        }
    }

    private static void addDraftAction(List<ActionItem> items,
                                       GraphDraftSummary draft,
                                       Map<String, String> operatorLibraryIdsByOperatorRef) {
        if (draft == null || !draft.active()) {
            return;
        }
        String state = readinessState(draft.readiness());
        VisualGraphActionReadiness actionReadiness = draft.actionReadiness();
        String actionState = actionReadinessState(actionReadiness);
        String label = "%s @%d".formatted(
                draft.graphName().isBlank() ? draft.draftId() : draft.graphName(),
                draft.currentRevision() > 0 ? draft.currentRevision() : draft.latestRevision()
        );
        addDraftSchemaDriftActions(items, draft, label, operatorLibraryIdsByOperatorRef);
        if (VisualGraphActionReadiness.DRAFT_REPAIR_REQUIRED.equals(actionState)
                || "draft-repair-required".equals(state) || "catalog-repair-required".equals(state)
                || draft.missingOperatorCount() > 0) {
            items.add(new ActionItem(
                    "error",
                    "REPAIR_DRAFT",
                    "draft",
                    draft.draftId(),
                    label,
                    state,
                    "",
                    "Draft has blocking validation, catalog repair, or missing-operator issues.",
                    "Open the draft, repair validation/catalog issues, then revalidate before publishing."
            ));
        } else if (draft.scopeMismatchOperatorCount() > 0 || draft.driftedFingerprintCount() > 0
                || draft.missingFingerprintCount() > 0) {
            items.add(new ActionItem(
                    "warning",
                    "REVIEW_DRAFT_DEPENDENCIES",
                    "draft",
                    draft.draftId(),
                    label,
                    state,
                    "",
                    "Draft dependency snapshots need review.",
                    "Review dependency report, then rebase fingerprints or repair catalog scope intentionally."
            ));
        } else if (VisualGraphActionReadiness.RUNTIME_BINDING_REQUIRED.equals(actionState)
                || "runtime-blocked".equals(state)) {
            if (!addDraftRuntimeBindingActions(items, draft, label, state, actionReadiness, "warning",
                    operatorLibraryIdsByOperatorRef)) {
                items.add(new ActionItem(
                        "warning",
                        "BIND_DRAFT_RUNTIME",
                        "draft",
                        draft.draftId(),
                        label,
                        state,
                        "",
                        "Draft is schema-valid but blocked by runtime capability or binding gaps.",
                        actionRecommendation(actionReadiness,
                                "Bind executable runtime implementations or publish as a DESIGN artifact for review.")
                ));
            }
        } else if (VisualGraphActionReadiness.GOVERNANCE_REVIEW_REQUIRED.equals(actionState)
                || "governance-review".equals(state)) {
            items.add(new ActionItem(
                    "warning",
                    "REVIEW_DRAFT_GOVERNANCE",
                    "draft",
                    draft.draftId(),
                    label,
                    state,
                    "",
                    "Draft requires governance review before production promotion.",
                    actionRecommendation(actionReadiness,
                            "Review warnings, provide actor/reason evidence, then publish when accepted.")
            ));
        } else if (VisualGraphActionReadiness.WARNING_ACK_REQUIRED.equals(actionState)) {
            items.add(new ActionItem(
                    "warning",
                    "ACK_DRAFT_WARNINGS",
                    "draft",
                    draft.draftId(),
                    label,
                    state,
                    "",
                    actionMessage(actionReadiness,
                            "Draft publication requires warning acknowledgement and governance evidence."),
                    actionRecommendation(actionReadiness,
                            "Publish with ackWarnings=true plus actor and reason after reviewing diagnostics.")
            ));
        } else if ("design-only".equals(state)) {
            addDraftRuntimeBindingActions(items, draft, label, state, actionReadiness, "info",
                    operatorLibraryIdsByOperatorRef);
            items.add(new ActionItem(
                    "info",
                    "TRACK_DESIGN_DRAFT",
                    "draft",
                    draft.draftId(),
                    label,
                    state,
                    "",
                    "Draft is a valid schema-only design asset.",
                    actionRecommendation(actionReadiness,
                            "Publish as DESIGN or keep tracking until runtime implementation is bound.")
            ));
        }
    }

    private static void addPublicationAction(List<ActionItem> items,
                                             VisualGraphPublicationSummary publication,
                                             Map<String, String> operatorLibraryIdsByOperatorRef) {
        if (publication == null) {
            return;
        }
        String state = readinessState(publication.readiness());
        VisualGraphActionReadiness actionReadiness = publication.actionReadiness();
        String actionState = actionReadinessState(actionReadiness);
        String artifactKind = normalizeArtifactKind(publication.artifactKind());
        String label = "%s @%d".formatted(
                publication.graphName().isBlank() ? publication.publicationId() : publication.graphName(),
                publication.draftRevision()
        );
        addPublicationSchemaDriftActions(items, publication, label, artifactKind, operatorLibraryIdsByOperatorRef);
        if (VisualGraphActionReadiness.DRAFT_REPAIR_REQUIRED.equals(actionState)
                || "draft-repair-required".equals(state) || "catalog-repair-required".equals(state)
                || publication.missingOperatorCount() > 0) {
            items.add(new ActionItem(
                    "error",
                    "RECERTIFY_PUBLICATION",
                    "publication",
                    publication.publicationId(),
                    label,
                    state,
                    artifactKind,
                    "Publication freezes blocking validation, repair, or missing-operator risk.",
                    "Repair the source draft or catalog, republish, and recertify golden cases."
            ));
        } else if (publication.scopeMismatchOperatorCount() > 0 || publication.driftedFingerprintCount() > 0
                || publication.missingFingerprintCount() > 0) {
            items.add(new ActionItem(
                    "warning",
                    "REVIEW_PUBLICATION_DEPENDENCIES",
                    "publication",
                    publication.publicationId(),
                    label,
                    state,
                    artifactKind,
                    "Publication dependency evidence should be reviewed before reuse.",
                    "Review frozen dependency report and recertify before promotion or reuse."
            ));
        } else if (VisualGraphActionReadiness.RUNTIME_BINDING_REQUIRED.equals(actionState)
                || "runtime-blocked".equals(state)) {
            if (!addPublicationRuntimeBindingActions(items, publication, label, state, artifactKind, "warning",
                    operatorLibraryIdsByOperatorRef)) {
                items.add(new ActionItem(
                        "warning",
                        "BIND_PUBLICATION_RUNTIME",
                        "publication",
                        publication.publicationId(),
                        label,
                        state,
                        artifactKind,
                        "Publication is not executable in the current runtime.",
                        "Bind runtime implementations, republish as EXECUTABLE, then certify."
                ));
            }
        } else if (VisualGraphActionReadiness.GOVERNANCE_REVIEW_REQUIRED.equals(actionState)
                || "governance-review".equals(state)) {
            items.add(new ActionItem(
                    "warning",
                    "REVIEW_PUBLICATION_GOVERNANCE",
                    "publication",
                    publication.publicationId(),
                    label,
                    state,
                    artifactKind,
                    "Publication freezes governance-review warnings.",
                    "Review promotion evidence and certification status before production use."
            ));
        } else if (VisualGraphActionReadiness.WARNING_ACK_REQUIRED.equals(actionState)) {
            items.add(new ActionItem(
                    "warning",
                    "REVIEW_PUBLICATION_WARNING_EVIDENCE",
                    "publication",
                    publication.publicationId(),
                    label,
                    state,
                    artifactKind,
                    actionMessage(actionReadiness,
                            "Publication freezes warning-level validation evidence."),
                    "Review frozen actor/reason acknowledgement, diagnostics, and certification before promotion."
            ));
        } else if (VisualGraphPublication.ARTIFACT_DESIGN.equals(artifactKind) || "design-only".equals(state)) {
            if (!addPublicationRuntimeBindingActions(items, publication, label, state, artifactKind, "info",
                    operatorLibraryIdsByOperatorRef)) {
                items.add(new ActionItem(
                        "info",
                        "PLAN_PUBLICATION_RUNTIME_BINDING",
                        "publication",
                        publication.publicationId(),
                        label,
                        state,
                        artifactKind,
                        "Publication is a non-executable design artifact.",
                        "Use it for design review, or bind runtime implementations and republish later."
                ));
            }
        }
    }

    private static void addDraftSchemaDriftActions(List<ActionItem> items,
                                                   GraphDraftSummary draft,
                                                   String label,
                                                   Map<String, String> operatorLibraryIdsByOperatorRef) {
        if (draft == null) {
            return;
        }
        addSchemaDriftActions(
                items,
                "draft",
                draft.draftId(),
                label,
                "",
                "REPAIR_DRAFT_SCHEMA_DRIFT",
                "REVIEW_DRAFT_SCHEMA_DRIFT",
                draft.schemaBreakingOperatorRefCounts(),
                draft.schemaCompatibleOperatorRefCounts(),
                operatorLibraryIdsByOperatorRef,
                "Draft",
                "Open the draft dependency report, repair incompatible bindings or intentionally rebase after updating the graph.",
                "Review compatible schema drift, then rebase fingerprints with actor/reason evidence if the change is accepted."
        );
    }

    private static void addPublicationSchemaDriftActions(List<ActionItem> items,
                                                         VisualGraphPublicationSummary publication,
                                                         String label,
                                                         String artifactKind,
                                                         Map<String, String> operatorLibraryIdsByOperatorRef) {
        if (publication == null) {
            return;
        }
        addSchemaDriftActions(
                items,
                "publication",
                publication.publicationId(),
                label,
                artifactKind,
                "RECERTIFY_PUBLICATION_SCHEMA_DRIFT",
                "REVIEW_PUBLICATION_SCHEMA_DRIFT",
                publication.schemaBreakingOperatorRefCounts(),
                publication.schemaCompatibleOperatorRefCounts(),
                operatorLibraryIdsByOperatorRef,
                "Publication",
                "Repair the source draft or catalog, republish, then recertify this immutable artifact.",
                "Review frozen dependency evidence and recertify before promoting or reusing this artifact."
        );
    }

    private static void addSchemaDriftActions(List<ActionItem> items,
                                              String targetKind,
                                              String targetId,
                                              String targetLabel,
                                              String artifactKind,
                                              String breakingActionType,
                                              String compatibleActionType,
                                              Map<String, Integer> breakingOperatorRefCounts,
                                              Map<String, Integer> compatibleOperatorRefCounts,
                                              Map<String, String> operatorLibraryIdsByOperatorRef,
                                              String assetKind,
                                              String breakingRecommendation,
                                              String compatibleRecommendation) {
        for (Map.Entry<String, Integer> entry : positiveOperatorCounts(breakingOperatorRefCounts).entrySet()) {
            String operatorRef = entry.getKey();
            int count = entry.getValue();
            items.add(new ActionItem(
                    schemaDriftActionKey(breakingActionType, targetKind, targetId, operatorRef, "breaking",
                            artifactKind),
                    "error",
                    breakingActionType,
                    targetKind,
                    targetId,
                    schemaDriftTargetLabel(targetLabel, operatorRef),
                    operatorRef,
                    operatorLibraryId(operatorRef, operatorLibraryIdsByOperatorRef),
                    "schema-breaking-drift",
                    artifactKind,
                    "operator-platform",
                    "schema-contract-review",
                    operatorRef,
                    "%s has %d node%s whose frozen operator schema is breaking against current catalog operator '%s'."
                            .formatted(assetKind, count, count == 1 ? "" : "s", operatorRef),
                    breakingRecommendation
            ));
        }
        for (Map.Entry<String, Integer> entry : positiveOperatorCounts(compatibleOperatorRefCounts).entrySet()) {
            String operatorRef = entry.getKey();
            int count = entry.getValue();
            items.add(new ActionItem(
                    schemaDriftActionKey(compatibleActionType, targetKind, targetId, operatorRef, "compatible",
                            artifactKind),
                    "warning",
                    compatibleActionType,
                    targetKind,
                    targetId,
                    schemaDriftTargetLabel(targetLabel, operatorRef),
                    operatorRef,
                    operatorLibraryId(operatorRef, operatorLibraryIdsByOperatorRef),
                    "schema-compatible-drift",
                    artifactKind,
                    "operator-platform",
                    "schema-contract-review",
                    operatorRef,
                    "%s has %d node%s whose frozen operator schema remains compatible but changed for current catalog operator '%s'."
                            .formatted(assetKind, count, count == 1 ? "" : "s", operatorRef),
                    compatibleRecommendation
            ));
        }
    }

    private static Map<String, Integer> positiveOperatorCounts(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String operatorRef = normalizeTextValue(entry.getKey());
            int value = entry.getValue() == null ? 0 : entry.getValue();
            if (!operatorRef.isBlank() && value > 0) {
                normalized.put(operatorRef, value);
            }
        }
        return normalized;
    }

    private static String schemaDriftActionKey(String actionType,
                                               String targetKind,
                                               String targetId,
                                               String operatorRef,
                                               String state,
                                               String artifactKind) {
        return String.join("|",
                actionType == null ? "" : actionType,
                targetKind == null ? "" : targetKind,
                targetId == null ? "" : targetId,
                operatorRef == null ? "" : operatorRef,
                state == null ? "" : state,
                artifactKind == null ? "" : artifactKind
        );
    }

    private static String schemaDriftTargetLabel(String targetLabel, String operatorRef) {
        String label = targetLabel == null ? "" : targetLabel;
        String operator = operatorRef == null ? "" : operatorRef;
        return operator.isBlank() ? label : "%s / %s".formatted(label, operator).trim();
    }

    private static boolean addDraftRuntimeBindingActions(List<ActionItem> items,
                                                        GraphDraftSummary draft,
                                                        String label,
                                                        String readinessState,
                                                        VisualGraphActionReadiness actionReadiness,
                                                        String severity,
                                                        Map<String, String> operatorLibraryIdsByOperatorRef) {
        List<VisualGraphReadiness.RuntimeBindingRequirement> requirements =
                runtimeBindingRequirements(draft == null ? null : draft.readiness());
        if (requirements.isEmpty()) {
            return false;
        }
        for (VisualGraphReadiness.RuntimeBindingRequirement requirement : requirements) {
            items.add(new ActionItem(
                    runtimeBindingActionKey("PLAN_DRAFT_RUNTIME_BINDING", "draft", draft.draftId(), requirement, ""),
                    severity,
                    "PLAN_DRAFT_RUNTIME_BINDING",
                    "draft",
                    draft.draftId(),
                    runtimeBindingTargetLabel(label, requirement),
                    requirement.operatorRef(),
                    operatorLibraryId(requirement.operatorRef(), operatorLibraryIdsByOperatorRef),
                    readinessState,
                    "",
                    requirement.handoffLane(),
                    requirement.handoffKind(),
                    requirement.handoffTarget(),
                    runtimeBindingSummary("Draft", requirement),
                    runtimeBindingRecommendation(requirement, actionReadiness,
                            "Bind executable runtime implementations or keep this draft in DESIGN review.")
            ));
        }
        return true;
    }

    private static boolean addPublicationRuntimeBindingActions(List<ActionItem> items,
                                                              VisualGraphPublicationSummary publication,
                                                              String label,
                                                              String readinessState,
                                                              String artifactKind,
                                                              String severity,
                                                              Map<String, String> operatorLibraryIdsByOperatorRef) {
        List<VisualGraphReadiness.RuntimeBindingRequirement> requirements =
                runtimeBindingRequirements(publication == null ? null : publication.readiness());
        if (requirements.isEmpty()) {
            return false;
        }
        for (VisualGraphReadiness.RuntimeBindingRequirement requirement : requirements) {
            items.add(new ActionItem(
                    runtimeBindingActionKey("PLAN_PUBLICATION_RUNTIME_BINDING", "publication",
                            publication.publicationId(), requirement, artifactKind),
                    severity,
                    "PLAN_PUBLICATION_RUNTIME_BINDING",
                    "publication",
                    publication.publicationId(),
                    runtimeBindingTargetLabel(label, requirement),
                    requirement.operatorRef(),
                    operatorLibraryId(requirement.operatorRef(), operatorLibraryIdsByOperatorRef),
                    readinessState,
                    artifactKind,
                    requirement.handoffLane(),
                    requirement.handoffKind(),
                    requirement.handoffTarget(),
                    runtimeBindingSummary("Publication", requirement),
                    runtimeBindingRecommendation(requirement, null,
                            "Bind runtime implementations, republish as EXECUTABLE, then certify.")
            ));
        }
        return true;
    }

    private static void addCatalogAction(List<ActionItem> items,
                                         OperatorDefinition operator,
                                         Map<String, String> operatorLibraryIdsByOperatorRef) {
        if (operator == null || operator.runtimeReadiness() == null) {
            return;
        }
        String state = normalizeFacetValue(operator.runtimeReadiness().state());
        String label = operator.display().name().isBlank()
                ? operator.operatorRef()
                : operator.display().name();
        String summary = operator.runtimeReadiness().summary().isBlank()
                ? operator.runtimeReadiness().title()
                : operator.runtimeReadiness().summary();
        String operatorLibraryId = operatorLibraryId(operator.operatorRef(), operatorLibraryIdsByOperatorRef);
        switch (state) {
            case "catalog-repair-required" -> items.add(new ActionItem(
                    "",
                    "error",
                    "REPAIR_OPERATOR_CATALOG",
                    "operator",
                    operator.operatorRef(),
                    label,
                    operator.operatorRef(),
                    operatorLibraryId,
                    state,
                    "",
                    "",
                    "",
                    "",
                    summary,
                    "Repair operator definition, policy, or lowering contract before authoring at scale."
            ));
            case "runtime-blocked" -> items.add(new ActionItem(
                    "",
                    "warning",
                    "BIND_OPERATOR_RUNTIME",
                    "operator",
                    operator.operatorRef(),
                    label,
                    operator.operatorRef(),
                    operatorLibraryId,
                    state,
                    "",
                    "",
                    "",
                    "",
                    summary,
                    "Provide the required runtime binding or keep this operator in DESIGN-only flows."
            ));
            case "governance-review" -> items.add(new ActionItem(
                    "",
                    "warning",
                    "REVIEW_OPERATOR_GOVERNANCE",
                    "operator",
                    operator.operatorRef(),
                    label,
                    operator.operatorRef(),
                    operatorLibraryId,
                    state,
                    "",
                    "",
                    "",
                    "",
                    summary,
                    "Review policy, secret, or side-effect governance before production promotion."
            ));
            case "design-only" -> items.add(new ActionItem(
                    "",
                    "info",
                    "TRACK_SCHEMA_ONLY_OPERATOR",
                    "operator",
                    operator.operatorRef(),
                    label,
                    operator.operatorRef(),
                    operatorLibraryId,
                    state,
                    "",
                    "",
                    "",
                    "",
                    summary,
                    "Keep as a schema-only operator until an executable runtime binding is available."
            ));
            default -> {
                // Runtime-executable operators do not need a control-plane action.
            }
        }
    }

    private static List<VisualGraphReadiness.RuntimeBindingRequirement> runtimeBindingRequirements(
            VisualGraphReadiness readiness) {
        if (readiness == null || readiness.runtimeBindingRequirements() == null) {
            return List.of();
        }
        return readiness.runtimeBindingRequirements().stream()
                .filter(requirement -> requirement != null)
                .toList();
    }

    private static String runtimeBindingActionKey(String actionType,
                                                  String targetKind,
                                                  String targetId,
                                                  VisualGraphReadiness.RuntimeBindingRequirement requirement,
                                                  String artifactKind) {
        return String.join("|",
                actionType,
                targetKind,
                targetId == null ? "" : targetId,
                requirement == null ? "" : requirement.nodeId(),
                requirement == null ? "" : requirement.bindingKind(),
                requirement == null ? "" : requirement.bindingTarget(),
                artifactKind == null ? "" : artifactKind
        );
    }

    private static String runtimeBindingTargetLabel(String assetLabel,
                                                    VisualGraphReadiness.RuntimeBindingRequirement requirement) {
        if (requirement == null || requirement.nodeId().isBlank()) {
            return assetLabel;
        }
        return "%s / %s".formatted(assetLabel, requirement.nodeId());
    }

    private static String runtimeBindingSummary(String assetKind,
                                                VisualGraphReadiness.RuntimeBindingRequirement requirement) {
        if (requirement == null) {
            return "%s has a runtime binding gap before executable promotion.".formatted(assetKind);
        }
        String target = requirement.bindingTarget().isBlank()
                ? ""
                : " target '%s'".formatted(requirement.bindingTarget());
        return "%s node '%s' needs %s%s before EXECUTABLE promotion.".formatted(
                assetKind,
                requirement.nodeId().isBlank() ? requirement.operatorRef() : requirement.nodeId(),
                requirement.bindingKind().isBlank() ? "runtime binding" : requirement.bindingKind(),
                target
        );
    }

    private static String runtimeBindingRecommendation(VisualGraphReadiness.RuntimeBindingRequirement requirement,
                                                       VisualGraphActionReadiness actionReadiness,
                                                       String fallback) {
        if (requirement != null && !requirement.recommendedAction().isBlank()) {
            return requirement.recommendedAction();
        }
        return actionRecommendation(actionReadiness, fallback);
    }

    private static void mergeNormalized(Map<String, Integer> target, Map<String, Integer> source) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            String key = normalizeFacetValue(entry.getKey());
            int value = entry.getValue() == null ? 0 : entry.getValue();
            if (!key.isBlank() && value != 0) {
                target.merge(key, value, Integer::sum);
            }
        }
    }

    private static void mergeText(Map<String, Integer> target, Map<String, Integer> source) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            String key = normalizeTextValue(entry.getKey());
            int value = entry.getValue() == null ? 0 : entry.getValue();
            if (!key.isBlank() && value != 0) {
                target.merge(key, value, Integer::sum);
            }
        }
    }

    private static void incrementNormalized(Map<String, Integer> counts, String value) {
        String key = normalizeFacetValue(value);
        if (!key.isBlank()) {
            counts.merge(key, 1, Integer::sum);
        }
    }

    private static void incrementText(Map<String, Integer> counts, String value) {
        String key = normalizeTextValue(value);
        if (!key.isBlank()) {
            counts.merge(key, 1, Integer::sum);
        }
    }

    private static void incrementUpper(Map<String, Integer> counts, String value) {
        String key = normalizeArtifactKind(value);
        if (!key.isBlank()) {
            counts.merge(key, 1, Integer::sum);
        }
    }

    private static String readinessState(VisualGraphReadiness readiness) {
        return normalizeFacetValue(readiness == null ? "" : readiness.state());
    }

    private static String actionReadinessState(VisualGraphActionReadiness actionReadiness) {
        return normalizeFacetValue(actionReadiness == null ? "" : actionReadiness.state());
    }

    private static String actionMessage(VisualGraphActionReadiness actionReadiness, String fallback) {
        if (actionReadiness == null || actionReadiness.message().isBlank()) {
            return fallback;
        }
        return actionReadiness.message();
    }

    private static String actionRecommendation(VisualGraphActionReadiness actionReadiness, String fallback) {
        if (actionReadiness == null || actionReadiness.recommendedAction().isBlank()) {
            return fallback;
        }
        return actionReadiness.recommendedAction();
    }

    private static String normalizeSeverity(String value) {
        String severity = String.valueOf(value == null ? "" : value).trim().toLowerCase(Locale.ROOT);
        return switch (severity) {
            case "error", "warning", "info", "success" -> severity;
            default -> "info";
        };
    }

    private static String normalizeSeverityFilter(String value) {
        return String.valueOf(value == null ? "" : value).trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeActionTypeFilter(String value) {
        return String.valueOf(value == null ? "" : value).trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeTargetKindFilter(String value) {
        return String.valueOf(value == null ? "" : value).trim().toLowerCase(Locale.ROOT);
    }

    private static int severityRank(String value) {
        return switch (normalizeSeverity(value)) {
            case "error" -> 0;
            case "warning" -> 1;
            case "success" -> 3;
            default -> 2;
        };
    }

    private static String stableActionKey(String actionType,
                                          String targetKind,
                                          String targetId,
                                          String readinessState,
                                          String artifactKind) {
        return String.join("|", actionType, targetKind, targetId, readinessState, artifactKind);
    }

    private static String normalizeArtifactKind(String value) {
        String normalized = String.valueOf(value == null ? "" : value).trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return VisualGraphPublication.ARTIFACT_EXECUTABLE;
        }
        return normalized;
    }

    private static String normalizeArtifactKindFilter(String value) {
        return String.valueOf(value == null ? "" : value).trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeFacetValue(String value) {
        return String.valueOf(value == null ? "" : value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    private static String normalizeTextValue(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        String normalized = normalizeTextValue(value);
        return normalized.isBlank() ? normalizeTextValue(fallback) : normalized;
    }
}
