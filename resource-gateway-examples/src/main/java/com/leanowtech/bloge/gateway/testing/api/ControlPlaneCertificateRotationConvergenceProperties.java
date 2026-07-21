package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Strict product configuration for certificate-rotation fleet convergence.
 *
 * <p>The configuration identifies one immutable deployment fleet and one process start. A local
 * inventory is accepted only for a single replica; multi-replica activation requires an
 * externally attested exact inventory with a monotonic revision and hard expiry. Quorum mode is
 * rejected until a deployment serving-fence authority is implemented.</p>
 *
 * @param enabled enables heartbeat, activation and serving admission fencing
 * @param required prevents an enabled certificate-rotation deployment from omitting convergence
 * @param fleetId immutable deployment rollout generation
 * @param instanceId stable serving slot represented by this process
 * @param startupId unique lowercase UUID for this process start
 * @param artifactFingerprint immutable application or image fingerprint
 * @param expectedInstanceIds comma-separated exact serving-slot inventory
 * @param protocolVersion exact private acknowledgement protocol generation
 * @param activationMode activation policy; currently only {@code ALL_REPLICAS}
 * @param requiredStagedReplicas exact all-replica activation threshold
 * @param heartbeatIntervalSeconds local heartbeat interval
 * @param leaseDurationSeconds database-clock process liveness lease
 * @param recordRetentionSeconds expired acknowledgement retention
 * @param inventorySourceType {@code LOCAL_CONFIGURED} or external authority type
 * @param inventoryRevision monotonic external inventory revision, zero in local mode
 * @param inventoryMaterialFingerprint signed external inventory fingerprint
 * @param inventoryPolicyFingerprint signed external inventory policy fingerprint
 * @param inventoryExpiresAt external inventory hard expiry in ISO-8601 form
 */
@ConfigurationProperties(
        prefix = ControlPlaneCertificateRotationConvergenceProperties.PREFIX,
        ignoreUnknownFields = false)
public record ControlPlaneCertificateRotationConvergenceProperties(
        Boolean enabled,
        Boolean required,
        String fleetId,
        String instanceId,
        String startupId,
        String artifactFingerprint,
        String expectedInstanceIds,
        String protocolVersion,
        String activationMode,
        Integer requiredStagedReplicas,
        Long heartbeatIntervalSeconds,
        Long leaseDurationSeconds,
        Long recordRetentionSeconds,
        String inventorySourceType,
        Long inventoryRevision,
        String inventoryMaterialFingerprint,
        String inventoryPolicyFingerprint,
        String inventoryExpiresAt) {

    /** Independent product configuration prefix, isolated from strict rotation binding. */
    public static final String PREFIX =
            "gateway.testing.control-plane-certificate-rotation-convergence";
    private static final String DEFAULT_PROTOCOL = "certificate-rotation-convergence-v1";

    /** Applies finite defaults and rejects partial or downgrade-prone fleet configuration. */
    public ControlPlaneCertificateRotationConvergenceProperties {
        enabled = Boolean.TRUE.equals(enabled);
        required = Boolean.TRUE.equals(required);
        fleetId = normalized(fleetId);
        instanceId = normalized(instanceId);
        startupId = normalized(startupId);
        artifactFingerprint = normalized(artifactFingerprint);
        expectedInstanceIds = normalized(expectedInstanceIds);
        protocolVersion = normalized(protocolVersion).isBlank()
                ? DEFAULT_PROTOCOL : normalized(protocolVersion);
        activationMode = normalized(activationMode).isBlank()
                ? ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS.name()
                : normalized(activationMode);
        requiredStagedReplicas = requiredStagedReplicas == null ? 0 : requiredStagedReplicas;
        heartbeatIntervalSeconds = heartbeatIntervalSeconds == null
                ? 5L : heartbeatIntervalSeconds;
        leaseDurationSeconds = leaseDurationSeconds == null ? 15L : leaseDurationSeconds;
        recordRetentionSeconds = recordRetentionSeconds == null
                ? 3_600L : recordRetentionSeconds;
        inventorySourceType = normalized(inventorySourceType).isBlank()
                ? "LOCAL_CONFIGURED" : normalized(inventorySourceType);
        inventoryRevision = inventoryRevision == null ? 0L : inventoryRevision;
        inventoryMaterialFingerprint = normalized(inventoryMaterialFingerprint);
        inventoryPolicyFingerprint = normalized(inventoryPolicyFingerprint);
        inventoryExpiresAt = normalized(inventoryExpiresAt);
        boolean residual = !fleetId.isBlank() || !instanceId.isBlank() || !startupId.isBlank()
                || !artifactFingerprint.isBlank() || !expectedInstanceIds.isBlank()
                || !DEFAULT_PROTOCOL.equals(protocolVersion)
                || !ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS
                .name().equals(activationMode)
                || requiredStagedReplicas != 0 || heartbeatIntervalSeconds != 5
                || leaseDurationSeconds != 15 || recordRetentionSeconds != 3_600
                || !"LOCAL_CONFIGURED".equals(inventorySourceType)
                || inventoryRevision != 0 || !inventoryMaterialFingerprint.isBlank()
                || !inventoryPolicyFingerprint.isBlank() || !inventoryExpiresAt.isBlank();
        if (required && !enabled || !enabled && residual) {
            throw invalid();
        }
        if (enabled) {
            Set<String> instances = parseInstances(expectedInstanceIds);
            if (fleetId.isBlank() || instanceId.isBlank() || startupId.isBlank()
                    || artifactFingerprint.isBlank() || !instances.contains(instanceId)
                    || instances.size() > 1 && "LOCAL_CONFIGURED".equals(inventorySourceType)
                    || !ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS
                    .name().equals(activationMode)
                    || requiredStagedReplicas != instances.size()) {
                throw invalid();
            }
            try {
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation inventory =
                        "LOCAL_CONFIGURED".equals(inventorySourceType)
                                ? ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                                .localConfigured()
                                : externalInventory(inventorySourceType, inventoryRevision,
                                inventoryMaterialFingerprint, inventoryPolicyFingerprint,
                                inventoryExpiresAt);
                // The domain policy remains the authoritative identity and duration validator.
                new ControlPlaneCertificateRotationFleetPolicy(
                        "configuration-validation-scope", fleetId, instanceId, startupId,
                        artifactFingerprint, instances, protocolVersion,
                        ControlPlaneCertificateRotationFleetPolicy.ActivationMode
                                .valueOf(activationMode),
                        requiredStagedReplicas, Duration.ofSeconds(heartbeatIntervalSeconds),
                        Duration.ofSeconds(leaseDurationSeconds),
                        Duration.ofSeconds(recordRetentionSeconds), inventory);
            } catch (RuntimeException invalid) {
                throw invalid();
            }
        }
    }

    /**
     * Materializes the immutable fleet policy bound to the rotation deployment scope.
     *
     * @param deploymentScopeId exact signed-event scope
     * @return validated fleet policy
     */
    public ControlPlaneCertificateRotationFleetPolicy policy(String deploymentScopeId) {
        if (!enabled) {
            throw new IllegalStateException(
                    "Certificate rotation convergence is disabled");
        }
        Set<String> instances = parseInstances(expectedInstanceIds);
        ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation inventory =
                "LOCAL_CONFIGURED".equals(inventorySourceType)
                        ? ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                        .localConfigured()
                        : externalInventory(inventorySourceType, inventoryRevision,
                        inventoryMaterialFingerprint, inventoryPolicyFingerprint,
                        inventoryExpiresAt);
        return new ControlPlaneCertificateRotationFleetPolicy(
                deploymentScopeId, fleetId, instanceId, startupId, artifactFingerprint,
                instances, protocolVersion,
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.valueOf(activationMode),
                requiredStagedReplicas, Duration.ofSeconds(heartbeatIntervalSeconds),
                Duration.ofSeconds(leaseDurationSeconds),
                Duration.ofSeconds(recordRetentionSeconds), inventory);
    }

    /** Returns the canonical disabled convergence policy. */
    public static ControlPlaneCertificateRotationConvergenceProperties disabled() {
        return new ControlPlaneCertificateRotationConvergenceProperties(
                false, false, "", "", "", "", "", DEFAULT_PROTOCOL,
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS.name(),
                0, 5L, 15L, 3_600L, "LOCAL_CONFIGURED", 0L, "", "", "");
    }

    private static ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
            externalInventory(
            String sourceType,
            long revision,
            String materialFingerprint,
            String policyFingerprint,
            String expiresAt) {
        Instant expiry;
        try {
            expiry = Instant.parse(expiresAt);
        } catch (RuntimeException invalid) {
            throw invalid();
        }
        return new ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation(
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation.SCHEMA_VERSION,
                true, sourceType, revision, materialFingerprint, policyFingerprint, expiry);
    }

    private static Set<String> parseInstances(String source) {
        LinkedHashSet<String> instances = new LinkedHashSet<>();
        for (String value : normalized(source).split(",", -1)) {
            String instance = normalized(value);
            if (instance.isBlank() || !instances.add(instance)) {
                throw invalid();
            }
        }
        return Set.copyOf(instances);
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate rotation convergence configuration is invalid");
    }
}
