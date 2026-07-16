package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.engine.ExecutionOptions;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryAuthorizer;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryTerminalReceipt;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableTestTerminalRecoveryRuntimeTest {

    @Test
    void retainsPreparedStageUntilRepositoryConsumerClosesIt() throws Exception {
        IndependentDurableTestEngineFactory factory = mock(
                IndependentDurableTestEngineFactory.class);
        CompiledTestRuntimeOptions runtimeOptions = mock(CompiledTestRuntimeOptions.class);
        IndependentDurableTestEngineFactory.RecoverySession session = mock(
                IndependentDurableTestEngineFactory.RecoverySession.class);
        DurableTestExecutionCheckpoint checkpoint = mock(DurableTestExecutionCheckpoint.class);
        DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized = authorized();
        ExecutionOptions options = ExecutionOptions.builder().build();
        FixtureConsumptionStateSnapshot fixture = mock(FixtureConsumptionStateSnapshot.class);
        com.leanowtech.bloge.gateway.testing.persistence.StagedBlogeDurableStateStore
                .PreparedMutation mutation = mock(
                com.leanowtech.bloge.gateway.testing.persistence.StagedBlogeDurableStateStore
                        .PreparedMutation.class);
        DurableTestExecutionCheckpoint.EngineState engineState =
                new DurableTestExecutionCheckpoint.EngineState(
                        "terminal:checkpoint", "wait", "NODE_BOUNDARY", 4, 4,
                        "sha256:" + "a".repeat(64));
        when(mutation.engineState()).thenReturn(engineState);
        when(runtimeOptions.options(eq(authorized.control()), any())).thenReturn(options);
        when(factory.openRecoverySession(eq(checkpoint), any(), eq(options))).thenReturn(session);
        when(session.signalAndAwait(authorized.graph(), "wait", "approved")).thenReturn(
                new IndependentDurableTestEngineFactory.RecoveryBoundary(
                        ExecutionStatus.COMPLETED, "wait", "NODE_BOUNDARY", 4));
        IndependentDurableTestEngineFactory.PreparedRecovery recovery =
                new IndependentDurableTestEngineFactory.PreparedRecovery(
                        mutation, fixture,
                        new IndependentDurableTestEngineFactory.RecoveryBoundary(
                                ExecutionStatus.COMPLETED, "wait", "NODE_BOUNDARY", 4));
        when(session.prepare(any())).thenReturn(recovery);
        ExecutionServiceStateSnapshot services = mock(ExecutionServiceStateSnapshot.class);
        when(authorized.control().executionServices().snapshotState()).thenReturn(services);
        DurableTestTerminalRecoveryRuntime runtime = new DurableTestTerminalRecoveryRuntime(
                factory, runtimeOptions, new ObjectMapper().findAndRegisterModules());

        DurableTestTerminalRecoveryRuntime.PreparedTerminalRecovery prepared = runtime.prepare(
                checkpoint, authorized, "wait", "approved", "terminal:checkpoint");

        assertThat(prepared.executionOutcome())
                .isEqualTo(DurableTestRecoveryTerminalReceipt.ExecutionOutcome.COMPLETED);
        assertThat(prepared.fixtureConsumptionState()).isSameAs(fixture);
        assertThat(prepared.executionServiceState()).isSameAs(services);
        prepared.close();
        verify(session).close();
    }

    @Test
    void discardsStageWhenSignalStopsAtAnotherSuspension() {
        IndependentDurableTestEngineFactory factory = mock(
                IndependentDurableTestEngineFactory.class);
        CompiledTestRuntimeOptions runtimeOptions = mock(CompiledTestRuntimeOptions.class);
        IndependentDurableTestEngineFactory.RecoverySession session = mock(
                IndependentDurableTestEngineFactory.RecoverySession.class);
        DurableTestExecutionCheckpoint checkpoint = mock(DurableTestExecutionCheckpoint.class);
        DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized = authorized();
        ExecutionOptions options = ExecutionOptions.builder().build();
        when(runtimeOptions.options(eq(authorized.control()), any())).thenReturn(options);
        when(factory.openRecoverySession(eq(checkpoint), any(), eq(options))).thenReturn(session);
        when(session.signalAndAwait(authorized.graph(), "wait", "approved")).thenReturn(
                new IndependentDurableTestEngineFactory.RecoveryBoundary(
                        ExecutionStatus.SUSPENDED, "wait-again", "SUSPEND", 4));
        DurableTestTerminalRecoveryRuntime runtime = new DurableTestTerminalRecoveryRuntime(
                factory, runtimeOptions, new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> runtime.prepare(
                checkpoint, authorized, "wait", "approved", "terminal:checkpoint"))
                .isInstanceOf(DurableTestTerminalRecoveryRuntime
                        .NonTerminalBoundaryException.class);
        verify(session).close();
    }

    private static DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized() {
        Graph graph = mock(Graph.class);
        CompiledExecutionControl control = mock(CompiledExecutionControl.class);
        when(control.executionServices()).thenReturn(mock(GovernedExecutionServices.class));
        return new DurableTestRecoveryAuthorizer.AuthorizedRecovery(
                graph, control,
                mock(com.leanowtech.bloge.gateway.testing.domain
                        .DurableTestRecoveryAuthorization.class));
    }
}
