package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceEvent;
import com.leanowtech.bloge.graphengine.service.GraphEngineServiceException;
import com.leanowtech.bloge.runtime.eventjournal.ExecutionEvent;
import com.leanowtech.bloge.runtime.eventjournal.InMemoryExecutionEventStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.Disposable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphInstanceEventControllerTest extends AbstractGraphControllerTest {
    private InMemoryExecutionEventStore store;
    private GraphInstance instance;

    @BeforeEach
    void setUpController() {
        store = new InMemoryExecutionEventStore();
        instance = instance("exec-1", "approval-flow", "ver-1", com.leanowtech.bloge.graphengine.model.GraphInstanceStatus.RUNNING);
        graphEngineService.getInstanceResult = instance;
    }

    @Test
    void streamInstanceEventsUsesLastEventIdAndReleasesConnectionSlot() {
        store.append(new ExecutionEvent.NodeStarted(
                header("exec-1", "approval-flow", "2025-01-01T00:00:00Z", 1L, "default", "default"),
                "approval", "user", "{}"
        ));
        store.append(new ExecutionEvent.NodeCompleted(
                header("exec-1", "approval-flow", "2025-01-01T00:00:01Z", 2L, "default", "default"),
                "approval", "user", "{\"approved\":true}", 12L
        ));
        GraphSseConnectionLimiter limiter = new GraphSseConnectionLimiter(1);
        GraphInstanceEventController controller = new GraphInstanceEventController(
                graphEngineService,
                new GraphInstanceEventFeed(store, JsonCodec.DEFAULT, Duration.ofMillis(10)),
                limiter
        );

        List<ServerSentEvent<GraphInstanceEvent>> events = controller.streamInstanceEvents("exec-1", "1")
                .take(1)
                .collectList()
                .block(Duration.ofSeconds(2));

        assertThat(graphEngineService.getInstanceId).isEqualTo("exec-1");
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().id()).isEqualTo("2");
        assertThat(events.getFirst().data().eventType()).isEqualTo("NODE_COMPLETED");
        assertThat(events.getFirst().data().payloadJson()).contains("elapsedMs");
        assertThat(limiter.activeConnections("default:default")).isZero();
    }

    @Test
    void streamInstanceEventsRejectsWhenConnectionLimitReached() {
        GraphSseConnectionLimiter limiter = new GraphSseConnectionLimiter(1);
        GraphInstanceEventController controller = new GraphInstanceEventController(
                graphEngineService,
                new GraphInstanceEventFeed(store, JsonCodec.DEFAULT, Duration.ofMillis(50)),
                limiter
        );

        Disposable firstSubscription = controller.streamInstanceEvents("exec-1", null).subscribe();
        try {
            assertThatThrownBy(() -> controller.streamInstanceEvents("exec-1", null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        } finally {
            firstSubscription.dispose();
        }
    }

    @Test
    void streamInstanceEventsReleasesConnectionSlotWhenTerminalEventCompletesFlux() {
        store.append(new ExecutionEvent.GraphCompleted(
                header("exec-1", "approval-flow", "2025-01-01T00:00:01Z", 1L, "default", "default"),
                true,
                false,
                25L,
                "{\"approved\":true}"
        ));
        GraphSseConnectionLimiter limiter = new GraphSseConnectionLimiter(1);
        GraphInstanceEventController controller = new GraphInstanceEventController(
                graphEngineService,
                new GraphInstanceEventFeed(store, JsonCodec.DEFAULT, Duration.ofMillis(10)),
                limiter
        );

        List<ServerSentEvent<GraphInstanceEvent>> events = controller.streamInstanceEvents("exec-1", null)
                .collectList()
                .block(Duration.ofSeconds(2));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().data().eventType()).isEqualTo("GRAPH_COMPLETED");
        assertThat(limiter.activeConnections("default:default")).isZero();
    }

    @Test
    void streamInstanceEventsFailsWhenEventJournalDisabled() {
        GraphInstanceEventController controller = new GraphInstanceEventController(
                graphEngineService,
                new GraphInstanceEventFeed(null, JsonCodec.DEFAULT, Duration.ofMillis(10)),
                new GraphSseConnectionLimiter(1)
        );

        assertThatThrownBy(() -> controller.streamInstanceEvents("exec-1", null))
                .isInstanceOf(GraphEngineServiceException.class);
    }

    private static ExecutionEvent.EventHeader header(
            String executionId,
            String graphName,
            String timestamp,
            long sequenceNumber,
            String tenantId,
            String namespace
    ) {
        return new ExecutionEvent.EventHeader(
                executionId,
                graphName,
                Instant.parse(timestamp),
                sequenceNumber,
                tenantId,
                namespace
        );
    }
}
