package com.leanowtech.bloge.gateway.testing.api;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlPlaneCertificateStatusSloMonitorTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private static final ControlPlaneCertificateStatusSloMonitor.Policy POLICY =
            new ControlPlaneCertificateStatusSloMonitor.Policy(
                    30, 60, 20, 4, 2_500, 4, 2_500, 2);

    @Test
    void startupGraceIsInitializingBeforeTheFirstPublication() {
        MutableClock clock = new MutableClock(NOW);
        Harness harness = harness(clock, monitor(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.FLOOR_UNAVAILABLE,
                false, false, 0, 0, NOW), admission(false, false, 0));

        var assessment = harness.slo().assess();

        assertThat(assessment.state()).isEqualTo(
                ControlPlaneCertificateStatusSloMonitor.State.INITIALIZING);
        assertThat(assessment.violations()).isEmpty();
        assertThat(harness.slo().health().getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void freshCacheCanServeWhileSourceOutageViolatesOperationalSlo() {
        MutableClock clock = new MutableClock(NOW);
        Harness harness = harness(clock, monitor(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.SOURCE_UNAVAILABLE,
                false, true, 7, 0, NOW), admission(true, true, 120));
        harness.telemetry().recordRefresh(monitor(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.APPLIED,
                true, true, 7, 1, NOW.minusSeconds(1)), admission(true, true, 120));
        harness.telemetry().recordRefresh(monitor(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.SOURCE_UNAVAILABLE,
                false, true, 7, 0, NOW), admission(true, true, 120));

        var assessment = harness.slo().assess();

        assertThat(assessment.state()).isEqualTo(
                ControlPlaneCertificateStatusSloMonitor.State.SLO_VIOLATED);
        assertThat(assessment.violations()).containsExactly(
                ControlPlaneCertificateStatusSloMonitor.Violation.SOURCE_UNAVAILABLE);
        assertThat(assessment.admissionFresh()).isTrue();
        assertThat(harness.slo().health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    void enforcesHeadroomMatureRatiosAndBoundedCatchUpBacklog() {
        MutableClock clock = new MutableClock(NOW);
        var latestMonitor = monitor(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.BATCH_LIMIT,
                true, true, 9, 2, NOW);
        var cache = admission(true, true, 10);
        Harness harness = harness(clock, latestMonitor, cache);

        harness.telemetry().recordRefresh(monitor(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.SOURCE_UNAVAILABLE,
                false, true, 6, 0, NOW.minusSeconds(4)), cache);
        harness.telemetry().recordRefresh(monitor(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.APPLIED,
                true, true, 7, 1, NOW.minusSeconds(3)), cache);
        for (int index = 0; index < 3; index++) {
            harness.telemetry().recordRefresh(monitor(
                    ControlPlaneCertificateStatusMonitor.RefreshStatus.BATCH_LIMIT,
                    true, true, 8 + index, 2, NOW.minusSeconds(2 - index)), cache);
        }
        harness.telemetry().recordAdmission(
                ControlPlaneCertificateStatusTelemetry.AdmissionDecision.ALLOWED);
        for (int index = 0; index < 3; index++) {
            harness.telemetry().recordAdmission(
                    ControlPlaneCertificateStatusTelemetry.AdmissionDecision.REVOKED);
        }

        var assessment = harness.slo().assess();

        assertThat(assessment.violations()).containsExactly(
                ControlPlaneCertificateStatusSloMonitor.Violation.EXPIRY_HEADROOM_LOW,
                ControlPlaneCertificateStatusSloMonitor.Violation.ADMISSION_DENIAL_RATE_EXCEEDED,
                ControlPlaneCertificateStatusSloMonitor.Violation.CATCH_UP_BACKLOG);
        assertThat(assessment.refreshFailureBasisPoints()).isEqualTo(2_000);
        assertThat(assessment.admissionDenialBasisPoints()).isEqualTo(7_500);
        assertThat(assessment.consecutiveBatchLimitCycles()).isEqualTo(3);
    }

    @Test
    void detectsNeverSucceededAndStaleRefreshAfterStartupGrace() {
        MutableClock neverClock = new MutableClock(NOW);
        Harness never = harness(neverClock, monitor(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.SOURCE_UNAVAILABLE,
                false, false, 0, 0, NOW), admission(false, false, 0));
        neverClock.advance(Duration.ofSeconds(31));

        assertThat(never.slo().assess().violations()).containsExactly(
                ControlPlaneCertificateStatusSloMonitor.Violation.NO_PUBLICATION,
                ControlPlaneCertificateStatusSloMonitor.Violation.SOURCE_UNAVAILABLE,
                ControlPlaneCertificateStatusSloMonitor.Violation.REFRESH_NEVER_SUCCEEDED);

        MutableClock staleClock = new MutableClock(NOW);
        var current = monitor(ControlPlaneCertificateStatusMonitor.RefreshStatus.CURRENT,
                true, true, 1, 0, NOW);
        var cache = admission(true, true, 120);
        Harness stale = harness(staleClock, current, cache);
        stale.telemetry().recordRefresh(current, cache);
        staleClock.advance(Duration.ofSeconds(61));

        assertThat(stale.slo().assess().violations()).containsExactly(
                ControlPlaneCertificateStatusSloMonitor.Violation.REFRESH_SUCCESS_STALE);
    }

    @Test
    void descriptorFailureIsBoundedUnavailableWithoutProviderText() {
        MutableClock clock = new MutableClock(NOW);
        Harness harness = harness(clock, monitor(
                ControlPlaneCertificateStatusMonitor.RefreshStatus.CURRENT,
                true, true, 1, 0, NOW), admission(true, true, 120));
        when(harness.monitor().descriptor()).thenThrow(
                new IllegalStateException("vault://status-source-secret"));

        var assessment = harness.slo().assess();

        assertThat(assessment.state()).isEqualTo(
                ControlPlaneCertificateStatusSloMonitor.State.OBSERVATION_UNAVAILABLE);
        assertThat(assessment.toString()).doesNotContain("vault://", "secret");
        assertThat(harness.slo().health().getDetails()).hasSize(12);
    }

    @Test
    void rejectsUnboundedPolicy() {
        assertThatThrownBy(() -> new ControlPlaneCertificateStatusSloMonitor.Policy(
                3_601, 60, 20, 4, 100, 4, 100, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateStatusSloMonitor.Policy(
                30, 0, 20, 4, 100, 4, 100, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateStatusSloMonitor.Policy(
                30, 60, 20, 0, 100, 4, 100, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateStatusSloMonitor.Policy(
                30, 60, 20, 4, 10_001, 4, 100, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assessmentRejectsForgedRatesStatusesAndPolicyDescriptors() {
        var policy = POLICY.descriptor();
        assertThatThrownBy(() -> new ControlPlaneCertificateStatusSloMonitor.Assessment(
                ControlPlaneCertificateStatusSloMonitor.Assessment.SCHEMA_VERSION,
                ControlPlaneCertificateStatusSloMonitor.State.HEALTHY,
                java.util.List.of(), NOW, "CURRENT", true, true, 1, 120,
                4, 1, 2_501, 4, 1, 2_500, 0, 0, policy))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateStatusSloMonitor.Assessment(
                ControlPlaneCertificateStatusSloMonitor.Assessment.SCHEMA_VERSION,
                ControlPlaneCertificateStatusSloMonitor.State.HEALTHY,
                java.util.List.of(), NOW, "PROVIDER_SECRET", true, true, 1, 120,
                4, 1, 2_500, 4, 1, 2_500, 0, 0, policy))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateStatusSloMonitor.PolicyDescriptor(
                30, 60, 20, 4, 2_500, 4, 2_500, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Harness harness(
            Clock clock,
            ControlPlaneCertificateStatusMonitor.Descriptor monitorDescriptor,
            ControlPlaneCertificateStatusAdmission.Descriptor admissionDescriptor) {
        ControlPlaneCertificateStatusMonitor monitor = mock(
                ControlPlaneCertificateStatusMonitor.class);
        when(monitor.descriptor()).thenReturn(monitorDescriptor);
        ControlPlaneCertificateStatusAdmission admission = mock(
                ControlPlaneCertificateStatusAdmission.class);
        when(admission.descriptor()).thenReturn(admissionDescriptor);
        var telemetry = new ControlPlaneCertificateStatusTelemetry(new SimpleMeterRegistry());
        return new Harness(monitor, telemetry, new ControlPlaneCertificateStatusSloMonitor(
                monitor, admission, telemetry, clock, POLICY));
    }

    private static ControlPlaneCertificateStatusMonitor.Descriptor monitor(
            ControlPlaneCertificateStatusMonitor.RefreshStatus status,
            boolean sourceAvailable,
            boolean admissionFresh,
            long sequence,
            int applied,
            Instant observedAt) {
        return new ControlPlaneCertificateStatusMonitor.Descriptor(
                ControlPlaneCertificateStatusMonitor.Descriptor.SCHEMA_VERSION,
                status, true, sourceAvailable, admissionFresh, sequence, applied,
                observedAt, admissionFresh ? observedAt.plusSeconds(120) : null);
    }

    private static ControlPlaneCertificateStatusAdmission.Descriptor admission(
            boolean loaded,
            boolean fresh,
            long secondsToExpiry) {
        return new ControlPlaneCertificateStatusAdmission.Descriptor(
                ControlPlaneCertificateStatusAdmission.Descriptor.SCHEMA_VERSION,
                loaded, fresh, loaded ? 1 : 0, loaded ? 1 : 0, loaded ? 1 : 0,
                0, 0, secondsToExpiry,
                !loaded ? "NO_PUBLICATION" : fresh ? "FRESH" : "STALE");
    }

    private record Harness(
            ControlPlaneCertificateStatusMonitor monitor,
            ControlPlaneCertificateStatusTelemetry telemetry,
            ControlPlaneCertificateStatusSloMonitor slo) {
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        private void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
