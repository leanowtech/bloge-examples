package com.leanowtech.bloge.gateway.testing.api;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateStatusAdmissionTest {

    private static final String TARGET = "recovery-fleet.inventory";
    private static final String FINGERPRINT = fingerprint('a');
    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    @Test
    void admitsOnlyTheExactFreshTargetGenerationAndSettingsIdentity() {
        MutableClock clock = new MutableClock(NOW);
        AtomicLong ticker = new AtomicLong(1_000);
        var admission = new ControlPlaneCertificateStatusAdmission(clock, ticker::get);
        admission.refresh(snapshot(1, FINGERPRINT, NOW, NOW.plusSeconds(60),
                target(7, FINGERPRINT, status(
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD))));

        assertThat(admission.servingPermitted(TARGET, 7, FINGERPRINT)).isTrue();
        assertThat(admission.gate(TARGET).servingPermitted(7, FINGERPRINT)).isTrue();
        assertThat(admission.servingPermitted(TARGET, 6, FINGERPRINT)).isFalse();
        assertThat(admission.servingPermitted(TARGET, 7, fingerprint('b'))).isFalse();
        assertThat(admission.servingPermitted("other-target", 7, FINGERPRINT)).isFalse();
        assertThat(admission.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.loaded()).isTrue();
            assertThat(descriptor.fresh()).isTrue();
            assertThat(descriptor.goodTargetCount()).isEqualTo(1);
            assertThat(descriptor.revokedTargetCount()).isZero();
            assertThat(descriptor.reasonCode()).isEqualTo("FRESH");
        });
    }

    @Test
    void revokedOrUnknownCertificateOnEitherRoleFailsClosed() {
        var revoked = new ControlPlaneCertificateStatusAdmission(
                Clock.fixed(NOW, ZoneId.of("UTC")), () -> 1);
        revoked.refresh(snapshot(1, FINGERPRINT, NOW, NOW.plusSeconds(60),
                target(1, FINGERPRINT, status(
                        ControlPlaneCertificateStatusPublication.CertificateStatus.REVOKED,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD))));
        var unknown = new ControlPlaneCertificateStatusAdmission(
                Clock.fixed(NOW, ZoneId.of("UTC")), () -> 1);
        unknown.refresh(snapshot(1, FINGERPRINT, NOW, NOW.plusSeconds(60),
                target(1, FINGERPRINT, status(
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.UNKNOWN))));

        assertThat(revoked.servingPermitted(TARGET, 1, FINGERPRINT)).isFalse();
        assertThat(revoked.descriptor().revokedTargetCount()).isEqualTo(1);
        assertThat(unknown.servingPermitted(TARGET, 1, FINGERPRINT)).isFalse();
        assertThat(unknown.descriptor().unknownTargetCount()).isEqualTo(1);
    }

    @Test
    void wallClockExpiryIsIrreversibleEvenAfterClockRollback() {
        MutableClock clock = new MutableClock(NOW);
        AtomicLong ticker = new AtomicLong(10);
        var admission = new ControlPlaneCertificateStatusAdmission(clock, ticker::get);
        var snapshot = snapshot(1, FINGERPRINT, NOW, NOW.plusSeconds(60),
                target(1, FINGERPRINT, good()));
        admission.refresh(snapshot);

        clock.advance(Duration.ofSeconds(61));
        assertThat(admission.servingPermitted(TARGET, 1, FINGERPRINT)).isFalse();
        clock.advance(Duration.ofSeconds(-120));
        assertThat(admission.servingPermitted(TARGET, 1, FINGERPRINT)).isFalse();
        assertThat(admission.descriptor().reasonCode()).isEqualTo("STALE");
    }

    @Test
    void monotonicDeadlineAndExactReplayCannotBeExtendedByWallClockRollback() {
        MutableClock clock = new MutableClock(NOW);
        AtomicLong ticker = new AtomicLong(10);
        var admission = new ControlPlaneCertificateStatusAdmission(clock, ticker::get);
        var snapshot = snapshot(1, FINGERPRINT, NOW, NOW.plusSeconds(60),
                target(1, FINGERPRINT, good()));
        admission.refresh(snapshot);
        ticker.addAndGet(Duration.ofSeconds(30).toNanos());
        clock.advance(Duration.ofSeconds(-30));

        admission.refresh(snapshot);
        ticker.addAndGet(Duration.ofSeconds(31).toNanos());

        assertThat(admission.servingPermitted(TARGET, 1, FINGERPRINT)).isFalse();
    }

    @Test
    void newerPublicationCanReopenAClosedCacheButRollbackAndForkCannot() {
        MutableClock clock = new MutableClock(NOW);
        AtomicLong ticker = new AtomicLong(10);
        var admission = new ControlPlaneCertificateStatusAdmission(clock, ticker::get);
        admission.refresh(snapshot(2, fingerprint('b'), NOW, NOW.plusSeconds(1),
                target(2, fingerprint('b'), good())));
        clock.advance(Duration.ofSeconds(2));
        assertThat(admission.servingPermitted(TARGET, 2, fingerprint('b'))).isFalse();

        assertThatThrownBy(() -> admission.refresh(snapshot(1, FINGERPRINT, NOW,
                NOW.plusSeconds(60), target(1, FINGERPRINT, good()))))
                .hasMessageContaining("rollback");
        assertThatThrownBy(() -> admission.refresh(snapshot(2, fingerprint('c'), NOW,
                NOW.plusSeconds(60), target(2, fingerprint('b'), good()))))
                .hasMessageContaining("fork");

        admission.refresh(snapshot(3, fingerprint('d'), clock.instant(),
                clock.instant().plusSeconds(60), target(3, fingerprint('d'), good())));
        assertThat(admission.servingPermitted(TARGET, 3, fingerprint('d'))).isTrue();
    }

    @Test
    void databaseObservationTimeCapsCacheLifetimeWhenLocalClockLags() {
        MutableClock clock = new MutableClock(NOW.minusSeconds(3600));
        AtomicLong ticker = new AtomicLong(10);
        var admission = new ControlPlaneCertificateStatusAdmission(clock, ticker::get);
        admission.refresh(snapshot(1, FINGERPRINT, NOW, NOW.plusSeconds(60),
                target(1, FINGERPRINT, good())));

        ticker.addAndGet(Duration.ofSeconds(61).toNanos());

        assertThat(admission.servingPermitted(TARGET, 1, FINGERPRINT)).isFalse();
    }

    @Test
    void exportsEveryClosedDecisionWithoutTargetOrCertificateTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var telemetry = new ControlPlaneCertificateStatusTelemetry(meters);
        MutableClock clock = new MutableClock(NOW);
        var admission = new ControlPlaneCertificateStatusAdmission(
                clock, () -> 1, telemetry);

        admission.servingPermitted(TARGET, 1, FINGERPRINT);
        admission.refresh(snapshot(1, FINGERPRINT, NOW, NOW.plusSeconds(60),
                target(1, FINGERPRINT, good())));
        admission.servingPermitted(TARGET, 1, FINGERPRINT);
        admission.servingPermitted("missing-target", 1, FINGERPRINT);
        admission.servingPermitted(TARGET, 2, FINGERPRINT);
        admission.servingPermitted(TARGET, 1, fingerprint('b'));

        var revoked = new ControlPlaneCertificateStatusAdmission(
                clock, () -> 1, telemetry);
        revoked.refresh(snapshot(1, fingerprint('b'), NOW, NOW.plusSeconds(60),
                target(1, FINGERPRINT, status(
                        ControlPlaneCertificateStatusPublication.CertificateStatus.REVOKED,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD))));
        revoked.servingPermitted(TARGET, 1, FINGERPRINT);
        var unknown = new ControlPlaneCertificateStatusAdmission(
                clock, () -> 1, telemetry);
        unknown.refresh(snapshot(1, fingerprint('c'), NOW, NOW.plusSeconds(60),
                target(1, FINGERPRINT, status(
                        ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                        ControlPlaneCertificateStatusPublication.CertificateStatus.UNKNOWN))));
        unknown.servingPermitted(TARGET, 1, FINGERPRINT);
        clock.advance(Duration.ofSeconds(61));
        admission.servingPermitted(TARGET, 1, FINGERPRINT);

        for (ControlPlaneCertificateStatusTelemetry.AdmissionDecision decision
                : ControlPlaneCertificateStatusTelemetry.AdmissionDecision.values()) {
            assertThat(meters.get(ControlPlaneCertificateStatusTelemetry.PREFIX
                            + "admission.checks")
                    .tag("decision", decision.name().toLowerCase())
                    .counter().count()).isOne();
        }
        assertThat(telemetry.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.admissionChecks()).isEqualTo(8);
            assertThat(snapshot.admissionDenials()).isEqualTo(7);
        });
        String meterIdentity = meters.getMeters().stream()
                .map(meter -> meter.getId().toString()).toList().toString();
        assertThat(meterIdentity).doesNotContain(
                TARGET, "missing-target", FINGERPRINT, "authority",
                "https://", "secret");
    }

    private static ControlPlaneCertificateStatusFloor.Snapshot snapshot(
            long sequence,
            String publicationFingerprint,
            Instant observedAt,
            Instant expiresAt,
            ControlPlaneCertificateStatusPublication.TargetStatus target) {
        return new ControlPlaneCertificateStatusFloor.Snapshot(
                ControlPlaneCertificateStatusFloor.Snapshot.SCHEMA_VERSION,
                "rg-staging", 0, fingerprint('0'), sequence, "status-" + sequence,
                publicationFingerprint, observedAt.minusSeconds(1), expiresAt, observedAt,
                List.of(target));
    }

    private static ControlPlaneCertificateStatusPublication.TargetStatus target(
            long generation,
            String settingsFingerprint,
            List<ControlPlaneCertificateStatusPublication.CertificateEvidence> evidence) {
        return new ControlPlaneCertificateStatusPublication.TargetStatus(
                TARGET, generation, settingsFingerprint, evidence);
    }

    private static List<ControlPlaneCertificateStatusPublication.CertificateEvidence> good() {
        return status(ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD);
    }

    private static List<ControlPlaneCertificateStatusPublication.CertificateEvidence> status(
            ControlPlaneCertificateStatusPublication.CertificateStatus client,
            ControlPlaneCertificateStatusPublication.CertificateStatus server) {
        return List.of(evidence(ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT,
                client), evidence(ControlPlaneCertificateStatusPublication.CertificateRole.SERVER,
                server));
    }

    private static ControlPlaneCertificateStatusPublication.CertificateEvidence evidence(
            ControlPlaneCertificateStatusPublication.CertificateRole role,
            ControlPlaneCertificateStatusPublication.CertificateStatus status) {
        return new ControlPlaneCertificateStatusPublication.CertificateEvidence(
                role, status, ControlPlaneCertificateStatusPublication.EvidenceType.OCSP,
                role == ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT
                        ? fingerprint('c') : fingerprint('d'),
                fingerprint('e'), fingerprint('f'),
                switch (status) {
                    case GOOD -> "CERTIFICATE_GOOD";
                    case REVOKED -> "KEY_COMPROMISE";
                    case UNKNOWN -> "STATUS_UNKNOWN";
                }, NOW, NOW, NOW.plusSeconds(7200));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
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
