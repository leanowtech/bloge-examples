package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.function.Predicate;

/**
 * Server-side schema gate for interactive canvas connections.
 */
@Service
public class VisualConnectionCheckService {

    private static final String PREVIEW_EDGE_ID = "__preview_connection";
    private static final String CONTEXT_SOURCE_NODE_ID = "__ctx";
    private static final String CONFIG_TARGET_PORT = "config";
    private static final Pattern ARRAY_INDEX = Pattern.compile("\\d+");

    private final GraphDraftValidator validator;
    private final VisualOperatorCatalog catalog;

    /**
     * @param validator graph draft validator
     * @param catalog visual operator catalog
     */
    public VisualConnectionCheckService(GraphDraftValidator validator, VisualOperatorCatalog catalog) {
        this.validator = validator;
        this.catalog = catalog;
    }

    /**
     * Checks a proposed edge by temporarily adding it to the draft and reusing the normal validator.
     *
     * @param request connection check request
     * @return normalized check result
     */
    public VisualConnectionCheckResult check(VisualConnectionCheckRequest request) {
        if (request == null || request.draft() == null) {
            return new VisualConnectionCheckResult(false, null, List.of(
                    VisualDiagnostic.error("visual.draft.missing", "Graph draft is required.", "/draft")
            ));
        }

        GraphDraft.DraftEdge edge = new GraphDraft.DraftEdge(PREVIEW_EDGE_ID, request.kind(),
                request.source(), request.target(), request.condition());
        if ("dependency".equals(edge.kind())) {
            return checkDependencyEdge(request, edge);
        }
        if ("route".equals(edge.kind())) {
            return checkRouteEdge(request, edge);
        }
        if (CONFIG_TARGET_PORT.equals(request.target().port())) {
            return checkConfigBinding(request, edge);
        }
        if (CONTEXT_SOURCE_NODE_ID.equals(request.source().nodeId())) {
            return checkContextBinding(request, edge);
        }

        if (hasSameConnection(request.draft(), edge)) {
            return new VisualConnectionCheckResult(false, edge, List.of(
                    VisualDiagnostic.error("visual.edge.duplicateConnection",
                            "Connection '%s' is already represented by another edge."
                                    .formatted(connectionLabel(edge)),
                            "/edges/" + request.draft().edges().size())
            ));
        }

        int targetIndex = targetNodeIndex(request.draft(), request.target().nodeId());
        if (targetIndex < 0) {
            return new VisualConnectionCheckResult(false, edge, List.of(
                    VisualDiagnostic.error("visual.edge.unknownTarget",
                            "Edge target node does not exist: " + request.target().nodeId(),
                            "/target/nodeId")
            ));
        }
        String inputKey = previewBindingKey(request.draft(), request.target());
        GraphDraft.Binding binding = withTargetUnionBranch(request, GraphDraft.Binding.nodePath(
                request.source().nodeId(),
                request.source().port(),
                request.source().path(),
                request.target().port(),
                request.target().path()
        ));
        GraphDraft candidate = draftWithPreviewBindingAndEdge(request.draft(), targetIndex, inputKey, binding, edge,
                targetOperator(request.draft(), request.target().nodeId()));
        int previewIndex = candidate.edges().size() - 1;
        String bindingPath = "/nodes/" + targetIndex + "/inputs/" + inputKey;
        String operatorPath = "/nodes/" + targetIndex + "/operatorRef";
        Map<String, Integer> nodeIndexes = nodeIndexes(candidate);

        VisualValidationResult validation = validator.validate(candidate);
        List<VisualDiagnostic> diagnostics = preflightDiagnostics(validation,
                diagnostic -> relevantToConnection(diagnostic, previewIndex, bindingPath, operatorPath,
                        request, nodeIndexes));
        return new VisualConnectionCheckResult(diagnostics.stream().noneMatch(VisualDiagnostic::error),
                edge, inputKey, diagnostics, validation);
    }

    private VisualConnectionCheckResult checkDependencyEdge(VisualConnectionCheckRequest request,
                                                            GraphDraft.DraftEdge edge) {
        if (hasSameConnection(request.draft(), edge)) {
            return new VisualConnectionCheckResult(false, edge, List.of(
                    VisualDiagnostic.error("visual.edge.duplicateConnection",
                            "Connection '%s' is already represented by another edge."
                                    .formatted(connectionLabel(edge)),
                            "/edges/" + request.draft().edges().size())
            ));
        }

        GraphDraft candidate = draftWithPreviewEdge(request.draft(), edge);
        int previewIndex = candidate.edges().size() - 1;
        Map<String, Integer> nodeIndexes = nodeIndexes(candidate);

        VisualValidationResult validation = validator.validate(candidate);
        List<VisualDiagnostic> diagnostics = preflightDiagnostics(validation,
                diagnostic -> relevantToDependencyEdge(diagnostic, previewIndex, request, nodeIndexes));
        return new VisualConnectionCheckResult(diagnostics.stream().noneMatch(VisualDiagnostic::error),
                edge, "", diagnostics, validation);
    }

    private VisualConnectionCheckResult checkRouteEdge(VisualConnectionCheckRequest request,
                                                       GraphDraft.DraftEdge edge) {
        if (hasSameConnection(request.draft(), edge)) {
            return new VisualConnectionCheckResult(false, edge, List.of(
                    VisualDiagnostic.error("visual.edge.duplicateConnection",
                            "Connection '%s' is already represented by another edge."
                                    .formatted(connectionLabel(edge)),
                            "/edges/" + request.draft().edges().size())
            ));
        }

        GraphDraft candidate = draftWithPreviewEdge(request.draft(), edge);
        int previewIndex = candidate.edges().size() - 1;
        Map<String, Integer> nodeIndexes = nodeIndexes(candidate);

        VisualValidationResult validation = validator.validate(candidate);
        List<VisualDiagnostic> diagnostics = preflightDiagnostics(validation,
                diagnostic -> relevantToDependencyEdge(diagnostic, previewIndex, request, nodeIndexes));
        return new VisualConnectionCheckResult(diagnostics.stream().noneMatch(VisualDiagnostic::error),
                edge, "", diagnostics, validation);
    }

    private VisualConnectionCheckResult checkConfigBinding(VisualConnectionCheckRequest request,
                                                           GraphDraft.DraftEdge edge) {
        int targetIndex = targetNodeIndex(request.draft(), request.target().nodeId());
        if (targetIndex < 0) {
            return new VisualConnectionCheckResult(false, edge, List.of(
                    VisualDiagnostic.error("visual.edge.unknownTarget",
                            "Connection target node does not exist: " + request.target().nodeId(),
                            "/target/nodeId")
            ));
        }
        if (request.target().path().isBlank()) {
            return new VisualConnectionCheckResult(false, edge, List.of(
                    VisualDiagnostic.error("visual.config.targetMissing",
                            "Config connection target path is required.",
                            "/target/path")
            ));
        }
        if (request.source().nodeId().isBlank()) {
            return new VisualConnectionCheckResult(false, edge, List.of(
                    VisualDiagnostic.error("visual.edge.unknownSource",
                            "Connection source node is required.",
                            "/source/nodeId")
            ));
        }

        GraphDraft candidate = draftWithPreviewConfigExpression(request.draft(), targetIndex,
                request.target().path(), expressionForSource(request.source()));
        String configPath = "/nodes/" + targetIndex + "/config/" + diagnosticPath(request.target().path());
        String operatorPath = "/nodes/" + targetIndex + "/operatorRef";
        Map<String, Integer> nodeIndexes = nodeIndexes(candidate);

        VisualValidationResult validation = validator.validate(candidate);
        List<VisualDiagnostic> diagnostics = preflightDiagnostics(validation,
                diagnostic -> relevantToConfigBinding(diagnostic, configPath, operatorPath,
                        request, nodeIndexes));
        return new VisualConnectionCheckResult(diagnostics.stream().noneMatch(VisualDiagnostic::error),
                edge, "", diagnostics, validation);
    }

    private VisualConnectionCheckResult checkContextBinding(VisualConnectionCheckRequest request,
                                                            GraphDraft.DraftEdge edge) {
        int targetIndex = targetNodeIndex(request.draft(), request.target().nodeId());
        if (targetIndex < 0) {
            return new VisualConnectionCheckResult(false, edge, List.of(
                    VisualDiagnostic.error("visual.edge.unknownTarget",
                            "Edge target node does not exist: " + request.target().nodeId(),
                            "/target/nodeId")
            ));
        }

        String inputKey = previewBindingKey(request.draft(), request.target());
        GraphDraft.Binding binding = withTargetUnionBranch(request, GraphDraft.Binding.contextPath(
                request.source().path(),
                request.target().port(),
                request.target().path()
        ));
        GraphDraft candidate = draftWithPreviewBinding(request.draft(), targetIndex, inputKey, binding,
                targetOperator(request.draft(), request.target().nodeId()));
        String bindingPath = "/nodes/" + targetIndex + "/inputs/" + inputKey;
        String operatorPath = "/nodes/" + targetIndex + "/operatorRef";

        VisualValidationResult validation = validator.validate(candidate);
        List<VisualDiagnostic> diagnostics = preflightDiagnostics(validation,
                diagnostic -> relevantToContextBinding(diagnostic, bindingPath, operatorPath));
        return new VisualConnectionCheckResult(diagnostics.stream().noneMatch(VisualDiagnostic::error),
                edge, inputKey, diagnostics, validation);
    }

    private static GraphDraft draftWithPreviewEdge(GraphDraft draft, GraphDraft.DraftEdge edge) {
        List<GraphDraft.DraftEdge> edges = new ArrayList<>(draft.edges());
        edges.add(edge);
        return new GraphDraft(
                draft.schemaVersion(),
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                draft.status(),
                draft.inputSchema(),
                draft.nodes(),
                edges,
                draft.visualLayout(),
                draft.output(),
                draft.operatorFingerprints(),
                draft.operatorSnapshots(),
                draft.revisionMetadata()
        );
    }

    private static GraphDraft.Binding withTargetUnionBranch(VisualConnectionCheckRequest request,
                                                            GraphDraft.Binding binding) {
        GraphDraft.UnionBranchSelection selection = request.targetUnionBranch();
        Map<String, GraphDraft.UnionBranchSelection> nestedSelections = new LinkedHashMap<>(
                binding.targetUnionBranches());
        nestedSelections.putAll(request.targetUnionBranches());
        if ((selection == null || !selection.selected()) && nestedSelections.isEmpty()) {
            return binding;
        }
        return new GraphDraft.Binding(
                binding.kind(),
                binding.value(),
                binding.path(),
                binding.nodeId(),
                binding.sourcePort(),
                binding.targetPort(),
                binding.targetPath(),
                binding.expr(),
                binding.fields(),
                selection != null && selection.selected() ? selection : binding.targetUnionBranch(),
                nestedSelections
        );
    }

    private static GraphDraft draftWithPreviewConfigExpression(GraphDraft draft,
                                                               int targetIndex,
                                                               String configPath,
                                                               String expression) {
        List<GraphDraft.DraftNode> nodes = new ArrayList<>(draft.nodes());
        GraphDraft.DraftNode target = nodes.get(targetIndex);
        Map<String, Object> config = new LinkedHashMap<>(target.config());
        putNestedConfigValue(config, configPath, Map.of(
                "kind", "expression",
                "expr", expression
        ));
        nodes.set(targetIndex, new GraphDraft.DraftNode(
                target.id(),
                target.operatorRef(),
                target.label(),
                target.inputs(),
                config,
                target.position()
        ));
        return new GraphDraft(
                draft.schemaVersion(),
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                draft.status(),
                draft.inputSchema(),
                nodes,
                draft.edges(),
                draft.visualLayout(),
                draft.output(),
                draft.operatorFingerprints(),
                draft.operatorSnapshots(),
                draft.revisionMetadata()
        );
    }

    private static void putNestedConfigValue(Map<String, Object> config, String path, Object value) {
        List<String> segments = pathSegments(path);
        if (segments.isEmpty()) {
            return;
        }
        Object current = config;
        for (int i = 0; i < segments.size() - 1; i++) {
            String segment = segments.get(i);
            String nextSegment = segments.get(i + 1);
            Object child = configContainerForNext(configSegmentValue(current, segment), nextSegment);
            setConfigSegmentValue(current, segment, child);
            current = child;
        }
        setConfigSegmentValue(current, segments.get(segments.size() - 1), value);
    }

    private static boolean isConfigBindingMap(Map<?, ?> map) {
        return map.get("kind") instanceof String;
    }

    private static Object configContainerForNext(Object existing, String nextSegment) {
        if (arrayIndexSegment(nextSegment) != null) {
            return existing instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
        }
        return existing instanceof Map<?, ?> map && !isConfigBindingMap(map)
                ? mutableStringMap(map)
                : new LinkedHashMap<String, Object>();
    }

    private static Object configSegmentValue(Object container, String segment) {
        if (container instanceof Map<?, ?> map) {
            return map.get(segment);
        }
        if (container instanceof List<?> list) {
            Integer index = arrayIndexSegment(segment);
            return index != null && index < list.size() ? list.get(index) : null;
        }
        return null;
    }

    private static void setConfigSegmentValue(Object container, String segment, Object value) {
        if (container instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> current = (Map<String, Object>) map;
            current.put(segment, value);
            return;
        }
        if (container instanceof List<?> list) {
            Integer index = arrayIndexSegment(segment);
            if (index == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object> current = (List<Object>) list;
            while (current.size() <= index) {
                current.add(null);
            }
            current.set(index, value);
        }
    }

    private static Map<String, Object> mutableStringMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static List<String> pathSegments(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("\\.")) {
            if (!segment.isBlank()) {
                segments.add(segment.trim());
            }
        }
        return segments;
    }

    private static String diagnosticPath(String path) {
        return String.join("/", pathSegments(path));
    }

    private static GraphDraft draftWithPreviewBinding(GraphDraft draft,
                                                      int targetIndex,
                                                      String inputKey,
                                                      GraphDraft.Binding binding,
                                                      Optional<OperatorDefinition> targetOperator) {
        List<GraphDraft.DraftNode> nodes = new ArrayList<>(draft.nodes());
        GraphDraft.DraftNode target = nodes.get(targetIndex);
        Map<String, GraphDraft.Binding> inputs = new LinkedHashMap<>(target.inputs());
        inputs.entrySet().removeIf(entry -> !entry.getKey().equals(inputKey)
                && sameBindingTarget(entry.getKey(), entry.getValue(), inputKey, binding, targetOperator));
        inputs.put(inputKey, binding);
        nodes.set(targetIndex, new GraphDraft.DraftNode(
                target.id(),
                target.operatorRef(),
                target.label(),
                inputs,
                target.config(),
                target.position()
        ));
        return new GraphDraft(
                draft.schemaVersion(),
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                draft.status(),
                draft.inputSchema(),
                nodes,
                draft.edges(),
                draft.visualLayout(),
                draft.output(),
                draft.operatorFingerprints(),
                draft.operatorSnapshots(),
                draft.revisionMetadata()
        );
    }

    private static boolean sameBindingTarget(String leftKey,
                                             GraphDraft.Binding left,
                                             String rightKey,
                                             GraphDraft.Binding right,
                                             Optional<OperatorDefinition> targetOperator) {
        Optional<BindingTarget> leftTarget = resolvedBindingTarget(targetOperator, leftKey, left);
        Optional<BindingTarget> rightTarget = resolvedBindingTarget(targetOperator, rightKey, right);
        if (leftTarget.isPresent() && rightTarget.isPresent()) {
            BindingTarget leftValue = leftTarget.get();
            BindingTarget rightValue = rightTarget.get();
            return leftValue.equals(rightValue)
                    || (leftValue.overlaps(rightValue)
                    && replaceableOverlappingBinding(leftKey, leftValue, rightKey, rightValue));
        }
        return compatibleTargetPorts(left.targetPort(), right.targetPort())
                && bindingTargetPath(leftKey, left).equals(bindingTargetPath(rightKey, right));
    }

    private static boolean replaceableOverlappingBinding(String leftKey,
                                                         BindingTarget left,
                                                         String rightKey,
                                                         BindingTarget right) {
        if (!left.path().isBlank() && !right.path().isBlank()) {
            return false;
        }
        return leftKey.equals(rightKey) || isGenericInputPort(left.port()) || isGenericInputPort(right.port());
    }

    private static boolean isGenericInputPort(String port) {
        return port == null || port.isBlank() || "inputs".equals(port) || "input".equals(port);
    }

    private static Optional<BindingTarget> resolvedBindingTarget(Optional<OperatorDefinition> operator,
                                                                 String inputKey,
                                                                 GraphDraft.Binding binding) {
        if (operator.isEmpty()) {
            return Optional.empty();
        }
        String path = bindingTargetPath(inputKey, binding);
        return resolveInputPort(operator.get(), binding.targetPort(), path)
                .map(port -> new BindingTarget(port.name(), path));
    }

    private static Optional<OperatorDefinition.Port> resolveInputPort(OperatorDefinition operator,
                                                                      String portName,
                                                                      String inputName) {
        if (portName != null && !portName.isBlank()) {
            return operator.ports().inputs().stream()
                    .filter(port -> portName.equals(port.name()))
                    .findFirst();
        }
        List<OperatorDefinition.Port> ports = operator.ports().inputs();
        if (ports.isEmpty()) {
            return Optional.of(new OperatorDefinition.Port("inputs", SchemaEnvelope.opaque(), false,
                    "Implicit opaque port."));
        }
        if (ports.size() == 1) {
            return Optional.of(ports.getFirst());
        }
        List<OperatorDefinition.Port> matches = ports.stream()
                .filter(port -> schemaAtPath(port.schema().schema(), inputName) != null)
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static boolean compatibleTargetPorts(String left, String right) {
        return left.equals(right) || left.isBlank() || right.isBlank();
    }

    private static String bindingTargetPath(String inputKey, GraphDraft.Binding binding) {
        if (!binding.targetPath().isBlank()) {
            return binding.targetPath();
        }
        if (!binding.targetPort().isBlank() && binding.targetPort().equals(inputKey)) {
            return "";
        }
        return inputKey;
    }

    private static GraphDraft draftWithPreviewBindingAndEdge(GraphDraft draft,
                                                             int targetIndex,
                                                             String inputKey,
                                                             GraphDraft.Binding binding,
                                                             GraphDraft.DraftEdge edge,
                                                             Optional<OperatorDefinition> targetOperator) {
        GraphDraft withBinding = draftWithPreviewBinding(draft, targetIndex, inputKey, binding, targetOperator);
        List<GraphDraft.DraftEdge> edges = new ArrayList<>();
        for (GraphDraft.DraftEdge existing : withBinding.edges()) {
            if (!sameTargetEndpoint(existing.target(), edge.target())) {
                edges.add(existing);
            }
        }
        edges.add(edge);
        return new GraphDraft(
                withBinding.schemaVersion(),
                withBinding.draftId(),
                withBinding.revision(),
                withBinding.graphName(),
                withBinding.tenantId(),
                withBinding.namespace(),
                withBinding.environment(),
                withBinding.status(),
                withBinding.inputSchema(),
                withBinding.nodes(),
                edges,
                withBinding.visualLayout(),
                withBinding.output(),
                withBinding.operatorFingerprints(),
                withBinding.operatorSnapshots(),
                withBinding.revisionMetadata()
        );
    }

    private record BindingTarget(String port, String path) {

        private boolean overlaps(BindingTarget other) {
            if (!port.equals(other.port)) {
                return false;
            }
            if (path.isBlank() || other.path.isBlank()) {
                return true;
            }
            return path.equals(other.path)
                    || path.startsWith(other.path + ".")
                    || other.path.startsWith(path + ".");
        }
    }

    private static boolean sameTargetEndpoint(GraphDraft.Endpoint left, GraphDraft.Endpoint right) {
        return left.nodeId().equals(right.nodeId())
                && left.port().equals(right.port())
                && left.path().equals(right.path());
    }

    private static boolean hasSameConnection(GraphDraft draft, GraphDraft.DraftEdge edge) {
        return draft.edges().stream()
                .anyMatch(existing -> existing.kind().equals(edge.kind())
                        && sameEndpoint(existing.source(), edge.source())
                        && sameEndpoint(existing.target(), edge.target())
                        && (!"route".equals(edge.kind()) || existing.condition().equals(edge.condition())));
    }

    private static boolean sameEndpoint(GraphDraft.Endpoint left, GraphDraft.Endpoint right) {
        return left.nodeId().equals(right.nodeId())
                && left.port().equals(right.port())
                && left.path().equals(right.path());
    }

    private static String connectionLabel(GraphDraft.DraftEdge edge) {
        return "%s.%s.%s -> %s.%s.%s%s".formatted(
                edge.source().nodeId(),
                edge.source().port(),
                edge.source().path(),
                edge.target().nodeId(),
                edge.target().port(),
                edge.target().path(),
                "route".equals(edge.kind()) && !edge.condition().isBlank()
                        ? " when " + edge.condition()
                        : "");
    }

    private static Map<String, Integer> nodeIndexes(GraphDraft draft) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < draft.nodes().size(); i++) {
            indexes.putIfAbsent(draft.nodes().get(i).id(), i);
        }
        return indexes;
    }

    private static int targetNodeIndex(GraphDraft draft, String nodeId) {
        for (int i = 0; i < draft.nodes().size(); i++) {
            if (draft.nodes().get(i).id().equals(nodeId)) {
                return i;
            }
        }
        return -1;
    }

    private String previewBindingKey(GraphDraft draft, GraphDraft.Endpoint target) {
        if (target.path() != null && !target.path().isBlank()) {
            if (!target.port().isBlank() && inputPathDeclaredByMultiplePorts(draft, target.nodeId(), target.path())) {
                return target.port() + "." + target.path();
            }
            return target.path();
        }
        return target.port() == null || target.port().isBlank() ? "input" : target.port();
    }

    private boolean inputPathDeclaredByMultiplePorts(GraphDraft draft, String nodeId, String path) {
        Optional<OperatorDefinition> operator = targetOperator(draft, nodeId);
        if (operator.isEmpty()) {
            return false;
        }
        long matches = operator.get().ports().inputs().stream()
                .filter(port -> schemaAtPath(port.schema().schema(), path) != null)
                .limit(2)
                .count();
        return matches > 1;
    }

    private Optional<OperatorDefinition> targetOperator(GraphDraft draft, String nodeId) {
        return draft.nodes().stream()
                .filter(node -> node.id().equals(nodeId))
                .findFirst()
                .flatMap(node -> catalog.find(node.operatorRef()));
    }

    private static Map<String, Object> schemaAtPath(Map<String, Object> schema, String path) {
        if (path == null || path.isBlank()) {
            return schema;
        }
        Map<String, Object> current = schema == null ? Map.of() : schema;
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            if ("array".equals(schemaType(current))) {
                Integer index = arrayIndexSegment(segment);
                if (index == null) {
                    return null;
                }
                Map<String, Object> item = arrayItemSchemaForIndex(current, index);
                if (item == null) {
                    return null;
                }
                current = item;
                continue;
            }
            Map<String, Object> properties = propertiesOf(current);
            Map<String, Object> next = objectSchema(properties.get(segment));
            if (next == null) {
                if (!propertyNameAllowedBySchema(current, segment)) {
                    return null;
                }
                next = patternPropertySchema(current, segment);
            }
            if (next == null) {
                next = additionalPropertySchema(current);
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    private static Map<String, Object> arrayItemSchemaForIndex(Map<String, Object> schema, int index) {
        Object prefixItems = schema.get("prefixItems");
        if (prefixItems instanceof List<?> list && index < list.size()) {
            return objectSchema(list.get(index));
        }
        return objectSchema(schema.get("items"));
    }

    private static Integer arrayIndexSegment(String segment) {
        if (!ARRAY_INDEX.matcher(segment).matches()) {
            return null;
        }
        try {
            int index = Integer.parseInt(segment);
            return index < 0 ? null : index;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Map<String, Object> patternPropertySchema(Map<String, Object> schema, String propertyName) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map.Entry<String, Object> entry : propertiesMap(schema.get("patternProperties")).entrySet()) {
            try {
                if (Pattern.compile(entry.getKey()).matcher(propertyName).find()) {
                    Map<String, Object> candidate = objectSchema(entry.getValue());
                    if (candidate != null) {
                        matches.add(candidate);
                    }
                }
            } catch (PatternSyntaxException ignored) {
                return null;
            }
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static boolean propertyNameAllowedBySchema(Map<String, Object> schema, String propertyName) {
        Map<String, Object> propertyNameSchema = objectSchema(schema.get("propertyNames"));
        if (propertyNameSchema == null) {
            return true;
        }
        Map<String, Object> effectiveSchema = new LinkedHashMap<>(propertyNameSchema);
        if (!effectiveSchema.containsKey("type") && !effectiveSchema.containsKey("kind")) {
            effectiveSchema.put("type", "string");
        }
        return VisualSchemaCompatibility.valueMatchesSchema(propertyName, effectiveSchema);
    }

    private static Map<String, Object> additionalPropertySchema(Map<String, Object> schema) {
        Object residual = residualPropertiesPolicy(schema);
        if (Boolean.TRUE.equals(residual)) {
            return Map.of();
        }
        return objectSchema(residual);
    }

    private static Object residualPropertiesPolicy(Map<String, Object> schema) {
        if (schema.containsKey("additionalProperties")) {
            return schema.get("additionalProperties");
        }
        return schema.get("unevaluatedProperties");
    }

    private static Map<String, Object> propertiesOf(Map<String, Object> schema) {
        return propertiesMap(schema.get("properties"));
    }

    private static Map<String, Object> propertiesMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        map.forEach((key, value) -> properties.put(String.valueOf(key), value));
        return properties;
    }

    private static Map<String, Object> objectSchema(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        map.forEach((key, value) -> schema.put(String.valueOf(key), value));
        return schema;
    }

    private static String schemaType(Map<String, Object> schema) {
        Object type = schema.get("kind");
        if (type == null) {
            type = schema.get("type");
        }
        if (type instanceof List<?> types) {
            return types.stream()
                    .filter(item -> item != null && !"null".equals(String.valueOf(item)))
                    .map(String::valueOf)
                    .findFirst()
                    .orElse("null");
        }
        if (type == null && schema.containsKey("properties")) {
            return "object";
        }
        if (type == null && schema.containsKey("items")) {
            return "array";
        }
        return type == null ? "" : String.valueOf(type);
    }

    private static boolean relevantToConnection(VisualDiagnostic diagnostic,
                                                int previewIndex,
                                                String bindingPath,
                                                String operatorPath,
                                                VisualConnectionCheckRequest request,
                                                Map<String, Integer> nodeIndexes) {
        String target = diagnostic.target();
        if (target.startsWith("/edges/" + previewIndex) || "visual.edge.cycle".equals(diagnostic.code())) {
            return true;
        }
        return targetAtOrBelow(target, bindingPath)
                || targetAtOrBelow(target, operatorPath)
                || endpointNodeDiagnostic(target, request.source().nodeId(), nodeIndexes)
                || endpointNodeDiagnostic(target, request.target().nodeId(), nodeIndexes);
    }

    private static boolean relevantToContextBinding(VisualDiagnostic diagnostic,
                                                    String bindingPath,
                                                    String operatorPath) {
        String target = diagnostic.target();
        return targetAtOrBelow(target, bindingPath) || targetAtOrBelow(target, operatorPath);
    }

    private static boolean relevantToConfigBinding(VisualDiagnostic diagnostic,
                                                   String configPath,
                                                   String operatorPath,
                                                   VisualConnectionCheckRequest request,
                                                   Map<String, Integer> nodeIndexes) {
        String target = diagnostic.target();
        return targetAtOrBelow(target, configPath)
                || (targetAtOrAbove(target, configPath) && !target.endsWith("/config"))
                || targetAtOrBelow(target, operatorPath)
                || endpointNodeDiagnostic(target, request.source().nodeId(), nodeIndexes)
                || endpointNodeDiagnostic(target, request.target().nodeId(), nodeIndexes);
    }

    private static boolean relevantToDependencyEdge(VisualDiagnostic diagnostic,
                                                    int previewIndex,
                                                    VisualConnectionCheckRequest request,
                                                    Map<String, Integer> nodeIndexes) {
        String target = diagnostic.target();
        return target.startsWith("/edges/" + previewIndex)
                || "visual.edge.cycle".equals(diagnostic.code())
                || endpointNodeDiagnostic(target, request.source().nodeId(), nodeIndexes)
                || endpointNodeDiagnostic(target, request.target().nodeId(), nodeIndexes);
    }

    private static List<VisualDiagnostic> preflightDiagnostics(VisualValidationResult validation,
                                                               Predicate<VisualDiagnostic> relevant) {
        return validation.diagnostics().stream()
                .filter(diagnostic -> relevant.test(diagnostic) || globalBlockingDiagnostic(diagnostic))
                .toList();
    }

    private static boolean globalBlockingDiagnostic(VisualDiagnostic diagnostic) {
        if (!diagnostic.error()) {
            return false;
        }
        String target = diagnostic.target();
        return targetAtOrBelow(target, "/schemaVersion")
                || targetAtOrBelow(target, "/status")
                || targetAtOrBelow(target, "/inputSchema");
    }

    private static boolean targetAtOrBelow(String target, String path) {
        return target.equals(path) || target.startsWith(path + "/");
    }

    private static boolean targetAtOrAbove(String target, String path) {
        return target.equals(path) || path.startsWith(target + "/");
    }

    private static boolean endpointNodeDiagnostic(String target, String nodeId, Map<String, Integer> nodeIndexes) {
        Integer index = nodeIndexes.get(nodeId);
        return index != null && target.startsWith("/nodes/" + index + "/operatorRef");
    }

    private static String expressionForSource(GraphDraft.Endpoint source) {
        if (CONTEXT_SOURCE_NODE_ID.equals(source.nodeId())) {
            return "ctx" + dslReferenceSuffixForSchemaPath(source.path());
        }
        String portSegment = source.port().isBlank() || "output".equals(source.port()) ? "" : "." + source.port();
        return source.nodeId() + ".output" + portSegment + dslReferenceSuffixForSchemaPath(source.path());
    }

    private static String dslReferenceSuffixForSchemaPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        StringBuilder suffix = new StringBuilder();
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            if (arrayIndexSegment(segment) != null) {
                suffix.append('[').append(segment).append(']');
            } else {
                suffix.append('.').append(segment);
            }
        }
        return suffix.toString();
    }
}
