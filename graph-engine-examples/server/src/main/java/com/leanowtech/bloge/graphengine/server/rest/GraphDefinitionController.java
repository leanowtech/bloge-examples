package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.graphengine.model.GraphCategory;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDefinitionStatus;
import com.leanowtech.bloge.graphengine.server.rest.dto.CreateDefinitionRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.UpdateDefinitionRequest;
import com.leanowtech.bloge.graphengine.service.GraphEngineService;
import com.leanowtech.bloge.graphengine.service.command.CreateDefinitionCommand;
import com.leanowtech.bloge.graphengine.service.command.UpdateDefinitionCommand;
import com.leanowtech.bloge.graphengine.store.GraphDefinitionQuery;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * REST controller for graph definition authoring and governance metadata.
 */
@RestController
@Validated
@RequestMapping("/api/v1/graphs")
public class GraphDefinitionController {

    private final GraphEngineService graphEngineService;
    private final GraphEngineRequestScopeResolver scopeResolver;

    /**
     * Creates one controller instance.
     *
     * @param graphEngineService product control-plane service
     * @param scopeResolver request-scope resolver
     */
    public GraphDefinitionController(GraphEngineService graphEngineService,
                                     GraphEngineRequestScopeResolver scopeResolver) {
        this.graphEngineService = graphEngineService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * Creates a new graph definition inside the current tenant scope.
     *
     * @param request create-definition request
     * @return persisted definition snapshot
     */
    @PostMapping
    public ResponseEntity<GraphDefinition> createDefinition(@Valid @RequestBody CreateDefinitionRequest request) {
        TenantContext scope = scopeResolver.currentScope();
        GraphDefinition definition = graphEngineService.createDefinition(new CreateDefinitionCommand(
                request.definitionKey(),
                scope.tenantId(),
                scope.namespace(),
                request.displayName(),
                request.description(),
                request.category(),
                request.labels(),
                request.ownerTeam(),
                request.rbacPolicy()
        ));
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{definitionKey}")
                .buildAndExpand(definition.definitionKey())
                .toUri();
        return ResponseEntity.created(location).body(definition);
    }

    /**
     * Lists graph definitions visible to the current tenant scope.
     *
     * @param status optional lifecycle-state filter
     * @param definitionKey optional exact business-key filter
     * @param ownerTeam optional owner-team filter
     * @param category optional category filter
     * @param page zero-based page index
     * @param size requested page size
     * @return matching definitions
     */
    @GetMapping
    public List<GraphDefinition> queryDefinitions(@RequestParam(required = false) GraphDefinitionStatus status,
                                                  @RequestParam(required = false) String definitionKey,
                                                  @RequestParam(required = false) String ownerTeam,
                                                  @RequestParam(required = false) GraphCategory category,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        TenantContext scope = scopeResolver.currentScope();
        return graphEngineService.queryDefinitions(new GraphDefinitionQuery(
                scope.tenantId(),
                scope.namespace(),
                status,
                definitionKey,
                ownerTeam,
                category,
                page,
                size
        ));
    }

    /**
     * Loads one graph definition by business key inside the current tenant scope.
     *
     * @param definitionKey graph definition key
     * @return matching definition
     */
    @GetMapping("/{definitionKey:.+}")
    public GraphDefinition getDefinition(@PathVariable String definitionKey) {
        TenantContext scope = scopeResolver.currentScope();
        return graphEngineService.getDefinitionByKey(definitionKey, scope.tenantId(), scope.namespace());
    }

    /**
     * Updates mutable metadata for one graph definition.
     *
     * @param definitionKey definition key from the URL
     * @param request update request
     * @return updated definition snapshot
     */
    @PutMapping("/{definitionKey:.+}")
    public GraphDefinition updateDefinition(@PathVariable String definitionKey,
                                            @Valid @RequestBody UpdateDefinitionRequest request) {
        GraphDefinition existing = getDefinition(definitionKey);
        return graphEngineService.updateDefinition(new UpdateDefinitionCommand(
                existing.definitionId(),
                request.expectedRevision(),
                request.displayName(),
                request.description(),
                request.category(),
                request.labels(),
                request.ownerTeam(),
                request.rbacPolicy()
        ));
    }

    /**
     * Archives one graph definition.
     *
     * @param definitionKey definition key from the URL
     * @param expectedRevision optimistic-lock revision expected by the caller
     * @return archived definition snapshot
     */
    @DeleteMapping("/{definitionKey:.+}")
    public GraphDefinition archiveDefinition(@PathVariable String definitionKey,
                                             @RequestParam("expectedRevision") long expectedRevision) {
        GraphDefinition existing = getDefinition(definitionKey);
        return graphEngineService.archiveDefinition(existing.definitionId(), expectedRevision);
    }
}
