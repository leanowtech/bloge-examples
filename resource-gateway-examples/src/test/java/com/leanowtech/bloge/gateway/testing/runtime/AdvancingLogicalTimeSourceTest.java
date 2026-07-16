package com.leanowtech.bloge.gateway.testing.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdvancingLogicalTimeSourceTest {

    @Test
    void concurrentSleepsAdvanceMonotonicallyWithoutLosingTime() {
        Instant origin = Instant.parse("2026-07-15T00:00:00Z");
        AdvancingLogicalTimeSource time = new AdvancingLogicalTimeSource(origin);

        IntStream.range(0, 100).parallel().forEach(ignored -> time.sleep(Duration.ofSeconds(1)));

        assertThat(time.origin()).isEqualTo(origin);
        assertThat(time.elapsed()).isEqualTo(Duration.ofSeconds(100));
        assertThat(time.now()).isEqualTo(origin.plusSeconds(100));
    }

    @Test
    void negativeAdvanceIsRejected() {
        AdvancingLogicalTimeSource time = new AdvancingLogicalTimeSource(Instant.EPOCH);

        assertThatThrownBy(() -> time.sleep(Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void restoreAdvancesButNeverRewindsTheCurrentLogicalTime() {
        Instant origin = Instant.parse("2026-07-15T00:00:00Z");
        AdvancingLogicalTimeSource time = new AdvancingLogicalTimeSource(origin);

        time.restore(origin.plusSeconds(10));

        assertThat(time.now()).isEqualTo(origin.plusSeconds(10));
        assertThatThrownBy(() -> time.restore(origin.plusSeconds(9)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backwards");
        assertThat(time.now()).isEqualTo(origin.plusSeconds(10));
    }
}
