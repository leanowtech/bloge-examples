package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DurableTestRecoverySequenceServiceTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");

    @Mock
    private DurableTestExecutionCheckpointRepository checkpoints;
    @Mock
    private DurableTestOwnerClaimService ownerClaims;
    @Mock
    private DurableTestTerminalRecoveryService recoverySteps;
    @Mock
    private TestSecurityEventRepository securityEvents;

    private ObjectMapper mapper;
    private DurableTestRecoverySequenceService service;
    private TestRuntimeTransactionMutation audit;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        audit = jdbc -> { };
        service = new DurableTestRecoverySequenceService(
                checkpoints, ownerClaims, recoverySteps, securityEvents, mapper);
        org.mockito.Mockito.lenient().when(securityEvents.boundAppend(any()))
                .thenReturn(audit);
    }

    @Test
    void advancesAcrossFreshIntermediateClaimAndStopsAtEarlyTerminal() {
        arrangeReservation(false);
        DurableTestRecoveryStepResponse first = suspended(
                "owner-a", 2, 5, SHA_A, "approval-2", false);
        DurableTestOwnerClaimResponse claim = claim(false);
        DurableTestRecoveryStepResponse terminal = terminal(false);
        when(recoverySteps.advance(eq("run-a"), any(), eq(identity())))
                .thenReturn(first, terminal);
        when(ownerClaims.claim(eq("run-a"), any(), eq(identity()))).thenReturn(claim);

        DurableTestRecoverySequenceResponse response = service.advance(
                "run-a", request(3), identity());

        assertThat(response).satisfies(value -> {
            assertThat(value.outcome()).isEqualTo("COMPLETED");
            assertThat(value.status()).isEqualTo("TERMINAL");
            assertThat(value.stopReason()).isEqualTo("TERMINAL");
            assertThat(value.providedSignalCount()).isEqualTo(3);
            assertThat(value.consumedSignalCount()).isEqualTo(2);
            assertThat(value.steps()).containsExactly(first, terminal);
            assertThat(value.idempotentReplay()).isFalse();
        });
        ArgumentCaptor<DurableTestOwnerClaimRequest> claimRequest =
                ArgumentCaptor.forClass(DurableTestOwnerClaimRequest.class);
        verify(ownerClaims).claim(eq("run-a"), claimRequest.capture(), eq(identity()));
        assertThat(claimRequest.getValue()).satisfies(value -> {
            assertThat(value.clientRequestId()).matches(
                    "rseq:[a-f0-9]{64}:claim:1");
            assertThat(value.expectedFence().ownerId()).isEqualTo("owner-a");
            assertThat(value.expectedFence().leaseEpoch()).isEqualTo(2);
            assertThat(value.expectedFence().revision()).isEqualTo(5);
            assertThat(value.expectedCheckpointFingerprint()).isEqualTo(SHA_A);
        });
        ArgumentCaptor<DurableTestRecoveryStepRequest> steps =
                ArgumentCaptor.forClass(DurableTestRecoveryStepRequest.class);
        verify(recoverySteps, org.mockito.Mockito.times(2))
                .advance(eq("run-a"), steps.capture(), eq(identity()));
        assertThat(steps.getAllValues()).satisfies(values -> {
            assertThat(values.get(0).clientRequestId()).matches(
                    "rseq:[a-f0-9]{64}:step:0");
            assertThat(values.get(0).expectedFence().ownerId()).isEqualTo("owner-initial");
            assertThat(values.get(1).clientRequestId()).matches(
                    "rseq:[a-f0-9]{64}:step:1");
            assertThat(values.get(1).expectedFence().ownerId()).isEqualTo("owner-b");
            assertThat(values.get(1).expectedCheckpointFingerprint()).isEqualTo(SHA_B);
        });
    }

    @Test
    void responseLossRetryReplaysCommittedPrefixAndContinuesAtFirstNewStep() {
        arrangeReservation(true);
        DurableTestRecoveryStepResponse firstReplay = suspended(
                "owner-a", 2, 5, SHA_A, "approval-2", true);
        when(recoverySteps.advance(eq("run-a"), any(), eq(identity())))
                .thenReturn(firstReplay, terminal(false));
        when(ownerClaims.claim(eq("run-a"), any(), eq(identity())))
                .thenReturn(claim(true));

        DurableTestRecoverySequenceResponse response = service.advance(
                "run-a", request(2), identity());

        assertThat(response.consumedSignalCount()).isEqualTo(2);
        assertThat(response.stopReason()).isEqualTo("TERMINAL");
        assertThat(response.steps().getFirst().idempotentReplay()).isTrue();
        assertThat(response.steps().getLast().idempotentReplay()).isFalse();
        assertThat(response.idempotentReplay()).isFalse();
    }

    @Test
    void unchangedCompletedRetryIsReportedAsFullyIdempotent() {
        arrangeReservation(true);
        when(recoverySteps.advance(eq("run-a"), any(), eq(identity())))
                .thenReturn(suspended("owner-a", 2, 5, SHA_A, "approval-2", true),
                        terminal(true));
        when(ownerClaims.claim(eq("run-a"), any(), eq(identity())))
                .thenReturn(claim(true));

        DurableTestRecoverySequenceResponse response = service.advance(
                "run-a", request(2), identity());

        assertThat(response.idempotentReplay()).isTrue();
        assertThat(response.steps()).allMatch(
                DurableTestRecoveryStepResponse::idempotentReplay);
    }

    @Test
    void consumesAllSignalsAndReturnsSuspendedWhenProgramIsExhausted() {
        arrangeReservation(false);
        DurableTestRecoveryStepResponse first = suspended(
                "owner-a", 2, 5, SHA_A, "approval-2", false);
        DurableTestRecoveryStepResponse second = suspended(
                "owner-b", 3, 7, SHA_C, "approval-3", false);
        when(recoverySteps.advance(eq("run-a"), any(), eq(identity())))
                .thenReturn(first, second);
        when(ownerClaims.claim(eq("run-a"), any(), eq(identity())))
                .thenReturn(claim(false));

        DurableTestRecoverySequenceResponse response = service.advance(
                "run-a", request(2), identity());

        assertThat(response.outcome()).isEqualTo("SUSPENDED");
        assertThat(response.stopReason()).isEqualTo("SIGNALS_EXHAUSTED");
        assertThat(response.consumedSignalCount()).isEqualTo(2);
        assertThat(response.steps()).containsExactly(first, second);
    }

    @Test
    void completeIntentDriftFailsBeforeAnyChildClaimOrStep() {
        when(checkpoints.reserveRecoverySequenceIdempotently(any(), eq(audit)))
                .thenThrow(new DurableTestExecutionCheckpointConflictException(
                        DurableTestExecutionCheckpointConflictException.Reason
                                .IDEMPOTENCY_CONFLICT,
                        "different late signal"));

        assertProblem(() -> service.advance("run-a", request(2), identity()),
                409, "RG.TEST.DURABLE_RECOVERY_SEQUENCE_IDEMPOTENCY_CONFLICT");

        verifyNoInteractions(ownerClaims, recoverySteps);
        ArgumentCaptor<TestSecurityEvent> rejected =
                ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents).append(rejected.capture());
        assertThat(rejected.getValue()).satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("DURABLE_RECOVERY_SEQUENCE");
            assertThat(event.outcome()).isEqualTo("REJECTED");
            assertThat(event.reasonCode()).isEqualTo(
                    "RG.TEST.DURABLE_RECOVERY_SEQUENCE_IDEMPOTENCY_CONFLICT");
            assertThat(event.facts()).containsOnlyKeys("runId", "clientRequestId");
        });
    }

    @Test
    void expiredExactReplayReturnsStableConflictBeforeAnyChildMutation() {
        when(checkpoints.reserveRecoverySequenceIdempotently(any(), eq(audit)))
                .thenThrow(new DurableTestExecutionCheckpointConflictException(
                        DurableTestExecutionCheckpointConflictException.Reason
                                .REPLAY_WINDOW_EXPIRED,
                        "detailed replay expired"));

        assertProblem(() -> service.advance("run-a", request(2), identity()),
                409, "RG.TEST.DURABLE_RECOVERY_SEQUENCE_REPLAY_WINDOW_EXPIRED");

        verifyNoInteractions(ownerClaims, recoverySteps);
        ArgumentCaptor<TestSecurityEvent> rejected =
                ArgumentCaptor.forClass(TestSecurityEvent.class);
        verify(securityEvents).append(rejected.capture());
        assertThat(rejected.getValue()).satisfies(event -> {
            assertThat(event.outcome()).isEqualTo("REJECTED");
            assertThat(event.reasonCode()).isEqualTo(
                    "RG.TEST.DURABLE_RECOVERY_SEQUENCE_REPLAY_WINDOW_EXPIRED");
            assertThat(event.facts()).containsOnlyKeys("runId", "clientRequestId");
        });
    }

    @Test
    void rejectsOversizedLateSignalBeforeReservingOrExecutingPrefix() {
        DurableTestRecoverySequenceRequest request = request(List.of(
                signal("approval-1", "approved"),
                signal("approval-2", "x".repeat(300_000))));

        assertProblem(() -> service.advance("run-a", request, identity()),
                400, "RG.TEST.DURABLE_RECOVERY_SEQUENCE_TOO_LARGE");

        verify(checkpoints, never()).reserveRecoverySequenceIdempotently(any(), any());
        verifyNoInteractions(ownerClaims, recoverySteps);
    }

    @Test
    void rejectsMoreThanSixteenSignalsBeforePersistence() {
        List<DurableTestRecoverySequenceRequest.Signal> signals =
                java.util.stream.IntStream.range(0, 17)
                        .mapToObj(index -> signal("approval-" + index, index))
                        .toList();

        assertProblem(() -> service.advance(
                        "run-a", request(signals), identity()),
                400, "RG.TEST.DURABLE_RECOVERY_SEQUENCE_REQUEST_INVALID");

        verify(checkpoints, never()).reserveRecoverySequenceIdempotently(any(), any());
    }

    private void arrangeReservation(boolean replay) {
        when(checkpoints.reserveRecoverySequenceIdempotently(any(), eq(audit)))
                .thenAnswer(invocation -> {
                    DurableTestExecutionCheckpointRepository.RecoverySequenceCommand command =
                            invocation.getArgument(0);
                    return new DurableTestExecutionCheckpointRepository
                            .RecoverySequenceReservation(command, NOW, SHA_C, replay);
                });
    }

    private DurableTestRecoverySequenceRequest request(int signalCount) {
        return request(java.util.stream.IntStream.range(0, signalCount)
                .mapToObj(index -> signal("approval-" + (index + 1), "value-" + index))
                .toList());
    }

    private DurableTestRecoverySequenceRequest request(
            List<DurableTestRecoverySequenceRequest.Signal> signals) {
        return new DurableTestRecoverySequenceRequest(
                "", "sequence-a",
                new DurableTestRecoverySequenceRequest.Fence("owner-initial", 1, 3),
                SHA_C, signals);
    }

    private DurableTestRecoverySequenceRequest.Signal signal(String nodeId, Object value) {
        return new DurableTestRecoverySequenceRequest.Signal(
                nodeId, mapper.valueToTree(value));
    }

    private static DurableTestRecoveryStepResponse suspended(
            String ownerId,
            long epoch,
            long revision,
            String fingerprint,
            String nodeId,
            boolean replay) {
        return new DurableTestRecoveryStepResponse(
                "", "run-a", "SUSPENDED", "SUSPENDED", ownerId, epoch, revision,
                NOW, fingerprint,
                new DurableTestRecoveryStepResponse.Boundary(
                        nodeId, "SUSPEND", revision, revision),
                null, replay);
    }

    private static DurableTestRecoveryStepResponse terminal(boolean replay) {
        return new DurableTestRecoveryStepResponse(
                "", "run-a", "COMPLETED", "TERMINAL", "owner-b", 3, 8,
                NOW, SHA_C,
                new DurableTestRecoveryStepResponse.Boundary(
                        "complete", "NODE_BOUNDARY", 8, 8),
                new DurableTestRecoveryStepResponse.Terminal(
                        "COMPLETED", NOW, SHA_A, "EVIDENCE_INCOMPLETE",
                        List.of("PRE_CHECKPOINT_TRACE_UNAVAILABLE")),
                replay);
    }

    private static DurableTestOwnerClaimResponse claim(boolean replay) {
        return new DurableTestOwnerClaimResponse(
                "", "run-a", "RESUMING", "owner-b", 3, 6,
                NOW.plusSeconds(120), SHA_B,
                new DurableTestOwnerClaimResponse.Target("GRAPH", "graph-a", SHA_C),
                replay);
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
}
