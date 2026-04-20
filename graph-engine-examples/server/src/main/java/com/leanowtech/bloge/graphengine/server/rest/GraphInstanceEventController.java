package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceEvent;
import com.leanowtech.bloge.graphengine.service.GraphEngineService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;

/**
 * Streams instance-scoped execution events over server-sent events.
 */
@RestController
public class GraphInstanceEventController {
    private final GraphEngineService graphEngineService;
    private final GraphInstanceEventFeed eventFeed;
    private final GraphSseConnectionLimiter connectionLimiter;

    public GraphInstanceEventController(GraphEngineService graphEngineService,
                                        GraphInstanceEventFeed eventFeed,
                                        GraphSseConnectionLimiter connectionLimiter) {
        this.graphEngineService = graphEngineService;
        this.eventFeed = eventFeed;
        this.connectionLimiter = connectionLimiter;
    }

    /**
     * Streams execution events for one visible instance.
     *
     * @param instanceId instance identifier
     * @param lastEventId optional SSE resume cursor
     * @return polling SSE stream of execution events
     */
    @GetMapping(value = "/api/v1/instances/{instanceId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<GraphInstanceEvent>> streamInstanceEvents(
            @PathVariable String instanceId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
    ) {
        GraphInstance instance = graphEngineService.getInstance(instanceId);
        eventFeed.requireAvailable();
        String tenantKey = tenantKey(instance);
        if (!connectionLimiter.acquire(tenantKey)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "SSE connection limit reached for tenant (max %d)".formatted(connectionLimiter.maxConnectionsPerTenant())
            );
        }
        try {
            return eventFeed.stream(instance, GraphInstanceEventFeed.parseLastEventId(lastEventId))
                    .doFinally(signal -> connectionLimiter.release(tenantKey));
        } catch (RuntimeException exception) {
            connectionLimiter.release(tenantKey);
            throw exception;
        }
    }

    private static String tenantKey(GraphInstance instance) {
        return instance.tenantId() + ":" + instance.namespace();
    }
}
