package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.List;
import java.util.Map;

/**
 * Schema-fit catalog response for adding the next operator from a source endpoint.
 *
 * @param schemaVersion response schema version
 * @param source normalized source endpoint
 * @param sourceSchemaType display-safe source schema type
 * @param sourceSchemaKnown true when a concrete source schema was resolved
 * @param operators operator window aligned with fitCandidates
 * @param fitCandidates fit metadata aligned with operators
 * @param diagnostics request-level diagnostics
 * @param facets count summary for visible fit operators before paging
 * @param runtimeBindingProjections server-derived runtime binding projections for operators
 * @param runtimeBindingProjectionStateCounts projection state counts
 * @param executablePromotionProjections server-derived executable promotion projections for operators
 * @param executablePromotionStateCounts executable promotion state counts
 * @param totalCandidateCount catalog operators evaluated before fit filtering
 * @param acceptedCount operators with at least one compatible target before paging
 * @param rejectedCount operators with no compatible target before paging
 * @param total visible operators after accepted/rejected filtering and before paging
 * @param unfilteredTotal total operators visible in the authoring scope before catalog filters
 * @param displayedCount returned operator row count
 * @param itemLimit requested response window size
 * @param offset zero-based response window offset
 * @param hasMore whether another fit window exists after this response
 * @param filter normalized catalog filter echoed for clients
 */
public record OperatorFitCatalogResponse(
        String schemaVersion,
        GraphDraft.Endpoint source,
        String sourceSchemaType,
        boolean sourceSchemaKnown,
        List<OperatorDefinition> operators,
        List<OperatorFitCandidate> fitCandidates,
        List<VisualDiagnostic> diagnostics,
        OperatorCatalogFacets facets,
        List<OperatorRuntimeBindingProjection> runtimeBindingProjections,
        Map<String, Integer> runtimeBindingProjectionStateCounts,
        List<OperatorExecutablePromotionProjection> executablePromotionProjections,
        Map<String, Integer> executablePromotionStateCounts,
        int totalCandidateCount,
        int acceptedCount,
        int rejectedCount,
        int total,
        int unfilteredTotal,
        int displayedCount,
        int itemLimit,
        int offset,
        boolean hasMore,
        OperatorCatalogQuery filter
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorFitCatalog.v1";

    /**
     * Creates a schema-fit catalog response.
     */
    public OperatorFitCatalogResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        source = source == null ? GraphDraft.Endpoint.empty() : source;
        sourceSchemaType = sourceSchemaType == null ? "" : sourceSchemaType;
        operators = operators == null ? List.of() : List.copyOf(operators);
        fitCandidates = fitCandidates == null ? List.of() : List.copyOf(fitCandidates);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        facets = facets == null ? OperatorCatalogFacets.from(operators) : facets;
        runtimeBindingProjections = runtimeBindingProjections == null
                ? List.of()
                : List.copyOf(runtimeBindingProjections);
        runtimeBindingProjectionStateCounts = runtimeBindingProjectionStateCounts == null
                ? OperatorRuntimeBindingProjection.stateCounts(runtimeBindingProjections)
                : Map.copyOf(runtimeBindingProjectionStateCounts);
        executablePromotionProjections = executablePromotionProjections == null
                ? OperatorExecutablePromotionProjection.from(runtimeBindingProjections)
                : List.copyOf(executablePromotionProjections);
        executablePromotionStateCounts = executablePromotionStateCounts == null
                ? OperatorExecutablePromotionProjection.stateCounts(executablePromotionProjections)
                : Map.copyOf(executablePromotionStateCounts);
        totalCandidateCount = Math.max(0, totalCandidateCount);
        acceptedCount = Math.max(0, acceptedCount);
        rejectedCount = Math.max(0, rejectedCount);
        total = Math.max(total, operators.size());
        unfilteredTotal = Math.max(unfilteredTotal, total);
        displayedCount = operators.size();
        itemLimit = Math.max(0, itemLimit);
        offset = Math.max(0, offset);
        filter = filter == null ? OperatorCatalogQuery.all() : filter;
    }

    /**
     * Fit metadata for one addable operator.
     *
     * @param operator operator definition
     * @param accepted true when at least one target accepts the source schema
     * @param acceptedTargetCount compatible target count
     * @param rejectedTargetCount incompatible target count
     * @param targets target rows inspected for this operator
     * @param message human-readable fit summary
     */
    public record OperatorFitCandidate(
            OperatorDefinition operator,
            boolean accepted,
            int acceptedTargetCount,
            int rejectedTargetCount,
            List<OperatorFitTarget> targets,
            String message
    ) {
        public OperatorFitCandidate {
            acceptedTargetCount = Math.max(0, acceptedTargetCount);
            rejectedTargetCount = Math.max(0, rejectedTargetCount);
            targets = targets == null ? List.of() : List.copyOf(targets);
            message = message == null ? "" : message;
        }
    }

    /**
     * Fit decision for one operator input/config target path.
     *
     * @param targetSurface target surface, such as input or config
     * @param targetPort target port name
     * @param targetPath target schema path
     * @param accepted true when the source schema can feed this target
     * @param sourceSchemaType summarized source schema type
     * @param targetSchemaType summarized target schema type
     * @param sourceSchemaKnown true when a concrete source schema was resolved
     * @param targetSchemaKnown true when a concrete target schema was resolved
     * @param message accepted or rejected reason
     */
    public record OperatorFitTarget(
            String targetSurface,
            String targetPort,
            String targetPath,
            boolean accepted,
            String sourceSchemaType,
            String targetSchemaType,
            boolean sourceSchemaKnown,
            boolean targetSchemaKnown,
            String message
    ) {
        public OperatorFitTarget {
            targetSurface = targetSurface == null || targetSurface.isBlank() ? "input" : targetSurface;
            targetPort = targetPort == null ? "" : targetPort;
            targetPath = targetPath == null ? "" : targetPath;
            sourceSchemaType = sourceSchemaType == null ? "" : sourceSchemaType;
            targetSchemaType = targetSchemaType == null ? "" : targetSchemaType;
            message = message == null ? "" : message;
        }
    }
}
