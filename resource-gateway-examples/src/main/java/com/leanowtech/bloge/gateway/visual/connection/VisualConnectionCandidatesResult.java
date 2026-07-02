package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.List;

/**
 * Schema-aware connection target candidates for an interactive canvas drag source.
 *
 * @param schemaVersion result contract version
 * @param source normalized source endpoint
 * @param kind normalized edge kind used for candidate checks
 * @param offset zero-based offset applied after accepted/rejected filtering
 * @param totalCandidateCount enumerated targets before accepted/rejected filtering
 * @param acceptedCount accepted target count before display limiting
 * @param rejectedCount rejected target count before display limiting
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
        this(schemaVersion, source, kind, 0, totalCandidateCount, acceptedCount, rejectedCount, displayedCount,
                truncated, candidates, diagnostics);
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
            this(targetNodeId, targetNodeLabel, targetOperatorRef, targetSurface, target, accepted, bindingKey,
                    summary, ConnectionCandidateExplanation.empty(), diagnostics);
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
            int replacedEdgeCount
    ) {
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
        }

        public static ConnectionCandidateExplanation empty() {
            return new ConnectionCandidateExplanation("", "", "", "", false, false, "server-validator",
                    "", "", "", 0, 0);
        }
    }
}
