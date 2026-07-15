package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.spi.TimeSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Run-scoped logical clock whose sleeps advance time atomically without consuming wall time.
 *
 * <p>The clock is deliberately not shared between runs. Concurrent advances are linearizable and
 * monotonic; callers that need deterministic ordering between concurrent branches still require a
 * deterministic scheduler.</p>
 */
public final class AdvancingLogicalTimeSource implements TimeSource {

    private final Instant origin;
    private final AtomicReference<Instant> current;

    /** @param origin immutable logical-clock origin for one test run */
    public AdvancingLogicalTimeSource(Instant origin) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.current = new AtomicReference<>(origin);
    }

    /** @return current logical instant */
    @Override
    public Instant now() {
        return current.get();
    }

    /** Advances the logical clock and returns immediately. */
    @Override
    public void sleep(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Logical sleep duration cannot be negative: " + duration);
        }
        if (!duration.isZero()) {
            current.updateAndGet(value -> value.plus(duration));
        }
    }

    /** @return immutable logical-clock origin */
    public Instant origin() {
        return origin;
    }

    /** @return elapsed logical time since this run started */
    public Duration elapsed() {
        return Duration.between(origin, now());
    }
}
