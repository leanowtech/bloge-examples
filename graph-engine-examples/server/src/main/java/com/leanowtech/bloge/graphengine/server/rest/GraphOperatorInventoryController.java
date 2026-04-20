package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.graphengine.service.GraphEngineService;
import com.leanowtech.bloge.graphengine.service.OperatorInventoryEntry;
import com.leanowtech.bloge.graphengine.service.OperatorInventoryQuery;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for the product-layer operator inventory API.
 *
 * <p>Exposes a read-only view of registered operators with registry metadata,
 * human-facing annotation details, schema information, and cross-definition
 * usage statistics derived from visible graph versions in the current
 * tenant scope.</p>
 */
@RestController
@Validated
@RequestMapping("/api/v1/operators")
public class GraphOperatorInventoryController {

    private final GraphEngineService graphEngineService;
    private final GraphEngineRequestScopeResolver scopeResolver;

    /**
     * Creates one controller instance.
     *
     * @param graphEngineService product control-plane service
     * @param scopeResolver      request-scope resolver
     */
    public GraphOperatorInventoryController(GraphEngineService graphEngineService,
                                            GraphEngineRequestScopeResolver scopeResolver) {
        this.graphEngineService = graphEngineService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * Queries the operator inventory visible in the current request scope.
     *
     * <p>Each entry includes the operator's registration name, annotation-derived
     * metadata (description, owner, tags), input/output schema information, and
     * a usage summary showing which graph definitions and versions reference
     * the operator.</p>
     *
     * @param pattern glob-style operator-name filter (defaults to {@code *})
     * @return immutable list of operator inventory entries
     */
    @GetMapping
    public List<OperatorInventoryEntry> queryOperatorInventory(
            @RequestParam(defaultValue = "*") String pattern) {
        TenantContext scope = scopeResolver.currentScope();
        return graphEngineService.queryOperatorInventory(new OperatorInventoryQuery(
                pattern,
                scope.tenantId(),
                scope.namespace()
        ));
    }
}
