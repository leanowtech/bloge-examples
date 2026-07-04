package com.leanowtech.bloge.gateway.visual.catalog;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public visual operator catalog API.
 */
@RestController
@RequestMapping("/api/visual/operators")
public class VisualOperatorCatalogController {

    private static final int MAX_OPERATOR_WINDOW_SIZE = 500;

    private final VisualOperatorCatalog catalog;

    /**
     * @param catalog visual operator catalog
     */
    public VisualOperatorCatalogController(VisualOperatorCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Lists operators available to the visual canvas.
     *
     * @param search free-text search
     * @param tags required tags
     * @param resourceOnly whether to return only resource virtual operators
     * @param includeDeprecated include deprecated resource contracts
     * @param tenantId tenant scope
     * @param namespace namespace scope
     * @param environment authoring environment scope
     * @param sourceKinds source kind filters
     * @param operatorLibraryIds imported operator library owner filters
     * @param loweringModes lowering mode filters
     * @param capabilities capability facet filters
     * @param runtimeReadinessStates runtime readiness state filters
     * @param itemLimit response window size
     * @param legacyLimit response window size alias
     * @param offset response window offset
     * @return catalog response
     */
    @GetMapping
    public OperatorCatalogResponse list(@RequestParam(defaultValue = "") String search,
                                        @RequestParam(defaultValue = "") List<String> tags,
                                        @RequestParam(defaultValue = "false") boolean resourceOnly,
                                        @RequestParam(defaultValue = "false") boolean includeDeprecated,
                                        @RequestParam(defaultValue = "") String tenantId,
                                        @RequestParam(defaultValue = "") String namespace,
                                        @RequestParam(defaultValue = "") String environment,
                                        @RequestParam(name = "sourceKind", defaultValue = "")
                                        List<String> sourceKinds,
                                        @RequestParam(name = "operatorLibraryId", defaultValue = "")
                                        List<String> operatorLibraryIds,
                                        @RequestParam(name = "loweringMode", defaultValue = "")
                                        List<String> loweringModes,
                                        @RequestParam(name = "capability", defaultValue = "")
                                        List<String> capabilities,
                                        @RequestParam(name = "runtimeReadiness", defaultValue = "")
                                        List<String> runtimeReadinessStates,
                                        @RequestParam(name = "itemLimit", required = false)
                                        Integer itemLimit,
                                        @RequestParam(name = "limit", required = false)
                                        Integer legacyLimit,
                                        @RequestParam(defaultValue = "0") int offset) {
        OperatorCatalogQuery query = new OperatorCatalogQuery(search, tags, resourceOnly, includeDeprecated,
                tenantId, namespace, environment, sourceKinds, operatorLibraryIds, loweringModes, capabilities,
                runtimeReadinessStates);
        OperatorCatalogQuery unfilteredQuery = new OperatorCatalogQuery("", List.of(), resourceOnly,
                includeDeprecated, tenantId, namespace, environment, List.of(), List.of(), List.of(), List.of(),
                List.of());
        int unfilteredTotal = catalog.list(unfilteredQuery).size();
        List<OperatorDefinition> matchingOperators = catalog.list(query);
        int normalizedOffset = Math.max(0, offset);
        int normalizedItemLimit = normalizeItemLimit(itemLimit == null ? legacyLimit : itemLimit,
                matchingOperators.size());
        List<OperatorDefinition> operators = page(matchingOperators, normalizedOffset, normalizedItemLimit);
        List<OperatorRuntimeBindingProjection> runtimeBindingProjections =
                catalog.runtimeBindingProjections(query, operators);
        return new OperatorCatalogResponse(
                "bloge.visualOperatorCatalog.v1",
                operators,
                catalog.diagnostics(query),
                OperatorCatalogFacets.from(matchingOperators),
                runtimeBindingProjections,
                OperatorRuntimeBindingProjection.stateCounts(runtimeBindingProjections),
                catalog.executablePromotionProjections(query, runtimeBindingProjections),
                null,
                matchingOperators.size(),
                unfilteredTotal,
                operators.size(),
                normalizedItemLimit,
                normalizedOffset,
                normalizedOffset + operators.size() < matchingOperators.size(),
                query
        );
    }

    /**
     * Returns one operator definition visible in the requested authoring scope.
     *
     * @param operatorRef operator reference
     * @param resourceOnly whether to return only resource virtual operators
     * @param includeDeprecated include deprecated resource contracts
     * @param tenantId tenant scope
     * @param namespace namespace scope
     * @param environment authoring environment scope
     * @param sourceKinds source kind filters
     * @param operatorLibraryIds imported operator library owner filters
     * @param loweringModes lowering mode filters
     * @param capabilities capability facet filters
     * @param runtimeReadinessStates runtime readiness state filters
     * @return visible operator definition or 404
     */
    @GetMapping("/{operatorRef:.+}")
    public ResponseEntity<OperatorDefinition> get(@PathVariable String operatorRef,
                                                  @RequestParam(defaultValue = "false") boolean resourceOnly,
                                                  @RequestParam(defaultValue = "false") boolean includeDeprecated,
                                                  @RequestParam(defaultValue = "") String tenantId,
                                                  @RequestParam(defaultValue = "") String namespace,
                                                  @RequestParam(defaultValue = "") String environment,
                                                  @RequestParam(name = "sourceKind", defaultValue = "")
                                                  List<String> sourceKinds,
                                                  @RequestParam(name = "operatorLibraryId", defaultValue = "")
                                                  List<String> operatorLibraryIds,
                                                  @RequestParam(name = "loweringMode", defaultValue = "")
                                                  List<String> loweringModes,
                                                  @RequestParam(name = "capability", defaultValue = "")
                                                  List<String> capabilities,
                                                  @RequestParam(name = "runtimeReadiness", defaultValue = "")
                                                  List<String> runtimeReadinessStates) {
        OperatorCatalogQuery query = new OperatorCatalogQuery("", List.of(), resourceOnly, includeDeprecated,
                tenantId, namespace, environment, sourceKinds, operatorLibraryIds, loweringModes, capabilities,
                runtimeReadinessStates);
        return catalog.list(query).stream()
                .filter(operator -> operator.operatorRef().equals(operatorRef))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static int normalizeItemLimit(Integer requestedLimit, int total) {
        if (requestedLimit == null) {
            return Math.max(0, total);
        }
        return Math.max(0, Math.min(requestedLimit, MAX_OPERATOR_WINDOW_SIZE));
    }

    private static List<OperatorDefinition> page(List<OperatorDefinition> operators, int offset, int itemLimit) {
        if (operators == null || operators.isEmpty() || itemLimit == 0 || offset >= operators.size()) {
            return List.of();
        }
        int endExclusive = Math.min(operators.size(), offset + itemLimit);
        return List.copyOf(operators.subList(offset, endExclusive));
    }
}
