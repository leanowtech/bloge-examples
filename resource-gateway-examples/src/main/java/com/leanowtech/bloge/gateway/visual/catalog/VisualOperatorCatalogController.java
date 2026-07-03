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
                                        List<String> runtimeReadinessStates) {
        OperatorCatalogQuery query = new OperatorCatalogQuery(search, tags, resourceOnly, includeDeprecated,
                tenantId, namespace, environment, sourceKinds, operatorLibraryIds, loweringModes, capabilities,
                runtimeReadinessStates);
        return new OperatorCatalogResponse(
                "bloge.visualOperatorCatalog.v1",
                catalog.list(query),
                catalog.diagnostics(query)
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
}
