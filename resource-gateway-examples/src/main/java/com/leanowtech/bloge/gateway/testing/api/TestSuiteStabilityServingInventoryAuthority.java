package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Independent deployment authority for an exact suite-stability serving inventory. */
@FunctionalInterface
public interface TestSuiteStabilityServingInventoryAuthority {

    /** Closed key-free descriptor vocabulary shared by static and dynamic adapters. */
    Set<String> DESCRIPTOR_PROPERTIES = Set.of(
            "sourceType", "privateMaterialPresent", "automaticRefresh", "refreshState",
            "refreshIntervalSeconds", "maximumSnapshotAgeSeconds", "conditionalRequests",
            "failClosedOnRefreshFailure", "signedRevocation", "witnessedPublications",
            "protocolVersion", "witnessSignatureThreshold", "durablePublicationFloor");

    /** @return current verified inventory state without network or database I/O */
    Observation observation();

    /** @return aggregate key-free operational descriptor */
    default Descriptor descriptor() {
        Observation observed = observation();
        return new Descriptor(Descriptor.SCHEMA_VERSION, observed.configured(),
                observed.externallyAttested(), observed.available(), observed.status(),
                observed.expectedInstanceIds().size(), observed.revision(),
                Map.of("sourceType", observed.sourceType(),
                        "privateMaterialPresent", false));
    }

    /** @return local configuration mode used when signed inventory is not required */
    static TestSuiteStabilityServingInventoryAuthority localOnly() {
        return () -> Observation.localOnly();
    }

    /**
     * Private verified inventory observation consumed by policy construction and runtime fencing.
     *
     * @param schemaVersion observation generation
     * @param configured whether an inventory authority is configured
     * @param externallyAttested whether independent signatures establish the set
     * @param available whether the attestation is currently valid
     * @param status stable bounded state
     * @param sourceType authority implementation type
     * @param sourceSequence monotonic source publication generation, or zero in local mode
     * @param sourceGenerationFingerprint private publication/witness generation identity
     * @param revision externally monotonic revision, or zero in local mode
     * @param materialFingerprint signed material identity, blank in local mode
     * @param policyFingerprint external policy identity, blank in local mode
     * @param expectedInstanceIds exact verified set, empty in local mode
     * @param expiresAt external validity deadline, null in local mode
     * @param validSignatureCount verified distinct authority count
     * @param requiredSignatureCount configured authority threshold
     */
    record Observation(
            String schemaVersion,
            boolean configured,
            boolean externallyAttested,
            boolean available,
            String status,
            String sourceType,
            long sourceSequence,
            String sourceGenerationFingerprint,
            long revision,
            String materialFingerprint,
            String policyFingerprint,
            List<String> expectedInstanceIds,
            Instant expiresAt,
            int validSignatureCount,
            int requiredSignatureCount) {

        /** Current private observation generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityServingInventoryObservation.v1";
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Enforces that only an external verified observation carries attestation identity. */
        public Observation {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            sourceType = normalized(sourceType);
            sourceGenerationFingerprint = normalized(sourceGenerationFingerprint);
            materialFingerprint = normalized(materialFingerprint);
            policyFingerprint = normalized(policyFingerprint);
            expectedInstanceIds = expectedInstanceIds == null
                    ? List.of() : List.copyOf(expectedInstanceIds);
            boolean signedShape = configured && externallyAttested
                    && sourceSequence > 0
                    && FINGERPRINT.matcher(sourceGenerationFingerprint).matches()
                    && revision > 0
                    && FINGERPRINT.matcher(materialFingerprint).matches()
                    && FINGERPRINT.matcher(policyFingerprint).matches()
                    && !expectedInstanceIds.isEmpty() && expectedInstanceIds.size() <= 256
                    && expiresAt != null
                    && requiredSignatureCount > 0
                    && validSignatureCount >= requiredSignatureCount;
            boolean localShape = !configured && !externallyAttested && available
                    && "LOCAL_CONFIGURED".equals(status) && sourceSequence == 0
                    && sourceGenerationFingerprint.isEmpty() && revision == 0
                    && materialFingerprint.isEmpty() && policyFingerprint.isEmpty()
                    && expectedInstanceIds.isEmpty() && expiresAt == null
                    && validSignatureCount == 0 && requiredSignatureCount == 0;
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isBlank()
                    || sourceType.isBlank() || validSignatureCount < 0
                    || sourceSequence < 0
                    || requiredSignatureCount < 0 || validSignatureCount > 32
                    || requiredSignatureCount > 32
                    || !(signedShape || localShape)) {
                throw new IllegalArgumentException(
                        "Suite-stability serving inventory observation is invalid");
            }
        }

        /** @return backward-compatible local configured mode */
        public static Observation localOnly() {
            return new Observation(SCHEMA_VERSION, false, false, true,
                    "LOCAL_CONFIGURED", "LOCAL_CONFIGURED", 0, "", 0,
                    "", "", List.of(), null, 0, 0);
        }
    }

    /**
     * Aggregate operational facts without inventory ids, instance ids, fingerprints, or keys.
     *
     * @param schemaVersion descriptor generation
     * @param configured whether external inventory trust is configured
     * @param externallyAttested whether the set is independently signed
     * @param available current freshness and verification readiness
     * @param status bounded current state
     * @param expectedReplicaCount verified inventory cardinality
     * @param revision external monotonic revision, safe as an aggregate operational fact
     * @param properties bounded key-free properties
     */
    record Descriptor(
            String schemaVersion,
            boolean configured,
            boolean externallyAttested,
            boolean available,
            String status,
            int expectedReplicaCount,
            long revision,
            Map<String, Object> properties) {

        /** Current aggregate descriptor generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityServingInventoryDescriptor.v1";

        /** Validates the aggregate-only public projection. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isBlank()
                    || expectedReplicaCount < 0 || expectedReplicaCount > 256
                    || revision < 0
                    || configured != externallyAttested
                    || configured && (expectedReplicaCount < 1 || revision < 1)
                    || !configured && (!available || expectedReplicaCount != 0
                    || revision != 0)
                    || !DESCRIPTOR_PROPERTIES.containsAll(properties.keySet())
                    || properties.size() > DESCRIPTOR_PROPERTIES.size()
                    || properties.entrySet().stream().anyMatch(entry ->
                    !safeDescriptorValue(entry.getValue()))) {
                throw new IllegalArgumentException(
                        "Suite-stability serving inventory descriptor is invalid");
            }
        }

        private static boolean safeDescriptorValue(Object value) {
            if (value instanceof Boolean) {
                return true;
            }
            if (value instanceof Number number) {
                long numeric = number.longValue();
                return numeric >= 0 && numeric <= 86_400;
            }
            return value instanceof String text && !text.isBlank() && text.length() <= 128
                    && text.chars().noneMatch(Character::isISOControl);
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
