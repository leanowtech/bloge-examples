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
                request.source(), request.target());
        if (CONTEXT_SOURCE_NODE_ID.equals(request.source().nodeId())) {
            return checkContextBinding(request, edge);
        }

        GraphDraft candidate = draftWithPreviewEdge(request.draft(), edge);
        int previewIndex = candidate.edges().size() - 1;
        Map<String, Integer> nodeIndexes = nodeIndexes(candidate);

        VisualValidationResult validation = validator.validateConnectionPreview(candidate);
        List<VisualDiagnostic> diagnostics = validation.diagnostics().stream()
                .filter(diagnostic -> relevantToConnection(diagnostic, previewIndex, request, nodeIndexes))
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
                                                VisualConnectionCheckRequest request,
                                                Map<String, Integer> nodeIndexes) {
        String target = diagnostic.target();
        if (target.startsWith("/edges/" + previewIndex) || "visual.edge.cycle".equals(diagnostic.code())) {
            return true;
        }
        return endpointNodeDiagnostic(target, request.source().nodeId(), nodeIndexes)
                || endpointNodeDiagnostic(target, request.target().nodeId(), nodeIndexes);
    }

    private static boolean relevantToContextBinding(VisualDiagnostic diagnostic,
                                                    String bindingPath,
                                                    String operatorPath) {
        String target = diagnostic.target();
        return targetAtOrBelow(target, bindingPath) || targetAtOrBelow(target, operatorPath);
    }

    private static boolean targetAtOrBelow(String target, String path) {
        return target.equals(path) || target.startsWith(path + "/");
    }

    private static boolean endpointNodeDiagnostic(String target, String nodeId, Map<String, Integer> nodeIndexes) {
        Integer index = nodeIndexes.get(nodeId);
        return index != null && target.startsWith("/nodes/" + index + "/operatorRef");
    }
}
