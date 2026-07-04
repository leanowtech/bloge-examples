package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema-aware connection target candidates for an interactive canvas drag source.
 *
 * @param schemaVersion result contract version
 * @param source normalized source endpoint
 * @param kind normalized edge kind used for candidate checks
 * @param offset zero-based offset applied after accepted/rejected filtering
 * @param totalCandidateCount enumerated targets after request query/status filtering and before accepted/rejected filtering
 * @param unfilteredCandidateCount enumerated targets before request query/status filtering
 * @param statusCounts ready/blocked/wired target counts after query filtering and before status filtering
 * @param facetCounts candidate facet counts after query/status filtering and before paging
 * @param acceptedCount accepted target count after request query/status filtering and before display limiting
 * @param rejectedCount rejected target count after request query/status filtering and before display limiting
 * @param displayedCount returned candidate row count
 * @param truncated true when more visible rows existed after the returned window
 * @param candidates returned candidate rows
 * @param diagnostics request-level diagnostics that prevented candidate enumeration
 */
public record VisualConnectionCandidatesResult(
        String schemaVersion,
        GraphDraft.Endpoint source,
        String kind,
        int offset,
        int totalCandidateCount,
        int unfilteredCandidateCount,
        Map<String, Integer> statusCounts,
        Map<String, Map<String, Integer>> facetCounts,
        int acceptedCount,
        int rejectedCount,
        int displayedCount,
        boolean truncated,
        List<ConnectionCandidate> candidates,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualConnectionCandidates.v1";

    /**
     * Creates a candidate discovery result.
     */
    public VisualConnectionCandidatesResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        source = source == null ? GraphDraft.Endpoint.empty() : source;
        kind = kind == null ? "data" : kind;
        offset = Math.max(0, offset);
        totalCandidateCount = Math.max(0, totalCandidateCount);
        unfilteredCandidateCount = Math.max(totalCandidateCount, unfilteredCandidateCount);
        statusCounts = normalizeStatusCounts(statusCounts);
        facetCounts = normalizeFacetCounts(facetCounts);
        acceptedCount = Math.max(0, acceptedCount);
        rejectedCount = Math.max(0, rejectedCount);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        displayedCount = candidates.size();
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * Backward-compatible constructor for callers created before offset was first-class.
     */
    public VisualConnectionCandidatesResult(String schemaVersion,
                                            GraphDraft.Endpoint source,
                                            String kind,
                                            int totalCandidateCount,
                                            int acceptedCount,
                                            int rejectedCount,
                                            int displayedCount,
                                            boolean truncated,
                                            List<ConnectionCandidate> candidates,
                                            List<VisualDiagnostic> diagnostics) {
        this(schemaVersion, source, kind, 0, totalCandidateCount, totalCandidateCount,
                Map.of(), Map.of(), acceptedCount, rejectedCount, displayedCount,
                truncated, candidates, diagnostics);
    }

    /**
     * Backward-compatible constructor for callers created before global query filtering existed.
     */
    public VisualConnectionCandidatesResult(String schemaVersion,
                                            GraphDraft.Endpoint source,
                                            String kind,
                                            int offset,
                                            int totalCandidateCount,
                                            int acceptedCount,
                                            int rejectedCount,
                                            int displayedCount,
                                            boolean truncated,
                                            List<ConnectionCandidate> candidates,
                                            List<VisualDiagnostic> diagnostics) {
        this(schemaVersion, source, kind, offset, totalCandidateCount, totalCandidateCount,
                Map.of(), Map.of(), acceptedCount, rejectedCount, displayedCount, truncated, candidates, diagnostics);
    }

    /**
     * Backward-compatible constructor for callers created before facet counts existed.
     */
    public VisualConnectionCandidatesResult(String schemaVersion,
                                            GraphDraft.Endpoint source,
                                            String kind,
                                            int offset,
                                            int totalCandidateCount,
                                            int unfilteredCandidateCount,
                                            Map<String, Integer> statusCounts,
                                            int acceptedCount,
                                            int rejectedCount,
                                            int displayedCount,
                                            boolean truncated,
                                            List<ConnectionCandidate> candidates,
                                            List<VisualDiagnostic> diagnostics) {
        this(schemaVersion, source, kind, offset, totalCandidateCount, unfilteredCandidateCount,
                statusCounts, Map.of(), acceptedCount, rejectedCount, displayedCount, truncated, candidates, diagnostics);
    }

    /**
     * Backward-compatible constructor for callers created before status counts existed.
     */
    public VisualConnectionCandidatesResult(String schemaVersion,
                                            GraphDraft.Endpoint source,
                                            String kind,
                                            int offset,
                                            int totalCandidateCount,
                                            int unfilteredCandidateCount,
                                            int acceptedCount,
                                            int rejectedCount,
                                            int displayedCount,
                                            boolean truncated,
                                            List<ConnectionCandidate> candidates,
                                            List<VisualDiagnostic> diagnostics) {
        this(schemaVersion, source, kind, offset, totalCandidateCount, unfilteredCandidateCount,
                Map.of(), Map.of(), acceptedCount, rejectedCount, displayedCount, truncated, candidates, diagnostics);
    }

    private static Map<String, Integer> normalizeStatusCounts(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        addStatusCount(counts, source, "ready");
        addStatusCount(counts, source, "blocked");
        addStatusCount(counts, source, "wired");
        return Collections.unmodifiableMap(counts);
    }

    private static void addStatusCount(Map<String, Integer> counts, Map<String, Integer> source, String key) {
        counts.put(key, Math.max(0, source.getOrDefault(key, 0)));
    }

    private static Map<String, Map<String, Integer>> normalizeFacetCounts(
            Map<String, Map<String, Integer>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        source.forEach((facet, bucket) -> {
            String facetKey = facet == null ? "" : facet.trim();
            Map<String, Integer> normalizedBucket = normalizeCountBucket(bucket);
            if (!facetKey.isBlank() && !normalizedBucket.isEmpty()) {
                counts.put(facetKey, normalizedBucket);
            }
        });
        return counts.isEmpty() ? Map.of() : Collections.unmodifiableMap(counts);
    }

    private static Map<String, Integer> normalizeCountBucket(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        source.forEach((key, count) -> {
            String normalizedKey = key == null ? "" : key.trim();
            int normalizedCount = Math.max(0, count == null ? 0 : count);
            if (!normalizedKey.isBlank() && normalizedCount > 0) {
                counts.put(normalizedKey, normalizedCount);
            }
        });
        return counts.isEmpty() ? Map.of() : Collections.unmodifiableMap(counts);
    }

    /**
     * One target endpoint candidate and the authoritative preflight decision for it.
     *
     * @param targetNodeId target node id
     * @param targetNodeLabel target node display label
     * @param targetOperatorRef target node operator reference
     * @param targetSurface target surface, such as input or config
     * @param target normalized target endpoint
     * @param accepted true when the connection can be applied
     * @param targetStatus target status, one of ready, blocked, or wired
     * @param bindingKey storage key for data/input bindings when applicable
     * @param summary machine-readable check summary reused from the connection preflight contract
     * @param explanation schema and replacement explanation for product UI and external control planes
     * @param diagnostics target-scoped diagnostics explaining rejection or warnings
     */
    public record ConnectionCandidate(
            String targetNodeId,
            String targetNodeLabel,
            String targetOperatorRef,
            String targetSurface,
            GraphDraft.Endpoint target,
            boolean accepted,
            String targetStatus,
            String bindingKey,
            VisualConnectionCheckResult.VisualConnectionCheckSummary summary,
            ConnectionCandidateExplanation explanation,
            List<VisualDiagnostic> diagnostics
    ) {
        /**
         * Creates one candidate row.
         */
        public ConnectionCandidate {
            targetNodeId = targetNodeId == null ? "" : targetNodeId;
            targetNodeLabel = targetNodeLabel == null ? "" : targetNodeLabel;
            targetOperatorRef = targetOperatorRef == null ? "" : targetOperatorRef;
            targetSurface = targetSurface == null || targetSurface.isBlank() ? "input" : targetSurface;
            target = target == null ? GraphDraft.Endpoint.empty() : target;
            targetStatus = normalizeTargetStatus(targetStatus, accepted);
            bindingKey = bindingKey == null ? "" : bindingKey;
            explanation = explanation == null ? ConnectionCandidateExplanation.empty() : explanation;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        /**
         * Backward-compatible constructor for callers created before explanation existed.
         */
        public ConnectionCandidate(String targetNodeId,
                                   String targetNodeLabel,
                                   String targetOperatorRef,
                                   String targetSurface,
                                   GraphDraft.Endpoint target,
                                   boolean accepted,
                                   String bindingKey,
                                   VisualConnectionCheckResult.VisualConnectionCheckSummary summary,
                                   List<VisualDiagnostic> diagnostics) {
            this(targetNodeId, targetNodeLabel, targetOperatorRef, targetSurface, target, accepted, "", bindingKey,
                    summary, ConnectionCandidateExplanation.empty(), diagnostics);
        }

        /**
         * Backward-compatible constructor for callers created before target status existed.
         */
        public ConnectionCandidate(String targetNodeId,
                                   String targetNodeLabel,
                                   String targetOperatorRef,
                                   String targetSurface,
                                   GraphDraft.Endpoint target,
                                   boolean accepted,
                                   String bindingKey,
                                   VisualConnectionCheckResult.VisualConnectionCheckSummary summary,
                                   ConnectionCandidateExplanation explanation,
                                   List<VisualDiagnostic> diagnostics) {
            this(targetNodeId, targetNodeLabel, targetOperatorRef, targetSurface, target, accepted, "", bindingKey,
                    summary, explanation, diagnostics);
        }

        private static String normalizeTargetStatus(String value, boolean accepted) {
            if (value != null && !value.isBlank()) {
                String normalized = value.trim().toLowerCase();
                if ("ready".equals(normalized) || "blocked".equals(normalized) || "wired".equals(normalized)) {
                    return normalized;
                }
            }
            return accepted ? "ready" : "blocked";
        }
    }

    /**
     * Schema-aware explanation for one candidate decision.
     *
     * @param sourceLabel display-safe source endpoint label
     * @param targetLabel display-safe target endpoint label
     * @param sourceSchemaType summarized source schema type
     * @param targetSchemaType summarized target schema type
     * @param sourceSchemaKnown true when a concrete source schema was found
     * @param targetSchemaKnown true when a concrete target schema was found
     * @param decisionSource source of the decision, currently server-validator
     * @param decisionMessage human-readable accepted/rejected explanation
     * @param firstDiagnosticCode first diagnostic code explaining rejection or warning
     * @param replacementSummary short summary of replaced bindings/edges
     * @param replacedBindingCount existing input bindings replaced by this accepted preview
     * @param replacedEdgeCount existing edges replaced by this accepted preview
     * @param targetRuntimeBinding target-node runtime binding impact for this candidate
     */
    public record ConnectionCandidateExplanation(
            String sourceLabel,
            String targetLabel,
            String sourceSchemaType,
            String targetSchemaType,
            boolean sourceSchemaKnown,
            boolean targetSchemaKnown,
            String decisionSource,
            String decisionMessage,
            String firstDiagnosticCode,
            String replacementSummary,
            int replacedBindingCount,
            int replacedEdgeCount,
            ConnectionCandidateRuntimeBindingImpact targetRuntimeBinding
    ) {
        public ConnectionCandidateExplanation(String sourceLabel,
                                              String targetLabel,
                                              String sourceSchemaType,
                                              String targetSchemaType,
                                              boolean sourceSchemaKnown,
                                              boolean targetSchemaKnown,
                                              String decisionSource,
                                              String decisionMessage,
                                              String firstDiagnosticCode,
                                              String replacementSummary,
                                              int replacedBindingCount,
                                              int replacedEdgeCount) {
            this(sourceLabel, targetLabel, sourceSchemaType, targetSchemaType, sourceSchemaKnown, targetSchemaKnown,
                    decisionSource, decisionMessage, firstDiagnosticCode, replacementSummary, replacedBindingCount,
                    replacedEdgeCount, ConnectionCandidateRuntimeBindingImpact.empty());
        }

        /**
         * Creates a candidate explanation.
         */
        public ConnectionCandidateExplanation {
            sourceLabel = sourceLabel == null ? "" : sourceLabel;
            targetLabel = targetLabel == null ? "" : targetLabel;
            sourceSchemaType = sourceSchemaType == null ? "" : sourceSchemaType;
            targetSchemaType = targetSchemaType == null ? "" : targetSchemaType;
            decisionSource = decisionSource == null || decisionSource.isBlank()
                    ? "server-validator"
                    : decisionSource;
            decisionMessage = decisionMessage == null ? "" : decisionMessage;
            firstDiagnosticCode = firstDiagnosticCode == null ? "" : firstDiagnosticCode;
            replacementSummary = replacementSummary == null ? "" : replacementSummary;
            replacedBindingCount = Math.max(0, replacedBindingCount);
            replacedEdgeCount = Math.max(0, replacedEdgeCount);
            targetRuntimeBinding = targetRuntimeBinding == null
                    ? ConnectionCandidateRuntimeBindingImpact.empty()
                    : targetRuntimeBinding;
        }

        public static ConnectionCandidateExplanation empty() {
            return new ConnectionCandidateExplanation("", "", "", "", false, false, "server-validator",
                    "", "", "", 0, 0, ConnectionCandidateRuntimeBindingImpact.empty());
        }
    }

    /**
     * Target-node runtime binding impact for one candidate.
     *
     * @param requirementCount number of target-node requirements
     * @param requirementKeys preview-scoped stable target-node requirement keys
     * @param bindingKindCounts target-node requirement counts by binding kind
     * @param handoffLaneCounts target-node requirement counts by runtime-plane handoff lane
     * @param handoffKindCounts target-node requirement counts by runtime-plane work kind
     * @param handoffTargetCounts target-node requirement counts by runtime-plane routing target
     * @param sourceKindCounts target-node requirement counts by operator source kind
     * @param operatorLibraryIdCounts target-node requirement counts by owner operator library id
     * @param loweringModeCounts target-node requirement counts by lowering mode
     * @param readinessStateCounts target-node requirement counts by node readiness state
     */
    public record ConnectionCandidateRuntimeBindingImpact(
            int requirementCount,
            List<String> requirementKeys,
            Map<String, Integer> bindingKindCounts,
            Map<String, Integer> handoffLaneCounts,
            Map<String, Integer> handoffKindCounts,
            Map<String, Integer> handoffTargetCounts,
            Map<String, Integer> sourceKindCounts,
            Map<String, Integer> operatorLibraryIdCounts,
            Map<String, Integer> loweringModeCounts,
            Map<String, Integer> readinessStateCounts
    ) {
        public ConnectionCandidateRuntimeBindingImpact {
            requirementKeys = requirementKeys == null ? List.of() : List.copyOf(requirementKeys);
            requirementCount = requirementKeys.isEmpty() ? Math.max(0, requirementCount) : requirementKeys.size();
            bindingKindCounts = immutableCounts(bindingKindCounts);
            handoffLaneCounts = immutableCounts(handoffLaneCounts);
            handoffKindCounts = immutableCounts(handoffKindCounts);
            handoffTargetCounts = immutableCounts(handoffTargetCounts);
            sourceKindCounts = immutableCounts(sourceKindCounts);
            operatorLibraryIdCounts = immutableCounts(operatorLibraryIdCounts);
            loweringModeCounts = immutableCounts(loweringModeCounts);
            readinessStateCounts = immutableCounts(readinessStateCounts);
        }

        public static ConnectionCandidateRuntimeBindingImpact empty() {
            return new ConnectionCandidateRuntimeBindingImpact(0, List.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    private static Map<String, Integer> immutableCounts(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            int count = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
            if (!key.isBlank() && count > 0) {
                normalized.put(key, count);
            }
        }
        return Collections.unmodifiableMap(normalized);
    }
}
