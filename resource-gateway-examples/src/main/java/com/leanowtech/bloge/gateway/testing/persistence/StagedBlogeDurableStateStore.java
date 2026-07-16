package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.exception.DurabilityException;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpointStore;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStore;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Execution-scoped transaction boundary for all BLOGE state required by durable tests.
 *
 * <p>The aggregate is the only object the runtime factory receives. It deliberately keeps the
 * concrete execution and checkpoint stores out of Spring's global bean registry, opens both
 * stages together, and emits one content-addressed {@link PreparedMutation}. That mutation applies
 * both store snapshots through one repository-owned JDBC transaction, so a process cannot publish
 * a control checkpoint that refers to a missing execution row or node checkpoint.</p>
 *
 * <p>Wait and work-item stores will join this same aggregate as their industrial implementation is
 * added. The aggregate fingerprint schema makes that extension explicit and prevents callers from
 * treating independently prepared component mutations as a complete engine closure.</p>
 */
public final class StagedBlogeDurableStateStore {

    private static final String CLOSURE_SCHEMA_VERSION = "bloge.testDurableStateMutation.v1";

    private final ObjectMapper objectMapper;
    private final StagedBlogeExecutionStore executionStore;
    private final StagedBlogeExecutionCheckpointStore checkpointStore;

    /**
     * Creates the full durable-state aggregate over one test-runtime datasource.
     *
     * @param jdbc shared transaction-capable JDBC facade
     * @param objectMapper canonical mapper used by both stores and aggregate fingerprints
     */
    public StagedBlogeDurableStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.executionStore = new StagedBlogeExecutionStore(jdbc, objectMapper);
        this.checkpointStore = new StagedBlogeExecutionCheckpointStore(jdbc, objectMapper);
    }

    /** Creates every table owned by the current durable-state aggregate version. */
    @PostConstruct
    public void init() {
        executionStore.init();
        checkpointStore.init();
    }

    /** @return lifecycle and lease SPI attached to the isolated durable engine */
    public ExecutionStore executionStore() {
        return executionStore;
    }

    /** @return node and suspend checkpoint SPI attached to the isolated durable engine */
    public ExecutionCheckpointStore checkpointStore() {
        return checkpointStore;
    }

    /**
     * Opens all component stages for one execution as a single lifecycle scope.
     *
     * @param executionId trusted BLOGE execution identity
     * @param timeSource run-scoped logical clock
     * @return aggregate stage that must be prepared once and then closed
     */
    public Stage begin(String executionId, TimeSource timeSource) {
        StagedBlogeExecutionStore.Stage executionStage =
                executionStore.begin(executionId, timeSource);
        try {
            StagedBlogeExecutionCheckpointStore.Stage checkpointStage =
                    checkpointStore.begin(executionId);
            return new Stage(executionStage, checkpointStage);
        } catch (RuntimeException | Error failure) {
            executionStage.close();
            throw failure;
        }
    }

    /** One aggregate execution scope containing every component stage. */
    public final class Stage implements AutoCloseable {
        private final StagedBlogeExecutionStore.Stage executionStage;
        private final StagedBlogeExecutionCheckpointStore.Stage checkpointStage;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean prepared = new AtomicBoolean();

        private Stage(StagedBlogeExecutionStore.Stage executionStage,
                      StagedBlogeExecutionCheckpointStore.Stage checkpointStage) {
            this.executionStage = executionStage;
            this.checkpointStage = checkpointStage;
        }

        /**
         * Freezes every component store and computes one formal engine-state closure.
         *
         * @param checkpointRef stable control-plane checkpoint reference
         * @param nodeId boundary node identifier
         * @param boundaryType supported durable boundary type
         * @param boundarySequence positive monotonic boundary sequence
         * @param stateVersion non-negative engine state version
         * @return one idempotently replayable composite transaction mutation
         */
        public PreparedMutation prepare(String checkpointRef,
                                        String nodeId,
                                        String boundaryType,
                                        long boundarySequence,
                                        long stateVersion) {
            requireOpen();
            if (!prepared.compareAndSet(false, true)) {
                throw new DurabilityException("Composite BLOGE state stage is already prepared");
            }
            StagedBlogeExecutionStore.PreparedMutation execution = executionStage.prepare(
                    checkpointRef, nodeId, boundaryType, boundarySequence, stateVersion);
            StagedBlogeExecutionCheckpointStore.PreparedMutation checkpoints = checkpointStage.prepare(
                    checkpointRef, nodeId, boundaryType, boundarySequence, stateVersion);
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", CLOSURE_SCHEMA_VERSION);
            material.put("engineExecutionId", execution.engineExecutionId());
            material.put("execution", execution.engineState().closureFingerprint());
            material.put("checkpoints", checkpoints.engineState().closureFingerprint());
            String fingerprint = ProtocolFingerprint.of(objectMapper, material);
            DurableTestExecutionCheckpoint.EngineState engineState =
                    new DurableTestExecutionCheckpoint.EngineState(
                            checkpointRef, nodeId, boundaryType, boundarySequence,
                            stateVersion, fingerprint);
            return new PreparedMutation(execution, checkpoints, engineState, closed);
        }

        /** Closes every component stage and discards all uncommitted state. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    checkpointStage.close();
                } finally {
                    executionStage.close();
                }
            }
        }

        private void requireOpen() {
            if (closed.get()) {
                throw new DurabilityException("Composite BLOGE state stage is closed");
            }
        }
    }

    /** Atomic execution plus checkpoint mutation bound to one aggregate fingerprint. */
    public static final class PreparedMutation
            implements DurableTestExecutionCheckpointRepository.BoundEngineStateMutation {
        private final DurableTestExecutionCheckpointRepository.BoundEngineStateMutation execution;
        private final DurableTestExecutionCheckpointRepository.BoundEngineStateMutation checkpoints;
        private final DurableTestExecutionCheckpoint.EngineState engineState;
        private final AtomicBoolean ownerClosed;

        private PreparedMutation(
                DurableTestExecutionCheckpointRepository.BoundEngineStateMutation execution,
                DurableTestExecutionCheckpointRepository.BoundEngineStateMutation checkpoints,
                DurableTestExecutionCheckpoint.EngineState engineState,
                AtomicBoolean ownerClosed) {
            this.execution = execution;
            this.checkpoints = checkpoints;
            this.engineState = engineState;
            this.ownerClosed = ownerClosed;
            if (!Objects.equals(execution.engineExecutionId(), checkpoints.engineExecutionId())) {
                throw new DurabilityException("Composite BLOGE state spans multiple executions");
            }
        }

        @Override
        public String engineExecutionId() {
            return execution.engineExecutionId();
        }

        @Override
        public DurableTestExecutionCheckpoint.EngineState engineState() {
            return engineState;
        }

        /** Applies every frozen store snapshot through the repository's current transaction. */
        @Override
        public void apply(JdbcTemplate jdbc) {
            if (ownerClosed.get()) {
                throw new DurabilityException(
                        "Prepared composite BLOGE state mutation belongs to a closed stage");
            }
            execution.apply(jdbc);
            checkpoints.apply(jdbc);
        }
    }
}
