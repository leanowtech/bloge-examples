package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraft;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowStoredDraft;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles exact Flow subjects into a bounded nested invocation topology.
 *
 * <p>The compiler resolves every nested Flow revision and fingerprint before execution, rejects
 * cycles, missing targets and ancestor/descendant binding overlap, and pins Fixture Set coordinates.
 * It does not evaluate conditions: dynamic Case selection belongs to the invocation boundary after
 * DAG mappings have produced the node input.</p>
 */
public final class FlowFixturePlanCompilerV2 {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final int MAX_DEPTH = 16;
    private static final int MAX_NODES = 256;
    private final ReusableFlowPublicationStore publications;
    private final ReusableFlowDraftStore drafts;
    private final FixturePlanCompiler fixtures;
    private final ComponentSimulationAuthorityV2 components;

    public FlowFixturePlanCompilerV2(ReusableFlowPublicationStore publications,
                                     ReusableFlowDraftStore drafts,
                                     FixturePlanCompiler fixtures) {
        this(publications, drafts, fixtures, null);
    }

    /** Creates a compiler that can also resolve exact Operator DAG nodes. */
    public FlowFixturePlanCompilerV2(ReusableFlowPublicationStore publications,
                                     ReusableFlowDraftStore drafts,
                                     FixturePlanCompiler fixtures,
                                     ComponentSimulationAuthorityV2 components) {
        this.publications = publications;
        this.drafts = drafts;
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.components = components;
    }

    /** Compiles one exact Flow command without reading mutable heads or Fixture material. */
    public ResolvedFlowSimulationPlanV2 compile(AuthoringScope scope, SimulationCommandV2 command) {
        if (scope == null || command == null || !(command.subject() instanceof ExactFixtureSubjectRefV2.FlowDraft
                || command.subject() instanceof ExactFixtureSubjectRefV2.FlowVersion)) {
            throw failure(FixturePlanFailure.Code.TARGET_UNSUPPORTED);
        }
        FlowAuthority root = authority(scope, command.subject());
        JsonNode input = input(scope, command);
        LinkedHashMap<List<String>, ResolvedFlowSimulationPlanV2.Node> nodes = new LinkedHashMap<>();
        expand(scope, root.graph(), List.of(), new HashSet<>(Set.of(command.subject())), nodes, 0);
        CompiledBindings bindings = bindings(scope, command, input, nodes);
        ObjectNode closure = JSON.createObjectNode();
        closure.set("subject", JSON.valueToTree(command.subject()));
        closure.put("inputFingerprint", AuthoringFingerprints.of(input));
        closure.put("unmatched", unmatched(command).name());
        closure.set("topology", JSON.valueToTree(nodes.values().stream()
                .map(value -> Map.of("path", value.path(), "subject", value.subject())).toList()));
        closure.set("fixturePlan", JSON.valueToTree(command.fixturePlan()));
        return new ResolvedFlowSimulationPlanV2(command.subject(), root.contract().input(),
                root.contract().output(), root.graph(), input, unmatched(command), nodes,
                bindings.nodes(), bindings.callSites(),
                AuthoringFingerprints.of(closure));
    }

    /** Resolves one dynamic binding against the actual mapped node input. */
    public ResolvedFixturePlan.Selection resolveInvocation(
            AuthoringScope scope, ResolvedFlowSimulationPlanV2.Node node,
            ResolvedFlowSimulationPlanV2.Binding binding, JsonNode invocationInput) {
        if (binding.fixedSelection() != null) return binding.fixedSelection();
        return fixtures.resolveInvocation(scope, bindingSubject(node, binding.target()), binding.target(),
                binding.selection(), invocationInput);
    }

    /** Resolves one persisted nested APPLY_CASE at the same dynamic invocation boundary. */
    public ResolvedFixturePlan.Selection resolveAppliedCase(
            AuthoringScope scope, ResolvedFlowSimulationPlanV2.Node node,
            SimulationCommandV2.FixtureTarget target,
            com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand.Behavior.ApplyCase appliedCase,
            JsonNode invocationInput) {
        return fixtures.resolveAppliedCase(
                scope, bindingSubject(node, target), target, appliedCase, invocationInput);
    }

    private CompiledBindings bindings(
            AuthoringScope scope, SimulationCommandV2 command, JsonNode input,
            Map<List<String>, ResolvedFlowSimulationPlanV2.Node> nodes) {
        LinkedHashMap<List<String>, ResolvedFlowSimulationPlanV2.Binding> result = new LinkedHashMap<>();
        LinkedHashMap<SimulationCommandV2.FixtureTarget.CallSite,
                ResolvedFlowSimulationPlanV2.Binding> callSites = new LinkedHashMap<>();
        if (command.fixturePlan() instanceof SimulationCommandV2.FixturePlan.None) {
            return new CompiledBindings(result, callSites);
        }
        if (command.fixturePlan() instanceof SimulationCommandV2.FixturePlan.CaseControls) {
            ResolvedFixturePlan parent = fixtures.compile(scope, command);
            for (ResolvedFixturePlan.Selection selection : parent.selections()) {
                if (!(selection.target() instanceof SimulationCommandV2.FixtureTarget.NodePath target)) {
                    throw failure(FixturePlanFailure.Code.TARGET_UNSUPPORTED);
                }
                add(result, target.nodePath(), new ResolvedFlowSimulationPlanV2.Binding(
                        target, null, selection), nodes, callSites.keySet());
            }
            return new CompiledBindings(result, callSites);
        }
        SimulationCommandV2.FixturePlan.Bindings authored =
                (SimulationCommandV2.FixturePlan.Bindings) command.fixturePlan();
        for (SimulationCommandV2.FixtureBinding binding : authored.bindings()) {
            if (binding.target() instanceof SimulationCommandV2.FixtureTarget.NodePath target) {
                add(result, target.nodePath(), new ResolvedFlowSimulationPlanV2.Binding(
                        target, binding.selection(), null), nodes, callSites.keySet());
                continue;
            }
            if (!(binding.target() instanceof SimulationCommandV2.FixtureTarget.CallSite target)) {
                throw failure(FixturePlanFailure.Code.TARGET_UNSUPPORTED);
            }
            addCallSite(callSites, target, new ResolvedFlowSimulationPlanV2.Binding(
                    target, binding.selection(), null), nodes, result.keySet());
        }
        return new CompiledBindings(result, callSites);
    }

    private static void add(Map<List<String>, ResolvedFlowSimulationPlanV2.Binding> result,
                            List<String> path, ResolvedFlowSimulationPlanV2.Binding binding,
                            Map<List<String>, ResolvedFlowSimulationPlanV2.Node> nodes,
                            Set<SimulationCommandV2.FixtureTarget.CallSite> callSites) {
        if (!nodes.containsKey(path)) throw failure(FixturePlanFailure.Code.FIXTURE_SUBJECT_MISMATCH);
        if (callSites.stream().anyMatch(value -> prefix(path, value.nodePath())
                || path.equals(value.nodePath()))) {
            throw failure(FixturePlanFailure.Code.TARGET_OVERLAP);
        }
        if (result.putIfAbsent(List.copyOf(path), binding) != null) {
            throw failure(FixturePlanFailure.Code.TARGET_OVERLAP);
        }
        for (List<String> existing : result.keySet()) {
            if (!existing.equals(path) && (prefix(existing, path) || prefix(path, existing))) {
                throw failure(FixturePlanFailure.Code.TARGET_OVERLAP);
            }
        }
    }

    private static void addCallSite(
            Map<SimulationCommandV2.FixtureTarget.CallSite, ResolvedFlowSimulationPlanV2.Binding> result,
            SimulationCommandV2.FixtureTarget.CallSite target,
            ResolvedFlowSimulationPlanV2.Binding binding,
            Map<List<String>, ResolvedFlowSimulationPlanV2.Node> nodes,
            Set<List<String>> nodeBindings) {
        ResolvedFlowSimulationPlanV2.Node node = nodes.get(target.nodePath());
        if (node == null || node.callSites().stream()
                .noneMatch(value -> value.callSiteId().equals(target.callSiteId()))) {
            throw failure(FixturePlanFailure.Code.FIXTURE_SUBJECT_MISMATCH);
        }
        if (nodeBindings.stream().anyMatch(value -> value.equals(target.nodePath())
                || prefix(value, target.nodePath()))) {
            throw failure(FixturePlanFailure.Code.TARGET_OVERLAP);
        }
        if (result.putIfAbsent(target, binding) != null) {
            throw failure(FixturePlanFailure.Code.TARGET_OVERLAP);
        }
    }

    private void expand(AuthoringScope scope, ReusableFlowCommand.Graph graph, List<String> parent,
                        Set<ExactFixtureSubjectRefV2> ancestors,
                        Map<List<String>, ResolvedFlowSimulationPlanV2.Node> nodes, int depth) {
        if (depth > MAX_DEPTH) throw failure(FixturePlanFailure.Code.INTEGRITY);
        for (ReusableFlowCommand.Node authored : graph.nodes()) {
            List<String> path = append(parent, authored.nodeId());
            ExactFixtureSubjectRefV2 subject = subject(authored.use());
            ReusableFlowCommand.Contract contract;
            ReusableFlowCommand.Graph child = null;
            List<ComponentSimulationAuthorityV2.CallSite> callSites = List.of();
            if (subject instanceof ExactFixtureSubjectRefV2.FlowVersion) {
                if (!ancestors.add(subject)) throw failure(FixturePlanFailure.Code.INTEGRITY);
                FlowAuthority authority = authority(scope, subject);
                contract = authority.contract();
                child = authority.graph();
            } else if (subject instanceof ExactFixtureSubjectRefV2.OperatorVersion) {
                if (components == null) throw failure(FixturePlanFailure.Code.TARGET_UNSUPPORTED);
                ComponentSimulationAuthorityV2.ComponentContract component = components.resolve(scope, subject)
                        .orElseThrow(() -> failure(FixturePlanFailure.Code.FIXTURE_STALE));
                contract = new ReusableFlowCommand.Contract(component.input(), component.output());
                callSites = component.callSites();
            } else {
                contract = null;
            }
            if (nodes.putIfAbsent(path, new ResolvedFlowSimulationPlanV2.Node(
                    path, subject, contract, authored, child, callSites)) != null
                    || nodes.size() > MAX_NODES) {
                throw failure(FixturePlanFailure.Code.INTEGRITY);
            }
            if (child != null) {
                expand(scope, child, path, ancestors, nodes, depth + 1);
                ancestors.remove(subject);
            }
        }
    }

    private JsonNode input(AuthoringScope scope, SimulationCommandV2 command) {
        if (command.input() instanceof SimulationCommandV2.Input.Inline inline) return inline.value();
        SimulationCommandV2 inputOnly = new SimulationCommandV2(command.schemaVersion(), command.subject(),
                command.input(), new SimulationCommandV2.FixturePlan.None(), command.executionPolicy());
        return fixtures.compile(scope, inputOnly).input();
    }

    private FlowAuthority authority(AuthoringScope scope, ExactFixtureSubjectRefV2 subject) {
        if (subject instanceof ExactFixtureSubjectRefV2.FlowVersion value) {
            if (publications == null) throw failure(FixturePlanFailure.Code.TARGET_UNSUPPORTED);
            ReusableFlowVersion flow = publications.findVersion(scope, value.publicationId(), value.revision())
                    .orElseThrow(() -> failure(FixturePlanFailure.Code.FIXTURE_NOT_FOUND));
            if (!value.fingerprint().equals(flow.fingerprint())) throw failure(FixturePlanFailure.Code.FIXTURE_STALE);
            return new FlowAuthority(flow.contract(), flow.graph());
        }
        ExactFixtureSubjectRefV2.FlowDraft value = (ExactFixtureSubjectRefV2.FlowDraft) subject;
        if (drafts == null) throw failure(FixturePlanFailure.Code.TARGET_UNSUPPORTED);
        ReusableFlowStoredDraft stored = drafts.findDraftRevisionStored(scope, value.draftId(), value.revision())
                .orElseThrow(() -> failure(FixturePlanFailure.Code.FIXTURE_NOT_FOUND));
        ReusableFlowDraft flow = stored.draft();
        if (!value.fingerprint().equals(flow.fingerprint())) throw failure(FixturePlanFailure.Code.FIXTURE_STALE);
        return new FlowAuthority(flow.contract(), flow.graph());
    }

    private static ExactFixtureSubjectRefV2 subject(ReusableFlowCommand.ComposableRef reference) {
        if (reference instanceof ReusableFlowCommand.ComposableRef.ApiResource resource) {
            return new ExactFixtureSubjectRefV2.ApiResource(
                    resource.resourceId(), resource.revision(), resource.fingerprint());
        }
        if (reference instanceof ReusableFlowCommand.ComposableRef.FlowVersion flow) {
            return new ExactFixtureSubjectRefV2.FlowVersion(
                    flow.publicationId(), flow.revision(), flow.fingerprint());
        }
        ReusableFlowCommand.ComposableRef.OperatorVersion operator =
                (ReusableFlowCommand.ComposableRef.OperatorVersion) reference;
        return new ExactFixtureSubjectRefV2.OperatorVersion(operator.libraryId(), operator.revision(),
                operator.operatorRef(), operator.fingerprint());
    }

    private static SimulationCommandV2.Unmatched unmatched(SimulationCommandV2 command) {
        if (command.fixturePlan() instanceof SimulationCommandV2.FixturePlan.None) {
            return SimulationCommandV2.Unmatched.BLOCK;
        }
        return command.fixturePlan() instanceof SimulationCommandV2.FixturePlan.CaseControls value
                ? value.unmatched() : ((SimulationCommandV2.FixturePlan.Bindings) command.fixturePlan()).unmatched();
    }

    private static boolean prefix(List<String> parent, List<String> child) {
        return child.size() > parent.size() && child.subList(0, parent.size()).equals(parent);
    }

    private static List<String> append(List<String> parent, String nodeId) {
        List<String> path = new ArrayList<>(parent);
        path.add(nodeId);
        return List.copyOf(path);
    }

    private static FixturePlanFailure failure(FixturePlanFailure.Code code) {
        return new FixturePlanFailure(code);
    }

    private static ExactFixtureSubjectRefV2 bindingSubject(
            ResolvedFlowSimulationPlanV2.Node node, SimulationCommandV2.FixtureTarget target) {
        if (!(target instanceof SimulationCommandV2.FixtureTarget.CallSite callSite)) {
            return node.subject();
        }
        return node.callSites().stream()
                .filter(value -> value.callSiteId().equals(callSite.callSiteId()))
                .map(ComponentSimulationAuthorityV2.CallSite::callable)
                .findFirst().orElseThrow(() -> failure(FixturePlanFailure.Code.FIXTURE_STALE));
    }

    private record FlowAuthority(ReusableFlowCommand.Contract contract, ReusableFlowCommand.Graph graph) { }
    private record CompiledBindings(
            Map<List<String>, ResolvedFlowSimulationPlanV2.Binding> nodes,
            Map<SimulationCommandV2.FixtureTarget.CallSite,
                    ResolvedFlowSimulationPlanV2.Binding> callSites) { }
}
