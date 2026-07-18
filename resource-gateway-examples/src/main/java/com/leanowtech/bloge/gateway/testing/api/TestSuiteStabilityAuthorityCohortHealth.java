package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.Map;
import java.util.Objects;

/** Aggregate-only Actuator health for exact authority-trust cohort convergence. */
public final class TestSuiteStabilityAuthorityCohortHealth implements HealthIndicator {

    private final TestSuiteStabilityAuthorityCohortGate gate;

    /**
     * Creates a payload-free cohort health contributor.
     *
     * @param gate exact current cohort readiness
     */
    public TestSuiteStabilityAuthorityCohortHealth(
            TestSuiteStabilityAuthorityCohortGate gate) {
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /** Returns UP only for an exact, live, healthy, one-generation configured cohort. */
    @Override
    public Health health() {
        try {
            TestSuiteStabilityAuthorityCohortGate.Descriptor descriptor = gate.descriptor();
            Map<String, Object> details = Map.ofEntries(
                    Map.entry("schemaVersion", descriptor.schemaVersion()),
                    Map.entry("configured", descriptor.configured()),
                    Map.entry("status", descriptor.status()),
                    Map.entry("expectedReplicaCount", descriptor.expectedReplicaCount()),
                    Map.entry("liveReplicaCount", descriptor.liveReplicaCount()),
                    Map.entry("healthyReplicaCount", descriptor.healthyReplicaCount()),
                    Map.entry("distinctSnapshotCount", descriptor.distinctSnapshotCount()),
                    Map.entry("leaseDurationSeconds", descriptor.leaseDurationSeconds()),
                    Map.entry("databaseAuthority", descriptor.databaseAuthority()),
                    Map.entry("exactConfiguredInventory",
                            descriptor.exactConfiguredInventory()));
            return (descriptor.available() ? Health.up() : Health.down())
                    .withDetails(details).build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("status", "STORE_UNAVAILABLE")
                    .build();
        }
    }
}
