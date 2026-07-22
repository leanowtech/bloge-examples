package com.leanowtech.bloge.gateway.integration.mirror;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MirrorOperationTelemetryTest {

    @Test
    void preRegistersTheCompleteClosedSeriesAndRecordsTerminalFacts() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MirrorOperationTelemetry telemetry = new MirrorOperationTelemetry(meters);

        telemetry.record(MirrorOperationAuditEvent.Operation.RUN_CREATE,
                MirrorOperationAuditEvent.Outcome.SUCCEEDED,
                MirrorOperationAuditEvent.Reason.NONE, Duration.ofMillis(25).toNanos());
        telemetry.record(MirrorOperationAuditEvent.Operation.PLAN_READ,
                MirrorOperationAuditEvent.Outcome.REJECTED,
                MirrorOperationAuditEvent.Reason.NOT_FOUND, Duration.ofMillis(4).toNanos());

        assertThat(meters.get("resource.gateway.mirror.operations")
                .tags("operation", "run_create", "outcome", "succeeded")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get("resource.gateway.mirror.duration")
                .tags("operation", "run_create", "outcome", "succeeded")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("resource.gateway.mirror.duration")
                .tags("operation", "run_create", "outcome", "succeeded")
                .timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(25);
        assertThat(meters.get("resource.gateway.mirror.failures")
                .tags("operation", "plan_read", "reason", "not_found")
                .counter().count()).isEqualTo(1);
        assertThat(meters.getMeters()).hasSize(75);
    }

    @Test
    void exposesOnlyClosedLowCardinalityTagKeysAndValues() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new MirrorOperationTelemetry(meters);

        Set<String> operations = enumValues(MirrorOperationAuditEvent.Operation.values());
        Set<String> outcomes = enumValues(MirrorOperationAuditEvent.Outcome.values());
        Set<String> reasons = enumValues(MirrorOperationAuditEvent.Reason.values());
        reasons.remove("none");

        for (Meter meter : meters.getMeters()) {
            Set<String> keys = meter.getId().getTags().stream()
                    .map(Tag::getKey).collect(Collectors.toSet());
            if (meter.getId().getName().equals("resource.gateway.mirror.failures")) {
                assertThat(keys).containsExactlyInAnyOrder("operation", "reason");
                assertThat(meter.getId().getTag("reason")).isIn(reasons);
            } else {
                assertThat(keys).containsExactlyInAnyOrder("operation", "outcome");
                assertThat(meter.getId().getTag("outcome")).isIn(outcomes);
            }
            assertThat(meter.getId().getTag("operation")).isIn(operations);
            assertThat(keys).doesNotContain("tenant", "organization", "project", "environment",
                    "region", "correlation", "actor", "request", "plan", "run", "error");
        }
    }

    private static Set<String> enumValues(Enum<?>[] values) {
        return java.util.Arrays.stream(values)
                .map(value -> value.name().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
