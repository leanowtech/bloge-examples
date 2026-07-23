package com.leanowtech.bloge.gateway.integration.mirror;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MirrorServingGenerationTelemetryTest {
    private static final String METRIC =
            "resource.gateway.mirror.serving_generation.checks";

    @Test
    void preRegistersOnlyClosedCheckAndOutcomeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MirrorServingGenerationTelemetry telemetry =
                new MirrorServingGenerationTelemetry(registry);

        telemetry.record(
                MirrorServingGenerationTelemetry.Check.RUN,
                MirrorServingGenerationTelemetry.Outcome.STALE);

        assertThat(registry.find(METRIC)
                .tags("check", "run", "outcome", "stale")
                .counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).hasSize(
                MirrorServingGenerationTelemetry.Check.values().length
                        * MirrorServingGenerationTelemetry.Outcome
                        .values().length);
        registry.getMeters().forEach(meter -> {
            Set<String> tagKeys = meter.getId().getTags().stream()
                    .map(tag -> tag.getKey())
                    .collect(Collectors.toSet());
            assertThat(tagKeys).containsExactlyInAnyOrder(
                    "check", "outcome");
        });
    }
}
