package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only reconciliation report for a portable runtime-binding handoff bundle.
 *
 * <p>This review compares an exported handoff snapshot with the current
 * runtime-binding requirement read model. It does not persist workflow state.</p>
 *
 * @param schemaVersion review contract version
 * @param reviewedAt server timestamp when this review was derived
 * @param reviewable true when the submitted bundle was understood and compared
 * @param state review state such as current, stale, empty, or invalid-bundle
 * @param level UI/control-plane severity
 * @param message human-readable review summary
 * @param sourceBundleSchemaVersion submitted handoff bundle schema version
 * @param sourceExportedAt original bundle export timestamp
 * @param sourceIndexGeneratedAt original source index generation timestamp
 * @param scope authoring scope from the submitted bundle
 * @param filter requirement filter from the submitted bundle
 * @param exportedRequirementCount submitted requirement key count
 * @param currentWindowTotal current matching requirement total for the same scope/filter
 * @param currentWindowDisplayedCount current matching requirement count in the same page window
 * @param currentWindowHasMore true when the current scope/filter has more rows after the reviewed window
 * @param matchedCount exported requirements still present with the same reviewed surface
 * @param driftedCount exported requirements still present but changed
 * @param missingCount exported requirements no longer present in the current read model
 * @param newCurrentWindowCount current same-window requirements absent from the submitted bundle
 * @param exportedRequirementKeys normalized submitted requirement keys
 * @param currentWindowRequirementKeys current same-window requirement keys
 * @param newCurrentWindowRequirementKeys current same-window keys absent from the submitted bundle
 * @param statusCounts review item counts by status
 * @param fieldChangeCategoryCounts drifted field-change counts by category
 * @param items per-requirement review rows for the exported bundle
 * @param diagnostics structured diagnostics for invalid or partial reviews
 */
public record VisualRuntimeBindingHandoffReview(
        String schemaVersion,
        Instant reviewedAt,
        boolean reviewable,
        String state,
        String level,
        String message,
        String sourceBundleSchemaVersion,
        Instant sourceExportedAt,
        Instant sourceIndexGeneratedAt,
        VisualAssetOverview.AuthoringScope scope,
        VisualRuntimeBindingRequirements.RequirementFilter filter,
        int exportedRequirementCount,
        int currentWindowTotal,
        int currentWindowDisplayedCount,
        boolean currentWindowHasMore,
        int matchedCount,
        int driftedCount,
        int missingCount,
        int newCurrentWindowCount,
        List<String> exportedRequirementKeys,
        List<String> currentWindowRequirementKeys,
        List<String> newCurrentWindowRequirementKeys,
        Map<String, Integer> statusCounts,
        Map<String, Integer> fieldChangeCategoryCounts,
        List<ReviewItem> items,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeBindingHandoffReview.v1";

    public static final String STATE_CURRENT = "current";
    public static final String STATE_STALE = "stale";
    public static final String STATE_EMPTY = "empty";
    public static final String STATE_INVALID_BUNDLE = "invalid-bundle";

    /**
     * Creates a normalized review.
     */
    public VisualRuntimeBindingHandoffReview {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        reviewedAt = reviewedAt == null ? Instant.now() : reviewedAt;
        state = normalizeState(state);
        level = normalizeLevel(level);
        message = message == null ? "" : message;
        sourceBundleSchemaVersion = sourceBundleSchemaVersion == null ? "" : sourceBundleSchemaVersion;
        sourceExportedAt = sourceExportedAt == null ? Instant.EPOCH : sourceExportedAt;
        sourceIndexGeneratedAt = sourceIndexGeneratedAt == null ? Instant.EPOCH : sourceIndexGeneratedAt;
        scope = scope == null ? VisualAssetOverview.AuthoringScope.all() : scope;
        filter = filter == null ? VisualRuntimeBindingRequirements.RequirementFilter.all() : filter;
        exportedRequirementCount = Math.max(0, exportedRequirementCount);
        currentWindowTotal = Math.max(0, currentWindowTotal);
        currentWindowDisplayedCount = Math.max(0, currentWindowDisplayedCount);
        matchedCount = Math.max(0, matchedCount);
        driftedCount = Math.max(0, driftedCount);
        missingCount = Math.max(0, missingCount);
        newCurrentWindowCount = Math.max(0, newCurrentWindowCount);
        exportedRequirementKeys = immutableStringList(exportedRequirementKeys);
        currentWindowRequirementKeys = immutableStringList(currentWindowRequirementKeys);
        newCurrentWindowRequirementKeys = immutableStringList(newCurrentWindowRequirementKeys);
        statusCounts = immutableCounts(statusCounts);
        fieldChangeCategoryCounts = immutableCounts(fieldChangeCategoryCounts);
        items = items == null ? List.of() : List.copyOf(items);
        diagnostics = immutableDiagnostics(diagnostics);
        reviewable = reviewable && diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }

    /**
     * Reviews a handoff bundle against the current runtime-binding requirement read model.
     *
     * @param bundle submitted handoff bundle
     * @param currentByRequirementKey current requirement rows keyed by submitted stable key
     * @param currentWindow current same-scope/filter/page requirement window
     * @return review result
     */
    public static VisualRuntimeBindingHandoffReview from(
            VisualRuntimeBindingHandoffBundle bundle,
            Map<String, VisualRuntimeBindingRequirements.RequirementItem> currentByRequirementKey,
            VisualRuntimeBindingRequirements currentWindow) {
        VisualRuntimeBindingHandoffBundle safeBundle = bundle == null
                ? VisualRuntimeBindingHandoffBundle.from(VisualRuntimeBindingRequirements.empty())
                : bundle;
        VisualRuntimeBindingRequirements safeWindow = currentWindow == null
                ? VisualRuntimeBindingRequirements.empty()
                : currentWindow;
        List<String> exportedKeys = requirementKeys(safeBundle);
        Map<String, VisualRuntimeBindingRequirements.RequirementItem> exportedByKey = requirementsByKey(
                safeBundle.requirements());
        Map<String, VisualRuntimeBindingRequirements.RequirementItem> safeCurrent =
                currentByRequirementKey == null ? Map.of() : currentByRequirementKey;
        List<ReviewItem> items = new ArrayList<>();
        for (String key : exportedKeys) {
            VisualRuntimeBindingRequirements.RequirementItem exported = exportedByKey.get(key);
            VisualRuntimeBindingRequirements.RequirementItem current = safeCurrent.get(key);
            items.add(ReviewItem.from(key, exported, current));
        }

        List<String> currentWindowKeys = safeWindow.items().stream()
                .map(VisualRuntimeBindingRequirements.RequirementItem::requirementKey)
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        Set<String> exportedKeySet = new LinkedHashSet<>(exportedKeys);
        List<String> newWindowKeys = currentWindowKeys.stream()
                .filter(key -> !exportedKeySet.contains(key))
                .toList();
        Map<String, Integer> statusCounts = countByStatus(items);
        int matched = statusCounts.getOrDefault(ReviewItem.STATUS_CURRENT, 0);
        int drifted = statusCounts.getOrDefault(ReviewItem.STATUS_DRIFTED, 0);
        int missing = statusCounts.getOrDefault(ReviewItem.STATUS_MISSING, 0);
        String state = reviewState(exportedKeys.size(), drifted, missing, newWindowKeys.size());
        String level = reviewLevel(state);
        return new VisualRuntimeBindingHandoffReview(
                SCHEMA_VERSION,
                Instant.now(),
                true,
                state,
                level,
                reviewMessage(state, exportedKeys.size(), matched, drifted, missing, newWindowKeys.size()),
                safeBundle.schemaVersion(),
                safeBundle.exportedAt(),
                safeBundle.sourceIndexGeneratedAt(),
                safeBundle.scope(),
                safeBundle.filter(),
                exportedKeys.size(),
                safeWindow.total(),
                safeWindow.displayedCount(),
                safeWindow.hasMore(),
                matched,
                drifted,
                missing,
                newWindowKeys.size(),
                exportedKeys,
                currentWindowKeys,
                newWindowKeys,
                statusCounts,
                countFieldChangeCategories(items),
                items,
                List.of()
        );
    }

    /**
     * Creates an invalid-bundle review.
     *
     * @param bundle submitted bundle if it was parseable
     * @param diagnostics diagnostics explaining why review did not run
     * @return invalid review result
     */
    public static VisualRuntimeBindingHandoffReview rejected(VisualRuntimeBindingHandoffBundle bundle,
                                                             List<VisualDiagnostic> diagnostics) {
        VisualRuntimeBindingHandoffBundle safeBundle = bundle == null
                ? VisualRuntimeBindingHandoffBundle.from(VisualRuntimeBindingRequirements.empty())
                : bundle;
        List<String> keys = requirementKeys(safeBundle);
        return new VisualRuntimeBindingHandoffReview(
                SCHEMA_VERSION,
                Instant.now(),
                false,
                STATE_INVALID_BUNDLE,
                "error",
                "Runtime binding handoff bundle could not be reviewed.",
                safeBundle.schemaVersion(),
                safeBundle.exportedAt(),
                safeBundle.sourceIndexGeneratedAt(),
                safeBundle.scope(),
                safeBundle.filter(),
                keys.size(),
                0,
                0,
                false,
                0,
                0,
                0,
                0,
                keys,
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                diagnostics
        );
    }

    /**
     * Extracts stable requirement keys from a bundle.
     *
     * @param bundle handoff bundle
     * @return normalized stable keys
     */
    public static List<String> requirementKeys(VisualRuntimeBindingHandoffBundle bundle) {
        if (bundle == null) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (bundle.requirementKeys() != null) {
            bundle.requirementKeys().stream()
                    .filter(key -> key != null && !key.isBlank())
                    .map(String::trim)
                    .forEach(keys::add);
        }
        if (bundle.requirements() != null) {
            bundle.requirements().stream()
                    .filter(item -> item != null && item.requirementKey() != null && !item.requirementKey().isBlank())
                    .map(item -> item.requirementKey().trim())
                    .forEach(keys::add);
        }
        return List.copyOf(keys);
    }

    /**
     * One exported requirement reconciled against the current read model.
     *
     * @param requirementKey stable key being reviewed
     * @param status current, drifted, or missing
     * @param level UI/control-plane severity
     * @param message human-readable row summary
     * @param changedFields fields that differ between exported and current rows
     * @param fieldChanges structured field changes with exported/current values
     * @param exportedRequirement exported requirement row
     * @param currentRequirement current requirement row when still present
     */
    public record ReviewItem(
            String requirementKey,
            String status,
            String level,
            String message,
            List<String> changedFields,
            List<FieldChange> fieldChanges,
            VisualRuntimeBindingRequirements.RequirementItem exportedRequirement,
            VisualRuntimeBindingRequirements.RequirementItem currentRequirement
    ) {
        public static final String STATUS_CURRENT = "current";
        public static final String STATUS_DRIFTED = "drifted";
        public static final String STATUS_MISSING = "missing";

        public ReviewItem {
            requirementKey = requirementKey == null ? "" : requirementKey.trim();
            status = normalizeState(status);
            level = normalizeLevel(level);
            message = message == null ? "" : message;
            changedFields = immutableStringList(changedFields);
            fieldChanges = fieldChanges == null ? List.of() : List.copyOf(fieldChanges);
        }

        static ReviewItem from(String requirementKey,
                               VisualRuntimeBindingRequirements.RequirementItem exported,
                               VisualRuntimeBindingRequirements.RequirementItem current) {
            if (current == null) {
                return new ReviewItem(
                        requirementKey,
                        STATUS_MISSING,
                        "warning",
                        "Exported requirement key is no longer present in the current runtime-binding read model.",
                        List.of(),
                        List.of(),
                        exported,
                        null
                );
            }
            List<FieldChange> fieldChanges = VisualRuntimeBindingHandoffReview.fieldChanges(exported, current);
            List<String> changedFields = fieldChanges.stream()
                    .map(FieldChange::field)
                    .toList();
            if (!changedFields.isEmpty()) {
                return new ReviewItem(
                        requirementKey,
                        STATUS_DRIFTED,
                        "warning",
                        "Requirement still exists but changed fields: %s.".formatted(String.join(", ", changedFields)),
                        changedFields,
                        fieldChanges,
                        exported,
                        current
                );
            }
            return new ReviewItem(
                    requirementKey,
                    STATUS_CURRENT,
                    "success",
                    "Requirement key is still current.",
                    List.of(),
                    List.of(),
                    exported,
                    current
            );
        }
    }

    /**
     * One changed field in a drifted handoff review row.
     *
     * @param field changed field name
     * @param category routing category for the changed field
     * @param exportedValue value captured in the submitted handoff bundle
     * @param currentValue value in the current runtime-binding read model
     */
    public record FieldChange(String field, String category, String exportedValue, String currentValue) {
        public FieldChange {
            field = field == null ? "" : field.trim();
            category = category == null || category.isBlank()
                    ? "metadata"
                    : category.trim().toLowerCase().replace('_', '-');
            exportedValue = exportedValue == null ? "" : exportedValue;
            currentValue = currentValue == null ? "" : currentValue;
        }
    }

    private static Map<String, VisualRuntimeBindingRequirements.RequirementItem> requirementsByKey(
            List<VisualRuntimeBindingRequirements.RequirementItem> requirements) {
        Map<String, VisualRuntimeBindingRequirements.RequirementItem> byKey = new LinkedHashMap<>();
        for (VisualRuntimeBindingRequirements.RequirementItem item
                : requirements == null ? List.<VisualRuntimeBindingRequirements.RequirementItem>of() : requirements) {
            if (item != null && !item.requirementKey().isBlank()) {
                byKey.putIfAbsent(item.requirementKey(), item);
            }
        }
        return byKey;
    }

    private static List<FieldChange> fieldChanges(VisualRuntimeBindingRequirements.RequirementItem exported,
                                                  VisualRuntimeBindingRequirements.RequirementItem current) {
        if (exported == null || current == null) {
            return List.of();
        }
        List<FieldChange> changed = new ArrayList<>();
        addChanged(changed, "targetKind", exported.targetKind(), current.targetKind());
        addChanged(changed, "targetId", exported.targetId(), current.targetId());
        addChanged(changed, "targetLabel", exported.targetLabel(), current.targetLabel());
        addChanged(changed, "graphName", exported.graphName(), current.graphName());
        addChanged(changed, "tenantId", exported.tenantId(), current.tenantId());
        addChanged(changed, "namespace", exported.namespace(), current.namespace());
        addChanged(changed, "environment", exported.environment(), current.environment());
        addChanged(changed, "artifactKind", exported.artifactKind(), current.artifactKind());
        addChanged(changed, "updatedAt", exported.updatedAt(), current.updatedAt());
        addChanged(changed, "nodeId", exported.nodeId(), current.nodeId());
        addChanged(changed, "operatorRef", exported.operatorRef(), current.operatorRef());
        addChanged(changed, "readinessState", exported.readinessState(), current.readinessState());
        addChanged(changed, "requirementState", exported.requirementState(), current.requirementState());
        addChanged(changed, "level", exported.level(), current.level());
        addChanged(changed, "sourceKind", exported.sourceKind(), current.sourceKind());
        addChanged(changed, "loweringMode", exported.loweringMode(), current.loweringMode());
        addChanged(changed, "bindingKind", exported.bindingKind(), current.bindingKind());
        addChanged(changed, "bindingTarget", exported.bindingTarget(), current.bindingTarget());
        addChanged(changed, "handoffLane", exported.handoffLane(), current.handoffLane());
        addChanged(changed, "handoffKind", exported.handoffKind(), current.handoffKind());
        addChanged(changed, "handoffTarget", exported.handoffTarget(), current.handoffTarget());
        addChanged(changed, "recommendedAction", exported.recommendedAction(), current.recommendedAction());
        return changed;
    }

    private static void addChanged(List<FieldChange> changed, String field, String exported, String current) {
        if (!String.valueOf(exported).equals(String.valueOf(current))) {
            changed.add(new FieldChange(field, fieldCategory(field), String.valueOf(exported), String.valueOf(current)));
        }
    }

    private static String fieldCategory(String field) {
        return switch (field == null ? "" : field) {
            case "targetKind", "targetId", "nodeId", "operatorRef", "artifactKind" -> "identity";
            case "tenantId", "namespace", "environment" -> "scope";
            case "readinessState", "requirementState", "level" -> "readiness";
            case "sourceKind", "loweringMode", "bindingKind", "bindingTarget",
                    "handoffLane", "handoffKind", "handoffTarget", "recommendedAction" -> "runtime-binding";
            case "graphName", "targetLabel", "updatedAt" -> "asset-metadata";
            default -> "metadata";
        };
    }

    private static Map<String, Integer> countByStatus(List<ReviewItem> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ReviewItem item : items == null ? List.<ReviewItem>of() : items) {
            if (item != null && !item.status().isBlank()) {
                counts.merge(item.status(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, Integer> countFieldChangeCategories(List<ReviewItem> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ReviewItem item : items == null ? List.<ReviewItem>of() : items) {
            if (item == null || item.fieldChanges() == null) {
                continue;
            }
            for (FieldChange change : item.fieldChanges()) {
                if (change != null && !change.category().isBlank()) {
                    counts.merge(change.category(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private static String reviewState(int exportedCount, int driftedCount, int missingCount, int newWindowCount) {
        if (exportedCount == 0) {
            return STATE_EMPTY;
        }
        if (driftedCount > 0 || missingCount > 0 || newWindowCount > 0) {
            return STATE_STALE;
        }
        return STATE_CURRENT;
    }

    private static String reviewLevel(String state) {
        if (STATE_CURRENT.equals(state)) {
            return "success";
        }
        if (STATE_INVALID_BUNDLE.equals(state)) {
            return "error";
        }
        return "warning";
    }

    private static String reviewMessage(String state,
                                        int exportedCount,
                                        int matched,
                                        int drifted,
                                        int missing,
                                        int newWindowCount) {
        if (STATE_EMPTY.equals(state)) {
            return "Handoff bundle has no runtime-binding requirement keys to review.";
        }
        if (STATE_CURRENT.equals(state)) {
            return "Handoff bundle is current: %d requirement(s) still match the current read model."
                    .formatted(matched);
        }
        return "Handoff bundle is stale: %d exported, %d current, %d drifted, %d missing, %d new in current window."
                .formatted(exportedCount, matched, drifted, missing, newWindowCount);
    }

    private static String normalizeState(String value) {
        return value == null || value.isBlank()
                ? STATE_INVALID_BUNDLE
                : value.trim().toLowerCase().replace('_', '-');
    }

    private static String normalizeLevel(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return Set.of("error", "warning", "info", "success").contains(normalized) ? normalized : "info";
    }

    private static List<String> immutableStringList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    private static List<VisualDiagnostic> immutableDiagnostics(List<VisualDiagnostic> diagnostics) {
        if (diagnostics == null) {
            return List.of();
        }
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic != null)
                .toList();
    }

    private static Map<String, Integer> immutableCounts(Map<String, Integer> counts) {
        return counts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }
}
