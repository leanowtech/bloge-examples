package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Machine-readable impact summary for operator-library validation and mutation preflight.
 *
 * @param schemaVersion impact review schema version
 * @param diagnosticCount diagnostic count
 * @param errorCount blocking diagnostic count
 * @param warningCount warning diagnostic count
 * @param infoCount informational diagnostic count
 * @param draftIds affected stored draft ids
 * @param publicationIds affected immutable publication ids
 * @param operatorRefs affected operator refs
 * @param draftTargets affected stored draft node targets
 * @param changeRiskCounts operator definition change-risk counts grouped by risk category
 * @param codeCounts diagnostic counts grouped by code
 */
public record OperatorLibraryImpactReview(
        String schemaVersion,
        int diagnosticCount,
        int errorCount,
        int warningCount,
        int infoCount,
        List<String> draftIds,
        List<String> publicationIds,
        List<String> operatorRefs,
        List<DraftTarget> draftTargets,
        List<ChangeRiskCount> changeRiskCounts,
        List<DiagnosticCodeCount> codeCounts
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorLibraryImpact.v1";

    /**
     * Creates an impact review.
     */
    public OperatorLibraryImpactReview {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        draftIds = sortedCopy(draftIds);
        publicationIds = sortedCopy(publicationIds);
        operatorRefs = sortedCopy(operatorRefs);
        draftTargets = draftTargets == null ? List.of() : draftTargets.stream()
                .filter(target -> target != null && !target.draftId().isBlank() && target.nodeIndex() >= 0)
                .distinct()
                .sorted(Comparator.comparing(DraftTarget::draftId).thenComparingInt(DraftTarget::nodeIndex))
                .toList();
        changeRiskCounts = changeRiskCounts == null ? List.of() : changeRiskCounts.stream()
                .filter(entry -> entry != null && !entry.risk().isBlank() && entry.count() > 0)
                .sorted(Comparator.comparingInt((ChangeRiskCount entry) -> riskRank(entry.risk())).reversed()
                        .thenComparing(ChangeRiskCount::risk))
                .toList();
        codeCounts = codeCounts == null ? List.of() : List.copyOf(codeCounts);
    }

    /**
     * @return empty review
     */
    public static OperatorLibraryImpactReview empty() {
        return fromDiagnostics(List.of(), List.of());
    }

    /**
     * @param diagnostics validation diagnostics
     * @param operatorRefs affected operator refs derived from catalog state
     * @return impact summary
     */
    public static OperatorLibraryImpactReview fromDiagnostics(List<VisualDiagnostic> diagnostics,
                                                              Iterable<String> operatorRefs) {
        List<VisualDiagnostic> normalized = diagnostics == null ? List.of() : diagnostics;
        Set<String> draftIds = new LinkedHashSet<>();
        Set<String> publicationIds = new LinkedHashSet<>();
        Set<String> refs = new LinkedHashSet<>();
        Set<DraftTarget> draftTargets = new LinkedHashSet<>();
        Map<String, Integer> changeRisks = new LinkedHashMap<>();
        if (operatorRefs != null) {
            for (String operatorRef : operatorRefs) {
                if (operatorRef != null && !operatorRef.isBlank()) {
                    refs.add(operatorRef);
                }
            }
        }
        Map<String, DiagnosticCodeCountBuilder> counts = new LinkedHashMap<>();
        int errors = 0;
        int warnings = 0;
        for (VisualDiagnostic diagnostic : normalized) {
            if (diagnostic == null) {
                continue;
            }
            String level = diagnostic.level() == null ? "INFO" : diagnostic.level().toUpperCase();
            if ("ERROR".equals(level)) {
                errors++;
            } else if ("WARNING".equals(level)) {
                warnings++;
            }
            String code = diagnostic.code() == null || diagnostic.code().isBlank()
                    ? "visual.info"
                    : diagnostic.code();
            counts.computeIfAbsent(code, key -> new DiagnosticCodeCountBuilder(code))
                    .increment(level);
            changeRisk(diagnostic).ifPresent(risk -> changeRisks.merge(risk, 1, Integer::sum));
            List<String> target = targetSegments(diagnostic.target());
            if (target.size() > 1 && "drafts".equals(target.getFirst())) {
                draftIds.add(target.get(1));
                if (target.size() > 3 && "nodes".equals(target.get(2))) {
                    parseNonNegativeInt(target.get(3))
                            .ifPresent(nodeIndex -> draftTargets.add(new DraftTarget(target.get(1), nodeIndex)));
                }
            }
            if (target.size() > 1 && "publications".equals(target.getFirst())) {
                publicationIds.add(target.get(1));
            }
        }
        List<DiagnosticCodeCount> codeCounts = counts.values().stream()
                .map(DiagnosticCodeCountBuilder::build)
                .sorted(Comparator.comparingInt(DiagnosticCodeCount::count).reversed()
                        .thenComparing(DiagnosticCodeCount::code))
                .toList();
        List<ChangeRiskCount> changeRiskCounts = changeRisks.entrySet().stream()
                .map(entry -> new ChangeRiskCount(entry.getKey(), entry.getValue()))
                .toList();
        int total = normalized.size();
        return new OperatorLibraryImpactReview(
                SCHEMA_VERSION,
                total,
                errors,
                warnings,
                Math.max(0, total - errors - warnings),
                List.copyOf(draftIds),
                List.copyOf(publicationIds),
                List.copyOf(refs),
                List.copyOf(draftTargets),
                changeRiskCounts,
                codeCounts
        );
    }

    private static java.util.Optional<String> changeRisk(VisualDiagnostic diagnostic) {
        Object raw = diagnostic.metadata().get("changeRisk");
        if (!(raw instanceof String risk) || risk.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(risk.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static List<String> sortedCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static List<String> targetSegments(String target) {
        if (target == null || target.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(target.split("/"))
                .filter(segment -> !segment.isBlank())
                .map(OperatorLibraryImpactReview::jsonPointerUnescape)
                .toList();
    }

    private static String jsonPointerUnescape(String value) {
        return value.replace("~1", "/").replace("~0", "~");
    }

    private static java.util.Optional<Integer> parseNonNegativeInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 ? java.util.Optional.of(parsed) : java.util.Optional.empty();
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Affected stored draft node target.
     *
     * @param draftId stored draft id
     * @param nodeIndex node index in the stored draft
     */
    public record DraftTarget(String draftId, int nodeIndex) {
        public DraftTarget {
            draftId = draftId == null ? "" : draftId;
            nodeIndex = Math.max(-1, nodeIndex);
        }
    }

    /**
     * Count of operator-definition drift diagnostics grouped by change-risk category.
     *
     * @param risk risk category such as BREAKING_SCHEMA or GOVERNANCE
     * @param count occurrence count
     */
    public record ChangeRiskCount(String risk, int count) {
        public ChangeRiskCount {
            risk = risk == null || risk.isBlank()
                    ? OperatorDefinitionChangeSummary.RISK_METADATA
                    : risk.trim().toUpperCase(java.util.Locale.ROOT);
            count = Math.max(0, count);
        }
    }

    /**
     * Diagnostic count by code.
     *
     * @param code diagnostic code
     * @param level highest diagnostic level for this code
     * @param count occurrence count
     */
    public record DiagnosticCodeCount(String code, String level, int count) {
        public DiagnosticCodeCount {
            code = code == null || code.isBlank() ? "visual.info" : code;
            level = level == null || level.isBlank() ? "INFO" : level.toUpperCase();
            count = Math.max(0, count);
        }
    }

    private static final class DiagnosticCodeCountBuilder {
        private final String code;
        private String level = "INFO";
        private int count;

        private DiagnosticCodeCountBuilder(String code) {
            this.code = code;
        }

        private void increment(String candidateLevel) {
            count++;
            if (levelRank(candidateLevel) > levelRank(level)) {
                level = candidateLevel;
            }
        }

        private DiagnosticCodeCount build() {
            return new DiagnosticCodeCount(code, level, count);
        }

        private static int levelRank(String value) {
            return switch (value == null ? "" : value.toUpperCase()) {
                case "ERROR" -> 3;
                case "WARNING" -> 2;
                case "INFO" -> 1;
                default -> 0;
            };
        }
    }

    private static int riskRank(String risk) {
        return OperatorDefinitionChangeSummary.riskRank(risk);
    }
}
