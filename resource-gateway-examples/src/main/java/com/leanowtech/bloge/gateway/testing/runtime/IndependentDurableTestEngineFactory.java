package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.CheckpointFailurePolicy;
import com.leanowtech.bloge.core.engine.ExecutionOptions;
import com.leanowtech.bloge.core.engine.ExecutionServices;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.gateway.testing.persistence.StagedBlogeExecutionCheckpointStore;
import com.leanowtech.bloge.runtime.engine.DurableGraphEngine;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Constructs an isolated durable test engine with mandatory fail-closed checkpoint semantics.
 *
 * <p>This factory is separate from {@link IndependentTestEngineFactory}: normal synchronous tests
 * remain short-lived and store-free, while a future suspend/resume path must explicitly select this
 * factory and open a transaction-participating checkpoint stage before execution. Production
 * interceptors, listeners, context carriers, extension listeners, and durable stores are never
 * copied from the application engine.</p>
 */
public final class IndependentDurableTestEngineFactory {

    private final OperatorRegistry registry;
    private final CheckpointCodec checkpointCodec;
    private final StagedBlogeExecutionCheckpointStore checkpointStore;

    /**
     * @param registry frozen operator bindings shared by identity
     * @param checkpointCodec durable checkpoint payload codec
     * @param checkpointStore isolated transaction-participating test checkpoint store
     */
    public IndependentDurableTestEngineFactory(
            OperatorRegistry registry,
            CheckpointCodec checkpointCodec,
            StagedBlogeExecutionCheckpointStore checkpointStore) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.checkpointCodec = Objects.requireNonNull(checkpointCodec, "checkpointCodec");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
    }

    /**
     * Creates a run-scoped durable facade with an optional deterministic time source.
     *
     * @param recorder run-scoped evidence listener
     * @param timeSource logical time source, or {@code null} for system time
     * @return isolated durable test engine
     */
    DurableGraphEngine create(InvocationRecorder recorder, TimeSource timeSource) {
        DurableGraphEngine.Builder builder = DurableGraphEngine.builder()
                .registry(registry)
                .checkpointCodec(checkpointCodec)
                .executionCheckpointStore(checkpointStore)
                .checkpointFailurePolicy(CheckpointFailurePolicy.FAIL_FAST)
                .interceptors(List.of())
                .listeners(List.of(Objects.requireNonNull(recorder, "recorder")))
                .extensionListeners(List.of())
                .contextCarriers(List.of());
        if (timeSource != null) {
            builder.timeSource(timeSource);
        }
        return builder.build();
    }

    /**
     * Opens one fail-closed durable execution session with a caller-assigned engine identity.
     *
     * <p>The session owns the local checkpoint stage and engine lifecycle. The supplied options
     * remain authoritative for operator fixture resolution, logical time, random values, built-in
     * UUIDs, identity, flags, secrets, and environment functions; only the first root execution ID
     * allocation is replaced with {@code executionId}. Subsequent ID requests still use the
     * supplied provider.</p>
     *
     * @param executionId trusted control-plane BLOGE execution identity
     * @param recorder run-scoped evidence and fixture-consumption ledger
     * @param executionOptions exact operator-resolution and deterministic-service closure
     * @return single-execution session that must be closed after composite commit or rollback
     */
    public RunSession openSession(String executionId,
                                  InvocationRecorder recorder,
                                  ExecutionOptions executionOptions) {
        String requiredExecutionId = required(executionId, "executionId");
        Objects.requireNonNull(recorder, "recorder");
        ExecutionOptions requiredOptions = Objects.requireNonNull(
                executionOptions, "executionOptions");
        ExecutionServices requiredServices = Objects.requireNonNull(
                requiredOptions.executionServices(), "executionOptions.executionServices");
        StagedBlogeExecutionCheckpointStore.Stage stage = checkpointStore.begin(requiredExecutionId);
        try {
            DurableGraphEngine engine = create(recorder, requiredServices.timeSource());
            return new RunSession(requiredExecutionId, requiredOptions, engine, stage);
        } catch (RuntimeException | Error failure) {
            stage.close();
            throw failure;
        }
    }

    /** @return immutable isolation and failure-policy facts for architecture tests and probes */
    public Configuration configuration() {
        return new Configuration(CheckpointFailurePolicy.FAIL_FAST, true, false, false);
    }

    /**
     * @param checkpointFailurePolicy configured checkpoint failure policy
     * @param durableStores whether isolated durable stores are attached
     * @param productionContextCarriers whether production ambient carriers are attached
     * @param productionExtensionListeners whether production extension listeners are attached
     */
    public record Configuration(CheckpointFailurePolicy checkpointFailurePolicy,
                                boolean durableStores,
                                boolean productionContextCarriers,
                                boolean productionExtensionListeners) {
    }

    /** One caller-controlled BLOGE execution and its uncommitted checkpoint overlay. */
    public static final class RunSession implements AutoCloseable {
        private final String executionId;
        private final DurableGraphEngine engine;
        private final StagedBlogeExecutionCheckpointStore.Stage stage;
        private final ExecutionOptions options;
        private final AtomicBoolean executed = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private RunSession(String executionId,
                           ExecutionOptions sourceOptions,
                           DurableGraphEngine engine,
                           StagedBlogeExecutionCheckpointStore.Stage stage) {
            this.executionId = executionId;
            this.engine = engine;
            this.stage = stage;
            ExecutionServices services = sourceOptions.executionServices();
            AtomicBoolean rootIdentityAllocated = new AtomicBoolean();
            ExecutionServices controlledServices = new ExecutionServices(
                    services.timeSource(), services.randomSource(), scope ->
                    rootIdentityAllocated.compareAndSet(false, true)
                            ? executionId : services.idGenerator().nextId(scope),
                    services.identityProvider(), services.featureFlagProvider(),
                    services.secretProvider(), services.expressionFunctionResolver());
            this.options = ExecutionOptions.builder()
                    .operatorResolver(sourceOptions.operatorResolver())
                    .executionServices(controlledServices)
                    .build();
        }

        /**
         * Executes exactly one root graph under the session's trusted execution identity.
         *
         * @param graph graph selected by the frozen effective plan
         * @param initialContext fresh business context for this attempt
         * @return BLOGE graph result carrying the assigned execution identity
         */
        public GraphResult execute(Graph graph, GraphContext initialContext) {
            requireOpen();
            if (!executed.compareAndSet(false, true)) {
                throw new IllegalStateException("A durable test session can execute only once");
            }
            GraphResult result = engine.execute(
                    Objects.requireNonNull(graph, "graph"), initialContext, options);
            if (!executionId.equals(result.executionId())) {
                throw new IllegalStateException("BLOGE returned an unexpected execution identity");
            }
            return result;
        }

        /**
         * Freezes the engine writes for the repository's composite transaction.
         *
         * @see StagedBlogeExecutionCheckpointStore.Stage#prepare(String, String, String, long, long)
         */
        public StagedBlogeExecutionCheckpointStore.PreparedMutation prepare(
                String checkpointRef,
                String nodeId,
                String boundaryType,
                long boundarySequence,
                long stateVersion) {
            requireOpen();
            if (!executed.get()) {
                throw new IllegalStateException(
                        "A durable test session must execute before its checkpoint is prepared");
            }
            return stage.prepare(checkpointRef, nodeId, boundaryType,
                    boundarySequence, stateVersion);
        }

        /** Shuts down the isolated engine and drops any uncommitted checkpoint overlay. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    engine.shutdown();
                } finally {
                    stage.close();
                }
            }
        }

        private void requireOpen() {
            if (closed.get()) {
                throw new IllegalStateException("Durable test session is closed");
            }
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
