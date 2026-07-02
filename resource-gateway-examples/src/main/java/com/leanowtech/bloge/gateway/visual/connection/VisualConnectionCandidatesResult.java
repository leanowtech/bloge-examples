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
        totalCandidateCount = Math.max(0, totalCandidateCount);
        acceptedCount = Math.max(0, acceptedCount);
        rejectedCount = Math.max(0, rejectedCount);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        displayedCount = candidates.size();
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
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
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }
}
