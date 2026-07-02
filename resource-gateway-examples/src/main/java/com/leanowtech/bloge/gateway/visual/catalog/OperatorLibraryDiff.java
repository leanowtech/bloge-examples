package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Machine-readable diff between two immutable operator-library revision snapshots.
 *
 * @param schemaVersion diff schema version
 * @param libraryId operator library id
 * @param baseRevision base revision number
 * @param targetRevision target revision number
 * @param baseAction registry action that produced the base revision
 * @param targetAction registry action that produced the target revision
 * @param baseVersion base library semantic version
 * @param targetVersion target library semantic version
 * @param changed whether any library or operator surface changed
 * @param changeRisk highest-risk category in the diff
 * @param changeCategories all risk categories present in the diff
 * @param changeSummary concise summary for review surfaces
 * @param addedOperatorCount number of added operatorRefs
 * @param removedOperatorCount number of removed operatorRefs
 * @param changedOperatorCount number of changed operatorRefs
 * @param libraryChanges library-level changes
 * @param operatorChanges operator-level changes
 */
public record OperatorLibraryDiff(
        String schemaVersion,
        String libraryId,
        long baseRevision,
        long targetRevision,
        String baseAction,
        String targetAction,
        String baseVersion,
        String targetVersion,
        boolean changed,
        String changeRisk,
        List<String> changeCategories,
        String changeSummary,
        int addedOperatorCount,
        int removedOperatorCount,
        int changedOperatorCount,
        List<LibraryChange> libraryChanges,
        List<OperatorChange> operatorChanges
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorLibraryDiff.v1";

    /**
     * Creates a normalized diff.
     */
    public OperatorLibraryDiff {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        libraryId = libraryId == null ? "" : libraryId;
        baseAction = baseAction == null ? "" : baseAction;
        targetAction = targetAction == null ? "" : targetAction;
        baseVersion = baseVersion == null ? "" : baseVersion;
        targetVersion = targetVersion == null ? "" : targetVersion;
        changeCategories = changeCategories == null ? List.of() : List.copyOf(changeCategories);
        changeRisk = changeRisk == null || changeRisk.isBlank()
                ? OperatorDefinitionChangeSummary.RISK_METADATA
                : changeRisk;
        changeSummary = changeSummary == null ? "" : changeSummary;
        addedOperatorCount = Math.max(0, addedOperatorCount);
        removedOperatorCount = Math.max(0, removedOperatorCount);
        changedOperatorCount = Math.max(0, changedOperatorCount);
        libraryChanges = libraryChanges == null ? List.of() : List.copyOf(libraryChanges);
        operatorChanges = operatorChanges == null ? List.of() : List.copyOf(operatorChanges);
    }

    /**
     * @param base base immutable revision snapshot
     * @param target target immutable revision snapshot
     * @return classified diff
     */
    public static OperatorLibraryDiff between(OperatorLibraryRevision base, OperatorLibraryRevision target) {
        OperatorLibrary baseLibrary = base == null ? null : base.library();
        OperatorLibrary targetLibrary = target == null ? null : target.library();
        List<LibraryChange> libraryChanges = libraryChanges(base, target, baseLibrary, targetLibrary);
        List<OperatorChange> operatorChanges = operatorChanges(baseLibrary, targetLibrary);
        ChangeClassification classification = classify(libraryChanges, operatorChanges);
        long baseRevision = base == null ? 0L : base.revision();
        long targetRevision = target == null ? 0L : target.revision();
        return new OperatorLibraryDiff(
                SCHEMA_VERSION,
                libraryId(base, target, baseLibrary, targetLibrary),
                baseRevision,
                targetRevision,
                base == null ? "" : base.action(),
                target == null ? "" : target.action(),
                baseLibrary == null ? "" : baseLibrary.version(),
                targetLibrary == null ? "" : targetLibrary.version(),
                !libraryChanges.isEmpty() || !operatorChanges.isEmpty(),
                classification.risk(),
                classification.categories(),
                classification.summary(),
                (int) operatorChanges.stream().filter(change -> "ADDED".equals(change.changeKind())).count(),
                (int) operatorChanges.stream().filter(change -> "REMOVED".equals(change.changeKind())).count(),
                (int) operatorChanges.stream().filter(change -> "CHANGED".equals(change.changeKind())).count(),
                libraryChanges,
                operatorChanges
        );
    }

    private static String libraryId(OperatorLibraryRevision base,
                                    OperatorLibraryRevision target,
                                    OperatorLibrary baseLibrary,
                                    OperatorLibrary targetLibrary) {
        if (target != null && !target.libraryId().isBlank()) {
            return target.libraryId();
        }
        if (base != null && !base.libraryId().isBlank()) {
            return base.libraryId();
        }
        if (targetLibrary != null) {
            return targetLibrary.libraryId();
        }
        return baseLibrary == null ? "" : baseLibrary.libraryId();
    }

    private static List<LibraryChange> libraryChanges(OperatorLibraryRevision base,
                                                      OperatorLibraryRevision target,
                                                      OperatorLibrary baseLibrary,
                                                      OperatorLibrary targetLibrary) {
        List<LibraryChange> changes = new ArrayList<>();
        addLibraryChange(changes, "revisionAction",
                base == null ? "" : base.action(),
                target == null ? "" : target.action(),
                OperatorDefinitionChangeSummary.RISK_METADATA,
                "revision action");
        if (baseLibrary == null && targetLibrary == null) {
            return List.copyOf(changes);
        }
        if (baseLibrary == null || targetLibrary == null) {
            changes.add(new LibraryChange("librarySnapshot",
                    baseLibrary == null ? "" : baseLibrary.libraryId(),
                    targetLibrary == null ? "" : targetLibrary.libraryId(),
                    baseLibrary == null
                            ? OperatorDefinitionChangeSummary.RISK_COMPATIBLE_SCHEMA
                            : OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA,
                    baseLibrary == null ? "library snapshot added" : "library snapshot removed"));
            return List.copyOf(changes);
        }
        addLibraryChange(changes, "version", baseLibrary.version(), targetLibrary.version(),
                OperatorDefinitionChangeSummary.RISK_METADATA, "library version");
        addLibraryChange(changes, "status", baseLibrary.status(), targetLibrary.status(),
                OperatorDefinitionChangeSummary.RISK_GOVERNANCE, "library lifecycle status");
        addLibraryChange(changes, "displayName", baseLibrary.displayName(), targetLibrary.displayName(),
                OperatorDefinitionChangeSummary.RISK_METADATA, "library display name");
        addLibraryChange(changes, "owner", baseLibrary.owner(), targetLibrary.owner(),
                OperatorDefinitionChangeSummary.RISK_METADATA, "library owner");
        return List.copyOf(changes);
    }

    private static void addLibraryChange(List<LibraryChange> changes,
                                         String field,
                                         String baseValue,
                                         String targetValue,
                                         String risk,
                                         String label) {
        if (!Objects.equals(normalize(baseValue), normalize(targetValue))) {
            changes.add(new LibraryChange(field, baseValue, targetValue, risk, label + " changed"));
        }
    }

    private static List<OperatorChange> operatorChanges(OperatorLibrary baseLibrary, OperatorLibrary targetLibrary) {
        Map<String, OperatorDefinition> baseByRef = operatorsByRef(baseLibrary);
        Map<String, OperatorDefinition> targetByRef = operatorsByRef(targetLibrary);
        Set<String> refs = new LinkedHashSet<>();
        refs.addAll(baseByRef.keySet());
        refs.addAll(targetByRef.keySet());
        List<OperatorChange> changes = new ArrayList<>();
        for (String operatorRef : refs) {
            OperatorDefinition base = baseByRef.get(operatorRef);
            OperatorDefinition target = targetByRef.get(operatorRef);
            if (base == null) {
                changes.add(new OperatorChange(operatorRef, "ADDED",
                        OperatorDefinitionChangeSummary.RISK_COMPATIBLE_SCHEMA,
                        List.of(OperatorDefinitionChangeSummary.RISK_COMPATIBLE_SCHEMA),
                        "operatorRef '" + operatorRef + "' added",
                        "",
                        target == null ? "" : target.fingerprint(),
                        "",
                        target == null ? "" : target.operatorVersion()));
                continue;
            }
            if (target == null) {
                changes.add(new OperatorChange(operatorRef, "REMOVED",
                        OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA,
                        List.of(OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA),
                        "operatorRef '" + operatorRef + "' removed",
                        base.fingerprint(),
                        "",
                        base.operatorVersion(),
                        ""));
                continue;
            }
            if (Objects.equals(base.fingerprint(), target.fingerprint())) {
                continue;
            }
            OperatorDefinitionChangeSummary.ChangeReport report = OperatorDefinitionChangeSummary.analyze(base, target);
            changes.add(new OperatorChange(operatorRef, "CHANGED",
                    report.risk(),
                    report.categories(),
                    report.summary(),
                    base.fingerprint(),
                    target.fingerprint(),
                    base.operatorVersion(),
                    target.operatorVersion()));
        }
        return List.copyOf(changes);
    }

    private static Map<String, OperatorDefinition> operatorsByRef(OperatorLibrary library) {
        if (library == null || library.operators() == null) {
            return Map.of();
        }
        Map<String, OperatorDefinition> byRef = new LinkedHashMap<>();
        for (OperatorDefinition operator : library.operators()) {
            if (operator != null && !operator.operatorRef().isBlank()) {
                byRef.putIfAbsent(operator.operatorRef(), operator);
            }
        }
        return Collections.unmodifiableMap(byRef);
    }

    private static ChangeClassification classify(List<LibraryChange> libraryChanges,
                                                 List<OperatorChange> operatorChanges) {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        List<String> summaries = new ArrayList<>();
        for (LibraryChange change : libraryChanges) {
            categories.add(change.risk());
            summaries.add(change.summary());
        }
        for (OperatorChange change : operatorChanges) {
            categories.addAll(change.categories());
            summaries.add(change.summary());
        }
        List<String> sortedCategories = categories.stream()
                .filter(category -> category != null && !category.isBlank())
                .sorted((left, right) -> Integer.compare(
                        OperatorDefinitionChangeSummary.riskRank(right),
                        OperatorDefinitionChangeSummary.riskRank(left)))
                .toList();
        String risk = sortedCategories.isEmpty()
                ? OperatorDefinitionChangeSummary.RISK_METADATA
                : sortedCategories.getFirst();
        return new ChangeClassification(risk, sortedCategories, summarize(summaries));
    }

    private static String summarize(List<String> summaries) {
        List<String> visibleSummaries = summaries.stream()
                .filter(summary -> summary != null && !summary.isBlank())
                .toList();
        if (visibleSummaries.isEmpty()) {
            return "No library or operator surface changes.";
        }
        int visible = Math.min(5, visibleSummaries.size());
        String summary = String.join("; ", visibleSummaries.subList(0, visible));
        int remaining = visibleSummaries.size() - visible;
        return remaining > 0 ? summary + "; +" + remaining + " more" : summary;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record ChangeClassification(String risk, List<String> categories, String summary) {
    }

    /**
     * Library-level revision change.
     *
     * @param field library or revision field name
     * @param baseValue value in the base revision
     * @param targetValue value in the target revision
     * @param risk risk category
     * @param summary concise change summary
     */
    public record LibraryChange(
            String field,
            String baseValue,
            String targetValue,
            String risk,
            String summary
    ) {
        public LibraryChange {
            field = field == null ? "" : field;
            baseValue = baseValue == null ? "" : baseValue;
            targetValue = targetValue == null ? "" : targetValue;
            risk = risk == null || risk.isBlank() ? OperatorDefinitionChangeSummary.RISK_METADATA : risk;
            summary = summary == null ? "" : summary;
        }
    }

    /**
     * Operator-level revision change.
     *
     * @param operatorRef operator ref
     * @param changeKind ADDED, REMOVED, or CHANGED
     * @param risk highest-risk category
     * @param categories all categories in this operator change
     * @param summary concise change summary
     * @param baseFingerprint fingerprint in the base revision
     * @param targetFingerprint fingerprint in the target revision
     * @param baseOperatorVersion operator version in the base revision
     * @param targetOperatorVersion operator version in the target revision
     */
    public record OperatorChange(
            String operatorRef,
            String changeKind,
            String risk,
            List<String> categories,
            String summary,
            String baseFingerprint,
            String targetFingerprint,
            String baseOperatorVersion,
            String targetOperatorVersion
    ) {
        public OperatorChange {
            operatorRef = operatorRef == null ? "" : operatorRef;
            changeKind = changeKind == null || changeKind.isBlank() ? "CHANGED" : changeKind;
            risk = risk == null || risk.isBlank() ? OperatorDefinitionChangeSummary.RISK_METADATA : risk;
            categories = categories == null ? List.of() : List.copyOf(categories);
            summary = summary == null ? "" : summary;
            baseFingerprint = baseFingerprint == null ? "" : baseFingerprint;
            targetFingerprint = targetFingerprint == null ? "" : targetFingerprint;
            baseOperatorVersion = baseOperatorVersion == null ? "" : baseOperatorVersion;
            targetOperatorVersion = targetOperatorVersion == null ? "" : targetOperatorVersion;
        }
    }
}
