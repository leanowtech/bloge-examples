package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Independent public-key trust boundary for normalized certificate-status publications.
 *
 * <p>This authority is separate from TLS trust, request identity, rotation authorization, and
 * certificate material resolution. A compromised transport or rotation signer therefore cannot
 * also manufacture a fresh GOOD status publication.</p>
 */
public interface ControlPlaneCertificateStatusTrustStore {

    /** Closed verification outcomes suitable for bounded health and audit projection. */
    enum VerificationStatus {
        /** Exact binding, policy, time, fingerprint, and independent signature quorum passed. */
        VERIFIED,
        /** No usable independent status trust policy is configured. */
        UNAVAILABLE,
        /** Publication deployment or trust domain differs from the local deployment. */
        BINDING_MISMATCH,
        /** External status policy revision is not accepted locally. */
        POLICY_REJECTED,
        /** Publication or evidence freshness window is invalid. */
        TIME_INVALID,
        /** Canonical material or bounded protocol shape is invalid. */
        MATERIAL_INVALID,
        /** A trusted signature is malformed or cryptographically invalid. */
        SIGNATURE_INVALID,
        /** Fewer distinct trusted authorities signed than policy requires. */
        QUORUM_NOT_MET
    }

    /**
     * Exact local deployment binding a signed publication must cover.
     *
     * @param deploymentScopeId stable Resource Gateway deployment scope
     */
    record ExpectedBinding(String deploymentScopeId) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Rejects blank, unbounded, or ambiguous deployment identities. */
        public ExpectedBinding {
            deploymentScopeId = normalized(deploymentScopeId);
            if (!IDENTIFIER.matcher(deploymentScopeId).matches()) {
                throw new IllegalArgumentException(
                        "Control-plane certificate status binding is invalid");
            }
        }
    }

    /**
     * Key-free bounded verification result.
     *
     * @param status closed verification state
     * @param reasonCode stable machine-readable outcome
     * @param publicationId identity exposed only when verified
     * @param publicationFingerprint fingerprint exposed only when verified
     * @param sequence contiguous cursor exposed only when verified
     * @param validSignatureCount distinct valid authority count
     * @param requiredSignatureCount configured authority threshold
     */
    record Verification(
            VerificationStatus status,
            String reasonCode,
            String publicationId,
            String publicationFingerprint,
            long sequence,
            int validSignatureCount,
            int requiredSignatureCount) {
        private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Ensures rejected outcomes never disclose publication identity. */
        public Verification {
            status = Objects.requireNonNull(status, "status");
            reasonCode = normalized(reasonCode);
            publicationId = normalized(publicationId);
            publicationFingerprint = normalized(publicationFingerprint);
            boolean verified = status == VerificationStatus.VERIFIED;
            if (!REASON.matcher(reasonCode).matches()
                    || validSignatureCount < 0 || validSignatureCount > 32
                    || requiredSignatureCount < 0 || requiredSignatureCount > 32
                    || (verified && (!IDENTIFIER.matcher(publicationId).matches()
                    || !FINGERPRINT.matcher(publicationFingerprint).matches()
                    || sequence < 1 || validSignatureCount < requiredSignatureCount))
                    || (!verified && (!publicationId.isBlank()
                    || !publicationFingerprint.isBlank() || sequence != 0))) {
                throw new IllegalArgumentException(
                        "Control-plane certificate status verification is invalid");
            }
        }

        /** @return true only for an exact externally authorized publication */
        public boolean verified() {
            return status == VerificationStatus.VERIFIED;
        }
    }

    /**
     * Fixed-cardinality public trust posture.
     *
     * @param schemaVersion descriptor protocol version
     * @param available whether an authority quorum is currently usable
     * @param trustDomain expected external trust domain
     * @param authorityCount configured authority count
     * @param keyCount configured public-key count
     * @param signatureThreshold required distinct signatures
     * @param acceptedPolicyCount accepted policy revision count
     * @param properties bounded key-free operational facts
     */
    record Descriptor(
            String schemaVersion,
            boolean available,
            String trustDomain,
            int authorityCount,
            int keyCount,
            int signatureThreshold,
            int acceptedPolicyCount,
            Map<String, Object> properties) {

        /** Current status-trust descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateStatusTrustStoreDescriptor.v1";

        /** Rejects contradictory or unbounded public trust posture. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion).isBlank()
                    ? SCHEMA_VERSION : normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || authorityCount < 0 || authorityCount > 32
                    || keyCount < 0 || keyCount > 64
                    || signatureThreshold < 0 || signatureThreshold > authorityCount
                    || acceptedPolicyCount < 0 || acceptedPolicyCount > 32
                    || properties.size() > 16
                    || (available && (trustDomain.isBlank() || authorityCount < 1
                    || keyCount < 1 || signatureThreshold < 1
                    || acceptedPolicyCount < 1))) {
                throw new IllegalArgumentException(
                        "Control-plane certificate status trust descriptor is invalid");
            }
        }
    }

    /**
     * Verifies one untrusted publication against exact local binding and observation time.
     *
     * @param publication untrusted normalized status publication
     * @param expected exact local deployment binding
     * @param observedAt authoritative observation time
     * @return closed verification outcome without keys or native revocation payloads
     */
    Verification verify(
            ControlPlaneCertificateStatusPublication publication,
            ExpectedBinding expected,
            Instant observedAt);

    /** @return key-free current trust posture */
    Descriptor descriptor();

    /** @return a fail-closed trust store for disabled product paths */
    static ControlPlaneCertificateStatusTrustStore unavailable() {
        return new ControlPlaneCertificateStatusTrustStore() {
            @Override
            public Verification verify(
                    ControlPlaneCertificateStatusPublication publication,
                    ExpectedBinding expected,
                    Instant observedAt) {
                return new Verification(VerificationStatus.UNAVAILABLE,
                        "CERTIFICATE_STATUS_TRUST_UNAVAILABLE", "", "", 0, 0, 0);
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor(Descriptor.SCHEMA_VERSION, false, "", 0, 0, 0, 0,
                        Map.of("algorithm", "Ed25519", "sourceType", "UNAVAILABLE",
                                "privateMaterialPresent", false));
            }
        };
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
