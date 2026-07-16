package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryAuthorization;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryDispatch;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryTerminalReceipt;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestTerminalRecoveryRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DurableTestTerminalRecoveryServiceTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);

    @Mock
    private DurableTestExecutionCheckpointRepository checkpoints;
    @Mock
    private DurableTestRecoveryAuthorizer authorizer;
    @Mock
    private DurableTestTerminalRecoveryRuntime runtime;
    @Mock
    private TestSecurityEventRepository securityEvents;

    private ObjectMapper mapper;
    private DurableTestTerminalRecoveryService service;
    private TestRuntimeTransactionMutation audit;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        audit = jdbc -> { };
        service = new DurableTestTerminalRecoveryService(
                checkpoints, authorizer, runtime, securityEvents, mapper);
    }

    @Test
    void reauthorizesExecutesAndAtomicallyCommitsOnlyServerDerivedTerminalState()
            throws Exception {
        IntegrationRequestContext identity = identity();
        DurableTestExecutionCheckpoint current = checkpoint(
                DurableTestExecutionCheckpoint.Status.RESUMING, 4, 8, SHA_A);
        DurableTestRecoveryDispatch dispatch = dispatch(identity, current);
        DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized = authorized(dispatch);
        DurableTestTerminalRecoveryRuntime.PreparedTerminalRecovery prepared = prepared();
        DurableTestExecutionCheckpoint terminal = checkpoint(
                DurableTestExecutionCheckpoint.Status.TERMINAL, 4, 9, SHA_B);
        DurableTestRecoveryTerminalReceipt receipt = receipt(dispatch, terminal);
        DurableTestExecutionCheckpointRepository.RecoveryTerminalResult result =
                new DurableTestExecutionCheckpointRepository.RecoveryTerminalResult(
                        terminal, receipt, false);
        when(checkpoints.findRecoveryTerminalResult(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(checkpoints.findRecoveryDispatch(eq("tenant-a"), eq("test"), eq("run-a"),
                any(), eq(SHA_A))).thenReturn(Optional.of(dispatch));
        when(checkpoints.find("tenant-a", "test", "run-a"))
                .thenReturn(Optional.of(current));
        when(authorizer.authorize(current, identity)).thenReturn(authorized);
        org.mockito.Mockito.doReturn(audit).when(securityEvents).boundAppend(any());
        when(runtime.prepare(eq(current), eq(authorized), eq("wait"), any(), any()))
                .thenReturn(prepared);
        when(checkpoints.terminalizeRecoveryIdempotently(any(), any(), eq(audit)))
                .thenReturn(result);

        DurableTestTerminalRecoveryResponse response = service.recover(
                "run-a", request("terminal-1", dispatch), identity);

        assertThat(response).extracting(
                        DurableTestTerminalRecoveryResponse::runId,
                        DurableTestTerminalRecoveryResponse::status,
                        DurableTestTerminalRecoveryResponse::executionOutcome,
                        DurableTestTerminalRecoveryResponse::revision,
                        DurableTestTerminalRecoveryResponse::terminalCheckpointFingerprint,
                        DurableTestTerminalRecoveryResponse::terminalReceiptFingerprint,
                        DurableTestTerminalRecoveryResponse::evidenceStatus,
                        DurableTestTerminalRecoveryResponse::idempotentReplay)
                .containsExactly("run-a", "TERMINAL", "COMPLETED", 9L, SHA_B, SHA_C,
                        "EVIDENCE_INCOMPLETE", false);

        DurableTestExecutionCheckpointRepository.BoundEngineStateMutation expectedMutation =
                prepared.engineStateMutation();
        FixtureConsumptionStateSnapshot expectedFixture =
                prepared.fixtureConsumptionState();
        ExecutionServiceStateSnapshot expectedServices =
                prepared.executionServiceState();
        ArgumentCaptor<DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand> command =
                ArgumentCaptor.forClass(
                        DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand.class);
        verify(checkpoints).terminalizeRecoveryIdempotently(
                command.capture(), eq(expectedMutation), eq(audit));
        assertThat(command.getValue().expectedDispatch()).isSameAs(dispatch);
        assertThat(command.getValue().executionOutcome())
                .isEqualTo(DurableTestRecoveryTerminalReceipt.ExecutionOutcome.COMPLETED);
        assertThat(command.getValue().fixtureConsumptionState())
                .isSameAs(expectedFixture);
        assertThat(command.getValue().executionServiceState())
                .isSameAs(expectedServices);
        assertThat(command.getValue().evidenceGapCodes()).containsExactly(
                "PRE_CHECKPOINT_TRACE_UNAVAILABLE", "RECOVERY_SIGNAL_PAYLOAD_OMITTED");
        verify(prepared).close();
    }

    @Test
    void responseLossReplayReturnsBeforeDispatchLookupReauthorizationOrEngineExecution() {
        DurableTestRecoveryDispatch dispatch = dispatch(identity(), checkpoint(
                DurableTestExecutionCheckpoint.Status.RESUMING, 4, 8, SHA_A));
        DurableTestExecutionCheckpoint terminal = checkpoint(
                DurableTestExecutionCheckpoint.Status.TERMINAL, 4, 9, SHA_B);
        DurableTestExecutionCheckpointRepository.RecoveryTerminalResult replay =
                new DurableTestExecutionCheckpointRepository.RecoveryTerminalResult(
                        terminal, receipt(dispatch, terminal), true);
        when(checkpoints.findRecoveryTerminalResult(
                eq("tenant-a"), eq("test"), eq("terminal-1"), any()))
                .thenReturn(Optional.of(replay));
        when(securityEvents.append(any())).thenAnswer(call -> call.getArgument(0));

        DurableTestTerminalRecoveryResponse response = service.recover(
                "run-a", request("terminal-1", dispatch), identity());

        assertThat(response.idempotentReplay()).isTrue();
        verify(checkpoints, never()).findRecoveryDispatch(any(), any(), any(), any(), any());
        verifyNoInteractions(authorizer, runtime);
        verify(securityEvents, never()).boundAppend(any());
        verify(securityEvents).append(any());
    }

    @Test
    void rejectsPrincipalOrReauthorizationDriftBeforeEngineExecution() {
        DurableTestExecutionCheckpoint current = checkpoint(
                DurableTestExecutionCheckpoint.Status.RESUMING, 4, 8, SHA_A);
        DurableTestRecoveryDispatch dispatch = dispatch(identity(), current);
        when(checkpoints.findRecoveryTerminalResult(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(checkpoints.findRecoveryDispatch(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(dispatch));

        assertProblem(() -> service.recover(
                        "run-a", request("terminal-1", dispatch), identity("other-worker")),
                403, "RG.TEST.DURABLE_RECOVERY_PRINCIPAL_MISMATCH");

        when(checkpoints.find("tenant-a", "test", "run-a"))
                .thenReturn(Optional.of(current));
        DurableTestRecoveryAuthorizer.AuthorizedRecovery drifted = authorized(dispatch);
        when(drifted.authorization()).thenReturn(mock(DurableTestRecoveryAuthorization.class));
        when(authorizer.authorize(current, identity())).thenReturn(drifted);
        assertProblem(() -> service.recover(
                        "run-a", request("terminal-2", dispatch), identity()),
                409, "RG.TEST.DURABLE_RECOVERY_AUTHORIZATION_DRIFT");

        verifyNoInteractions(runtime);
    }

    @Test
    void rejectsNonTerminalBoundaryAndAuditOutageWithoutCommittingEngineState()
            throws Exception {
        DurableTestExecutionCheckpoint current = checkpoint(
                DurableTestExecutionCheckpoint.Status.RESUMING, 4, 8, SHA_A);
        DurableTestRecoveryDispatch dispatch = dispatch(identity(), current);
        DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized = authorized(dispatch);
        when(checkpoints.findRecoveryTerminalResult(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(checkpoints.findRecoveryDispatch(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(dispatch));
        when(checkpoints.find("tenant-a", "test", "run-a"))
                .thenReturn(Optional.of(current));
        when(authorizer.authorize(current, identity())).thenReturn(authorized);

        when(securityEvents.boundAppend(any()))
                .thenThrow(new IllegalStateException("audit unavailable"));
        assertProblem(() -> service.recover(
                        "run-a", request("terminal-1", dispatch), identity()),
                503, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE");
        verifyNoInteractions(runtime);

        org.mockito.Mockito.doReturn(audit).when(securityEvents).boundAppend(any());
        when(runtime.prepare(eq(current), eq(authorized), eq("wait"), any(), any()))
                .thenThrow(new DurableTestTerminalRecoveryRuntime.NonTerminalBoundaryException());
        assertProblem(() -> service.recover(
                        "run-a", request("terminal-2", dispatch), identity()),
                409, "RG.TEST.DURABLE_RECOVERY_NOT_TERMINAL");
        verify(checkpoints, never()).terminalizeRecoveryIdempotently(any(), any(), any());
    }

    @Test
    void validatesEnvironmentWireSizeAndScopeBeforeRuntimeAccess() {
        DurableTestRecoveryDispatch dispatch = dispatch(identity(), checkpoint(
                DurableTestExecutionCheckpoint.Status.RESUMING, 4, 8, SHA_A));
        DurableTestTerminalRecoveryRequest malformed = new DurableTestTerminalRecoveryRequest(
                "wrong", "", null, "bad", null);

        assertProblem(() -> service.recover(
                        "run-a", malformed, identity("worker-a", "production")),
                403, "RG.TEST.DURABLE_ENVIRONMENT_FORBIDDEN");
        assertProblem(() -> service.recover("run-a", malformed, identity()),
                400, "RG.TEST.DURABLE_TERMINAL_RECOVERY_REQUEST_INVALID");
        DurableTestTerminalRecoveryRequest oversized = new DurableTestTerminalRecoveryRequest(
                "", "terminal-large", new DurableTestTerminalRecoveryRequest.Fence(
                dispatch.ownerId(), dispatch.leaseEpoch(), dispatch.revision()), SHA_A,
                new DurableTestTerminalRecoveryRequest.Signal("wait",
                        mapper.valueToTree("x".repeat(300_000))));
        assertProblem(() -> service.recover("run-a", oversized, identity()),
                400, "RG.TEST.DURABLE_RECOVERY_SIGNAL_TOO_LARGE");

        verifyNoInteractions(authorizer, runtime);
        verify(checkpoints, never()).findRecoveryDispatch(any(), any(), any(), any(), any());
    }

    private DurableTestTerminalRecoveryRequest request(
            String key, DurableTestRecoveryDispatch dispatch) {
        return new DurableTestTerminalRecoveryRequest("", key,
                new DurableTestTerminalRecoveryRequest.Fence(
                        dispatch.ownerId(), dispatch.leaseEpoch(), dispatch.revision()),
                dispatch.checkpointFingerprint(),
                new DurableTestTerminalRecoveryRequest.Signal(
                        "wait", mapper.valueToTree("approved")));
    }

    private DurableTestRecoveryDispatch dispatch(
            IntegrationRequestContext identity, DurableTestExecutionCheckpoint checkpoint) {
        DurableTestExecutionCheckpoint.Scope scope = checkpoint.scope();
        DurableTestRecoveryAuthorization authorization = mock(
                DurableTestRecoveryAuthorization.class);
        org.mockito.Mockito.lenient().when(authorization.principalFingerprint()).thenReturn(
                DurableTestRecoveryPrincipal.fingerprint(mapper, identity));
        org.mockito.Mockito.lenient().when(authorization.planFingerprint()).thenReturn(SHA_A);
        DurableTestRecoveryDispatch dispatch = mock(DurableTestRecoveryDispatch.class);
        org.mockito.Mockito.lenient().when(dispatch.scope()).thenReturn(scope);
        org.mockito.Mockito.lenient().when(dispatch.runId()).thenReturn("run-a");
        org.mockito.Mockito.lenient().when(dispatch.engineExecutionId()).thenReturn("engine-a");
        org.mockito.Mockito.lenient().when(dispatch.ownerId()).thenReturn("recovery-a");
        org.mockito.Mockito.lenient().when(dispatch.leaseEpoch()).thenReturn(4L);
        org.mockito.Mockito.lenient().when(dispatch.revision()).thenReturn(8L);
        org.mockito.Mockito.lenient().when(dispatch.leaseExpiresAt()).thenReturn(
                Instant.parse("2026-07-17T01:00:00Z"));
        org.mockito.Mockito.lenient().when(dispatch.checkpointFingerprint()).thenReturn(SHA_A);
        org.mockito.Mockito.lenient().when(dispatch.dispatchFingerprint()).thenReturn(SHA_B);
        org.mockito.Mockito.lenient().when(dispatch.authorization()).thenReturn(authorization);
        org.mockito.Mockito.lenient().when(dispatch.agreesWith(checkpoint)).thenReturn(true);
        return dispatch;
    }

    private DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized(
            DurableTestRecoveryDispatch dispatch) {
        return org.mockito.Mockito.mock(
                DurableTestRecoveryAuthorizer.AuthorizedRecovery.class,
                invocation -> switch (invocation.getMethod().getName()) {
                    case "graph" -> mock(com.leanowtech.bloge.core.model.Graph.class);
                    case "control" -> mock(CompiledExecutionControl.class);
                    case "authorization" -> dispatch.authorization();
                    default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                });
    }

    private DurableTestTerminalRecoveryRuntime.PreparedTerminalRecovery prepared() {
        DurableTestExecutionCheckpointRepository.BoundEngineStateMutation mutation =
                mock(DurableTestExecutionCheckpointRepository.BoundEngineStateMutation.class);
        DurableTestExecutionCheckpoint.EngineState engineState =
                new DurableTestExecutionCheckpoint.EngineState(
                        "terminal:checkpoint", "wait", "NODE_BOUNDARY", 4, 4, SHA_C);
        when(mutation.engineState()).thenReturn(engineState);
        FixtureConsumptionStateSnapshot fixture = mock(FixtureConsumptionStateSnapshot.class);
        ExecutionServiceStateSnapshot services = mock(ExecutionServiceStateSnapshot.class);
        when(services.planFingerprint()).thenReturn(SHA_A);
        return mock(DurableTestTerminalRecoveryRuntime.PreparedTerminalRecovery.class,
                invocation -> switch (invocation.getMethod().getName()) {
                    case "engineStateMutation" -> mutation;
                    case "fixtureConsumptionState" -> fixture;
                    case "executionServiceState" -> services;
                    case "executionOutcome" ->
                            DurableTestRecoveryTerminalReceipt.ExecutionOutcome.COMPLETED;
                    default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                });
    }

    private DurableTestExecutionCheckpoint checkpoint(
            DurableTestExecutionCheckpoint.Status status,
            long leaseEpoch,
            long revision,
            String fingerprint) {
        DurableTestExecutionCheckpoint checkpoint = mock(DurableTestExecutionCheckpoint.class);
        org.mockito.Mockito.lenient().when(checkpoint.scope()).thenReturn(
                new DurableTestExecutionCheckpoint.Scope(
                        "tenant-a", "org-a", "project-a", "test", "runner-a"));
        org.mockito.Mockito.lenient().when(checkpoint.runId()).thenReturn("run-a");
        org.mockito.Mockito.lenient().when(checkpoint.engineExecutionId()).thenReturn("engine-a");
        org.mockito.Mockito.lenient().when(checkpoint.checkpointFingerprint()).thenReturn(fingerprint);
        org.mockito.Mockito.lenient().when(checkpoint.lifecycle()).thenReturn(
                new DurableTestExecutionCheckpoint.Lifecycle(
                        status, "recovery-a", leaseEpoch, revision,
                        Instant.parse("2026-07-17T00:00:00Z"),
                        Instant.parse("2026-07-17T00:01:00Z"),
                        Instant.parse("2026-07-17T01:00:00Z")));
        return checkpoint;
    }

    private DurableTestRecoveryTerminalReceipt receipt(
            DurableTestRecoveryDispatch dispatch, DurableTestExecutionCheckpoint terminal) {
        String terminalFingerprint = terminal.checkpointFingerprint();
        DurableTestRecoveryTerminalReceipt receipt = mock(
                DurableTestRecoveryTerminalReceipt.class);
        when(receipt.terminalCheckpointFingerprint()).thenReturn(
                terminalFingerprint);
        when(receipt.receiptFingerprint()).thenReturn(SHA_C);
        when(receipt.executionOutcome()).thenReturn(
                DurableTestRecoveryTerminalReceipt.ExecutionOutcome.COMPLETED);
        when(receipt.evidenceStatus()).thenReturn("EVIDENCE_INCOMPLETE");
        when(receipt.evidenceGapCodes()).thenReturn(List.of(
                "PRE_CHECKPOINT_TRACE_UNAVAILABLE", "RECOVERY_SIGNAL_PAYLOAD_OMITTED"));
        when(receipt.completedAt()).thenReturn(Instant.parse("2026-07-17T00:02:00Z"));
        return receipt;
    }

    private static IntegrationRequestContext identity() {
        return identity("worker-a", "test");
    }

    private static IntegrationRequestContext identity(String actor) {
        return identity(actor, "test");
    }

    private static IntegrationRequestContext identity(String actor, String environment) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", environment, "sg", "WORKLOAD", actor,
                "dispatcher-a", "TEST_EXECUTION", "correlation-a", Set.of("quality"),
                "CONFIDENTIAL", "grant-a");
    }

    private static void assertProblem(Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo(code);
                    assertThat(failure.problem().details()).isEmpty();
                });
    }
}
