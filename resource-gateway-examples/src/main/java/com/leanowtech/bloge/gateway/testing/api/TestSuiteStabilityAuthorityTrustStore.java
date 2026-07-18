package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Verification boundary for signed current-authority decisions.
 *
 * <p>Static configuration is one implementation. Deployments may provide a dynamic JWKS, KMS or
 * certificate-backed implementation without changing worker or protocol semantics.</p>
 */
public interface TestSuiteStabilityAuthorityTrustStore {

    Set<String> DESCRIPTOR_PROPERTIES = Set.of(
            "algorithm", "signedDecisions", "challengeBound", "privateMaterialPresent",
            "activeKeyCount", "maximumDecisionLifetimeSeconds", "clockSkewSeconds",
            "minimumRemainingValidityMillis");

    /** Closed trust outcomes; only {@link #VERIFIED} permits use of the signed decision. */
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
        /** @return whether the signed decision is safe to consume */
        public boolean verified() {
            return status == VerificationStatus.VERIFIED;
        }
    }

    /**
     * Key-free deployment readiness descriptor.
     *
     * @param schemaVersion descriptor generation
     * @param available whether at least one configured key can verify current decisions
     * @param providerType trust source type
     * @param expectedAuthorityId exact expected policy authority
     * @param keyCount bounded public key inventory
     * @param properties bounded non-secret trust semantics
     */
    record Descriptor(
            String schemaVersion,
            boolean available,
            String providerType,
            String expectedAuthorityId,
            int keyCount,
            Map<String, Object> properties) {
        /** Descriptor protocol generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityAuthorityTrustDescriptor.v1";

        /** Defensively freezes non-secret descriptor properties. */
        public Descriptor {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                    ? SCHEMA_VERSION : schemaVersion.trim();
            providerType = providerType == null ? "UNAVAILABLE" : providerType.trim();
            expectedAuthorityId = expectedAuthorityId == null
                    ? "" : expectedAuthorityId.trim();
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            if (!SCHEMA_VERSION.equals(schemaVersion) || keyCount < 0 || keyCount > 64
                    || available && (expectedAuthorityId.isBlank() || keyCount == 0)
                    || !DESCRIPTOR_PROPERTIES.containsAll(properties.keySet())
                    || properties.size() > DESCRIPTOR_PROPERTIES.size()
                    || properties.entrySet().stream().anyMatch(
                    entry -> !safeDescriptorValue(entry.getValue()))) {
                throw new IllegalArgumentException("Invalid authority trust descriptor");
            }
        }
    }

    /**
     * Verifies response binding, time, material fingerprint, key lifecycle and signature.
     *
     * @param response untrusted external response
     * @param request exact local request the response must answer
     * @param observedAt local verification time
     * @return closed payload-free verification result
     */
    Verification verify(
            TestSuiteStabilityAuthorityResponse response,
            TestSuiteStabilityAuthorityRequest request,
            Instant observedAt);

    /** @return key-free deployment readiness */
    Descriptor descriptor();

    /** @return fail-closed trust store used when no current-authority trust is configured */
    static TestSuiteStabilityAuthorityTrustStore unavailable() {
        return new TestSuiteStabilityAuthorityTrustStore() {
            @Override
            public Verification verify(
                    TestSuiteStabilityAuthorityResponse response,
                    TestSuiteStabilityAuthorityRequest request,
                    Instant observedAt) {
                return new Verification(VerificationStatus.KEY_UNAVAILABLE,
                        "RG.TEST.STABILITY_JOB_AUTHORITY_KEY_UNAVAILABLE");
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor("", false, "UNAVAILABLE", "", 0, Map.of(
                        "signedDecisions", true,
                        "challengeBound", true,
                        "privateMaterialPresent", false));
            }
        };
    }

    private static boolean safeDescriptorValue(Object value) {
        return value instanceof Boolean || value instanceof String text && text.length() <= 255
                || value instanceof Number number && number.longValue() >= 0;
    }
}
