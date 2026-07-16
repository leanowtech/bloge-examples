package com.leanowtech.bloge.gateway.integration;

import java.time.Instant;
import java.util.Map;

/**
 * Independently configured governance trust anchors for evidence key-set publications.
 *
 * <p>Implementations must resolve authority keys from a channel independent of the publication
 * response. Resource Gateway uses this interface only to verify and distribute externally signed
 * policy; it does not receive governance private keys.</p>
 */
public interface EvidenceKeySetTrustStore {

    /** Stable trust-verification states safe to expose through bounded problem details. */
    enum VerificationStatus {
        VERIFIED,
        UNAVAILABLE,
        IDENTITY_MISMATCH,
        MATERIAL_INVALID,
        TIME_INVALID,
        SIGNATURE_INVALID,
        QUORUM_NOT_MET
    }

    /**
     * Payload-free verification result.
     *
     * @param status bounded verification state
     * @param reasonCode stable machine-readable reason
     * @param validSignatureCount independently trusted valid signature count
     * @param requiredSignatureCount configured quorum
     */
    record Verification(
            VerificationStatus status,
            String reasonCode,
            int validSignatureCount,
            int requiredSignatureCount
    ) {
        /** Normalizes bounded counters and reason identity. */
        public Verification {
            if (status == null) {
                throw new IllegalArgumentException("Evidence trust verification status is required");
            }
            reasonCode = normalize(reasonCode);
            validSignatureCount = Math.max(0, validSignatureCount);
            requiredSignatureCount = Math.max(0, requiredSignatureCount);
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("Evidence trust verification reason is invalid");
            }
        }

        /** @return true only when the configured independent quorum passed */
        public boolean verified() {
            return status == VerificationStatus.VERIFIED;
        }
    }

    /**
     * Public capability descriptor without authority key material.
     *
     * @param schemaVersion descriptor protocol version
     * @param available whether a non-empty externally configured quorum is usable
     * @param trustDomain expected trust domain
     * @param logId expected append-only log id
     * @param authorityCount configured authority count
     * @param signatureThreshold required M-of-N threshold
     * @param properties bounded operational metadata without keys or diagnostics
     */
    record Descriptor(
            String schemaVersion,
            boolean available,
            String trustDomain,
            String logId,
            int authorityCount,
            int signatureThreshold,
            Map<String, Object> properties
    ) {
        /** Current descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "toolStudio.resourceGateway.evidenceTrustStoreDescriptor.v1";

        /** Normalizes safe descriptor metadata. */
        public Descriptor {
            schemaVersion = normalize(schemaVersion).isBlank() ? SCHEMA_VERSION : normalize(schemaVersion);
            trustDomain = normalize(trustDomain);
            logId = normalize(logId);
            authorityCount = Math.max(0, authorityCount);
            signatureThreshold = Math.max(0, signatureThreshold);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || (available && (trustDomain.isBlank() || logId.isBlank()
                    || authorityCount < 1 || signatureThreshold < 1
                    || signatureThreshold > authorityCount))) {
                throw new IllegalArgumentException("Evidence trust-store descriptor is invalid");
            }
        }
    }

    /**
     * Verifies publication identity, freshness, canonical material, and authority quorum.
     *
     * @param publication untrusted publication candidate
     * @param observedAt verification time
     * @return bounded verification result
     */
    Verification verify(EvidenceKeySetTrustPublication publication, Instant observedAt);

    /** @return key-free capability descriptor */
    Descriptor descriptor();

    /** @return fail-closed store used when no independent trust policy is configured */
    static EvidenceKeySetTrustStore unavailable() {
        return new EvidenceKeySetTrustStore() {
            private final Descriptor descriptor = new Descriptor("", false, "", "", 0, 0,
                    Map.of("sourceType", "UNAVAILABLE"));

            @Override
            public Verification verify(EvidenceKeySetTrustPublication publication, Instant observedAt) {
                return new Verification(VerificationStatus.UNAVAILABLE,
                        "TRUST_STORE_UNAVAILABLE", 0, 0);
            }

            @Override
            public Descriptor descriptor() {
                return descriptor;
            }
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
