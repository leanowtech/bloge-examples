package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DurableTestCreationLeaseCoordinatorTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);

    @Test
    void freezesTheLatestRepositoryIssuedFenceAndStopsFutureHeartbeats() throws Exception {
        DurableTestExecutionCheckpointRepository repository =
                mock(DurableTestExecutionCheckpointRepository.class);
        CountDownLatch firstHeartbeat = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(repository.heartbeatInitialCreation(any(), any())).thenAnswer(invocation -> {
            int sequence = calls.incrementAndGet();
            firstHeartbeat.countDown();
            return renewed(invocation.getArgument(0), sequence);
        });

        try (DurableTestCreationLeaseCoordinator coordinator =
                     new DurableTestCreationLeaseCoordinator(
                             repository, "creator-a", Duration.ofSeconds(3),
                             Duration.ofMillis(10));
             DurableTestCreationLeaseCoordinator.LeaseGuard guard =
                     coordinator.monitor(reservation("creator-a"))) {
            assertThat(firstHeartbeat.await(2, TimeUnit.SECONDS)).isTrue();

            var frozen = guard.freeze();
            int callsAtFreeze = calls.get();
            Thread.sleep(35);

            assertThat(frozen.ownerId()).isEqualTo("creator-a");
            assertThat(frozen.leaseEpoch()).isEqualTo(1);
            assertThat(frozen.updatedAt())
                    .isAfter(Instant.parse("2026-07-17T00:00:00Z"));
            assertThat(frozen.recordFingerprint())
                    .isNotEqualTo(reservation("creator-a").recordFingerprint());
            assertThat(calls).hasValue(callsAtFreeze);
            assertThat(guard.held()).isFalse();
        }
    }

    @Test
    void heartbeatFailureMakesOwnershipUncertainAndFreezeFailsClosed() throws Exception {
        DurableTestExecutionCheckpointRepository repository =
                mock(DurableTestExecutionCheckpointRepository.class);
        CountDownLatch attempted = new CountDownLatch(1);
        when(repository.heartbeatInitialCreation(any(), any())).thenAnswer(invocation -> {
            attempted.countDown();
            throw new DurableTestExecutionCheckpointConflictException(
                    DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE,
                    "lost");
        });

        try (DurableTestCreationLeaseCoordinator coordinator =
                     new DurableTestCreationLeaseCoordinator(
                             repository, "creator-a", Duration.ofSeconds(3),
                             Duration.ofMillis(10));
             DurableTestCreationLeaseCoordinator.LeaseGuard guard =
                     coordinator.monitor(reservation("creator-a"))) {
            assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitLeaseLoss(guard)).isTrue();

            assertThatThrownBy(guard::freeze)
                    .isInstanceOf(DurableTestCreationLeaseCoordinator.LeaseLostException.class)
                    .hasMessageContaining("ownership became uncertain");
        }
    }

    @Test
    void rejectsUnsafeTimingPolicyAndForeignReservation() {
        DurableTestExecutionCheckpointRepository repository =
                mock(DurableTestExecutionCheckpointRepository.class);

        assertThatThrownBy(() -> new DurableTestCreationLeaseCoordinator(
                repository, "creator-a", Duration.ofSeconds(2), Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("three seconds");
        assertThatThrownBy(() -> new DurableTestCreationLeaseCoordinator(
                repository, "creator-a", Duration.ofSeconds(3), Duration.ofMillis(1_001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one third");
        assertThatThrownBy(() -> new DurableTestCreationLeaseCoordinator(
                repository, "creator-a", Duration.ofSeconds(3), Duration.ofNanos(999_999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one millisecond");

        try (DurableTestCreationLeaseCoordinator coordinator =
                     new DurableTestCreationLeaseCoordinator(
                             repository, "creator-a", Duration.ofSeconds(3),
                             Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> coordinator.monitor(reservation("creator-b")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("this coordinator's pending");
        }
    }

    @Test
    void coordinatorShutdownInvalidatesEveryUnfrozenGuard() {
        DurableTestExecutionCheckpointRepository repository =
                mock(DurableTestExecutionCheckpointRepository.class);
        DurableTestCreationLeaseCoordinator coordinator =
                new DurableTestCreationLeaseCoordinator(
                        repository, "creator-a", Duration.ofSeconds(3),
                        Duration.ofSeconds(1));
        DurableTestCreationLeaseCoordinator.LeaseGuard guard =
                coordinator.monitor(reservation("creator-a"));

        coordinator.close();

        assertThat(guard.held()).isFalse();
        assertThatThrownBy(guard::freeze)
                .isInstanceOf(DurableTestCreationLeaseCoordinator.LeaseLostException.class)
                .hasMessageContaining("ownership became uncertain")
                .hasRootCauseMessage("Durable creation lease coordinator closed during preparation");
        assertThatThrownBy(() -> coordinator.monitor(reservation("creator-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coordinator is closed");
    }

    private static boolean awaitLeaseLoss(
            DurableTestCreationLeaseCoordinator.LeaseGuard guard)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (guard.held() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return !guard.held();
    }

    private static DurableTestExecutionCheckpointRepository.InitialCreationReservation renewed(
            DurableTestExecutionCheckpointRepository.InitialCreationReservation current,
            int sequence) {
        Instant updatedAt = current.updatedAt().plusSeconds(1);
        return new DurableTestExecutionCheckpointRepository.InitialCreationReservation(
                current.schemaVersion(), current.scope(), current.clientRequestId(),
                current.requestFingerprint(), current.authorizationFingerprint(),
                current.runId(), current.engineExecutionId(), current.ownerId(),
                current.leaseEpoch(), current.createdAt(), updatedAt,
                current.leaseExpiresAt().plusSeconds(1), current.state(), "", "",
                ProtocolFingerprint.ofText("renewed-" + sequence));
    }

    private static DurableTestExecutionCheckpointRepository.InitialCreationReservation reservation(
            String ownerId) {
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        return new DurableTestExecutionCheckpointRepository.InitialCreationReservation(
                DurableTestExecutionCheckpointRepository.InitialCreationReservation.SCHEMA_VERSION,
                new DurableTestExecutionCheckpoint.Scope(
                        "tenant-a", "org-a", "project-a", "test", "runner"),
                "create-1", SHA_A, SHA_B, "run-a", "engine-a", ownerId, 1,
                now, now, now.plusSeconds(3),
                DurableTestExecutionCheckpointRepository.InitialCreationState.PENDING,
                "", "", ProtocolFingerprint.ofText("initial-reservation"));
    }
}
