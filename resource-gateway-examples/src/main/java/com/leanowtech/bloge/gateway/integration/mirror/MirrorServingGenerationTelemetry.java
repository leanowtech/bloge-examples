package com.leanowtech.bloge.gateway.integration.mirror;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed-cardinality counters for serving-generation admission and floor checks.
 *
 * <p>Every series is registered from closed enums. Enterprise scope, stream, generation, token,
 * fingerprint, authority, key, request, run, exception, and provider diagnostics can never become
 * metric tags.</p>
 */
public final class MirrorServingGenerationTelemetry {
    private static final String CHECKS =
            "resource.gateway.mirror.serving_generation.checks";

    private final boolean enabled;
    private final Map<Check, Map<Outcome, Counter>> counters;

    /**
     * Registers the complete bounded metric series before serving traffic.
     *
     * @param registry deployment meter registry
     */
    public MirrorServingGenerationTelemetry(MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(registry, "registry");
        EnumMap<Check, Map<Outcome, Counter>> registered =
                new EnumMap<>(Check.class);
        for (Check check : Check.values()) {
            EnumMap<Outcome, Counter> byOutcome =
                    new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                byOutcome.put(
                        outcome,
                        Counter.builder(CHECKS)
                                .tag("check", metric(check))
                                .tag("outcome", metric(outcome))
                                .register(meters));
            }
            registered.put(check, Map.copyOf(byOutcome));
        }
        enabled = true;
        counters = Map.copyOf(registered);
    }

    private MirrorServingGenerationTelemetry() {
        enabled = false;
        counters = Map.of();
    }

    /**
     * Returns an inert adapter for focused tests or non-Spring embedding.
     *
     * @return no-op telemetry adapter
     */
    public static MirrorServingGenerationTelemetry noop() {
        return new MirrorServingGenerationTelemetry();
    }

    /**
     * Records one bounded admission result.
     *
     * @param check admission boundary
     * @param outcome closed result class
     */
    public void record(Check check, Outcome outcome) {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(outcome, "outcome");
        if (!enabled) {
            return;
        }
        try {
            counters.get(check).get(outcome).increment();
        } catch (RuntimeException ignored) {
            // Metrics are advisory and must never alter fail-closed admission.
        }
    }

    /** Closed admission boundaries. */
    public enum Check {
        MATERIALIZATION,
        RUN,
        OCCURRENCE
    }

    /** Closed terminal outcomes. */
    public enum Outcome {
        CURRENT,
        CACHED,
        REJECTED,
        UNAVAILABLE,
        INVALID,
        STALE,
        ROLLBACK,
        EXPIRED
    }

    private static String metric(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
