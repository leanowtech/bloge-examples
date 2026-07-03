package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Control-plane dependency summary for one stored visual graph draft.
 *
 * <p>This is a read model for review, migration, and impact analysis. It does
 * not replace {@code GraphDraftValidator}; it makes the draft's current catalog
 * dependencies and saved operator snapshot state explicit.</p>
 *
 * @param schemaVersion dependency report contract version
 * @param draftId draft id
 * @param revision stored draft revision
 * @param graphName graph name
 * @param tenantId tenant id
 * @param namespace namespace
 * @param environment environment
 * @param nodeCount number of nodes
 * @param edgeCount number of edges
 * @param operatorDependencyCount number of distinct operator references
 * @param missingOperatorCount number of nodes whose current operator is absent from the catalog
 * @param scopeMismatchOperatorCount number of nodes whose operator exists but is unavailable in the draft scope
 * @param driftedFingerprintCount number of nodes whose saved fingerprint differs from the current catalog
 * @param missingFingerprintCount number of nodes without a saved fingerprint
 * @param sourceKindCounts node counts by operator source kind
 * @param operatorLibraryIdCounts node counts by owner operator library id
 * @param loweringModeCounts node counts by operator lowering mode
 * @param runtimeReadinessStateCounts node counts by operator runtime readiness state
 * @param operators distinct operator dependencies used by the draft
 * @param nodes per-node dependency rows
 */
public record GraphDraftDependencyReport(
        String schemaVersion,
        String draftId,
        long revision,
        String graphName,
        String tenantId,
        String namespace,
        String environment,
        int nodeCount,
        int edgeCount,
        int operatorDependencyCount,
        int missingOperatorCount,
        int scopeMismatchOperatorCount,
        int driftedFingerprintCount,
        int missingFingerprintCount,
        Map<String, Integer> sourceKindCounts,
        Map<String, Integer> operatorLibraryIdCounts,
        Map<String, Integer> loweringModeCounts,
        Map<String, Integer> runtimeReadinessStateCounts,
        List<OperatorDependency> operators,
        List<NodeDependency> nodes
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphDraftDependencies.v1";

    /**
     * Creates a dependency report.
     */
    public GraphDraftDependencyReport {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        draftId = draftId == null ? "" : draftId;
        graphName = graphName == null ? "" : graphName;
        tenantId = tenantId == null ? "" : tenantId;
        namespace = namespace == null ? "" : namespace;
        environment = environment == null ? "" : environment;
        sourceKindCounts = sourceKindCounts == null ? Map.of() : new LinkedHashMap<>(sourceKindCounts);
        operatorLibraryIdCounts = operatorLibraryIdCounts == null
                ? Map.of()
                : new LinkedHashMap<>(operatorLibraryIdCounts);
        loweringModeCounts = loweringModeCounts == null ? Map.of() : new LinkedHashMap<>(loweringModeCounts);
        runtimeReadinessStateCounts = runtimeReadinessStateCounts == null
                ? Map.of()
                : new LinkedHashMap<>(runtimeReadinessStateCounts);
        operators = operators == null ? List.of() : List.copyOf(operators);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /**
     * Builds a dependency report from a stored draft and the current catalog.
     *
     * @param draft stored draft
     * @param catalog current visual operator catalog
     * @return dependency report
     */
    public static GraphDraftDependencyReport from(GraphDraft draft, VisualOperatorCatalog catalog) {
        if (draft == null) {
            return empty();
        }
        Map<String, Set<String>> edgeSourcesByTarget = new LinkedHashMap<>();
        Map<String, Set<String>> edgeTargetsBySource = new LinkedHashMap<>();
        Map<String, Set<String>> bindingSourcesByTarget = new LinkedHashMap<>();
        Map<String, Set<String>> bindingTargetsBySource = new LinkedHashMap<>();
        for (GraphDraft.DraftEdge edge : draft.edges()) {
            String source = edge.source().nodeId();
            String target = edge.target().nodeId();
            if (!source.isBlank() && !target.isBlank()) {
                edgeSourcesByTarget.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(source);
                edgeTargetsBySource.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(target);
            }
        }
        for (GraphDraft.DraftNode node : draft.nodes()) {
            Set<String> sourceNodes = GraphDraftDependencies.nodeDependencies(node);
            bindingSourcesByTarget.put(node.id(), sourceNodes);
            for (String sourceNode : sourceNodes) {
                if (!sourceNode.isBlank()) {
                    bindingTargetsBySource.computeIfAbsent(sourceNode, ignored -> new LinkedHashSet<>())
                            .add(node.id());
                }
            }
        }

        Map<String, Integer> sourceKindCounts = new LinkedHashMap<>();
        Map<String, Integer> operatorLibraryIdCounts = new LinkedHashMap<>();
        Map<String, Integer> loweringModeCounts = new LinkedHashMap<>();
        Map<String, Integer> runtimeReadinessStateCounts = new LinkedHashMap<>();
        Map<String, OperatorAggregate> operatorAggregates = new LinkedHashMap<>();
        List<NodeDependency> nodeRows = new ArrayList<>();
        int missingOperators = 0;
        int scopeMismatchOperators = 0;
        int driftedFingerprints = 0;
        int missingFingerprints = 0;
        Map<String, OperatorDefinition> scopedOperators = scopedOperators(draft, catalog);

        for (GraphDraft.DraftNode node : draft.nodes()) {
            Optional<OperatorDefinition> catalogOperator = catalog == null
                    ? Optional.empty()
                    : catalog.find(node.operatorRef());
            OperatorDefinition scopedOperator = scopedOperators.get(node.operatorRef());
            boolean currentOperatorPresent = catalogOperator.isPresent();
            boolean scopeAllowed = scopedOperator != null;
            OperatorDefinition reviewOperator = scopeAllowed
                    ? scopedOperator
                    : catalogOperator.orElseGet(() -> snapshotForNode(draft, node));
            String savedFingerprint = draft.operatorFingerprints().getOrDefault(node.id(), "");
            String currentFingerprint = catalogOperator.map(OperatorDefinition::fingerprint).orElse("");
            String fingerprintState = fingerprintState(savedFingerprint, currentFingerprint,
                    currentOperatorPresent, scopeAllowed);
            if (!currentOperatorPresent) {
                missingOperators++;
            }
            List<String> policyViolations = currentOperatorPresent
                    ? catalogOperator.get().policy().violations(draft.tenantId(), draft.namespace(), draft.environment())
                    : List.of();
            if (currentOperatorPresent && !scopeAllowed) {
                scopeMismatchOperators++;
            }
            if ("drifted".equals(fingerprintState)) {
                driftedFingerprints++;
            }
            if (savedFingerprint.isBlank()) {
                missingFingerprints++;
            }

            String sourceKind = sourceKind(reviewOperator, currentOperatorPresent);
            String operatorLibraryId = operatorLibraryId(reviewOperator);
            String loweringMode = loweringMode(reviewOperator);
            String readinessState = runtimeReadinessState(reviewOperator, currentOperatorPresent, scopeAllowed);
            boolean executable = reviewOperator != null
                    && reviewOperator.runtimeReadiness() != null
                    && reviewOperator.runtimeReadiness().executable()
                    && scopeAllowed;
            List<String> artifactKinds = reviewOperator == null || reviewOperator.runtimeReadiness() == null
                    ? List.of()
                    : reviewOperator.runtimeReadiness().artifactKinds();

            increment(sourceKindCounts, sourceKind);
            incrementIfPresent(operatorLibraryIdCounts, operatorLibraryId);
            increment(loweringModeCounts, loweringMode);
            increment(runtimeReadinessStateCounts, readinessState);

            Set<String> bindingSourceNodes = bindingSourcesByTarget.getOrDefault(node.id(), Set.of());
            Set<String> edgeSourceNodes = edgeSourcesByTarget.getOrDefault(node.id(), Set.of());
            Set<String> upstreamNodes = new LinkedHashSet<>();
            upstreamNodes.addAll(edgeSourceNodes);
            upstreamNodes.addAll(bindingSourceNodes);
            Set<String> bindingTargetNodes = bindingTargetsBySource.getOrDefault(node.id(), Set.of());
            Set<String> edgeTargetNodes = edgeTargetsBySource.getOrDefault(node.id(), Set.of());
            Set<String> downstreamNodes = new LinkedHashSet<>();
            downstreamNodes.addAll(edgeTargetNodes);
            downstreamNodes.addAll(bindingTargetNodes);

            NodeDependency nodeRow = new NodeDependency(
                    node.id(),
                    node.label(),
                    node.operatorRef(),
                    sourceKind,
                    operatorLibraryId,
                    loweringMode,
                    readinessState,
                    executable,
                    savedFingerprint,
                    currentFingerprint,
                    fingerprintState,
                    List.copyOf(bindingSourceNodes),
                    List.copyOf(edgeSourceNodes),
                    List.copyOf(upstreamNodes),
                    List.copyOf(bindingTargetNodes),
                    List.copyOf(edgeTargetNodes),
                    List.copyOf(downstreamNodes),
                    scopeAllowed,
                    policyViolations
            );
            nodeRows.add(nodeRow);

            operatorAggregates
                    .computeIfAbsent(node.operatorRef(), ignored -> new OperatorAggregate(
                            node.operatorRef(),
                            sourceKind,
                            operatorLibraryId,
                            loweringMode,
                            readinessState,
                            executable,
                            artifactKinds,
                            currentFingerprint,
                            scopeAllowed,
                            policyViolations
                    ))
                    .add(node.id(), fingerprintState);
        }

        List<OperatorDependency> operators = operatorAggregates.values().stream()
                .map(OperatorAggregate::toDependency)
                .toList();
        return new GraphDraftDependencyReport(
                SCHEMA_VERSION,
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                draft.nodes().size(),
                draft.edges().size(),
                operators.size(),
                missingOperators,
                scopeMismatchOperators,
                driftedFingerprints,
                missingFingerprints,
                sourceKindCounts,
                operatorLibraryIdCounts,
                loweringModeCounts,
                runtimeReadinessStateCounts,
                operators,
                nodeRows
        );
    }

    /**
     * @return an empty dependency report for rejected control-plane responses
     */
    public static GraphDraftDependencyReport empty() {
        return new GraphDraftDependencyReport(
                SCHEMA_VERSION,
                "",
                0,
                "",
                "",
                "",
                "",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of()
        );
    }

    private static OperatorDefinition snapshotForNode(GraphDraft draft, GraphDraft.DraftNode node) {
        OperatorDefinition snapshot = draft.operatorSnapshots().get(node.id());
        if (snapshot != null && snapshot.operatorRef().equals(node.operatorRef())) {
            return snapshot;
        }
        return null;
    }

    private static Map<String, OperatorDefinition> scopedOperators(GraphDraft draft, VisualOperatorCatalog catalog) {
        if (catalog == null) {
            return Map.of();
        }
        OperatorCatalogQuery query = new OperatorCatalogQuery("", List.of(), false, true,
                draft.tenantId(), draft.namespace(), draft.environment());
        Map<String, OperatorDefinition> operators = new LinkedHashMap<>();
        for (OperatorDefinition operator : catalog.list(query)) {
            operators.putIfAbsent(operator.operatorRef(), operator);
        }
        return operators;
    }

    private static String fingerprintState(String savedFingerprint,
                                           String currentFingerprint,
                                           boolean currentOperatorPresent,
                                           boolean scopeAllowed) {
        if (!currentOperatorPresent) {
            return "catalog-missing";
        }
        if (!scopeAllowed) {
            return "scope-mismatch";
        }
        if (savedFingerprint == null || savedFingerprint.isBlank()) {
            return "missing-snapshot";
        }
        if (savedFingerprint.equals(currentFingerprint)) {
            return "current";
        }
        return "drifted";
    }

    private static String sourceKind(OperatorDefinition operator, boolean currentOperatorPresent) {
        if (operator == null || operator.source() == null) {
            return currentOperatorPresent ? "unknown" : "catalog-missing";
        }
        return normalizeFacet(operator.source().kind(), "unknown");
    }

    private static String operatorLibraryId(OperatorDefinition operator) {
        if (operator == null || operator.source() == null || operator.source().libraryId() == null) {
            return "";
        }
        return operator.source().libraryId().trim();
    }

    private static String loweringMode(OperatorDefinition operator) {
        if (operator == null || operator.lowering() == null) {
            return "unknown";
        }
        return normalizeFacet(operator.lowering().mode(), "native");
    }

    private static String runtimeReadinessState(OperatorDefinition operator,
                                                boolean currentOperatorPresent,
                                                boolean scopeAllowed) {
        if (!currentOperatorPresent) {
            return "CATALOG_MISSING";
        }
        if (!scopeAllowed) {
            return "SCOPE_MISMATCH";
        }
        if (operator == null || operator.runtimeReadiness() == null) {
            return "UNKNOWN";
        }
        String state = operator.runtimeReadiness().state();
        return state == null || state.isBlank()
                ? "UNKNOWN"
                : state.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeFacet(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static void increment(Map<String, Integer> counts, String value) {
        counts.merge(value, 1, Integer::sum);
    }

    private static void incrementIfPresent(Map<String, Integer> counts, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        counts.merge(value, 1, Integer::sum);
    }

    /**
     * Distinct operator dependency row.
     *
     * @param operatorRef operator reference
     * @param sourceKind source kind
     * @param operatorLibraryId owner operator library id for imported operators
     * @param loweringMode lowering mode
     * @param runtimeReadinessState current runtime readiness state
     * @param executable whether all current runtime prerequisites are present for this operator
     * @param artifactKinds supported artifact kinds from the operator contract
     * @param currentFingerprint current catalog fingerprint, when present
     * @param fingerprintState aggregate fingerprint state across using nodes
     * @param scopeAllowed whether the operator is available in the draft tenant/namespace/environment
     * @param policyViolations scope policy violations for the draft context
     * @param nodeIds draft nodes using the operator
     */
    public record OperatorDependency(
            String operatorRef,
            String sourceKind,
            String operatorLibraryId,
            String loweringMode,
            String runtimeReadinessState,
            boolean executable,
            List<String> artifactKinds,
            String currentFingerprint,
            String fingerprintState,
            boolean scopeAllowed,
            List<String> policyViolations,
            List<String> nodeIds
    ) {
        public OperatorDependency {
            operatorRef = operatorRef == null ? "" : operatorRef;
            sourceKind = sourceKind == null ? "" : sourceKind;
            operatorLibraryId = operatorLibraryId == null ? "" : operatorLibraryId;
            loweringMode = loweringMode == null ? "" : loweringMode;
            runtimeReadinessState = runtimeReadinessState == null ? "" : runtimeReadinessState;
            artifactKinds = artifactKinds == null ? List.of() : List.copyOf(artifactKinds);
            currentFingerprint = currentFingerprint == null ? "" : currentFingerprint;
            fingerprintState = fingerprintState == null ? "" : fingerprintState;
            policyViolations = policyViolations == null ? List.of() : List.copyOf(policyViolations);
            nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
        }
    }

    /**
     * Per-node dependency row.
     *
     * @param nodeId node id
     * @param label node label
     * @param operatorRef operator reference
     * @param sourceKind source kind
     * @param operatorLibraryId owner operator library id for imported operators
     * @param loweringMode lowering mode
     * @param runtimeReadinessState current runtime readiness state
     * @param executable whether this node is executable against the current catalog
     * @param savedFingerprint saved draft fingerprint snapshot
     * @param currentFingerprint current catalog fingerprint
     * @param fingerprintState node fingerprint state
     * @param bindingSourceNodes node ids referenced by input/config bindings
     * @param edgeSourceNodes node ids referenced by incoming visual edges
     * @param upstreamNodes union of binding and edge source nodes
     * @param bindingTargetNodes nodes whose input/config bindings reference this node
     * @param edgeTargetNodes outgoing visual edge targets
     * @param downstreamNodes union of binding target nodes and outgoing visual edge targets
     * @param scopeAllowed whether this node's operator is available in the draft tenant/namespace/environment
     * @param policyViolations scope policy violations for the draft context
     */
    public record NodeDependency(
            String nodeId,
            String label,
            String operatorRef,
            String sourceKind,
            String operatorLibraryId,
            String loweringMode,
            String runtimeReadinessState,
            boolean executable,
            String savedFingerprint,
            String currentFingerprint,
            String fingerprintState,
            List<String> bindingSourceNodes,
            List<String> edgeSourceNodes,
            List<String> upstreamNodes,
            List<String> bindingTargetNodes,
            List<String> edgeTargetNodes,
            List<String> downstreamNodes,
            boolean scopeAllowed,
            List<String> policyViolations
    ) {
        public NodeDependency {
            nodeId = nodeId == null ? "" : nodeId;
            label = label == null ? "" : label;
            operatorRef = operatorRef == null ? "" : operatorRef;
            sourceKind = sourceKind == null ? "" : sourceKind;
            operatorLibraryId = operatorLibraryId == null ? "" : operatorLibraryId;
            loweringMode = loweringMode == null ? "" : loweringMode;
            runtimeReadinessState = runtimeReadinessState == null ? "" : runtimeReadinessState;
            savedFingerprint = savedFingerprint == null ? "" : savedFingerprint;
            currentFingerprint = currentFingerprint == null ? "" : currentFingerprint;
            fingerprintState = fingerprintState == null ? "" : fingerprintState;
            bindingSourceNodes = bindingSourceNodes == null ? List.of() : List.copyOf(bindingSourceNodes);
            edgeSourceNodes = edgeSourceNodes == null ? List.of() : List.copyOf(edgeSourceNodes);
            upstreamNodes = upstreamNodes == null ? List.of() : List.copyOf(upstreamNodes);
            bindingTargetNodes = bindingTargetNodes == null ? List.of() : List.copyOf(bindingTargetNodes);
            edgeTargetNodes = edgeTargetNodes == null ? List.of() : List.copyOf(edgeTargetNodes);
            downstreamNodes = downstreamNodes == null ? List.of() : List.copyOf(downstreamNodes);
            policyViolations = policyViolations == null ? List.of() : List.copyOf(policyViolations);
        }
    }

    private static final class OperatorAggregate {
        private final String operatorRef;
        private final String sourceKind;
        private final String operatorLibraryId;
        private final String loweringMode;
        private final String runtimeReadinessState;
        private final boolean executable;
        private final List<String> artifactKinds;
        private final String currentFingerprint;
        private final boolean scopeAllowed;
        private final Set<String> policyViolations = new LinkedHashSet<>();
        private final Set<String> nodeIds = new LinkedHashSet<>();
        private final Set<String> fingerprintStates = new LinkedHashSet<>();

        private OperatorAggregate(String operatorRef,
                                  String sourceKind,
                                  String operatorLibraryId,
                                  String loweringMode,
                                  String runtimeReadinessState,
                                  boolean executable,
                                  List<String> artifactKinds,
                                  String currentFingerprint,
                                  boolean scopeAllowed,
                                  List<String> policyViolations) {
            this.operatorRef = operatorRef;
            this.sourceKind = sourceKind;
            this.operatorLibraryId = operatorLibraryId == null ? "" : operatorLibraryId;
            this.loweringMode = loweringMode;
            this.runtimeReadinessState = runtimeReadinessState;
            this.executable = executable;
            this.artifactKinds = artifactKinds == null ? List.of() : List.copyOf(artifactKinds);
            this.currentFingerprint = currentFingerprint == null ? "" : currentFingerprint;
            this.scopeAllowed = scopeAllowed;
            this.policyViolations.addAll(policyViolations == null ? List.of() : policyViolations);
        }

        private OperatorAggregate add(String nodeId, String fingerprintState) {
            nodeIds.add(nodeId);
            fingerprintStates.add(fingerprintState);
            return this;
        }

        private OperatorDependency toDependency() {
            return new OperatorDependency(
                    operatorRef,
                    sourceKind,
                    operatorLibraryId,
                    loweringMode,
                    runtimeReadinessState,
                    executable,
                    artifactKinds,
                    currentFingerprint,
                    aggregateFingerprintState(fingerprintStates),
                    scopeAllowed,
                    List.copyOf(policyViolations),
                    List.copyOf(nodeIds)
            );
        }
    }

    private static String aggregateFingerprintState(Set<String> states) {
        if (states.contains("catalog-missing")) {
            return "catalog-missing";
        }
        if (states.contains("scope-mismatch")) {
            return "scope-mismatch";
        }
        if (states.contains("drifted")) {
            return "drifted";
        }
        if (states.contains("missing-snapshot")) {
            return "missing-snapshot";
        }
        return states.isEmpty() ? "unknown" : "current";
    }
}
