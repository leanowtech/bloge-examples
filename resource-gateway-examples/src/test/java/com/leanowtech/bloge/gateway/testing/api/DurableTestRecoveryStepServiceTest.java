package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionGuard;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionIntent;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryAuthorization;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryDispatch;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryTerminalReceipt;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestTerminalRecoveryRuntime;
import org.junit.jupiter.api.AfterEach;
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
class DurableTestRecoveryStepServiceTest {

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
    @Mock
    private TestRuntimeAdmissionGate admissions;

    private ObjectMapper mapper;
    private DurableTestRecoveryLeaseCoordinator leases;
    private DurableTestTerminalRecoveryService service;
    private TestRuntimeTransactionMutation audit;
    private AdmissionGuard admission;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        audit = jdbc -> { };
        admission = mock(AdmissionGuard.class);
        org.mockito.Mockito.lenient().when(admissions.admit(any(), any()))
                .thenReturn(admission);
        leases = DurableTestRecoveryLeaseCoordinator.passive();
        service = new DurableTestTerminalRecoveryService(
                checkpoints, authorizer, runtime, securityEvents, mapper, leases, admissions);
    }

    @AfterEach
    void tearDown() {
        leases.close();
    }

    @Test
    void commitsServerDerivedSuspensionAndReleasesTheConsumedStepAsOneMutation() {
        Live live = arrangeLive();
        DurableTestExecutionCheckpoint.EngineState nextEngine = engineState(
                "step:next", "approval-2", "SUSPEND", 5, 5, SHA_C);
        DurableTestTerminalRecoveryRuntime.PreparedRecoveryStep prepared = prepared(
                DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.SUSPENDED,
                nextEngine);
        DurableTestExecutionCheckpoint suspended = checkpoint(
                DurableTestExecutionCheckpoint.Status.SUSPENDED, 4, 9, SHA_B, nextEngine);
        DurableTestExecutionCheckpointRepository.RecoveryStepResult result =
                new DurableTestExecutionCheckpointRepository.RecoveryStepResult(
                        DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.SUSPENDED,
                        suspended, null, false);
        when(runtime.prepareStep(eq(live.current()), eq(live.authorized()),
                eq("approval-1"), any(), any())).thenReturn(prepared);
        when(checkpoints.advanceRecoveryStepIdempotently(any(), any(), eq(audit)))
                .thenReturn(result);

        DurableTestRecoveryStepResponse response = service.advance(
                "run-a", request("step-1", live.dispatch()), identity());

        assertThat(response).satisfies(value -> {
            assertThat(value.outcome()).isEqualTo("SUSPENDED");
            assertThat(value.status()).isEqualTo("SUSPENDED");
            assertThat(value.revision()).isEqualTo(9);
            assertThat(value.checkpointFingerprint()).isEqualTo(SHA_B);
            assertThat(value.boundary().nodeId()).isEqualTo("approval-2");
            assertThat(value.boundary().boundaryType()).isEqualTo("SUSPEND");
            assertThat(value.terminal()).isNull();
            assertThat(value.idempotentReplay()).isFalse();
        });
        ArgumentCaptor<DurableTestExecutionCheckpointRepository.RecoveryStepCommand> command =
                ArgumentCaptor.forClass(
                        DurableTestExecutionCheckpointRepository.RecoveryStepCommand.class);
        DurableTestExecutionCheckpointRepository.BoundEngineStateMutation expectedMutation =
                prepared.engineStateMutation();
        verify(checkpoints).advanceRecoveryStepIdempotently(
                command.capture(), eq(expectedMutation), eq(audit));
        assertThat(command.getValue()).satisfies(value -> {
            assertThat(value.expectedDispatch()).isSameAs(live.dispatch());
            assertThat(value.outcome()).isEqualTo(
                    DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.SUSPENDED);
            assertThat(value.engineState()).isSameAs(nextEngine);
            assertThat(value.evidenceGapCodes()).containsExactly(
                    "PRE_CHECKPOINT_TRACE_UNAVAILABLE",
                    "RECOVERY_SIGNAL_PAYLOAD_OMITTED");
        });
        ArgumentCaptor<TestSecurityEvent> event = ArgumentCaptor.forClass(
                TestSecurityEvent.class);
        verify(securityEvents).boundAppend(event.capture());
        assertThat(event.getValue()).satisfies(value -> {
            assertThat(value.eventType()).isEqualTo("DURABLE_RECOVERY_STEP");
            assertThat(value.reasonCode()).isEqualTo(
                    "RG.TEST.DURABLE_RECOVERY_STEP_COMMITTED");
            assertThat(value.facts()).containsEntry("clientRequestId", "step-1");
        });
        ArgumentCaptor<AdmissionIntent> intent = ArgumentCaptor.forClass(AdmissionIntent.class);
        verify(admissions).admit(eq(identity()), intent.capture());
        assertThat(intent.getValue().stableRequestKey()).isEqualTo("step-1");
        verify(admission).checkpoint();
        verify(prepared).close();
    }

    @Test
    void commitsTerminalBoundaryWithPromotionBlockingReceiptProjection() {
        Live live = arrangeLive();
        DurableTestExecutionCheckpoint.EngineState terminalEngine = engineState(
                "step:terminal", "complete", "NODE_BOUNDARY", 5, 5, SHA_C);
        DurableTestTerminalRecoveryRuntime.PreparedRecoveryStep prepared = prepared(
                DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.COMPLETED,
                terminalEngine);
        DurableTestExecutionCheckpoint terminal = checkpoint(
                DurableTestExecutionCheckpoint.Status.TERMINAL, 4, 9, SHA_B,
                terminalEngine);
        DurableTestRecoveryTerminalReceipt receipt = receipt(terminal);
        DurableTestExecutionCheckpointRepository.RecoveryStepResult result =
                new DurableTestExecutionCheckpointRepository.RecoveryStepResult(
                        DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.COMPLETED,
                        terminal, receipt, false);
        when(runtime.prepareStep(eq(live.current()), eq(live.authorized()),
                eq("approval-1"), any(), any())).thenReturn(prepared);
        when(checkpoints.advanceRecoveryStepIdempotently(any(), any(), eq(audit)))
                .thenReturn(result);

        DurableTestRecoveryStepResponse response = service.advance(
                "run-a", request("step-terminal", live.dispatch()), identity());

        assertThat(response.outcome()).isEqualTo("COMPLETED");
        assertThat(response.status()).isEqualTo("TERMINAL");
        assertThat(response.terminal()).satisfies(value -> {
            assertThat(value.executionOutcome()).isEqualTo("COMPLETED");
            assertThat(value.receiptFingerprint()).isEqualTo(SHA_C);
            assertThat(value.evidenceStatus()).isEqualTo("EVIDENCE_INCOMPLETE");
        });
    }

    @Test
    void responseLossReplayReturnsBeforeDispatchAuthorizationAdmissionOrEngineAccess() {
        DurableTestExecutionCheckpoint.EngineState nextEngine = engineState(
                "step:next", "approval-2", "SUSPEND", 5, 5, SHA_C);
        DurableTestExecutionCheckpoint suspended = checkpoint(
                DurableTestExecutionCheckpoint.Status.SUSPENDED, 4, 9, SHA_B, nextEngine);
        DurableTestExecutionCheckpointRepository.RecoveryStepResult replay =
                new DurableTestExecutionCheckpointRepository.RecoveryStepResult(
                        DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.SUSPENDED,
                        suspended, null, true);
        DurableTestRecoveryDispatch source = dispatch(checkpoint(
                DurableTestExecutionCheckpoint.Status.RESUMING, 4, 8, SHA_A,
                engineState("source", "approval-1", "SUSPEND", 4, 4, SHA_A)));
        when(checkpoints.findRecoveryStepResult(
                eq("tenant-a"), eq("test"), eq("step-replay"), any()))
                .thenReturn(Optional.of(replay));
        when(securityEvents.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DurableTestRecoveryStepResponse response = service.advance(
                "run-a", request("step-replay", source), identity());

        assertThat(response.idempotentReplay()).isTrue();
        verify(checkpoints, never()).findRecoveryDispatch(any(), any(), any(), any(), any());
        verifyNoInteractions(authorizer, runtime, admissions);
        ArgumentCaptor<TestSecurityEvent> event = ArgumentCaptor.forClass(
                TestSecurityEvent.class);
        verify(securityEvents).append(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("DURABLE_RECOVERY_STEP");
        assertThat(event.getValue().reasonCode()).isEqualTo(
                "RG.TEST.DURABLE_RECOVERY_STEP_IDEMPOTENT_REPLAY");
    }

    @Test
    void rejectsInvalidProtocolAndOversizedSignalBeforeDispatchLookup() {
        DurableTestRecoveryStepRequest invalid = new DurableTestRecoveryStepRequest(
                "wrong", "", null, "bad", null);
        DurableTestRecoveryStepRequest oversized = new DurableTestRecoveryStepRequest(
                "", "step-large", new DurableTestRecoveryStepRequest.Fence(
                "recovery-a", 4, 8), SHA_A,
                new DurableTestRecoveryStepRequest.Signal(
                        "approval-1", mapper.valueToTree("x".repeat(300_000))));

        assertProblem(() -> service.advance("run-a", invalid, identity()),
                400, "RG.TEST.DURABLE_RECOVERY_STEP_REQUEST_INVALID");
        assertProblem(() -> service.advance("run-a", oversized, identity()),
                400, "RG.TEST.DURABLE_RECOVERY_SIGNAL_TOO_LARGE");

        verify(checkpoints, never()).findRecoveryDispatch(any(), any(), any(), any(), any());
        verifyNoInteractions(authorizer, runtime);
    }

    private Live arrangeLive() {
        DurableTestExecutionCheckpoint current = checkpoint(
                DurableTestExecutionCheckpoint.Status.RESUMING, 4, 8, SHA_A,
                engineState("source", "approval-1", "SUSPEND", 4, 4, SHA_A));
        DurableTestRecoveryDispatch dispatch = dispatch(current);
        DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized = authorized(dispatch);
        when(checkpoints.findRecoveryStepResult(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(checkpoints.findRecoveryDispatch(
                eq("tenant-a"), eq("test"), eq("run-a"), any(), eq(SHA_A)))
                .thenReturn(Optional.of(dispatch));
        when(checkpoints.find("tenant-a", "test", "run-a"))
                .thenReturn(Optional.of(current));
        when(authorizer.authorize(current, identity())).thenReturn(authorized);
        org.mockito.Mockito.doReturn(audit).when(securityEvents).boundAppend(any());
        return new Live(current, dispatch, authorized);
    }

    private DurableTestTerminalRecoveryRuntime.PreparedRecoveryStep prepared(
            DurableTestExecutionCheckpointRepository.RecoveryStepOutcome outcome,
            DurableTestExecutionCheckpoint.EngineState engineState) {
        DurableTestExecutionCheckpointRepository.BoundEngineStateMutation mutation =
                mock(DurableTestExecutionCheckpointRepository.BoundEngineStateMutation.class);
        when(mutation.engineState()).thenReturn(engineState);
        FixtureConsumptionStateSnapshot fixture = mock(FixtureConsumptionStateSnapshot.class);
        ExecutionServiceStateSnapshot services = mock(ExecutionServiceStateSnapshot.class);
        when(services.planFingerprint()).thenReturn(SHA_A);
        return mock(DurableTestTerminalRecoveryRuntime.PreparedRecoveryStep.class,
                invocation -> switch (invocation.getMethod().getName()) {
                    case "engineStateMutation" -> mutation;
                    case "fixtureConsumptionState" -> fixture;
                    case "executionServiceState" -> services;
                    case "outcome" -> outcome;
                    default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                });
    }

    private DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized(
            DurableTestRecoveryDispatch dispatch) {
        CompiledExecutionControl control = mock(CompiledExecutionControl.class,
                invocation -> "inventory".equals(invocation.getMethod().getName())
                        ? new InvocationInventory(
                        List.of(), java.util.Map.of(), java.util.Map.of())
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
        return mock(DurableTestRecoveryAuthorizer.AuthorizedRecovery.class,
                invocation -> switch (invocation.getMethod().getName()) {
                    case "graph" -> mock(com.leanowtech.bloge.core.model.Graph.class);
                    case "control" -> control;
                    case "dependencyRefs" -> Set.of("resource-a");
                    case "authorization" -> dispatch.authorization();
                    default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                });
    }

    private DurableTestRecoveryDispatch dispatch(DurableTestExecutionCheckpoint checkpoint) {
        DurableTestRecoveryAuthorization authorization = mock(
                DurableTestRecoveryAuthorization.class);
        org.mockito.Mockito.lenient().when(authorization.principalFingerprint()).thenReturn(
                DurableTestRecoveryPrincipal.fingerprint(mapper, identity()));
        org.mockito.Mockito.lenient().when(authorization.planFingerprint()).thenReturn(SHA_A);
        DurableTestRecoveryDispatch dispatch = mock(DurableTestRecoveryDispatch.class);
        DurableTestExecutionCheckpoint.Lifecycle lifecycle = checkpoint.lifecycle();
        DurableTestExecutionCheckpoint.Scope scope = checkpoint.scope();
        String checkpointFingerprint = checkpoint.checkpointFingerprint();
        org.mockito.Mockito.lenient().when(dispatch.scope()).thenReturn(scope);
        org.mockito.Mockito.lenient().when(dispatch.runId()).thenReturn("run-a");
        org.mockito.Mockito.lenient().when(dispatch.engineExecutionId()).thenReturn("engine-a");
        org.mockito.Mockito.lenient().when(dispatch.ownerId()).thenReturn("recovery-a");
        org.mockito.Mockito.lenient().when(dispatch.leaseEpoch()).thenReturn(lifecycle.leaseEpoch());
        org.mockito.Mockito.lenient().when(dispatch.revision()).thenReturn(lifecycle.revision());
        org.mockito.Mockito.lenient().when(dispatch.leaseExpiresAt()).thenReturn(
                lifecycle.leaseExpiresAt());
        org.mockito.Mockito.lenient().when(dispatch.checkpointFingerprint()).thenReturn(
                checkpointFingerprint);
        org.mockito.Mockito.lenient().when(dispatch.dispatchFingerprint()).thenReturn(SHA_B);
        org.mockito.Mockito.lenient().when(dispatch.authorization()).thenReturn(authorization);
        org.mockito.Mockito.lenient().when(dispatch.agreesWith(checkpoint)).thenReturn(true);
        return dispatch;
    }

    private DurableTestExecutionCheckpoint checkpoint(
            DurableTestExecutionCheckpoint.Status status,
            long leaseEpoch,
            long revision,
            String fingerprint,
            DurableTestExecutionCheckpoint.EngineState engineState) {
        DurableTestExecutionCheckpoint checkpoint = mock(DurableTestExecutionCheckpoint.class);
        org.mockito.Mockito.lenient().when(checkpoint.scope()).thenReturn(
                new DurableTestExecutionCheckpoint.Scope(
                "tenant-a", "org-a", "project-a", "test", "runner-a"));
        org.mockito.Mockito.lenient().when(checkpoint.runId()).thenReturn("run-a");
        org.mockito.Mockito.lenient().when(checkpoint.engineExecutionId()).thenReturn("engine-a");
        org.mockito.Mockito.lenient().when(checkpoint.engineState()).thenReturn(engineState);
        org.mockito.Mockito.lenient().when(checkpoint.checkpointFingerprint()).thenReturn(
                fingerprint);
        org.mockito.Mockito.lenient().when(checkpoint.lifecycle()).thenReturn(
                new DurableTestExecutionCheckpoint.Lifecycle(
                status, "recovery-a", leaseEpoch, revision,
                Instant.parse("2026-07-17T00:00:00Z"),
                Instant.parse("2026-07-17T00:02:00Z"),
                Instant.parse("2026-07-17T01:00:00Z")));
        return checkpoint;
    }

    private static DurableTestExecutionCheckpoint.EngineState engineState(
            String checkpointRef,
            String nodeId,
            String boundaryType,
            long boundarySequence,
            long stateVersion,
            String closureFingerprint) {
        return new DurableTestExecutionCheckpoint.EngineState(
                checkpointRef, nodeId, boundaryType, boundarySequence,
                stateVersion, closureFingerprint);
    }

    private DurableTestRecoveryTerminalReceipt receipt(
            DurableTestExecutionCheckpoint terminal) {
        DurableTestRecoveryTerminalReceipt receipt = mock(
                DurableTestRecoveryTerminalReceipt.class);
        String terminalFingerprint = terminal.checkpointFingerprint();
        when(receipt.terminalCheckpointFingerprint()).thenReturn(
                terminalFingerprint);
        when(receipt.receiptFingerprint()).thenReturn(SHA_C);
        when(receipt.executionOutcome()).thenReturn(
                DurableTestRecoveryTerminalReceipt.ExecutionOutcome.COMPLETED);
        when(receipt.evidenceStatus()).thenReturn("EVIDENCE_INCOMPLETE");
        when(receipt.evidenceGapCodes()).thenReturn(List.of(
                "PRE_CHECKPOINT_TRACE_UNAVAILABLE",
                "RECOVERY_SIGNAL_PAYLOAD_OMITTED"));
        when(receipt.completedAt()).thenReturn(
                Instant.parse("2026-07-17T00:02:00Z"));
        return receipt;
    }

    private DurableTestRecoveryStepRequest request(
            String key, DurableTestRecoveryDispatch dispatch) {
        return new DurableTestRecoveryStepRequest(
                "", key, new DurableTestRecoveryStepRequest.Fence(
                dispatch.ownerId(), dispatch.leaseEpoch(), dispatch.revision()),
                dispatch.checkpointFingerprint(),
                new DurableTestRecoveryStepRequest.Signal(
                        "approval-1", mapper.valueToTree("approved")));
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "worker-a", "dispatcher-a", "TEST_EXECUTION",
                "correlation-a", Set.of("quality"), "CONFIDENTIAL", "grant-a");
    }

    private static void assertProblem(Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo(code);
                    assertThat(failure.problem().details()).isEmpty();
                });
    }

    private record Live(
            DurableTestExecutionCheckpoint current,
            DurableTestRecoveryDispatch dispatch,
            DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized) {
    }
}
