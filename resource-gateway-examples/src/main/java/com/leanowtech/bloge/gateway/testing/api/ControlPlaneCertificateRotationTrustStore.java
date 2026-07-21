package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Independent public-key trust boundary for control-plane certificate rotation events.
 *
 * <p>This authority is deliberately separate from request identity, TLS trust stores, evidence
 * signing, and certificate material resolution. Compromise of any one boundary therefore cannot
 * both authorize and supply a new workload identity.</p>
 */
public interface ControlPlaneCertificateRotationTrustStore {

    /** Stable fail-closed states safe for metrics, health, and problem mapping. */
    enum VerificationStatus {
        /** Exact binding, policy, time window, fingerprint, and signature quorum passed. */
        VERIFIED,
        /** No usable independent rotation trust policy is configured. */
        UNAVAILABLE,
        /** Globally scoped transport identity differs from the local target. */
        BINDING_MISMATCH,
        /** External rotation policy revision is not accepted locally. */
        POLICY_REJECTED,
        /** Manifest or signature time window is premature, expired, future, or excessive. */
        TIME_INVALID,
        /** Canonical material or bounded protocol shape is invalid. */
        MATERIAL_INVALID,
        /** A trusted signature is malformed or cryptographically invalid. */
        SIGNATURE_INVALID,
        /** Fewer distinct trusted authorities signed than deployment policy requires. */
        QUORUM_NOT_MET
    }

    /**
     * Exact local transport binding a signed event must target.
     *
     * @param deploymentScopeId stable Resource Gateway deployment scope
     * @param targetId independently governed transport identity within that scope
     */
    record ExpectedBinding(String deploymentScopeId, String targetId) {
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Rejects blank, unbounded, or ambiguous local bindings. */
        public ExpectedBinding {
            deploymentScopeId = normalized(deploymentScopeId);
            targetId = normalized(targetId);
            if (!IDENTIFIER.matcher(deploymentScopeId).matches()
                    || !IDENTIFIER.matcher(targetId).matches()) {
                throw new IllegalArgumentException(
                        "Control-plane certificate rotation binding is invalid");
            }
        }
    }

    /**
     * Key-free bounded verification result.
     *
     * @param status closed verification state
     * @param reasonCode stable machine-readable outcome
     * @param eventId external change identity only when verified
     * @param eventFingerprint signed event identity only when verified
     * @param materialFingerprint resolved TLS settings identity only when verified
     * @param validSignatureCount distinct valid authority count
     * @param requiredSignatureCount configured authority threshold
     */
    record Verification(
            VerificationStatus status,
            String reasonCode,
            String eventId,
            String eventFingerprint,
            String materialFingerprint,
            int validSignatureCount,
            int requiredSignatureCount) {
        private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Enforces that only verified results expose rotation identity. */
        public Verification {
            status = Objects.requireNonNull(status, "status");
            reasonCode = normalized(reasonCode);
            eventId = normalized(eventId);
            eventFingerprint = normalized(eventFingerprint);
            materialFingerprint = normalized(materialFingerprint);
            boolean verified = status == VerificationStatus.VERIFIED;
            if (!REASON.matcher(reasonCode).matches()
                    || validSignatureCount < 0 || validSignatureCount > 32
                    || requiredSignatureCount < 0 || requiredSignatureCount > 32
                    || (verified && (!IDENTIFIER.matcher(eventId).matches()
                    || !FINGERPRINT.matcher(eventFingerprint).matches()
                    || !FINGERPRINT.matcher(materialFingerprint).matches()
                    || validSignatureCount < requiredSignatureCount))
                    || (!verified && (!eventId.isBlank() || !eventFingerprint.isBlank()
                    || !materialFingerprint.isBlank()))) {
                throw new IllegalArgumentException(
                        "Control-plane certificate rotation verification is invalid");
            }
        }

        /** @return true only for an exact externally authorized rotation */
        public boolean verified() {
            return status == VerificationStatus.VERIFIED;
        }
    }

    /**
     * Public, fixed-cardinality trust posture for health and capability projection.
     *
     * @param schemaVersion descriptor protocol version
     * @param available whether an authority quorum is currently usable
     * @param trustDomain expected external trust domain
     * @param authorityCount distinct configured authority count
     * @param keyCount configured public verification-key count
     * @param signatureThreshold required distinct signatures
     * @param acceptedPolicyCount accepted exact policy revisions
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

        /** Current trust-store descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationTrustStoreDescriptor.v1";

        /** Rejects contradictory or unbounded health projections. */
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
                        "Control-plane certificate rotation trust descriptor is invalid");
            }
        }
    }

    /**
     * Verifies an event against exact local binding and observation time.
     *
     * @param event untrusted external event
     * @param expected exact local transport binding
     * @param observedAt authoritative observation time
     * @return closed verification outcome without key or material disclosure
     */
    Verification verify(
            ControlPlaneCertificateRotationEvent event,
            ExpectedBinding expected,
            Instant observedAt);

    /** @return key-free current trust posture */
    Descriptor descriptor();

    /** @return a fail-closed trust store for disabled product paths */
    static ControlPlaneCertificateRotationTrustStore unavailable() {
        return new ControlPlaneCertificateRotationTrustStore() {
            @Override
            public Verification verify(
                    ControlPlaneCertificateRotationEvent event,
                    ExpectedBinding expected,
                    Instant observedAt) {
                return new Verification(VerificationStatus.UNAVAILABLE,
                        "CERTIFICATE_ROTATION_TRUST_UNAVAILABLE", "", "", "", 0, 0);
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor(Descriptor.SCHEMA_VERSION, false, "",
                        0, 0, 0, 0, Map.of(
                        "algorithm", "Ed25519",
                        "privateMaterialPresent", false,
                        "sourceType", "UNAVAILABLE"));
            }
        };
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
