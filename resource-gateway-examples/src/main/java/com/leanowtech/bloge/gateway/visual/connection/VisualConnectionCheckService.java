package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaIntrospection;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;
import com.leanowtech.bloge.gateway.visual.validation.VisualRuntimeBindingRequirementKey;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Server-side schema gate for interactive canvas connections.
 */
@Service
public class VisualConnectionCheckService {

    private static final String PREVIEW_EDGE_ID = "__preview_connection";
    private static final String CONTEXT_SOURCE_NODE_ID = "__ctx";
    private static final String CONFIG_TARGET_PORT = "config";
    private static final int MAX_SCHEMA_CANDIDATE_PATHS = 64;
    private static final int MAX_SCHEMA_CANDIDATE_DEPTH = 4;
    private static final int FULL_CANDIDATE_CHECK_LIMIT = 80;

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
        Optional<OperatorDefinition> targetOperator = targetOperator(request.draft(), request.target().nodeId());
        List<String> replacedInputKeys = replacedInputKeys(request.draft(), targetIndex, inputKey, binding,
                targetOperator);
        List<String> replacedEdgeIds = replacedEdgeIds(request.draft(), edge);
        GraphDraft candidate = draftWithPreviewBindingAndEdge(request.draft(), targetIndex, inputKey, binding, edge,
                targetOperator);
        int previewIndex = candidate.edges().size() - 1;
        String bindingPath = "/nodes/" + targetIndex + "/inputs/" + inputKey;
        String operatorPath = "/nodes/" + targetIndex + "/operatorRef";
        Map<String, Integer> nodeIndexes = nodeIndexes(candidate);

        VisualValidationResult validation = validator.validate(candidate);
        List<VisualDiagnostic> diagnostics = preflightDiagnostics(validation,
                diagnostic -> relevantToConnection(diagnostic, previewIndex, bindingPath, operatorPath,
                        request, nodeIndexes));
        boolean accepted = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        return new VisualConnectionCheckResult(accepted, edge, inputKey, diagnostics, validation,
                VisualConnectionCheckResult.VisualConnectionCheckSummary.from(accepted, edge, inputKey,
                        diagnostics, validation, replacedInputKeys, replacedEdgeIds,
                        operatorLibraryIdsByOperatorRef()));
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
        boolean accepted = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        return new VisualConnectionCheckResult(accepted, edge, "", diagnostics, validation,
                VisualConnectionCheckResult.VisualConnectionCheckSummary.from(accepted, edge, "",
                        diagnostics, validation, List.of(), List.of(), operatorLibraryIdsByOperatorRef()));
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
        boolean accepted = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        return new VisualConnectionCheckResult(accepted, edge, "", diagnostics, validation,
                VisualConnectionCheckResult.VisualConnectionCheckSummary.from(accepted, edge, "",
                        diagnostics, validation, List.of(), List.of(), operatorLibraryIdsByOperatorRef()));
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
        boolean accepted = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        return new VisualConnectionCheckResult(accepted, edge, "", diagnostics, validation,
                VisualConnectionCheckResult.VisualConnectionCheckSummary.from(accepted, edge, "",
                        diagnostics, validation, List.of(), List.of(), operatorLibraryIdsByOperatorRef()));
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
        Optional<OperatorDefinition> targetOperator = targetOperator(request.draft(), request.target().nodeId());
        List<String> replacedInputKeys = replacedInputKeys(request.draft(), targetIndex, inputKey, binding,
                targetOperator);
        GraphDraft candidate = draftWithPreviewBinding(request.draft(), targetIndex, inputKey, binding,
                targetOperator);
        String bindingPath = "/nodes/" + targetIndex + "/inputs/" + inputKey;
        String operatorPath = "/nodes/" + targetIndex + "/operatorRef";

        VisualValidationResult validation = validator.validate(candidate);
        List<VisualDiagnostic> diagnostics = preflightDiagnostics(validation,
                diagnostic -> relevantToContextBinding(diagnostic, bindingPath, operatorPath));
        boolean accepted = diagnostics.stream().noneMatch(VisualDiagnostic::error);
        return new VisualConnectionCheckResult(accepted, edge, inputKey, diagnostics, validation,
                VisualConnectionCheckResult.VisualConnectionCheckSummary.from(accepted, edge, inputKey,
                        diagnostics, validation, replacedInputKeys, List.of(), operatorLibraryIdsByOperatorRef()));
    }

    private Map<String, String> operatorLibraryIdsByOperatorRef() {
        return catalog == null ? Map.of() : catalog.operatorLibraryIdsByOperatorRef(true);
    }

    /**
     * Discovers target candidates for an interactive source drag by reusing the authoritative check path.
     *
     * @param request connection candidate discovery request
     * @return schema-aware target candidates and preflight decisions
     */
    public VisualConnectionCandidatesResult candidates(VisualConnectionCandidatesRequest request) {
        if (request == null || request.draft() == null) {
            return new VisualConnectionCandidatesResult(
                    VisualConnectionCandidatesResult.SCHEMA_VERSION,
                    request == null ? GraphDraft.Endpoint.empty() : request.source(),
                    request == null ? "data" : request.kind(),
                    0,
                    0,
                    0,
                    0,
                    false,
                    List.of(),
                    List.of(VisualDiagnostic.error("visual.draft.missing",
                            "Graph draft is required.", "/draft"))
            );
        }
        if (request.source().nodeId().isBlank()) {
            return new VisualConnectionCandidatesResult(
                    VisualConnectionCandidatesResult.SCHEMA_VERSION,
                    request.source(),
                    request.kind(),
                    0,
                    0,
                    0,
                    0,
                    false,
                    List.of(),
                    List.of(VisualDiagnostic.error("visual.connection.sourceMissing",
                            "Connection source endpoint is required.", "/source"))
            );
        }
        if (!CONTEXT_SOURCE_NODE_ID.equals(request.source().nodeId())
                && request.draft().nodes().stream().noneMatch(node -> node.id().equals(request.source().nodeId()))) {
            return new VisualConnectionCandidatesResult(
                    VisualConnectionCandidatesResult.SCHEMA_VERSION,
                    request.source(),
                    request.kind(),
                    0,
                    0,
                    0,
                    0,
                    false,
                    List.of(),
                    List.of(VisualDiagnostic.error("visual.edge.unknownSource",
                            "Connection source node does not exist: " + request.source().nodeId(),
                            "/source/nodeId"))
            );
        }

        List<ConnectionCandidateTarget> targets = candidateTargets(request.draft(), request);
        if (request.includeRejected() && targets.size() > FULL_CANDIDATE_CHECK_LIMIT) {
            return pagedCandidates(request, targets);
        }
        List<VisualConnectionCandidatesResult.ConnectionCandidate> candidates = new ArrayList<>();
        for (ConnectionCandidateTarget target : targets) {
            candidates.add(candidateFor(request, target));
        }

        int acceptedCount = 0;
        for (VisualConnectionCandidatesResult.ConnectionCandidate candidate : candidates) {
            if (candidate.accepted()) {
                acceptedCount++;
            }
        }
        int rejectedCount = candidates.size() - acceptedCount;
        List<VisualConnectionCandidatesResult.ConnectionCandidate> visible = candidates.stream()
                .filter(candidate -> request.includeRejected() || candidate.accepted())
                .toList();
        boolean truncated = visible.size() > request.offset() + request.limit();
        List<VisualConnectionCandidatesResult.ConnectionCandidate> window = visible.stream()
                .skip(request.offset())
                .limit(request.limit())
                .toList();
        return new VisualConnectionCandidatesResult(
                VisualConnectionCandidatesResult.SCHEMA_VERSION,
                request.source(),
                request.kind(),
                request.offset(),
                candidates.size(),
                acceptedCount,
                rejectedCount,
                window.size(),
                truncated,
                window,
                List.of()
        );
    }

    private VisualConnectionCandidatesResult pagedCandidates(VisualConnectionCandidatesRequest request,
                                                             List<ConnectionCandidateTarget> targets) {
        int acceptedCount = 0;
        for (ConnectionCandidateTarget target : targets) {
            if (fastCandidateAccepted(request, target)) {
                acceptedCount++;
            }
        }
        int rejectedCount = targets.size() - acceptedCount;
        boolean truncated = targets.size() > request.offset() + request.limit();
        List<VisualConnectionCandidatesResult.ConnectionCandidate> window = targets.stream()
                .skip(request.offset())
                .limit(request.limit())
                .map(target -> candidateFor(request, target))
                .toList();
        return new VisualConnectionCandidatesResult(
                VisualConnectionCandidatesResult.SCHEMA_VERSION,
                request.source(),
                request.kind(),
                request.offset(),
                targets.size(),
                acceptedCount,
                rejectedCount,
                window.size(),
                truncated,
                window,
                List.of()
        );
    }

    private VisualConnectionCandidatesResult.ConnectionCandidate candidateFor(VisualConnectionCandidatesRequest request,
                                                                             ConnectionCandidateTarget target) {
        VisualConnectionCheckResult check = check(new VisualConnectionCheckRequest(
                request.draft(),
                request.source(),
                target.endpoint(),
                request.kind(),
                "",
                request.targetUnionBranch(),
                request.targetUnionBranches()
        ));
        return new VisualConnectionCandidatesResult.ConnectionCandidate(
                target.node().id(),
                target.node().label(),
                target.node().operatorRef(),
                target.surface(),
                check.edge() == null ? target.endpoint() : check.edge().target(),
                check.accepted(),
                check.bindingKey(),
                check.summary(),
                candidateExplanation(request, target, check),
                check.diagnostics()
        );
    }

    private boolean fastCandidateAccepted(VisualConnectionCandidatesRequest request,
                                          ConnectionCandidateTarget target) {
        if ("dependency".equals(request.kind()) || "route".equals(request.kind())
                || controlSurface(target.surface())) {
            GraphDraft.DraftEdge edge = new GraphDraft.DraftEdge(PREVIEW_EDGE_ID, request.kind(),
                    request.source(), target.endpoint(), "");
            return !hasSameConnection(request.draft(), edge);
        }
        Map<String, Object> sourceSchema = endpointSourceSchema(request.draft(), request.source());
        Map<String, Object> targetSchema = selectedUnionBranchSchema(
                endpointTargetSchema(request.draft(), target.endpoint(), target.surface()),
                request.targetUnionBranch()
        );
        if (sourceSchema == null || targetSchema == null) {
            return true;
        }
        return VisualSchemaCompatibility.schemasCompatible(sourceSchema, targetSchema);
    }

    private List<ConnectionCandidateTarget> candidateTargets(GraphDraft draft,
                                                             VisualConnectionCandidatesRequest request) {
        List<ConnectionCandidateTarget> targets = new ArrayList<>();
        String kind = request.kind();
        if ("dependency".equals(kind) || "route".equals(kind)) {
            for (GraphDraft.DraftNode node : draft.nodes()) {
                if (!candidateTargetMatches(request, node, kind)) {
                    continue;
                }
                targets.add(new ConnectionCandidateTarget(
                        node,
                        kind,
                        new GraphDraft.Endpoint(node.id(), kind, "")
                ));
            }
            return targets;
        }

        for (GraphDraft.DraftNode node : draft.nodes()) {
            if (!candidateTargetMatches(request, node, "")) {
                continue;
            }
            Optional<OperatorDefinition> operator = catalog.find(node.operatorRef());
            if (operator.isEmpty()) {
                continue;
            }
            if (candidateSurfaceMatches(request.targetSurface(), "input")) {
                for (OperatorDefinition.Port port : operator.get().ports().inputs()) {
                    List<String> paths = VisualSchemaIntrospection.connectableSchemaPaths(port.schema(),
                            MAX_SCHEMA_CANDIDATE_PATHS, MAX_SCHEMA_CANDIDATE_DEPTH);
                    if (canvasTargetSemantics(request) && paths.stream().anyMatch(Predicate.not(String::isBlank))) {
                        paths = paths.stream()
                                .filter(Predicate.not(String::isBlank))
                                .toList();
                    }
                    for (String path : paths) {
                        if (!candidateEndpointMatches(request, port.name(), path)) {
                            continue;
                        }
                        targets.add(new ConnectionCandidateTarget(
                                node,
                                "input",
                                new GraphDraft.Endpoint(node.id(), port.name(), path)
                        ));
                    }
                }
            }
            if (candidateSurfaceMatches(request.targetSurface(), "config")) {
                for (String path : VisualSchemaIntrospection.connectableSchemaPaths(operator.get().configSchema(),
                        MAX_SCHEMA_CANDIDATE_PATHS, MAX_SCHEMA_CANDIDATE_DEPTH)) {
                    if (path.isBlank() || !candidateEndpointMatches(request, CONFIG_TARGET_PORT, path)) {
                        continue;
                    }
                    targets.add(new ConnectionCandidateTarget(
                            node,
                            "config",
                            new GraphDraft.Endpoint(node.id(), CONFIG_TARGET_PORT, path)
                    ));
                }
            }
        }
        return targets;
    }

    private static boolean candidateTargetMatches(VisualConnectionCandidatesRequest request,
                                                  GraphDraft.DraftNode node,
                                                  String surface) {
        if (!request.targetNodeId().isBlank() && !request.targetNodeId().equals(node.id())) {
            return false;
        }
        return surface.isBlank() || candidateSurfaceMatches(request.targetSurface(), surface);
    }

    private static boolean candidateEndpointMatches(VisualConnectionCandidatesRequest request,
                                                    String port,
                                                    String path) {
        if (!request.targetPort().isBlank() && !request.targetPort().equals(port)) {
            return false;
        }
        return request.targetPath().isBlank() || request.targetPath().equals(path == null ? "" : path);
    }

    private static boolean candidateSurfaceMatches(String requested, String actual) {
        if (requested == null || requested.isBlank()) {
            return true;
        }
        if (requested.equals(actual)) {
            return true;
        }
        if ("canvas".equals(requested)) {
            return "input".equals(actual) || "config".equals(actual)
                    || "dependency".equals(actual) || "route".equals(actual);
        }
        return "control".equals(requested) && ("dependency".equals(actual) || "route".equals(actual));
    }

    private static boolean canvasTargetSemantics(VisualConnectionCandidatesRequest request) {
        return "canvas".equals(request.targetSurface());
    }

    private VisualConnectionCandidatesResult.ConnectionCandidateExplanation candidateExplanation(
            VisualConnectionCandidatesRequest request,
            ConnectionCandidateTarget target,
            VisualConnectionCheckResult check) {
        GraphDraft draft = request.draft();
        GraphDraft.Endpoint source = request.source();
        Map<String, Object> sourceSchema = endpointSourceSchema(draft, source);
        Map<String, Object> targetSchema = selectedUnionBranchSchema(
                endpointTargetSchema(draft, target.endpoint(), target.surface()),
                request.targetUnionBranch()
        );
        VisualDiagnostic firstDiagnostic = firstDiagnostic(check.diagnostics());
        VisualConnectionCheckResult.VisualConnectionCheckSummary summary = check.summary();
        int replacedBindings = summary == null ? 0 : summary.replacedBindingCount();
        int replacedEdges = summary == null ? 0 : summary.replacedEdgeCount();
        return new VisualConnectionCandidatesResult.ConnectionCandidateExplanation(
                endpointLabel(source, true),
                endpointLabel(target.endpoint(), false),
                schemaTypeLabel(sourceSchema, controlSurface(target.surface()) ? target.surface() : ""),
                schemaTypeLabel(targetSchema, controlSurface(target.surface()) ? target.surface() : ""),
                schemaKnown(sourceSchema),
                schemaKnown(targetSchema),
                "server-validator",
                candidateDecisionMessage(check, firstDiagnostic),
                firstDiagnostic == null ? "" : firstDiagnostic.code(),
                replacementSummary(replacedBindings, replacedEdges),
                replacedBindings,
                replacedEdges,
                targetRuntimeBindingImpact(target.node().id(), check.validation(), operatorLibraryIdsByOperatorRef())
        );
    }

    private static VisualConnectionCandidatesResult.ConnectionCandidateRuntimeBindingImpact targetRuntimeBindingImpact(
            String targetNodeId,
            VisualValidationResult validation,
            Map<String, String> operatorLibraryIdsByOperatorRef) {
        List<VisualGraphReadiness.RuntimeBindingRequirement> requirements =
                targetRuntimeBindingRequirements(targetNodeId, validation);
        return new VisualConnectionCandidatesResult.ConnectionCandidateRuntimeBindingImpact(
                requirements.size(),
                runtimeBindingRequirementKeys(requirements),
                countBy(requirements, VisualGraphReadiness.RuntimeBindingRequirement::bindingKind),
                countBy(requirements, VisualGraphReadiness.RuntimeBindingRequirement::handoffLane),
                countBy(requirements, VisualGraphReadiness.RuntimeBindingRequirement::handoffKind),
                countBy(requirements, VisualGraphReadiness.RuntimeBindingRequirement::handoffTarget),
                countBy(requirements, VisualGraphReadiness.RuntimeBindingRequirement::sourceKind),
                countBy(requirements, requirement -> operatorLibraryId(requirement, operatorLibraryIdsByOperatorRef)),
                countBy(requirements, VisualGraphReadiness.RuntimeBindingRequirement::loweringMode),
                countBy(requirements, VisualGraphReadiness.RuntimeBindingRequirement::state)
        );
    }

    private static List<VisualGraphReadiness.RuntimeBindingRequirement> targetRuntimeBindingRequirements(
            String targetNodeId,
            VisualValidationResult validation) {
        if (targetNodeId == null || targetNodeId.isBlank() || validation == null || validation.readiness() == null
                || validation.readiness().runtimeBindingRequirements() == null) {
            return List.of();
        }
        return validation.readiness().runtimeBindingRequirements().stream()
                .filter(requirement -> requirement != null && targetNodeId.equals(requirement.nodeId()))
                .toList();
    }

    private static List<String> runtimeBindingRequirementKeys(
            List<VisualGraphReadiness.RuntimeBindingRequirement> requirements) {
        return requirements == null ? List.of() : requirements.stream()
                .map(requirement -> VisualRuntimeBindingRequirementKey.stable(
                        "connection-preview",
                        "",
                        requirement.nodeId(),
                        requirement.bindingKind(),
                        requirement.bindingTarget(),
                        ""))
                .toList();
    }

    private static String operatorLibraryId(VisualGraphReadiness.RuntimeBindingRequirement requirement,
                                            Map<String, String> operatorLibraryIdsByOperatorRef) {
        if (requirement == null || operatorLibraryIdsByOperatorRef == null) {
            return "";
        }
        String value = operatorLibraryIdsByOperatorRef.get(requirement.operatorRef());
        return value == null ? "" : value;
    }

    private static Map<String, Integer> countBy(
            List<VisualGraphReadiness.RuntimeBindingRequirement> requirements,
            Function<VisualGraphReadiness.RuntimeBindingRequirement, String> classifier) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (VisualGraphReadiness.RuntimeBindingRequirement requirement
                : requirements == null ? List.<VisualGraphReadiness.RuntimeBindingRequirement>of() : requirements) {
            String key = classifier.apply(requirement);
            if (key == null || key.isBlank()) {
                continue;
            }
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private Map<String, Object> endpointSourceSchema(GraphDraft draft, GraphDraft.Endpoint source) {
        if (source == null || source.nodeId().isBlank()) {
            return null;
        }
        if (CONTEXT_SOURCE_NODE_ID.equals(source.nodeId())) {
            return VisualSchemaIntrospection.schemaAtPath(draft.inputSchema().schema(), source.path());
        }
        Optional<GraphDraft.DraftNode> node = draft.nodes().stream()
                .filter(candidate -> candidate.id().equals(source.nodeId()))
                .findFirst();
        if (node.isEmpty()) {
            return null;
        }
        Optional<OperatorDefinition> operator = catalog.find(node.get().operatorRef());
        if (operator.isEmpty()) {
            return null;
        }
        return resolveOutputPort(operator.get(), source.port(), source.path())
                .map(port -> VisualSchemaIntrospection.schemaAtPath(port.schema().schema(), source.path()))
                .orElse(null);
    }

    private Map<String, Object> endpointTargetSchema(GraphDraft draft,
                                                     GraphDraft.Endpoint target,
                                                     String surface) {
        if (target == null || target.nodeId().isBlank() || controlSurface(surface)) {
            return null;
        }
        Optional<GraphDraft.DraftNode> node = draft.nodes().stream()
                .filter(candidate -> candidate.id().equals(target.nodeId()))
                .findFirst();
        if (node.isEmpty()) {
            return null;
        }
        Optional<OperatorDefinition> operator = catalog.find(node.get().operatorRef());
        if (operator.isEmpty()) {
            return null;
        }
        if (CONFIG_TARGET_PORT.equals(target.port()) || "config".equals(surface)) {
            return VisualSchemaIntrospection.schemaAtPath(operator.get().configSchema().schema(), target.path());
        }
        return resolveInputPort(operator.get(), target.port(), target.path())
                .map(port -> VisualSchemaIntrospection.schemaAtPath(port.schema().schema(), target.path()))
                .orElse(null);
    }

    private static Optional<OperatorDefinition.Port> resolveOutputPort(OperatorDefinition operator,
                                                                       String portName,
                                                                       String path) {
        if (portName != null && !portName.isBlank()) {
            return operator.ports().outputs().stream()
                    .filter(port -> portName.equals(port.name()))
                    .findFirst();
        }
        List<OperatorDefinition.Port> ports = operator.ports().outputs();
        if (ports.isEmpty()) {
            return Optional.empty();
        }
        if (ports.size() == 1) {
            return Optional.of(ports.getFirst());
        }
        List<OperatorDefinition.Port> matches = ports.stream()
                .filter(port -> VisualSchemaIntrospection.schemaAtPath(port.schema().schema(), path) != null)
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static boolean controlSurface(String surface) {
        return "dependency".equals(surface) || "route".equals(surface) || "control".equals(surface);
    }

    private static VisualDiagnostic firstDiagnostic(List<VisualDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return null;
        }
        return diagnostics.getFirst();
    }

    private static String candidateDecisionMessage(VisualConnectionCheckResult check,
                                                   VisualDiagnostic firstDiagnostic) {
        if (firstDiagnostic != null && !firstDiagnostic.message().isBlank()) {
            return firstDiagnostic.message();
        }
        if (check.summary() != null && !check.summary().message().isBlank()) {
            return check.summary().message();
        }
        return check.accepted() ? "Connection accepted by server validator." : "Connection rejected by server validator.";
    }

    private static String replacementSummary(int replacedBindings, int replacedEdges) {
        if (replacedBindings == 0 && replacedEdges == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (replacedBindings > 0) {
            parts.add(replacedBindings + " binding" + (replacedBindings == 1 ? "" : "s"));
        }
        if (replacedEdges > 0) {
            parts.add(replacedEdges + " edge" + (replacedEdges == 1 ? "" : "s"));
        }
        return "Replaces " + String.join(" and ", parts) + ".";
    }

    private static String endpointLabel(GraphDraft.Endpoint endpoint, boolean source) {
        if (endpoint == null) {
            return "";
        }
        if (CONTEXT_SOURCE_NODE_ID.equals(endpoint.nodeId())) {
            return endpoint.path().isBlank() ? "ctx" : "ctx." + endpoint.path();
        }
        String prefix = endpoint.nodeId();
        if (prefix.isBlank()) {
            return "";
        }
        if (endpoint.port().isBlank() && endpoint.path().isBlank()) {
            return prefix;
        }
        String port = endpoint.port().isBlank() ? (source ? "output" : "input") : endpoint.port();
        return endpoint.path().isBlank()
                ? prefix + "." + port
                : prefix + "." + port + "." + endpoint.path();
    }

    private static boolean schemaKnown(Map<String, Object> schema) {
        return schema != null && !schema.isEmpty();
    }

    private static String schemaTypeLabel(Map<String, Object> schema, String fallback) {
        if (schema == null) {
            return fallback == null ? "" : fallback;
        }
        if (schema.isEmpty()) {
            return "any";
        }
        String union = unionTypeLabel(schema, "oneOf");
        if (!union.isBlank()) {
            return union;
        }
        union = unionTypeLabel(schema, "anyOf");
        if (!union.isBlank()) {
            return union;
        }
        Object enumValues = schema.get("enum");
        if (enumValues instanceof List<?> values && !values.isEmpty()) {
            return "enum";
        }
        String type = VisualSchemaIntrospection.schemaType(schema);
        if ("array".equals(type)) {
            Map<String, Object> items = VisualSchemaIntrospection.objectSchema(schema.get("items"));
            return items == null ? "array" : "array<" + schemaTypeLabel(items, "any") + ">";
        }
        if (!type.isBlank()) {
            return type;
        }
        return fallback == null || fallback.isBlank() ? "any" : fallback;
    }

    private static String unionTypeLabel(Map<String, Object> schema, String keyword) {
        Object raw = schema.get(keyword);
        if (!(raw instanceof List<?> branches) || branches.isEmpty()) {
            return "";
        }
        List<String> labels = branches.stream()
                .map(VisualSchemaIntrospection::objectSchema)
                .filter(candidate -> candidate != null)
                .map(candidate -> schemaTypeLabel(candidate, "any"))
                .distinct()
                .toList();
        return labels.isEmpty() ? keyword : keyword + "<" + String.join("|", labels) + ">";
    }

    private static Map<String, Object> selectedUnionBranchSchema(Map<String, Object> schema,
                                                                 GraphDraft.UnionBranchSelection selection) {
        if (schema == null || selection == null || !selection.selected()) {
            return schema;
        }
        Object raw = schema.get(selection.keyword());
        if (!(raw instanceof List<?> branches) || selection.index() >= branches.size()) {
            return schema;
        }
        Map<String, Object> branch = VisualSchemaIntrospection.objectSchema(branches.get(selection.index()));
        return branch == null ? schema : branch;
    }

    private record ConnectionCandidateTarget(
            GraphDraft.DraftNode node,
            String surface,
            GraphDraft.Endpoint endpoint
    ) {
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
        if (VisualSchemaIntrospection.arrayIndexSegment(nextSegment) != null) {
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
            Integer index = VisualSchemaIntrospection.arrayIndexSegment(segment);
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
            Integer index = VisualSchemaIntrospection.arrayIndexSegment(segment);
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

    private static List<String> replacedInputKeys(GraphDraft draft,
                                                  int targetIndex,
                                                  String inputKey,
                                                  GraphDraft.Binding binding,
                                                  Optional<OperatorDefinition> targetOperator) {
        if (targetIndex < 0 || targetIndex >= draft.nodes().size()) {
            return List.of();
        }
        return draft.nodes().get(targetIndex).inputs().entrySet().stream()
                .filter(entry -> entry.getKey().equals(inputKey)
                        || sameBindingTarget(entry.getKey(), entry.getValue(), inputKey, binding, targetOperator))
                .map(Map.Entry::getKey)
                .distinct()
                .toList();
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
                .filter(port -> VisualSchemaIntrospection.schemaAtPath(port.schema().schema(), inputName) != null)
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

    private static List<String> replacedEdgeIds(GraphDraft draft, GraphDraft.DraftEdge edge) {
        return draft.edges().stream()
                .filter(existing -> sameTargetEndpoint(existing.target(), edge.target()))
                .map(GraphDraft.DraftEdge::id)
                .distinct()
                .toList();
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
                .filter(port -> VisualSchemaIntrospection.schemaAtPath(port.schema().schema(), path) != null)
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
            if (VisualSchemaIntrospection.arrayIndexSegment(segment) != null) {
                suffix.append('[').append(segment).append(']');
            } else {
                suffix.append('.').append(segment);
            }
        }
        return suffix.toString();
    }
}
