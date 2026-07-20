package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.Objects;

/** Actuator health projection for aggregate test-secret trust-cohort convergence. */
public final class TestSecretAuthorityTrustCohortHealth implements HealthIndicator {

    private final TestSecretAuthorityTrustCohortGate gate;

    /** @param gate non-network aggregate convergence projection */
    public TestSecretAuthorityTrustCohortHealth(TestSecretAuthorityTrustCohortGate gate) {
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /**
     * Exposes bounded counts and policy facts without member ids, fingerprints or key material.
     *
     * @return UP only while the exact configured fleet is on one healthy trust generation
     */
    @Override
    public Health health() {
        TestSecretAuthorityTrustCohortGate.Descriptor descriptor;
        try {
            descriptor = gate.descriptor();
        } catch (RuntimeException unavailable) {
            return Health.down().withDetail("status", "DESCRIPTOR_UNAVAILABLE").build();
        }
        Health.Builder builder = descriptor.available() ? Health.up() : Health.down();
        return builder
                .withDetail("configured", descriptor.configured())
                .withDetail("status", descriptor.status())
                .withDetail("expectedReplicaCount", descriptor.expectedReplicaCount())
                .withDetail("liveReplicaCount", descriptor.liveReplicaCount())
                .withDetail("healthyReplicaCount", descriptor.healthyReplicaCount())
                .withDetail("distinctTrustGenerationCount",
                        descriptor.distinctTrustGenerationCount())
                .withDetail("distinctServingInventoryGenerationCount",
                        descriptor.distinctServingInventoryGenerationCount())
                .withDetail("leaseDurationSeconds", descriptor.leaseDurationSeconds())
                .withDetail("databaseAuthority", descriptor.databaseAuthority())
                .withDetail("exactConfiguredInventory",
                        descriptor.exactConfiguredInventory())
                .withDetail("externallyAttestedInventory",
                        descriptor.externallyAttestedInventory())
                .build();
    }
}
