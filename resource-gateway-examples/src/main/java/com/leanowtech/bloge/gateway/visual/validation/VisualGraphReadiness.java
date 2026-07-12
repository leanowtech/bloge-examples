package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server-derived graph-level readiness for visual authoring.
 *
 * <p>Validation answers whether the draft contract is structurally sound; readiness answers whether the
 * structurally sound draft can be executed by the current request-response runtime or should remain a design
 * artifact until runtime lowerings/governance are resolved.</p>
 *
 * @param schemaVersion readiness contract schema version
 * @param state stable machine-readable graph readiness state
 * @param level UI severity: success/info/warning/error
 * @param executable whether request-response execution is available
 * @param artifactKinds artifact kinds this draft can currently publish as
 * @param title short display title
 * @param summary human-readable readiness summary
 * @param nodeCount number of draft nodes
 * @param runtimeExecutableNodeCount executable node count
 * @param designOnlyNodeCount design-only node count
 * @param runtimeBlockedNodeCount runtime-blocked node count
 * @param governanceReviewNodeCount governance-review node count
 * @param draftRepairNodeCount node count requiring draft/catalog repair
 * @param runtimeBindingRequirementCount runtime binding requirement count
 * @param runtimeBindingRequirements machine-readable runtime bindings needed before executable promotion
 * @param nodes per-node readiness rows
 */
public record VisualGraphReadiness(
        String schemaVersion,
        String state,
        String level,
        boolean executable,
        List<String> artifactKinds,
        String title,
        String summary,
        int nodeCount,
        int runtimeExecutableNodeCount,
        int designOnlyNodeCount,
        int runtimeBlockedNodeCount,
        int governanceReviewNodeCount,
        int draftRepairNodeCount,
        int runtimeBindingRequirementCount,
        List<RuntimeBindingRequirement> runtimeBindingRequirements,
        List<NodeReadiness> nodes
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphReadiness.v1";
    public static final String RUNTIME_EXECUTABLE = "runtime-executable";
    public static final String DESIGN_ONLY = "design-only";
    public static final String RUNTIME_BLOCKED = "runtime-blocked";
    public static final String EXTERNAL_RUNTIME_BOUND = "external-runtime-bound";
    public static final String GOVERNANCE_REVIEW = "governance-review";
    public static final String DRAFT_REPAIR_REQUIRED = "draft-repair-required";
    public static final String NOT_ASSESSED = "not-assessed";

    private static final Set<String> RUNTIME_BLOCKING_DIAGNOSTICS = Set.of(
            "visual.operator.runtime.streamingUnsupported",
            "visual.operator.runtime.durableUnsupported"
    );

    /**
     * Creates a readiness payload.
     */
    public VisualGraphReadiness {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        state = normalizeState(state);
        level = level == null || level.isBlank() ? "info" : level.trim().toLowerCase(Locale.ROOT);
        artifactKinds = artifactKinds == null ? List.of() : artifactKinds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        title = title == null ? "" : title;
        summary = summary == null ? "" : summary;
        runtimeBindingRequirements = runtimeBindingRequirements == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(runtimeBindingRequirements));
        runtimeBindingRequirementCount = runtimeBindingRequirements.size();
        nodes = nodes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(nodes));
    }

    /**
     * @return an empty readiness payload
     */
    public static VisualGraphReadiness empty() {
        return new VisualGraphReadiness(
                SCHEMA_VERSION,
                DRAFT_REPAIR_REQUIRED,
                "error",
                false,
                List.of(),
                "Draft repair required",
                "Graph draft is required before runtime readiness can be assessed.",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of()
        );
    }

    /**
     * @return a neutral readiness payload for legacy validation results that were not graph-assessed
     */
    public static VisualGraphReadiness notAssessed() {
        return new VisualGraphReadiness(
                SCHEMA_VERSION,
                NOT_ASSESSED,
                "info",
                false,
                List.of(),
                "Readiness not assessed",
                "This validation result was created without a graph-level runtime readiness assessment.",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of()
        );
    }

    /**
     * Builds graph readiness from the validated draft, resolved operators, and diagnostics.
     *
     * @param draft graph draft
     * @param operatorsByNodeId resolved operators keyed by draft node id
     * @param diagnostics validation diagnostics
     * @return graph readiness
     */
    public static VisualGraphReadiness from(GraphDraft draft,
                                            Map<String, OperatorDefinition> operatorsByNodeId,
                                            List<VisualDiagnostic> diagnostics) {
        if (draft == null) {
            return empty();
        }
        List<VisualDiagnostic> safeDiagnostics = diagnostics == null ? List.of() : diagnostics;
        Map<String, List<VisualDiagnostic>> diagnosticsByNodeId = diagnosticsByNodeId(draft, safeDiagnostics);
        List<NodeReadiness> nodes = new ArrayList<>();
        List<RuntimeBindingRequirement> runtimeBindingRequirements = new ArrayList<>();
        Totals totals = new Totals();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            OperatorDefinition operator = operatorsByNodeId == null ? null : operatorsByNodeId.get(node.id());
            NodeReadiness readiness = NodeReadiness.from(node, operator,
                    diagnosticsByNodeId.getOrDefault(node.id(), List.of()));
            nodes.add(readiness);
            runtimeBindingRequirements.addAll(RuntimeBindingRequirement.from(node, operator, readiness));
            totals.add(readiness);
        }
        boolean hasGlobalRepairError = safeDiagnostics.stream()
                .filter(VisualDiagnostic::error)
                .anyMatch(diagnostic -> diagnosticTargetNodeId(draft, diagnostic).isBlank()
                        && !isRuntimeBlockingDiagnostic(diagnostic));
        boolean hasRuntimeError = safeDiagnostics.stream().anyMatch(VisualGraphReadiness::isRuntimeBlockingDiagnostic);
        boolean hasRepairError = hasGlobalRepairError || totals.draftRepairNodeCount > 0;
        boolean valid = safeDiagnostics.stream().noneMatch(VisualDiagnostic::error);
        GraphState graphState = graphState(valid, hasRepairError, hasRuntimeError, totals);
        return new VisualGraphReadiness(
                SCHEMA_VERSION,
                graphState.state(),
                graphState.level(),
                graphState.executable(),
                artifactKinds(valid, graphState.executable()),
                graphState.title(),
                graphState.summary(),
                nodes.size(),
                totals.runtimeExecutableNodeCount,
                totals.designOnlyNodeCount,
                totals.runtimeBlockedNodeCount,
                totals.governanceReviewNodeCount,
                totals.draftRepairNodeCount,
                runtimeBindingRequirements.size(),
                runtimeBindingRequirements,
                nodes
        );
    }

    /**
     * Runtime binding required before a schema-valid design can be promoted to an executable artifact.
     *
     * @param nodeId draft node id that needs the binding
     * @param operatorRef operator reference used by the node
     * @param state readiness state that produced the requirement
     * @param level UI severity
     * @param sourceKind operator source kind
     * @param loweringMode requested lowering mode
     * @param bindingKind stable machine-readable binding kind
     * @param bindingTarget topic/tool/channel/path/operator target when declared
     * @param handoffLane runtime-plane responsibility lane
     * @param handoffKind runtime-plane work kind
     * @param handoffTarget runtime-plane routing target
     * @param title short display title
     * @param summary human-readable binding gap summary
     * @param recommendedAction human-readable next action
     */
    public record RuntimeBindingRequirement(
            String nodeId,
            String operatorRef,
            String state,
            String level,
            String sourceKind,
            String loweringMode,
            String bindingKind,
            String bindingTarget,
            String handoffLane,
            String handoffKind,
            String handoffTarget,
            String title,
            String summary,
            String recommendedAction
    ) {
        public RuntimeBindingRequirement {
            nodeId = nodeId == null ? "" : nodeId;
            operatorRef = operatorRef == null ? "" : operatorRef;
            state = normalizeState(state);
            level = level == null || level.isBlank() ? "warning" : level.trim().toLowerCase(Locale.ROOT);
            sourceKind = normalizeState(sourceKind);
            loweringMode = normalizeState(loweringMode);
            bindingKind = normalizeState(bindingKind);
            bindingTarget = bindingTarget == null ? "" : bindingTarget;
            handoffLane = normalizeState(handoffLane);
            handoffKind = normalizeState(handoffKind);
            handoffTarget = handoffTarget == null ? "" : handoffTarget;
            title = title == null ? "" : title;
            summary = summary == null ? "" : summary;
            recommendedAction = recommendedAction == null ? "" : recommendedAction;
        }

        private static List<RuntimeBindingRequirement> from(GraphDraft.DraftNode node,
                                                            OperatorDefinition operator,
                                                            NodeReadiness readiness) {
            if (node == null || operator == null || readiness == null) {
                return List.of();
            }
            return VisualRuntimeBindingRequirementPlanner.from(operator, readiness.state(), readiness.level(),
                            readiness.summary()).stream()
                    .map(requirement -> requirement(node, requirement))
                    .toList();
        }

        private static RuntimeBindingRequirement requirement(
                GraphDraft.DraftNode node,
                VisualRuntimeBindingRequirementPlanner.OperatorRequirement requirement) {
            return new RuntimeBindingRequirement(
                    node.id(),
                    node.operatorRef(),
                    requirement.state(),
                    requirement.level(),
                    requirement.sourceKind(),
                    requirement.loweringMode(),
                    requirement.bindingKind(),
                    requirement.bindingTarget(),
                    requirement.handoffLane(),
                    requirement.handoffKind(),
                    requirement.handoffTarget(),
                    requirement.title(),
                    requirement.summary(),
                    nodeRecommendedAction(requirement)
            );
        }

        private static String nodeRecommendedAction(
                VisualRuntimeBindingRequirementPlanner.OperatorRequirement requirement) {
            return switch (requirement.bindingKind()) {
                case "executable-lowering" ->
                        "Bind a native/resource/subgraph lowering or replace the node with an executable operator before EXECUTABLE promotion.";
                case "remote-worker-runtime" ->
                        "Bind worker dispatch for this topic or replace the node before EXECUTABLE promotion.";
                case "ai-tool-runtime" ->
                        "Bind tool invocation for this toolRef or replace the node before EXECUTABLE promotion.";
                case "event-source-runtime" ->
                        "Bind event subscription for this event type or replace the node before EXECUTABLE promotion.";
                case "message-runtime" ->
                        "Bind message consumption for this channel or replace the node before EXECUTABLE promotion.";
                case "webhook-ingress-runtime" ->
                        "Bind webhook ingress for this endpoint or replace the node before EXECUTABLE promotion.";
                case "streaming-runtime" ->
                        "Bind a streaming runtime or replace the node before EXECUTABLE promotion.";
                case "durable-runtime" ->
                        "Bind a durable runtime or replace the node before EXECUTABLE promotion.";
                case "side-effect-conformance" ->
                        "Adopt bloge.sideEffectProtocol.v1, prove journal/receipt/reconciliation behavior, and refresh the operator fingerprint before EXECUTABLE promotion.";
                default -> "Bind the missing runtime adapter or replace the node before EXECUTABLE promotion.";
            };
        }
    }

    /**
     * Per-node readiness row.
     *
     * @param nodeId draft node id
     * @param operatorRef operator reference
     * @param state stable machine-readable node readiness state
     * @param level UI severity
     * @param executable whether this node is executable by the current runtime
     * @param title short display title
     * @param summary human-readable readiness summary
     * @param diagnosticCount diagnostics attached to the node
     * @param errorCount error diagnostics attached to the node
     * @param warningCount warning diagnostics attached to the node
     */
    public record NodeReadiness(
            String nodeId,
            String operatorRef,
            String state,
            String level,
            boolean executable,
            String title,
            String summary,
            int diagnosticCount,
            int errorCount,
            int warningCount
    ) {
        public NodeReadiness {
            nodeId = nodeId == null ? "" : nodeId;
            operatorRef = operatorRef == null ? "" : operatorRef;
            state = normalizeState(state);
            level = level == null || level.isBlank() ? "info" : level.trim().toLowerCase(Locale.ROOT);
            title = title == null ? "" : title;
            summary = summary == null ? "" : summary;
        }

        private static NodeReadiness from(GraphDraft.DraftNode node,
                                          OperatorDefinition operator,
                                          List<VisualDiagnostic> diagnostics) {
            List<VisualDiagnostic> safeDiagnostics = diagnostics == null ? List.of() : diagnostics;
            long errorCount = safeDiagnostics.stream().filter(VisualDiagnostic::error).count();
            long warningCount = safeDiagnostics.stream()
                    .filter(diagnostic -> "WARNING".equalsIgnoreCase(diagnostic.level()))
                    .count();
            if (errorCount > 0) {
                boolean runtimeOnly = safeDiagnostics.stream()
                        .filter(VisualDiagnostic::error)
                        .allMatch(VisualGraphReadiness::isRuntimeBlockingDiagnostic);
                if (runtimeOnly) {
                    return new NodeReadiness(
                            node.id(),
                            node.operatorRef(),
                            RUNTIME_BLOCKED,
                            "error",
                            false,
                            "Runtime blocked",
                            "The node schema can be authored, but the current request-response runtime cannot execute this operator.",
                            safeDiagnostics.size(),
                            Math.toIntExact(errorCount),
                            Math.toIntExact(warningCount)
                    );
                }
                return new NodeReadiness(
                        node.id(),
                        node.operatorRef(),
                        DRAFT_REPAIR_REQUIRED,
                        "error",
                        false,
                        "Draft repair required",
                        "Validation errors on this node must be repaired before it can be promoted.",
                        safeDiagnostics.size(),
                        Math.toIntExact(errorCount),
                        Math.toIntExact(warningCount)
                );
            }
            if (operator == null) {
                return new NodeReadiness(
                        node.id(),
                        node.operatorRef(),
                        DRAFT_REPAIR_REQUIRED,
                        "error",
                        false,
                        "Operator unavailable",
                        "The draft references an operator that is not visible in the current catalog scope.",
                        safeDiagnostics.size(),
                        0,
                        Math.toIntExact(warningCount)
                );
            }
            OperatorDefinition.RuntimeReadiness readiness = operator.runtimeReadiness();
            if (readiness == null) {
                return new NodeReadiness(
                        node.id(),
                        node.operatorRef(),
                        RUNTIME_EXECUTABLE,
                        warningCount > 0 ? "warning" : "success",
                        true,
                        "Runtime executable",
                        "Executable lowering is present for this request-response visual runtime.",
                        safeDiagnostics.size(),
                        0,
                        Math.toIntExact(warningCount)
                );
            }
            return new NodeReadiness(
                    node.id(),
                    node.operatorRef(),
                    normalizeState(readiness.state()),
                    warningCount > 0 && "success".equalsIgnoreCase(readiness.level())
                            ? "warning"
                            : readiness.level(),
                    readiness.executable(),
                    readiness.title(),
                    readiness.summary(),
                    safeDiagnostics.size(),
                    0,
                    Math.toIntExact(warningCount)
            );
        }
    }

    private record GraphState(String state,
                              String level,
                              boolean executable,
                              String title,
                              String summary) {
    }

    private static final class Totals {
        private int runtimeExecutableNodeCount;
        private int designOnlyNodeCount;
        private int runtimeBlockedNodeCount;
        private int governanceReviewNodeCount;
        private int draftRepairNodeCount;

        private void add(NodeReadiness node) {
            switch (node.state()) {
                case RUNTIME_EXECUTABLE -> runtimeExecutableNodeCount += 1;
                case DESIGN_ONLY -> designOnlyNodeCount += 1;
                case RUNTIME_BLOCKED, EXTERNAL_RUNTIME_BOUND -> runtimeBlockedNodeCount += 1;
                case GOVERNANCE_REVIEW -> governanceReviewNodeCount += 1;
                case DRAFT_REPAIR_REQUIRED -> draftRepairNodeCount += 1;
                default -> {
                    if (!node.executable()) {
                        draftRepairNodeCount += 1;
                    }
                }
            }
        }
    }

    private static GraphState graphState(boolean valid,
                                         boolean hasRepairError,
                                         boolean hasRuntimeError,
                                         Totals totals) {
        if (!valid && hasRepairError) {
            return new GraphState(
                    DRAFT_REPAIR_REQUIRED,
                    "error",
                    false,
                    "Draft repair required",
                    "Validation found draft or catalog contract errors that must be repaired before promotion."
            );
        }
        if (hasRuntimeError || totals.runtimeBlockedNodeCount > 0) {
            return new GraphState(
                    RUNTIME_BLOCKED,
                    valid ? "warning" : "error",
                    false,
                    "Runtime blocked",
                    "The draft can be inspected as a schema graph, but the current runtime cannot execute every node."
            );
        }
        if (totals.designOnlyNodeCount > 0) {
            return new GraphState(
                    DESIGN_ONLY,
                    "info",
                    false,
                    "Design-only graph",
                    "The graph is schema-valid and can be frozen as a design artifact; bind runtime lowerings to execute it."
            );
        }
        if (totals.governanceReviewNodeCount > 0) {
            return new GraphState(
                    GOVERNANCE_REVIEW,
                    "warning",
                    true,
                    "Executable with governance review",
                    "The graph can execute, but promotion should review external effects, secrets, or idempotency risks."
            );
        }
        return new GraphState(
                RUNTIME_EXECUTABLE,
                "success",
                true,
                "Runtime executable",
                "Every node is ready for request-response execution in the current visual runtime."
        );
    }

    private static List<String> artifactKinds(boolean valid, boolean executable) {
        if (!valid) {
            return List.of();
        }
        Set<String> kinds = new LinkedHashSet<>();
        if (executable) {
            kinds.add("EXECUTABLE");
        }
        kinds.add("DESIGN");
        return List.copyOf(kinds);
    }

    private static Map<String, List<VisualDiagnostic>> diagnosticsByNodeId(GraphDraft draft,
                                                                            List<VisualDiagnostic> diagnostics) {
        Map<String, List<VisualDiagnostic>> byNode = new LinkedHashMap<>();
        for (VisualDiagnostic diagnostic : diagnostics) {
            String nodeId = diagnosticTargetNodeId(draft, diagnostic);
            if (nodeId.isBlank()) {
                continue;
            }
            byNode.computeIfAbsent(nodeId, ignored -> new ArrayList<>()).add(diagnostic);
        }
        return byNode;
    }

    private static String diagnosticTargetNodeId(GraphDraft draft, VisualDiagnostic diagnostic) {
        Object metadataNodeId = diagnostic == null ? null : diagnostic.metadata().get("nodeId");
        if (metadataNodeId instanceof String nodeId && !nodeId.isBlank()) {
            return nodeId;
        }
        String target = diagnostic == null ? "" : diagnostic.target();
        if (target == null || target.isBlank()) {
            return "";
        }
        String[] segments = target.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (!"nodes".equals(segments[i])) {
                continue;
            }
            try {
                int index = Integer.parseInt(segments[i + 1]);
                if (index >= 0 && index < draft.nodes().size()) {
                    return draft.nodes().get(index).id();
                }
            } catch (NumberFormatException ignored) {
                return "";
            }
        }
        return "";
    }

    private static boolean isRuntimeBlockingDiagnostic(VisualDiagnostic diagnostic) {
        return diagnostic != null && RUNTIME_BLOCKING_DIAGNOSTICS.contains(diagnostic.code());
    }

    private static String normalizeState(String value) {
        return String.valueOf(value == null ? "" : value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }
}
