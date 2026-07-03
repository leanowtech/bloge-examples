package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogFacets;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftSummary;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationSummary;
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
 * @param actionQueue server-derived next-action queue for control-plane triage
 */
public record VisualAssetOverview(
        String schemaVersion,
        Instant generatedAt,
        AuthoringScope scope,
        DraftAssets drafts,
        PublicationAssets publications,
        CatalogAssets catalog,
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
        return new VisualAssetOverview(
                SCHEMA_VERSION,
                Instant.now(),
                new AuthoringScope(tenantId, namespace, environment),
                DraftAssets.from(drafts),
                PublicationAssets.from(publications),
                CatalogAssets.from(operators, catalogDiagnostics),
                ActionQueue.from(
                        drafts,
                        publications,
                        operators,
                        actionItemLimit,
                        actionOffset,
                        actionSeverity,
                        actionType,
                        actionTargetKind,
                        actionOperatorRef
                )
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
            Map<String, Integer> graphReadinessStateCounts,
            Map<String, Integer> publishableArtifactKindCounts,
            Map<String, Integer> sourceKindCounts,
            Map<String, Integer> loweringModeCounts,
            Map<String, Integer> operatorRuntimeReadinessStateCounts
    ) {
        public DraftAssets {
            graphReadinessStateCounts = immutableCounts(graphReadinessStateCounts);
            publishableArtifactKindCounts = immutableCounts(publishableArtifactKindCounts);
            sourceKindCounts = immutableCounts(sourceKindCounts);
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
            Map<String, Integer> loweringModes = new LinkedHashMap<>();
            Map<String, Integer> runtimeReadiness = new LinkedHashMap<>();
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
                VisualGraphReadiness readiness = summary.readiness();
                incrementNormalized(graphReadiness, readiness == null ? "" : readiness.state());
                if (readiness != null) {
                    for (String kind : readiness.artifactKinds()) {
                        incrementUpper(publishableKinds, kind);
                    }
                }
                mergeNormalized(sourceKinds, summary.sourceKindCounts());
                mergeNormalized(loweringModes, summary.loweringModeCounts());
                mergeNormalized(runtimeReadiness, summary.runtimeReadinessStateCounts());
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
                    graphReadiness,
                    publishableKinds,
                    sourceKinds,
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
            Map<String, Integer> artifactKindCounts,
            Map<String, Integer> graphReadinessStateCounts,
            Map<String, Integer> sourceKindCounts,
            Map<String, Integer> loweringModeCounts,
            Map<String, Integer> operatorRuntimeReadinessStateCounts
    ) {
        public PublicationAssets {
            artifactKindCounts = immutableCounts(artifactKindCounts);
            graphReadinessStateCounts = immutableCounts(graphReadinessStateCounts);
            sourceKindCounts = immutableCounts(sourceKindCounts);
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
            Map<String, Integer> loweringModes = new LinkedHashMap<>();
            Map<String, Integer> runtimeReadiness = new LinkedHashMap<>();
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
                VisualGraphReadiness readiness = summary.readiness();
                incrementNormalized(graphReadiness, readiness == null ? "" : readiness.state());
                mergeNormalized(sourceKinds, summary.sourceKindCounts());
                mergeNormalized(loweringModes, summary.loweringModeCounts());
                mergeNormalized(runtimeReadiness, summary.runtimeReadinessStateCounts());
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
                    artifactKinds,
                    graphReadiness,
                    sourceKinds,
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
            List<ActionItem> generated = new ArrayList<>();
            for (GraphDraftSummary draft : drafts == null ? List.<GraphDraftSummary>of() : drafts) {
                addDraftAction(generated, draft);
            }
            for (VisualGraphPublicationSummary publication : publications == null
                    ? List.<VisualGraphPublicationSummary>of()
                    : publications) {
                addPublicationAction(generated, publication);
            }
            for (OperatorDefinition operator : operators == null ? List.<OperatorDefinition>of() : operators) {
                addCatalogAction(generated, operator);
            }
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
            ActionFilter filter = new ActionFilter(actionSeverity, actionType, actionTargetKind, actionOperatorRef);
            List<ActionItem> filtered = generated.stream()
                    .filter(filter::matches)
                    .toList();
            Map<String, Integer> actionTypes = new LinkedHashMap<>();
            Map<String, Integer> operatorRefs = countBy(filtered, ActionItem::operatorRef);
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
                    filtered.stream().skip(normalizedOffset).limit(normalizedLimit).toList()
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
     * @param filtered true when at least one action filter is active
     */
    public record ActionFilter(
            String severity,
            String actionType,
            String targetKind,
            String operatorRef,
            boolean filtered
    ) {
        public ActionFilter(String severity, String actionType, String targetKind) {
            this(severity, actionType, targetKind, "");
        }

        public ActionFilter(String severity, String actionType, String targetKind, String operatorRef) {
            this(
                    normalizeSeverityFilter(severity),
                    normalizeActionTypeFilter(actionType),
                    normalizeTargetKindFilter(targetKind),
                    normalizeTextValue(operatorRef),
                    !normalizeSeverityFilter(severity).isBlank()
                            || !normalizeActionTypeFilter(actionType).isBlank()
                            || !normalizeTargetKindFilter(targetKind).isBlank()
                            || !normalizeTextValue(operatorRef).isBlank()
            );
        }

        public ActionFilter {
            severity = normalizeSeverityFilter(severity);
            actionType = normalizeActionTypeFilter(actionType);
            targetKind = normalizeTargetKindFilter(targetKind);
            operatorRef = normalizeTextValue(operatorRef);
            filtered = !severity.isBlank() || !actionType.isBlank() || !targetKind.isBlank()
                    || !operatorRef.isBlank();
        }

        static ActionFilter all() {
            return new ActionFilter("", "", "", "");
        }

        boolean matches(ActionItem item) {
            return item != null
                    && (severity.isBlank() || severity.equals(item.severity()))
                    && (actionType.isBlank() || actionType.equals(item.actionType()))
                    && (targetKind.isBlank() || targetKind.equals(item.targetKind()))
                    && (operatorRef.isBlank() || operatorRef.equals(item.operatorRef()));
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
                    readinessState,
                    artifactKind,
                    "",
                    "",
                    "",
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

    private static void addDraftAction(List<ActionItem> items, GraphDraftSummary draft) {
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
            if (!addDraftRuntimeBindingActions(items, draft, label, state, actionReadiness, "warning")) {
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
            addDraftRuntimeBindingActions(items, draft, label, state, actionReadiness, "info");
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

    private static void addPublicationAction(List<ActionItem> items, VisualGraphPublicationSummary publication) {
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
            if (!addPublicationRuntimeBindingActions(items, publication, label, state, artifactKind, "warning")) {
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
            if (!addPublicationRuntimeBindingActions(items, publication, label, state, artifactKind, "info")) {
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

    private static boolean addDraftRuntimeBindingActions(List<ActionItem> items,
                                                        GraphDraftSummary draft,
                                                        String label,
                                                        String readinessState,
                                                        VisualGraphActionReadiness actionReadiness,
                                                        String severity) {
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
                                                              String severity) {
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

    private static void addCatalogAction(List<ActionItem> items, OperatorDefinition operator) {
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
        switch (state) {
            case "catalog-repair-required" -> items.add(new ActionItem(
                    "",
                    "error",
                    "REPAIR_OPERATOR_CATALOG",
                    "operator",
                    operator.operatorRef(),
                    label,
                    operator.operatorRef(),
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

    private static void incrementNormalized(Map<String, Integer> counts, String value) {
        String key = normalizeFacetValue(value);
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

    private static String normalizeFacetValue(String value) {
        return String.valueOf(value == null ? "" : value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    private static String normalizeTextValue(String value) {
        return value == null ? "" : value.trim();
    }
}
