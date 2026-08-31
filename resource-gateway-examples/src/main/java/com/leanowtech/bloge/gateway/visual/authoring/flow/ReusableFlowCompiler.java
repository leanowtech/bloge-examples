package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure.Code.CYCLE;
import static com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure.Code.DEPENDENCY_DRIFT;
import static com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure.Code.DEPENDENCY_NOT_FOUND;
import static com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure.Code.LAYOUT_INVALID;
import static com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure.Code.MAPPING_INVALID;
import static com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure.Code.SCHEMA_INCOMPATIBLE;
import static com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowFailure.Code.VALIDATION;

/**
 * Compiles one wire-authoritative reusable Flow into a deterministic DAG plan.
 *
 * <p>Mappings are the only edge authority. The compiler resolves every dependency by exact
 * revision and fingerprint, validates direct JSON-path bindings against both contracts, derives
 * dependencies, and rejects cycles. Persist, simulate, and publish modules can therefore consume
 * one plan without independently reinterpreting the authored graph.</p>
 */
public final class ReusableFlowCompiler {
    private static final int MAX_NODES = 100;
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern FINGERPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern DIRECT_PATH = Pattern.compile("^\\$(?:\\.[A-Za-z0-9][A-Za-z0-9_-]{0,127})?$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ComposableCatalog catalog;

    public ReusableFlowCompiler(ComposableCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Compiles the command inside a trusted authoring scope.
     *
     * @throws ReusableFlowFailure with a closed, payload-free code when compilation fails
     */
    public CompiledReusableFlow compile(AuthoringScope scope, ReusableFlowCommand command) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(command, "command");
        validateCommand(command);

        LinkedHashMap<String, ReusableFlowCommand.Node> authoredNodes = indexNodes(command.flow().graph());
        validateLayout(command.flow().layout(), authoredNodes.keySet());

        LinkedHashMap<String, ComposableDefinition> definitions = resolve(scope, authoredNodes);
        LinkedHashMap<String, List<String>> dependencies = validateMappings(
                command.flow().contract().input(), authoredNodes, definitions);
        List<String> topologicalOrder = topologicalOrder(authoredNodes.keySet(), dependencies);
        validateOutput(command.flow().contract().output(), command.flow().graph().output(), definitions);
        return new CompiledReusableFlow(command, definitions, dependencies, topologicalOrder);
    }

    private static void validateCommand(ReusableFlowCommand command) {
        ReusableFlowCommand.Flow flow = command.flow();
        if (!ReusableFlowCommand.SCHEMA_VERSION.equals(command.schemaVersion())
                || flow.displayName().isBlank() || flow.displayName().length() > 200
                || flow.description().length() > 2000
                || flow.graph().nodes().isEmpty() || flow.graph().nodes().size() > MAX_NODES
                || !VisualSchemaValidator.validateEnvelope(flow.contract().input(), "/flow/contract/input").isEmpty()
                || !VisualSchemaValidator.validateEnvelope(flow.contract().output(), "/flow/contract/output").isEmpty()) {
            throw new ReusableFlowFailure(VALIDATION);
        }
    }

    private static LinkedHashMap<String, ReusableFlowCommand.Node> indexNodes(
            ReusableFlowCommand.Graph graph) {
        LinkedHashMap<String, ReusableFlowCommand.Node> nodes = new LinkedHashMap<>();
        for (ReusableFlowCommand.Node node : graph.nodes()) {
            if (node == null || !validIdentifier(node.nodeId()) || node.label().isBlank()
                    || node.label().length() > 200 || !validReference(node.use())
                    || nodes.putIfAbsent(node.nodeId(), node) != null) {
                throw new ReusableFlowFailure(VALIDATION);
            }
        }
        if (!nodes.containsKey(graph.output().nodeId()) || !validDirectPath(graph.output().path())) {
            throw new ReusableFlowFailure(MAPPING_INVALID);
        }
        return nodes;
    }

    private static void validateLayout(ReusableFlowCommand.Layout layout, Set<String> nodeIds) {
        if (!layout.nodes().keySet().equals(nodeIds)) {
            throw new ReusableFlowFailure(LAYOUT_INVALID);
        }
        for (ReusableFlowCommand.Position position : layout.nodes().values()) {
            if (position == null || !Double.isFinite(position.x()) || !Double.isFinite(position.y())) {
                throw new ReusableFlowFailure(LAYOUT_INVALID);
            }
        }
    }

    private LinkedHashMap<String, ComposableDefinition> resolve(
            AuthoringScope scope, LinkedHashMap<String, ReusableFlowCommand.Node> nodes) {
        LinkedHashMap<String, ComposableDefinition> definitions = new LinkedHashMap<>();
        nodes.forEach((nodeId, node) -> {
            ComposableDefinition definition = catalog.resolve(scope, node.use())
                    .orElseThrow(() -> new ReusableFlowFailure(DEPENDENCY_NOT_FOUND));
            if (!definition.reference().equals(node.use())) {
                throw new ReusableFlowFailure(DEPENDENCY_DRIFT);
            }
            if (!VisualSchemaValidator.validateEnvelope(definition.input(), "/dependencies/input").isEmpty()
                    || !VisualSchemaValidator.validateEnvelope(definition.output(), "/dependencies/output").isEmpty()) {
                throw new ReusableFlowFailure(DEPENDENCY_DRIFT);
            }
            definitions.put(nodeId, definition);
        });
        return definitions;
    }

    private static LinkedHashMap<String, List<String>> validateMappings(
            SchemaEnvelope flowInput,
            LinkedHashMap<String, ReusableFlowCommand.Node> nodes,
            Map<String, ComposableDefinition> definitions) {
        LinkedHashMap<String, List<String>> dependencies = new LinkedHashMap<>();
        nodes.forEach((nodeId, node) -> {
            ComposableDefinition target = definitions.get(nodeId);
            LinkedHashSet<String> mappedTargets = new LinkedHashSet<>();
            LinkedHashSet<String> nodeDependencies = new LinkedHashSet<>();
            for (ReusableFlowCommand.Input mapping : node.inputs()) {
                Optional<Map<String, Object>> targetSchema = directProperty(target.input(), mapping.to());
                if (targetSchema.isEmpty() || !mappedTargets.add(mapping.to())) {
                    throw new ReusableFlowFailure(MAPPING_INVALID);
                }
                validateSource(flowInput, nodes, definitions, nodeId, mapping.from(),
                        targetSchema.get(), nodeDependencies);
            }
            for (String required : target.input().required()) {
                if (!mappedTargets.contains("$." + required)) {
                    throw new ReusableFlowFailure(MAPPING_INVALID);
                }
            }
            dependencies.put(nodeId, List.copyOf(nodeDependencies));
        });
        return dependencies;
    }

    private static void validateSource(
            SchemaEnvelope flowInput,
            Map<String, ReusableFlowCommand.Node> nodes,
            Map<String, ComposableDefinition> definitions,
            String targetNodeId,
            ReusableFlowCommand.MappingSource source,
            Map<String, Object> targetSchema,
            Set<String> dependencies) {
        Map<String, Object> sourceSchema;
        if (source instanceof ReusableFlowCommand.MappingSource.FlowInput input) {
            sourceSchema = directProperty(flowInput, input.path())
                    .orElseThrow(() -> new ReusableFlowFailure(MAPPING_INVALID));
        } else if (source instanceof ReusableFlowCommand.MappingSource.NodeOutput output) {
            if (!validIdentifier(output.nodeId()) || targetNodeId.equals(output.nodeId())
                    || !nodes.containsKey(output.nodeId())) {
                throw new ReusableFlowFailure(MAPPING_INVALID);
            }
            sourceSchema = directProperty(definitions.get(output.nodeId()).output(), output.path())
                    .orElseThrow(() -> new ReusableFlowFailure(MAPPING_INVALID));
            dependencies.add(output.nodeId());
        } else if (source instanceof ReusableFlowCommand.MappingSource.Constant constant) {
            validateConstant(constant.value(), targetSchema);
            return;
        } else {
            throw new ReusableFlowFailure(MAPPING_INVALID);
        }
        if (!compatible(sourceSchema, targetSchema)) {
            throw new ReusableFlowFailure(SCHEMA_INCOMPATIBLE);
        }
    }

    private static void validateConstant(JsonNode value, Map<String, Object> targetSchema) {
        SchemaEnvelope envelope = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", targetSchema);
        Object material = value == null ? null : JSON.convertValue(value, Object.class);
        if (!VisualSchemaValidator.validateValue(envelope, material, "/constant").isEmpty()) {
            throw new ReusableFlowFailure(SCHEMA_INCOMPATIBLE);
        }
    }

    private static List<String> topologicalOrder(Set<String> nodeIds,
                                                 Map<String, List<String>> dependencies) {
        LinkedHashMap<String, Integer> indegree = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> dependents = new LinkedHashMap<>();
        nodeIds.forEach(nodeId -> {
            indegree.put(nodeId, dependencies.getOrDefault(nodeId, List.of()).size());
            dependents.put(nodeId, new ArrayList<>());
        });
        dependencies.forEach((nodeId, sources) -> sources.forEach(
                source -> dependents.get(source).add(nodeId)));

        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((nodeId, count) -> { if (count == 0) ready.addLast(nodeId); });
        List<String> order = new ArrayList<>(nodeIds.size());
        while (!ready.isEmpty()) {
            String nodeId = ready.removeFirst();
            order.add(nodeId);
            for (String dependent : dependents.get(nodeId)) {
                int remaining = indegree.computeIfPresent(dependent, (ignored, value) -> value - 1);
                if (remaining == 0) {
                    ready.addLast(dependent);
                }
            }
        }
        if (order.size() != nodeIds.size()) {
            throw new ReusableFlowFailure(CYCLE);
        }
        return List.copyOf(order);
    }

    private static void validateOutput(SchemaEnvelope flowOutput,
                                       ReusableFlowCommand.Output output,
                                       Map<String, ComposableDefinition> definitions) {
        Map<String, Object> selected = directProperty(definitions.get(output.nodeId()).output(), output.path())
                .orElseThrow(() -> new ReusableFlowFailure(MAPPING_INVALID));
        if (!compatible(selected, flowOutput.schema())) {
            throw new ReusableFlowFailure(SCHEMA_INCOMPATIBLE);
        }
    }

    private static Optional<Map<String, Object>> directProperty(SchemaEnvelope envelope, String path) {
        if (!validDirectPath(path)) {
            return Optional.empty();
        }
        if ("$".equals(path)) {
            return Optional.of(envelope.schema());
        }
        Object raw = envelope.properties().get(path.substring(2));
        if (!(raw instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        LinkedHashMap<String, Object> property = new LinkedHashMap<>();
        map.forEach((key, value) -> property.put(String.valueOf(key), value));
        return Optional.of(Map.copyOf(property));
    }

    private static boolean compatible(Map<String, Object> source, Map<String, Object> target) {
        Object sourceType = source.get("type");
        Object targetType = target.get("type");
        return Objects.equals(sourceType, targetType) && sourceType != null;
    }

    private static boolean validReference(ReusableFlowCommand.ComposableRef reference) {
        return reference != null && validIdentifier(reference.id()) && reference.revision() > 0
                && reference.revision() <= Integer.MAX_VALUE
                && reference.fingerprint() != null
                && FINGERPRINT.matcher(reference.fingerprint()).matches();
    }

    private static boolean validIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static boolean validDirectPath(String value) {
        return value != null && DIRECT_PATH.matcher(value).matches();
    }
}
