package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinitionChangeSummary;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Machine-readable impact summary for resource design-contract validation and mutation preflight.
 *
 * @param schemaVersion impact review schema version
 * @param diagnosticCount diagnostic count
 * @param errorCount blocking diagnostic count
 * @param warningCount warning diagnostic count
 * @param infoCount informational diagnostic count
 * @param resourceIds affected resource ids
 * @param operatorRefs affected resource-backed operator refs
 * @param draftIds affected stored draft ids
 * @param publicationIds affected immutable publication ids
 * @param draftTargets affected stored draft node targets
 * @param publicationTargets affected immutable publication node targets
 * @param changeRiskCounts resource-backed operator definition change-risk counts
 * @param codeCounts diagnostic counts grouped by code
 */
public record ResourceDesignContractImpactReview(
        String schemaVersion,
        int diagnosticCount,
        int errorCount,
        int warningCount,
        int infoCount,
        List<String> resourceIds,
        List<String> operatorRefs,
        List<String> draftIds,
        List<String> publicationIds,
        List<DraftTarget> draftTargets,
        List<PublicationTarget> publicationTargets,
        List<ChangeRiskCount> changeRiskCounts,
        List<DiagnosticCodeCount> codeCounts
) {
    public static final String SCHEMA_VERSION = "bloge.resourceDesignContractImpact.v1";

    /**
     * Creates an impact review.
     */
    public ResourceDesignContractImpactReview {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        resourceIds = sortedCopy(resourceIds);
        operatorRefs = sortedCopy(operatorRefs);
        draftIds = sortedCopy(draftIds);
        publicationIds = sortedCopy(publicationIds);
        draftTargets = draftTargets == null ? List.of() : draftTargets.stream()
                .filter(target -> target != null && !target.draftId().isBlank() && target.nodeIndex() >= 0)
                .distinct()
                .sorted(Comparator.comparing(DraftTarget::draftId).thenComparingInt(DraftTarget::nodeIndex))
                .toList();
        publicationTargets = publicationTargets == null ? List.of() : publicationTargets.stream()
                .filter(target -> target != null && !target.publicationId().isBlank() && target.nodeIndex() >= 0)
                .distinct()
                .sorted(Comparator.comparing(PublicationTarget::publicationId)
                        .thenComparingInt(PublicationTarget::nodeIndex))
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
    public static ResourceDesignContractImpactReview empty() {
        return fromDiagnostics(List.of(), "");
    }

    /**
     * @param diagnostics validation diagnostics
     * @param resourceId candidate resource id
     * @return impact summary
     */
    public static ResourceDesignContractImpactReview fromDiagnostics(List<VisualDiagnostic> diagnostics,
                                                                     String resourceId) {
        List<VisualDiagnostic> normalized = diagnostics == null ? List.of() : diagnostics;
        Set<String> resourceIds = new LinkedHashSet<>();
        Set<String> operatorRefs = new LinkedHashSet<>();
        Set<String> draftIds = new LinkedHashSet<>();
        Set<String> publicationIds = new LinkedHashSet<>();
        Set<DraftTarget> draftTargets = new LinkedHashSet<>();
        Set<PublicationTarget> publicationTargets = new LinkedHashSet<>();
        Map<String, Integer> changeRisks = new LinkedHashMap<>();
        Map<String, DiagnosticCodeCountBuilder> counts = new LinkedHashMap<>();
        if (resourceId != null && !resourceId.isBlank()) {
            resourceIds.add(resourceId);
            operatorRefs.add("resource:" + resourceId);
        }
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
            counts.computeIfAbsent(code, DiagnosticCodeCountBuilder::new).increment(level);
            metadataString(diagnostic, "resourceId").ifPresent(resourceIds::add);
            metadataString(diagnostic, "operatorRef").ifPresent(operatorRefs::add);
            metadataString(diagnostic, "changeRisk").ifPresent(risk -> changeRisks.merge(
                    risk.toUpperCase(java.util.Locale.ROOT), 1, Integer::sum));
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
                if (target.size() > 3 && "nodes".equals(target.get(2))) {
                    parseNonNegativeInt(target.get(3)).ifPresent(nodeIndex ->
                            publicationTargets.add(new PublicationTarget(target.get(1), nodeIndex)));
                }
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
        return new ResourceDesignContractImpactReview(
                SCHEMA_VERSION,
                total,
                errors,
                warnings,
                Math.max(0, total - errors - warnings),
                List.copyOf(resourceIds),
                List.copyOf(operatorRefs),
                List.copyOf(draftIds),
                List.copyOf(publicationIds),
                List.copyOf(draftTargets),
                List.copyOf(publicationTargets),
                changeRiskCounts,
                codeCounts
        );
    }

    private static java.util.Optional<String> metadataString(VisualDiagnostic diagnostic, String key) {
        Object value = diagnostic.metadata().get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(text.trim());
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
                .map(ResourceDesignContractImpactReview::jsonPointerUnescape)
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
     * Affected immutable publication node target.
     *
     * @param publicationId immutable publication id
     * @param nodeIndex node index in the frozen publication draft
     */
    public record PublicationTarget(String publicationId, int nodeIndex) {
        public PublicationTarget {
            publicationId = publicationId == null ? "" : publicationId;
            nodeIndex = Math.max(-1, nodeIndex);
        }
    }

    /**
     * Count of resource-backed operator-definition drift diagnostics grouped by change-risk category.
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
        return switch (risk == null ? "" : risk) {
            case OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA -> 6;
            case OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING -> 5;
            case OperatorDefinitionChangeSummary.RISK_GOVERNANCE -> 4;
            case OperatorDefinitionChangeSummary.RISK_POLICY -> 3;
            case OperatorDefinitionChangeSummary.RISK_COMPATIBLE_SCHEMA -> 2;
            case OperatorDefinitionChangeSummary.RISK_METADATA -> 1;
            default -> 0;
        };
    }
}
