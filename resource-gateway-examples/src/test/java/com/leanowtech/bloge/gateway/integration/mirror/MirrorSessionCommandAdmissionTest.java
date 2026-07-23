package com.leanowtech.bloge.gateway.integration.mirror;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MirrorSessionCommandAdmissionTest {

    @Test
    void boundsInflightCommandsAndReturnsCapacityOnExactRelease() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MirrorSessionCapacityTelemetry telemetry =
                new MirrorSessionCapacityTelemetry(meters);
        MirrorSessionCommandAdmission admission =
                new MirrorSessionCommandAdmission(1, telemetry);

        MirrorSessionCommandAdmission.Permit first =
                admission.tryAcquire().orElseThrow();
        assertThat(admission.tryAcquire()).isEmpty();
        assertThat(admission.inflight()).isEqualTo(1);

        first.close();
        first.close();
        assertThat(admission.inflight()).isZero();
        try (MirrorSessionCommandAdmission.Permit ignored =
                     admission.tryAcquire().orElseThrow()) {
            assertThat(admission.inflight()).isEqualTo(1);
        }

        assertThat(meters.get(
                "resource.gateway.mirror.session.admission.decisions")
                .tags("boundary", "replica", "decision", "admitted")
                .counter().count()).isEqualTo(2);
        assertThat(meters.get(
                "resource.gateway.mirror.session.admission.decisions")
                .tags("boundary", "replica", "decision", "rejected")
                .counter().count()).isEqualTo(1);
    }
}
