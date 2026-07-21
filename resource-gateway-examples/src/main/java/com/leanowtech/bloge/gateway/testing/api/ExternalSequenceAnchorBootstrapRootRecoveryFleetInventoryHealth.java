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
    private final Supplier<
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor>
            descriptor;

    /**
     * Creates health over one signed inventory authority.
     *
     * @param authority in-memory externally attested fleet inventory authority
     */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority authority) {
        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority validated =
                Objects.requireNonNull(authority, "authority");
        this.observation = validated::observation;
        this.descriptor = validated::descriptor;
    }

    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth(
            Supplier<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation>
                    observation) {
        this.observation = Objects.requireNonNull(observation, "observation");
        this.descriptor = () -> staticDescriptor(this.observation.get());
    }

    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth(
            Supplier<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation>
                    observation,
            Supplier<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor>
                    descriptor) {
        this.observation = Objects.requireNonNull(observation, "observation");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
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
            var described = Objects.requireNonNull(descriptor.get(), "inventory descriptor");
            requireSameGeneration(observed, described);
            return (observed.available() ? Health.up() : Health.down())
                    .withDetails(details(observed, described)).build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("schemaVersion", SnapshotSchema.VERSION)
                    .withDetail("inventoryStatus", "UNAVAILABLE")
                    .build();
        }
    }

    private static Map<String, Object> details(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation
                    observed,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor
                    descriptor) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("schemaVersion", SnapshotSchema.VERSION);
        details.put("inventoryStatus", observed.status());
        details.put("inventoryAvailable", observed.available());
        details.put("sourceType", observed.sourceType());
        details.put("inventoryGeneration", observed.generation());
        details.put("laneCount", observed.laneCount());
        details.put("validSignatureCount", observed.validSignatureCount());
        details.put("requiredSignatureCount", observed.requiredSignatureCount());
        details.putAll(descriptor.properties());
        return Map.copyOf(details);
    }

    private static void requireSameGeneration(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation
                    observation,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor
                    descriptor) {
        Object sourceType = descriptor.properties().get("sourceType");
        if (observation.available() != descriptor.available()
                || !observation.status().equals(descriptor.status())
                || observation.generation() != descriptor.generation()
                || observation.laneCount() != descriptor.laneCount()
                || !observation.sourceType().equals(sourceType)) {
            throw new IllegalStateException(
                    "Recovery-fleet inventory health generation changed during projection");
        }
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor
            staticDescriptor(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation
                    observed) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor
                        .SCHEMA_VERSION,
                true, true, observed.available(), observed.status(), observed.generation(),
                observed.laneCount(), Map.of(
                "sourceType", observed.sourceType(),
                "protocolVersion",
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                        .SCHEMA_VERSION,
                "privateMaterialPresent", false,
                "signatureThreshold", observed.requiredSignatureCount(),
                "runtimeExpiryFence", true,
                "fleetTopologyBound", true,
                "exactRuntimeBinding", true,
                "automaticRefresh", false,
                "signedRevocation", false,
                "durableGenerationFloor", false));
    }

    private static final class SnapshotSchema {
        private static final String VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth.v1";

        private SnapshotSchema() {
        }
    }
}
