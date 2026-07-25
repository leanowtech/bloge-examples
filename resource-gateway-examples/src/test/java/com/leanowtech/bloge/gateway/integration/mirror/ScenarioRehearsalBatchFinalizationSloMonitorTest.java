package com.leanowtech.bloge.gateway.integration.mirror;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioRehearsalBatchFinalizationSloMonitorTest {
    private static final Instant NOW =
            Instant.parse("2026-07-25T08:00:00Z");

    @Test
    void projectsQuarantineAsDegradedWithoutFailingReadiness() {
        ScenarioRehearsalBatchRepository repository =
                mock(ScenarioRehearsalBatchRepository.class);
        when(repository.finalizationPartitionHealth(
                "sg", "test"))
                .thenReturn(snapshot(
                        0, 0, 0, 1, 0,
                        0, 0, 0, 0,
                        0, 0, 1, 0,
                        NOW.minusSeconds(120),
                        null,
                        NOW.minusSeconds(60),
                        null));
        SimpleMeterRegistry meters =
                new SimpleMeterRegistry();
        ScenarioRehearsalBatchFinalizationSloMonitor monitor =
                monitor(repository, meters);

        monitor.refresh();

        assertThat(monitor.assessment().state()).isEqualTo(
                ScenarioRehearsalBatchFinalizationHealth
                        .State.DEGRADED);
        assertThat(monitor.assessment().violations())
                .containsExactly(
                        ScenarioRehearsalBatchFinalizationHealth
                                .Violation.QUARANTINE_PRESENT,
                        ScenarioRehearsalBatchFinalizationHealth
                                .Violation
                                .NON_RETRYABLE_FAILURE_PRESENT);
        assertThat(monitor.health().getStatus())
                .isEqualTo(Status.UP);
        assertThat(meters.get(
                        "resource.gateway.mirror.scenario.batch.finalization.states")
                .tag("state", "quarantined")
                .gauge().value()).isEqualTo(1);
        assertThat(meters.get(
                        "resource.gateway.mirror.scenario.batch.finalization.health")
                .tag("state", "degraded")
                .gauge().value()).isEqualTo(1);
    }

    @Test
    void failsReadinessForStaleSigningAndStoreUnavailability() {
        ScenarioRehearsalBatchRepository repository =
                mock(ScenarioRehearsalBatchRepository.class);
        when(repository.finalizationPartitionHealth(
                "sg", "test"))
                .thenReturn(snapshot(
                        0, 1, 0, 0, 0,
                        1, 1, 0, 0,
                        0, 0, 0, 0,
                        NOW.minusSeconds(600),
                        NOW.minusSeconds(301),
                        null,
                        null))
                .thenThrow(new IllegalStateException(
                        "database unavailable"));
        SimpleMeterRegistry meters =
                new SimpleMeterRegistry();
        ScenarioRehearsalBatchFinalizationSloMonitor monitor =
                monitor(repository, meters);

        monitor.refresh();
        assertThat(monitor.assessment().state()).isEqualTo(
                ScenarioRehearsalBatchFinalizationHealth
                        .State.CRITICAL);
        assertThat(monitor.assessment().violations())
                .contains(
                        ScenarioRehearsalBatchFinalizationHealth
                                .Violation
                                .ELIGIBLE_BACKLOG_STALE,
                        ScenarioRehearsalBatchFinalizationHealth
                                .Violation.STALE_SIGNING_LEASE);
        assertThat(monitor.health().getStatus())
                .isEqualTo(Status.OUT_OF_SERVICE);

        monitor.refresh();
        assertThat(monitor.assessment().state()).isEqualTo(
                ScenarioRehearsalBatchFinalizationHealth
                        .State.UNAVAILABLE);
        assertThat(monitor.health().getStatus())
                .isEqualTo(Status.DOWN);
        assertThat(meters.get(
                        "resource.gateway.mirror.scenario.batch.finalization.health")
                .tag("state", "unavailable")
                .gauge().value()).isEqualTo(1);
        assertThat(meters.get(
                        "resource.gateway.mirror.scenario.batch.finalization.states")
                .tag("state", "signing")
                .gauge().value()).isZero();
    }

    @Test
    void includesExactScopeAndServerThresholdsInHealthyProtocol() {
        CapabilitySnapshot.Scope scope =
                new CapabilitySnapshot.Scope(
                        "tenant-a",
                        "org-a",
                        "support",
                        "test",
                        "sg");

        ScenarioRehearsalBatchFinalizationHealth health =
                ScenarioRehearsalBatchFinalizationHealth.from(
                        scope,
                        snapshot(
                                0, 0, 0, 0, 0,
                                0, 0, 0, 0,
                                0, 0, 0, 0,
                                null, null, null, null),
                        ScenarioRehearsalBatchFinalizationHealthPolicy
                                .defaults());

        assertThat(health.schemaVersion()).isEqualTo(
                ScenarioRehearsalBatchFinalizationHealth
                        .SCHEMA_VERSION);
        assertThat(health.scope()).isEqualTo(scope);
        assertThat(health.state()).isEqualTo(
                ScenarioRehearsalBatchFinalizationHealth
                        .State.HEALTHY);
        assertThat(health.violations()).isEmpty();
        assertThat(health.thresholds()
                .maximumEligibleBacklog()).isEqualTo(100);
    }

    private static ScenarioRehearsalBatchFinalizationSloMonitor
    monitor(
            ScenarioRehearsalBatchRepository repository,
            SimpleMeterRegistry meters) {
        return new ScenarioRehearsalBatchFinalizationSloMonitor(
                repository,
                new ScenarioRehearsalBatchFinalizationHealthTelemetry(
                        meters),
                ScenarioRehearsalBatchFinalizationHealthPolicy
                        .defaults(),
                "sg",
                "test");
    }

    private static ScenarioRehearsalBatchRepository
            .FinalizationHealthSnapshot
    snapshot(
            long pending,
            long signing,
            long retryWait,
            long quarantined,
            long finalized,
            long eligible,
            long staleSigning,
            long inconsistent,
            long policyMismatch,
            long signerUnavailable,
            long signatureInvalid,
            long materialInvalid,
            long controlUnavailable,
            Instant oldestUnfinalized,
            Instant oldestEligible,
            Instant oldestQuarantined,
            Instant oldestActiveSigning) {
        return new ScenarioRehearsalBatchRepository
                .FinalizationHealthSnapshot(
                NOW,
                1,
                pending + signing + retryWait
                        + quarantined + finalized,
                pending,
                signing,
                retryWait,
                quarantined,
                finalized,
                0,
                eligible,
                staleSigning,
                inconsistent,
                policyMismatch,
                signerUnavailable,
                signatureInvalid,
                materialInvalid,
                controlUnavailable,
                signing + retryWait + quarantined > 0
                        ? 1 : 0,
                oldestUnfinalized,
                oldestEligible,
                oldestQuarantined,
                oldestActiveSigning);
    }
}
