package com.leanowtech.bloge.gateway.testkit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deployment-owned authority dependencies for formal Capability Studio stage acceptance.
 *
 * <p>Implementations are discovered by {@link java.util.ServiceLoader} only after a Stage
 * Acceptance Result v2 document has passed local schema and semantic verification and declares
 * {@code PASS}. An enterprise provider should obtain resolver storage, issuer pins, and owner
 * authority from independently governed deployment configuration. Resource Gateway must not
 * implement this provider by minting its own environment or owner evidence.</p>
 *
 * <p>Provider methods and authority callbacks are synchronous. Providers MUST NOT spawn
 * background threads or write asynchronously. The current in-process output isolation captures
 * synchronous output only; process-level isolation is the security boundary for untrusted
 * Providers and is reserved for a future subprocess integration.</p>
 */
public interface CapabilityStudioStageAcceptanceAuthorityProvider {
    /** Canonical message version for target-bound Provider fingerprints. */
    String TARGET_BOUND_BINDING_MESSAGE_VERSION =
            "resource-gateway.capability-studio.stage-acceptance-provider-binding.v1";

    /**
     * Immutable target admission material for one formal verification attempt.
     *
     * <p>The raw documents are copied at construction and on every access. The callback
     * interfaces receive only the typed, payload-free facts produced by the target verifier;
     * neither this record nor its callbacks receive a Stage Result.</p>
     *
     * @param targetBindingBytes raw Stage Acceptance Target Binding v1 bytes
     * @param candidateAttestationBytes raw Candidate Attestation v1 bytes
     * @param environmentAttestationBytes raw Environment Attestation v1 bytes
     * @param verificationContext deployment-owned target verification context
     * @param candidateAuthority Candidate Authority callback
     * @param environmentAuthority Environment Authority callback
     */
    record TargetAdmissionBinding(
            byte[] targetBindingBytes,
            byte[] candidateAttestationBytes,
            byte[] environmentAttestationBytes,
            CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext
                    verificationContext,
            CapabilityStudioStageAcceptanceTargetBindingVerifier.CandidateAuthority
                    candidateAuthority,
            CapabilityStudioStageAcceptanceTargetBindingVerifier.EnvironmentAuthority
                    environmentAuthority) {
        /** Validates and snapshots the target admission boundary. */
        public TargetAdmissionBinding {
            targetBindingBytes = boundedCopy(targetBindingBytes,
                    CapabilityStudioStageAcceptanceTargetBindingVerifier.MAXIMUM_TARGET_BINDING_BYTES,
                    "targetBindingBytes");
            candidateAttestationBytes = boundedCopy(candidateAttestationBytes,
                    CapabilityStudioStageAcceptanceTargetBindingVerifier
                            .MAXIMUM_CANDIDATE_ATTESTATION_BYTES,
                    "candidateAttestationBytes");
            environmentAttestationBytes = boundedCopy(environmentAttestationBytes,
                    CapabilityStudioStageAcceptanceTargetBindingVerifier
                            .MAXIMUM_ENVIRONMENT_ATTESTATION_BYTES,
                    "environmentAttestationBytes");
            verificationContext = Objects.requireNonNull(
                    verificationContext, "verificationContext is required");
            candidateAuthority = Objects.requireNonNull(
                    candidateAuthority, "candidateAuthority is required");
            environmentAuthority = Objects.requireNonNull(
                    environmentAuthority, "environmentAuthority is required");
        }

        /**
         * Returns a defensive copy of the target binding bytes.
         *
         * @return copied target binding bytes
         */
        @Override
        public byte[] targetBindingBytes() {
            return targetBindingBytes.clone();
        }

        /**
         * Returns a defensive copy of the Candidate Attestation bytes.
         *
         * @return copied Candidate Attestation bytes
         */
        @Override
        public byte[] candidateAttestationBytes() {
            return candidateAttestationBytes.clone();
        }

        /**
         * Returns a defensive copy of the Environment Attestation bytes.
         *
         * @return copied Environment Attestation bytes
         */
        @Override
        public byte[] environmentAttestationBytes() {
            return environmentAttestationBytes.clone();
        }

        /**
         * Returns the target fingerprint pinned by the immutable verification context.
         *
         * @return expected target-binding fingerprint
         */
        public String targetBindingFingerprint() {
            return verificationContext.expectedTargetBindingFingerprint();
        }

        /** Redacted representation that never includes raw admission bytes or callbacks. */
        @Override
        public String toString() {
            return "TargetAdmissionBinding[targetBindingBytes=<redacted>, "
                    + "candidateAttestationBytes=<redacted>, "
                    + "environmentAttestationBytes=<redacted>, authorities=REDACTED]";
        }
    }

    /**
     * One immutable snapshot of all authority dependencies used by one verification attempt.
     *
     * <p>Implementations must construct this value from the same deployment snapshot as the
     * fingerprint. Consumers must not reconstruct a binding by calling the legacy accessors
     * independently.</p>
     *
     * @param fingerprint lowercase deployment binding fingerprint
     * @param resolver exact-coordinate evidence resolver
     * @param issuerPolicy pinned evidence issuer policy
     * @param ownerAuthority organizational owner authority
     */
    record AuthorityBinding(
            String fingerprint,
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver resolver,
            CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy issuerPolicy,
            CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority) {
        /** Validates and defensively fixes the authority snapshot boundary. */
        public AuthorityBinding {
            if (fingerprint == null || resolver == null || issuerPolicy == null
                    || ownerAuthority == null) {
                throw new IllegalArgumentException("authority binding is incomplete");
            }
        }
    }

    /**
     * Atomic target-bound Provider snapshot for one formal verification attempt.
     *
     * @param fingerprint aggregate outer Provider fingerprint
     * @param authorityBinding legacy-compatible authority-material snapshot
     * @param targetAdmissionBinding immutable target admission snapshot
     */
    record TargetBoundAuthorityBinding(
            String fingerprint,
            AuthorityBinding authorityBinding,
            TargetAdmissionBinding targetAdmissionBinding) {
        /** Canonical message version used by {@link #aggregateFingerprint}. */
        public static final String MESSAGE_VERSION = TARGET_BOUND_BINDING_MESSAGE_VERSION;

        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

        /**
         * Creates a target-bound snapshot with a computed aggregate fingerprint.
         *
         * @param authorityBinding legacy-compatible authority-material snapshot
         * @param targetAdmissionBinding immutable target admission snapshot
         */
        public TargetBoundAuthorityBinding(
                AuthorityBinding authorityBinding,
                TargetAdmissionBinding targetAdmissionBinding) {
            this(aggregateFingerprint(MESSAGE_VERSION,
                            authorityBinding == null ? null : authorityBinding.fingerprint(),
                            targetAdmissionBinding == null
                                    ? null : targetAdmissionBinding.targetBindingFingerprint()),
                    authorityBinding, targetAdmissionBinding);
        }

        /** Validates that the supplied outer fingerprint binds the complete atomic snapshot. */
        public TargetBoundAuthorityBinding {
            if (fingerprint == null || authorityBinding == null
                    || targetAdmissionBinding == null) {
                throw new IllegalArgumentException("target-bound authority binding is incomplete");
            }
            String expected = aggregateFingerprint(MESSAGE_VERSION,
                    authorityBinding.fingerprint(),
                    targetAdmissionBinding.targetBindingFingerprint());
            if (!FINGERPRINT.matcher(fingerprint).matches() || !fingerprint.equals(expected)) {
                throw new IllegalArgumentException(
                        "target-bound authority binding fingerprint is invalid");
            }
        }

        /**
         * Builds the fixed-field canonical aggregate message.
         *
         * @param messageVersion aggregate message version
         * @param authorityMaterialFingerprint authority-only material fingerprint
         * @param targetBindingFingerprint target binding fingerprint
         * @return compact canonical UTF-8-compatible message
         */
        public static String aggregateCanonicalMessage(
                String messageVersion,
                String authorityMaterialFingerprint,
                String targetBindingFingerprint) {
            validateFingerprint(messageVersion, "messageVersion", false);
            validateFingerprint(authorityMaterialFingerprint,
                    "authorityMaterialFingerprint", true);
            validateFingerprint(targetBindingFingerprint, "targetBindingFingerprint", true);
            return "{\"messageVersion\":\"" + messageVersion
                    + "\",\"authorityMaterialFingerprint\":\""
                    + authorityMaterialFingerprint
                    + "\",\"targetBindingFingerprint\":\""
                    + targetBindingFingerprint + "\"}";
        }

        /**
         * Computes the deterministic aggregate Provider fingerprint.
         *
         * @param messageVersion aggregate message version
         * @param authorityMaterialFingerprint authority-only material fingerprint
         * @param targetBindingFingerprint target binding fingerprint
         * @return lowercase {@code sha256:} digest
         */
        public static String aggregateFingerprint(
                String messageVersion,
                String authorityMaterialFingerprint,
                String targetBindingFingerprint) {
            byte[] message = aggregateCanonicalMessage(messageVersion,
                    authorityMaterialFingerprint, targetBindingFingerprint)
                    .getBytes(StandardCharsets.UTF_8);
            try {
                return "sha256:" + HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(message));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable");
            }
        }

        private static void validateFingerprint(String value, String field, boolean digest) {
            if (value == null || value.isBlank()
                    || (digest && !FINGERPRINT.matcher(value).matches())
                    || (!digest && !value.matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,127}"))) {
                throw new IllegalArgumentException(field + " is invalid");
            }
        }

        /** Redacted representation that never exposes callbacks or fingerprint material. */
        @Override
        public String toString() {
            return "TargetBoundAuthorityBinding[fingerprint=<redacted>, material=<redacted>, "
                    + "targetAdmission=PRESENT]";
        }
    }

    /**
     * Computes the deterministic aggregate Provider fingerprint using the canonical binding API.
     *
     * @param messageVersion aggregate message version
     * @param authorityMaterialFingerprint authority-only material fingerprint
     * @param targetBindingFingerprint target binding fingerprint
     * @return lowercase {@code sha256:} digest
     */
    public static String aggregateFingerprint(
            String messageVersion,
            String authorityMaterialFingerprint,
            String targetBindingFingerprint) {
        return TargetBoundAuthorityBinding.aggregateFingerprint(messageVersion,
                authorityMaterialFingerprint, targetBindingFingerprint);
    }

    private static byte[] boundedCopy(byte[] value, int maximum, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length > maximum) {
            throw new IllegalArgumentException(field + " exceeds size limit");
        }
        return value.clone();
    }

    /**
     * Returns one atomic target-bound snapshot for formal verification.
     *
     * <p>The default preserves compatibility for legacy Providers. Formal target-bound consumers
     * treat a missing snapshot as blocked and must not reconstruct one through legacy accessors.</p>
     *
     * @return one target-bound snapshot, or {@code null} for a legacy Provider
     */
    default TargetBoundAuthorityBinding targetBoundAuthorityBinding() {
        return null;
    }

    /**
     * Returns one atomic authority snapshot for formal verification.
     *
     * <p>The default is intentionally {@code null}: it preserves source and binary compatibility
     * for legacy providers, while current formal and conformance paths reject it closed.</p>
     *
     * @return one immutable binding, or null for a legacy provider
     */
    default AuthorityBinding authorityBinding() {
        return null;
    }

    /**
     * Returns the deployment-owned immutable fingerprint for the complete authority binding.
     *
     * <p>The fingerprint identifies the resolver, issuer policy, and owner authority as one
     * deployment binding. It must never contain or derive from secrets. The default keeps source
     * and binary compatibility for providers compiled before the binding contract was added;
     * formal and conformance paths reject a missing or malformed value.</p>
     *
     * @return lowercase {@code sha256:} fingerprint, or null for a legacy provider
     */
    @Deprecated
    default String authorityBindingFingerprint() {
        return null;
    }

    /**
     * Returns the exact-coordinate external evidence and signature resolver.
     *
     * @return deployment-owned resolver
     */
    CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver evidenceResolver();

    /**
     * Returns the pinned evidence issuer policy.
     *
     * @return deployment-owned evidence issuer policy
     */
    CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceIssuerPolicy evidenceIssuerPolicy();

    /**
     * Returns the organizational owner signature authority.
     *
     * @return deployment-owned owner authority
     */
    CapabilityStudioStageAcceptanceAuthorityVerifier.OwnerAuthority ownerAuthority();
}
