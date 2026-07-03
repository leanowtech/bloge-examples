package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request to check whether a proposed canvas connection is schema-compatible.
 *
 * @param draft current graph draft
 * @param source proposed source endpoint
 * @param target proposed target endpoint
 * @param kind edge kind, defaults to data
 * @param condition route condition for control-flow edges
 * @param targetUnionBranch explicit target oneOf/anyOf branch selected by the author
 * @param targetUnionBranches explicit nested target oneOf/anyOf branches keyed by target path
 */
public record VisualConnectionCheckRequest(
        GraphDraft draft,
        GraphDraft.Endpoint source,
        GraphDraft.Endpoint target,
        String kind,
        String condition,
        GraphDraft.UnionBranchSelection targetUnionBranch,
        Map<String, GraphDraft.UnionBranchSelection> targetUnionBranches
) {
    /**
     * Creates a connection check request.
     */
    public VisualConnectionCheckRequest {
        source = source == null ? GraphDraft.Endpoint.empty() : source;
        target = target == null ? GraphDraft.Endpoint.empty() : target;
        kind = kind == null || kind.isBlank() ? "data" : kind;
        condition = condition == null ? "" : condition.trim();
        targetUnionBranch = targetUnionBranch == null
                ? GraphDraft.UnionBranchSelection.empty()
                : targetUnionBranch;
        targetUnionBranches = normalizeUnionBranchSelections(targetUnionBranches);
    }

    /**
     * Backward-compatible constructor for callers that do not supply nested target union branch selections.
     */
    public VisualConnectionCheckRequest(GraphDraft draft,
                                        GraphDraft.Endpoint source,
                                        GraphDraft.Endpoint target,
                                        String kind,
                                        String condition,
                                        GraphDraft.UnionBranchSelection targetUnionBranch) {
        this(draft, source, target, kind, condition, targetUnionBranch, Map.of());
    }

    /**
     * Backward-compatible constructor for callers that do not supply target union branch selection.
     */
    public VisualConnectionCheckRequest(GraphDraft draft,
                                        GraphDraft.Endpoint source,
                                        GraphDraft.Endpoint target,
                                        String kind,
                                        String condition) {
        this(draft, source, target, kind, condition, GraphDraft.UnionBranchSelection.empty());
    }

    /**
     * Backward-compatible constructor for callers that do not supply route conditions.
     */
    public VisualConnectionCheckRequest(GraphDraft draft,
                                        GraphDraft.Endpoint source,
                                        GraphDraft.Endpoint target,
                                        String kind) {
        this(draft, source, target, kind, "");
    }

    static Map<String, GraphDraft.UnionBranchSelection> normalizeUnionBranchSelections(
            Map<String, GraphDraft.UnionBranchSelection> selections) {
        if (selections == null || selections.isEmpty()) {
            return Map.of();
        }
        Map<String, GraphDraft.UnionBranchSelection> normalized = new LinkedHashMap<>();
        selections.forEach((path, selection) -> {
            GraphDraft.UnionBranchSelection value = selection == null
                    ? GraphDraft.UnionBranchSelection.empty()
                    : selection;
            if (value.selected()) {
                normalized.put(path == null ? "" : path.trim(), value);
            }
        });
        return normalized;
    }
}
