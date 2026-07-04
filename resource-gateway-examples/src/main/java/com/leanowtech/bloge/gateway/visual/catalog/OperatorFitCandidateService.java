package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaIntrospection;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Schema-aware catalog fit discovery for adding the next operator from a canvas source.
 */
@Service
public class OperatorFitCandidateService {

    private static final String CONTEXT_SOURCE_NODE_ID = "__ctx";
    private static final String CONFIG_TARGET_PORT = "config";
    private static final int MAX_SCHEMA_CANDIDATE_PATHS = 64;
    private static final int MAX_SCHEMA_CANDIDATE_DEPTH = 4;

    private final VisualOperatorCatalog catalog;

    /**
     * @param catalog visual operator catalog
     */
    public OperatorFitCandidateService(VisualOperatorCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Discovers catalog operators whose declared target schemas can accept a source endpoint schema.
     *
     * @param request fit discovery request
     * @return catalog-compatible operator fit window
     */
    public OperatorFitCatalogResponse candidates(OperatorFitCandidatesRequest request) {
        OperatorFitCandidatesRequest safeRequest = request == null
                ? new OperatorFitCandidatesRequest(null, GraphDraft.Endpoint.empty(), OperatorCatalogQuery.all(),
                        "input", false, 0, 0)
                : request;
        OperatorCatalogQuery query = effectiveQuery(safeRequest);
        OperatorCatalogQuery unfilteredQuery = unfilteredQuery(query);
        SourceResolution source = resolveSource(safeRequest.draft(), safeRequest.source());
        if (!source.diagnostics().isEmpty()) {
            return emptyResponse(safeRequest, query, source, source.diagnostics());
        }

        List<OperatorDefinition> matchingOperators = catalog.list(query);
        List<OperatorFitCatalogResponse.OperatorFitCandidate> evaluated = matchingOperators.stream()
                .map(operator -> evaluateOperator(operator, source, safeRequest.targetSurface()))
                .toList();
        int acceptedCount = (int) evaluated.stream()
                .filter(OperatorFitCatalogResponse.OperatorFitCandidate::accepted)
                .count();
        int rejectedCount = evaluated.size() - acceptedCount;
        List<OperatorFitCatalogResponse.OperatorFitCandidate> visible = evaluated.stream()
                .filter(candidate -> safeRequest.includeRejected() || candidate.accepted())
                .toList();
        boolean hasMore = visible.size() > safeRequest.offset() + safeRequest.limit();
        List<OperatorFitCatalogResponse.OperatorFitCandidate> window = visible.stream()
                .skip(safeRequest.offset())
                .limit(safeRequest.limit())
                .toList();
        List<OperatorDefinition> operators = window.stream()
                .map(OperatorFitCatalogResponse.OperatorFitCandidate::operator)
                .toList();
        List<OperatorRuntimeBindingProjection> runtimeBindingProjections =
                catalog.runtimeBindingProjections(query, operators);
        List<OperatorExecutablePromotionProjection> executablePromotionProjections =
                catalog.executablePromotionProjections(query, runtimeBindingProjections);
        List<OperatorDefinition> visibleOperators = visible.stream()
                .map(OperatorFitCatalogResponse.OperatorFitCandidate::operator)
                .toList();
        return new OperatorFitCatalogResponse(
                OperatorFitCatalogResponse.SCHEMA_VERSION,
                safeRequest.source(),
                source.typeLabel(),
                source.known(),
                operators,
                window,
                catalog.diagnostics(query),
                OperatorCatalogFacets.from(visibleOperators),
                runtimeBindingProjections,
                OperatorRuntimeBindingProjection.stateCounts(runtimeBindingProjections),
                executablePromotionProjections,
                OperatorExecutablePromotionProjection.stateCounts(executablePromotionProjections),
                evaluated.size(),
                acceptedCount,
                rejectedCount,
                visible.size(),
                catalog.list(unfilteredQuery).size(),
                operators.size(),
                safeRequest.limit(),
                safeRequest.offset(),
                hasMore,
                query
        );
    }

    private OperatorFitCatalogResponse.OperatorFitCandidate evaluateOperator(OperatorDefinition operator,
                                                                             SourceResolution source,
                                                                             String targetSurface) {
        List<OperatorFitCatalogResponse.OperatorFitTarget> targets = new ArrayList<>();
        if (surfaceMatches(targetSurface, "input")) {
            for (OperatorDefinition.Port port : operator.ports().inputs()) {
                for (String path : VisualSchemaIntrospection.connectableSchemaPaths(port.schema(),
                        MAX_SCHEMA_CANDIDATE_PATHS, MAX_SCHEMA_CANDIDATE_DEPTH)) {
                    Map<String, Object> targetSchema =
                            VisualSchemaIntrospection.schemaAtPath(port.schema().schema(), path);
                    if (targetSchema == null) {
                        continue;
                    }
                    targets.add(evaluateTarget(source, "input", port.name(), path, targetSchema));
                }
            }
        }
        if (surfaceMatches(targetSurface, "config")) {
            for (String path : VisualSchemaIntrospection.connectableSchemaPaths(operator.configSchema(),
                    MAX_SCHEMA_CANDIDATE_PATHS, MAX_SCHEMA_CANDIDATE_DEPTH)) {
                if (path.isBlank()) {
                    continue;
                }
                Map<String, Object> targetSchema =
                        VisualSchemaIntrospection.schemaAtPath(operator.configSchema().schema(), path);
                if (targetSchema == null) {
                    continue;
                }
                targets.add(evaluateTarget(source, "config", CONFIG_TARGET_PORT, path, targetSchema));
            }
        }

        int acceptedTargets = (int) targets.stream()
                .filter(OperatorFitCatalogResponse.OperatorFitTarget::accepted)
                .count();
        int rejectedTargets = targets.size() - acceptedTargets;
        return new OperatorFitCatalogResponse.OperatorFitCandidate(
                operator,
                acceptedTargets > 0,
                acceptedTargets,
                rejectedTargets,
                targets,
                fitMessage(targets, acceptedTargets)
        );
    }

    private static boolean surfaceMatches(String requested, String actual) {
        return requested == null || requested.isBlank() || requested.equals(actual);
    }

    private OperatorFitCatalogResponse.OperatorFitTarget evaluateTarget(SourceResolution source,
                                                                        String surface,
                                                                        String port,
                                                                        String path,
                                                                        Map<String, Object> targetSchema) {
        Optional<String> issue = VisualSchemaCompatibility.schemaCompatibilityIssue(source.schema(), targetSchema);
        boolean accepted = issue.isEmpty();
        String targetLabel = port + (path == null || path.isBlank() ? "" : "." + path);
        String targetType = schemaTypeLabel(targetSchema);
        String message = accepted
                ? "Source %s can feed %s %s.".formatted(source.typeLabel(), surface, targetLabel)
                : issue.orElse("Source schema is not compatible with target schema.");
        return new OperatorFitCatalogResponse.OperatorFitTarget(
                surface,
                port,
                path,
                accepted,
                source.typeLabel(),
                targetType,
                source.known(),
                schemaKnown(targetSchema),
                message
        );
    }

    private static String fitMessage(List<OperatorFitCatalogResponse.OperatorFitTarget> targets,
                                     int acceptedTargets) {
        if (targets.isEmpty()) {
            return "Operator exposes no target schemas for this fit surface.";
        }
        if (acceptedTargets > 0) {
            Optional<OperatorFitCatalogResponse.OperatorFitTarget> first = targets.stream()
                    .filter(OperatorFitCatalogResponse.OperatorFitTarget::accepted)
                    .findFirst();
            String suffix = first.map(target -> " Best target: %s.%s."
                            .formatted(target.targetPort(), target.targetPath().isBlank()
                                    ? "<root>"
                                    : target.targetPath()))
                    .orElse("");
            return "%d compatible target%s.%s".formatted(
                    acceptedTargets,
                    acceptedTargets == 1 ? "" : "s",
                    suffix
            );
        }
        return targets.getFirst().message();
    }

    private SourceResolution resolveSource(GraphDraft draft, GraphDraft.Endpoint source) {
        if (draft == null) {
            return SourceResolution.invalid(source, List.of(VisualDiagnostic.error(
                    "visual.draft.missing",
                    "Graph draft is required.",
                    "/draft"
            )));
        }
        if (source == null || source.nodeId().isBlank()) {
            return SourceResolution.invalid(source, List.of(VisualDiagnostic.error(
                    "visual.operatorFit.sourceMissing",
                    "Operator fit source endpoint is required.",
                    "/source"
            )));
        }
        if (CONTEXT_SOURCE_NODE_ID.equals(source.nodeId())) {
            Map<String, Object> schema =
                    VisualSchemaIntrospection.schemaAtPath(draft.inputSchema().schema(), source.path());
            return sourceSchema(source, schema, "/source/path");
        }
        Optional<GraphDraft.DraftNode> node = draft.nodes().stream()
                .filter(candidate -> candidate.id().equals(source.nodeId()))
                .findFirst();
        if (node.isEmpty()) {
            return SourceResolution.invalid(source, List.of(VisualDiagnostic.error(
                    "visual.edge.unknownSource",
                    "Operator fit source node does not exist: " + source.nodeId(),
                    "/source/nodeId"
            )));
        }
        Optional<OperatorDefinition> operator = catalog.find(node.get().operatorRef());
        if (operator.isEmpty()) {
            return SourceResolution.invalid(source, List.of(VisualDiagnostic.error(
                    "visual.operatorFit.sourceOperatorMissing",
                    "Operator fit source operator is not available in the catalog: " + node.get().operatorRef(),
                    "/source/nodeId"
            )));
        }
        Optional<OperatorDefinition.Port> port = resolveOutputPort(operator.get(), source.port(), source.path());
        if (port.isEmpty()) {
            return SourceResolution.invalid(source, List.of(VisualDiagnostic.error(
                    "visual.operatorFit.sourcePortMissing",
                    "Operator fit source output port is not available: " + source.port(),
                    "/source/port"
            )));
        }
        Map<String, Object> schema = VisualSchemaIntrospection.schemaAtPath(port.get().schema().schema(),
                source.path());
        return sourceSchema(source, schema, "/source/path");
    }

    private static SourceResolution sourceSchema(GraphDraft.Endpoint source,
                                                 Map<String, Object> schema,
                                                 String target) {
        if (schema == null) {
            return SourceResolution.invalid(source, List.of(VisualDiagnostic.error(
                    "visual.operatorFit.sourceSchemaMissing",
                    "Operator fit source schema path is not available: " + source.path(),
                    target
            )));
        }
        return new SourceResolution(source, schema, schemaTypeLabel(schema), schemaKnown(schema), List.of());
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

    private OperatorFitCatalogResponse emptyResponse(OperatorFitCandidatesRequest request,
                                                     OperatorCatalogQuery query,
                                                     SourceResolution source,
                                                     List<VisualDiagnostic> diagnostics) {
        return new OperatorFitCatalogResponse(
                OperatorFitCatalogResponse.SCHEMA_VERSION,
                request == null ? GraphDraft.Endpoint.empty() : request.source(),
                source.typeLabel(),
                source.known(),
                List.of(),
                List.of(),
                diagnostics,
                OperatorCatalogFacets.from(List.of()),
                List.of(),
                Map.of(),
                List.of(),
                Map.of(),
                0,
                0,
                0,
                0,
                catalog.list(unfilteredQuery(query)).size(),
                0,
                request == null ? 0 : request.limit(),
                request == null ? 0 : request.offset(),
                false,
                query
        );
    }

    private static OperatorCatalogQuery effectiveQuery(OperatorFitCandidatesRequest request) {
        OperatorCatalogQuery filter = request.filter();
        GraphDraft draft = request.draft();
        return new OperatorCatalogQuery(
                filter.search(),
                filter.tags(),
                filter.resourceOnly(),
                filter.includeDeprecated(),
                firstNonBlank(filter.tenantId(), draft == null ? "" : draft.tenantId()),
                firstNonBlank(filter.namespace(), draft == null ? "" : draft.namespace()),
                firstNonBlank(filter.environment(), draft == null ? "" : draft.environment()),
                filter.sourceKinds(),
                filter.operatorLibraryIds(),
                filter.loweringModes(),
                filter.capabilities(),
                filter.runtimeReadinessStates()
        );
    }

    private static OperatorCatalogQuery unfilteredQuery(OperatorCatalogQuery query) {
        return new OperatorCatalogQuery("", List.of(), query.resourceOnly(), query.includeDeprecated(),
                query.tenantId(), query.namespace(), query.environment());
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private static String schemaTypeLabel(Map<String, Object> schema) {
        if (schema == null) {
            return "";
        }
        if (schema.isEmpty()) {
            return "any";
        }
        return VisualSchemaCompatibility.schemaTypeLabel(schema);
    }

    private static boolean schemaKnown(Map<String, Object> schema) {
        return schema != null && !schema.isEmpty();
    }

    private record SourceResolution(
            GraphDraft.Endpoint source,
            Map<String, Object> schema,
            String typeLabel,
            boolean known,
            List<VisualDiagnostic> diagnostics
    ) {
        private SourceResolution {
            source = source == null ? GraphDraft.Endpoint.empty() : source;
            schema = schema == null ? Map.of() : schema;
            typeLabel = typeLabel == null ? "" : typeLabel;
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        private static SourceResolution invalid(GraphDraft.Endpoint source, List<VisualDiagnostic> diagnostics) {
            return new SourceResolution(source, Map.of(), "", false, diagnostics);
        }
    }
}
