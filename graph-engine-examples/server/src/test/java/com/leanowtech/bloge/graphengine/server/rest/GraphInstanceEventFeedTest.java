package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceEvent;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.service.GraphEngineServiceErrorCode;
import com.leanowtech.bloge.graphengine.service.GraphEngineServiceException;
import com.leanowtech.bloge.runtime.eventjournal.ExecutionEvent;
import com.leanowtech.bloge.runtime.eventjournal.InMemoryExecutionEventStore;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import reactor.test.StepVerifier;

class GraphInstanceEventFeedTest {

    @Test
    void loadSinceFiltersToSequencesGreaterThanCursorAndEncodesPayloadJson() {
        InMemoryExecutionEventStore store = new InMemoryExecutionEventStore();
        GraphInstanceEventFeed feed = new GraphInstanceEventFeed(store, JsonCodec.DEFAULT, Duration.ofMillis(10));
        GraphInstance instance = instance("exec-1", "acme", "sales");
        store.append(new ExecutionEvent.NodeStarted(
                header("exec-1", "approval-flow", "2025-01-01T00:00:00Z", 1L, "acme", "sales"),
                "approval", "user", "{\"approved\":false}"
        ));
        store.append(new ExecutionEvent.NodeCompleted(
                header("exec-1", "approval-flow", "2025-01-01T00:00:01Z", 2L, "acme", "sales"),
                "approval", "user", "{\"approved\":true}", 15L
        ));
        store.append(new ExecutionEvent.NodeFailed(
                header("exec-1", "approval-flow", "2025-01-01T00:00:02Z", 3L, "other", "sales"),
                "approval", "user", "should-not-leak", 1
        ));

        List<GraphInstanceEvent> events = feed.loadSince(instance, 1L);

        assertThat(events).hasSize(1);
        GraphInstanceEvent event = events.getFirst();
        assertThat(event.sequenceNumber()).isEqualTo(2L);
        assertThat(event.eventType()).isEqualTo("NODE_COMPLETED");
        assertThat(event.nodeId()).isEqualTo("approval");
        assertThat(event.operatorRef()).isEqualTo("user");
        assertThat(event.payloadJson()).contains("elapsedMs", "15");
        assertThat(event.payloadJson()).contains("outputJson");
    }

    @Test
    void loadSinceExcludesResumeSequenceBoundary() {
        InMemoryExecutionEventStore store = new InMemoryExecutionEventStore();
        GraphInstanceEventFeed feed = new GraphInstanceEventFeed(store, JsonCodec.DEFAULT, Duration.ofMillis(10));
        GraphInstance instance = instance("exec-2", "acme", "sales");
        store.append(new ExecutionEvent.NodeStarted(
                header("exec-2", "approval-flow", "2025-01-01T00:00:00Z", 4L, "acme", "sales"),
                "approval", "user", "{\"approved\":false}"
        ));
        store.append(new ExecutionEvent.NodeCompleted(
                header("exec-2", "approval-flow", "2025-01-01T00:00:01Z", 5L, "acme", "sales"),
                "approval", "user", "{\"approved\":true}", 10L
        ));
        store.append(new ExecutionEvent.NodeCompleted(
                header("exec-2", "approval-flow", "2025-01-01T00:00:02Z", 6L, "acme", "sales"),
                "approval", "user", "{\"approved\":\"final\"}", 12L
        ));

        List<GraphInstanceEvent> events = feed.loadSince(instance, 5L);

        assertThat(events).extracting(GraphInstanceEvent::sequenceNumber).containsExactly(6L);
    }

    @Test
    void loadSinceAllowsLegacyEventsForDefaultTenantInstances() {
        InMemoryExecutionEventStore store = new InMemoryExecutionEventStore();
        GraphInstanceEventFeed feed = new GraphInstanceEventFeed(store, JsonCodec.DEFAULT, Duration.ofMillis(10));
        GraphInstance instance = instance("exec-default", null, null);
        store.append(new ExecutionEvent.NodeStarted(
                header("exec-default", "approval-flow", "2025-01-01T00:00:00Z", 1L, null, null),
                "approval", "user", "{\"approved\":false}"
        ));

        List<GraphInstanceEvent> events = feed.loadSince(instance, 0L);

        assertThat(events).extracting(GraphInstanceEvent::sequenceNumber).containsExactly(1L);
    }

    @Test
    void loadSinceRejectsEventsMissingTenantMetadataForExplicitTenantInstances() {
        InMemoryExecutionEventStore store = new InMemoryExecutionEventStore();
        GraphInstanceEventFeed feed = new GraphInstanceEventFeed(store, JsonCodec.DEFAULT, Duration.ofMillis(10));
        GraphInstance instance = instance("exec-explicit-tenant", "acme", "sales");
        store.append(new ExecutionEvent.NodeStarted(
                header("exec-explicit-tenant", "approval-flow", "2025-01-01T00:00:00Z", 1L, null, "sales"),
                "approval", "user", "{\"approved\":false}"
        ));

        assertThat(feed.loadSince(instance, 0L)).isEmpty();
    }

    @Test
    void loadSinceRejectsEventsMissingNamespaceMetadataForExplicitNamespaces() {
        InMemoryExecutionEventStore store = new InMemoryExecutionEventStore();
        GraphInstanceEventFeed feed = new GraphInstanceEventFeed(store, JsonCodec.DEFAULT, Duration.ofMillis(10));
        GraphInstance instance = instance("exec-explicit-namespace", "acme", "sales");
        store.append(new ExecutionEvent.NodeStarted(
                header("exec-explicit-namespace", "approval-flow", "2025-01-01T00:00:00Z", 1L, "acme", null),
                "approval", "user", "{\"approved\":false}"
        ));

        assertThat(feed.loadSince(instance, 0L)).isEmpty();
    }

    @Test
    void loadSinceRejectsNamespaceMismatchForExplicitNamespaces() {
        InMemoryExecutionEventStore store = new InMemoryExecutionEventStore();
        GraphInstanceEventFeed feed = new GraphInstanceEventFeed(store, JsonCodec.DEFAULT, Duration.ofMillis(10));
        GraphInstance instance = instance("exec-namespace-mismatch", "acme", "sales");
        store.append(new ExecutionEvent.NodeStarted(
                header("exec-namespace-mismatch", "approval-flow", "2025-01-01T00:00:00Z", 1L, "acme", "support"),
                "approval", "user", "{\"approved\":false}"
        ));

        assertThat(feed.loadSince(instance, 0L)).isEmpty();
    }

    @Test
    void parseLastEventIdGracefullyHandlesInvalidValues() {
        assertThat(GraphInstanceEventFeed.parseLastEventId(null)).isZero();
        assertThat(GraphInstanceEventFeed.parseLastEventId("")).isZero();
        assertThat(GraphInstanceEventFeed.parseLastEventId("abc")).isZero();
        assertThat(GraphInstanceEventFeed.parseLastEventId("-5")).isZero();
        assertThat(GraphInstanceEventFeed.parseLastEventId("7")).isEqualTo(7L);
    }

    @Test
    void requireAvailableFailsWhenEventJournalDisabled() {
        GraphInstanceEventFeed feed = new GraphInstanceEventFeed(null, JsonCodec.DEFAULT, Duration.ofMillis(10));

        assertThatThrownBy(feed::requireAvailable)
                .isInstanceOf(GraphEngineServiceException.class)
                .satisfies(exception -> assertThat(((GraphEngineServiceException) exception).errorCode())
                        .isEqualTo(GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE));
    }

    @Test
    void streamCompletesImmediatelyWhenReplayAlreadyContainsTerminalEvent() {
        InMemoryExecutionEventStore store = new InMemoryExecutionEventStore();
        GraphInstanceEventFeed feed = new GraphInstanceEventFeed(store, JsonCodec.DEFAULT, Duration.ofMillis(10));
        GraphInstance instance = instance("exec-terminal-replay", "acme", "sales");
        store.append(new ExecutionEvent.NodeCompleted(
                header("exec-terminal-replay", "approval-flow", "2025-01-01T00:00:00Z", 1L, "acme", "sales"),
                "approval", "user", "{\"approved\":true}", 12L
        ));
        store.append(new ExecutionEvent.GraphCompleted(
                header("exec-terminal-replay", "approval-flow", "2025-01-01T00:00:01Z", 2L, "acme", "sales"),
                true,
                false,
                25L,
                "{\"approved\":true}"
        ));

        StepVerifier.create(feed.stream(instance, 0L))
                .expectNextMatches(event -> "1".equals(event.id()) && "NODE_COMPLETED".equals(event.data().eventType()))
                .expectNextMatches(event -> "2".equals(event.id()) && "GRAPH_COMPLETED".equals(event.data().eventType()))
                .verifyComplete();
    }

    @Test
    void streamCompletesAfterPollingDetectsGraphCompleted() {
        InMemoryExecutionEventStore store = new InMemoryExecutionEventStore();
        GraphInstanceEventFeed feed = new GraphInstanceEventFeed(store, JsonCodec.DEFAULT, Duration.ofMillis(20));
        GraphInstance instance = instance("exec-terminal-poll", "acme", "sales");
        store.append(new ExecutionEvent.NodeStarted(
                header("exec-terminal-poll", "approval-flow", "2025-01-01T00:00:00Z", 1L, "acme", "sales"),
                "approval", "user", "{\"approved\":false}"
        ));

        Thread publisher = new Thread(() -> {
            try {
                Thread.sleep(40);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while publishing terminal event", exception);
            }
            store.append(new ExecutionEvent.GraphCompleted(
                    header("exec-terminal-poll", "approval-flow", "2025-01-01T00:00:01Z", 2L, "acme", "sales"),
                    true,
                    false,
                    25L,
                    "{\"approved\":true}"
            ));
        });
        publisher.start();
        try {
            StepVerifier.create(feed.stream(instance, 0L))
                    .expectNextMatches(event -> "1".equals(event.id()) && "NODE_STARTED".equals(event.data().eventType()))
                    .expectNextMatches(event -> "2".equals(event.id()) && "GRAPH_COMPLETED".equals(event.data().eventType()))
                    .verifyComplete();
        } finally {
            try {
                publisher.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while joining publisher thread", exception);
            }
        }
    }

    private static GraphInstance instance(String instanceId, String tenantId, String namespace) {
        return new GraphInstance(
                instanceId,
                "approval-flow",
                "ver-1",
                tenantId,
                namespace,
                "business-key",
                GraphExecutionMode.GRAPH,
                GraphInstanceStatus.RUNNING,
                "starter",
                Map.of(),
                1,
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"),
                null
        );
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
