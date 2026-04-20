package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.graphengine.model.GraphDeployment;
import com.leanowtech.bloge.graphengine.server.rest.dto.CreateDeploymentRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.UpdateDeploymentRequest;
import com.leanowtech.bloge.graphengine.service.GraphEngineService;
import com.leanowtech.bloge.graphengine.service.command.CreateDeploymentCommand;
import com.leanowtech.bloge.graphengine.service.command.UpdateDeploymentCommand;
import com.leanowtech.bloge.graphengine.store.GraphDeploymentQuery;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * REST controller for deployment routing and execution-plane configuration.
 */
@RestController
@Validated
@RequestMapping("/api/v1/deployments")
public class GraphDeploymentController {

    private final GraphEngineService graphEngineService;
    private final GraphEngineRequestScopeResolver scopeResolver;

    /**
     * Creates one controller instance.
     *
     * @param graphEngineService product control-plane service
     * @param scopeResolver request-scope resolver
     */
    public GraphDeploymentController(GraphEngineService graphEngineService,
                                     GraphEngineRequestScopeResolver scopeResolver) {
        this.graphEngineService = graphEngineService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * Creates a deployment binding for one graph definition.
     *
     * @param request create-deployment request
     * @return created deployment snapshot
     */
    @PostMapping
    public ResponseEntity<GraphDeployment> createDeployment(@Valid @RequestBody CreateDeploymentRequest request) {
        TenantContext scope = scopeResolver.currentScope();
        GraphDeployment deployment = graphEngineService.createDeployment(new CreateDeploymentCommand(
                request.definitionKey(),
                scope.tenantId(),
                scope.namespace(),
                request.environment(),
                request.routingPolicy(),
                request.operatorPlaneConfig(),
                request.active()
        ));
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{deploymentId}")
                .buildAndExpand(deployment.deploymentId())
                .toUri();
        return ResponseEntity.created(location).body(deployment);
    }

    /**
     * Lists deployment bindings visible to the current tenant scope.
     *
     * @param definitionKey optional definition-key filter
     * @param environment optional environment filter
     * @param active optional active-state filter
     * @param page zero-based page index
     * @param size requested page size
     * @return matching deployments
     */
    @GetMapping
    public List<GraphDeployment> queryDeployments(@RequestParam(required = false) String definitionKey,
                                                  @RequestParam(required = false) String environment,
                                                  @RequestParam(required = false) Boolean active,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        TenantContext scope = scopeResolver.currentScope();
        return graphEngineService.queryDeployments(new GraphDeploymentQuery(
                scope.tenantId(),
                scope.namespace(),
                definitionKey,
                environment,
                active,
                page,
                size
        ));
    }

    /**
     * Loads one deployment by identifier.
     *
     * @param deploymentId deployment identifier
     * @return matching deployment snapshot
     */
    @GetMapping("/{deploymentId}")
    public GraphDeployment getDeployment(@PathVariable String deploymentId) {
        return graphEngineService.getDeployment(deploymentId);
    }

    /**
     * Updates mutable routing configuration for one deployment.
     *
     * @param deploymentId deployment identifier
     * @param request update request
     * @return updated deployment snapshot
     */
    @PutMapping("/{deploymentId}")
    public GraphDeployment updateDeployment(@PathVariable String deploymentId,
                                            @Valid @RequestBody UpdateDeploymentRequest request) {
        return graphEngineService.updateDeployment(new UpdateDeploymentCommand(
                deploymentId,
                request.expectedRevision(),
                request.routingPolicy(),
                request.operatorPlaneConfig(),
                request.active()
        ));
    }
}
