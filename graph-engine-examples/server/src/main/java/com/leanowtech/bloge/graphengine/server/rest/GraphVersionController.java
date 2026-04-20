package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionDiagram;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import com.leanowtech.bloge.graphengine.server.rest.dto.CreateVersionRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.ExpectedRevisionRequest;
import com.leanowtech.bloge.graphengine.service.GraphEngineService;
import com.leanowtech.bloge.graphengine.service.GraphEngineServiceErrorCode;
import com.leanowtech.bloge.graphengine.service.GraphEngineServiceException;
import com.leanowtech.bloge.graphengine.service.GraphVersionDiff;
import com.leanowtech.bloge.graphengine.service.PublishVersionResult;
import com.leanowtech.bloge.graphengine.service.VersionValidationResult;
import com.leanowtech.bloge.graphengine.service.command.CreateVersionCommand;
import com.leanowtech.bloge.graphengine.store.GraphVersionQuery;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * REST controller for immutable graph version authoring, validation, and publish operations.
 */
@RestController
@Validated
@RequestMapping("/api/v1/graphs")
public class GraphVersionController {

    private final GraphEngineService graphEngineService;
    private final GraphEngineRequestScopeResolver scopeResolver;

    /**
     * Creates one controller instance.
     *
     * @param graphEngineService product control-plane service
     * @param scopeResolver request-scope resolver
     */
    public GraphVersionController(GraphEngineService graphEngineService,
                                  GraphEngineRequestScopeResolver scopeResolver) {
        this.graphEngineService = graphEngineService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * Creates a new draft version for one graph definition.
     *
     * @param definitionKey definition key from the URL
     * @param request create-version request
     * @return created draft version
     */
    @PostMapping("/{definitionKey:.+}/versions")
    public ResponseEntity<GraphVersion> createVersion(@PathVariable String definitionKey,
                                                      @Valid @RequestBody CreateVersionRequest request) {
        TenantContext scope = scopeResolver.currentScope();
        GraphVersion version = graphEngineService.createVersion(new CreateVersionCommand(
                definitionKey,
                scope.tenantId(),
                scope.namespace(),
                request.version(),
                request.dslSource(),
                request.visualLayout(),
                request.migrationPolicy()
        ));
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{version}")
                .buildAndExpand(version.version())
                .toUri();
        return ResponseEntity.created(location).body(version);
    }

    /**
     * Lists versions for one graph definition.
     *
     * @param definitionKey definition key from the URL
     * @param statuses optional version-state filters
     * @param page zero-based page index
     * @param size requested page size
     * @return matching versions
     */
    @GetMapping("/{definitionKey:.+}/versions")
    public List<GraphVersion> queryVersions(@PathVariable String definitionKey,
                                            @RequestParam(required = false) Set<GraphVersionStatus> statuses,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        GraphDefinition definition = getDefinition(definitionKey);
        return graphEngineService.queryVersions(new GraphVersionQuery(
                definition.definitionId(),
                statuses,
                page,
                size
        ));
    }

    /**
     * Loads one graph version by definition key and semantic version string.
     *
     * @param definitionKey definition key from the URL
     * @param version version from the URL
     * @return matching version snapshot
     */
    @GetMapping("/{definitionKey:.+}/versions/{version:.+}")
    public GraphVersion getVersion(@PathVariable String definitionKey,
                                   @PathVariable String version) {
        return findVersion(definitionKey, version);
    }

    /**
     * Loads the stored visual layout payload for one semantic version.
     *
     * @param definitionKey definition key from the URL
     * @param version version from the URL
     * @return version diagram payload
     */
    @GetMapping("/{definitionKey:.+}/versions/{version:.+}/diagram")
    public GraphVersionDiagram getVersionDiagram(@PathVariable String definitionKey,
                                                 @PathVariable String version) {
        GraphVersion target = findVersion(definitionKey, version);
        return graphEngineService.getVersionDiagram(target.versionId());
    }

    /**
     * Validates one stored graph version.
     *
     * @param definitionKey definition key from the URL
     * @param version version from the URL
     * @return validation result
     */
    @PostMapping("/{definitionKey:.+}/versions/{version:.+}/validate")
    public VersionValidationResult validateVersion(@PathVariable String definitionKey,
                                                   @PathVariable String version) {
        GraphVersion target = findVersion(definitionKey, version);
        return graphEngineService.validateVersion(target.versionId());
    }

    /**
     * Publishes one stored graph version into the underlying runtime.
     *
     * @param definitionKey definition key from the URL
     * @param version version from the URL
     * @param request publish request
     * @return publish result with compatibility information
     */
    @PostMapping("/{definitionKey:.+}/versions/{version:.+}/publish")
    public PublishVersionResult publishVersion(@PathVariable String definitionKey,
                                               @PathVariable String version,
                                               @Valid @RequestBody ExpectedRevisionRequest request) {
        GraphVersion target = findVersion(definitionKey, version);
        return graphEngineService.publishVersion(target.versionId(), request.expectedRevision());
    }

    /**
     * Deprecates one published graph version so future starts stop routing to it.
     *
     * @param definitionKey definition key from the URL
     * @param version version from the URL
     * @param request deprecation request
     * @return deprecated version snapshot
     */
    @PostMapping("/{definitionKey:.+}/versions/{version:.+}/deprecate")
    public GraphVersion deprecateVersion(@PathVariable String definitionKey,
                                         @PathVariable String version,
                                         @Valid @RequestBody ExpectedRevisionRequest request) {
        GraphVersion target = findVersion(definitionKey, version);
        return graphEngineService.deprecateVersion(target.versionId(), request.expectedRevision());
    }

    /**
     * Computes a structural diff between two versions of the same graph definition.
     *
     * @param definitionKey definition key from the URL
     * @param leftVersion   left (typically older) semantic version string
     * @param rightVersion  right (typically newer) semantic version string
     * @return version diff result
     */
    @GetMapping("/{definitionKey:.+}/versions/{leftVersion:.+}/diff/{rightVersion:.+}")
    public GraphVersionDiff diffVersions(@PathVariable String definitionKey,
                                         @PathVariable String leftVersion,
                                         @PathVariable String rightVersion) {
        GraphVersion left = findVersion(definitionKey, leftVersion);
        GraphVersion right = findVersion(definitionKey, rightVersion);
        return graphEngineService.diffVersions(left.versionId(), right.versionId());
    }

    private GraphDefinition getDefinition(String definitionKey) {
        TenantContext scope = scopeResolver.currentScope();
        return graphEngineService.getDefinitionByKey(definitionKey, scope.tenantId(), scope.namespace());
    }

    private GraphVersion findVersion(String definitionKey, String version) {
        GraphDefinition definition = getDefinition(definitionKey);
        return graphEngineService.queryVersions(new GraphVersionQuery(
                        definition.definitionId(),
                        Set.of(),
                        0,
                        Integer.MAX_VALUE
                )).stream()
                .filter(candidate -> version.equals(candidate.version()))
                .findFirst()
                .orElseThrow(() -> new GraphEngineServiceException(
                        GraphEngineServiceErrorCode.NOT_FOUND,
                        "Version '" + version + "' not found for definition '" + definitionKey + "'"
                ));
    }
}
