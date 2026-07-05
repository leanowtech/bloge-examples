package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.runtime.work.WorkItemType;
import com.leanowtech.bloge.graphengine.model.GraphDeadLetter;
import com.leanowtech.bloge.graphengine.service.GraphEngineService;
import com.leanowtech.bloge.graphengine.server.rest.dto.DeadLetterRetryRequest;
import com.leanowtech.bloge.graphengine.store.GraphDeadLetterQuery;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * REST controller for dead-letter inspection and replay.
 */
@RestController
@Validated
public class GraphDeadLetterController {

    private final GraphEngineService graphEngineService;
    private final GraphEngineRequestScopeResolver scopeResolver;

    /**
     * Creates one controller instance.
     *
     * @param graphEngineService product control-plane service
     * @param scopeResolver request-scope resolver
     */
    public GraphDeadLetterController(GraphEngineService graphEngineService,
                                     GraphEngineRequestScopeResolver scopeResolver) {
        this.graphEngineService = graphEngineService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * Lists tenant-scoped dead-lettered work items.
     *
     * @param itemId optional item identifier filter
     * @param instanceId optional instance identifier filter
     * @param itemType optional durable work-item type filter
     * @param shardId optional shard identifier filter
     * @param deadLetteredAfter optional lower timestamp bound
     * @param page zero-based page index
     * @param size requested page size
     * @return matching dead-letter entries
     */
    @GetMapping("/api/v1/dead-letters")
    public List<GraphDeadLetter> queryDeadLetters(@RequestParam(required = false) String itemId,
                                                  @RequestParam(required = false) String instanceId,
                                                  @RequestParam(required = false) WorkItemType itemType,
                                                  @RequestParam(required = false) String shardId,
                                                  @RequestParam(required = false)
                                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant deadLetteredAfter,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        TenantContext scope = scopeResolver.currentScope();
        return graphEngineService.queryDeadLetters(new GraphDeadLetterQuery(
                scope.tenantId(),
                scope.namespace(),
                itemId,
                instanceId,
                itemType,
                shardId,
                deadLetteredAfter,
                page,
                size
        ));
    }

    /**
     * Replays one dead-lettered work item.
     *
     * @param itemId dead-letter item identifier
     * @param request optional recovery evidence
     * @return empty response when the replay request has been accepted
     */
    @PostMapping("/api/v1/dead-letters/{itemId}/retry")
    public ResponseEntity<Void> retryDeadLetter(@PathVariable String itemId,
                                                @RequestBody(required = false) DeadLetterRetryRequest request) {
        graphEngineService.retryDeadLetter(itemId, DeadLetterRetryRequest.toEvidence(request));
        return ResponseEntity.noContent().build();
    }
}
