package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaIntrospection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * @param schemaBreakingDriftCount number of nodes whose frozen operator schema snapshot is incompatible with current catalog schema
 * @param schemaCompatibleDriftCount number of nodes whose frozen operator schema snapshot changed but remains compatible
 * @param schemaCompatibilityStateCounts node counts by frozen-vs-current operator schema compatibility state
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
        int schemaBreakingDriftCount,
        int schemaCompatibleDriftCount,
        Map<String, Integer> schemaCompatibilityStateCounts,
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
        schemaCompatibilityStateCounts = schemaCompatibilityStateCounts == null
                ? Map.of()
                : new LinkedHashMap<>(schemaCompatibilityStateCounts);
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
        Map<String, Integer> schemaCompatibilityStateCounts = new LinkedHashMap<>();
        Map<String, OperatorAggregate> operatorAggregates = new LinkedHashMap<>();
        List<NodeDependency> nodeRows = new ArrayList<>();
        int missingOperators = 0;
        int scopeMismatchOperators = 0;
        int driftedFingerprints = 0;
        int missingFingerprints = 0;
        int schemaBreakingDrifts = 0;
        int schemaCompatibleDrifts = 0;
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
            SchemaCompatibilityReview schemaReview = SchemaCompatibilityReview.from(
                    node.id(),
                    snapshotForNode(draft, node),
                    catalogOperator.orElse(null),
                    currentOperatorPresent,
                    scopeAllowed
            );
            if ("breaking".equals(schemaReview.state())) {
                schemaBreakingDrifts++;
            }
            if ("compatible".equals(schemaReview.state())) {
                schemaCompatibleDrifts++;
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
            increment(schemaCompatibilityStateCounts, schemaReview.state());

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
                    schemaReview.state(),
                    schemaReview.issues(),
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
                    .add(node.id(), fingerprintState, schemaReview);
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
                schemaBreakingDrifts,
                schemaCompatibleDrifts,
                schemaCompatibilityStateCounts,
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
                0,
                0,
                Map.of(),
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
     * @param schemaCompatibilityState aggregate frozen-vs-current schema compatibility state across using nodes
     * @param schemaCompatibilityIssues schema compatibility issues found across using nodes
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
            String schemaCompatibilityState,
            List<SchemaCompatibilityIssue> schemaCompatibilityIssues,
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
            schemaCompatibilityState = schemaCompatibilityState == null ? "" : schemaCompatibilityState;
            schemaCompatibilityIssues = schemaCompatibilityIssues == null
                    ? List.of()
                    : List.copyOf(schemaCompatibilityIssues);
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
     * @param schemaCompatibilityState frozen-vs-current operator schema compatibility state
     * @param schemaCompatibilityIssues schema compatibility issues for this node
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
            String schemaCompatibilityState,
            List<SchemaCompatibilityIssue> schemaCompatibilityIssues,
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
            schemaCompatibilityState = schemaCompatibilityState == null ? "" : schemaCompatibilityState;
            schemaCompatibilityIssues = schemaCompatibilityIssues == null
                    ? List.of()
                    : List.copyOf(schemaCompatibilityIssues);
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
        private final Set<String> schemaCompatibilityStates = new LinkedHashSet<>();
        private final List<SchemaCompatibilityIssue> schemaCompatibilityIssues = new ArrayList<>();

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

        private OperatorAggregate add(String nodeId,
                                      String fingerprintState,
                                      SchemaCompatibilityReview schemaReview) {
            nodeIds.add(nodeId);
            fingerprintStates.add(fingerprintState);
            if (schemaReview != null) {
                schemaCompatibilityStates.add(schemaReview.state());
                schemaCompatibilityIssues.addAll(schemaReview.issues());
            }
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
                    aggregateSchemaCompatibilityState(schemaCompatibilityStates),
                    List.copyOf(schemaCompatibilityIssues),
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

    private static String aggregateSchemaCompatibilityState(Set<String> states) {
        if (states.contains("breaking")) {
            return "breaking";
        }
        if (states.contains("compatible")) {
            return "compatible";
        }
        if (states.contains("catalog-missing")) {
            return "catalog-missing";
        }
        if (states.contains("scope-mismatch")) {
            return "scope-mismatch";
        }
        if (states.contains("missing-snapshot")) {
            return "missing-snapshot";
        }
        return states.isEmpty() ? "unknown" : "current";
    }

    /**
     * Field-level frozen-vs-current schema compatibility issue.
     *
     * @param nodeId draft node id
     * @param surface operator contract surface: input, output, or config
     * @param portName port name when the issue belongs to a port
     * @param compatibility breaking or compatible
     * @param path schema-relative path inside the port/config schema, blank for whole-surface changes
     * @param savedType frozen snapshot type at the issue path, or port/config root type when path is blank
     * @param currentType current catalog type at the issue path, or port/config root type when path is blank
     * @param reviewHint short remediation hint for dependency and canvas review surfaces
     * @param schemaChanges bounded keyword-level schema diff rows for review surfaces
     * @param message human-readable compatibility detail
     */
    public record SchemaCompatibilityIssue(
            String nodeId,
            String surface,
            String portName,
            String compatibility,
            String path,
            String savedType,
            String currentType,
            String reviewHint,
            List<SchemaKeywordChange> schemaChanges,
            String message
    ) {
        private static final String MISSING_TYPE = "missing";
        private static final String BREAKING_REVIEW_HINT = "Review bindings before rebase or keep the frozen snapshot.";
        private static final String COMPATIBLE_REVIEW_HINT = "Review downstream expectations before rebase.";
        private static final int SCHEMA_CHANGE_LIMIT = 8;
        private static final List<String> SCHEMA_CHANGE_KEYWORDS = List.of(
                "const",
                "enum",
                "format",
                "pattern",
                "multipleOf",
                "minimum",
                "maximum",
                "exclusiveMinimum",
                "exclusiveMaximum",
                "minLength",
                "maxLength",
                "minItems",
                "maxItems",
                "minContains",
                "maxContains",
                "required",
                "properties",
                "propertyNames",
                "additionalProperties",
                "unevaluatedProperties",
                "items",
                "prefixItems",
                "contains"
        );

        public SchemaCompatibilityIssue(String nodeId,
                                        String surface,
                                        String portName,
                                        String compatibility,
                                        String message) {
            this(nodeId, surface, portName, compatibility, "", "", "", "", List.of(), message);
        }

        public SchemaCompatibilityIssue {
            nodeId = nodeId == null ? "" : nodeId;
            surface = surface == null ? "" : surface;
            portName = portName == null ? "" : portName;
            compatibility = compatibility == null ? "" : compatibility;
            path = path == null ? "" : path;
            savedType = savedType == null ? "" : savedType;
            currentType = currentType == null ? "" : currentType;
            reviewHint = reviewHint == null ? "" : reviewHint;
            schemaChanges = schemaChanges == null ? List.of() : List.copyOf(schemaChanges);
            message = message == null ? "" : message;
        }

        public SchemaCompatibilityIssue(String nodeId,
                                        String surface,
                                        String portName,
                                        String compatibility,
                                        String path,
                                        String message) {
            this(nodeId, surface, portName, compatibility, path, "", "", "", List.of(), message);
        }

        /**
         * Keyword-level frozen-vs-current schema diff row.
         *
         * @param path schema-relative path for this change
         * @param keyword JSON Schema keyword or synthetic surface such as type
         * @param savedValue compact frozen snapshot value label
         * @param currentValue compact current catalog value label
         * @param compatibility breaking or compatible
         * @param summary human-readable compact diff summary
         */
        public record SchemaKeywordChange(
                String path,
                String keyword,
                String savedValue,
                String currentValue,
                String compatibility,
                String summary
        ) {
            public SchemaKeywordChange {
                path = path == null ? "" : path;
                keyword = keyword == null ? "" : keyword;
                savedValue = savedValue == null ? "" : savedValue;
                currentValue = currentValue == null ? "" : currentValue;
                compatibility = compatibility == null ? "" : compatibility;
                summary = summary == null ? "" : summary;
            }

            private static SchemaKeywordChange of(String path,
                                                  String keyword,
                                                  Object savedValue,
                                                  Object currentValue,
                                                  String compatibility,
                                                  Map<String, Object> savedSchema,
                                                  Map<String, Object> currentSchema) {
                String savedLabel = schemaKeywordValueLabel(keyword, savedValue, savedSchema);
                String currentLabel = schemaKeywordValueLabel(keyword, currentValue, currentSchema);
                return new SchemaKeywordChange(path, keyword, savedLabel, currentLabel, compatibility,
                        "%s: %s -> %s".formatted(keyword, savedLabel, currentLabel));
            }
        }

        private static SchemaCompatibilityIssue breaking(String nodeId,
                                                         String surface,
                                                         String portName,
                                                         String message) {
            return breaking(nodeId, surface, portName, "", "", "", message);
        }

        private static SchemaCompatibilityIssue breaking(String nodeId,
                                                         String surface,
                                                         String portName,
                                                         String path,
                                                         String message) {
            return breaking(nodeId, surface, portName, path, "", "", message);
        }

        private static SchemaCompatibilityIssue breaking(String nodeId,
                                                         String surface,
                                                         String portName,
                                                         String path,
                                                         String savedType,
                                                         String currentType,
                                                         String message) {
            return breaking(nodeId, surface, portName, path, savedType, currentType, List.of(), message);
        }

        private static SchemaCompatibilityIssue breaking(String nodeId,
                                                         String surface,
                                                         String portName,
                                                         String path,
                                                         String savedType,
                                                         String currentType,
                                                         List<SchemaKeywordChange> schemaChanges,
                                                         String message) {
            return new SchemaCompatibilityIssue(nodeId, surface, portName, "breaking", path,
                    savedType, currentType, BREAKING_REVIEW_HINT, schemaChanges, message);
        }

        private static SchemaCompatibilityIssue compatible(String nodeId,
                                                           String surface,
                                                           String portName,
                                                           String message) {
            return compatible(nodeId, surface, portName, "", "", "", message);
        }

        private static SchemaCompatibilityIssue compatible(String nodeId,
                                                           String surface,
                                                           String portName,
                                                           String path,
                                                           String savedType,
                                                           String currentType,
                                                           String message) {
            return compatible(nodeId, surface, portName, path, savedType, currentType, List.of(), message);
        }

        private static SchemaCompatibilityIssue compatible(String nodeId,
                                                           String surface,
                                                           String portName,
                                                           String path,
                                                           String savedType,
                                                           String currentType,
                                                           List<SchemaKeywordChange> schemaChanges,
                                                           String message) {
            return new SchemaCompatibilityIssue(nodeId, surface, portName, "compatible", path,
                    savedType, currentType, COMPATIBLE_REVIEW_HINT, schemaChanges, message);
        }

        private static String typeLabel(com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope envelope,
                                        String path) {
            Map<String, Object> root = envelope == null ? Map.of() : envelope.schema();
            Map<String, Object> effective = path == null || path.isBlank()
                    ? root
                    : VisualSchemaIntrospection.schemaAtPath(root, path);
            return VisualSchemaCompatibility.schemaTypeLabel(effective == null ? root : effective);
        }

        private static String firstChangedPath(com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope saved,
                                               com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope current) {
            Map<String, Object> savedSchema = saved == null ? Map.of() : saved.schema();
            Map<String, Object> currentSchema = current == null ? Map.of() : current.schema();
            return firstChangedPath(savedSchema, currentSchema);
        }

        private static String firstChangedPath(Map<String, Object> savedSchema, Map<String, Object> currentSchema) {
            if (Objects.equals(savedSchema, currentSchema)) {
                return "";
            }
            Map<String, Object> savedProperties = VisualSchemaIntrospection.propertiesOf(savedSchema);
            Map<String, Object> currentProperties = VisualSchemaIntrospection.propertiesOf(currentSchema);
            Set<String> propertyNames = new LinkedHashSet<>();
            propertyNames.addAll(savedProperties.keySet());
            propertyNames.addAll(currentProperties.keySet());
            for (String name : propertyNames) {
                Map<String, Object> savedProperty = VisualSchemaIntrospection.objectSchema(savedProperties.get(name));
                Map<String, Object> currentProperty = VisualSchemaIntrospection.objectSchema(currentProperties.get(name));
                if (Objects.equals(savedProperty, currentProperty)) {
                    continue;
                }
                if (savedProperty != null && currentProperty != null) {
                    String nested = firstChangedPath(savedProperty, currentProperty);
                    return nested.isBlank() ? name : name + "." + nested;
                }
                return name;
            }
            Map<String, Object> savedItems = VisualSchemaIntrospection.objectSchema(
                    savedSchema == null ? null : savedSchema.get("items"));
            Map<String, Object> currentItems = VisualSchemaIntrospection.objectSchema(
                    currentSchema == null ? null : currentSchema.get("items"));
            if (!Objects.equals(savedItems, currentItems)) {
                return "";
            }
            return "";
        }

        private static List<SchemaKeywordChange> schemaChanges(
                com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope saved,
                com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope current,
                String path,
                String compatibility) {
            Map<String, Object> savedRoot = saved == null ? Map.of() : saved.schema();
            Map<String, Object> currentRoot = current == null ? Map.of() : current.schema();
            Map<String, Object> savedSchema = effectiveSchemaAtPath(savedRoot, path);
            Map<String, Object> currentSchema = effectiveSchemaAtPath(currentRoot, path);
            List<SchemaKeywordChange> changes = new ArrayList<>();
            String savedType = VisualSchemaCompatibility.schemaTypeLabel(savedSchema);
            String currentType = VisualSchemaCompatibility.schemaTypeLabel(currentSchema);
            if (!Objects.equals(savedType, currentType)) {
                changes.add(new SchemaKeywordChange(path, "type", savedType, currentType, compatibility,
                        "type: %s -> %s".formatted(savedType, currentType)));
            }
            for (String keyword : SCHEMA_CHANGE_KEYWORDS) {
                if (changes.size() >= SCHEMA_CHANGE_LIMIT) {
                    break;
                }
                Object savedValue = savedSchema == null ? null : savedSchema.get(keyword);
                Object currentValue = currentSchema == null ? null : currentSchema.get(keyword);
                if (!Objects.equals(savedValue, currentValue)) {
                    changes.add(SchemaKeywordChange.of(path, keyword, savedValue, currentValue,
                            compatibility, savedSchema, currentSchema));
                }
            }
            return changes.isEmpty() ? List.of() : List.copyOf(changes);
        }

        private static Map<String, Object> effectiveSchemaAtPath(Map<String, Object> root, String path) {
            if (path == null || path.isBlank()) {
                return root == null ? Map.of() : root;
            }
            Map<String, Object> schema = VisualSchemaIntrospection.schemaAtPath(root, path);
            return schema == null ? root : schema;
        }

        private static String schemaKeywordValueLabel(String keyword,
                                                      Object value,
                                                      Map<String, Object> schema) {
            if (value == null) {
                return MISSING_TYPE;
            }
            if ("type".equals(keyword) && schema != null) {
                return VisualSchemaCompatibility.schemaTypeLabel(schema);
            }
            if ("properties".equals(keyword) && value instanceof Map<?, ?> map) {
                return compactCollectionLabel(map.keySet());
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> schemaValue = VisualSchemaIntrospection.objectSchema(map);
                if (schemaValue != null) {
                    return VisualSchemaCompatibility.schemaTypeLabel(schemaValue);
                }
            }
            if (value instanceof List<?> list) {
                return compactCollectionLabel(list);
            }
            return compactSchemaValueLabel(String.valueOf(value));
        }

        private static String compactCollectionLabel(Iterable<?> values) {
            List<String> labels = new ArrayList<>();
            for (Object value : values) {
                if (labels.size() >= 6) {
                    labels.add("...");
                    break;
                }
                if (value instanceof Map<?, ?> map) {
                    Map<String, Object> schemaValue = VisualSchemaIntrospection.objectSchema(map);
                    labels.add(schemaValue == null ? "object" : VisualSchemaCompatibility.schemaTypeLabel(schemaValue));
                } else {
                    labels.add(String.valueOf(value));
                }
            }
            return "[" + String.join(", ", labels) + "]";
        }

        private static String compactSchemaValueLabel(String value) {
            String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
            return compact.length() <= 96 ? compact : compact.substring(0, 93) + "...";
        }
    }

    private record SchemaCompatibilityReview(
            String state,
            List<SchemaCompatibilityIssue> issues
    ) {
        private SchemaCompatibilityReview {
            state = state == null || state.isBlank() ? "current" : state;
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        private static SchemaCompatibilityReview from(String nodeId,
                                                      OperatorDefinition snapshot,
                                                      OperatorDefinition current,
                                                      boolean currentOperatorPresent,
                                                      boolean scopeAllowed) {
            if (!currentOperatorPresent) {
                return new SchemaCompatibilityReview("catalog-missing", List.of());
            }
            if (!scopeAllowed) {
                return new SchemaCompatibilityReview("scope-mismatch", List.of());
            }
            if (snapshot == null) {
                return new SchemaCompatibilityReview("missing-snapshot", List.of());
            }
            List<SchemaCompatibilityIssue> issues = new ArrayList<>();
            compareInputPorts(nodeId, snapshot, current, issues);
            compareOutputPorts(nodeId, snapshot, current, issues);
            compareConfigSchema(nodeId, snapshot, current, issues);
            if (issues.stream().anyMatch(issue -> "breaking".equals(issue.compatibility()))) {
                return new SchemaCompatibilityReview("breaking", issues);
            }
            if (!issues.isEmpty()) {
                return new SchemaCompatibilityReview("compatible", issues);
            }
            return new SchemaCompatibilityReview("current", List.of());
        }

        private static void compareInputPorts(String nodeId,
                                              OperatorDefinition snapshot,
                                              OperatorDefinition current,
                                              List<SchemaCompatibilityIssue> issues) {
            Map<String, OperatorDefinition.Port> savedPorts = inputPorts(snapshot);
            Map<String, OperatorDefinition.Port> currentPorts = inputPorts(current);
            savedPorts.forEach((name, savedPort) -> {
                OperatorDefinition.Port currentPort = currentPorts.get(name);
                if (currentPort == null) {
                    issues.add(SchemaCompatibilityIssue.breaking(nodeId, "input", name,
                            "",
                            SchemaCompatibilityIssue.typeLabel(savedPort.schema(), ""),
                            SchemaCompatibilityIssue.MISSING_TYPE,
                            "Input port '%s' was removed from current operator contract.".formatted(name)));
                    return;
                }
                if (!savedPort.required() && currentPort.required()) {
                    issues.add(SchemaCompatibilityIssue.breaking(nodeId, "input", name,
                            "",
                            SchemaCompatibilityIssue.typeLabel(savedPort.schema(), ""),
                            SchemaCompatibilityIssue.typeLabel(currentPort.schema(), ""),
                            "Input port '%s' became required in current operator contract.".formatted(name)));
                } else if (savedPort.required() && !currentPort.required()) {
                    issues.add(SchemaCompatibilityIssue.compatible(nodeId, "input", name,
                            "",
                            SchemaCompatibilityIssue.typeLabel(savedPort.schema(), ""),
                            SchemaCompatibilityIssue.typeLabel(currentPort.schema(), ""),
                            "Input port '%s' became optional in current operator contract.".formatted(name)));
                }
                compareSchemas(nodeId, "input", name, savedPort.schema(), currentPort.schema(),
                        savedPort.schema(), currentPort.schema(),
                        "Frozen input port '%s' schema can still feed current input schema.".formatted(name),
                        issues);
            });
            currentPorts.forEach((name, currentPort) -> {
                if (savedPorts.containsKey(name)) {
                    return;
                }
                if (currentPort.required()) {
                    issues.add(SchemaCompatibilityIssue.breaking(nodeId, "input", name,
                            "",
                            SchemaCompatibilityIssue.MISSING_TYPE,
                            SchemaCompatibilityIssue.typeLabel(currentPort.schema(), ""),
                            "Required input port '%s' was added to current operator contract.".formatted(name)));
                } else {
                    issues.add(SchemaCompatibilityIssue.compatible(nodeId, "input", name,
                            "",
                            SchemaCompatibilityIssue.MISSING_TYPE,
                            SchemaCompatibilityIssue.typeLabel(currentPort.schema(), ""),
                            "Optional input port '%s' was added to current operator contract.".formatted(name)));
                }
            });
        }

        private static void compareOutputPorts(String nodeId,
                                               OperatorDefinition snapshot,
                                               OperatorDefinition current,
                                               List<SchemaCompatibilityIssue> issues) {
            Map<String, OperatorDefinition.Port> savedPorts = outputPorts(snapshot);
            Map<String, OperatorDefinition.Port> currentPorts = outputPorts(current);
            savedPorts.forEach((name, savedPort) -> {
                OperatorDefinition.Port currentPort = currentPorts.get(name);
                if (currentPort == null) {
                    issues.add(SchemaCompatibilityIssue.breaking(nodeId, "output", name,
                            "",
                            SchemaCompatibilityIssue.typeLabel(savedPort.schema(), ""),
                            SchemaCompatibilityIssue.MISSING_TYPE,
                            "Output port '%s' was removed from current operator contract.".formatted(name)));
                    return;
                }
                compareSchemas(nodeId, "output", name, currentPort.schema(), savedPort.schema(),
                        savedPort.schema(), currentPort.schema(),
                        "Current output port '%s' schema can still satisfy frozen output schema.".formatted(name),
                        issues);
            });
            currentPorts.forEach((name, currentPort) -> {
                if (!savedPorts.containsKey(name)) {
                    issues.add(SchemaCompatibilityIssue.compatible(nodeId, "output", name,
                            "",
                            SchemaCompatibilityIssue.MISSING_TYPE,
                            SchemaCompatibilityIssue.typeLabel(currentPort.schema(), ""),
                            "Output port '%s' was added to current operator contract.".formatted(name)));
                }
            });
        }

        private static void compareConfigSchema(String nodeId,
                                                OperatorDefinition snapshot,
                                                OperatorDefinition current,
                                                List<SchemaCompatibilityIssue> issues) {
            compareSchemas(nodeId, "config", "", snapshot.configSchema(), current.configSchema(),
                    snapshot.configSchema(), current.configSchema(),
                    "Frozen config schema can still feed current config schema.", issues);
        }

        private static void compareSchemas(String nodeId,
                                           String surface,
                                           String portName,
                                           com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope source,
                                           com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope target,
                                           com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope saved,
                                           com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope current,
                                           String compatibleMessage,
                                           List<SchemaCompatibilityIssue> issues) {
            Map<String, Object> sourceSchema = source == null ? Map.of() : source.schema();
            Map<String, Object> targetSchema = target == null ? Map.of() : target.schema();
            if (Objects.equals(sourceSchema, targetSchema)) {
                return;
            }
            Optional<VisualSchemaCompatibility.SchemaCompatibilityIssue> issue =
                    VisualSchemaCompatibility.schemaCompatibilityIssueDetail(sourceSchema, targetSchema);
            if (issue.isPresent()) {
                String path = issue.get().path();
                issues.add(SchemaCompatibilityIssue.breaking(nodeId, surface, portName,
                        path,
                        SchemaCompatibilityIssue.typeLabel(saved, path),
                        SchemaCompatibilityIssue.typeLabel(current, path),
                        SchemaCompatibilityIssue.schemaChanges(saved, current, path, "breaking"),
                        issue.get().message()));
                return;
            }
            String compatiblePath = SchemaCompatibilityIssue.firstChangedPath(saved, current);
            issues.add(SchemaCompatibilityIssue.compatible(nodeId, surface, portName,
                    compatiblePath,
                    SchemaCompatibilityIssue.typeLabel(saved, compatiblePath),
                    SchemaCompatibilityIssue.typeLabel(current, compatiblePath),
                    SchemaCompatibilityIssue.schemaChanges(saved, current, compatiblePath, "compatible"),
                    compatibleMessage));
        }

        private static Map<String, OperatorDefinition.Port> inputPorts(OperatorDefinition operator) {
            if (operator == null || operator.ports() == null) {
                return Map.of();
            }
            return portsByName(operator.ports().inputs());
        }

        private static Map<String, OperatorDefinition.Port> outputPorts(OperatorDefinition operator) {
            if (operator == null || operator.ports() == null) {
                return Map.of();
            }
            return portsByName(operator.ports().outputs());
        }

        private static Map<String, OperatorDefinition.Port> portsByName(List<OperatorDefinition.Port> ports) {
            Map<String, OperatorDefinition.Port> byName = new LinkedHashMap<>();
            for (OperatorDefinition.Port port : ports == null ? List.<OperatorDefinition.Port>of() : ports) {
                if (port != null) {
                    byName.putIfAbsent(port.name(), port);
                }
            }
            return byName;
        }
    }
}
