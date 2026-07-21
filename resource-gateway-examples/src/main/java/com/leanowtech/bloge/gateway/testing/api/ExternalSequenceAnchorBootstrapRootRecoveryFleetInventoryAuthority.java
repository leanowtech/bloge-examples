package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Externally attested authority for a bootstrap-root recovery fleet inventory.
 *
 * <p>Implementations publish already-verified in-memory snapshots and re-evaluate hard validity on
 * every observation and snapshot read. No method may perform remote or database I/O. Dynamic
 * fetch, signature verification, revocation processing, and durable floor advancement belong to a
 * bounded refresh control loop that atomically replaces the current implementation state.</p>
 */
public interface ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
        extends ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory {

    /** Aggregate descriptor fields permitted in health and capability projections. */
    Set<String> DESCRIPTOR_PROPERTIES = Set.of(
            "sourceType", "protocolVersion", "privateMaterialPresent",
            "signatureThreshold", "runtimeExpiryFence", "fleetTopologyBound",
            "exactRuntimeBinding", "automaticRefresh", "signedRevocation",
            "durableGenerationFloor", "refreshState", "publicationState",
            "publicationSequence", "conditionalRequests", "failClosedOnRefreshFailure",
            "witnessedPublications", "witnessSignatureThreshold",
            "refreshIntervalSeconds", "maximumSnapshotAgeSeconds",
            "externallyAnchoredPublicationFloor",
            "byzantineQuorumAnchoredPublicationFloor");

    /**
     * Returns current verified authority state without network or database I/O.
     *
     * @return private immutable observation used for runtime fencing
     */
    Observation observation();

    /**
     * Returns the exact deployment and topology binding verified by the authority.
     *
     * @return immutable signed fleet binding
     */
    VerifiedBinding verifiedBinding();

    /**
     * Returns a key-free aggregate projection suitable for health and capabilities.
     *
     * @return bounded descriptor without fleet, lane, policy, or fingerprint identity
     */
    default Descriptor descriptor() {
        Observation observed = observation();
        return new Descriptor(Descriptor.SCHEMA_VERSION, true, true,
                observed.available(), observed.status(), observed.generation(),
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

    /**
     * Exact local deployment facts covered by the signed inventory.
     *
     * @param deploymentScopeId stable tenant/environment deployment scope
     * @param fleetId durable scheduler identity
     * @param artifactFingerprint exact local image or artifact SHA-256
     * @param partitionCount immutable durable partition topology
     */
    record VerifiedBinding(
            String deploymentScopeId,
            String fleetId,
            String artifactFingerprint,
            int partitionCount) {

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Enforces canonical deployment and topology identity. */
        public VerifiedBinding {
            deploymentScopeId = normalized(deploymentScopeId);
            fleetId = normalized(fleetId);
            artifactFingerprint = normalized(artifactFingerprint);
            if (!IDENTIFIER.matcher(deploymentScopeId).matches()
                    || !IDENTIFIER.matcher(fleetId).matches()
                    || !FINGERPRINT.matcher(artifactFingerprint).matches()
                    || partitionCount < 1
                    || partitionCount
                    > ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
                    .MAXIMUM_PARTITIONS) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet verified binding is invalid");
            }
        }
    }

    /**
     * Private authority observation consumed by worker runtime fencing.
     *
     * @param schemaVersion observation protocol generation
     * @param available whether the exact signed inventory is currently valid
     * @param status bounded stable status
     * @param sourceType authority implementation mode
     * @param generation signed inventory generation
     * @param laneCount signed canonical lane count
     * @param expiresAt exclusive hard validity deadline
     * @param validSignatureCount verified distinct signing authorities
     * @param requiredSignatureCount configured M-of-N threshold
     */
    record Observation(
            String schemaVersion,
            boolean available,
            String status,
            String sourceType,
            long generation,
            int laneCount,
            Instant expiresAt,
            int validSignatureCount,
            int requiredSignatureCount) {

        /** Current recovery-fleet inventory authority observation generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryObservation.v1";

        private static final Pattern STATUS = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
        private static final Pattern SOURCE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

        /** Enforces bounded externally attested observation shape. */
        public Observation {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            sourceType = normalized(sourceType);
            if (!SCHEMA_VERSION.equals(schemaVersion) || !STATUS.matcher(status).matches()
                    || !SOURCE.matcher(sourceType).matches() || generation < 1L
                    || available != "VERIFIED".equals(status)
                    || laneCount < 0
                    || laneCount
                    > ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.MAXIMUM_LANES
                    || expiresAt == null || expiresAt.getNano() != 0
                    || requiredSignatureCount < 1 || requiredSignatureCount > 32
                    || validSignatureCount < requiredSignatureCount
                    || validSignatureCount > 32) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet inventory observation is invalid");
            }
        }
    }

    /**
     * Aggregate authority facts without fleet, lane, policy, key, or fingerprint identity.
     *
     * @param schemaVersion descriptor protocol generation
     * @param configured whether an external authority is configured
     * @param externallyAttested whether independent signatures establish the inventory
     * @param available whether the signed inventory is currently valid
     * @param status bounded stable status
     * @param generation signed monotonic generation
     * @param laneCount signed inventory cardinality
     * @param properties bounded key-free capability properties
     */
    record Descriptor(
            String schemaVersion,
            boolean configured,
            boolean externallyAttested,
            boolean available,
            String status,
            long generation,
            int laneCount,
            Map<String, Object> properties) {

        /** Current aggregate recovery-fleet inventory descriptor generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryDescriptor.v1";

        /** Rejects identity-bearing or unbounded public descriptor values. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            if (!SCHEMA_VERSION.equals(schemaVersion) || !configured
                    || !externallyAttested || status.isBlank()
                    || available != "VERIFIED".equals(status) || generation < 1L
                    || laneCount < 0
                    || laneCount
                    > ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.MAXIMUM_LANES
                    || !DESCRIPTOR_PROPERTIES.containsAll(properties.keySet())
                    || properties.size() > DESCRIPTOR_PROPERTIES.size()
                    || properties.entrySet().stream().anyMatch(entry ->
                    !safeDescriptorValue(entry.getValue()))) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet inventory descriptor is invalid");
            }
        }

        private static boolean safeDescriptorValue(Object value) {
            if (value instanceof Boolean) {
                return true;
            }
            if (value instanceof Number number) {
                long numeric = number.longValue();
                return numeric >= 0L && numeric <= 1_000_000L;
            }
            return value instanceof String text && !text.isBlank() && text.length() <= 160
                    && text.chars().noneMatch(Character::isISOControl);
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
