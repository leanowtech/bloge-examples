package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Independent trust boundary for enterprise worker-quarantine change authorizations.
 *
 * <p>The store contains public governance verification material only. It is intentionally separate
 * from request-identity JWT trust, Resource Gateway evidence-signing keys, and the quarantine
 * maker/checker database so compromise of one authority does not collapse all authorization
 * layers.</p>
 */
public interface WorkerQuarantineChangeAuthorizationTrustStore {

    /** Stable fail-closed verification states safe for metrics and problem mapping. */
    enum VerificationStatus {
        /** Exact authorization and configured quorum passed. */
        VERIFIED,
        /** No usable independent external trust policy is configured. */
        UNAVAILABLE,
        /** Trust domain, action, scope, or subject differs from the requested mutation. */
        BINDING_MISMATCH,
        /** The external policy revision is not accepted by deployment policy. */
        POLICY_REJECTED,
        /** The authorization time window is invalid, premature, expired, or excessive. */
        TIME_INVALID,
        /** Canonical material or bounded protocol shape is invalid. */
        MATERIAL_INVALID,
        /** A trusted signature is malformed or cryptographically invalid. */
        SIGNATURE_INVALID,
        /** Fewer distinct trusted authorities signed than deployment policy requires. */
        QUORUM_NOT_MET
    }

    /**
     * Exact local mutation binding that an external authorization must approve.
     *
     * @param scopeFingerprint identity-derived tenant/organization/project/environment fingerprint
     * @param subjectFingerprint exact quarantine claim, reason, and discard intent fingerprint
     */
    record ExpectedBinding(String scopeFingerprint, String subjectFingerprint) {
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects non-canonical local authorization bindings. */
        public ExpectedBinding {
            scopeFingerprint = normalized(scopeFingerprint);
            subjectFingerprint = normalized(subjectFingerprint);
            if (!FINGERPRINT.matcher(scopeFingerprint).matches()
                    || !FINGERPRINT.matcher(subjectFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "External change-authorization binding is invalid");
            }
        }
    }

    /**
     * Bounded verification result without signatures, keys, tickets, or scope values.
     *
     * @param status closed verification state
     * @param reasonCode stable machine-readable result reason
     * @param authorizationId external authorization identity only when verified
     * @param materialFingerprint exact authorization identity only when verified
     * @param validSignatureCount distinct valid trusted authority count
     * @param requiredSignatureCount configured authority threshold
     */
    record Verification(
            VerificationStatus status,
            String reasonCode,
            String authorizationId,
            String materialFingerprint,
            int validSignatureCount,
            int requiredSignatureCount) {
        private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Enforces that only successful results expose authorization identity. */
        public Verification {
            status = Objects.requireNonNull(status, "status");
            reasonCode = normalized(reasonCode);
            authorizationId = normalized(authorizationId);
            materialFingerprint = normalized(materialFingerprint);
            if (!REASON.matcher(reasonCode).matches()
                    || validSignatureCount < 0 || requiredSignatureCount < 0
                    || validSignatureCount > 32 || requiredSignatureCount > 32) {
                throw new IllegalArgumentException(
                        "External change-authorization verification is invalid");
            }
            boolean verified = status == VerificationStatus.VERIFIED;
            if ((verified && (authorizationId.isBlank() || materialFingerprint.isBlank()))
                    || (!verified && (!authorizationId.isBlank()
                    || !materialFingerprint.isBlank()))
                    || (verified && (!IDENTIFIER.matcher(authorizationId).matches()
                    || !FINGERPRINT.matcher(materialFingerprint).matches()
                    || validSignatureCount < requiredSignatureCount))) {
                throw new IllegalArgumentException(
                        "External change-authorization verification identity is invalid");
            }
        }

        /** @return true only for an exact externally authorized mutation */
        public boolean verified() {
            return status == VerificationStatus.VERIFIED;
        }
    }

    /**
     * Key-free capability descriptor for readiness and operations.
     *
     * @param schemaVersion descriptor protocol version
     * @param available whether the configured authority quorum is currently usable
     * @param trustDomain expected external governance trust domain
     * @param authorityCount number of distinct configured authorities
     * @param keyCount number of configured public verification keys
     * @param signatureThreshold required distinct authority signatures
     * @param acceptedPolicyCount number of accepted exact policy fingerprints
     * @param properties bounded operational metadata without key material
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

        /** Current trust-store descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.workerQuarantineChangeAuthorizationTrustStoreDescriptor.v1";

        /** Validates key-free bounded capability metadata. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion).isBlank()
                    ? SCHEMA_VERSION : normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            if (!SCHEMA_VERSION.equals(schemaVersion) || authorityCount < 0 || authorityCount > 32
                    || keyCount < 0 || keyCount > 64 || signatureThreshold < 0
                    || signatureThreshold > authorityCount || acceptedPolicyCount < 0
                    || acceptedPolicyCount > 32 || (available && (trustDomain.isBlank()
                    || authorityCount < 1 || keyCount < 1 || signatureThreshold < 1
                    || acceptedPolicyCount < 1))) {
                throw new IllegalArgumentException(
                        "External change-authorization trust descriptor is invalid");
            }
        }
    }

    /**
     * Verifies one untrusted authorization against exact local intent and external trust policy.
     *
     * @param authorization untrusted detached authorization envelope
     * @param expected exact local scope and mutation binding
     * @param observedAt authoritative verification time
     * @return bounded fail-closed verification result
     */
    Verification verify(
            WorkerQuarantineChangeAuthorization authorization,
            ExpectedBinding expected,
            Instant observedAt);

    /** @return public-key-free operational capability descriptor */
    Descriptor descriptor();

    /** @return fail-closed store used when external governance trust is absent */
    static WorkerQuarantineChangeAuthorizationTrustStore unavailable() {
        return new WorkerQuarantineChangeAuthorizationTrustStore() {
            private final Descriptor descriptor = new Descriptor(
                    "", false, "", 0, 0, 0, 0,
                    Map.of("sourceType", "UNAVAILABLE", "privateMaterialPresent", false));

            @Override
            public Verification verify(
                    WorkerQuarantineChangeAuthorization authorization,
                    ExpectedBinding expected,
                    Instant observedAt) {
                return new Verification(VerificationStatus.UNAVAILABLE,
                        "CHANGE_AUTHORIZATION_TRUST_UNAVAILABLE", "", "", 0, 0);
            }

            @Override
            public Descriptor descriptor() {
                return descriptor;
            }
        };
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
