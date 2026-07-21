package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Exact deployment-owned replica policy for certificate-rotation convergence.
 *
 * <p>The member inventory is an authority, not a discovery result. Every replica in one fleet
 * generation must share the same artifact, protocol, inventory attestation, activation mode and
 * threshold. A fenced quorum may improve availability, but it is safe only when the later serving
 * admission fence prevents an absent replica from serving an older generation.</p>
 *
 * @param deploymentScopeId stable deployment scope shared with signed rotation events
 * @param fleetId immutable rollout/fleet generation
 * @param instanceId stable serving slot represented by this process
 * @param startupId unique UUID for this process start
 * @param artifactFingerprint exact immutable application or image identity
 * @param expectedInstanceIds complete configured serving-slot inventory
 * @param protocolVersion exact acknowledgement protocol generation
 * @param activationMode all-replica or externally fenced quorum activation
 * @param requiredStagedReplicas minimum exact staged/active acknowledgements before activation
 * @param heartbeatInterval local acknowledgement lease-renewal interval
 * @param leaseDuration database-clock liveness window, at least three heartbeat intervals
 * @param recordRetention acknowledgement retention after its lease expires
 * @param inventoryAttestation local or externally governed inventory identity
 */
public record ControlPlaneCertificateRotationFleetPolicy(
        String deploymentScopeId,
        String fleetId,
        String instanceId,
        String startupId,
        String artifactFingerprint,
        Set<String> expectedInstanceIds,
        String protocolVersion,
        ActivationMode activationMode,
        int requiredStagedReplicas,
        Duration heartbeatInterval,
        Duration leaseDuration,
        Duration recordRetention,
        InventoryAttestation inventoryAttestation) {

    private static final int MAXIMUM_REPLICAS = 64;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Normalizes and validates the complete fail-closed fleet contract. */
    public ControlPlaneCertificateRotationFleetPolicy {
        deploymentScopeId = normalized(deploymentScopeId);
        fleetId = normalized(fleetId);
        instanceId = normalized(instanceId);
        startupId = normalized(startupId);
        artifactFingerprint = normalized(artifactFingerprint);
        protocolVersion = normalized(protocolVersion);
        activationMode = Objects.requireNonNull(activationMode, "activationMode");
        inventoryAttestation = inventoryAttestation == null
                ? InventoryAttestation.localConfigured() : inventoryAttestation;
        TreeSet<String> expected = new TreeSet<>();
        if (expectedInstanceIds != null) {
            expectedInstanceIds.stream()
                    .map(ControlPlaneCertificateRotationFleetPolicy::normalized)
                    .forEach(expected::add);
        }
        expectedInstanceIds = Set.copyOf(expected);
        int majority = expectedInstanceIds.size() / 2 + 1;
        if (!IDENTIFIER.matcher(deploymentScopeId).matches()
                || !IDENTIFIER.matcher(fleetId).matches()
                || !IDENTIFIER.matcher(instanceId).matches()
                || !validUuid(startupId)
                || !FINGERPRINT.matcher(artifactFingerprint).matches()
                || !IDENTIFIER.matcher(protocolVersion).matches()
                || expectedInstanceIds.isEmpty()
                || expectedInstanceIds.size() > MAXIMUM_REPLICAS
                || !expectedInstanceIds.contains(instanceId)
                || expectedInstanceIds.stream().anyMatch(
                value -> !IDENTIFIER.matcher(value).matches())
                || activationMode == ActivationMode.ALL_REPLICAS
                && requiredStagedReplicas != expectedInstanceIds.size()
                || activationMode == ActivationMode.FENCED_QUORUM
                && (requiredStagedReplicas < majority
                || requiredStagedReplicas >= expectedInstanceIds.size())) {
            throw invalid();
        }
        heartbeatInterval = bounded(heartbeatInterval, Duration.ofSeconds(1),
                Duration.ofMinutes(5));
        leaseDuration = bounded(leaseDuration, Duration.ofSeconds(3),
                Duration.ofMinutes(15));
        recordRetention = bounded(recordRetention, Duration.ofHours(1),
                Duration.ofDays(30));
        if (leaseDuration.compareTo(heartbeatInterval.multipliedBy(3)) < 0
                || recordRetention.compareTo(leaseDuration) < 0) {
            throw invalid();
        }
    }

    /**
     * Computes the identity shared by every process in this fleet.
     *
     * <p>Local process identity is intentionally excluded; otherwise valid replicas would publish
     * different policy fingerprints and could never converge.</p>
     *
     * @param objectMapper canonical protocol mapper
     * @return exact shared SHA-256 policy identity
     */
    public String sharedPolicyFingerprint(ObjectMapper objectMapper) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                Map.ofEntries(
                        Map.entry("schemaVersion",
                                "bloge.controlPlaneCertificateRotationFleetPolicy.v1"),
                        Map.entry("deploymentScopeId", deploymentScopeId),
                        Map.entry("fleetId", fleetId),
                        Map.entry("artifactFingerprint", artifactFingerprint),
                        Map.entry("expectedInstanceIds",
                                expectedInstanceIds.stream().sorted().toList()),
                        Map.entry("protocolVersion", protocolVersion),
                        Map.entry("activationMode", activationMode.name()),
                        Map.entry("requiredStagedReplicas", requiredStagedReplicas),
                        Map.entry("heartbeatSeconds", heartbeatInterval.toSeconds()),
                        Map.entry("leaseSeconds", leaseDuration.toSeconds()),
                        Map.entry("recordRetentionSeconds", recordRetention.toSeconds()),
                        Map.entry("inventoryAttestation", inventoryAttestation)));
    }

    /** Maximum exact member inventory accepted by this protocol. */
    public static int maximumReplicas() {
        return MAXIMUM_REPLICAS;
    }

    /** Activation availability mode; neither value alone proves final convergence. */
    public enum ActivationMode {
        /** Every configured replica must stage the exact successor. */
        ALL_REPLICAS,
        /** A strict majority may stage only when absent replicas are externally serving-fenced. */
        FENCED_QUORUM
    }

    /**
     * Immutable authority identity for the exact replica inventory.
     *
     * @param schemaVersion attestation binding generation
     * @param externallyAttested whether independent signatures establish the inventory
     * @param sourceType inventory authority implementation type
     * @param revision monotonic signed inventory revision, or zero in local mode
     * @param materialFingerprint signed inventory identity, blank in local mode
     * @param policyFingerprint signed inventory-policy identity, blank in local mode
     * @param expiresAt hard inventory deadline, null in local mode
     */
    public record InventoryAttestation(
            String schemaVersion,
            boolean externallyAttested,
            String sourceType,
            long revision,
            String materialFingerprint,
            String policyFingerprint,
            Instant expiresAt) {

        /** Current replica-inventory attestation generation. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationInventoryAttestation.v1";

        /** Rejects ambiguous partial authority identity. */
        public InventoryAttestation {
            schemaVersion = normalized(schemaVersion);
            sourceType = normalized(sourceType);
            materialFingerprint = normalized(materialFingerprint);
            policyFingerprint = normalized(policyFingerprint);
            boolean external = externallyAttested
                    && IDENTIFIER.matcher(sourceType).matches()
                    && !"LOCAL_CONFIGURED".equals(sourceType)
                    && revision > 0
                    && FINGERPRINT.matcher(materialFingerprint).matches()
                    && FINGERPRINT.matcher(policyFingerprint).matches()
                    && expiresAt != null;
            boolean local = !externallyAttested
                    && "LOCAL_CONFIGURED".equals(sourceType)
                    && revision == 0 && materialFingerprint.isEmpty()
                    && policyFingerprint.isEmpty() && expiresAt == null;
            if (!SCHEMA_VERSION.equals(schemaVersion) || !(external || local)) {
                throw invalid();
            }
        }

        /** @return explicit local inventory identity for test-only composition */
        public static InventoryAttestation localConfigured() {
            return new InventoryAttestation(SCHEMA_VERSION, false,
                    "LOCAL_CONFIGURED", 0, "", "", null);
        }
    }

    private static Duration bounded(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.getNano() != 0
                || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw invalid();
        }
        return value;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean validUuid(String value) {
        try {
            return java.util.UUID.fromString(value).toString().equals(value);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate rotation fleet policy is invalid");
    }
}
