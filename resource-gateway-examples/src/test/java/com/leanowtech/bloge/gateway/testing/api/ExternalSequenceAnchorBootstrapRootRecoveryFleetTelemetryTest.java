package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Assessment;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Policy;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.State;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Violation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetryTest {

    private static final String PREFIX =
            ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry.PREFIX;
    private static final Policy POLICY = new Policy(
            Duration.ofSeconds(30), Duration.ofSeconds(30), 20, 500, 500, 1_000);

    @Test
    void registersOnlyTheCompleteFixedCardinalityVocabulary() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var telemetry = new ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry(registry);

        telemetry.observe(violated());

        assertThat(registry.getMeters()).hasSize(41);
        assertThat(value(registry, PREFIX + "health")).isEqualTo(-1D);
        assertThat(value(registry, PREFIX + "status", "status", "ready")).isOne();
        assertThat(value(registry, PREFIX + "status", "status", "scheduler_failed"))
                .isZero();
        assertThat(value(registry, PREFIX + "violation", "code",
                "poll_failure_rate_exceeded")).isOne();
        assertThat(value(registry, PREFIX + "violation", "code",
                "runtime_closed")).isZero();
        assertThat(value(registry, PREFIX + "inventory.generation")).isEqualTo(17D);
        assertThat(value(registry, PREFIX + "inventory.lanes")).isEqualTo(2D);
        assertThat(value(registry, PREFIX + "polls", "outcome", "total"))
                .isEqualTo(20D);
        assertThat(value(registry, PREFIX + "polls", "outcome", "completed"))
                .isEqualTo(18D);
        assertThat(value(registry, PREFIX + "polls", "outcome", "failed"))
                .isEqualTo(2D);
        assertThat(value(registry, PREFIX + "cycles", "outcome", "failed"))
                .isEqualTo(1D);
        assertThat(value(registry, PREFIX + "lanes", "outcome", "attempted"))
                .isEqualTo(20D);
        assertThat(value(registry, PREFIX + "failure.ratio.basis.points",
                "scope", "poll")).isEqualTo(1_000D);
        assertThat(value(registry, PREFIX + "failure.ratio.basis.points",
                "scope", "cycle")).isEqualTo(500D);
        assertThat(value(registry, PREFIX + "failure.ratio.basis.points",
                "scope", "lane")).isEqualTo(1_500D);
        assertThat(value(registry, PREFIX + "last.success.age.millis"))
                .isEqualTo(1_000D);

        String meterIdentity = registry.getMeters().stream()
                .map(meter -> meter.getId().getName() + meter.getId().getTags())
                .toList().toString();
        assertThat(meterIdentity).doesNotContain(
                "fleet-sensitive", "worker-sensitive", "tenant-a", "sha256:",
                "https://", "payload", "exception", "credential", "secret");
    }

    @Test
    void unavailableObservationReplacesAllValueGaugesWithUnknownSentinel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var telemetry = new ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry(registry);
        telemetry.observe(violated());

        telemetry.observe(unavailable());

        assertThat(value(registry, PREFIX + "health")).isEqualTo(-3D);
        assertThat(value(registry, PREFIX + "status", "status", "unavailable"))
                .isOne();
        assertThat(value(registry, PREFIX + "violation", "code",
                "observation_unavailable")).isOne();
        assertThat(value(registry, PREFIX + "inventory.generation")).isEqualTo(-1D);
        assertThat(value(registry, PREFIX + "polls", "outcome", "total"))
                .isEqualTo(-1D);
        assertThat(value(registry, PREFIX + "failure.ratio.basis.points",
                "scope", "lane")).isEqualTo(-1D);
        assertThat(value(registry, PREFIX + "last.success.age.millis"))
                .isEqualTo(-1D);
    }

    @Test
    void noopAcceptsLegacyAndIsolatedTestCallsWithoutMeters() {
        assertThatCode(() -> ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry
                .noop().observe(null)).doesNotThrowAnyException();
    }

    private static Assessment violated() {
        return new Assessment(Assessment.SCHEMA_VERSION, State.SLO_VIOLATED,
                List.of(Violation.POLL_FAILURE_RATE_EXCEEDED),
                Instant.parse("2026-07-21T12:00:00Z"), Status.READY,
                17, 2, 20, 18, 2, 1_000,
                20, 1, 500, 20, 3, 1_500, 1_000, POLICY.descriptor());
    }

    private static Assessment unavailable() {
        return new Assessment(Assessment.SCHEMA_VERSION, State.OBSERVATION_UNAVAILABLE,
                List.of(Violation.OBSERVATION_UNAVAILABLE), null, Status.UNAVAILABLE,
                -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, POLICY.descriptor());
    }

    private static double value(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }

    private static double value(
            SimpleMeterRegistry registry,
            String name,
            String tagName,
            String tagValue) {
        return registry.get(name).tag(tagName, tagValue).gauge().value();
    }
}
