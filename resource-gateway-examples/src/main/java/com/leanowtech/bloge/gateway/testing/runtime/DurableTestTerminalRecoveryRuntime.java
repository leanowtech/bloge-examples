package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryAuthorizer;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryTerminalReceipt;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prepares one server-owned cold-signal recovery at a stable suspended or terminal boundary. */
public class DurableTestTerminalRecoveryRuntime {

    private final IndependentDurableTestEngineFactory engineFactory;
    private final CompiledTestRuntimeOptions runtimeOptions;
    private final ObjectMapper objectMapper;

    /**
     * Creates the terminal-only recovery runtime.
     *
     * @param engineFactory isolated staged durable engine factory
     * @param runtimeOptions shared compiled fixture/operator runtime
     * @param objectMapper fixture cursor mapper
     */
    public DurableTestTerminalRecoveryRuntime(
            IndependentDurableTestEngineFactory engineFactory,
            CompiledTestRuntimeOptions runtimeOptions,
            ObjectMapper objectMapper) {
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        this.runtimeOptions = Objects.requireNonNull(runtimeOptions, "runtimeOptions");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Runs one signal synchronously and retains its staged state until the caller commits or closes.
     *
     * @param checkpoint exact live checkpoint owned by the source dispatch
     * @param authorized freshly reconstructed executable authorization closure
     * @param signalNodeId exact suspended signal node
     * @param signalData caller signal value, retained only in the in-memory engine invocation
     * @param checkpointRef server-derived final engine checkpoint reference
     * @return prepared terminal state whose close discards any uncommitted overlay
     */
    public PreparedTerminalRecovery prepare(
            DurableTestExecutionCheckpoint checkpoint,
            DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized,
            String signalNodeId,
            Object signalData,
            String checkpointRef) {
        PreparedRecoveryStep step = prepareStep(
                checkpoint, authorized, signalNodeId, signalData, checkpointRef);
        if (!step.outcome().terminal()) {
            step.close();
            throw new NonTerminalBoundaryException();
        }
        return new PreparedTerminalRecovery(
                step.engineStateMutation(), step.fixtureConsumptionState(),
                step.executionServiceState(), step.outcome().terminalOutcome(), step);
    }

    /**
     * Runs one signal synchronously and accepts either one new signal suspension or a terminal
     * BLOGE lifecycle.
     *
     * <p>The returned object owns the staged engine session. Its mutation must be applied in the
     * same transaction as the next fenced control checkpoint; closing it first discards the entire
     * speculative recovery step.</p>
     *
     * @param checkpoint exact live checkpoint owned by the source dispatch
     * @param authorized freshly reconstructed executable authorization closure
     * @param signalNodeId exact currently suspended signal node
     * @param signalData caller signal value retained only by the in-memory invocation
     * @param checkpointRef server-derived next checkpoint reference
     * @return prepared suspended or terminal recovery step
     */
    public PreparedRecoveryStep prepareStep(
            DurableTestExecutionCheckpoint checkpoint,
            DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized,
            String signalNodeId,
            Object signalData,
            String checkpointRef) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        DurableTestRecoveryAuthorizer.AuthorizedRecovery requiredAuthorization =
                Objects.requireNonNull(authorized, "authorized");
        InvocationRecorder recorder = new InvocationRecorder(objectMapper);
        var options = runtimeOptions.options(requiredAuthorization.control(), recorder);
        IndependentDurableTestEngineFactory.RecoverySession session =
                engineFactory.openRecoverySession(checkpoint, recorder, options);
        try {
            IndependentDurableTestEngineFactory.RecoveryBoundary boundary =
                    session.signalAndAwait(requiredAuthorization.graph(), signalNodeId, signalData);
            DurableTestExecutionCheckpointRepository.RecoveryStepOutcome outcome = stepOutcome(
                    boundary.executionStatus());
            if (outcome == null) {
                throw new IllegalStateException(
                        "Recovery did not reach a supported stable lifecycle boundary");
            }
            IndependentDurableTestEngineFactory.PreparedRecovery prepared =
                    session.prepare(checkpointRef);
            ExecutionServiceStateSnapshot providerState =
                    requiredAuthorization.control().executionServices().snapshotState();
            return new PreparedRecoveryStep(
                    prepared.engineStateMutation(), prepared.fixtureConsumptionState(),
                    providerState, outcome, session);
        } catch (RuntimeException | Error failure) {
            session.close();
            throw failure;
        }
    }

    private static DurableTestExecutionCheckpointRepository.RecoveryStepOutcome stepOutcome(
            ExecutionStatus status) {
        return switch (status) {
            case SUSPENDED ->
                    DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.SUSPENDED;
            case COMPLETED ->
                    DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.COMPLETED;
            case FAILED -> DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.FAILED;
            case FAILED_RECOVERY ->
                    DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.FAILED_RECOVERY;
            case CANCELLED ->
                    DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.CANCELLED;
            case TERMINATED ->
                    DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.TERMINATED;
            default -> null;
        };
    }

    /** Signals that the terminal-only public protocol reached another suspension instead. */
    public static final class NonTerminalBoundaryException extends RuntimeException {
        /** Creates the stable terminal-policy failure without embedding business state. */
        public NonTerminalBoundaryException() {
            super("Recovery reached a non-terminal boundary");
        }
    }

    /**
     * Server-derived suspended or terminal closure retained with its staged engine session.
     *
     * <p>This is a transaction handoff, not a serializable worker payload. Closing it invalidates
     * the uncommitted four-store mutation.</p>
     */
    public static class PreparedRecoveryStep implements AutoCloseable {
        private final DurableTestExecutionCheckpointRepository.BoundEngineStateMutation mutation;
        private final FixtureConsumptionStateSnapshot fixtureState;
        private final ExecutionServiceStateSnapshot serviceState;
        private final DurableTestExecutionCheckpointRepository.RecoveryStepOutcome outcome;
        private final AutoCloseable session;
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * Creates one complete prepared recovery-step value.
         *
         * @param mutation exact staged BLOGE aggregate mutation
         * @param fixtureState cumulative fixture cursor at the stable boundary
         * @param serviceState deterministic provider state at the stable boundary
         * @param outcome suspended or terminal boundary outcome
         * @param session open staged session owning the mutation
         */
        public PreparedRecoveryStep(
                DurableTestExecutionCheckpointRepository.BoundEngineStateMutation mutation,
                FixtureConsumptionStateSnapshot fixtureState,
                ExecutionServiceStateSnapshot serviceState,
                DurableTestExecutionCheckpointRepository.RecoveryStepOutcome outcome,
                AutoCloseable session) {
            this.mutation = Objects.requireNonNull(mutation, "mutation");
            this.fixtureState = Objects.requireNonNull(fixtureState, "fixtureState");
            this.serviceState = Objects.requireNonNull(serviceState, "serviceState");
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.session = Objects.requireNonNull(session, "session");
        }

        /** @return exact staged BLOGE aggregate mutation */
        public DurableTestExecutionCheckpointRepository.BoundEngineStateMutation
                engineStateMutation() {
            return mutation;
        }

        /** @return cumulative payload-free fixture cursor */
        public FixtureConsumptionStateSnapshot fixtureConsumptionState() {
            return fixtureState;
        }

        /** @return deterministic provider state at the next boundary */
        public ExecutionServiceStateSnapshot executionServiceState() {
            return serviceState;
        }

        /** @return suspended or terminal recovery-step outcome */
        public DurableTestExecutionCheckpointRepository.RecoveryStepOutcome outcome() {
            return outcome;
        }

        /** Releases the isolated engine and invalidates any uncommitted staged mutation. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    session.close();
                } catch (RuntimeException runtime) {
                    throw runtime;
                } catch (Exception checked) {
                    throw new IllegalStateException(
                            "Durable recovery-step session could not close", checked);
                }
            }
        }
    }

    /**
     * Server-derived terminal closure retained with its open staged session.
     *
     * <p>The object must remain open while the repository applies its mutation in the same local
     * transaction. Closing it discards any overlay not consumed by a successful commit.</p>
     */
    public static class PreparedTerminalRecovery implements AutoCloseable {
        private final DurableTestExecutionCheckpointRepository.BoundEngineStateMutation mutation;
        private final FixtureConsumptionStateSnapshot fixtureState;
        private final ExecutionServiceStateSnapshot serviceState;
        private final DurableTestRecoveryTerminalReceipt.ExecutionOutcome outcome;
        private final AutoCloseable session;
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * Creates a complete prepared terminal value.
         *
         * @param mutation exact staged BLOGE aggregate mutation
         * @param fixtureState cumulative fixture cursor after recovery
         * @param serviceState deterministic provider state after recovery
         * @param outcome terminal BLOGE lifecycle outcome
         * @param session open staged session owning the mutation
         */
        public PreparedTerminalRecovery(
                DurableTestExecutionCheckpointRepository.BoundEngineStateMutation mutation,
                FixtureConsumptionStateSnapshot fixtureState,
                ExecutionServiceStateSnapshot serviceState,
                DurableTestRecoveryTerminalReceipt.ExecutionOutcome outcome,
                AutoCloseable session) {
            this.mutation = Objects.requireNonNull(mutation, "mutation");
            this.fixtureState = Objects.requireNonNull(fixtureState, "fixtureState");
            this.serviceState = Objects.requireNonNull(serviceState, "serviceState");
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.session = Objects.requireNonNull(session, "session");
        }

        /**
         * Returns the exact staged BLOGE aggregate mutation.
         *
         * @return exact staged BLOGE aggregate mutation
         */
        public DurableTestExecutionCheckpointRepository.BoundEngineStateMutation
                engineStateMutation() {
            return mutation;
        }

        /**
         * Returns the cumulative fixture cursor after recovery.
         *
         * @return cumulative payload-free fixture cursor
         */
        public FixtureConsumptionStateSnapshot fixtureConsumptionState() {
            return fixtureState;
        }

        /**
         * Returns the final deterministic-provider state.
         *
         * @return final deterministic-provider state
         */
        public ExecutionServiceStateSnapshot executionServiceState() {
            return serviceState;
        }

        /**
         * Returns the normalized terminal engine outcome.
         *
         * @return normalized terminal engine outcome
         */
        public DurableTestRecoveryTerminalReceipt.ExecutionOutcome executionOutcome() {
            return outcome;
        }

        /** Releases the isolated engine and invalidates any uncommitted staged mutation. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    session.close();
                } catch (RuntimeException runtime) {
                    throw runtime;
                } catch (Exception checked) {
                    throw new IllegalStateException(
                            "Durable terminal recovery session could not close", checked);
                }
            }
        }
    }
}
