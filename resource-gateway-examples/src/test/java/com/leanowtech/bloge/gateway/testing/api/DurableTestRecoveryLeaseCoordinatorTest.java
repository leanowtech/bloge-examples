package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryAuthorization;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryDispatch;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DurableTestRecoveryLeaseCoordinatorTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);

    @Test
    void renewsBeforeExecutionAndFreezesTheLatestSuccessorDispatch() throws Exception {
        DurableTestRecoveryHeartbeatService heartbeats =
                mock(DurableTestRecoveryHeartbeatService.class);
        RecoveryValues values = new RecoveryValues();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch periodic = new CountDownLatch(1);
        when(heartbeats.leaseDuration()).thenReturn(Duration.ofSeconds(3));
        when(heartbeats.renewIssuedDispatch(any(), any(), any())).thenAnswer(invocation -> {
            int sequence = calls.incrementAndGet();
            if (sequence > 1) {
                periodic.countDown();
            }
            return values.successor(invocation.getArgument(0));
        });

        try (DurableTestRecoveryLeaseCoordinator coordinator =
                     new DurableTestRecoveryLeaseCoordinator(
                             heartbeats, Duration.ofMillis(100));
             DurableTestRecoveryLeaseCoordinator.LeaseGuard guard = coordinator.monitor(
                     values.sourceDispatch, values.sourceCheckpoint, SHA_A, identity())) {
            assertThat(guard.executionCheckpoint().lifecycle().revision()).isEqualTo(9);
            assertThat(calls).hasValue(1);
            assertThat(periodic.await(2, TimeUnit.SECONDS)).isTrue();

            var frozen = guard.freeze();
            int callsAtFreeze = calls.get();
            Thread.sleep(35);

            assertThat(frozen.dispatch().revision()).isGreaterThanOrEqualTo(10);
            assertThat(frozen.dispatch().agreesWith(frozen.checkpoint())).isTrue();
            assertThat(calls).hasValue(callsAtFreeze);
            assertThat(guard.held()).isFalse();
        }
    }

    @Test
    void heartbeatFailureMakesTheRecoveryStageUncommittable() throws Exception {
        DurableTestRecoveryHeartbeatService heartbeats =
                mock(DurableTestRecoveryHeartbeatService.class);
        RecoveryValues values = new RecoveryValues();
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(heartbeats.leaseDuration()).thenReturn(Duration.ofSeconds(3));
        when(heartbeats.renewIssuedDispatch(any(), any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return values.successor(invocation.getArgument(0));
            }
            failed.countDown();
            throw new IllegalStateException("heartbeat unavailable");
        });

        try (DurableTestRecoveryLeaseCoordinator coordinator =
                     new DurableTestRecoveryLeaseCoordinator(
                             heartbeats, Duration.ofMillis(10));
             DurableTestRecoveryLeaseCoordinator.LeaseGuard guard = coordinator.monitor(
                     values.sourceDispatch, values.sourceCheckpoint, SHA_A, identity())) {
            assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitLeaseLoss(guard)).isTrue();

            assertThatThrownBy(guard::freeze)
                    .isInstanceOf(
                            DurableTestRecoveryLeaseCoordinator.LeaseLostException.class)
                    .hasMessageContaining("ownership became uncertain")
                    .hasRootCauseMessage("heartbeat unavailable");
        }
    }

    @Test
    void rejectsAHeartbeatThatMutatesTheFrozenRecoveryClosure() {
        DurableTestRecoveryHeartbeatService heartbeats =
                mock(DurableTestRecoveryHeartbeatService.class);
        RecoveryValues values = new RecoveryValues();
        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult mutated =
                values.mutatedSuccessor();
        when(heartbeats.leaseDuration()).thenReturn(Duration.ofSeconds(3));
        when(heartbeats.renewIssuedDispatch(any(), any(), any()))
                .thenReturn(mutated);

        try (DurableTestRecoveryLeaseCoordinator coordinator =
                     new DurableTestRecoveryLeaseCoordinator(
                             heartbeats, Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> coordinator.monitor(
                    values.sourceDispatch, values.sourceCheckpoint, SHA_A, identity()))
                    .isInstanceOf(
                            DurableTestRecoveryLeaseCoordinator.LeaseLostException.class)
                    .hasMessageContaining("invalid successor")
                    .hasRootCauseMessage("Recovery heartbeat changed frozen engine state");
        }
    }

    @Test
    void shutdownInvalidatesEveryUnfrozenRecoveryGuard() {
        DurableTestRecoveryHeartbeatService heartbeats =
                mock(DurableTestRecoveryHeartbeatService.class);
        RecoveryValues values = new RecoveryValues();
        when(heartbeats.leaseDuration()).thenReturn(Duration.ofSeconds(3));
        when(heartbeats.renewIssuedDispatch(any(), any(), any()))
                .thenAnswer(invocation -> values.successor(invocation.getArgument(0)));
        DurableTestRecoveryLeaseCoordinator coordinator =
                new DurableTestRecoveryLeaseCoordinator(heartbeats, Duration.ofSeconds(1));
        DurableTestRecoveryLeaseCoordinator.LeaseGuard guard = coordinator.monitor(
                values.sourceDispatch, values.sourceCheckpoint, SHA_A, identity());

        coordinator.close();

        assertThat(guard.held()).isFalse();
        assertThatThrownBy(guard::freeze)
                .isInstanceOf(DurableTestRecoveryLeaseCoordinator.LeaseLostException.class)
                .hasRootCauseMessage(
                        "Durable recovery lease coordinator closed during execution");
    }

    @Test
    void rejectsUnsafeLeaseTimingAndMalformedOperationIdentity() {
        DurableTestRecoveryHeartbeatService heartbeats =
                mock(DurableTestRecoveryHeartbeatService.class);
        when(heartbeats.leaseDuration()).thenReturn(Duration.ofSeconds(2));
        assertThatThrownBy(() -> new DurableTestRecoveryLeaseCoordinator(
                heartbeats, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("three seconds");

        when(heartbeats.leaseDuration()).thenReturn(Duration.ofSeconds(3));
        assertThatThrownBy(() -> new DurableTestRecoveryLeaseCoordinator(
                heartbeats, Duration.ofMillis(1_001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one third");
        assertThatThrownBy(() -> new DurableTestRecoveryLeaseCoordinator(
                heartbeats, Duration.ofNanos(999_999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one millisecond");

        RecoveryValues values = new RecoveryValues();
        when(heartbeats.renewIssuedDispatch(any(), any(), any()))
                .thenAnswer(invocation -> values.successor(invocation.getArgument(0)));
        try (DurableTestRecoveryLeaseCoordinator coordinator =
                     new DurableTestRecoveryLeaseCoordinator(
                             heartbeats, Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> coordinator.monitor(
                    values.sourceDispatch, values.sourceCheckpoint, "bad", identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("operationFingerprint");
        }
    }

    private static boolean awaitLeaseLoss(
            DurableTestRecoveryLeaseCoordinator.LeaseGuard guard)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (guard.held() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return !guard.held();
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD", "worker-a",
                "dispatcher-a", "TEST_EXECUTION", "correlation-a", Set.of("quality"),
                "CONFIDENTIAL", "grant-a");
    }

    private static final class RecoveryValues {
        private final DurableTestRecoveryAuthorization authorization =
                mock(DurableTestRecoveryAuthorization.class);
        private final DurableTestExecutionCheckpoint.ControlDependencies dependencies =
                mock(DurableTestExecutionCheckpoint.ControlDependencies.class);
        private final com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot
                fixture = mock(
                com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot.class);
        private final com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot
                services = mock(
                com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot.class);
        private final DurableTestExecutionCheckpoint.EngineState engine =
                mock(DurableTestExecutionCheckpoint.EngineState.class);
        private final DurableTestExecutionCheckpoint sourceCheckpoint =
                checkpoint(8, SHA_A, engine);
        private final DurableTestRecoveryDispatch sourceDispatch =
                dispatch(sourceCheckpoint, SHA_B);

        private DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult successor(
                DurableTestRecoveryDispatch source) {
            long revision = source.revision() + 1;
            String checkpointFingerprint = revision % 2 == 0 ? SHA_A : SHA_C;
            DurableTestExecutionCheckpoint checkpoint =
                    checkpoint(revision, checkpointFingerprint, engine);
            DurableTestRecoveryDispatch dispatch = dispatch(checkpoint, checkpointFingerprint);
            return new DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult(
                    checkpoint, dispatch, false);
        }

        private DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult
                mutatedSuccessor() {
            DurableTestExecutionCheckpoint checkpoint =
                    checkpoint(9, SHA_C, mock(DurableTestExecutionCheckpoint.EngineState.class));
            DurableTestRecoveryDispatch dispatch = dispatch(checkpoint, SHA_C);
            return new DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult(
                    checkpoint, dispatch, false);
        }

        private DurableTestExecutionCheckpoint checkpoint(
                long revision,
                String checkpointFingerprint,
                DurableTestExecutionCheckpoint.EngineState engineState) {
            DurableTestExecutionCheckpoint checkpoint =
                    mock(DurableTestExecutionCheckpoint.class);
            when(checkpoint.scope()).thenReturn(new DurableTestExecutionCheckpoint.Scope(
                    "tenant-a", "org-a", "project-a", "test", "runner-a"));
            when(checkpoint.runId()).thenReturn("run-a");
            when(checkpoint.engineExecutionId()).thenReturn("engine-a");
            when(checkpoint.dependencies()).thenReturn(dependencies);
            when(checkpoint.fixtureConsumptionState()).thenReturn(fixture);
            when(checkpoint.executionServiceState()).thenReturn(services);
            when(checkpoint.engineState()).thenReturn(engineState);
            when(checkpoint.checkpointFingerprint()).thenReturn(checkpointFingerprint);
            when(checkpoint.lifecycle()).thenReturn(
                    new DurableTestExecutionCheckpoint.Lifecycle(
                            DurableTestExecutionCheckpoint.Status.RESUMING,
                            "recovery-a", 4, revision,
                            Instant.parse("2026-07-17T00:00:00Z"),
                            Instant.parse("2026-07-17T00:00:00Z").plusSeconds(revision),
                            Instant.parse("2026-07-17T01:00:00Z").plusSeconds(revision)));
            return checkpoint;
        }

        private DurableTestRecoveryDispatch dispatch(
                DurableTestExecutionCheckpoint checkpoint,
                String dispatchFingerprint) {
            DurableTestExecutionCheckpoint.Scope scope = checkpoint.scope();
            String runId = checkpoint.runId();
            String engineExecutionId = checkpoint.engineExecutionId();
            DurableTestExecutionCheckpoint.Lifecycle lifecycle = checkpoint.lifecycle();
            String checkpointFingerprint = checkpoint.checkpointFingerprint();
            DurableTestRecoveryDispatch dispatch = mock(DurableTestRecoveryDispatch.class);
            when(dispatch.authorization()).thenReturn(authorization);
            when(dispatch.scope()).thenReturn(scope);
            when(dispatch.runId()).thenReturn(runId);
            when(dispatch.engineExecutionId()).thenReturn(engineExecutionId);
            when(dispatch.ownerId()).thenReturn(lifecycle.ownerId());
            when(dispatch.leaseEpoch()).thenReturn(lifecycle.leaseEpoch());
            when(dispatch.revision()).thenReturn(lifecycle.revision());
            when(dispatch.leaseExpiresAt()).thenReturn(lifecycle.leaseExpiresAt());
            when(dispatch.checkpointFingerprint()).thenReturn(checkpointFingerprint);
            when(dispatch.dispatchFingerprint()).thenReturn(dispatchFingerprint);
            when(dispatch.agreesWith(checkpoint)).thenReturn(true);
            return dispatch;
        }
    }
}
