package com.leanowtech.bloge.gateway.visual.authoring.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableCatalog;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableDefinition;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowVersion;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compiles non-recursive node-level Fixture reuse for one immutable parent Flow.
 *
 * <p>Every parent node must have one explicit {@code NODE + APPLY_CASE}. Each referenced Case
 * must itself be exactly {@code SUBJECT + RETURN/INLINE}; its saved input is deliberately ignored.
 * Parent mappings compute the node input from the parent Case input and preceding mocked outputs,
 * while exact subject equality prevents a Fixture for another Resource or Flow from being reused.</p>
 */
public final class ParentFlowApplyCaseCompiler {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final ComposableCatalog catalog;
    private final FixtureSetAuthorityReader fixtures;

    public ParentFlowApplyCaseCompiler(ComposableCatalog catalog, FixtureSetAuthorityReader fixtures) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
    }

    /** Validates all node-controlled Cases before an idempotency coordinate is occupied. */
    public void validateCommand(AuthoringScope scope, ReusableFlowVersion parent,
                                FixtureSetCommand command) {
        Objects.requireNonNull(command, "command");
        for (FixtureSetCommand.Case fixtureCase : command.cases()) {
            if (!isWholeSubjectReturn(fixtureCase)) {
                compile(scope, parent, fixtureCase);
            }
        }
    }

    /** Resolves exact referenced Cases, evaluates parent mappings and returns immutable evidence. */
    public CompiledCase compile(AuthoringScope scope, ReusableFlowVersion parent,
                                FixtureSetCommand.Case fixtureCase) {
        if (scope == null || parent == null || fixtureCase == null
                || !valid(parent.contract().input(), fixtureCase.input())) {
            throw failure(ParentFlowApplyCaseFailure.Code.VALIDATION);
        }
        LinkedHashMap<String, ReusableFlowCommand.Node> authoredNodes = new LinkedHashMap<>();
        for (ReusableFlowCommand.Node node : parent.graph().nodes()) {
            if (authoredNodes.putIfAbsent(node.nodeId(), node) != null) {
                throw failure(ParentFlowApplyCaseFailure.Code.INTEGRITY);
            }
        }
        if (fixtureCase.controls().size() != authoredNodes.size()) {
            throw failure(ParentFlowApplyCaseFailure.Code.UNSUPPORTED);
        }

        LinkedHashMap<String, NodeMaterial> materials = new LinkedHashMap<>();
        for (FixtureSetCommand.Control control : fixtureCase.controls()) {
            if (!(control.target() instanceof FixtureSetCommand.Target.Node target)
                    || !(control.behavior() instanceof FixtureSetCommand.Behavior.ApplyCase apply)
                    || control.fidelity() != null || !IDENTIFIER.matcher(target.nodeId()).matches()
                    || !IDENTIFIER.matcher(apply.fixtureSetId()).matches()
                    || !IDENTIFIER.matcher(apply.caseId()).matches() || apply.revision() < 1
                    || materials.containsKey(target.nodeId())) {
                throw failure(ParentFlowApplyCaseFailure.Code.UNSUPPORTED);
            }
            ReusableFlowCommand.Node node = authoredNodes.get(target.nodeId());
            if (node == null) throw failure(ParentFlowApplyCaseFailure.Code.UNSUPPORTED);
            materials.put(target.nodeId(), resolve(scope, node, apply));
        }
        if (!materials.keySet().equals(authoredNodes.keySet())) {
            throw failure(ParentFlowApplyCaseFailure.Code.UNSUPPORTED);
        }

        List<String> order = topologicalOrder(authoredNodes);
        LinkedHashMap<String, JsonNode> outputs = new LinkedHashMap<>();
        List<CompiledNode> compiledNodes = new ArrayList<>();
        for (String nodeId : order) {
            ReusableFlowCommand.Node node = authoredNodes.get(nodeId);
            NodeMaterial material = materials.get(nodeId);
            JsonNode input = mapInput(node.inputs(), fixtureCase.input(), outputs);
            if (!valid(material.definition().input(), input)) {
                throw failure(ParentFlowApplyCaseFailure.Code.VALIDATION);
            }
            outputs.put(nodeId, material.output());
            compiledNodes.add(new CompiledNode(nodeId, material.fixtureSetId(), material.revision(),
                    material.caseId(), material.fidelity(), material.apiResource()));
        }
        JsonNode output = select(outputs.get(parent.graph().output().nodeId()),
                parent.graph().output().path());
        if (!valid(parent.contract().output(), output)
                || fixtureCase.expect() != null
                && !valid(parent.contract().output(), fixtureCase.expect().output())) {
            throw failure(ParentFlowApplyCaseFailure.Code.VALIDATION);
        }
        return new CompiledCase(parent.subject(), fixtureCase, output, compiledNodes);
    }

    private NodeMaterial resolve(AuthoringScope scope, ReusableFlowCommand.Node node,
                                 FixtureSetCommand.Behavior.ApplyCase apply) {
        try {
            ComposableDefinition definition = catalog.resolve(scope, node.use())
                    .orElseThrow(() -> failure(ParentFlowApplyCaseFailure.Code.NOT_FOUND));
            if (!definition.reference().equals(node.use())) {
                throw failure(ParentFlowApplyCaseFailure.Code.INTEGRITY);
            }
            if (!VisualSchemaValidator.validateEnvelope(definition.input(), "/node/input").isEmpty()
                    || !VisualSchemaValidator.validateEnvelope(
                    definition.output(), "/node/output").isEmpty()) {
                throw failure(ParentFlowApplyCaseFailure.Code.INTEGRITY);
            }
            StoredFixtureSet fixture = fixtures.findRevision(scope, apply.fixtureSetId(), apply.revision())
                    .orElseThrow(() -> failure(ParentFlowApplyCaseFailure.Code.NOT_FOUND));
            FixtureSetView referenced = fixture.generated().view();
            if (!scope.equals(fixture.scope()) || !apply.fixtureSetId().equals(referenced.fixtureSetId())
                    || apply.revision() != referenced.revision()
                    || !referenced.subject().equals(subject(node.use()))) {
                throw failure(ParentFlowApplyCaseFailure.Code.INTEGRITY);
            }
            if (referenced.status() != FixtureSetView.Status.PRIVATE_DRAFT) {
                throw failure(ParentFlowApplyCaseFailure.Code.UNSUPPORTED);
            }
            FixtureSetCommand.Case selected = referenced.cases().stream()
                    .filter(value -> apply.caseId().equals(value.caseId()))
                    .reduce((left, right) -> { throw failure(ParentFlowApplyCaseFailure.Code.INTEGRITY); })
                    .orElseThrow(() -> failure(ParentFlowApplyCaseFailure.Code.NOT_FOUND));
            FixtureSetCommand.Control returned = soleSubjectReturn(selected);
            FixtureSetCommand.Behavior.Return behavior =
                    (FixtureSetCommand.Behavior.Return) returned.behavior();
            if (!(behavior.material() instanceof FixtureSetCommand.Material.Inline inline)
                    || !valid(definition.output(), inline.value())) {
                throw failure(ParentFlowApplyCaseFailure.Code.UNSUPPORTED);
            }
            boolean apiResource = node.use() instanceof ReusableFlowCommand.ComposableRef.ApiResource;
            FixtureSetCommand.Fidelity fidelity = returned.fidelity() == null
                    ? FixtureSetCommand.Fidelity.OUTPUT_LEVEL : returned.fidelity();
            if (!apiResource && fidelity != FixtureSetCommand.Fidelity.OUTPUT_LEVEL) {
                throw failure(ParentFlowApplyCaseFailure.Code.UNSUPPORTED);
            }
            return new NodeMaterial(definition, apply.fixtureSetId(), apply.revision(), apply.caseId(),
                    inline.value(), fidelity, apiResource);
        } catch (ParentFlowApplyCaseFailure failure) {
            throw failure;
        } catch (ApiFixtureSetCommitStoreException | StandaloneFixtureSetStoreException
                 | ApiResourceCommitStoreException | ReusableFlowFailure failure) {
            throw failure(ParentFlowApplyCaseFailure.Code.INTEGRITY);
        }
    }

    private static FixtureSetCommand.Control soleSubjectReturn(FixtureSetCommand.Case selected) {
        if (selected.controls().size() != 1) {
            throw failure(ParentFlowApplyCaseFailure.Code.UNSUPPORTED);
        }
        FixtureSetCommand.Control control = selected.controls().getFirst();
        if (!(control.target() instanceof FixtureSetCommand.Target.Subject)
                || !(control.behavior() instanceof FixtureSetCommand.Behavior.Return)) {
            throw failure(ParentFlowApplyCaseFailure.Code.UNSUPPORTED);
        }
        return control;
    }

    private static JsonNode mapInput(List<ReusableFlowCommand.Input> mappings, JsonNode flowInput,
                                     Map<String, JsonNode> nodeOutputs) {
        if (mappings.size() == 1 && "$".equals(mappings.getFirst().to())) {
            return source(mappings.getFirst().from(), flowInput, nodeOutputs);
        }
        ObjectNode input = JSON.createObjectNode();
        Set<String> targets = new LinkedHashSet<>();
        for (ReusableFlowCommand.Input mapping : mappings) {
            if (!directProperty(mapping.to()) || !targets.add(mapping.to())) {
                throw failure(ParentFlowApplyCaseFailure.Code.INTEGRITY);
            }
            input.set(mapping.to().substring(2), source(mapping.from(), flowInput, nodeOutputs));
        }
        return input;
    }

    private static JsonNode source(ReusableFlowCommand.MappingSource source, JsonNode flowInput,
                                   Map<String, JsonNode> nodeOutputs) {
        if (source instanceof ReusableFlowCommand.MappingSource.FlowInput input) {
            return select(flowInput, input.path());
        }
        if (source instanceof ReusableFlowCommand.MappingSource.NodeOutput output) {
            JsonNode prior = nodeOutputs.get(output.nodeId());
            if (prior == null) throw failure(ParentFlowApplyCaseFailure.Code.INTEGRITY);
            return select(prior, output.path());
        }
        if (source instanceof ReusableFlowCommand.MappingSource.Constant constant) {
            return constant.value() == null
                    ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : constant.value();
        }
        throw failure(ParentFlowApplyCaseFailure.Code.INTEGRITY);
    }

    private static JsonNode select(JsonNode root, String path) {
        if (root == null || path == null) throw failure(ParentFlowApplyCaseFailure.Code.INTEGRITY);
        if ("$".equals(path)) return root.deepCopy();
        if (!directProperty(path) || !root.isObject()) {
            throw failure(ParentFlowApplyCaseFailure.Code.INTEGRITY);
        }
        JsonNode selected = root.get(path.substring(2));
        if (selected == null) throw failure(ParentFlowApplyCaseFailure.Code.VALIDATION);
        return selected.deepCopy();
    }

    private static List<String> topologicalOrder(Map<String, ReusableFlowCommand.Node> nodes) {
        LinkedHashMap<String, Set<String>> dependencies = new LinkedHashMap<>();
        nodes.forEach((nodeId, node) -> {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            node.inputs().forEach(input -> {
                if (input.from() instanceof ReusableFlowCommand.MappingSource.NodeOutput output) {
                    if (!nodes.containsKey(output.nodeId()) || nodeId.equals(output.nodeId())) {
                        throw failure(ParentFlowApplyCaseFailure.Code.INTEGRITY);
                    }
                    values.add(output.nodeId());
                }
            });
            dependencies.put(nodeId, values);
        });
        ArrayDeque<String> ready = new ArrayDeque<>();
        dependencies.forEach((nodeId, values) -> { if (values.isEmpty()) ready.add(nodeId); });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String next = ready.removeFirst();
            order.add(next);
            dependencies.forEach((nodeId, values) -> {
                if (values.remove(next) && values.isEmpty() && !order.contains(nodeId)
                        && !ready.contains(nodeId)) ready.addLast(nodeId);
            });
        }
        if (order.size() != nodes.size()) throw failure(ParentFlowApplyCaseFailure.Code.INTEGRITY);
        return List.copyOf(order);
    }

    private static FixtureSubjectRef subject(ReusableFlowCommand.ComposableRef reference) {
        if (reference instanceof ReusableFlowCommand.ComposableRef.ApiResource api) {
            return new FixtureSubjectRef.ApiResource(api.resourceId(), api.revision(), api.fingerprint());
        }
        ReusableFlowCommand.ComposableRef.FlowVersion flow =
                (ReusableFlowCommand.ComposableRef.FlowVersion) reference;
        return new FixtureSubjectRef.FlowVersion(
                flow.publicationId(), flow.revision(), flow.fingerprint());
    }

    private static boolean isWholeSubjectReturn(FixtureSetCommand.Case fixtureCase) {
        return fixtureCase.controls().size() == 1
                && fixtureCase.controls().getFirst().target() instanceof FixtureSetCommand.Target.Subject
                && fixtureCase.controls().getFirst().behavior() instanceof FixtureSetCommand.Behavior.Return;
    }

    private static boolean directProperty(String path) {
        return path != null && path.matches("^\\$\\.[A-Za-z0-9][A-Za-z0-9_-]{0,127}$");
    }

    private static boolean valid(SchemaEnvelope schema, JsonNode value) {
        return VisualSchemaValidator.validateValue(
                schema, JSON.convertValue(value, Object.class), "/value").isEmpty();
    }

    private static ParentFlowApplyCaseFailure failure(ParentFlowApplyCaseFailure.Code code) {
        return new ParentFlowApplyCaseFailure(code);
    }

    /** Immutable result consumed by Simulation without reinterpreting Fixture controls. */
    public record CompiledCase(FixtureSubjectRef.FlowVersion subject, FixtureSetCommand.Case fixture,
                               JsonNode output, List<CompiledNode> nodes) {
        public CompiledCase {
            output = output.deepCopy();
            nodes = List.copyOf(nodes);
        }
        @Override public JsonNode output() { return output.deepCopy(); }
        @Override public List<CompiledNode> nodes() { return List.copyOf(nodes); }
        @Override public String toString() {
            return "CompiledCase[subject=" + subject + ", nodes=" + nodes.size()
                    + ", output=protected]";
        }
    }

    /** One mocked parent node and the exact leaf Case that supplied its output. */
    public record CompiledNode(String nodeId, String fixtureSetId, int revision, String caseId,
                               FixtureSetCommand.Fidelity fidelity, boolean apiResource) { }

    private record NodeMaterial(ComposableDefinition definition, String fixtureSetId, int revision,
                                String caseId, JsonNode output, FixtureSetCommand.Fidelity fidelity,
                                boolean apiResource) {
        private NodeMaterial {
            output = output.deepCopy();
        }
        @Override public JsonNode output() { return output.deepCopy(); }
    }
}
