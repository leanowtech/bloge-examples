package com.leanowtech.bloge.gateway.testing.admission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Fixed-cardinality decision and lease-failure telemetry for runtime admission. */
public final class TestRuntimeAdmissionTelemetry {

    private static final String DECISIONS = "resource.gateway.test.admission.decisions";

    /** Stable admission outcomes suitable for dashboards and alert rules. */
    public enum Result {
        ACQUIRED,
        REJECTED,
        IN_PROGRESS,
        STORE_UNAVAILABLE,
        POLICY_DRIFT,
        LEASE_LOST,
        RELEASE_FAILED
    }

    /** Closed metric dimension; {@code RUNTIME} represents non-quota control failures. */
    public enum Scope {
        RUNTIME,
        TENANT,
        SUITE,
        OPERATOR,
        DEPENDENCY
    }

    private final boolean enabled;
    private final Map<Result, Map<Scope, Counter>> counters;

    /**
     * Registers every result/scope series up front so no caller identity can become a tag.
     *
     * @param registry deployment-selected Micrometer registry
     */
    public TestRuntimeAdmissionTelemetry(MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(registry, "registry");
        enabled = true;
        EnumMap<Result, Map<Scope, Counter>> registered = new EnumMap<>(Result.class);
        for (Result result : Result.values()) {
            EnumMap<Scope, Counter> byScope = new EnumMap<>(Scope.class);
            for (Scope scope : Scope.values()) {
                byScope.put(scope, Counter.builder(DECISIONS)
                        .tag("result", result.name().toLowerCase(java.util.Locale.ROOT))
                        .tag("scope", scope.name().toLowerCase(java.util.Locale.ROOT))
                        .register(meters));
            }
            registered.put(result, Map.copyOf(byScope));
        }
        counters = Map.copyOf(registered);
    }

    private TestRuntimeAdmissionTelemetry() {
        enabled = false;
        counters = Map.of();
    }

    /** @return disabled adapter for focused unit tests */
    public static TestRuntimeAdmissionTelemetry noop() {
        return new TestRuntimeAdmissionTelemetry();
    }

    /** Records one bounded-cardinality decision without subject identity. */
    public void record(Result result, Scope scope) {
        if (enabled) {
            counters.get(Objects.requireNonNull(result, "result"))
                    .get(Objects.requireNonNull(scope, "scope")).increment();
        }
    }

    /** Maps a quota dimension to its closed metric scope. */
    public static Scope scope(TestRuntimeAdmissionPolicy.Dimension dimension) {
        return Scope.valueOf(Objects.requireNonNull(dimension, "dimension").name());
    }
}
