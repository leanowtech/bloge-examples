package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinitionChangeSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Machine-readable diff between two immutable visual graph draft revisions.
 *
 * @param schemaVersion diff schema version
 * @param draftId graph draft id
 * @param baseRevision base revision number
 * @param targetRevision target revision number
 * @param changed whether graph, node, or edge surface changed
 * @param changeRisk highest-risk category in the diff
 * @param changeCategories all risk categories present in the diff
 * @param changeSummary concise summary for review surfaces
 * @param addedNodeCount number of added node ids
 * @param removedNodeCount number of removed node ids
 * @param changedNodeCount number of changed node ids
 * @param addedEdgeCount number of added edge ids
 * @param removedEdgeCount number of removed edge ids
 * @param changedEdgeCount number of changed edge ids
 * @param graphChanges graph-level changes
 * @param nodeChanges node-level changes
 * @param edgeChanges edge-level changes
 */
public record GraphDraftDiff(
        String schemaVersion,
        String draftId,
        long baseRevision,
        long targetRevision,
        boolean changed,
        String changeRisk,
        List<String> changeCategories,
        String changeSummary,
        int addedNodeCount,
        int removedNodeCount,
        int changedNodeCount,
        int addedEdgeCount,
        int removedEdgeCount,
        int changedEdgeCount,
        List<GraphChange> graphChanges,
        List<NodeChange> nodeChanges,
        List<EdgeChange> edgeChanges
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphDraftDiff.v1";

    /**
     * Creates a normalized diff.
     */
    public GraphDraftDiff {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        draftId = draftId == null ? "" : draftId;
        changeRisk = changeRisk == null || changeRisk.isBlank()
                ? OperatorDefinitionChangeSummary.RISK_METADATA
                : changeRisk;
        changeCategories = changeCategories == null ? List.of() : List.copyOf(changeCategories);
        changeSummary = changeSummary == null ? "" : changeSummary;
        addedNodeCount = Math.max(0, addedNodeCount);
        removedNodeCount = Math.max(0, removedNodeCount);
        changedNodeCount = Math.max(0, changedNodeCount);
        addedEdgeCount = Math.max(0, addedEdgeCount);
        removedEdgeCount = Math.max(0, removedEdgeCount);
        changedEdgeCount = Math.max(0, changedEdgeCount);
        graphChanges = graphChanges == null ? List.of() : List.copyOf(graphChanges);
        nodeChanges = nodeChanges == null ? List.of() : List.copyOf(nodeChanges);
        edgeChanges = edgeChanges == null ? List.of() : List.copyOf(edgeChanges);
    }

    /**
     * @param base base immutable draft revision snapshot
     * @param target target immutable draft revision snapshot
     * @return classified graph draft diff
     */
    public static GraphDraftDiff between(GraphDraft base, GraphDraft target) {
        List<GraphChange> graphChanges = graphChanges(base, target);
        List<NodeChange> nodeChanges = nodeChanges(base, target);
        List<EdgeChange> edgeChanges = edgeChanges(base, target);
        ChangeClassification classification = classify(graphChanges, nodeChanges, edgeChanges);
        return new GraphDraftDiff(
                SCHEMA_VERSION,
                draftId(base, target),
                base == null ? 0L : base.revision(),
                target == null ? 0L : target.revision(),
                !graphChanges.isEmpty() || !nodeChanges.isEmpty() || !edgeChanges.isEmpty(),
                classification.risk(),
                classification.categories(),
                classification.summary(),
                (int) nodeChanges.stream().filter(change -> "ADDED".equals(change.changeKind())).count(),
                (int) nodeChanges.stream().filter(change -> "REMOVED".equals(change.changeKind())).count(),
                (int) nodeChanges.stream().filter(change -> "CHANGED".equals(change.changeKind())).count(),
                (int) edgeChanges.stream().filter(change -> "ADDED".equals(change.changeKind())).count(),
                (int) edgeChanges.stream().filter(change -> "REMOVED".equals(change.changeKind())).count(),
                (int) edgeChanges.stream().filter(change -> "CHANGED".equals(change.changeKind())).count(),
                graphChanges,
                nodeChanges,
                edgeChanges
        );
    }

    private static String draftId(GraphDraft base, GraphDraft target) {
        if (target != null && !target.draftId().isBlank()) {
            return target.draftId();
        }
        return base == null ? "" : base.draftId();
    }

    private static List<GraphChange> graphChanges(GraphDraft base, GraphDraft target) {
        List<GraphChange> changes = new ArrayList<>();
        addGraphChange(changes, "graphName", base == null ? "" : base.graphName(),
                target == null ? "" : target.graphName(),
                OperatorDefinitionChangeSummary.RISK_METADATA, "graph name");
        addGraphChange(changes, "tenantId", base == null ? "" : base.tenantId(),
                target == null ? "" : target.tenantId(),
                OperatorDefinitionChangeSummary.RISK_POLICY, "tenant scope");
        addGraphChange(changes, "namespace", base == null ? "" : base.namespace(),
                target == null ? "" : target.namespace(),
                OperatorDefinitionChangeSummary.RISK_POLICY, "namespace scope");
        addGraphChange(changes, "environment", base == null ? "" : base.environment(),
                target == null ? "" : target.environment(),
                OperatorDefinitionChangeSummary.RISK_POLICY, "environment scope");
        addGraphChange(changes, "status", base == null ? "" : base.status(),
                target == null ? "" : target.status(),
                OperatorDefinitionChangeSummary.RISK_METADATA, "draft lifecycle status");
        if (!Objects.equals(base == null ? null : base.inputSchema(), target == null ? null : target.inputSchema())) {
            changes.add(new GraphChange("inputSchema",
                    valueSummary(base == null ? null : base.inputSchema()),
                    valueSummary(target == null ? null : target.inputSchema()),
                    OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA,
                    "graph input schema changed"));
        }
        if (!Objects.equals(base == null ? null : base.output(), target == null ? null : target.output())) {
            changes.add(new GraphChange("output",
                    valueSummary(base == null ? null : base.output()),
                    valueSummary(target == null ? null : target.output()),
                    OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING,
                    "graph output selection changed"));
        }
        if (!Objects.equals(base == null ? null : base.visualLayout(), target == null ? null : target.visualLayout())) {
            changes.add(new GraphChange("visualLayout",
                    valueSummary(base == null ? null : base.visualLayout()),
                    valueSummary(target == null ? null : target.visualLayout()),
                    OperatorDefinitionChangeSummary.RISK_METADATA,
                    "visual layout contract changed"));
        }
        return List.copyOf(changes);
    }

    private static void addGraphChange(List<GraphChange> changes,
                                       String field,
                                       String baseValue,
                                       String targetValue,
                                       String risk,
                                       String label) {
        if (!Objects.equals(normalize(baseValue), normalize(targetValue))) {
            changes.add(new GraphChange(field, baseValue, targetValue, risk, label + " changed"));
        }
    }

    private static List<NodeChange> nodeChanges(GraphDraft base, GraphDraft target) {
        Map<String, GraphDraft.DraftNode> baseById = nodesById(base);
        Map<String, GraphDraft.DraftNode> targetById = nodesById(target);
        Set<String> nodeIds = new LinkedHashSet<>();
        nodeIds.addAll(baseById.keySet());
        nodeIds.addAll(targetById.keySet());
        List<NodeChange> changes = new ArrayList<>();
        for (String nodeId : nodeIds) {
            GraphDraft.DraftNode baseNode = baseById.get(nodeId);
            GraphDraft.DraftNode targetNode = targetById.get(nodeId);
            if (baseNode == null) {
                changes.add(new NodeChange(nodeId, "ADDED", "",
                        targetNode == null ? "" : targetNode.operatorRef(),
                        OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING,
                        List.of(OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING),
                        "node '" + nodeId + "' added",
                        List.of("node"),
                        "",
                        fingerprint(target, nodeId)));
                continue;
            }
            if (targetNode == null) {
                changes.add(new NodeChange(nodeId, "REMOVED", baseNode.operatorRef(), "",
                        OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING,
                        List.of(OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING),
                        "node '" + nodeId + "' removed",
                        List.of("node"),
                        fingerprint(base, nodeId),
                        ""));
                continue;
            }
            NodeChange changed = changedNode(base, target, baseNode, targetNode);
            if (changed != null) {
                changes.add(changed);
            }
        }
        return List.copyOf(changes);
    }

    private static NodeChange changedNode(GraphDraft base,
                                          GraphDraft target,
                                          GraphDraft.DraftNode baseNode,
                                          GraphDraft.DraftNode targetNode) {
        List<String> fields = new ArrayList<>();
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        if (!Objects.equals(baseNode.operatorRef(), targetNode.operatorRef())) {
            fields.add("operatorRef");
            categories.add(OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING);
        }
        if (!Objects.equals(baseNode.inputs(), targetNode.inputs())) {
            fields.add("inputs");
            categories.add(OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING);
        }
        if (!Objects.equals(baseNode.config(), targetNode.config())) {
            fields.add("config");
            categories.add(OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING);
        }
        if (!Objects.equals(fingerprint(base, baseNode.id()), fingerprint(target, targetNode.id()))) {
            fields.add("operatorFingerprint");
            categories.add(OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING);
        }
        OperatorDefinition baseSnapshot = base == null ? null : base.operatorSnapshots().get(baseNode.id());
        OperatorDefinition targetSnapshot = target == null ? null : target.operatorSnapshots().get(targetNode.id());
        if (!Objects.equals(baseSnapshot, targetSnapshot)) {
            fields.add("operatorSnapshot");
            categories.add(OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING);
        }
        if (!Objects.equals(baseNode.label(), targetNode.label())) {
            fields.add("label");
            categories.add(OperatorDefinitionChangeSummary.RISK_METADATA);
        }
        if (!Objects.equals(baseNode.position(), targetNode.position())) {
            fields.add("position");
            categories.add(OperatorDefinitionChangeSummary.RISK_METADATA);
        }
        if (fields.isEmpty()) {
            return null;
        }
        List<String> sortedCategories = sortedCategories(categories);
        String risk = sortedCategories.isEmpty()
                ? OperatorDefinitionChangeSummary.RISK_METADATA
                : sortedCategories.getFirst();
        return new NodeChange(
                baseNode.id(),
                "CHANGED",
                baseNode.operatorRef(),
                targetNode.operatorRef(),
                risk,
                sortedCategories,
                "node '" + baseNode.id() + "' changed: " + String.join(", ", fields),
                fields,
                fingerprint(base, baseNode.id()),
                fingerprint(target, targetNode.id())
        );
    }

    private static List<EdgeChange> edgeChanges(GraphDraft base, GraphDraft target) {
        Map<String, GraphDraft.DraftEdge> baseById = edgesById(base);
        Map<String, GraphDraft.DraftEdge> targetById = edgesById(target);
        Set<String> edgeIds = new LinkedHashSet<>();
        edgeIds.addAll(baseById.keySet());
        edgeIds.addAll(targetById.keySet());
        List<EdgeChange> changes = new ArrayList<>();
        for (String edgeId : edgeIds) {
            GraphDraft.DraftEdge baseEdge = baseById.get(edgeId);
            GraphDraft.DraftEdge targetEdge = targetById.get(edgeId);
            if (baseEdge == null) {
                changes.add(new EdgeChange(edgeId, "ADDED",
                        OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING,
                        List.of(OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING),
                        "edge '" + edgeId + "' added",
                        List.of("edge"),
                        "",
                        edgeSignature(targetEdge)));
                continue;
            }
            if (targetEdge == null) {
                changes.add(new EdgeChange(edgeId, "REMOVED",
                        OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING,
                        List.of(OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING),
                        "edge '" + edgeId + "' removed",
                        List.of("edge"),
                        edgeSignature(baseEdge),
                        ""));
                continue;
            }
            EdgeChange changed = changedEdge(baseEdge, targetEdge);
            if (changed != null) {
                changes.add(changed);
            }
        }
        return List.copyOf(changes);
    }

    private static EdgeChange changedEdge(GraphDraft.DraftEdge baseEdge, GraphDraft.DraftEdge targetEdge) {
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(baseEdge.kind(), targetEdge.kind())) {
            fields.add("kind");
        }
        if (!Objects.equals(baseEdge.source(), targetEdge.source())) {
            fields.add("source");
        }
        if (!Objects.equals(baseEdge.target(), targetEdge.target())) {
            fields.add("target");
        }
        if (!Objects.equals(baseEdge.condition(), targetEdge.condition())) {
            fields.add("condition");
        }
        if (fields.isEmpty()) {
            return null;
        }
        return new EdgeChange(
                baseEdge.id(),
                "CHANGED",
                OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING,
                List.of(OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING),
                "edge '" + baseEdge.id() + "' changed: " + String.join(", ", fields),
                fields,
                edgeSignature(baseEdge),
                edgeSignature(targetEdge)
        );
    }

    private static Map<String, GraphDraft.DraftNode> nodesById(GraphDraft draft) {
        if (draft == null || draft.nodes() == null) {
            return Map.of();
        }
        Map<String, GraphDraft.DraftNode> byId = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            if (node != null && !node.id().isBlank()) {
                byId.putIfAbsent(node.id(), node);
            }
        }
        return byId;
    }

    private static Map<String, GraphDraft.DraftEdge> edgesById(GraphDraft draft) {
        if (draft == null || draft.edges() == null) {
            return Map.of();
        }
        Map<String, GraphDraft.DraftEdge> byId = new LinkedHashMap<>();
        for (GraphDraft.DraftEdge edge : draft.edges()) {
            if (edge != null && !edge.id().isBlank()) {
                byId.putIfAbsent(edge.id(), edge);
            }
        }
        return byId;
    }

    private static String fingerprint(GraphDraft draft, String nodeId) {
        return draft == null ? "" : normalize(draft.operatorFingerprints().get(nodeId));
    }

    private static String edgeSignature(GraphDraft.DraftEdge edge) {
        if (edge == null) {
            return "";
        }
        return edge.kind()
                + ":" + endpointSignature(edge.source())
                + "->" + endpointSignature(edge.target())
                + (edge.condition().isBlank() ? "" : " when " + edge.condition());
    }

    private static String endpointSignature(GraphDraft.Endpoint endpoint) {
        if (endpoint == null) {
            return "";
        }
        return endpoint.nodeId() + "." + endpoint.port() + (endpoint.path().isBlank() ? "" : "." + endpoint.path());
    }

    private static ChangeClassification classify(List<GraphChange> graphChanges,
                                                 List<NodeChange> nodeChanges,
                                                 List<EdgeChange> edgeChanges) {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        List<String> summaries = new ArrayList<>();
        for (GraphChange change : graphChanges) {
            categories.add(change.risk());
            summaries.add(change.summary());
        }
        for (NodeChange change : nodeChanges) {
            categories.addAll(change.categories());
            summaries.add(change.summary());
        }
        for (EdgeChange change : edgeChanges) {
            categories.addAll(change.categories());
            summaries.add(change.summary());
        }
        List<String> sortedCategories = sortedCategories(categories);
        String risk = sortedCategories.isEmpty()
                ? OperatorDefinitionChangeSummary.RISK_METADATA
                : sortedCategories.getFirst();
        return new ChangeClassification(risk, sortedCategories, summarize(summaries));
    }

    private static List<String> sortedCategories(Set<String> categories) {
        return categories.stream()
                .filter(category -> category != null && !category.isBlank())
                .sorted((left, right) -> Integer.compare(
                        riskRank(right),
                        riskRank(left)))
                .toList();
    }

    private static int riskRank(String risk) {
        return switch (normalize(risk).toUpperCase()) {
            case OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA -> 6;
            case OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING -> 5;
            case OperatorDefinitionChangeSummary.RISK_GOVERNANCE -> 4;
            case OperatorDefinitionChangeSummary.RISK_POLICY -> 3;
            case OperatorDefinitionChangeSummary.RISK_COMPATIBLE_SCHEMA -> 2;
            case OperatorDefinitionChangeSummary.RISK_METADATA -> 1;
            default -> 0;
        };
    }

    private static String summarize(List<String> summaries) {
        List<String> visibleSummaries = summaries.stream()
                .filter(summary -> summary != null && !summary.isBlank())
                .toList();
        if (visibleSummaries.isEmpty()) {
            return "No graph draft surface changes.";
        }
        int visible = Math.min(6, visibleSummaries.size());
        String summary = String.join("; ", visibleSummaries.subList(0, visible));
        int remaining = visibleSummaries.size() - visible;
        return remaining > 0 ? summary + "; +" + remaining + " more" : summary;
    }

    private static String valueSummary(Object value) {
        if (value == null) {
            return "";
        }
        String raw = String.valueOf(value);
        return raw.length() > 180 ? raw.substring(0, 177) + "..." : raw;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record ChangeClassification(String risk, List<String> categories, String summary) {
    }

    /**
     * Graph-level revision change.
     *
     * @param field graph field name
     * @param baseValue value in the base revision
     * @param targetValue value in the target revision
     * @param risk risk category
     * @param summary concise change summary
     */
    public record GraphChange(
            String field,
            String baseValue,
            String targetValue,
            String risk,
            String summary
    ) {
        public GraphChange {
            field = field == null ? "" : field;
            baseValue = baseValue == null ? "" : baseValue;
            targetValue = targetValue == null ? "" : targetValue;
            risk = risk == null || risk.isBlank() ? OperatorDefinitionChangeSummary.RISK_METADATA : risk;
            summary = summary == null ? "" : summary;
        }
    }

    /**
     * Node-level revision change.
     *
     * @param nodeId node id
     * @param changeKind ADDED, REMOVED, or CHANGED
     * @param baseOperatorRef operator ref in the base revision
     * @param targetOperatorRef operator ref in the target revision
     * @param risk highest-risk category
     * @param categories all categories in this node change
     * @param summary concise change summary
     * @param changedFields changed node fields
     * @param baseFingerprint operator fingerprint in the base revision
     * @param targetFingerprint operator fingerprint in the target revision
     */
    public record NodeChange(
            String nodeId,
            String changeKind,
            String baseOperatorRef,
            String targetOperatorRef,
            String risk,
            List<String> categories,
            String summary,
            List<String> changedFields,
            String baseFingerprint,
            String targetFingerprint
    ) {
        public NodeChange {
            nodeId = nodeId == null ? "" : nodeId;
            changeKind = changeKind == null || changeKind.isBlank() ? "CHANGED" : changeKind;
            baseOperatorRef = baseOperatorRef == null ? "" : baseOperatorRef;
            targetOperatorRef = targetOperatorRef == null ? "" : targetOperatorRef;
            risk = risk == null || risk.isBlank() ? OperatorDefinitionChangeSummary.RISK_METADATA : risk;
            categories = categories == null ? List.of() : List.copyOf(categories);
            summary = summary == null ? "" : summary;
            changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
            baseFingerprint = baseFingerprint == null ? "" : baseFingerprint;
            targetFingerprint = targetFingerprint == null ? "" : targetFingerprint;
        }
    }

    /**
     * Edge-level revision change.
     *
     * @param edgeId edge id
     * @param changeKind ADDED, REMOVED, or CHANGED
     * @param risk highest-risk category
     * @param categories all categories in this edge change
     * @param summary concise change summary
     * @param changedFields changed edge fields
     * @param baseSignature compact base edge signature
     * @param targetSignature compact target edge signature
     */
    public record EdgeChange(
            String edgeId,
            String changeKind,
            String risk,
            List<String> categories,
            String summary,
            List<String> changedFields,
            String baseSignature,
            String targetSignature
    ) {
        public EdgeChange {
            edgeId = edgeId == null ? "" : edgeId;
            changeKind = changeKind == null || changeKind.isBlank() ? "CHANGED" : changeKind;
            risk = risk == null || risk.isBlank() ? OperatorDefinitionChangeSummary.RISK_METADATA : risk;
            categories = categories == null ? List.of() : List.copyOf(categories);
            summary = summary == null ? "" : summary;
            changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
            baseSignature = baseSignature == null ? "" : baseSignature;
            targetSignature = targetSignature == null ? "" : targetSignature;
        }
    }
}
