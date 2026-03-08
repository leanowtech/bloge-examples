package com.leanowtech.bloge.examples.longrunning;

import com.leanowtech.bloge.core.checkpoint.AggregationMode;
import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.checkpoint.CorrelationStatus;
import com.leanowtech.bloge.core.checkpoint.EventCorrelation;
import com.leanowtech.bloge.core.checkpoint.EventMatcher;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.runtime.checkpoint.CheckpointType;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpoint;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpointStore;
import com.leanowtech.bloge.core.runtime.event.EventMatcherCorrelationStore;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStore;
import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.runtime.identity.ExecutionType;
import com.leanowtech.bloge.core.spi.ExecutionListener;
import com.leanowtech.bloge.core.spi.EventCorrelationStore;
import com.leanowtech.bloge.core.spi.InMemoryTimerService;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.durable.store.memory.InMemoryEventMatcherStore;
import com.leanowtech.bloge.durable.store.memory.InMemoryExecutionCheckpointStore;
import com.leanowtech.bloge.durable.store.memory.InMemoryExecutionStore;
import com.leanowtech.bloge.durable.store.memory.InMemoryWaitStore;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class LongRunningRuntimeExampleSupport {
    private static final CheckpointCodec CHECKPOINT_CODEC = CheckpointCodec.DEFAULT;

    private LongRunningRuntimeExampleSupport() {
    }

    static ExampleRuntime runtime(OperatorRegistry registry, ExecutionListener... listeners) {
        var executionStore = new InMemoryExecutionStore();
        var executionCheckpointStore = new InMemoryExecutionCheckpointStore();
        var waitStore = new InMemoryWaitStore();
        var eventMatcherStore = new InMemoryEventMatcherStore();
        EventCorrelationStore eventCorrelationStore = new EventMatcherCorrelationStore(
                eventMatcherStore,
                executionCheckpointStore,
                executionStore
        );
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .listeners(Arrays.stream(listeners).toList())
                .executionStore(executionStore)
                .executionCheckpointStore(executionCheckpointStore)
                .waitStore(waitStore)
                .timerService(new InMemoryTimerService())
                .eventCorrelationStore(eventCorrelationStore)
                .build();
        return new ExampleRuntime(engine, executionStore, executionCheckpointStore, eventCorrelationStore);
    }

    static EventSpec event(String eventName, String correlationKey, String expectedValue) {
        return new EventSpec(eventName, correlationKey, expectedValue, false);
    }

    static Map<String, Object> payload(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("payload requires even key/value pairs");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            payload.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return payload;
    }

    static final class ExampleRuntime {
        private final GraphEngine engine;
        private final ExecutionStore executionStore;
        private final ExecutionCheckpointStore executionCheckpointStore;
        private final EventCorrelationStore eventCorrelationStore;

        private ExampleRuntime(GraphEngine engine,
                               ExecutionStore executionStore,
                               ExecutionCheckpointStore executionCheckpointStore,
                               EventCorrelationStore eventCorrelationStore) {
            this.engine = engine;
            this.executionStore = executionStore;
            this.executionCheckpointStore = executionCheckpointStore;
            this.eventCorrelationStore = eventCorrelationStore;
        }

        GraphEngine engine() {
            return engine;
        }

        void registerOrCorrelation(String executionId, String nodeId, EventSpec... events) {
            registerCorrelation(executionId, nodeId, AggregationMode.OR, events);
        }

        void registerAndCorrelation(String executionId, String nodeId, EventSpec... events) {
            registerCorrelation(executionId, nodeId, AggregationMode.AND, events);
        }

        void saveNodeOutput(String executionId, String graphName, String nodeId, Object output) {
            ExecutionCheckpoint existing = executionCheckpointStore.load(executionId, CheckpointType.NODE_OUTPUT, nodeId)
                    .orElse(null);
            Instant now = Instant.now();
            ExecutionIdentity identity = executionStore.get(executionId)
                    .map(execution -> execution.identity().withGraph(
                            graphName != null ? graphName : execution.identity().graphName(),
                            execution.identity().graphVersion(),
                            execution.identity().graphHash()
                    ))
                    .orElseGet(() -> identity(executionId, graphName));
            executionCheckpointStore.save(new ExecutionCheckpoint(
                    identity,
                    CheckpointType.NODE_OUTPUT,
                    nodeId,
                    null,
                    output instanceof String json ? json : CHECKPOINT_CODEC.serialize(output),
                    null,
                    "1",
                    existing == null ? 0 : existing.version() + 1,
                    existing == null ? now : existing.createdAt(),
                    now
            ));
        }

        private void registerCorrelation(String executionId,
                                         String nodeId,
                                         AggregationMode mode,
                                         EventSpec... events) {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(nodeId, "nodeId");
            if (events == null || events.length == 0) {
                throw new IllegalArgumentException("At least one event matcher is required");
            }
            List<EventMatcher> matchers = Arrays.stream(events)
                    .map(event -> new EventMatcher(
                            event.eventName(),
                            event.correlationKey(),
                            event.expectedValue(),
                            event.optional()
                    ))
                    .toList();
            eventCorrelationStore.saveCorrelation(new EventCorrelation(
                    executionId + ':' + nodeId,
                    executionId,
                    nodeId,
                    matchers,
                    mode,
                    Map.of(),
                    Instant.now(),
                    CorrelationStatus.WAITING
            ));
        }
    }

    record EventSpec(String eventName, String correlationKey, String expectedValue, boolean optional) {
        EventSpec {
            eventName = Objects.requireNonNull(eventName, "eventName");
            correlationKey = Objects.requireNonNull(correlationKey, "correlationKey");
            expectedValue = Objects.requireNonNull(expectedValue, "expectedValue");
        }
    }

    private static ExecutionIdentity identity(String executionId, String graphName) {
        return new ExecutionIdentity(
                ExecutionIdentity.DEFAULT_TENANT,
                ExecutionIdentity.DEFAULT_NAMESPACE,
                graphName == null ? executionId : graphName + ':' + executionId,
                executionId,
                ExecutionType.GRAPH,
                graphName,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
