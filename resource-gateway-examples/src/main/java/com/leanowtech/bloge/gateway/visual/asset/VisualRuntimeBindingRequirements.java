package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraftSummary;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationSummary;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualRuntimeBindingRequirementKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Queryable runtime-binding requirement index for schema-valid design assets.
 *
 * <p>This is a fact index rather than a workflow engine: draft validation and publication
 * freezing still own the requirements, while this read model makes them easy to page,
 * filter, route, and audit from external control planes.</p>
 *
 * @param schemaVersion index contract version
 * @param generatedAt server timestamp when this index was derived
 * @param scope authoring scope used to derive this index
 * @param total requirements after query filtering and before display limiting
 * @param unfilteredTotal requirements in the authoring scope before requirement filters
 * @param displayedCount returned item count
 * @param itemLimit normalized maximum number of item details returned
 * @param offset zero-based offset after query filtering
 * @param hasMore true when more filtered requirements exist after the returned window
 * @param filter normalized requirement filter
 * @param targetKindCounts filtered requirement counts by target asset kind
 * @param operatorRefCounts filtered requirement counts by operator reference
 * @param operatorLibraryIdCounts filtered requirement counts by owner operator library id
 * @param bindingKindCounts filtered requirement counts by binding kind
 * @param handoffLaneCounts filtered requirement counts by runtime-plane handoff lane
 * @param handoffKindCounts filtered requirement counts by runtime-plane handoff work kind
 * @param handoffTargetCounts filtered requirement counts by runtime-plane routing target
 * @param sourceKindCounts filtered requirement counts by operator source kind
 * @param loweringModeCounts filtered requirement counts by lowering mode
 * @param readinessStateCounts filtered requirement counts by graph/node readiness state
 * @param artifactKindCounts filtered publication requirement counts by frozen artifact kind
 * @param items returned requirement window
 */
public record VisualRuntimeBindingRequirements(
        String schemaVersion,
        Instant generatedAt,
        VisualAssetOverview.AuthoringScope scope,
        int total,
        int unfilteredTotal,
        int displayedCount,
        int itemLimit,
        int offset,
        boolean hasMore,
        RequirementFilter filter,
        Map<String, Integer> targetKindCounts,
        Map<String, Integer> operatorRefCounts,
        Map<String, Integer> operatorLibraryIdCounts,
        Map<String, Integer> bindingKindCounts,
        Map<String, Integer> handoffLaneCounts,
        Map<String, Integer> handoffKindCounts,
        Map<String, Integer> handoffTargetCounts,
        Map<String, Integer> sourceKindCounts,
        Map<String, Integer> loweringModeCounts,
        Map<String, Integer> readinessStateCounts,
        Map<String, Integer> artifactKindCounts,
        List<RequirementItem> items
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeBindingRequirements.v1";
    public static final int DEFAULT_ITEM_LIMIT = 50;
    public static final int MAX_ITEM_LIMIT = 200;

    /**
     * Creates a runtime binding requirement index.
     */
    public VisualRuntimeBindingRequirements {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        scope = scope == null ? VisualAssetOverview.AuthoringScope.all() : scope;
        total = Math.max(0, total);
        unfilteredTotal = Math.max(total, unfilteredTotal);
        itemLimit = normalizeLimit(itemLimit);
        offset = Math.max(0, offset);
        filter = filter == null ? RequirementFilter.all() : filter;
        targetKindCounts = immutableCounts(targetKindCounts);
        operatorRefCounts = immutableCounts(operatorRefCounts);
        operatorLibraryIdCounts = immutableCounts(operatorLibraryIdCounts);
        bindingKindCounts = immutableCounts(bindingKindCounts);
        handoffLaneCounts = immutableCounts(handoffLaneCounts);
        handoffKindCounts = immutableCounts(handoffKindCounts);
        handoffTargetCounts = immutableCounts(handoffTargetCounts);
        sourceKindCounts = immutableCounts(sourceKindCounts);
        loweringModeCounts = immutableCounts(loweringModeCounts);
        readinessStateCounts = immutableCounts(readinessStateCounts);
        artifactKindCounts = immutableCounts(artifactKindCounts);
        items = items == null ? List.of() : List.copyOf(items);
        displayedCount = items.size();
        hasMore = offset + displayedCount < total;
    }

    /**
     * Builds a requirement index from already scoped draft/publication summaries.
     *
     * @param drafts draft summaries in scope
     * @param publications publication summaries in scope
     * @param tenantId tenant scope used for the read model, empty for all
     * @param namespace namespace scope used for the read model, empty for all
     * @param environment environment scope used for the read model, empty for all
     * @param itemLimit requested item limit
     * @param offset zero-based item offset after filtering
     * @param targetKind optional target kind filter
     * @param operatorRef optional operator reference filter
     * @param operatorLibraryId optional owner operator library id filter
     * @param bindingKind optional binding kind filter
     * @param handoffLane optional runtime-plane handoff lane filter
     * @param handoffKind optional runtime-plane handoff work kind filter
     * @param handoffTarget optional runtime-plane routing target filter
     * @param sourceKind optional source kind filter
     * @param loweringMode optional lowering mode filter
     * @param readinessState optional graph/node readiness state filter
     * @param requirementKey optional stable requirement key filter
     * @return runtime binding requirement index
     */
    public static VisualRuntimeBindingRequirements from(List<GraphDraftSummary> drafts,
                                                        List<VisualGraphPublicationSummary> publications,
                                                        Map<String, String> operatorLibraryIdsByOperatorRef,
                                                        String tenantId,
                                                        String namespace,
                                                        String environment,
                                                        int itemLimit,
                                                        int offset,
                                                        String targetKind,
                                                        String operatorRef,
                                                        String operatorLibraryId,
                                                        String bindingKind,
                                                        String handoffLane,
                                                        String handoffKind,
                                                        String handoffTarget,
                                                        String sourceKind,
                                                        String loweringMode,
                                                        String readinessState,
                                                        String requirementKey) {
        List<RequirementItem> generated = generate(drafts, publications, operatorLibraryIdsByOperatorRef);
        RequirementFilter filter = new RequirementFilter(targetKind, operatorRef, operatorLibraryId, bindingKind,
                handoffLane, handoffKind, handoffTarget, sourceKind, loweringMode, readinessState, requirementKey);
        List<RequirementItem> filtered = generated.stream()
                .filter(filter::matches)
                .sorted(VisualRuntimeBindingRequirements::compareItems)
                .toList();
        int normalizedLimit = normalizeLimit(itemLimit);
        int normalizedOffset = Math.max(0, offset);
        return new VisualRuntimeBindingRequirements(
                SCHEMA_VERSION,
                Instant.now(),
                new VisualAssetOverview.AuthoringScope(tenantId, namespace, environment),
                filtered.size(),
                generated.size(),
                Math.min(Math.max(0, filtered.size() - normalizedOffset), normalizedLimit),
                normalizedLimit,
                normalizedOffset,
                false,
                filter,
                countBy(filtered, RequirementItem::targetKind),
                countBy(filtered, RequirementItem::operatorRef),
                countBy(filtered, RequirementItem::operatorLibraryId),
                countBy(filtered, RequirementItem::bindingKind),
                countBy(filtered, RequirementItem::handoffLane),
                countBy(filtered, RequirementItem::handoffKind),
                countBy(filtered, RequirementItem::handoffTarget),
                countBy(filtered, RequirementItem::sourceKind),
                countBy(filtered, RequirementItem::loweringMode),
                countBy(filtered, RequirementItem::readinessState),
                countBy(filtered, RequirementItem::artifactKind),
                filtered.stream().skip(normalizedOffset).limit(normalizedLimit).toList()
        );
    }

    public static VisualRuntimeBindingRequirements from(List<GraphDraftSummary> drafts,
                                                        List<VisualGraphPublicationSummary> publications,
                                                        String tenantId,
                                                        String namespace,
                                                        String environment,
                                                        int itemLimit,
                                                        int offset,
                                                        String targetKind,
                                                        String operatorRef,
                                                        String bindingKind,
                                                        String handoffLane,
                                                        String handoffKind,
                                                        String handoffTarget,
                                                        String sourceKind,
                                                        String loweringMode,
                                                        String readinessState,
                                                        String requirementKey) {
        return from(drafts, publications, Map.of(), tenantId, namespace, environment, itemLimit, offset, targetKind,
                operatorRef, "", bindingKind, handoffLane, handoffKind, handoffTarget, sourceKind, loweringMode,
                readinessState, requirementKey);
    }

    /**
     * Backward-compatible builder for callers that do not filter by operator reference.
     */
    public static VisualRuntimeBindingRequirements from(List<GraphDraftSummary> drafts,
                                                        List<VisualGraphPublicationSummary> publications,
                                                        String tenantId,
                                                        String namespace,
                                                        String environment,
                                                        int itemLimit,
                                                        int offset,
                                                        String targetKind,
                                                        String bindingKind,
                                                        String handoffLane,
                                                        String handoffKind,
                                                        String handoffTarget,
                                                        String sourceKind,
                                                        String loweringMode,
                                                        String readinessState,
                                                        String requirementKey) {
        return from(drafts, publications, tenantId, namespace, environment, itemLimit, offset, targetKind, "",
                bindingKind, handoffLane, handoffKind, handoffTarget, sourceKind, loweringMode, readinessState,
                requirementKey);
    }

    /**
     * @return an empty index
     */
    public static VisualRuntimeBindingRequirements empty() {
        return from(List.of(), List.of(), "", "", "", DEFAULT_ITEM_LIMIT, 0, "", "", "", "", "", "", "", "",
                "", "");
    }

    /**
     * Normalized runtime binding requirement query filter.
     *
     * @param targetKind draft or publication, empty when unfiltered
     * @param operatorRef operator reference, empty when unfiltered
     * @param operatorLibraryId owner operator library id, empty when unfiltered
     * @param bindingKind binding kind, empty when unfiltered
     * @param handoffLane runtime-plane handoff lane, empty when unfiltered
     * @param handoffKind runtime-plane handoff work kind, empty when unfiltered
     * @param handoffTarget runtime-plane routing target, empty when unfiltered
     * @param sourceKind operator source kind, empty when unfiltered
     * @param loweringMode lowering mode, empty when unfiltered
     * @param readinessState graph/node readiness state, empty when unfiltered
     * @param requirementKey stable requirement key, empty when unfiltered
     * @param filtered true when any filter is active
     */
    public record RequirementFilter(
            String targetKind,
            String operatorRef,
            String operatorLibraryId,
            String bindingKind,
            String handoffLane,
            String handoffKind,
            String handoffTarget,
            String sourceKind,
            String loweringMode,
            String readinessState,
            String requirementKey,
            boolean filtered
    ) {
        public RequirementFilter(String targetKind,
                                 String operatorRef,
                                 String operatorLibraryId,
                                 String bindingKind,
                                 String handoffLane,
                                 String handoffKind,
                                 String handoffTarget,
                                 String sourceKind,
                                 String loweringMode,
                                 String readinessState,
                                 String requirementKey) {
            this(
                    normalizeFacetValue(targetKind),
                    normalizeTextValue(operatorRef),
                    normalizeTextValue(operatorLibraryId),
                    normalizeFacetValue(bindingKind),
                    normalizeFacetValue(handoffLane),
                    normalizeFacetValue(handoffKind),
                    normalizeTextValue(handoffTarget),
                    normalizeFacetValue(sourceKind),
                    normalizeFacetValue(loweringMode),
                    normalizeFacetValue(readinessState),
                    normalizeTextValue(requirementKey),
                    !normalizeFacetValue(targetKind).isBlank()
                            || !normalizeTextValue(operatorRef).isBlank()
                            || !normalizeTextValue(operatorLibraryId).isBlank()
                            || !normalizeFacetValue(bindingKind).isBlank()
                            || !normalizeFacetValue(handoffLane).isBlank()
                            || !normalizeFacetValue(handoffKind).isBlank()
                            || !normalizeTextValue(handoffTarget).isBlank()
                            || !normalizeFacetValue(sourceKind).isBlank()
                            || !normalizeFacetValue(loweringMode).isBlank()
                            || !normalizeFacetValue(readinessState).isBlank()
                            || !normalizeTextValue(requirementKey).isBlank()
            );
        }

        public RequirementFilter(String targetKind,
                                 String operatorRef,
                                 String bindingKind,
                                 String handoffLane,
                                 String handoffKind,
                                 String handoffTarget,
                                 String sourceKind,
                                 String loweringMode,
                                 String readinessState,
                                 String requirementKey) {
            this(targetKind, operatorRef, "", bindingKind, handoffLane, handoffKind, handoffTarget, sourceKind,
                    loweringMode,
                    readinessState, requirementKey);
        }

        public RequirementFilter(String targetKind,
                                 String bindingKind,
                                 String handoffLane,
                                 String handoffKind,
                                 String handoffTarget,
                                 String sourceKind,
                                 String loweringMode,
                                 String readinessState,
                                 String requirementKey) {
            this(targetKind, "", "", bindingKind, handoffLane, handoffKind, handoffTarget, sourceKind, loweringMode,
                    readinessState, requirementKey);
        }

        public RequirementFilter {
            targetKind = normalizeFacetValue(targetKind);
            operatorRef = normalizeTextValue(operatorRef);
            operatorLibraryId = normalizeTextValue(operatorLibraryId);
            bindingKind = normalizeFacetValue(bindingKind);
            handoffLane = normalizeFacetValue(handoffLane);
            handoffKind = normalizeFacetValue(handoffKind);
            handoffTarget = normalizeTextValue(handoffTarget);
            sourceKind = normalizeFacetValue(sourceKind);
            loweringMode = normalizeFacetValue(loweringMode);
            readinessState = normalizeFacetValue(readinessState);
            requirementKey = normalizeTextValue(requirementKey);
            filtered = !targetKind.isBlank()
                    || !operatorRef.isBlank()
                    || !operatorLibraryId.isBlank()
                    || !bindingKind.isBlank()
                    || !handoffLane.isBlank()
                    || !handoffKind.isBlank()
                    || !handoffTarget.isBlank()
                    || !sourceKind.isBlank()
                    || !loweringMode.isBlank()
                    || !readinessState.isBlank()
                    || !requirementKey.isBlank();
        }

        static RequirementFilter all() {
            return new RequirementFilter("", "", "", "", "", "", "", "", "", "", "");
        }

        boolean matches(RequirementItem item) {
            return item != null
                    && (requirementKey.isBlank() || requirementKey.equals(item.requirementKey()))
                    && (targetKind.isBlank() || targetKind.equals(item.targetKind()))
                    && (operatorRef.isBlank() || operatorRef.equals(item.operatorRef()))
                    && (operatorLibraryId.isBlank() || operatorLibraryId.equals(item.operatorLibraryId()))
                    && (bindingKind.isBlank() || bindingKind.equals(item.bindingKind()))
                    && (handoffLane.isBlank() || handoffLane.equals(item.handoffLane()))
                    && (handoffKind.isBlank() || handoffKind.equals(item.handoffKind()))
                    && (handoffTarget.isBlank() || handoffTarget.equals(item.handoffTarget()))
                    && (sourceKind.isBlank() || sourceKind.equals(item.sourceKind()))
                    && (loweringMode.isBlank() || loweringMode.equals(item.loweringMode()))
                    && (readinessState.isBlank()
                            || readinessState.equals(item.readinessState())
                            || readinessState.equals(item.requirementState()));
        }
    }

    /**
     * One node-scoped runtime binding requirement.
     *
     * @param requirementKey stable machine-readable key for deduplication and routing
     * @param targetKind target asset kind
     * @param targetId target asset id
     * @param targetLabel human-readable target label
     * @param graphName graph name
     * @param tenantId tenant id
     * @param namespace namespace
     * @param environment authoring environment
     * @param artifactKind publication artifact kind when applicable
     * @param updatedAt draft update or publication creation timestamp
     * @param nodeId draft/publication node id
     * @param operatorRef operator reference used by the node
     * @param operatorLibraryId owner operator library id when the operator comes from an imported library
     * @param readinessState graph readiness state
     * @param requirementState node requirement readiness state
     * @param level requirement severity
     * @param sourceKind operator source kind
     * @param loweringMode lowering mode
     * @param bindingKind missing binding kind
     * @param bindingTarget topic/tool/channel/path/operator target when declared
     * @param handoffLane runtime-plane responsibility lane
     * @param handoffKind runtime-plane work kind
     * @param handoffTarget runtime-plane routing target
     * @param title short display title
     * @param summary human-readable binding gap summary
     * @param recommendedAction human-readable next action
     */
    public record RequirementItem(
            String requirementKey,
            String targetKind,
            String targetId,
            String targetLabel,
            String graphName,
            String tenantId,
            String namespace,
            String environment,
            String artifactKind,
            String updatedAt,
            String nodeId,
            String operatorRef,
            String operatorLibraryId,
            String readinessState,
            String requirementState,
            String level,
            String sourceKind,
            String loweringMode,
            String bindingKind,
            String bindingTarget,
            String handoffLane,
            String handoffKind,
            String handoffTarget,
            String title,
            String summary,
            String recommendedAction
    ) {
        public RequirementItem {
            targetKind = normalizeFacetValue(targetKind);
            targetId = targetId == null ? "" : targetId;
            targetLabel = targetLabel == null ? "" : targetLabel;
            graphName = graphName == null ? "" : graphName;
            tenantId = tenantId == null ? "" : tenantId;
            namespace = namespace == null ? "" : namespace;
            environment = environment == null ? "" : environment;
            artifactKind = artifactKind == null || artifactKind.isBlank()
                    ? ""
                    : artifactKind.trim().toUpperCase(Locale.ROOT);
            updatedAt = updatedAt == null ? "" : updatedAt;
            nodeId = nodeId == null ? "" : nodeId;
            operatorRef = operatorRef == null ? "" : operatorRef;
            operatorLibraryId = normalizeTextValue(operatorLibraryId);
            readinessState = normalizeFacetValue(readinessState);
            requirementState = normalizeFacetValue(requirementState);
            level = normalizeFacetValue(level);
            sourceKind = normalizeFacetValue(sourceKind);
            loweringMode = normalizeFacetValue(loweringMode);
            bindingKind = normalizeFacetValue(bindingKind);
            bindingTarget = bindingTarget == null ? "" : bindingTarget;
            handoffLane = normalizeFacetValue(handoffLane);
            handoffKind = normalizeFacetValue(handoffKind);
            handoffTarget = normalizeTextValue(handoffTarget);
            title = title == null ? "" : title;
            summary = summary == null ? "" : summary;
            recommendedAction = recommendedAction == null ? "" : recommendedAction;
            requirementKey = requirementKey == null || requirementKey.isBlank()
                    ? VisualRuntimeBindingRequirementKey.stable(
                            targetKind, targetId, nodeId, bindingKind, bindingTarget, artifactKind)
                    : requirementKey.trim();
        }
    }

    private static List<RequirementItem> generate(List<GraphDraftSummary> drafts,
                                                  List<VisualGraphPublicationSummary> publications,
                                                  Map<String, String> operatorLibraryIdsByOperatorRef) {
        List<RequirementItem> generated = new ArrayList<>();
        for (GraphDraftSummary draft : drafts == null ? List.<GraphDraftSummary>of() : drafts) {
            if (draft == null || !draft.active()) {
                continue;
            }
            Map<String, String> ownerIdsByOperatorRef = mergedOperatorLibraryIds(
                    draft.operatorLibraryIdsByOperatorRef(),
                    operatorLibraryIdsByOperatorRef
            );
            for (VisualGraphReadiness.RuntimeBindingRequirement requirement
                    : runtimeBindingRequirements(draft.readiness())) {
                generated.add(fromDraft(draft, requirement,
                        operatorLibraryId(requirement, ownerIdsByOperatorRef)));
            }
        }
        for (VisualGraphPublicationSummary publication : publications == null
                ? List.<VisualGraphPublicationSummary>of()
                : publications) {
            if (publication == null) {
                continue;
            }
            Map<String, String> ownerIdsByOperatorRef = mergedOperatorLibraryIds(
                    publication.operatorLibraryIdsByOperatorRef(),
                    operatorLibraryIdsByOperatorRef
            );
            for (VisualGraphReadiness.RuntimeBindingRequirement requirement
                    : runtimeBindingRequirements(publication.readiness())) {
                generated.add(fromPublication(publication, requirement,
                        operatorLibraryId(requirement, ownerIdsByOperatorRef)));
            }
        }
        return generated;
    }

    private static RequirementItem fromDraft(GraphDraftSummary draft,
                                             VisualGraphReadiness.RuntimeBindingRequirement requirement,
                                             String operatorLibraryId) {
        String label = "%s @%d".formatted(
                draft.graphName().isBlank() ? draft.draftId() : draft.graphName(),
                draft.currentRevision() > 0 ? draft.currentRevision() : draft.latestRevision()
        );
        return new RequirementItem(
                VisualRuntimeBindingRequirementKey.stable("draft", draft.draftId(), requirement.nodeId(),
                        requirement.bindingKind(), requirement.bindingTarget(), ""),
                "draft",
                draft.draftId(),
                runtimeBindingTargetLabel(label, requirement),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                "",
                draft.updatedAt(),
                requirement.nodeId(),
                requirement.operatorRef(),
                operatorLibraryId,
                draft.readiness() == null ? "" : draft.readiness().state(),
                requirement.state(),
                requirement.level(),
                requirement.sourceKind(),
                requirement.loweringMode(),
                requirement.bindingKind(),
                requirement.bindingTarget(),
                requirement.handoffLane(),
                requirement.handoffKind(),
                requirement.handoffTarget(),
                requirement.title(),
                requirement.summary(),
                requirement.recommendedAction()
        );
    }

    private static RequirementItem fromPublication(VisualGraphPublicationSummary publication,
                                                   VisualGraphReadiness.RuntimeBindingRequirement requirement,
                                                   String operatorLibraryId) {
        String label = "%s publication @%d".formatted(
                publication.graphName().isBlank() ? publication.publicationId() : publication.graphName(),
                publication.draftRevision()
        );
        String artifactKind = publication.artifactKind();
        return new RequirementItem(
                VisualRuntimeBindingRequirementKey.stable("publication", publication.publicationId(), requirement.nodeId(),
                        requirement.bindingKind(), requirement.bindingTarget(), artifactKind),
                "publication",
                publication.publicationId(),
                runtimeBindingTargetLabel(label, requirement),
                publication.graphName(),
                publication.tenantId(),
                publication.namespace(),
                publication.environment(),
                artifactKind,
                publication.createdAt().toString(),
                requirement.nodeId(),
                requirement.operatorRef(),
                operatorLibraryId,
                publication.readiness() == null ? "" : publication.readiness().state(),
                requirement.state(),
                requirement.level(),
                requirement.sourceKind(),
                requirement.loweringMode(),
                requirement.bindingKind(),
                requirement.bindingTarget(),
                requirement.handoffLane(),
                requirement.handoffKind(),
                requirement.handoffTarget(),
                requirement.title(),
                requirement.summary(),
                requirement.recommendedAction()
        );
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

    private static String operatorLibraryId(VisualGraphReadiness.RuntimeBindingRequirement requirement,
                                            Map<String, String> operatorLibraryIdsByOperatorRef) {
        if (requirement == null || operatorLibraryIdsByOperatorRef == null) {
            return "";
        }
        return normalizeTextValue(operatorLibraryIdsByOperatorRef.get(requirement.operatorRef()));
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

    private static String runtimeBindingTargetLabel(String assetLabel,
                                                    VisualGraphReadiness.RuntimeBindingRequirement requirement) {
        if (requirement == null || requirement.nodeId().isBlank()) {
            return assetLabel;
        }
        return "%s / %s".formatted(assetLabel, requirement.nodeId());
    }

    private static int compareItems(RequirementItem left, RequirementItem right) {
        int targetKind = left.targetKind().compareTo(right.targetKind());
        if (targetKind != 0) {
            return targetKind;
        }
        int targetId = left.targetId().compareTo(right.targetId());
        if (targetId != 0) {
            return targetId;
        }
        int nodeId = left.nodeId().compareTo(right.nodeId());
        if (nodeId != 0) {
            return nodeId;
        }
        int bindingKind = left.bindingKind().compareTo(right.bindingKind());
        if (bindingKind != 0) {
            return bindingKind;
        }
        return left.bindingTarget().compareTo(right.bindingTarget());
    }

    private static Map<String, Integer> countBy(List<RequirementItem> items,
                                                Function<RequirementItem, String> classifier) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RequirementItem item : items == null ? List.<RequirementItem>of() : items) {
            String key = classifier.apply(item);
            if (key == null || key.isBlank()) {
                continue;
            }
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> immutableCounts(Map<String, Integer> counts) {
        return counts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    private static int normalizeLimit(int itemLimit) {
        return Math.max(0, Math.min(itemLimit, MAX_ITEM_LIMIT));
    }

    private static String normalizeFacetValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeTextValue(String value) {
        return value == null ? "" : value.trim();
    }

}
