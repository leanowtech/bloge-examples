package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphAuditEntry;
import com.leanowtech.bloge.graphengine.model.GraphControlActionEntry;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceDiagram;
import com.leanowtech.bloge.graphengine.model.GraphInstanceContext;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.model.GraphNodeState;
import com.leanowtech.bloge.graphengine.model.GraphNodeStatus;
import com.leanowtech.bloge.graphengine.model.GraphPendingSignal;
import com.leanowtech.bloge.graphengine.model.PagedResult;
import com.leanowtech.bloge.graphengine.model.GraphTransitionEntry;
import com.leanowtech.bloge.graphengine.server.config.GraphEngineServerProperties;
import com.leanowtech.bloge.graphengine.server.rest.dto.LifecycleActionRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.RetryInstanceRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.SignalInstanceRequest;
import com.leanowtech.bloge.graphengine.server.rest.dto.StartInstanceRequest;
import com.leanowtech.bloge.graphengine.service.GraphEngineService;
import com.leanowtech.bloge.graphengine.service.RetryInstanceResult;
import com.leanowtech.bloge.graphengine.service.SignalInstanceResult;
import com.leanowtech.bloge.graphengine.service.StartInstanceResult;
import com.leanowtech.bloge.graphengine.service.command.SignalInstanceCommand;
import com.leanowtech.bloge.graphengine.service.command.StartInstanceCommand;
import com.leanowtech.bloge.graphengine.store.GraphInstanceQuery;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * REST controller for graph instance start, query, and signal operations.
 */
@RestController
@Validated
public class GraphInstanceController {

    private final GraphEngineService graphEngineService;
    private final GraphEngineRequestScopeResolver scopeResolver;
    private final GraphEngineServerProperties properties;

    /**
     * Creates one controller instance.
     *
     * @param graphEngineService product control-plane service
     * @param scopeResolver request-scope resolver
     * @param properties server properties
     */
    public GraphInstanceController(GraphEngineService graphEngineService,
                                   GraphEngineRequestScopeResolver scopeResolver,
                                   GraphEngineServerProperties properties) {
        this.graphEngineService = graphEngineService;
        this.scopeResolver = scopeResolver;
        this.properties = properties;
    }

    /**
     * Starts a new instance for the addressed graph definition.
     *
     * @param definitionKey definition key from the URL
     * @param request start request
     * @return start result with the projected instance snapshot
     */
    @PostMapping("/api/v1/graphs/{definitionKey:.+}/instances")
    public ResponseEntity<StartInstanceResult> startInstance(@PathVariable String definitionKey,
                                                             @Valid @RequestBody StartInstanceRequest request) {
        TenantContext scope = scopeResolver.currentScope();
        StartInstanceResult started = graphEngineService.startInstance(new StartInstanceCommand(
                definitionKey,
                scope.tenantId(),
                scope.namespace(),
                request.version(),
                resolveEnvironment(request.environment()),
                request.businessKey(),
                request.initiator(),
                request.variables()
        ));
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/instances/{instanceId}")
                .buildAndExpand(started.instance().instanceId())
                .toUri();
        return ResponseEntity.created(location).body(started);
    }

    /**
     * Lists projected graph instances visible to the current tenant scope.
     *
     * @param definitionKey optional definition-key filter
     * @param businessKey optional business-key filter
     * @param statuses optional instance-state filters
     * @param executionMode optional execution-mode filter
     * @param page zero-based page index
     * @param size requested page size
     * @return matching instances
     */
    @GetMapping("/api/v1/instances")
    public List<GraphInstance> queryInstances(@RequestParam(required = false) String definitionKey,
                                              @RequestParam(required = false) String businessKey,
                                              @RequestParam(required = false) Set<GraphInstanceStatus> statuses,
                                              @RequestParam(required = false) GraphExecutionMode executionMode,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        TenantContext scope = scopeResolver.currentScope();
        return graphEngineService.queryInstances(new GraphInstanceQuery(
                scope.tenantId(),
                scope.namespace(),
                definitionKey,
                businessKey,
                statuses,
                executionMode,
                page,
                size
        ));
    }

    /**
     * Loads one projected graph instance.
     *
     * @param instanceId instance identifier
     * @return matching instance snapshot
     */
    @GetMapping("/api/v1/instances/{instanceId}")
    public GraphInstance getInstance(@PathVariable String instanceId) {
        return graphEngineService.getInstance(instanceId);
    }

    /**
     * Loads one business-safe context snapshot for an instance.
     *
     * @param instanceId instance identifier
     * @return context projection for the addressed instance
     */
    @GetMapping("/api/v1/instances/{instanceId}/context")
    public GraphInstanceContext getInstanceContext(@PathVariable String instanceId) {
        return graphEngineService.getInstanceContext(instanceId);
    }

    /**
     * Delivers an external signal to one running or suspended instance.
     *
     * @param instanceId instance identifier
     * @param request signal request
     * @return refreshed instance result
     */
    @PostMapping("/api/v1/instances/{instanceId}/signal")
    public SignalInstanceResult signalInstance(@PathVariable String instanceId,
                                               @Valid @RequestBody SignalInstanceRequest request) {
        return graphEngineService.signalInstance(new SignalInstanceCommand(
                instanceId,
                request.nodeId(),
                request.eventName(),
                request.payload(),
                request.callerId()
        ));
    }

    /**
     * Cancels one running instance.
     *
     * @param instanceId instance identifier
     * @param request lifecycle request with optimistic-lock revision and reason
     * @return refreshed cancelled instance
     */
    @PostMapping("/api/v1/instances/{instanceId}/cancel")
    public GraphInstance cancelInstance(@PathVariable String instanceId,
                                        @Valid @RequestBody LifecycleActionRequest request) {
        return graphEngineService.cancelInstance(instanceId, request.reason(), request.expectedRevision());
    }

    /**
     * Force-terminates one running instance.
     *
     * @param instanceId instance identifier
     * @param request lifecycle request with optimistic-lock revision and reason
     * @return refreshed terminated instance
     */
    @PostMapping("/api/v1/instances/{instanceId}/terminate")
    public GraphInstance terminateInstance(@PathVariable String instanceId,
                                           @Valid @RequestBody LifecycleActionRequest request) {
        return graphEngineService.terminateInstance(instanceId, request.reason(), request.expectedRevision());
    }

    /**
     * Lists node-level audit events for one instance.
     *
     * @param instanceId instance identifier
     * @param page zero-based page index
     * @param size requested page size
     * @return matching audit events
     */
    @GetMapping("/api/v1/instances/{instanceId}/audit")
    public List<GraphAuditEntry> queryAuditLog(@PathVariable String instanceId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size) {
        return graphEngineService.queryInstanceAuditLog(instanceId, page, size);
    }

    /**
     * Lists structured control-plane action events for one instance.
     *
     * @param instanceId instance identifier
     * @param page zero-based page index
     * @param size requested page size
     * @return matching control action events
     */
    @GetMapping("/api/v1/instances/{instanceId}/control-actions")
    public List<GraphControlActionEntry> queryControlActions(@PathVariable String instanceId,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "50") int size) {
        return graphEngineService.queryInstanceControlActions(instanceId, page, size);
    }

    /**
     * Lists status transitions for one instance.
     *
     * @param instanceId instance identifier
     * @param page zero-based page index
     * @param size requested page size
     * @return matching transition entries
     */
    @GetMapping("/api/v1/instances/{instanceId}/transitions")
    public List<GraphTransitionEntry> queryTransitions(@PathVariable String instanceId,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "50") int size) {
        return graphEngineService.queryInstanceTransitions(instanceId, page, size);
    }

    /**
     * Returns the inferred execution state of every node in one graph instance.
     *
     * <p>Node state is derived from durable checkpoints, waits, and work items —
     * no persistent per-node status map exists. See {@link GraphNodeStatus} for
     * the inference semantics.</p>
     *
     * @param instanceId instance identifier
     * @param statuses optional node-status filter
     * @param page zero-based page index
     * @param size requested page size
     * @return filtered node states in topological order
     */
    @GetMapping("/api/v1/instances/{instanceId}/nodes")
    public PagedResult<GraphNodeState> queryInstanceNodes(@PathVariable String instanceId,
                                                          @RequestParam(name = "status", required = false) Set<GraphNodeStatus> statuses,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "50") int size) {
        return graphEngineService.queryInstanceNodes(instanceId, statuses, page, size);
    }

    /**
     * Returns the stored diagram layout plus the current node-state overlay for one instance.
     *
     * @param instanceId instance identifier
     * @return diagram payload
     */
    @GetMapping("/api/v1/instances/{instanceId}/diagram")
    public GraphInstanceDiagram getInstanceDiagram(@PathVariable String instanceId) {
        return graphEngineService.getInstanceDiagram(instanceId);
    }

    /**
     * Returns the external signals currently awaited by one suspended GRAPH instance.
     *
     * @param instanceId instance identifier
     * @return pending signal projections
     */
    @GetMapping("/api/v1/instances/{instanceId}/pending-signals")
    public List<GraphPendingSignal> queryPendingSignals(@PathVariable String instanceId) {
        return graphEngineService.queryPendingSignals(instanceId);
    }

    /**
     * Retries all dead-lettered work items for one instance, optionally filtered
     * to a subset of node identifiers.
     *
     * @param instanceId instance identifier
     * @param request    retry request with optional node filter and optimistic-lock revision
     * @return retry result with refreshed instance and count of restored items
     */
    @PostMapping("/api/v1/instances/{instanceId}/retry")
    public RetryInstanceResult retryInstance(@PathVariable String instanceId,
                                             @Valid @RequestBody RetryInstanceRequest request) {
        return graphEngineService.retryInstance(
                instanceId,
                request.nodeIds(),
                request.expectedRevision(),
                request.toEvidence()
        );
    }

    private String resolveEnvironment(String requestedEnvironment) {
        if (requestedEnvironment == null || requestedEnvironment.isBlank()) {
            return properties.getDefaultEnvironment();
        }
        return requestedEnvironment;
    }
}
