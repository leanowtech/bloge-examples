package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Signed provider-inventory authority and the only supported physical observation resolver.
 *
 * <p>{@link #observation()} is process-local and performs no network, database, provider, or
 * payload operation. {@link #resolve(String, String)} must return an adapter fenced to the exact
 * observed inventory generation and must reject unknown or stale provider deployments.</p>
 */
public interface TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority extends
        TestSuiteStabilityPhysicalAttemptObservationReconciler.AuthorityResolver {

    /** Closed aggregate properties shared by static and future dynamic authorities. */
    Set<String> DESCRIPTOR_PROPERTIES = Set.of(
            "sourceType", "privateMaterialPresent", "dynamicInventory", "automaticRefresh",
            "signedRevocation", "durablePublicationFloor", "witnessedPublications",
            "externalNonEquivocation", "byzantineQuorumNonEquivocation",
            "managedTrustRootRefresh", "managedTrustRootAvailable",
            "managedTrustRootStatus", "managedTrustRootSequence",
            "atomicDualTrustRootPublication", "durableTrustRootFloor",
            "externallyAnchoredTrustRootFloor", "byzantineQuorumAnchoredTrustRootFloor");

    /**
     * Returns the current privately fenced inventory state without external I/O.
     *
     * @return current immutable local observation
     */
    Observation observation();

    /**
     * Returns an identity-free public projection suitable for health and capability APIs.
     *
     * @return bounded aggregate descriptor
     */
    default Descriptor descriptor() {
        Observation observed = observation();
        boolean dynamic = observed.sourceType().contains("DYNAMIC");
        return new Descriptor(Descriptor.SCHEMA_VERSION, true,
                observed.externallyAttested(), observed.available(), observed.status(),
                observed.revision(), observed.bindings().size(),
                Map.ofEntries(
                        Map.entry("sourceType", observed.sourceType()),
                        Map.entry("privateMaterialPresent", false),
                        Map.entry("dynamicInventory", dynamic),
                        Map.entry("automaticRefresh", false),
                        Map.entry("signedRevocation", false),
                        Map.entry("durablePublicationFloor", false),
                        Map.entry("witnessedPublications", false),
                        Map.entry("externalNonEquivocation", false),
                        Map.entry("byzantineQuorumNonEquivocation", false),
                        Map.entry("managedTrustRootRefresh", false),
                        Map.entry("managedTrustRootAvailable", false),
                        Map.entry("managedTrustRootStatus", "DISABLED"),
                        Map.entry("managedTrustRootSequence", 0L),
                        Map.entry("atomicDualTrustRootPublication", false),
                        Map.entry("durableTrustRootFloor", false),
                        Map.entry("externallyAnchoredTrustRootFloor", false),
                        Map.entry("byzantineQuorumAnchoredTrustRootFloor", false)));
    }

    /**
     * Private generation snapshot used to fence resolver and capability projections.
     *
     * @param schemaVersion observation protocol generation
     * @param externallyAttested whether independent signatures established the complete set
     * @param available whether this exact generation may resolve provider calls
     * @param status bounded lifecycle state
     * @param sourceType bounded authority implementation type
     * @param sourceSequence monotonic publication generation
     * @param sourceGenerationFingerprint exact private source-generation identity
     * @param revision signed provider-inventory revision
     * @param materialFingerprint signed material identity
     * @param policyFingerprint signed policy identity
     * @param cohortId exact private deployment cohort
     * @param bindings exact complete provider bindings
     * @param expiresAt exclusive hard validity deadline
     * @param validSignatureCount verified distinct authority count
     * @param requiredSignatureCount configured signature threshold
     */
    record Observation(
            String schemaVersion,
            boolean externallyAttested,
            boolean available,
            String status,
            String sourceType,
            long sourceSequence,
            String sourceGenerationFingerprint,
            long revision,
            String materialFingerprint,
            String policyFingerprint,
            String cohortId,
            List<TestSuiteStabilityPhysicalAttemptProviderInventory.Binding> bindings,
            Instant expiresAt,
            int validSignatureCount,
            int requiredSignatureCount) {

        /** Current private provider-inventory observation generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryObservation.v1";
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
        private static final Pattern STATUS = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
        private static final Pattern SOURCE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

        /** Enforces a complete externally attested snapshot even while hard-expired. */
        public Observation {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            sourceType = normalized(sourceType);
            sourceGenerationFingerprint = normalized(sourceGenerationFingerprint);
            materialFingerprint = normalized(materialFingerprint);
            policyFingerprint = normalized(policyFingerprint);
            cohortId = normalized(cohortId);
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            if (!SCHEMA_VERSION.equals(schemaVersion) || !externallyAttested
                    || !STATUS.matcher(status).matches() || !SOURCE.matcher(sourceType).matches()
                    || sourceSequence < 1
                    || !FINGERPRINT.matcher(sourceGenerationFingerprint).matches()
                    || revision < 1 || !FINGERPRINT.matcher(materialFingerprint).matches()
                    || !FINGERPRINT.matcher(policyFingerprint).matches() || cohortId.isEmpty()
                    || bindings.isEmpty() || bindings.size() > 128 || expiresAt == null
                    || validSignatureCount < requiredSignatureCount
                    || requiredSignatureCount < 1 || validSignatureCount > 32) {
                throw new IllegalArgumentException(
                        "Physical-attempt provider inventory observation is invalid");
            }
        }
    }

    /**
     * Aggregate public provider-inventory state without identities, keys, or fingerprints.
     *
     * @param schemaVersion descriptor protocol generation
     * @param configured whether an inventory authority is assembled
     * @param externallyAttested whether independent signatures govern the inventory
     * @param available whether the current local inventory generation is usable
     * @param status bounded lifecycle state
     * @param revision external monotonic revision
     * @param providerBindingCount aggregate binding cardinality
     * @param properties bounded key-free implementation facts
     */
    record Descriptor(
            String schemaVersion,
            boolean configured,
            boolean externallyAttested,
            boolean available,
            String status,
            long revision,
            int providerBindingCount,
            Map<String, Object> properties) {

        /** First public descriptor generation retained for protocol negotiation. */
        public static final String SCHEMA_VERSION_V1 =
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryDescriptor.v1";

        /** Current public provider-inventory descriptor generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryDescriptor.v2";

        /** Validates aggregate-only public state. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            if (!SCHEMA_VERSION.equals(schemaVersion) || configured != externallyAttested
                    || configured && (revision < 1 || providerBindingCount < 1)
                    || !configured && (available || revision != 0 || providerBindingCount != 0)
                    || status.isEmpty() || revision < 0 || providerBindingCount < 0
                    || providerBindingCount > 128
                    || !DESCRIPTOR_PROPERTIES.containsAll(properties.keySet())
                    || properties.entrySet().stream().anyMatch(entry ->
                    !safeValue(entry.getValue()))) {
                throw new IllegalArgumentException(
                        "Physical-attempt provider inventory descriptor is invalid");
            }
        }

        /**
         * Returns the explicit descriptor for a physically absent inventory authority.
         *
         * @return disabled aggregate descriptor
         */
        public static Descriptor disabled() {
            return new Descriptor(SCHEMA_VERSION, false, false, false,
                    "DISABLED", 0, 0, Map.of());
        }

        private static boolean safeValue(Object value) {
            return value instanceof Boolean
                    || value instanceof Number number && number.longValue() >= 0
                    && number.longValue() <= 1_000_000
                    || value instanceof String text && !text.isBlank() && text.length() <= 128
                    && text.chars().noneMatch(Character::isISOControl);
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
