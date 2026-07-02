package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogFacets;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftSummary;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationSummary;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphReadiness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Environment-level visual authoring asset overview.
 *
 * <p>This is a control-plane read model for browser dashboards and external governance
 * automation. It summarizes drafts, immutable publications, and the current operator
 * catalog without returning full draft/publication payloads.</p>
 *
 * @param schemaVersion overview contract version
 * @param generatedAt server timestamp when this overview was derived
 * @param drafts draft asset aggregate
 * @param publications publication asset aggregate
 * @param catalog operator catalog aggregate
 * @param actionQueue server-derived next-action queue for control-plane triage
 */
public record VisualAssetOverview(
        String schemaVersion,
        Instant generatedAt,
        DraftAssets drafts,
        PublicationAssets publications,
        CatalogAssets catalog,
        ActionQueue actionQueue
) {
    public static final String SCHEMA_VERSION = "bloge.visualAssetOverview.v1";
    private static final int ACTION_ITEM_LIMIT = 12;

    /**
     * Creates an overview.
     */
    public VisualAssetOverview {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
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
        return new VisualAssetOverview(
                SCHEMA_VERSION,
                Instant.now(),
                DraftAssets.from(drafts),
                PublicationAssets.from(publications),
                CatalogAssets.from(operators, catalogDiagnostics),
                ActionQueue.from(drafts, publications, operators)
        );
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
     * @param total total action items before display limiting
     * @param displayedCount returned action item count
     * @param errorCount error-severity action count
     * @param warningCount warning-severity action count
     * @param infoCount info-severity action count
     * @param actionTypeCounts action counts by machine-readable action type
     * @param items first high-priority action items
     */
    public record ActionQueue(
            int total,
            int displayedCount,
            int errorCount,
            int warningCount,
            int infoCount,
            Map<String, Integer> actionTypeCounts,
            List<ActionItem> items
    ) {
        public ActionQueue {
            actionTypeCounts = immutableCounts(actionTypeCounts);
            items = items == null ? List.of() : List.copyOf(items);
            displayedCount = items.size();
        }

        static ActionQueue from(List<GraphDraftSummary> drafts,
                                List<VisualGraphPublicationSummary> publications,
                                List<OperatorDefinition> operators) {
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
            Map<String, Integer> actionTypes = new LinkedHashMap<>();
            int errorCount = 0;
            int warningCount = 0;
            int infoCount = 0;
            for (ActionItem item : generated) {
                actionTypes.merge(item.actionType(), 1, Integer::sum);
                switch (item.severity()) {
                    case "error" -> errorCount++;
                    case "warning" -> warningCount++;
                    default -> infoCount++;
                }
            }
            return new ActionQueue(
                    generated.size(),
                    Math.min(generated.size(), ACTION_ITEM_LIMIT),
                    errorCount,
                    warningCount,
                    infoCount,
                    actionTypes,
                    generated.stream().limit(ACTION_ITEM_LIMIT).toList()
            );
        }

        static ActionQueue empty() {
            return new ActionQueue(0, 0, 0, 0, 0, Map.of(), List.of());
        }
    }

    /**
     * One server-derived action suggestion.
     *
     * @param severity normalized UI severity
     * @param actionType stable machine-readable action type
     * @param targetKind target asset kind
     * @param targetId target asset id
     * @param targetLabel human-readable target label
     * @param readinessState graph/operator readiness state when known
     * @param artifactKind publication artifact kind when known
     * @param summary concise reason for the queue item
     * @param recommendedAction concrete next action
     */
    public record ActionItem(
            String severity,
            String actionType,
            String targetKind,
            String targetId,
            String targetLabel,
            String readinessState,
            String artifactKind,
            String summary,
            String recommendedAction
    ) {
        public ActionItem {
            severity = normalizeSeverity(severity);
            actionType = actionType == null || actionType.isBlank()
                    ? "REVIEW_ASSET"
                    : actionType.trim().toUpperCase(Locale.ROOT);
            targetKind = targetKind == null ? "" : targetKind.trim().toLowerCase(Locale.ROOT);
            targetId = targetId == null ? "" : targetId;
            targetLabel = targetLabel == null ? "" : targetLabel;
            readinessState = normalizeFacetValue(readinessState);
            artifactKind = artifactKind == null || artifactKind.isBlank() ? "" : artifactKind.trim().toUpperCase(Locale.ROOT);
            summary = summary == null ? "" : summary;
            recommendedAction = recommendedAction == null ? "" : recommendedAction;
        }
    }

    private static Map<String, Integer> immutableCounts(Map<String, Integer> counts) {
        return counts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    private static void addDraftAction(List<ActionItem> items, GraphDraftSummary draft) {
        if (draft == null || !draft.active()) {
            return;
        }
        String state = readinessState(draft.readiness());
        String label = "%s @%d".formatted(
                draft.graphName().isBlank() ? draft.draftId() : draft.graphName(),
                draft.currentRevision() > 0 ? draft.currentRevision() : draft.latestRevision()
        );
        if (!draft.valid() || "draft-repair-required".equals(state) || "catalog-repair-required".equals(state)
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
        } else if ("runtime-blocked".equals(state)) {
            items.add(new ActionItem(
                    "warning",
                    "BIND_DRAFT_RUNTIME",
                    "draft",
                    draft.draftId(),
                    label,
                    state,
                    "",
                    "Draft is schema-valid but blocked by runtime capability or binding gaps.",
                    "Bind executable runtime implementations or publish as a DESIGN artifact for review."
            ));
        } else if ("governance-review".equals(state)) {
            items.add(new ActionItem(
                    "warning",
                    "REVIEW_DRAFT_GOVERNANCE",
                    "draft",
                    draft.draftId(),
                    label,
                    state,
                    "",
                    "Draft requires governance review before production promotion.",
                    "Review warnings, provide actor/reason evidence, then publish when accepted."
            ));
        } else if ("design-only".equals(state)) {
            items.add(new ActionItem(
                    "info",
                    "TRACK_DESIGN_DRAFT",
                    "draft",
                    draft.draftId(),
                    label,
                    state,
                    "",
                    "Draft is a valid schema-only design asset.",
                    "Publish as DESIGN or keep tracking until runtime implementation is bound."
            ));
        }
    }

    private static void addPublicationAction(List<ActionItem> items, VisualGraphPublicationSummary publication) {
        if (publication == null) {
            return;
        }
        String state = readinessState(publication.readiness());
        String artifactKind = normalizeArtifactKind(publication.artifactKind());
        String label = "%s @%d".formatted(
                publication.graphName().isBlank() ? publication.publicationId() : publication.graphName(),
                publication.draftRevision()
        );
        if (!publication.valid() || "draft-repair-required".equals(state) || "catalog-repair-required".equals(state)
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
        } else if ("runtime-blocked".equals(state)) {
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
        } else if ("governance-review".equals(state)) {
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
        } else if (VisualGraphPublication.ARTIFACT_DESIGN.equals(artifactKind) || "design-only".equals(state)) {
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
                    "error",
                    "REPAIR_OPERATOR_CATALOG",
                    "operator",
                    operator.operatorRef(),
                    label,
                    state,
                    "",
                    summary,
                    "Repair operator definition, policy, or lowering contract before authoring at scale."
            ));
            case "runtime-blocked" -> items.add(new ActionItem(
                    "warning",
                    "BIND_OPERATOR_RUNTIME",
                    "operator",
                    operator.operatorRef(),
                    label,
                    state,
                    "",
                    summary,
                    "Provide the required runtime binding or keep this operator in DESIGN-only flows."
            ));
            case "governance-review" -> items.add(new ActionItem(
                    "warning",
                    "REVIEW_OPERATOR_GOVERNANCE",
                    "operator",
                    operator.operatorRef(),
                    label,
                    state,
                    "",
                    summary,
                    "Review policy, secret, or side-effect governance before production promotion."
            ));
            case "design-only" -> items.add(new ActionItem(
                    "info",
                    "TRACK_SCHEMA_ONLY_OPERATOR",
                    "operator",
                    operator.operatorRef(),
                    label,
                    state,
                    "",
                    summary,
                    "Keep as a schema-only operator until an executable runtime binding is available."
            ));
            default -> {
                // Runtime-executable operators do not need a control-plane action.
            }
        }
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

    private static String normalizeSeverity(String value) {
        String severity = String.valueOf(value == null ? "" : value).trim().toLowerCase(Locale.ROOT);
        return switch (severity) {
            case "error", "warning", "info", "success" -> severity;
            default -> "info";
        };
    }

    private static int severityRank(String value) {
        return switch (normalizeSeverity(value)) {
            case "error" -> 0;
            case "warning" -> 1;
            case "success" -> 3;
            default -> 2;
        };
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
}
