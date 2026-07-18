package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Current-authority revalidation boundary for one durable stability job.
 *
 * <p>The durable principal is an authenticated submission snapshot, not perpetual authority.
 * Implementations must consult the current policy, delegation, tenant, and environment state
 * without returning credentials or business payloads.</p>
 */
public interface TestSuiteStabilityJobAuthorizer {

    Set<String> DESCRIPTOR_PROPERTIES = Set.of(
            "protocolVersion", "responseProtocolVersion", "signedDecisions",
            "challengeBound", "redirectsFollowed", "automaticRetries",
            "privateMaterialPresent", "requestTimeoutMillis", "trustProviderType",
            "trustLocalAvailable", "trustRefreshState", "trustRefreshIntervalSeconds",
            "trustMaximumSnapshotAgeSeconds", "trustFailClosedOnRefreshFailure",
            "trustAutomaticRefresh", "trustCohortConfigured", "trustCohortConverged",
            "trustCohortStatus", "trustCohortExpectedReplicaCount",
            "trustCohortLiveReplicaCount", "trustCohortHealthyReplicaCount",
            "trustCohortDistinctSnapshotCount", "trustCohortLeaseDurationSeconds",
            "trustCohortDistinctServingInventoryGenerationCount",
            "trustCohortDatabaseAuthority", "trustCohortExactConfiguredInventory",
            "trustCohortExternallyAttestedInventory",
            "trustCohortDynamicallyRefreshedInventory",
            "trustCohortWitnessedInventoryPublications",
            "trustCohortDurableInventoryPublicationFloor",
            "trustCohortManagedInventoryTrustRoots",
            "trustCohortAtomicDualInventoryTrustRootPublication");

    /**
     * Key-free deployment readiness descriptor for capability and startup diagnostics.
     *
     * @param schemaVersion descriptor generation
     * @param available whether this adapter is fully configured for current-authority decisions
     * @param providerType deployment-owned provider type
     * @param expectedAuthorityId expected external authority, possibly blank for custom providers
     * @param properties bounded non-secret protocol semantics
     */
    record Descriptor(
            String schemaVersion,
            boolean available,
            String providerType,
            String expectedAuthorityId,
            Map<String, Object> properties) {
        /** Descriptor protocol generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityJobAuthorizerDescriptor.v1";

        /** Defensively freezes non-secret descriptor fields. */
        public Descriptor {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                    ? SCHEMA_VERSION : schemaVersion.trim();
            providerType = providerType == null ? "UNAVAILABLE" : providerType.trim();
            expectedAuthorityId = expectedAuthorityId == null
                    ? "" : expectedAuthorityId.trim();
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            if (!SCHEMA_VERSION.equals(schemaVersion) || providerType.isBlank()
                    || !DESCRIPTOR_PROPERTIES.containsAll(properties.keySet())
                    || properties.size() > DESCRIPTOR_PROPERTIES.size()
                    || properties.entrySet().stream().anyMatch(
                    entry -> !safeDescriptorValue(entry.getValue()))) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability authorizer descriptor");
            }
        }
    }

    /** Current authorization decisions consumed before engine execution. */
    enum Decision {
        /** Current authority still permits the exact submitted stability intent. */
        AUTHORIZED,
        /** Authority was definitively revoked or no longer satisfies policy. */
        REVOKED,
        /** Current authority could not be determined and execution must fail closed. */
        UNAVAILABLE
    }

    /**
     * Payload-free revalidation result.
     *
     * @param decision current authority decision
     * @param failureCode stable diagnostic only when not authorized
     */
    record Authorization(Decision decision, String failureCode) {

        private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

        /** Enforces an empty success diagnostic or one bounded failure code. */
        public Authorization {
            decision = Objects.requireNonNull(decision, "decision");
            failureCode = failureCode == null ? "" : failureCode.trim();
            if ((decision == Decision.AUTHORIZED) != failureCode.isBlank()
                    || !failureCode.isBlank() && !CODE.matcher(failureCode).matches()) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability job authorization result");
            }
        }

        /** @return successful current-authority result */
        public static Authorization authorized() {
            return new Authorization(Decision.AUTHORIZED, "");
        }

        /** @return definitive current-authority revocation */
        public static Authorization revoked(String failureCode) {
            return new Authorization(Decision.REVOKED, failureCode);
        }

        /** @return fail-closed current-authority ambiguity */
        public static Authorization unavailable(String failureCode) {
            return new Authorization(Decision.UNAVAILABLE, failureCode);
        }
    }

    /**
     * Revalidates one credential-free immutable job immediately before engine execution.
     *
     * @param job integrity-verified claimed job and durable principal snapshot
     * @return current payload-free decision
     */
    Authorization reauthorize(TestSuiteStabilityJobRecord job);

    /**
     * Reports deployment readiness without exposing endpoint, credential or key material.
     *
     * <p>Custom authorizers remain source-compatible through this fail-closed default. Providers
     * should override it when they can make stronger machine-readable guarantees.</p>
     *
     * @return key-free current-authority readiness
     */
    default Descriptor descriptor() {
        return new Descriptor("", false, "CUSTOM_UNDECLARED", "", Map.of(
                "signedDecisions", false,
                "challengeBound", false,
                "privateMaterialPresent", false));
    }

    private static boolean safeDescriptorValue(Object value) {
        return value instanceof Boolean || value instanceof String text && text.length() <= 255
                || value instanceof Number number && number.longValue() >= 0;
    }
}
