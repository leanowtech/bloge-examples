package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceEvent;
import com.leanowtech.bloge.graphengine.service.GraphEngineServiceErrorCode;
import com.leanowtech.bloge.graphengine.service.GraphEngineServiceException;
import com.leanowtech.bloge.runtime.eventjournal.ExecutionEvent;
import com.leanowtech.bloge.runtime.eventjournal.ExecutionEventPayloadCodec;
import com.leanowtech.bloge.runtime.eventjournal.ExecutionEventStore;

import org.springframework.http.codec.ServerSentEvent;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Server-local helper that turns durable execution-journal rows into instance SSE payloads.
 */
public final class GraphInstanceEventFeed {
    private static final Logger logger = Logger.getLogger(GraphInstanceEventFeed.class.getName());
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);

    private final ExecutionEventStore executionEventStore;
    private final JsonCodec jsonCodec;
    private final Duration pollInterval;

    public GraphInstanceEventFeed(ExecutionEventStore executionEventStore, JsonCodec jsonCodec) {
        this(executionEventStore, jsonCodec, DEFAULT_POLL_INTERVAL);
    }

    public GraphInstanceEventFeed(ExecutionEventStore executionEventStore,
                                  JsonCodec jsonCodec,
                                  Duration pollInterval) {
        this.executionEventStore = executionEventStore;
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
    }

    public void requireAvailable() {
        if (executionEventStore == null) {
            throw new GraphEngineServiceException(
                    GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE,
                    "Instance event streaming requires spring.bloge.event-journal.enabled=true"
            );
        }
    }

    public Flux<ServerSentEvent<GraphInstanceEvent>> stream(GraphInstance instance, long afterSequence) {
        requireAvailable();
        AtomicLong lastSeen = new AtomicLong(Math.max(0L, afterSequence));
        List<GraphInstanceEvent> initialEvents = loadSince(instance, lastSeen.get());
        initialEvents.forEach(event -> lastSeen.updateAndGet(current -> Math.max(current, event.sequenceNumber())));
        Flux<ServerSentEvent<GraphInstanceEvent>> replay = Flux.fromIterable(initialEvents)
                .map(GraphInstanceEventFeed::toSse);
        if (initialEvents.stream().anyMatch(GraphInstanceEventFeed::isTerminalEvent)) {
            return replay;
        }
        return Flux.concat(
                replay,
                Flux.interval(pollInterval)
                        .concatMap(tick -> Flux.fromIterable(loadSince(instance, lastSeen.get()))
                                .doOnNext(event -> lastSeen.updateAndGet(current -> Math.max(current, event.sequenceNumber())))
                                .map(GraphInstanceEventFeed::toSse))
        ).takeUntil(sse -> isTerminalEvent(sse.data()));
    }

    List<GraphInstanceEvent> loadSince(GraphInstance instance, long afterSequence) {
        requireAvailable();
        long resumeFrom = Math.max(0L, afterSequence);
        // ExecutionEventStore.loadEvents(id, from) is inclusive (>= from). SSE resume semantics must be
        // exclusive (> Last-Event-ID), so keep the second filter even though the store already filtered.
        return executionEventStore.loadEvents(instance.instanceId(), resumeFrom).stream()
                .filter(event -> event.sequenceNumber() > resumeFrom)
                .filter(event -> belongsToInstance(instance, event))
                .sorted(Comparator.comparingLong(ExecutionEvent::sequenceNumber))
                .map(this::mapEvent)
                .toList();
    }

    public static long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(lastEventId));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static boolean belongsToInstance(GraphInstance instance, ExecutionEvent event) {
        if (!Objects.equals(instance.instanceId(), event.executionId())) {
            return false;
        }

        boolean explicitTenant = !ExecutionIdentity.DEFAULT_TENANT.equals(instance.tenantId());
        if (explicitTenant) {
            if (event.tenantId() == null) {
                logger.warning(() -> "SSE filter rejected event " + event.sequenceNumber()
                        + " for instance " + instance.instanceId()
                        + " because tenant metadata was missing");
                return false;
            }
            if (!Objects.equals(instance.tenantId(), event.tenantId())) {
                return false;
            }
        }

        boolean explicitNamespace = !ExecutionIdentity.DEFAULT_NAMESPACE.equals(instance.namespace());
        if (explicitNamespace) {
            if (event.namespace() == null) {
                logger.warning(() -> "SSE filter rejected event " + event.sequenceNumber()
                        + " for instance " + instance.instanceId()
                        + " because namespace metadata was missing");
                return false;
            }
            if (!Objects.equals(instance.namespace(), event.namespace())) {
                return false;
            }
        }

        return true;
    }

    private static ServerSentEvent<GraphInstanceEvent> toSse(GraphInstanceEvent event) {
        return ServerSentEvent.<GraphInstanceEvent>builder()
                .id(Long.toString(event.sequenceNumber()))
                .data(event)
                .build();
    }

    private static boolean isTerminalEvent(GraphInstanceEvent event) {
        return event != null && "GRAPH_COMPLETED".equals(event.eventType());
    }

    private GraphInstanceEvent mapEvent(ExecutionEvent event) {
        return new GraphInstanceEvent(
                event.executionId(),
                event.sequenceNumber(),
                event.eventType().name(),
                event.timestamp(),
                event.nodeId(),
                event.operatorRef(),
                ExecutionEventPayloadCodec.encodePayload(event, jsonCodec)
        );
    }
}
