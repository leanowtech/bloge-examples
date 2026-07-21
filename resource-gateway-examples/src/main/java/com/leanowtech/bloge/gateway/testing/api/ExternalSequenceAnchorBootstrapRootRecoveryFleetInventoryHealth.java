package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Aggregate-only health indicator for a signed bootstrap-root recovery fleet inventory.
 *
 * <p>The indicator reads one immutable in-memory authority observation. It performs no remote,
 * database, runtime-catalog, resolver, service, or provider call and exposes no deployment scope,
 * fleet id, lane key, policy fingerprint, material fingerprint, signing key, endpoint, payload, or
 * exception. An intentionally empty but valid signed inventory is healthy.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth
        implements HealthIndicator {

    private final Supplier<
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation>
            observation;

    /**
     * Creates health over one signed inventory authority.
     *
     * @param authority in-memory externally attested fleet inventory authority
     */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority authority) {
        this(Objects.requireNonNull(authority, "authority")::observation);
    }

    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth(
            Supplier<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation>
                    observation) {
        this.observation = Objects.requireNonNull(observation, "observation");
    }

    /**
     * Returns UP only while the exact signed inventory remains inside its hard validity window.
     *
     * @return bounded key-free Actuator health projection
     */
    @Override
    public Health health() {
        try {
            var observed = Objects.requireNonNull(observation.get(), "inventory observation");
            return (observed.available() ? Health.up() : Health.down())
                    .withDetails(details(observed)).build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("schemaVersion", SnapshotSchema.VERSION)
                    .withDetail("inventoryStatus", "UNAVAILABLE")
                    .build();
        }
    }

    private static Map<String, Object> details(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation
                    observed) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("schemaVersion", SnapshotSchema.VERSION);
        details.put("inventoryStatus", observed.status());
        details.put("inventoryAvailable", observed.available());
        details.put("sourceType", observed.sourceType());
        details.put("inventoryGeneration", observed.generation());
        details.put("laneCount", observed.laneCount());
        details.put("validSignatureCount", observed.validSignatureCount());
        details.put("requiredSignatureCount", observed.requiredSignatureCount());
        details.put("runtimeExpiryFence", true);
        details.put("fleetTopologyBound", true);
        details.put("exactRuntimeBinding", true);
        details.put("automaticRefresh", false);
        details.put("signedRevocation", false);
        details.put("durableGenerationFloor", false);
        return Map.copyOf(details);
    }

    private static final class SnapshotSchema {
        private static final String VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth.v1";

        private SnapshotSchema() {
        }
    }
}
