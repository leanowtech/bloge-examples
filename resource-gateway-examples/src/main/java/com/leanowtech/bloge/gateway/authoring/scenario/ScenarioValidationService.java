package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates Scenario draft sets against exact graph or operator Contract inputs.
 *
 * <p>The validator is deliberately fail closed: missing target fingerprints, contract drift,
 * duplicate ids, unknown node selectors, invalid inputs, and incomplete behavior declarations are
 * all blocking. A caller can therefore use a VALID report as the prerequisite for either the
 * transient compiler or the later governed publication compiler.</p>
 */
@Service
public class ScenarioValidationService {

    /** Maximum mutable authoring corpus; governed publication and run shards remain smaller. */
    public static final int MAX_SCENARIOS = 10_000;

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper application mapper used for canonical Contract fingerprinting
     */
    public ScenarioValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Validates an entire Scenario draft set.
     *
     * @param draftSet mutable Scenario authoring asset
     * @param contract current Contract projection
     * @param graphDraft current graph draft when the target kind is GRAPH
     * @return exact-input validation report
     */
    public ScenarioValidationReport validate(ScenarioDraftSet draftSet,
                                             ContractDraft contract,
                                             GraphDraft graphDraft) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (draftSet == null) {
            diagnostics.add(error("visual.scenario.draftSet.missing",
                    "Scenario draft set is required.", "/"));
            return report(null, contract, diagnostics, ScenarioValidationReport.Status.INVALID);
        }
        if (!ScenarioDraftSet.SCHEMA_VERSION.equals(draftSet.schemaVersion())) {
            diagnostics.add(error("visual.scenario.schemaVersion.unsupported",
                    "Unsupported Scenario draft-set schemaVersion '%s'."
                            .formatted(draftSet.schemaVersion()),
                    "/schemaVersion"));
        }
        validateScope(draftSet, diagnostics);
        validateTarget(draftSet, contract, graphDraft, diagnostics);
        validateContractFingerprint(draftSet, contract, diagnostics);
        validateScenarios(draftSet, contract, graphDraft, diagnostics);
        ScenarioValidationReport.Status status = diagnostics.stream().anyMatch(VisualDiagnostic::error)
                ? ScenarioValidationReport.Status.INVALID
                : ScenarioValidationReport.Status.VALID;
        return report(draftSet, contract, diagnostics, status);
    }

    private static void validateScope(ScenarioDraftSet draftSet, List<VisualDiagnostic> diagnostics) {
        ScenarioDraftSet.EnterpriseScope scope = draftSet.scope();
        if (scope.tenantId().isBlank()) {
            diagnostics.add(error("visual.scenario.scope.tenantMissing",
                    "Tenant scope is required before Scenario persistence or execution.", "/scope/tenantId"));
        }
        if (scope.organizationId().isBlank()) {
            diagnostics.add(error("visual.scenario.scope.organizationMissing",
                    "Organization scope is required before Scenario persistence or execution.",
                    "/scope/organizationId"));
        }
        if (scope.projectId().isBlank()) {
            diagnostics.add(error("visual.scenario.scope.projectMissing",
                    "Project scope is required before Scenario persistence or execution.", "/scope/projectId"));
        }
        if (scope.environment().isBlank()) {
            diagnostics.add(error("visual.scenario.scope.environmentMissing",
                    "Environment scope is required before Scenario persistence or execution.",
                    "/scope/environment"));
        }
        if (scope.region().isBlank()) {
            diagnostics.add(error("visual.scenario.scope.regionMissing",
                    "Region scope is required before Scenario persistence or execution.",
                    "/scope/region"));
        }
    }

    private static void validateTarget(ScenarioDraftSet draftSet,
                                       ContractDraft contract,
                                       GraphDraft graphDraft,
                                       List<VisualDiagnostic> diagnostics) {
        ContractDraft.Target target = draftSet.target();
        if (target.id().isBlank()) {
            diagnostics.add(error("visual.scenario.target.idMissing",
                    "Exact target id is required.", "/target/id"));
        }
        if (target.fingerprint().isBlank()) {
            diagnostics.add(error("visual.scenario.target.fingerprintMissing",
                    "Exact target fingerprint is required.", "/target/fingerprint"));
        }
        if (contract != null && !target.equals(contract.target())) {
            diagnostics.add(error("visual.scenario.target.contractMismatch",
                    "Scenario target does not match the current Contract target.", "/target"));
        }
        if (target.kind() == ContractDraft.TargetKind.GRAPH && graphDraft == null) {
            diagnostics.add(error("visual.scenario.target.graphMissing",
                    "Current GraphDraft is required for a graph-target Scenario.", "/target"));
        }
        if (target.kind() == ContractDraft.TargetKind.GRAPH && graphDraft != null) {
            String graphTargetId = graphDraft.draftId().isBlank() ? graphDraft.graphName() : graphDraft.draftId();
            if (!target.id().equals(graphTargetId)) {
                diagnostics.add(error("visual.scenario.target.graphIdMismatch",
                        "Scenario target '%s' does not match GraphDraft '%s'."
                                .formatted(target.id(), graphTargetId),
                        "/target/id"));
            }
            if (target.revision() > 0 && target.revision() != graphDraft.revision()) {
                diagnostics.add(error("visual.scenario.target.revisionStale",
                        "Scenario target revision %d does not match GraphDraft revision %d."
                                .formatted(target.revision(), graphDraft.revision()),
                        "/target/revision"));
            }
        }
    }

    private void validateContractFingerprint(ScenarioDraftSet draftSet,
                                             ContractDraft contract,
                                             List<VisualDiagnostic> diagnostics) {
        if (contract == null) {
            diagnostics.add(error("visual.scenario.contract.missing",
                    "Current Contract projection is required.", "/contractFingerprint"));
            return;
        }
        String currentFingerprint = contract.fingerprint(objectMapper);
        if (draftSet.contractFingerprint().isBlank()) {
            diagnostics.add(error("visual.scenario.contract.fingerprintMissing",
                    "Exact Contract fingerprint is required.", "/contractFingerprint"));
        } else if (!draftSet.contractFingerprint().equals(currentFingerprint)) {
            diagnostics.add(error("visual.scenario.contract.stale",
                    "Scenario Contract fingerprint is stale.", "/contractFingerprint"));
        }
    }

    private static void validateScenarios(ScenarioDraftSet draftSet,
                                          ContractDraft contract,
                                          GraphDraft graphDraft,
                                          List<VisualDiagnostic> diagnostics) {
        if (draftSet.scenarios().isEmpty()) {
            diagnostics.add(error("visual.scenario.scenarios.empty",
                    "At least one Scenario is required.", "/scenarios"));
            return;
        }
        if (draftSet.scenarios().size() > MAX_SCENARIOS) {
            diagnostics.add(error("visual.scenario.scenarios.limitExceeded",
                    "A Scenario authoring corpus may contain at most %d cases."
                            .formatted(MAX_SCENARIOS), "/scenarios"));
            return;
        }
        Set<String> scenarioIds = new HashSet<>();
        for (int index = 0; index < draftSet.scenarios().size(); index++) {
            ScenarioDraftSet.ScenarioDraft scenario = draftSet.scenarios().get(index);
            String base = "/scenarios/" + index;
            if (scenario.scenarioId().isBlank()) {
                diagnostics.add(error("visual.scenario.idMissing",
                        "Scenario id is required.", base + "/scenarioId"));
            } else if (!scenarioIds.add(scenario.scenarioId())) {
                diagnostics.add(error("visual.scenario.idDuplicate",
                        "Scenario id '%s' is duplicated.".formatted(scenario.scenarioId()),
                        base + "/scenarioId"));
            }
            if (scenario.name().isBlank()) {
                diagnostics.add(error("visual.scenario.nameMissing",
                        "Scenario name is required.", base + "/name"));
            }
            if (contract != null) {
                VisualSchemaValidator.validateValue(contract.inputSchema(), scenario.given().input(), base + "/given/input")
                        .forEach(diagnostics::add);
            }
            validateDependencies(
                    scenario, draftSet.target().kind(), graphDraft, base, diagnostics);
            validateAssertions(
                    scenario, draftSet.target().kind(), graphDraft, base, diagnostics);
        }
    }

    private static void validateDependencies(ScenarioDraftSet.ScenarioDraft scenario,
                                             ContractDraft.TargetKind targetKind,
                                             GraphDraft graphDraft,
                                             String base,
                                             List<VisualDiagnostic> diagnostics) {
        Set<String> dependencyIds = new HashSet<>();
        for (int index = 0; index < scenario.dependencies().size(); index++) {
            ScenarioDraftSet.DependencyBehaviorDraft dependency = scenario.dependencies().get(index);
            String path = base + "/dependencies/" + index;
            if (dependency.dependencyId().isBlank()) {
                diagnostics.add(error("visual.scenario.dependency.idMissing",
                        "Dependency behavior id is required.", path + "/dependencyId"));
            } else if (!dependencyIds.add(dependency.dependencyId())) {
                diagnostics.add(error("visual.scenario.dependency.idDuplicate",
                        "Dependency behavior id '%s' is duplicated.".formatted(dependency.dependencyId()),
                        path + "/dependencyId"));
            }
            ScenarioDraftSet.DependencySelector selector = dependency.selector();
            if (selector.nodeId().isBlank() && selector.operatorRef().isBlank()
                    && selector.resourceRef().isBlank() && selector.functionRef().isBlank()) {
                diagnostics.add(error("visual.scenario.dependency.selectorMissing",
                        "Dependency behavior requires a node, operator, resource, or function selector.",
                        path + "/selector"));
            }
            if (!selector.nodeId().isBlank() && graphDraft != null
                    && graphDraft.nodes().stream().noneMatch(node -> node.id().equals(selector.nodeId()))) {
                diagnostics.add(error("visual.scenario.dependency.nodeUnknown",
                        "Dependency node '%s' does not exist in the GraphDraft.".formatted(selector.nodeId()),
                        path + "/selector/nodeId"));
            }
            if (!selector.nodeId().isBlank()
                    && targetKind == ContractDraft.TargetKind.OPERATOR) {
                diagnostics.add(error("visual.scenario.dependency.nodeSelectorUnsupported",
                        "Operator-target Scenarios cannot select graph node dependencies.",
                        path + "/selector/nodeId"));
            }
            validateSelector(selector, path, diagnostics);
            validateBehavior(dependency, path, diagnostics);
            if (!Set.of("STRICT", "WAIVED").contains(dependency.schemaCheck().mode())) {
                diagnostics.add(error("visual.scenario.dependency.schemaCheckModeInvalid",
                        "Schema-check mode must be STRICT or WAIVED.", path + "/schemaCheck/mode"));
            } else if ("WAIVED".equals(dependency.schemaCheck().mode())
                    && dependency.schemaCheck().waiverReason().isBlank()) {
                diagnostics.add(error("visual.scenario.dependency.waiverReasonMissing",
                        "Schema-check waiver requires a reason.", path + "/schemaCheck/waiverReason"));
            }
        }
    }

    private static void validateSelector(ScenarioDraftSet.DependencySelector selector,
                                         String path,
                                         List<VisualDiagnostic> diagnostics) {
        if (!strictlyIncreasingPositive(selector.attempts())) {
            diagnostics.add(error("visual.scenario.dependency.attemptsInvalid",
                    "Attempt selectors must be strictly increasing positive integers.",
                    path + "/selector/attempts"));
        }
        if (!strictlyIncreasingPositive(selector.occurrences())) {
            diagnostics.add(error("visual.scenario.dependency.occurrencesInvalid",
                    "Occurrence selectors must be strictly increasing positive integers.",
                    path + "/selector/occurrences"));
        }
        selector.pathEquals().keySet().stream()
                .filter(pointer -> !pointer.isEmpty() && !pointer.startsWith("/"))
                .findFirst()
                .ifPresent(pointer -> diagnostics.add(error(
                        "visual.scenario.dependency.matchPathInvalid",
                        "Input match paths must use JSON Pointer syntax.",
                        path + "/selector/pathEquals")));
    }

    private static void validateBehavior(ScenarioDraftSet.DependencyBehaviorDraft dependency,
                                         String path,
                                         List<VisualDiagnostic> diagnostics) {
        ScenarioDraftSet.DependencyBehavior behavior = dependency.behavior();
        if (behavior.kind() == ScenarioDraftSet.BehaviorKind.ERROR && behavior.errorCode().isBlank()) {
            diagnostics.add(error("visual.scenario.behavior.errorCodeMissing",
                    "ERROR behavior requires a stable errorCode.", path + "/behavior/errorCode"));
        }
        if ((behavior.kind() == ScenarioDraftSet.BehaviorKind.DELAY
                || behavior.kind() == ScenarioDraftSet.BehaviorKind.TIMEOUT)
                && (behavior.after() == null || behavior.after().isZero() || behavior.after().isNegative())) {
            diagnostics.add(error("visual.scenario.behavior.durationMissing",
                    "%s behavior requires a positive deterministic duration."
                            .formatted(behavior.kind()),
                    path + "/behavior/after"));
        }
        if (behavior.kind() == ScenarioDraftSet.BehaviorKind.REPLAY && behavior.replayRef().isBlank()) {
            diagnostics.add(error("visual.scenario.behavior.replayRefMissing",
                    "REPLAY behavior requires an exact governed replay reference.",
                    path + "/behavior/replayRef"));
        }
        if (behavior.boundary() == ScenarioDraftSet.BehaviorBoundary.TRANSPORT
                && behavior.kind() == ScenarioDraftSet.BehaviorKind.RETURN
                && behavior.statusCode() == null) {
            diagnostics.add(error("visual.scenario.behavior.statusCodeMissing",
                    "Transport RETURN behavior requires a protocol statusCode.",
                    path + "/behavior/statusCode"));
        }
        ScenarioDraftSet.Consumption consumption = dependency.consumption();
        if (consumption.maxUses() > 0 && consumption.minUses() > consumption.maxUses()) {
            diagnostics.add(error("visual.scenario.dependency.consumptionInvalid",
                    "Dependency minUses must not exceed maxUses.", path + "/consumption"));
        }
        if (!Set.of("FAIL", "FALLBACK_TO_REAL").contains(consumption.onExhausted())) {
            diagnostics.add(error("visual.scenario.dependency.onExhaustedInvalid",
                    "onExhausted must be FAIL or FALLBACK_TO_REAL.",
                    path + "/consumption/onExhausted"));
        }
        if (!Set.of("FAIL", "WARN", "ALLOW_REAL").contains(consumption.onUnmatched())) {
            diagnostics.add(error("visual.scenario.dependency.onUnmatchedInvalid",
                    "onUnmatched must be FAIL, WARN, or ALLOW_REAL.",
                    path + "/consumption/onUnmatched"));
        }
    }

    private static void validateAssertions(ScenarioDraftSet.ScenarioDraft scenario,
                                           ContractDraft.TargetKind targetKind,
                                           GraphDraft graphDraft,
                                           String base,
                                           List<VisualDiagnostic> diagnostics) {
        Set<String> assertionIds = new HashSet<>();
        for (int index = 0; index < scenario.then().assertions().size(); index++) {
            ScenarioDraftSet.AssertionDraft assertion = scenario.then().assertions().get(index);
            String path = base + "/then/assertions/" + index;
            if (assertion.assertionId().isBlank()) {
                diagnostics.add(error("visual.scenario.assertion.idMissing",
                        "Assertion id is required.", path + "/assertionId"));
            } else if (!assertionIds.add(assertion.assertionId())) {
                diagnostics.add(error("visual.scenario.assertion.idDuplicate",
                        "Assertion id '%s' is duplicated.".formatted(assertion.assertionId()),
                        path + "/assertionId"));
            }
            if ((assertion.scope() == ScenarioDraftSet.AssertionScope.NODE_OUTPUT
                    || assertion.scope() == ScenarioDraftSet.AssertionScope.NODE_STATUS)
                    && assertion.nodeId().isBlank()) {
                diagnostics.add(error("visual.scenario.assertion.nodeMissing",
                        "Node-scoped assertion requires nodeId.", path + "/nodeId"));
            }
            if (targetKind == ContractDraft.TargetKind.OPERATOR
                    && Set.of(
                    ScenarioDraftSet.AssertionScope.NODE_OUTPUT,
                    ScenarioDraftSet.AssertionScope.NODE_STATUS,
                    ScenarioDraftSet.AssertionScope.EDGE_TRANSFER).contains(assertion.scope())) {
                diagnostics.add(error("visual.scenario.assertion.graphScopeUnsupported",
                        "Operator-target Scenarios support output and invocation assertions, "
                                + "not graph node or edge scopes.",
                        path + "/scope"));
            }
            if (!assertion.nodeId().isBlank() && graphDraft != null
                    && graphDraft.nodes().stream().noneMatch(node -> node.id().equals(assertion.nodeId()))) {
                diagnostics.add(error("visual.scenario.assertion.nodeUnknown",
                        "Assertion node '%s' does not exist in the GraphDraft.".formatted(assertion.nodeId()),
                        path + "/nodeId"));
            }
            if (assertion.scope() == ScenarioDraftSet.AssertionScope.EDGE_TRANSFER
                    && (assertion.fromNodeId().isBlank() || assertion.toNodeId().isBlank())) {
                diagnostics.add(error("visual.scenario.assertion.edgeMissing",
                        "Edge assertion requires fromNodeId and toNodeId.", path));
            }
            if (assertion.scope() == ScenarioDraftSet.AssertionScope.EDGE_TRANSFER
                    && graphDraft != null
                    && (!knownNode(graphDraft, assertion.fromNodeId())
                    || !knownNode(graphDraft, assertion.toNodeId()))) {
                diagnostics.add(error("visual.scenario.assertion.edgeNodeUnknown",
                        "Edge assertion endpoints must exist in the GraphDraft.", path));
            }
            if (!assertion.path().isBlank() && !assertion.path().startsWith("/")) {
                diagnostics.add(error("visual.scenario.assertion.pathInvalid",
                        "Assertion paths must use JSON Pointer syntax.", path + "/path"));
            }
            validateAssertionOperator(assertion, path, diagnostics);
        }
    }

    private static void validateAssertionOperator(
            ScenarioDraftSet.AssertionDraft assertion,
            String path,
            List<VisualDiagnostic> diagnostics) {
        Set<ScenarioDraftSet.AssertionOperator> supported = switch (assertion.scope()) {
            case OUTPUT_PATH, NODE_OUTPUT -> Set.of(
                    ScenarioDraftSet.AssertionOperator.EQUALS,
                    ScenarioDraftSet.AssertionOperator.MATCHES_SCHEMA,
                    ScenarioDraftSet.AssertionOperator.EXISTS,
                    ScenarioDraftSet.AssertionOperator.ABSENT);
            case NODE_STATUS -> Set.of(
                    ScenarioDraftSet.AssertionOperator.STATUS,
                    ScenarioDraftSet.AssertionOperator.EQUALS);
            case EDGE_TRANSFER -> Set.of(ScenarioDraftSet.AssertionOperator.USED);
            case INVOCATION -> Set.of(
                    ScenarioDraftSet.AssertionOperator.USED,
                    ScenarioDraftSet.AssertionOperator.NOT_USED);
        };
        if (!supported.contains(assertion.operator())) {
            diagnostics.add(error("visual.scenario.assertion.operatorUnsupported",
                    "Assertion operator '%s' is not supported for scope '%s'."
                            .formatted(assertion.operator(), assertion.scope()),
                    path + "/operator"));
        }
    }

    private static boolean knownNode(GraphDraft graphDraft, String nodeId) {
        return !nodeId.isBlank()
                && graphDraft.nodes().stream().anyMatch(node -> node.id().equals(nodeId));
    }

    private static boolean strictlyIncreasingPositive(List<Integer> values) {
        int previous = 0;
        for (Integer value : values) {
            if (value == null || value <= previous) {
                return false;
            }
            previous = value;
        }
        return true;
    }

    private static ScenarioValidationReport report(ScenarioDraftSet draftSet,
                                                   ContractDraft contract,
                                                   List<VisualDiagnostic> diagnostics,
                                                   ScenarioValidationReport.Status status) {
        String targetFingerprint = draftSet == null ? "" : draftSet.target().fingerprint();
        String contractFingerprint = contract == null || draftSet == null ? "" : draftSet.contractFingerprint();
        long revision = draftSet == null ? 0 : draftSet.revision();
        return new ScenarioValidationReport(
                ScenarioValidationReport.SCHEMA_VERSION,
                targetFingerprint,
                contractFingerprint,
                revision,
                status,
                diagnostics,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static VisualDiagnostic error(String code, String message, String target) {
        return VisualDiagnostic.error(code, message, target);
    }
}
