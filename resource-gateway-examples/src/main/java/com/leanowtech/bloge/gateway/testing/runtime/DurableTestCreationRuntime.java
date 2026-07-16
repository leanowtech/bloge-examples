package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryAuthorizer;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prepares one fresh graph execution at the only initial suspension supported by protocol v1. */
public final class DurableTestCreationRuntime {

    private final IndependentDurableTestEngineFactory engineFactory;
    private final CompiledTestRuntimeOptions runtimeOptions;
    private final ObjectMapper objectMapper;

    /**
     * Creates the fresh durable runtime over isolated staged BLOGE stores.
     *
     * @param engineFactory isolated staged durable engine factory
     * @param runtimeOptions exact compiled fixture/operator option adapter
     * @param objectMapper fixture cursor recorder mapper
     */
    public DurableTestCreationRuntime(
            IndependentDurableTestEngineFactory engineFactory,
            CompiledTestRuntimeOptions runtimeOptions,
            ObjectMapper objectMapper) {
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        this.runtimeOptions = Objects.requireNonNull(runtimeOptions, "runtimeOptions");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Executes to one stable boundary and retains its stage for the repository commit.
     *
     * @param engineExecutionId server-minted BLOGE execution identity
     * @param authorized exact frozen dependency and executable closure
     * @param context bounded business input retained only in the staged engine invocation
     * @param checkpointRef server-derived initial checkpoint reference
     * @return frozen provider, fixture, and engine closure owning an open staged session
     */
    public PreparedCreation prepare(
            String engineExecutionId,
            DurableTestRecoveryAuthorizer.AuthorizedCreation authorized,
            Map<String, Object> context,
            String checkpointRef) {
        DurableTestRecoveryAuthorizer.AuthorizedCreation requiredAuthorization =
                Objects.requireNonNull(authorized, "authorized");
        InvocationRecorder recorder = new InvocationRecorder(objectMapper);
        var options = runtimeOptions.options(requiredAuthorization.control(), recorder);
        IndependentDurableTestEngineFactory.RunSession session = engineFactory.openSession(
                engineExecutionId, recorder, options);
        try {
            session.execute(requiredAuthorization.graph(), new GraphContext(
                    context == null ? Map.of() : context));
            IndependentDurableTestEngineFactory.PreparedRun prepared =
                    session.prepareInitialSuspension(checkpointRef);
            ExecutionServiceStateSnapshot providerState =
                    requiredAuthorization.control().executionServices().snapshotState();
            return new PreparedCreation(
                    prepared.engineStateMutation(), prepared.fixtureConsumptionState(),
                    providerState, session);
        } catch (RuntimeException | Error failure) {
            session.close();
            throw failure;
        }
    }

    /** Frozen fresh-run closure retained with the open stage that owns its mutation. */
    public static final class PreparedCreation implements AutoCloseable {
        private final DurableTestExecutionCheckpointRepository.BoundEngineStateMutation mutation;
        private final FixtureConsumptionStateSnapshot fixtureState;
        private final ExecutionServiceStateSnapshot serviceState;
        private final AutoCloseable session;
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * Creates one mutually owned prepared creation closure.
         *
         * @param mutation complete staged BLOGE aggregate mutation
         * @param fixtureState payload-free fixture cursor at the initial boundary
         * @param serviceState payload-free deterministic-provider state at the same boundary
         * @param session open staged session owning the mutation
         */
        public PreparedCreation(
                DurableTestExecutionCheckpointRepository.BoundEngineStateMutation mutation,
                FixtureConsumptionStateSnapshot fixtureState,
                ExecutionServiceStateSnapshot serviceState,
                AutoCloseable session) {
            this.mutation = Objects.requireNonNull(mutation, "mutation");
            this.fixtureState = Objects.requireNonNull(fixtureState, "fixtureState");
            this.serviceState = Objects.requireNonNull(serviceState, "serviceState");
            this.session = Objects.requireNonNull(session, "session");
        }

        /**
         * Returns the staged transaction-participating BLOGE aggregate mutation.
         *
         * @return exact transaction-participating BLOGE aggregate mutation
         */
        public DurableTestExecutionCheckpointRepository.BoundEngineStateMutation
                engineStateMutation() {
            return mutation;
        }

        /**
         * Returns the fixture cursor frozen at the stable boundary.
         *
         * @return fixture cursor captured after all admitted work quiesced
         */
        public FixtureConsumptionStateSnapshot fixtureConsumptionState() {
            return fixtureState;
        }

        /**
         * Returns the deterministic-provider state frozen at the stable boundary.
         *
         * @return deterministic-provider state captured at the same boundary
         */
        public ExecutionServiceStateSnapshot executionServiceState() {
            return serviceState;
        }

        /** Releases the isolated engine and discards any uncommitted staged mutation. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    session.close();
                } catch (RuntimeException runtime) {
                    throw runtime;
                } catch (Exception checked) {
                    throw new IllegalStateException(
                            "Durable creation session could not close", checked);
                }
            }
        }
    }
}
