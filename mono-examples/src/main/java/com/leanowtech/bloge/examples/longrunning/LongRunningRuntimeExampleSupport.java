package com.leanowtech.bloge.examples.longrunning;

import com.leanowtech.bloge.core.checkpoint.AggregationMode;
import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.checkpoint.CorrelationStatus;
import com.leanowtech.bloge.core.checkpoint.EventCorrelation;
import com.leanowtech.bloge.core.checkpoint.EventMatcher;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.runtime.engine.DurableGraphEngine;
import com.leanowtech.bloge.runtime.timer.TimerManager;
import com.leanowtech.bloge.core.runtime.checkpoint.CheckpointType;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpoint;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpointStore;
import com.leanowtech.bloge.core.runtime.event.EventMatcherCorrelationStore;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStore;
import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.runtime.identity.ExecutionType;
import com.leanowtech.bloge.core.spi.ExecutionListener;
import com.leanowtech.bloge.core.spi.EventCorrelationStore;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.runtime.timer.InMemoryTimerService;
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
    /**
     * Example programs demonstrate the durable suspend/resume lifecycle in a single JVM process, so
     * they intentionally expire the in-memory suspension almost immediately. That lets
     * {@code execute(...)} return a suspended {@code GraphResult} quickly while checkpoints,
     * timers, and correlations remain in the durable stores used by {@link ExampleRuntime}.
     */
    private static final java.time.Duration EXAMPLE_IN_MEMORY_SUSPEND_TTL = java.time.Duration.ofMillis(25);

    // Inline JsonCodec adapter for in-memory examples only. Production deployments should use
    // JacksonCheckpointCodec from bloge-durable-codec for durable persistence compatibility.
    private static final CheckpointCodec CHECKPOINT_CODEC = new CheckpointCodec() {
        private final JsonCodec json = JsonCodec.DEFAULT;
        @Override public String serialize(Object value) { return json.serialize(value); }
        @Override public Object deserialize(String s) { return json.deserialize(s); }
        @SuppressWarnings("unchecked")
        @Override public <T> T deserialize(String s, Class<T> type) { return (T) json.deserialize(s); }
    };

    private LongRunningRuntimeExampleSupport() {
    }

    static ExampleRuntime runtime(OperatorRegistry registry, ExecutionListener... listeners) {
        var executionStore = new InMemoryExecutionStore();
        var executionCheckpointStore = new InMemoryExecutionCheckpointStore(executionStore);
        var waitStore = new InMemoryWaitStore(executionStore);
        var eventMatcherStore = new InMemoryEventMatcherStore();
        EventCorrelationStore eventCorrelationStore = new EventMatcherCorrelationStore(
                eventMatcherStore,
                executionCheckpointStore,
                executionStore,
                CHECKPOINT_CODEC
        );
        DurableGraphEngine engine = DurableGraphEngine.builder()
                .registry(registry)
                .listeners(Arrays.stream(listeners).toList())
                .inMemorySuspendTtl(EXAMPLE_IN_MEMORY_SUSPEND_TTL)
                .executionStore(executionStore)
                .executionCheckpointStore(executionCheckpointStore)
                .waitStore(waitStore)
                .checkpointCodec(CHECKPOINT_CODEC)
                .schedulerTimerSupport(new TimerManager(new InMemoryTimerService()))
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
        private final DurableGraphEngine engine;
        private final ExecutionStore executionStore;
        private final ExecutionCheckpointStore executionCheckpointStore;
        private final EventCorrelationStore eventCorrelationStore;

        private ExampleRuntime(DurableGraphEngine engine,
                               ExecutionStore executionStore,
                               ExecutionCheckpointStore executionCheckpointStore,
                               EventCorrelationStore eventCorrelationStore) {
            this.engine = engine;
            this.executionStore = executionStore;
            this.executionCheckpointStore = executionCheckpointStore;
            this.eventCorrelationStore = eventCorrelationStore;
        }

        DurableGraphEngine engine() {
            return engine;
        }

        GraphEngine coreEngine() {
            return engine.asGraphEngine();
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
            executionCheckpointStore.save(ExecutionCheckpoint.builder(identity, CheckpointType.NODE_OUTPUT, nodeId)
                    .payload(output instanceof String json ? json : CHECKPOINT_CODEC.serialize(output))
                    .schemaVersion("1")
                    .version(existing == null ? 0 : existing.version() + 1)
                    .createdAt(existing == null ? now : existing.createdAt())
                    .updatedAt(now)
                    .build());
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
