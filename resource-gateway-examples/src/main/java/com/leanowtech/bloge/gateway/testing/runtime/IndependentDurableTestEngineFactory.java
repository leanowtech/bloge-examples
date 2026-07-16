package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.CheckpointFailurePolicy;
import com.leanowtech.bloge.core.engine.ExecutionOptions;
import com.leanowtech.bloge.core.engine.ExecutionServices;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.wait.ExecutionWait;
import com.leanowtech.bloge.core.runtime.wait.WaitStatus;
import com.leanowtech.bloge.core.runtime.wait.WaitType;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.persistence.StagedBlogeDurableStateStore;
import com.leanowtech.bloge.runtime.engine.DurableGraphEngine;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Constructs an isolated durable test engine with mandatory fail-closed checkpoint semantics.
 *
 * <p>This factory is separate from {@link IndependentTestEngineFactory}: normal synchronous tests
 * remain short-lived and store-free, while a future suspend/resume path must explicitly select this
 * factory and open a transaction-participating durable-state stage before execution. Production
 * interceptors, listeners, context carriers, extension listeners, and durable stores are never
 * copied from the application engine.</p>
 */
public final class IndependentDurableTestEngineFactory {

    private final OperatorRegistry registry;
    private final CheckpointCodec checkpointCodec;
    private final StagedBlogeDurableStateStore durableStateStore;

    /**
     * Creates a factory over an isolated operator registry and composite durable-state authority.
     *
     * @param registry frozen operator bindings shared by identity
     * @param checkpointCodec durable checkpoint payload codec
     * @param durableStateStore isolated transaction-participating BLOGE state aggregate
     */
    public IndependentDurableTestEngineFactory(
            OperatorRegistry registry,
            CheckpointCodec checkpointCodec,
            StagedBlogeDurableStateStore durableStateStore) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.checkpointCodec = Objects.requireNonNull(checkpointCodec, "checkpointCodec");
        this.durableStateStore = Objects.requireNonNull(durableStateStore, "durableStateStore");
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
                .executionStore(durableStateStore.executionStore())
                .executionCheckpointStore(durableStateStore.checkpointStore())
                .waitStore(durableStateStore.waitStore())
                .workItemStore(durableStateStore.workItemStore())
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
     * <p>The session owns the local durable-state stage and engine lifecycle. The supplied options
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
        StagedBlogeDurableStateStore.Stage stage = durableStateStore.begin(
                requiredExecutionId, requiredServices.timeSource());
        try {
            DurableGraphEngine engine = create(recorder, requiredServices.timeSource());
            return new RunSession(requiredExecutionId, requiredOptions, engine, stage);
        } catch (RuntimeException | Error failure) {
            stage.close();
            throw failure;
        }
    }

    /**
     * Opens a cold-signal recovery session for an already claimed durable test execution.
     *
     * <p>This entry point is intentionally narrower than BLOGE's general {@code resume}. It accepts
     * only a v2 control checkpoint in {@code RESUMING}, restores the payload-free fixture cursor
     * into an empty recorder, and opens the complete staged BLOGE aggregate. The caller must supply
     * execution options rebuilt from the exact authorized plan and provider-state snapshot. No
     * engine write becomes durable until {@link RecoverySession#prepare(String)} participates in a
     * fenced control-checkpoint advance. Recovery execution is synchronous so closing the stage
     * cannot leave a detached engine thread mutating discarded state.</p>
     *
     * @param claimedCheckpoint integrity-verified checkpoint owned by the recovery process
     * @param recorder empty run-scoped recorder that will continue persisted fixture cursors
     * @param executionOptions exact re-authorized operator and execution-service controls
     * @return one cold-signal session that must be closed after commit or rollback
     */
    public RecoverySession openRecoverySession(
            DurableTestExecutionCheckpoint claimedCheckpoint,
            InvocationRecorder recorder,
            ExecutionOptions executionOptions) {
        DurableTestExecutionCheckpoint checkpoint = Objects.requireNonNull(
                claimedCheckpoint, "claimedCheckpoint");
        if (!DurableTestExecutionCheckpoint.SCHEMA_VERSION.equals(checkpoint.schemaVersion())
                || checkpoint.lifecycle().status()
                != DurableTestExecutionCheckpoint.Status.RESUMING) {
            throw new IllegalArgumentException(
                    "Cold-signal recovery requires a v2 RESUMING checkpoint");
        }
        if (checkpoint.dependencies().target() == null
                || !checkpoint.executionServiceState().restorable()) {
            throw new IllegalArgumentException(
                    "Cold-signal recovery requires an exact restorable dependency closure");
        }
        if (!"SUSPEND".equals(checkpoint.engineState().boundaryType())) {
            throw new IllegalStateException(
                    "Cold-signal recovery requires a suspend boundary");
        }
        InvocationRecorder requiredRecorder = Objects.requireNonNull(recorder, "recorder");
        ExecutionOptions requiredOptions = Objects.requireNonNull(
                executionOptions, "executionOptions");
        ExecutionServices requiredServices = Objects.requireNonNull(
                requiredOptions.executionServices(), "executionOptions.executionServices");
        StagedBlogeDurableStateStore.Stage stage = durableStateStore.begin(
                checkpoint.engineExecutionId(), requiredServices.timeSource());
        try {
            ExecutionInstance execution = durableStateStore.executionStore()
                    .get(checkpoint.engineExecutionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Claimed BLOGE execution state is unavailable"));
            if (execution.status() != ExecutionStatus.SUSPENDED) {
                throw new IllegalStateException(
                        "Cold-signal recovery requires a suspended BLOGE execution");
            }
            if (execution.version() != checkpoint.engineState().stateVersion()) {
                throw new IllegalStateException(
                        "Cold-signal recovery engine version does not match its control checkpoint");
            }
            requiredRecorder.restoreFixtureState(checkpoint.fixtureConsumptionState());
            DurableGraphEngine engine = create(
                    requiredRecorder, requiredServices.timeSource());
            return new RecoverySession(checkpoint, requiredRecorder, requiredOptions,
                    engine, stage, durableStateStore, execution.version());
        } catch (RuntimeException | Error failure) {
            stage.close();
            throw failure;
        }
    }

    /**
     * Describes the isolation and checkpoint policy enforced by every engine from this factory.
     *
     * @return immutable isolation and failure-policy facts for architecture tests and probes
     */
    public Configuration configuration() {
        return new Configuration(CheckpointFailurePolicy.FAIL_FAST, true, false, false);
    }

    /**
     * Immutable facts exposed to architecture tests and capability probes.
     *
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

    /** One caller-controlled BLOGE execution and its uncommitted durable-state overlay. */
    public static final class RunSession implements AutoCloseable {
        private final String executionId;
        private final DurableGraphEngine engine;
        private final StagedBlogeDurableStateStore.Stage stage;
        private final ExecutionOptions options;
        private final AtomicBoolean executed = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private RunSession(String executionId,
                           ExecutionOptions sourceOptions,
                           DurableGraphEngine engine,
                           StagedBlogeDurableStateStore.Stage stage) {
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
         * @param checkpointRef stable control-plane checkpoint reference
         * @param nodeId boundary node identifier
         * @param boundaryType supported durable boundary type
         * @param boundarySequence positive monotonic boundary sequence
         * @param stateVersion non-negative engine state version
         * @return idempotently replayable aggregate mutation and closure fingerprint
         * @see StagedBlogeDurableStateStore.Stage#prepare(String, String, String, long, long)
         */
        public StagedBlogeDurableStateStore.PreparedMutation prepare(
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

    /**
     * Stable BLOGE boundary reached after one cold-start signal.
     *
     * @param executionStatus persisted BLOGE lifecycle observed at the boundary
     * @param nodeId signalled node for terminal outcomes or sole newly suspended node
     * @param boundaryType control-checkpoint boundary category
     * @param stateVersion persisted BLOGE execution version used as the control monotonic counter
     */
    public record RecoveryBoundary(
            ExecutionStatus executionStatus,
            String nodeId,
            String boundaryType,
            long stateVersion) {
        /** Rejects ambiguous or non-restorable recovery boundaries. */
        public RecoveryBoundary {
            executionStatus = Objects.requireNonNull(executionStatus, "executionStatus");
            nodeId = required(nodeId, "nodeId");
            boundaryType = required(boundaryType, "boundaryType");
            if (stateVersion < 0) {
                throw new IllegalArgumentException("stateVersion must be non-negative");
            }
        }
    }

    /**
     * Frozen recovery writes and the payload-free fixture ledger captured at the same boundary.
     *
     * @param engineStateMutation complete BLOGE aggregate mutation for repository participation
     * @param fixtureConsumptionState cumulative fixture cursor after recovery execution
     * @param boundary stable engine lifecycle boundary represented by the mutation
     */
    public record PreparedRecovery(
            StagedBlogeDurableStateStore.PreparedMutation engineStateMutation,
            FixtureConsumptionStateSnapshot fixtureConsumptionState,
            RecoveryBoundary boundary) {
        /** Requires a complete, mutually consistent prepared recovery value. */
        public PreparedRecovery {
            engineStateMutation = Objects.requireNonNull(
                    engineStateMutation, "engineStateMutation");
            fixtureConsumptionState = Objects.requireNonNull(
                    fixtureConsumptionState, "fixtureConsumptionState");
            boundary = Objects.requireNonNull(boundary, "boundary");
            DurableTestExecutionCheckpoint.EngineState state =
                    engineStateMutation.engineState();
            if (!state.nodeId().equals(boundary.nodeId())
                    || !state.boundaryType().equals(boundary.boundaryType())
                    || state.stateVersion() != boundary.stateVersion()) {
                throw new IllegalArgumentException(
                        "Prepared BLOGE state does not match its recovery boundary");
            }
        }
    }

    /**
     * One-use cold-signal executor over an uncommitted BLOGE aggregate.
     *
     * <p>The session invokes BLOGE's synchronous cold-start recovery API. Success means only that
     * BLOGE reached a terminal state or one new, unambiguous signal suspension before control
     * returns. Persistence still requires a repository CAS with the claimed control fence. A hard
     * wall-clock deadline belongs at a cancellable worker-process boundary; this in-process session
     * deliberately does not claim cancellation it cannot enforce.</p>
     */
    public static final class RecoverySession implements AutoCloseable {
        private final DurableTestExecutionCheckpoint claimedCheckpoint;
        private final InvocationRecorder recorder;
        private final ExecutionOptions options;
        private final DurableGraphEngine engine;
        private final StagedBlogeDurableStateStore.Stage stage;
        private final StagedBlogeDurableStateStore store;
        private final long initialExecutionVersion;
        private final AtomicBoolean signalled = new AtomicBoolean();
        private final AtomicBoolean prepared = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile RecoveryBoundary boundary;

        private RecoverySession(
                DurableTestExecutionCheckpoint claimedCheckpoint,
                InvocationRecorder recorder,
                ExecutionOptions options,
                DurableGraphEngine engine,
                StagedBlogeDurableStateStore.Stage stage,
                StagedBlogeDurableStateStore store,
                long initialExecutionVersion) {
            this.claimedCheckpoint = claimedCheckpoint;
            this.recorder = recorder;
            this.options = options;
            this.engine = engine;
            this.stage = stage;
            this.store = store;
            this.initialExecutionVersion = initialExecutionVersion;
        }

        /**
         * Applies one external signal synchronously and verifies the next transaction-safe boundary.
         *
         * @param graph exact graph re-authorized against the claimed target fingerprint
         * @param nodeId currently suspended signal node
         * @param signalData business signal payload; never copied into the control checkpoint
         * @return terminal or newly suspended BLOGE boundary
         */
        public RecoveryBoundary signalAndAwait(
                Graph graph, String nodeId, Object signalData) {
            requireOpen();
            Graph requiredGraph = Objects.requireNonNull(graph, "graph");
            String requiredNodeId = required(nodeId, "nodeId");
            if (!claimedCheckpoint.engineState().nodeId().equals(requiredNodeId)) {
                throw new IllegalArgumentException(
                        "Signal node does not match the claimed recovery boundary node");
            }
            if (!signalled.compareAndSet(false, true)) {
                throw new IllegalStateException("A recovery session can signal only once");
            }
            requireWaitingSignal(requiredNodeId);
            GraphResult result = engine.resumeSuspended(
                    requiredGraph, claimedCheckpoint.engineExecutionId(),
                    requiredNodeId, signalData, options);
            if (!claimedCheckpoint.engineExecutionId().equals(result.executionId())) {
                throw new IllegalStateException(
                        "BLOGE returned an unexpected recovered execution identity");
            }
            boundary = boundaryAfterSignal(requiredNodeId);
            return boundary;
        }

        /**
         * Freezes the engine aggregate and cumulative fixture cursor for a fenced repository CAS.
         *
         * @param checkpointRef stable content-addressed reference for the next control checkpoint
         * @return mutation, fixture cursor, and verified engine boundary
         */
        public PreparedRecovery prepare(String checkpointRef) {
            requireOpen();
            RecoveryBoundary stableBoundary = boundary;
            if (stableBoundary == null) {
                throw new IllegalStateException(
                        "Recovery must reach a stable boundary before prepare");
            }
            if (!prepared.compareAndSet(false, true)) {
                throw new IllegalStateException("Recovery session is already prepared");
            }
            FixtureConsumptionStateSnapshot fixtureState = recorder.captureFixtureState();
            long nextBoundarySequence = Math.addExact(
                    claimedCheckpoint.engineState().boundarySequence(), 1);
            StagedBlogeDurableStateStore.PreparedMutation mutation = stage.prepare(
                    required(checkpointRef, "checkpointRef"), stableBoundary.nodeId(),
                    stableBoundary.boundaryType(), nextBoundarySequence,
                    stableBoundary.stateVersion());
            return new PreparedRecovery(mutation, fixtureState, stableBoundary);
        }

        /** Drops all uncommitted recovery writes and releases the isolated engine. */
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

        private RecoveryBoundary boundaryAfterSignal(String signalledNodeId) {
            ExecutionInstance execution = store.executionStore()
                    .get(claimedCheckpoint.engineExecutionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Recovered BLOGE execution state disappeared"));
            if (execution.version() <= initialExecutionVersion) {
                throw new IllegalStateException(
                        "Recovered BLOGE execution version did not advance");
            }
            if (terminal(execution.status())) {
                return checkedBoundary(execution, signalledNodeId, "NODE_BOUNDARY");
            }
            List<ExecutionWait> waitingSignals = waitingSignals();
            if (execution.status() == ExecutionStatus.SUSPENDED
                    && !waitingSignals.isEmpty()) {
                if (waitingSignals.size() != 1) {
                    throw new IllegalStateException(
                            "Cold-signal recovery reached multiple signal suspensions");
                }
                return checkedBoundary(
                        execution, waitingSignals.getFirst().nodeId(), "SUSPEND");
            }
            if (execution.status() == ExecutionStatus.PAUSED) {
                throw new IllegalStateException(
                        "Cold-signal recovery reached an unsupported paused boundary");
            }
            throw new IllegalStateException(
                    "Synchronous cold-signal recovery returned without a stable boundary");
        }

        private RecoveryBoundary checkedBoundary(
                ExecutionInstance execution, String nodeId, String boundaryType) {
            if (execution.version() <= claimedCheckpoint.engineState().stateVersion()) {
                throw new IllegalStateException(
                        "Recovered BLOGE execution version did not advance monotonically");
            }
            return new RecoveryBoundary(
                    execution.status(), nodeId, boundaryType, execution.version());
        }

        private void requireWaitingSignal(String nodeId) {
            long matches = waitingSignals().stream()
                    .filter(wait -> nodeId.equals(wait.nodeId()))
                    .count();
            if (matches != 1) {
                throw new IllegalStateException(
                        "Cold-signal recovery requires exactly one matching signal wait");
            }
        }

        private List<ExecutionWait> waitingSignals() {
            return store.waitStore().findByExecution(
                            claimedCheckpoint.engineExecutionId()).stream()
                    .filter(wait -> wait.status() == WaitStatus.WAITING)
                    .filter(wait -> wait.waitType() == WaitType.WAIT_SIGNAL)
                    .toList();
        }

        private void requireOpen() {
            if (closed.get()) {
                throw new IllegalStateException("Recovery session is closed");
            }
        }

        private static boolean terminal(ExecutionStatus status) {
            return status == ExecutionStatus.COMPLETED
                    || status == ExecutionStatus.FAILED
                    || status == ExecutionStatus.FAILED_RECOVERY
                    || status == ExecutionStatus.CANCELLED
                    || status == ExecutionStatus.TERMINATED;
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
