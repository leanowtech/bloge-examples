package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.simulation.NodeFixture;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationRequest;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Compiles basic Scenario dependency behavior into the existing transient visual-run protocol.
 *
 * <p>Only exact node-level REAL and RETURN behaviors are losslessly representable by
 * {@link GraphDraft.NodeFixture}. ERROR, DELAY, TIMEOUT, REPLAY, OBSERVE, MUST_NOT_CALL,
 * transport-boundary behavior, and runtime selector coordinates are rejected rather than silently
 * downgraded. Those behaviors must use the governed testing control plane.</p>
 */
@Service
public class ScenarioSimulationCompiler {

    private static final Set<ScenarioDraftSet.BehaviorKind> BASIC_BEHAVIORS = Set.of(
            ScenarioDraftSet.BehaviorKind.REAL,
            ScenarioDraftSet.BehaviorKind.RETURN
    );

    private final ScenarioValidationService validator;

    /**
     * @param validator exact-input Scenario validator
     */
    public ScenarioSimulationCompiler(ScenarioValidationService validator) {
        this.validator = validator;
    }

    /**
     * Compiles one Scenario into an existing visual graph-run request.
     *
     * @param graphDraft current graph draft
     * @param contract current Contract projection
     * @param draftSet Scenario authoring asset
     * @param scenarioId scenario to compile
     * @return transient plan or fail-closed diagnostics
     */
    public ScenarioSimulationPlan compile(GraphDraft graphDraft,
                                          ContractDraft contract,
                                          ScenarioDraftSet draftSet,
                                          String scenarioId) {
        ScenarioValidationReport validation = validator.validate(draftSet, contract, graphDraft);
        List<VisualDiagnostic> diagnostics = new ArrayList<>(validation.diagnostics());
        String normalizedScenarioId = scenarioId == null ? "" : scenarioId.trim();
        Optional<ScenarioDraftSet.ScenarioDraft> selected = draftSet == null
                ? Optional.empty()
                : draftSet.scenarios().stream()
                .filter(scenario -> scenario.scenarioId().equals(normalizedScenarioId))
                .findFirst();
        if (selected.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.scenario.compile.scenarioMissing",
                    "Scenario '%s' does not exist in the draft set.".formatted(normalizedScenarioId),
                    "/scenarioId"));
            return blocked(draftSet, normalizedScenarioId, diagnostics);
        }
        if (!validation.valid()) {
            return blocked(draftSet, normalizedScenarioId, diagnostics);
        }
        if (graphDraft == null) {
            diagnostics.add(VisualDiagnostic.error("visual.scenario.compile.graphDraftMissing",
                    "A current GraphDraft is required to resolve transient Scenario fixtures.",
                    "/graphDraft"));
            return blocked(draftSet, normalizedScenarioId, diagnostics);
        }

        Map<String, GraphDraft.NodeFixture> persistedFixtures = new LinkedHashMap<>(graphDraft.nodeFixtures());
        Map<String, NodeFixture> requestFixtures = new LinkedHashMap<>();
        Set<String> controlledNodes = new java.util.HashSet<>();
        for (int index = 0; index < selected.get().dependencies().size(); index++) {
            ScenarioDraftSet.DependencyBehaviorDraft dependency = selected.get().dependencies().get(index);
            String path = "/scenarios/" + normalizedScenarioId + "/dependencies/" + index;
            if (draftSet.target().kind() == ContractDraft.TargetKind.GRAPH
                    && !dependency.selector().operatorRef().isBlank()) {
                diagnostics.add(VisualDiagnostic.error("visual.scenario.compile.operatorSelectorUnsupported",
                        "Graph-target Scenarios must select dependencies by nodeId; operatorRef selectors are not supported.",
                        path + "/selector/operatorRef"));
                continue;
            }
            boolean basicNodeBehavior = BASIC_BEHAVIORS.contains(dependency.behavior().kind())
                    && dependency.behavior().boundary() == ScenarioDraftSet.BehaviorBoundary.NODE;
            if (!basicNodeBehavior) {
                diagnostics.add(VisualDiagnostic.error("visual.scenario.compile.governedBehaviorRequired",
                        "Dependency behavior '%s' cannot be represented by transient NodeFixture; use governed Scenario execution."
                                .formatted(dependency.behavior().kind()),
                        path));
                continue;
            }
            Optional<String> resolvedNodeId;
            if (draftSet.target().kind() == ContractDraft.TargetKind.OPERATOR
                    && !dependency.selector().operatorRef().isBlank()) {
                resolvedNodeId = resolveNodeId(
                        graphDraft, draftSet.target(), dependency.selector(), path, diagnostics);
            } else if (losslesslyRepresentable(dependency)) {
                resolvedNodeId = resolveNodeId(
                        graphDraft, draftSet.target(), dependency.selector(), path, diagnostics);
            } else {
                diagnostics.add(VisualDiagnostic.error("visual.scenario.compile.governedBehaviorRequired",
                        "Dependency selector cannot be represented by transient NodeFixture; use governed Scenario execution.",
                        path));
                continue;
            }
            if (resolvedNodeId.isEmpty()) {
                continue;
            }
            String nodeId = resolvedNodeId.get();
            if (!controlledNodes.add(nodeId)) {
                diagnostics.add(VisualDiagnostic.error("visual.scenario.compile.nodeBehaviorDuplicate",
                        "Transient Scenario has more than one behavior for node '%s'.".formatted(nodeId),
                        path + "/selector/nodeId"));
                continue;
            }
            if (dependency.behavior().kind() == ScenarioDraftSet.BehaviorKind.REAL) {
                persistedFixtures.remove(nodeId);
            } else {
                requestFixtures.put(nodeId, new NodeFixture(
                        dependency.behavior().output(),
                        dependency.behavior().expectedInput()
                ));
            }
        }
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return blocked(draftSet, normalizedScenarioId, diagnostics);
        }

        GraphDraft executableDraft = graphDraft.withNodeFixtures(persistedFixtures);
        Object givenInput = selected.get().given().input();
        if (!(givenInput instanceof Map<?, ?> rawInput)) {
            diagnostics.add(VisualDiagnostic.error("visual.scenario.compile.graphInputNotObject",
                    "Graph-target Scenario input must be a JSON object.", "/given/input"));
            return blocked(draftSet, normalizedScenarioId, diagnostics);
        }
        Map<String, Object> context = new LinkedHashMap<>();
        rawInput.forEach((key, value) -> context.put(String.valueOf(key), value));
        VisualGraphSimulationRequest request = new VisualGraphSimulationRequest(
                executableDraft,
                context,
                graphDraft.output().nodeId(),
                requestFixtures
        );
        return new ScenarioSimulationPlan(
                ScenarioSimulationPlan.SCHEMA_VERSION,
                true,
                normalizedScenarioId,
                draftSet.target().fingerprint(),
                draftSet.contractFingerprint(),
                request,
                selected.get().then().assertions(),
                diagnostics
        );
    }

    /**
     * Checks whether a dependency has the only behavior shape representable by a transient fixture.
     * Selector identity is resolved separately so an operator-wide selector cannot be mistaken for
     * a node id before its exact target and graph-node cardinality have been checked.
     */
    private static boolean losslesslyRepresentable(ScenarioDraftSet.DependencyBehaviorDraft dependency) {
        ScenarioDraftSet.DependencySelector selector = dependency.selector();
        ScenarioDraftSet.DependencyBehavior behavior = dependency.behavior();
        return BASIC_BEHAVIORS.contains(behavior.kind())
                && behavior.boundary() == ScenarioDraftSet.BehaviorBoundary.NODE
                && !selector.nodeId().isBlank()
                && selector.operatorRef().isBlank()
                && selector.resourceRef().isBlank()
                && selector.functionRef().isBlank()
                && selector.attempts().isEmpty()
                && selector.occurrences().isEmpty()
                && selector.correlationKey().isBlank()
                && selector.pathEquals().isEmpty();
    }

    /**
     * Resolves a transient fixture key from a dependency selector.
     *
     * <p>Graph targets retain their existing exact nodeId path. Top-level operator targets may use
     * operatorRef only when it exactly equals the target id, has no other selector coordinates, and
     * resolves to one—and only one—GraphDraft node. Every other outcome is diagnostic and therefore
     * blocks compilation; in particular, an unresolved operator selector is never executed as REAL.</p>
     *
     * @param graphDraft current graph whose nodes provide the executable fixture keys
     * @param target exact Scenario target
     * @param selector dependency selector
     * @param path dependency JSON pointer
     * @param diagnostics diagnostics to append on a failed resolution
     * @return resolved node id, or empty when the selector is not executable
     */
    private static Optional<String> resolveNodeId(
            GraphDraft graphDraft,
            ContractDraft.Target target,
            ScenarioDraftSet.DependencySelector selector,
            String path,
            List<VisualDiagnostic> diagnostics) {
        if (selector.operatorRef().isBlank()) {
            return Optional.of(selector.nodeId());
        }
        if (target.kind() != ContractDraft.TargetKind.OPERATOR) {
            diagnostics.add(VisualDiagnostic.error("visual.scenario.compile.operatorSelectorUnsupported",
                    "operatorRef selectors are supported only for top-level operator-target Scenarios.",
                    path + "/selector/operatorRef"));
            return Optional.empty();
        }
        if (!target.id().equals(selector.operatorRef())) {
            diagnostics.add(VisualDiagnostic.error("visual.scenario.compile.operatorSelectorTargetMismatch",
                    "Operator selector '%s' does not exactly match Scenario target '%s'."
                            .formatted(selector.operatorRef(), target.id()),
                    path + "/selector/operatorRef"));
            return Optional.empty();
        }
        if (!selector.nodeId().isBlank() || !selector.graphPath().isBlank()
                || !selector.resourceRef().isBlank() || !selector.functionRef().isBlank()
                || !selector.attempts().isEmpty() || !selector.occurrences().isEmpty()
                || !selector.correlationKey().isBlank() || !selector.pathEquals().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.scenario.compile.operatorSelectorCoordinatesUnsupported",
                    "Top-level operatorRef selectors require nodeId and all advanced selector coordinates to be empty.",
                    path + "/selector"));
            return Optional.empty();
        }
        List<GraphDraft.DraftNode> matches = graphDraft.nodes().stream()
                .filter(node -> selector.operatorRef().equals(node.operatorRef()))
                .toList();
        if (matches.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.scenario.compile.operatorSelectorNodeMissing",
                    "No GraphDraft node matches operatorRef '%s'.".formatted(selector.operatorRef()),
                    path + "/selector/operatorRef"));
            return Optional.empty();
        }
        if (matches.size() > 1) {
            diagnostics.add(VisualDiagnostic.error("visual.scenario.compile.operatorSelectorNodeAmbiguous",
                    "operatorRef '%s' matches %d GraphDraft nodes; exactly one is required."
                            .formatted(selector.operatorRef(), matches.size()),
                    path + "/selector/operatorRef"));
            return Optional.empty();
        }
        return Optional.of(matches.getFirst().id());
    }

    private static ScenarioSimulationPlan blocked(ScenarioDraftSet draftSet,
                                                  String scenarioId,
                                                  List<VisualDiagnostic> diagnostics) {
        return new ScenarioSimulationPlan(
                ScenarioSimulationPlan.SCHEMA_VERSION,
                false,
                scenarioId,
                draftSet == null ? "" : draftSet.target().fingerprint(),
                draftSet == null ? "" : draftSet.contractFingerprint(),
                null,
                List.of(),
                diagnostics
        );
    }
}
