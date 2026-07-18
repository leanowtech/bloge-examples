package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityLeaseCoordinatorTest {

    @Test
    void terminalCheckpointPerformsSynchronousRenewalAndConsumedLeaseIsNotReleased() {
        TestSuiteStabilityRunRepository repository = mock(TestSuiteStabilityRunRepository.class);
        TestSuiteStabilityExecutionLease initial = lease("owner-a", 0, Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease renewed = lease("owner-a", 0, Instant.now().plusSeconds(60));
        when(repository.renew(initial, Duration.ofSeconds(30)))
                .thenReturn(Optional.of(renewed));
        TestSuiteStabilityLeaseCoordinator coordinator =
                TestSuiteStabilityLeaseCoordinator.passive(
                        repository, Duration.ofSeconds(30));

        try (TestSuiteStabilityLeaseCoordinator.LeaseGuard guard =
                     coordinator.monitor(initial)) {
            assertThat(guard.checkpoint()).isEqualTo(renewed);
            guard.consumed();
        }

        verify(repository).renew(initial, Duration.ofSeconds(30));
        verify(repository, never()).release(renewed);
    }

    @Test
    void ambiguousRenewalPermanentlyLosesTheLocalGuardAndCloseCannotMaskIt() {
        TestSuiteStabilityRunRepository repository = mock(TestSuiteStabilityRunRepository.class);
        TestSuiteStabilityExecutionLease initial = lease("owner-a", 0, Instant.now().plusSeconds(30));
        when(repository.renew(initial, Duration.ofSeconds(30))).thenReturn(Optional.empty());
        TestSuiteStabilityLeaseCoordinator coordinator =
                TestSuiteStabilityLeaseCoordinator.passive(
                        repository, Duration.ofSeconds(30));

        try (TestSuiteStabilityLeaseCoordinator.LeaseGuard guard =
                     coordinator.monitor(initial)) {
            assertThatThrownBy(guard::checkpoint)
                    .isInstanceOf(TestSuiteStabilityLeaseCoordinator.LeaseLostException.class);
            assertThatThrownBy(guard::checkpoint)
                    .isInstanceOf(TestSuiteStabilityLeaseCoordinator.LeaseLostException.class);
        }

        verify(repository).release(initial);
    }

    @Test
    void configurationRejectsHeartbeatThatCannotRenewBeforeOneThirdLease() {
        TestSuiteStabilityRunRepository repository = mock(TestSuiteStabilityRunRepository.class);

        assertThatThrownBy(() -> new TestSuiteStabilityLeaseCoordinator(
                repository, "replica-a", Duration.ofSeconds(30), Duration.ofSeconds(11)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one-third");
        assertThatThrownBy(() -> new TestSuiteStabilityLeaseCoordinator(
                repository, "replica-a", Duration.ofSeconds(4), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 to 3600");
    }

    @Test
    void claimRequestsUseFreshInvocationOwnersButOneConfiguredDuration() {
        TestSuiteStabilityRunRepository repository = mock(TestSuiteStabilityRunRepository.class);
        TestSuiteStabilityLeaseCoordinator coordinator =
                TestSuiteStabilityLeaseCoordinator.passive(
                        repository, Duration.ofSeconds(30));

        TestSuiteStabilityLeaseRequest first = request(coordinator);
        TestSuiteStabilityLeaseRequest second = request(coordinator);

        assertThat(first.ownerId()).isNotEqualTo(second.ownerId());
        assertThat(first.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(second.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(first.progressRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(first.plannedAttempts()).isEqualTo(3);
    }

    @Test
    void progressCheckpointAtomicallyAdvancesTheJournalAndTheGuardFence() {
        TestSuiteStabilityRunRepository repository = mock(TestSuiteStabilityRunRepository.class);
        Instant now = Instant.now();
        TestSuiteStabilityExecutionLease initial = lease(
                "owner-a", 0, now.plusSeconds(30));
        TestSuiteStabilityExecutionLease renewed = lease(
                "owner-a", 0, now.plusSeconds(60));
        TestSuiteStabilityExecutionProgress progress = progress(now);
        TestSuiteStabilityExecutionProgress.AttemptReference attempt =
                new TestSuiteStabilityExecutionProgress.AttemptReference(
                        1, "suite-run-1", fingerprint('c'));
        TestSuiteStabilityExecutionProgress successor = progress.append(
                attempt, now.plusSeconds(1), now.plus(Duration.ofDays(30)));
        when(repository.checkpoint(initial, attempt, Duration.ofSeconds(30),
                Duration.ofDays(30))).thenReturn(
                new TestSuiteStabilityProgressCheckpoint(renewed, successor));
        when(repository.renew(renewed, Duration.ofSeconds(30)))
                .thenReturn(Optional.of(renewed));
        TestSuiteStabilityLeaseCoordinator coordinator =
                TestSuiteStabilityLeaseCoordinator.passive(
                        repository, Duration.ofSeconds(30));

        try (TestSuiteStabilityLeaseCoordinator.LeaseGuard guard =
                     coordinator.monitor(initial)) {
            assertThat(guard.checkpoint(attempt, Duration.ofDays(30)))
                    .isEqualTo(successor);
            assertThat(guard.checkpoint()).isEqualTo(renewed);
        }

        verify(repository).checkpoint(initial, attempt, Duration.ofSeconds(30),
                Duration.ofDays(30));
        verify(repository).renew(renewed, Duration.ofSeconds(30));
    }

    @Test
    void coordinatorShutdownInvalidatesEveryActiveGuardBeforeReleasingItsLease() {
        TestSuiteStabilityRunRepository repository = mock(TestSuiteStabilityRunRepository.class);
        TestSuiteStabilityExecutionLease initial = lease(
                "owner-a", 0, Instant.now().plusSeconds(30));
        TestSuiteStabilityLeaseCoordinator coordinator =
                TestSuiteStabilityLeaseCoordinator.passive(
                        repository, Duration.ofSeconds(30));
        TestSuiteStabilityLeaseCoordinator.LeaseGuard guard = coordinator.monitor(initial);

        coordinator.close();

        assertThatThrownBy(guard::checkpoint)
                .isInstanceOf(TestSuiteStabilityLeaseCoordinator.LeaseLostException.class);
        assertThatThrownBy(() -> coordinator.monitor(initial))
                .isInstanceOf(TestSuiteStabilityLeaseCoordinator.LeaseLostException.class);
        assertThatThrownBy(() -> coordinator.request(
                runId(), "tenant-a", "test", "request-a", fingerprint('a'), suiteRef(),
                "INTERNAL", 3, Duration.ofDays(30)))
                .isInstanceOf(TestSuiteStabilityLeaseCoordinator.LeaseLostException.class);
        verify(repository).release(initial);
    }

    private static TestSuiteStabilityExecutionLease lease(
            String owner,
            long epoch,
            Instant expiresAt) {
        return new TestSuiteStabilityExecutionLease(runId(), "tenant-a", "test", "request-a",
                fingerprint('a'), owner, epoch, expiresAt);
    }

    private static TestSuiteStabilityLeaseRequest request(
            TestSuiteStabilityLeaseCoordinator coordinator) {
        return coordinator.request(runId(), "tenant-a", "test", "request-a",
                fingerprint('a'), suiteRef(), "INTERNAL", 3, Duration.ofDays(30));
    }

    private static TestSuiteStabilityExecutionProgress progress(Instant now) {
        return new TestSuiteStabilityExecutionProgress(runId(), "tenant-a", "test",
                "request-a", fingerprint('a'), suiteRef(), "INTERNAL", 3, List.of(),
                now, now, now.plus(Duration.ofDays(30)));
    }

    private static TestSuiteExecutionRequest.SuiteRef suiteRef() {
        return new TestSuiteExecutionRequest.SuiteRef("orders-suite", 7, fingerprint('b'));
    }

    private static String runId() {
        return "stability-" + "1".repeat(64);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
