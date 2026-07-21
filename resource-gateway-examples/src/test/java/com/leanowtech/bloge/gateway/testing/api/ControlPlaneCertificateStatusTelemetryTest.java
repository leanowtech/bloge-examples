package com.leanowtech.bloge.gateway.testing.api;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ControlPlaneCertificateStatusTelemetryTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @Test
    void registersOnlyTheCompleteFixedCardinalityVocabulary() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var telemetry = new ControlPlaneCertificateStatusTelemetry(meters);
        var monitor = monitor(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.SOURCE_UNAVAILABLE,
                false, 17, NOW);
        var admission = admission(true, 17, 12, 10, 1, 1, 45);

        telemetry.recordRefresh(monitor, admission);
        telemetry.recordAdmission(
                ControlPlaneCertificateStatusTelemetry.AdmissionDecision.REVOKED);
        telemetry.observe(violated());

        assertThat(meters.getMeters()).hasSize(51);
        assertThat(gauge(meters, "sequence")).isEqualTo(17D);
        assertThat(gauge(meters, "source.available")).isZero();
        assertThat(gauge(meters, "source.head.verified")).isOne();
        assertThat(gauge(meters, "source.head.sequence")).isEqualTo(17D);
        assertThat(gauge(meters, "source.head.lag")).isZero();
        assertThat(gauge(meters, "source.head.seconds.to.expiry")).isEqualTo(60D);
        assertThat(gauge(meters, "admission.fresh")).isOne();
        assertThat(gauge(meters, "admission.seconds.to.expiry")).isEqualTo(45D);
        assertThat(gauge(meters, "targets", "status", "good")).isEqualTo(10D);
        assertThat(gauge(meters, "slo.health")).isEqualTo(-1D);
        assertThat(gauge(meters, "slo.violation", "code", "source_unavailable"))
                .isOne();
        assertThat(gauge(meters, "slo.violation", "code", "admission_stale"))
                .isZero();
        assertThat(meters.get(ControlPlaneCertificateStatusTelemetry.PREFIX
                        + "refresh.attempts")
                .tag("result", "source_unavailable").counter().count()).isOne();
        assertThat(meters.get(ControlPlaneCertificateStatusTelemetry.PREFIX
                        + "admission.checks")
                .tag("decision", "revoked").counter().count()).isOne();
        assertThat(telemetry.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.refreshAttempts()).isOne();
            assertThat(snapshot.refreshFailures()).isOne();
            assertThat(snapshot.admissionChecks()).isOne();
            assertThat(snapshot.admissionDenials()).isOne();
        });

        String meterIdentity = meters.getMeters().stream()
                .map(meter -> meter.getId().toString()).toList().toString();
        assertThat(meterIdentity).doesNotContain(
                "target-a", "sha256:", "authority-a", "https://", "credential",
                "secret", "exception");
    }

    @Test
    void consecutiveBatchLimitAndClockRollbackRemainBounded() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var telemetry = new ControlPlaneCertificateStatusTelemetry(meters);
        var admission = admission(true, 1, 1, 1, 0, 0, 60);

        telemetry.recordRefresh(monitor(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.BATCH_LIMIT,
                true, 1, NOW), admission);
        telemetry.recordRefresh(monitor(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.BATCH_LIMIT,
                true, 2, NOW.minusSeconds(60)), admission);
        assertThat(telemetry.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.consecutiveBatchLimitCycles()).isEqualTo(2);
            assertThat(snapshot.lastRefreshAt()).isEqualTo(NOW);
            assertThat(snapshot.lastSuccessfulRefreshAt()).isEqualTo(NOW);
        });

        telemetry.recordRefresh(monitor(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.CURRENT,
                true, 2, NOW.plusSeconds(1)), admission);
        assertThat(telemetry.snapshot().consecutiveBatchLimitCycles()).isZero();
    }

    @Test
    void noopNeverChangesLegacyControlFlow() {
        assertThatCode(() -> {
            ControlPlaneCertificateStatusTelemetry noop =
                    ControlPlaneCertificateStatusTelemetry.noop();
            noop.recordRefresh(null, null);
            noop.recordAdmission(null);
            noop.observe(null);
            assertThat(noop.snapshot().refreshAttempts()).isZero();
        }).doesNotThrowAnyException();
    }

    private static ControlPlaneCertificateStatusMonitor.Descriptor monitor(
            ControlPlaneCertificateStatusMonitor.RefreshStatus status,
            boolean sourceAvailable,
            long sequence,
            Instant observedAt) {
        return new ControlPlaneCertificateStatusMonitor.Descriptor(
                ControlPlaneCertificateStatusMonitor.Descriptor.SCHEMA_VERSION,
                status, true, sourceAvailable, true, sequence, 0, observedAt,
                observedAt.plusSeconds(60), true, sequence, 0,
                observedAt.plusSeconds(60));
    }

    private static ControlPlaneCertificateStatusAdmission.Descriptor admission(
            boolean fresh,
            long sequence,
            int targetCount,
            int good,
            int revoked,
            int unknown,
            long secondsToExpiry) {
        return new ControlPlaneCertificateStatusAdmission.Descriptor(
                ControlPlaneCertificateStatusAdmission.Descriptor.SCHEMA_VERSION,
                true, fresh, sequence, targetCount, good, revoked, unknown,
                secondsToExpiry, fresh ? "FRESH" : "STALE");
    }

    private static ControlPlaneCertificateStatusSloMonitor.Assessment violated() {
        var policy = new ControlPlaneCertificateStatusSloMonitor.Policy(
                30, 60, 30, 10, 500, 10, 500, 2);
        return new ControlPlaneCertificateStatusSloMonitor.Assessment(
                ControlPlaneCertificateStatusSloMonitor.Assessment.SCHEMA_VERSION,
                ControlPlaneCertificateStatusSloMonitor.State.SLO_VIOLATED,
                List.of(ControlPlaneCertificateStatusSloMonitor.Violation.SOURCE_UNAVAILABLE),
                NOW, "SOURCE_UNAVAILABLE", false, true, 17,
                true, 17, 0, 45,
                1, 1, 10_000, 1, 1, 10_000, 0, -1, policy.descriptor());
    }

    private static double gauge(SimpleMeterRegistry meters, String suffix) {
        return meters.get(ControlPlaneCertificateStatusTelemetry.PREFIX + suffix)
                .gauge().value();
    }

    private static double gauge(
            SimpleMeterRegistry meters,
            String suffix,
            String tagName,
            String tagValue) {
        return meters.get(ControlPlaneCertificateStatusTelemetry.PREFIX + suffix)
                .tag(tagName, tagValue).gauge().value();
    }
}
