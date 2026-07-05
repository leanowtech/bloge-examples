package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.graphengine.model.GraphOperationsSnapshot;
import com.leanowtech.bloge.graphengine.service.GraphEngineService;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for graph-engine operations health projections.
 */
@RestController
@Validated
public class GraphOperationsController {

    private final GraphEngineService graphEngineService;
    private final GraphEngineRequestScopeResolver scopeResolver;

    /**
     * Creates one controller instance.
     *
     * @param graphEngineService product control-plane service
     * @param scopeResolver request-scope resolver
     */
    public GraphOperationsController(GraphEngineService graphEngineService,
                                     GraphEngineRequestScopeResolver scopeResolver) {
        this.graphEngineService = graphEngineService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * Returns a tenant-scoped operations snapshot for dashboards and operators.
     *
     * @return operations snapshot for the current request scope
     */
    @GetMapping("/api/v1/operations/snapshot")
    public GraphOperationsSnapshot getOperationsSnapshot() {
        TenantContext scope = scopeResolver.currentScope();
        return graphEngineService.queryOperationsSnapshot(scope.tenantId(), scope.namespace());
    }
}
