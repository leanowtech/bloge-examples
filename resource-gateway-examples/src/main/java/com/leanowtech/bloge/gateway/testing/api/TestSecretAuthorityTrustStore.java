package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Verification boundary for signed test-secret authority responses.
 *
 * <p>This trust domain is intentionally distinct from evidence signing and suite-stability policy
 * decisions. Static configuration is the first implementation; dynamic JWKS, KMS or
 * certificate-backed providers can implement the same exact verification contract later.</p>
 */
public interface TestSecretAuthorityTrustStore {

    /** Closed trust outcomes; only {@link #VERIFIED} permits secret use or signed denial. */
    enum VerificationStatus {
        VERIFIED,
        BINDING_MISMATCH,
        AUTHORITY_MISMATCH,
        MATERIAL_INVALID,
        TIME_INVALID,
        KEY_UNAVAILABLE,
        SIGNATURE_INVALID
    }

    /**
     * Payload-free verification result.
     *
     * @param status closed trust outcome
     * @param failureCode stable diagnostic code
     */
    record Verification(VerificationStatus status, String failureCode) {
        /** @return whether the response can be consumed */
        public boolean verified() {
            return status == VerificationStatus.VERIFIED;
        }
    }

    /** Allowed key-free descriptor fields shared by static and future dynamic trust providers. */
    Set<String> DESCRIPTOR_PROPERTIES = Set.of(
            "algorithm", "signedResponses", "challengeBound", "privateMaterialPresent",
            "activeKeyCount", "maximumResponseLifetimeSeconds", "clockSkewSeconds",
            "minimumRemainingValidityMillis", "refreshMode", "refreshState",
            "refreshIntervalSeconds", "maximumSnapshotAgeSeconds",
            "unknownKeyRefreshIntervalSeconds", "failClosedOnRefreshFailure",
            "conditionalRequests", "automaticRefresh");

    /**
     * Key-free trust readiness.
     *
     * @param schemaVersion descriptor protocol generation
     * @param available whether current responses can be verified
     * @param providerType trust source type
     * @param expectedAuthorityId exact accepted authority identity
     * @param keyCount bounded public-key inventory
     * @param properties bounded non-secret trust semantics
     */
    record Descriptor(
            String schemaVersion,
            boolean available,
            String providerType,
            String expectedAuthorityId,
            int keyCount,
            Map<String, Object> properties) {

        /** Current key-free descriptor version. */
        public static final String SCHEMA_VERSION =
                "bloge.testSecretAuthorityTrustDescriptor.v1";

        /** Defensively freezes and validates payload-free readiness facts. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            if (schemaVersion.isBlank()) {
                schemaVersion = SCHEMA_VERSION;
            }
            providerType = normalized(providerType);
            if (providerType.isBlank()) {
                providerType = "UNAVAILABLE";
            }
            expectedAuthorityId = normalized(expectedAuthorityId);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            if (!SCHEMA_VERSION.equals(schemaVersion) || keyCount < 0 || keyCount > 64
                    || available && (expectedAuthorityId.isBlank() || keyCount == 0)
                    || !DESCRIPTOR_PROPERTIES.containsAll(properties.keySet())
                    || properties.entrySet().stream().anyMatch(
                    entry -> !safeDescriptorValue(entry.getValue()))) {
                throw new IllegalArgumentException("Invalid test-secret authority trust descriptor");
            }
        }
    }

    /**
     * Verifies response binding, material, authority, time, key lifecycle and signature.
     *
     * @param response untrusted external response
     * @param request exact local request the response must answer
     * @param observedAt trusted local verification time
     * @return closed payload-free verification result
     */
    Verification verify(
            TestSecretAuthorityResponse response,
            TestSecretAuthorityRequest request,
            Instant observedAt);

    /** @return key-free current trust readiness */
    Descriptor descriptor();

    /** @return fail-closed trust provider used when no verification authority exists */
    static TestSecretAuthorityTrustStore unavailable() {
        return new TestSecretAuthorityTrustStore() {
            @Override
            public Verification verify(
                    TestSecretAuthorityResponse response,
                    TestSecretAuthorityRequest request,
                    Instant observedAt) {
                return new Verification(VerificationStatus.KEY_UNAVAILABLE,
                        "RG.TEST.SECRET_AUTHORITY_KEY_UNAVAILABLE");
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor("", false, "UNAVAILABLE", "", 0, Map.of());
            }
        };
    }

    private static boolean safeDescriptorValue(Object value) {
        return value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof String text && text.length() <= 255;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
