package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portable handoff snapshot for runtime-plane teams that need to bind schema-only
 * or runtime-blocked visual graph assets after authoring.
 *
 * <p>The bundle is derived from {@link VisualRuntimeBindingRequirements}; it is
 * deliberately not a new state source or workflow engine.</p>
 *
 * @param schemaVersion handoff bundle contract version
 * @param exportedAt bundle export timestamp
 * @param sourceIndexSchemaVersion source runtime-binding index contract version
 * @param sourceIndexGeneratedAt source runtime-binding index generation timestamp
 * @param bundleFingerprint stable fingerprint of the handoff material
 * @param scope authoring scope used to derive the bundle
 * @param filter normalized requirement filter used by the source index
 * @param total requirements after query filtering and before display limiting
 * @param unfilteredTotal requirements in the authoring scope before requirement filters
 * @param displayedCount returned requirement count
 * @param itemLimit normalized item limit used by the source index
 * @param offset zero-based offset after query filtering
 * @param hasMore true when more filtered requirements exist after this exported window
 * @param requirementKeys stable requirement keys included in this handoff window
 * @param targetKindCounts filtered requirement counts by target asset kind
 * @param operatorRefCounts filtered requirement counts by operator reference
 * @param operatorLibraryIdCounts filtered requirement counts by owner operator library id
 * @param bindingKindCounts filtered requirement counts by binding kind
 * @param handoffLaneCounts filtered requirement counts by runtime-plane responsibility lane
 * @param handoffKindCounts filtered requirement counts by runtime-plane work kind
 * @param handoffTargetCounts filtered requirement counts by runtime-plane routing target
 * @param sourceKindCounts filtered requirement counts by operator source kind
 * @param loweringModeCounts filtered requirement counts by lowering mode
 * @param readinessStateCounts filtered requirement counts by graph/node readiness state
 * @param artifactKindCounts filtered publication requirement counts by frozen artifact kind
 * @param requirements returned runtime-binding requirements
 */
public record VisualRuntimeBindingHandoffBundle(
        String schemaVersion,
        Instant exportedAt,
        String sourceIndexSchemaVersion,
        Instant sourceIndexGeneratedAt,
        String bundleFingerprint,
        VisualAssetOverview.AuthoringScope scope,
        VisualRuntimeBindingRequirements.RequirementFilter filter,
        int total,
        int unfilteredTotal,
        int displayedCount,
        int itemLimit,
        int offset,
        boolean hasMore,
        List<String> requirementKeys,
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
        List<VisualRuntimeBindingRequirements.RequirementItem> requirements
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeBindingHandoff.v1";

    /**
     * Creates a normalized handoff bundle.
     */
    public VisualRuntimeBindingHandoffBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        exportedAt = exportedAt == null ? Instant.now() : exportedAt;
        sourceIndexSchemaVersion = sourceIndexSchemaVersion == null ? "" : sourceIndexSchemaVersion;
        sourceIndexGeneratedAt = sourceIndexGeneratedAt == null ? Instant.EPOCH : sourceIndexGeneratedAt;
        scope = scope == null ? VisualAssetOverview.AuthoringScope.all() : scope;
        filter = filter == null ? VisualRuntimeBindingRequirements.RequirementFilter.all() : filter;
        total = Math.max(0, total);
        unfilteredTotal = Math.max(total, unfilteredTotal);
        displayedCount = Math.max(0, displayedCount);
        itemLimit = Math.max(0, itemLimit);
        offset = Math.max(0, offset);
        requirementKeys = requirementKeys == null ? List.of() : List.copyOf(requirementKeys);
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
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        bundleFingerprint = bundleFingerprint == null || bundleFingerprint.isBlank()
                ? computedFingerprint(
                        schemaVersion,
                        sourceIndexSchemaVersion,
                        scope,
                        filter,
                        total,
                        unfilteredTotal,
                        displayedCount,
                        itemLimit,
                        offset,
                        hasMore,
                        requirementKeys,
                        targetKindCounts,
                        operatorRefCounts,
                        operatorLibraryIdCounts,
                        bindingKindCounts,
                        handoffLaneCounts,
                        handoffKindCounts,
                        handoffTargetCounts,
                        sourceKindCounts,
                        loweringModeCounts,
                        readinessStateCounts,
                        artifactKindCounts,
                        requirements)
                : bundleFingerprint.trim();
    }

    /**
     * Backward-compatible constructor for callers that do not supply the derived fingerprint.
     */
    public VisualRuntimeBindingHandoffBundle(String schemaVersion,
                                             Instant exportedAt,
                                             String sourceIndexSchemaVersion,
                                             Instant sourceIndexGeneratedAt,
                                             VisualAssetOverview.AuthoringScope scope,
                                             VisualRuntimeBindingRequirements.RequirementFilter filter,
                                             int total,
                                             int unfilteredTotal,
                                             int displayedCount,
                                             int itemLimit,
                                             int offset,
                                             boolean hasMore,
                                             List<String> requirementKeys,
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
                                             List<VisualRuntimeBindingRequirements.RequirementItem> requirements) {
        this(schemaVersion, exportedAt, sourceIndexSchemaVersion, sourceIndexGeneratedAt, "",
                scope, filter, total, unfilteredTotal, displayedCount, itemLimit, offset, hasMore,
                requirementKeys, targetKindCounts, operatorRefCounts, operatorLibraryIdCounts, bindingKindCounts,
                handoffLaneCounts, handoffKindCounts, handoffTargetCounts, sourceKindCounts, loweringModeCounts,
                readinessStateCounts, artifactKindCounts, requirements);
    }

    /**
     * Backward-compatible constructor for callers that do not supply library owner counts.
     */
    public VisualRuntimeBindingHandoffBundle(String schemaVersion,
                                             Instant exportedAt,
                                             String sourceIndexSchemaVersion,
                                             Instant sourceIndexGeneratedAt,
                                             VisualAssetOverview.AuthoringScope scope,
                                             VisualRuntimeBindingRequirements.RequirementFilter filter,
                                             int total,
                                             int unfilteredTotal,
                                             int displayedCount,
                                             int itemLimit,
                                             int offset,
                                             boolean hasMore,
                                             List<String> requirementKeys,
                                             Map<String, Integer> targetKindCounts,
                                             Map<String, Integer> operatorRefCounts,
                                             Map<String, Integer> bindingKindCounts,
                                             Map<String, Integer> handoffLaneCounts,
                                             Map<String, Integer> handoffKindCounts,
                                             Map<String, Integer> handoffTargetCounts,
                                             Map<String, Integer> sourceKindCounts,
                                             Map<String, Integer> loweringModeCounts,
                                             Map<String, Integer> readinessStateCounts,
                                             Map<String, Integer> artifactKindCounts,
                                             List<VisualRuntimeBindingRequirements.RequirementItem> requirements) {
        this(schemaVersion, exportedAt, sourceIndexSchemaVersion, sourceIndexGeneratedAt, "",
                scope, filter, total, unfilteredTotal, displayedCount, itemLimit, offset, hasMore,
                requirementKeys, targetKindCounts, operatorRefCounts, Map.of(), bindingKindCounts, handoffLaneCounts,
                handoffKindCounts, handoffTargetCounts, sourceKindCounts, loweringModeCounts, readinessStateCounts,
                artifactKindCounts, requirements);
    }

    /**
     * Backward-compatible constructor for submitted bundles that do not carry library owner counts.
     */
    public VisualRuntimeBindingHandoffBundle(String schemaVersion,
                                             Instant exportedAt,
                                             String sourceIndexSchemaVersion,
                                             Instant sourceIndexGeneratedAt,
                                             String bundleFingerprint,
                                             VisualAssetOverview.AuthoringScope scope,
                                             VisualRuntimeBindingRequirements.RequirementFilter filter,
                                             int total,
                                             int unfilteredTotal,
                                             int displayedCount,
                                             int itemLimit,
                                             int offset,
                                             boolean hasMore,
                                             List<String> requirementKeys,
                                             Map<String, Integer> targetKindCounts,
                                             Map<String, Integer> operatorRefCounts,
                                             Map<String, Integer> bindingKindCounts,
                                             Map<String, Integer> handoffLaneCounts,
                                             Map<String, Integer> handoffKindCounts,
                                             Map<String, Integer> handoffTargetCounts,
                                             Map<String, Integer> sourceKindCounts,
                                             Map<String, Integer> loweringModeCounts,
                                             Map<String, Integer> readinessStateCounts,
                                             Map<String, Integer> artifactKindCounts,
                                             List<VisualRuntimeBindingRequirements.RequirementItem> requirements) {
        this(schemaVersion, exportedAt, sourceIndexSchemaVersion, sourceIndexGeneratedAt, bundleFingerprint,
                scope, filter, total, unfilteredTotal, displayedCount, itemLimit, offset, hasMore,
                requirementKeys, targetKindCounts, operatorRefCounts, Map.of(), bindingKindCounts, handoffLaneCounts,
                handoffKindCounts, handoffTargetCounts, sourceKindCounts, loweringModeCounts, readinessStateCounts,
                artifactKindCounts, requirements);
    }

    /**
     * Backward-compatible constructor for callers that do not supply operatorRef counts.
     */
    public VisualRuntimeBindingHandoffBundle(String schemaVersion,
                                             Instant exportedAt,
                                             String sourceIndexSchemaVersion,
                                             Instant sourceIndexGeneratedAt,
                                             VisualAssetOverview.AuthoringScope scope,
                                             VisualRuntimeBindingRequirements.RequirementFilter filter,
                                             int total,
                                             int unfilteredTotal,
                                             int displayedCount,
                                             int itemLimit,
                                             int offset,
                                             boolean hasMore,
                                             List<String> requirementKeys,
                                             Map<String, Integer> targetKindCounts,
                                             Map<String, Integer> bindingKindCounts,
                                             Map<String, Integer> handoffLaneCounts,
                                             Map<String, Integer> handoffKindCounts,
                                             Map<String, Integer> handoffTargetCounts,
                                             Map<String, Integer> sourceKindCounts,
                                             Map<String, Integer> loweringModeCounts,
                                             Map<String, Integer> readinessStateCounts,
                                             Map<String, Integer> artifactKindCounts,
                                             List<VisualRuntimeBindingRequirements.RequirementItem> requirements) {
        this(schemaVersion, exportedAt, sourceIndexSchemaVersion, sourceIndexGeneratedAt, "", scope, filter, total,
                unfilteredTotal, displayedCount, itemLimit, offset, hasMore, requirementKeys, targetKindCounts,
                Map.of(), Map.of(), bindingKindCounts, handoffLaneCounts, handoffKindCounts, handoffTargetCounts,
                sourceKindCounts, loweringModeCounts, readinessStateCounts, artifactKindCounts, requirements);
    }

    /**
     * Backward-compatible constructor for submitted bundles that do not carry operatorRef counts.
     */
    public VisualRuntimeBindingHandoffBundle(String schemaVersion,
                                             Instant exportedAt,
                                             String sourceIndexSchemaVersion,
                                             Instant sourceIndexGeneratedAt,
                                             String bundleFingerprint,
                                             VisualAssetOverview.AuthoringScope scope,
                                             VisualRuntimeBindingRequirements.RequirementFilter filter,
                                             int total,
                                             int unfilteredTotal,
                                             int displayedCount,
                                             int itemLimit,
                                             int offset,
                                             boolean hasMore,
                                             List<String> requirementKeys,
                                             Map<String, Integer> targetKindCounts,
                                             Map<String, Integer> bindingKindCounts,
                                             Map<String, Integer> handoffLaneCounts,
                                             Map<String, Integer> handoffKindCounts,
                                             Map<String, Integer> handoffTargetCounts,
                                             Map<String, Integer> sourceKindCounts,
                                             Map<String, Integer> loweringModeCounts,
                                             Map<String, Integer> readinessStateCounts,
                                             Map<String, Integer> artifactKindCounts,
                                             List<VisualRuntimeBindingRequirements.RequirementItem> requirements) {
        this(schemaVersion, exportedAt, sourceIndexSchemaVersion, sourceIndexGeneratedAt, bundleFingerprint, scope,
                filter, total, unfilteredTotal, displayedCount, itemLimit, offset, hasMore, requirementKeys,
                targetKindCounts, Map.of(), Map.of(), bindingKindCounts, handoffLaneCounts, handoffKindCounts,
                handoffTargetCounts, sourceKindCounts, loweringModeCounts, readinessStateCounts, artifactKindCounts,
                requirements);
    }

    /**
     * Builds a portable handoff bundle from a runtime-binding requirement index.
     *
     * @param index source runtime-binding requirement index
     * @return portable handoff bundle
     */
    public static VisualRuntimeBindingHandoffBundle from(VisualRuntimeBindingRequirements index) {
        VisualRuntimeBindingRequirements safeIndex = index == null
                ? VisualRuntimeBindingRequirements.empty()
                : index;
        List<VisualRuntimeBindingRequirements.RequirementItem> items = safeIndex.items();
        return new VisualRuntimeBindingHandoffBundle(
                SCHEMA_VERSION,
                Instant.now(),
                safeIndex.schemaVersion(),
                safeIndex.generatedAt(),
                "",
                safeIndex.scope(),
                safeIndex.filter(),
                safeIndex.total(),
                safeIndex.unfilteredTotal(),
                safeIndex.displayedCount(),
                safeIndex.itemLimit(),
                safeIndex.offset(),
                safeIndex.hasMore(),
                items.stream()
                        .map(VisualRuntimeBindingRequirements.RequirementItem::requirementKey)
                        .filter(key -> key != null && !key.isBlank())
                        .toList(),
                safeIndex.targetKindCounts(),
                safeIndex.operatorRefCounts(),
                safeIndex.operatorLibraryIdCounts(),
                safeIndex.bindingKindCounts(),
                safeIndex.handoffLaneCounts(),
                safeIndex.handoffKindCounts(),
                safeIndex.handoffTargetCounts(),
                safeIndex.sourceKindCounts(),
                safeIndex.loweringModeCounts(),
                safeIndex.readinessStateCounts(),
                safeIndex.artifactKindCounts(),
                items
        );
    }

    /**
     * Computes the canonical fingerprint for the current normalized handoff material.
     *
     * @return expected fingerprint derived from bundle content
     */
    public String computedBundleFingerprint() {
        return computedFingerprint(
                schemaVersion,
                sourceIndexSchemaVersion,
                scope,
                filter,
                total,
                unfilteredTotal,
                displayedCount,
                itemLimit,
                offset,
                hasMore,
                requirementKeys,
                targetKindCounts,
                operatorRefCounts,
                operatorLibraryIdCounts,
                bindingKindCounts,
                handoffLaneCounts,
                handoffKindCounts,
                handoffTargetCounts,
                sourceKindCounts,
                loweringModeCounts,
                readinessStateCounts,
                artifactKindCounts,
                requirements);
    }

    /**
     * Checks whether the submitted fingerprint matches the current normalized material.
     *
     * @return true when the handoff fingerprint is current for this bundle body
     */
    public boolean bundleFingerprintVerified() {
        return bundleFingerprint.equals(computedBundleFingerprint())
                || bundleFingerprint.equals(legacyComputedBundleFingerprint());
    }

    private static Map<String, Integer> immutableCounts(Map<String, Integer> counts) {
        return counts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    private String legacyComputedBundleFingerprint() {
        return computedFingerprint(
                schemaVersion,
                sourceIndexSchemaVersion,
                scope,
                filter,
                total,
                unfilteredTotal,
                displayedCount,
                itemLimit,
                offset,
                hasMore,
                requirementKeys,
                targetKindCounts,
                operatorRefCounts,
                operatorLibraryIdCounts,
                bindingKindCounts,
                handoffLaneCounts,
                handoffKindCounts,
                handoffTargetCounts,
                sourceKindCounts,
                loweringModeCounts,
                readinessStateCounts,
                artifactKindCounts,
                requirements,
                false);
    }

    private static String computedFingerprint(String schemaVersion,
                                              String sourceIndexSchemaVersion,
                                              VisualAssetOverview.AuthoringScope scope,
                                              VisualRuntimeBindingRequirements.RequirementFilter filter,
                                              int total,
                                              int unfilteredTotal,
                                              int displayedCount,
                                              int itemLimit,
                                              int offset,
                                              boolean hasMore,
                                              List<String> requirementKeys,
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
                                              List<VisualRuntimeBindingRequirements.RequirementItem> requirements) {
        return computedFingerprint(
                schemaVersion,
                sourceIndexSchemaVersion,
                scope,
                filter,
                total,
                unfilteredTotal,
                displayedCount,
                itemLimit,
                offset,
                hasMore,
                requirementKeys,
                targetKindCounts,
                operatorRefCounts,
                operatorLibraryIdCounts,
                bindingKindCounts,
                handoffLaneCounts,
                handoffKindCounts,
                handoffTargetCounts,
                sourceKindCounts,
                loweringModeCounts,
                readinessStateCounts,
                artifactKindCounts,
                requirements,
                true);
    }

    private static String computedFingerprint(String schemaVersion,
                                              String sourceIndexSchemaVersion,
                                              VisualAssetOverview.AuthoringScope scope,
                                              VisualRuntimeBindingRequirements.RequirementFilter filter,
                                              int total,
                                              int unfilteredTotal,
                                              int displayedCount,
                                              int itemLimit,
                                              int offset,
                                              boolean hasMore,
                                              List<String> requirementKeys,
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
                                              List<VisualRuntimeBindingRequirements.RequirementItem> requirements,
                                              boolean includeOperatorOwnership) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", schemaVersion);
        material.put("sourceIndexSchemaVersion", sourceIndexSchemaVersion);
        material.put("scope", scope);
        material.put("filter", includeOperatorOwnership ? filter : legacyFilter(filter));
        material.put("total", total);
        material.put("unfilteredTotal", unfilteredTotal);
        material.put("displayedCount", displayedCount);
        material.put("itemLimit", itemLimit);
        material.put("offset", offset);
        material.put("hasMore", hasMore);
        material.put("requirementKeys", requirementKeys);
        material.put("targetKindCounts", targetKindCounts);
        if (includeOperatorOwnership) {
            material.put("operatorRefCounts", operatorRefCounts);
            material.put("operatorLibraryIdCounts", operatorLibraryIdCounts);
        }
        material.put("bindingKindCounts", bindingKindCounts);
        material.put("handoffLaneCounts", handoffLaneCounts);
        material.put("handoffKindCounts", handoffKindCounts);
        material.put("handoffTargetCounts", handoffTargetCounts);
        material.put("sourceKindCounts", sourceKindCounts);
        material.put("loweringModeCounts", loweringModeCounts);
        material.put("readinessStateCounts", readinessStateCounts);
        material.put("artifactKindCounts", artifactKindCounts);
        material.put("requirements", includeOperatorOwnership ? requirements : legacyRequirements(requirements));
        return VisualBundleFingerprint.fromMaterial(material);
    }

    private static List<Map<String, Object>> legacyRequirements(
            List<VisualRuntimeBindingRequirements.RequirementItem> requirements) {
        return (requirements == null ? List.<VisualRuntimeBindingRequirements.RequirementItem>of() : requirements)
                .stream()
                .map(VisualRuntimeBindingHandoffBundle::legacyRequirement)
                .toList();
    }

    private static Map<String, Object> legacyRequirement(VisualRuntimeBindingRequirements.RequirementItem item) {
        VisualRuntimeBindingRequirements.RequirementItem safeItem = item == null
                ? new VisualRuntimeBindingRequirements.RequirementItem(
                        "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                        "", "", "", "", "")
                : item;
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("requirementKey", safeItem.requirementKey());
        legacy.put("targetKind", safeItem.targetKind());
        legacy.put("targetId", safeItem.targetId());
        legacy.put("targetLabel", safeItem.targetLabel());
        legacy.put("graphName", safeItem.graphName());
        legacy.put("tenantId", safeItem.tenantId());
        legacy.put("namespace", safeItem.namespace());
        legacy.put("environment", safeItem.environment());
        legacy.put("artifactKind", safeItem.artifactKind());
        legacy.put("updatedAt", safeItem.updatedAt());
        legacy.put("nodeId", safeItem.nodeId());
        legacy.put("operatorRef", safeItem.operatorRef());
        legacy.put("readinessState", safeItem.readinessState());
        legacy.put("requirementState", safeItem.requirementState());
        legacy.put("level", safeItem.level());
        legacy.put("sourceKind", safeItem.sourceKind());
        legacy.put("loweringMode", safeItem.loweringMode());
        legacy.put("bindingKind", safeItem.bindingKind());
        legacy.put("bindingTarget", safeItem.bindingTarget());
        legacy.put("handoffLane", safeItem.handoffLane());
        legacy.put("handoffKind", safeItem.handoffKind());
        legacy.put("handoffTarget", safeItem.handoffTarget());
        legacy.put("title", safeItem.title());
        legacy.put("summary", safeItem.summary());
        legacy.put("recommendedAction", safeItem.recommendedAction());
        return legacy;
    }

    private static Object legacyFilter(VisualRuntimeBindingRequirements.RequirementFilter filter) {
        VisualRuntimeBindingRequirements.RequirementFilter safeFilter = filter == null
                ? VisualRuntimeBindingRequirements.RequirementFilter.all()
                : filter;
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("targetKind", safeFilter.targetKind());
        legacy.put("bindingKind", safeFilter.bindingKind());
        legacy.put("handoffLane", safeFilter.handoffLane());
        legacy.put("handoffKind", safeFilter.handoffKind());
        legacy.put("handoffTarget", safeFilter.handoffTarget());
        legacy.put("sourceKind", safeFilter.sourceKind());
        legacy.put("loweringMode", safeFilter.loweringMode());
        legacy.put("readinessState", safeFilter.readinessState());
        legacy.put("requirementKey", safeFilter.requirementKey());
        legacy.put("filtered", safeFilter.filtered());
        return legacy;
    }
}
