package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side schema gate for interactive canvas connections.
 */
@Service
public class VisualConnectionCheckService {

    private static final String PREVIEW_EDGE_ID = "__preview_connection";
    private static final String CONTEXT_SOURCE_NODE_ID = "__ctx";
    private static final String CONFIG_TARGET_PORT = "config";

    private final GraphDraftValidator validator;

    /**
     * @param validator graph draft validator
     */
    public VisualConnectionCheckService(GraphDraftValidator validator) {
        this.validator = validator;
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
        String inputKey = previewBindingKey(request.target());
        GraphDraft.Binding binding = GraphDraft.Binding.nodePath(
                request.source().nodeId(),
                request.source().port(),
                request.source().path(),
                request.target().port(),
                request.target().path()
        );
        GraphDraft candidate = draftWithPreviewBindingAndEdge(request.draft(), targetIndex, inputKey, binding, edge);
        int previewIndex = candidate.edges().size() - 1;
        String bindingPath = "/nodes/" + targetIndex + "/inputs/" + inputKey;
        String operatorPath = "/nodes/" + targetIndex + "/operatorRef";
        Map<String, Integer> nodeIndexes = nodeIndexes(candidate);

        VisualValidationResult validation = validator.validate(candidate);
        List<VisualDiagnostic> diagnostics = validation.diagnostics().stream()
                .filter(diagnostic -> relevantToConnection(diagnostic, previewIndex, bindingPath, operatorPath,
                        request, nodeIndexes))
                .toList();
        return new VisualConnectionCheckResult(diagnostics.stream().noneMatch(VisualDiagnostic::error),
                edge, diagnostics);
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
        List<VisualDiagnostic> diagnostics = validation.diagnostics().stream()
                .filter(diagnostic -> relevantToDependencyEdge(diagnostic, previewIndex, request, nodeIndexes))
                .toList();
        return new VisualConnectionCheckResult(diagnostics.stream().noneMatch(VisualDiagnostic::error),
                edge, diagnostics);
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
        List<VisualDiagnostic> diagnostics = validation.diagnostics().stream()
                .filter(diagnostic -> relevantToDependencyEdge(diagnostic, previewIndex, request, nodeIndexes))
                .toList();
        return new VisualConnectionCheckResult(diagnostics.stream().noneMatch(VisualDiagnostic::error),
                edge, diagnostics);
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
        List<VisualDiagnostic> diagnostics = validation.diagnostics().stream()
                .filter(diagnostic -> relevantToConfigBinding(diagnostic, configPath, operatorPath,
                        request, nodeIndexes))
                .toList();
        return new VisualConnectionCheckResult(diagnostics.stream().noneMatch(VisualDiagnostic::error),
                edge, diagnostics);
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

        String inputKey = previewBindingKey(request.target());
        GraphDraft.Binding binding = GraphDraft.Binding.contextPath(
                request.source().path(),
                request.target().port(),
                request.target().path()
        );
        GraphDraft candidate = draftWithPreviewBinding(request.draft(), targetIndex, inputKey, binding);
        String bindingPath = "/nodes/" + targetIndex + "/inputs/" + inputKey;
        String operatorPath = "/nodes/" + targetIndex + "/operatorRef";

        VisualValidationResult validation = validator.validate(candidate);
        List<VisualDiagnostic> diagnostics = validation.diagnostics().stream()
                .filter(diagnostic -> relevantToContextBinding(diagnostic, bindingPath, operatorPath))
                .toList();
        return new VisualConnectionCheckResult(diagnostics.stream().noneMatch(VisualDiagnostic::error),
                edge, diagnostics);
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
                draft.operatorFingerprints()
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
                draft.operatorFingerprints()
        );
    }

    private static void putNestedConfigValue(Map<String, Object> config, String path, Object value) {
        List<String> segments = pathSegments(path);
        if (segments.isEmpty()) {
            return;
        }
        Map<String, Object> current = config;
        for (int i = 0; i < segments.size() - 1; i++) {
            String segment = segments.get(i);
            Object existing = current.get(segment);
            Map<String, Object> child = existing instanceof Map<?, ?> map && !isConfigBindingMap(map)
                    ? mutableStringMap(map)
                    : new LinkedHashMap<>();
            current.put(segment, child);
            current = child;
        }
        current.put(segments.get(segments.size() - 1), value);
    }

    private static boolean isConfigBindingMap(Map<?, ?> map) {
        return map.get("kind") instanceof String;
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
                                                      GraphDraft.Binding binding) {
        List<GraphDraft.DraftNode> nodes = new ArrayList<>(draft.nodes());
        GraphDraft.DraftNode target = nodes.get(targetIndex);
        Map<String, GraphDraft.Binding> inputs = new LinkedHashMap<>(target.inputs());
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
                draft.operatorFingerprints()
        );
    }

    private static GraphDraft draftWithPreviewBindingAndEdge(GraphDraft draft,
                                                             int targetIndex,
                                                             String inputKey,
                                                             GraphDraft.Binding binding,
                                                             GraphDraft.DraftEdge edge) {
        GraphDraft withBinding = draftWithPreviewBinding(draft, targetIndex, inputKey, binding);
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
                withBinding.operatorFingerprints()
        );
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

    private static String previewBindingKey(GraphDraft.Endpoint target) {
        if (target.path() != null && !target.path().isBlank()) {
            return target.path();
        }
        return target.port() == null || target.port().isBlank() ? "input" : target.port();
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

    private static boolean targetAtOrBelow(String target, String path) {
        return target.equals(path) || target.startsWith(path + "/");
    }

    private static boolean endpointNodeDiagnostic(String target, String nodeId, Map<String, Integer> nodeIndexes) {
        Integer index = nodeIndexes.get(nodeId);
        return index != null && target.startsWith("/nodes/" + index + "/operatorRef");
    }

    private static String expressionForSource(GraphDraft.Endpoint source) {
        if (CONTEXT_SOURCE_NODE_ID.equals(source.nodeId())) {
            return source.path().isBlank() ? "ctx" : "ctx." + source.path();
        }
        String portSegment = source.port().isBlank() || "output".equals(source.port()) ? "" : "." + source.port();
        String pathSegment = source.path().isBlank() ? "" : "." + source.path();
        return source.nodeId() + ".output" + portSegment + pathSegment;
    }
}
