package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.exception.DurabilityException;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpointStore;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStore;
import com.leanowtech.bloge.core.runtime.wait.WaitStore;
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
 * concrete execution, checkpoint, and wait stores out of Spring's global bean registry, opens all
 * stages together, and emits one content-addressed {@link PreparedMutation}. That mutation applies
 * component snapshots through one repository-owned JDBC transaction, so a process cannot publish
 * a control checkpoint that refers to a missing execution row, node checkpoint, signal, or timer.</p>
 *
 * <p>The work-item store will join this same aggregate as its industrial implementation is added.
 * The aggregate fingerprint schema makes that extension explicit and prevents callers from
 * treating independently prepared component mutations as a complete engine closure.</p>
 */
public final class StagedBlogeDurableStateStore {

    private static final String CLOSURE_SCHEMA_VERSION = "bloge.testDurableStateMutation.v2";

    private final ObjectMapper objectMapper;
    private final StagedBlogeExecutionStore executionStore;
    private final StagedBlogeExecutionCheckpointStore checkpointStore;
    private final StagedBlogeWaitStore waitStore;

    /**
     * Creates the full durable-state aggregate over one test-runtime datasource.
     *
     * @param jdbc shared transaction-capable JDBC facade
     * @param objectMapper canonical mapper used by all stores and aggregate fingerprints
     */
    public StagedBlogeDurableStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.executionStore = new StagedBlogeExecutionStore(jdbc, objectMapper);
        this.checkpointStore = new StagedBlogeExecutionCheckpointStore(jdbc, objectMapper);
        this.waitStore = new StagedBlogeWaitStore(jdbc, objectMapper, executionStore);
    }

    /** Creates every table owned by the current durable-state aggregate version. */
    @PostConstruct
    public void init() {
        executionStore.init();
        checkpointStore.init();
        waitStore.init();
    }

    /**
     * Returns the lifecycle and lease store attached to the isolated durable engine.
     *
     * @return lifecycle and lease SPI
     */
    public ExecutionStore executionStore() {
        return executionStore;
    }

    /**
     * Returns the node and suspend checkpoint store attached to the isolated durable engine.
     *
     * @return node and suspend checkpoint SPI
     */
    public ExecutionCheckpointStore checkpointStore() {
        return checkpointStore;
    }

    /**
     * Returns the deferred wait store attached to the isolated durable engine.
     *
     * @return signal, timer, task, and retry wait SPI
     */
    public WaitStore waitStore() {
        return waitStore;
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
            try {
                StagedBlogeWaitStore.Stage waitStage = waitStore.begin(executionId, timeSource);
                return new Stage(executionStage, checkpointStage, waitStage);
            } catch (RuntimeException | Error failure) {
                checkpointStage.close();
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            executionStage.close();
            throw failure;
        }
    }

    /** One aggregate execution scope containing every component stage. */
    public final class Stage implements AutoCloseable {
        private final StagedBlogeExecutionStore.Stage executionStage;
        private final StagedBlogeExecutionCheckpointStore.Stage checkpointStage;
        private final StagedBlogeWaitStore.Stage waitStage;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean prepared = new AtomicBoolean();

        private Stage(StagedBlogeExecutionStore.Stage executionStage,
                      StagedBlogeExecutionCheckpointStore.Stage checkpointStage,
                      StagedBlogeWaitStore.Stage waitStage) {
            this.executionStage = executionStage;
            this.checkpointStage = checkpointStage;
            this.waitStage = waitStage;
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
            StagedBlogeWaitStore.PreparedMutation waits = waitStage.prepare(
                    checkpointRef, nodeId, boundaryType, boundarySequence, stateVersion);
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", CLOSURE_SCHEMA_VERSION);
            material.put("engineExecutionId", execution.engineExecutionId());
            material.put("execution", execution.engineState().closureFingerprint());
            material.put("checkpoints", checkpoints.engineState().closureFingerprint());
            material.put("waits", waits.engineState().closureFingerprint());
            String fingerprint = ProtocolFingerprint.of(objectMapper, material);
            DurableTestExecutionCheckpoint.EngineState engineState =
                    new DurableTestExecutionCheckpoint.EngineState(
                            checkpointRef, nodeId, boundaryType, boundarySequence,
                            stateVersion, fingerprint);
            return new PreparedMutation(execution, checkpoints, waits, engineState, closed);
        }

        /** Closes every component stage and discards all uncommitted state. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    waitStage.close();
                } finally {
                    try {
                        checkpointStage.close();
                    } finally {
                        executionStage.close();
                    }
                }
            }
        }

        private void requireOpen() {
            if (closed.get()) {
                throw new DurabilityException("Composite BLOGE state stage is closed");
            }
        }
    }

    /** Atomic execution, checkpoint, and wait mutation bound to one aggregate fingerprint. */
    public static final class PreparedMutation
            implements DurableTestExecutionCheckpointRepository.BoundEngineStateMutation {
        private final DurableTestExecutionCheckpointRepository.BoundEngineStateMutation execution;
        private final DurableTestExecutionCheckpointRepository.BoundEngineStateMutation checkpoints;
        private final DurableTestExecutionCheckpointRepository.BoundEngineStateMutation waits;
        private final DurableTestExecutionCheckpoint.EngineState engineState;
        private final AtomicBoolean ownerClosed;

        private PreparedMutation(
                DurableTestExecutionCheckpointRepository.BoundEngineStateMutation execution,
                DurableTestExecutionCheckpointRepository.BoundEngineStateMutation checkpoints,
                DurableTestExecutionCheckpointRepository.BoundEngineStateMutation waits,
                DurableTestExecutionCheckpoint.EngineState engineState,
                AtomicBoolean ownerClosed) {
            this.execution = execution;
            this.checkpoints = checkpoints;
            this.waits = waits;
            this.engineState = engineState;
            this.ownerClosed = ownerClosed;
            if (!Objects.equals(execution.engineExecutionId(), checkpoints.engineExecutionId())) {
                throw new DurabilityException("Composite BLOGE state spans multiple executions");
            }
            if (!Objects.equals(execution.engineExecutionId(), waits.engineExecutionId())) {
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
            waits.apply(jdbc);
        }
    }
}
