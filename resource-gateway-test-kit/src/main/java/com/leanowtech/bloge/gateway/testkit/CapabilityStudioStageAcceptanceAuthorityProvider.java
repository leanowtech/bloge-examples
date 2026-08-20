package com.leanowtech.bloge.gateway.testkit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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

    /** Canonical message version for formal v2 target-bound Provider fingerprints. */
    String FORMAL_TARGET_BOUND_BINDING_MESSAGE_VERSION =
            "resource-gateway.capability-studio.stage-acceptance-provider-binding.v2";

    /** Canonical message version for lifecycle-material fingerprints. */
    String ADMISSION_LIFECYCLE_MATERIAL_MESSAGE_VERSION =
            "resource-gateway.capability-studio.admission-lifecycle-material.v1";

    /** Canonical message version for stable execution-lease commit identities. */
    String EXECUTION_LEASE_COMMIT_IDENTITY_MESSAGE_VERSION =
            "resource-gateway.capability-studio.execution-lease-commit-identity.v1";

    /** Canonical message version for atomic lifecycle commit receipt fingerprints. */
    String ATOMIC_ADMISSION_LIFECYCLE_COMMIT_RECEIPT_MESSAGE_VERSION =
            "resource-gateway.capability-studio.atomic-admission-lifecycle-commit-receipt.v1";

    /** Canonical message version for execution-lease receipt fingerprints. */
    String EXECUTION_LEASE_RECEIPT_MESSAGE_VERSION =
            "resource-gateway.capability-studio.execution-lease-receipt.v1";

    /** Canonical message version for deployment admission authority binding fingerprints. */
    String DEPLOYMENT_ADMISSION_AUTHORITY_BINDING_MESSAGE_VERSION =
            "resource-gateway.capability-studio.deployment-admission-authority-binding.v1";

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

    /** Deployment-owned decision returned by the formal lifecycle preflight authority. */
    enum DeploymentDecisionStatus {
        /** The deployment authority verified the lifecycle preflight. */
        VERIFIED,
        /** The deployment authority rejected invalid, stale, revoked, or replayed material. */
        REJECTED,
        /** A required deployment dependency was unavailable. */
        UNAVAILABLE
    }

    /**
     * Payload-free formal lifecycle preflight decision.
     *
     * @param status stable decision status
     * @param reasonCode stable payload-free Provider-internal reason code; the formal CLI never
     *                   emits it
     */
    record DeploymentAuthorityDecision(
            DeploymentDecisionStatus status,
            String reasonCode) {
        /** Validates the stable, redacted decision. */
        public DeploymentAuthorityDecision {
            status = Objects.requireNonNull(status, "status is required");
            if (reasonCode == null
                    || !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("reasonCode is invalid");
            }
        }

        /**
         * Returns a verified decision.
         *
         * @param reasonCode stable payload-free reason code
         * @return verified decision
         */
        public static DeploymentAuthorityDecision verified(String reasonCode) {
            return new DeploymentAuthorityDecision(
                    DeploymentDecisionStatus.VERIFIED, reasonCode);
        }

        /**
         * Returns a rejected decision.
         *
         * @param reasonCode stable payload-free reason code
         * @return rejected decision
         */
        public static DeploymentAuthorityDecision rejected(String reasonCode) {
            return new DeploymentAuthorityDecision(
                    DeploymentDecisionStatus.REJECTED, reasonCode);
        }

        /**
         * Returns an unavailable decision.
         *
         * @param reasonCode stable payload-free reason code
         * @return unavailable decision
         */
        public static DeploymentAuthorityDecision unavailable(String reasonCode) {
            return new DeploymentAuthorityDecision(
                    DeploymentDecisionStatus.UNAVAILABLE, reasonCode);
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "DeploymentAuthorityDecision[status=" + status + ", reasonCode=REDACTED]";
        }
    }

    /**
     * Stable marker for an unavailable trusted clock, mount, store, or lifecycle dependency.
     *
     * <p>Providers may throw this marker from {@link #formalTargetBoundAuthorityBinding()} when a
     * configured mount or other required deployment dependency cannot be reached. Invalid local
     * material must use an ordinary fail-closed exception and is treated as malformed Provider
     * configuration.</p>
     */
    final class DeploymentUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Creates the stable payload-free marker. */
        public DeploymentUnavailableException() {
            super("RG.CAPABILITY_STUDIO.DEPLOYMENT_UNAVAILABLE");
        }
    }

    /**
     * Trusted deployment clock used by formal admission.
     *
     * <p>The callback is synchronous. Implementations MUST NOT spawn background threads or write
     * asynchronously. An outage must throw {@link DeploymentUnavailableException}; arbitrary
     * failures are malformed Provider behavior.</p>
     */
    @FunctionalInterface
    interface TrustedVerificationClock {
        /**
         * Returns the trusted verification time for one formal attempt.
         *
         * @return trusted verification time
         */
        Instant verificationTime();
    }

    /**
     * Deployment revocation registry snapshot bound by mounted admission material.
     *
     * @param registryRef deployment revocation registry coordinate
     * @param revision monotonic registry snapshot revision
     * @param snapshotFingerprint exact snapshot fingerprint
     * @param observedAt trusted observation time recorded by the bundle
     * @param expiresAt snapshot expiry
     */
    record RevocationAuthoritySnapshot(
            String registryRef,
            long revision,
            String snapshotFingerprint,
            Instant observedAt,
            Instant expiresAt) {
        /** Validates the immutable revocation coordinate. */
        public RevocationAuthoritySnapshot {
            requireRef(registryRef, "registryRef");
            if (revision < 1) {
                throw new IllegalArgumentException("revision is invalid");
            }
            validateFingerprint(snapshotFingerprint, "snapshotFingerprint");
            observedAt = Objects.requireNonNull(observedAt, "observedAt is required");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt is required");
            if (!expiresAt.isAfter(observedAt)) {
                throw new IllegalArgumentException("revocation window is invalid");
            }
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "RevocationAuthoritySnapshot[coordinate=REDACTED]";
        }
    }

    /**
     * Lifecycle coordinates locally bound by a mounted admission bundle.
     *
     * <p>These fields cannot self-prove currentness. Only an external
     * {@link AdmissionLifecycleAuthority} can establish monotonic revision, predecessor,
     * current-active state, and revocation status.</p>
     *
     * @param bundleFingerprint mounted bundle material fingerprint
     * @param bundleId deployment bundle identity
     * @param revision bundle revision
     * @param lifecycleState required local lifecycle state
     * @param predecessorBundleFingerprint prior bundle fingerprint, or null for genesis
     * @param revocationAuthority bound revocation registry snapshot
     */
    record AdmissionLifecycleMaterial(
            String bundleFingerprint,
            String bundleId,
            long revision,
            String lifecycleState,
            String predecessorBundleFingerprint,
            RevocationAuthoritySnapshot revocationAuthority) {
        /** Validates immutable locally bound lifecycle material. */
        public AdmissionLifecycleMaterial {
            validateFingerprint(bundleFingerprint, "bundleFingerprint");
            requireRef(bundleId, "bundleId");
            if (revision < 1 || !"ACTIVE".equals(lifecycleState)) {
                throw new IllegalArgumentException("lifecycle material is invalid");
            }
            if (predecessorBundleFingerprint != null) {
                validateFingerprint(predecessorBundleFingerprint,
                        "predecessorBundleFingerprint");
            }
            revocationAuthority = Objects.requireNonNull(
                    revocationAuthority, "revocationAuthority is required");
        }

        /**
         * Returns the deterministic fingerprint of the complete lifecycle snapshot.
         *
         * @return lowercase {@code sha256:} lifecycle-material fingerprint
         */
        public String fingerprint() {
            return sha256(canonicalMessage().getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Returns the fixed-field canonical lifecycle snapshot message.
         *
         * @return canonical lifecycle-material message
         */
        public String canonicalMessage() {
            String predecessor = predecessorBundleFingerprint == null
                    ? "null" : "\"" + predecessorBundleFingerprint + "\"";
            return "{\"messageVersion\":\""
                    + ADMISSION_LIFECYCLE_MATERIAL_MESSAGE_VERSION
                    + "\",\"bundleFingerprint\":\"" + bundleFingerprint
                    + "\",\"bundleId\":\"" + bundleId
                    + "\",\"revision\":" + revision
                    + ",\"lifecycleState\":\"" + lifecycleState
                    + "\",\"predecessorBundleFingerprint\":" + predecessor
                    + ",\"revocationAuthority\":{\"registryRef\":\""
                    + revocationAuthority.registryRef()
                    + "\",\"revision\":" + revocationAuthority.revision()
                    + ",\"snapshotFingerprint\":\""
                    + revocationAuthority.snapshotFingerprint()
                    + "\",\"observedAt\":\"" + revocationAuthority.observedAt()
                    + "\",\"expiresAt\":\"" + revocationAuthority.expiresAt()
                    + "\"}}";
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "AdmissionLifecycleMaterial[material=REDACTED]";
        }
    }

    /**
     * Payload-free lifecycle verification request.
     *
     * @param material locally bound lifecycle material
     * @param providerOuterFingerprint independently pinned formal Provider fingerprint
     * @param targetRawFingerprint exact Target Binding bytes fingerprint
     * @param targetCanonicalFingerprint canonical Target Binding fingerprint
     * @param deploymentAdmissionAuthorityMaterialFingerprint deployment admission authority
     *                                                        configuration fingerprint
     * @param trustedVerificationTime trusted time for this formal attempt
     */
    record AdmissionLifecycleRequest(
            AdmissionLifecycleMaterial material,
            String providerOuterFingerprint,
            String targetRawFingerprint,
            String targetCanonicalFingerprint,
            String deploymentAdmissionAuthorityMaterialFingerprint,
            Instant trustedVerificationTime) {
        /** Validates the immutable lifecycle request. */
        public AdmissionLifecycleRequest {
            material = Objects.requireNonNull(material, "material is required");
            validateFingerprint(providerOuterFingerprint, "providerOuterFingerprint");
            validateFingerprint(targetRawFingerprint, "targetRawFingerprint");
            validateFingerprint(targetCanonicalFingerprint, "targetCanonicalFingerprint");
            validateFingerprint(deploymentAdmissionAuthorityMaterialFingerprint,
                    "deploymentAdmissionAuthorityMaterialFingerprint");
            trustedVerificationTime = Objects.requireNonNull(
                    trustedVerificationTime, "trustedVerificationTime is required");
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "AdmissionLifecycleRequest[material=REDACTED]";
        }
    }

    /**
     * External lifecycle and revocation authority for formal admission.
     *
     * <p>The callback is synchronous and verifies monotonic revision, predecessor continuity,
     * current {@code ACTIVE} state, and the bound revocation snapshot out of band. Providers MUST
     * NOT spawn background threads or write asynchronously.</p>
     */
    @FunctionalInterface
    interface AdmissionLifecycleAuthority {
        /**
         * Verifies the lifecycle request without consuming an execution lease.
         *
         * @param request payload-free lifecycle request
         * @return verified, rejected, or unavailable decision
         */
        DeploymentAuthorityDecision verify(AdmissionLifecycleRequest request);
    }

    /**
     * Payload-free atomic execution-lease commit request.
     *
     * <p>All governed coordinates form the stable commit identity. The trusted verification time
     * is attempt-local freshness input and is not part of that identity.</p>
     *
     * @param resultId Stage Result identity
     * @param resultRevision Stage Result revision
     * @param stageResultRawFingerprint SHA-256 fingerprint of the exact Stage Result bytes
     * @param evidenceClosureFingerprint verified Stage Result evidence-closure fingerprint
     * @param contractId governed contract identity
     * @param contractRevision governed contract revision
     * @param executionLeaseId deployment execution lease
     * @param providerOuterFingerprint independently pinned formal Provider fingerprint
     * @param targetRawFingerprint exact Target Binding bytes fingerprint
     * @param targetCanonicalFingerprint canonical Target Binding fingerprint
     * @param lifecycleMaterial exact lifecycle and revocation snapshot to revalidate atomically
     * @param deploymentAdmissionAuthorityMaterialFingerprint deployment admission authority
     *                                                        configuration fingerprint
     * @param trustedVerificationTime trusted time for this formal attempt
     */
    record ExecutionLeaseRequest(
            String resultId,
            long resultRevision,
            String stageResultRawFingerprint,
            String evidenceClosureFingerprint,
            String contractId,
            String contractRevision,
            String executionLeaseId,
            String providerOuterFingerprint,
            String targetRawFingerprint,
            String targetCanonicalFingerprint,
            AdmissionLifecycleMaterial lifecycleMaterial,
            String deploymentAdmissionAuthorityMaterialFingerprint,
            Instant trustedVerificationTime) {
        /** Validates the immutable lease request. */
        public ExecutionLeaseRequest {
            requireRef(resultId, "resultId");
            if (resultRevision < 1) {
                throw new IllegalArgumentException("resultRevision is invalid");
            }
            validateFingerprint(stageResultRawFingerprint, "stageResultRawFingerprint");
            validateFingerprint(evidenceClosureFingerprint, "evidenceClosureFingerprint");
            requireRef(contractId, "contractId");
            requireRef(contractRevision, "contractRevision");
            requireRef(executionLeaseId, "executionLeaseId");
            validateFingerprint(providerOuterFingerprint, "providerOuterFingerprint");
            validateFingerprint(targetRawFingerprint, "targetRawFingerprint");
            validateFingerprint(targetCanonicalFingerprint, "targetCanonicalFingerprint");
            lifecycleMaterial = Objects.requireNonNull(
                    lifecycleMaterial, "lifecycleMaterial is required");
            validateFingerprint(deploymentAdmissionAuthorityMaterialFingerprint,
                    "deploymentAdmissionAuthorityMaterialFingerprint");
            trustedVerificationTime = Objects.requireNonNull(
                    trustedVerificationTime, "trustedVerificationTime is required");
        }

        /**
         * Returns the deterministic stable identity of this governed commit.
         *
         * <p>The attempt-time {@link #trustedVerificationTime()} is intentionally excluded so an
         * exact crash retry can recover the same durable receipt. The time remains available to
         * the authority for freshness checks on every attempt.</p>
         *
         * @return lowercase {@code sha256:} stable commit identity fingerprint
         */
        public String commitIdentityFingerprint() {
            return sha256(commitIdentityCanonicalMessage().getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Returns the fixed-field canonical stable commit identity message.
         *
         * @return canonical stable commit identity message
         */
        public String commitIdentityCanonicalMessage() {
            return "{\"messageVersion\":\""
                    + EXECUTION_LEASE_COMMIT_IDENTITY_MESSAGE_VERSION
                    + "\",\"resultId\":\"" + resultId
                    + "\",\"resultRevision\":" + resultRevision
                    + ",\"stageResultRawFingerprint\":\"" + stageResultRawFingerprint
                    + "\",\"evidenceClosureFingerprint\":\"" + evidenceClosureFingerprint
                    + "\",\"contractId\":\"" + contractId
                    + "\",\"contractRevision\":\"" + contractRevision
                    + "\",\"executionLeaseId\":\"" + executionLeaseId
                    + "\",\"providerOuterFingerprint\":\"" + providerOuterFingerprint
                    + "\",\"targetRawFingerprint\":\"" + targetRawFingerprint
                    + "\",\"targetCanonicalFingerprint\":\"" + targetCanonicalFingerprint
                    + "\",\"lifecycleMaterialFingerprint\":\""
                    + lifecycleMaterial.fingerprint()
                    + "\",\"deploymentAdmissionAuthorityMaterialFingerprint\":\""
                    + deploymentAdmissionAuthorityMaterialFingerprint
                    + "\"}";
        }

        /**
         * Compatibility alias for the stable commit identity fingerprint.
         *
         * @return lowercase {@code sha256:} stable commit identity fingerprint
         */
        public String fingerprint() {
            return commitIdentityFingerprint();
        }

        /**
         * Compatibility alias for the stable commit identity message.
         *
         * @return canonical stable commit identity message
         */
        public String canonicalMessage() {
            return commitIdentityCanonicalMessage();
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "ExecutionLeaseRequest[coordinates=REDACTED]";
        }
    }

    /**
     * Independently issued declaration of the lifecycle/revocation head fenced atomically with
     * one lease commit.
     *
     * <p>The reproducible fingerprint proves internal consistency only. This core SPI does not
     * cryptographically authenticate the receipt; deployments must provide durable storage and
     * authenticate the implementation through the externally pinned deployment authority
     * binding.</p>
     *
     * @param fingerprint reproducible atomic lifecycle receipt fingerprint
     * @param deploymentAdmissionAuthorityMaterialFingerprint deployment authority configuration
     * @param lifecycleMaterialFingerprint exact lifecycle material fingerprint
     * @param revocationRegistryRef current revocation registry coordinate
     * @param revocationRegistryRevision current revocation registry revision
     * @param revocationSnapshotFingerprint current revocation snapshot fingerprint
     * @param fencingSequence monotonic durable fencing sequence
     * @param committedAt durable atomic commit time
     * @param requestFingerprint stable execution-lease commit identity fingerprint
     */
    record AtomicAdmissionLifecycleCommitReceipt(
            String fingerprint,
            String deploymentAdmissionAuthorityMaterialFingerprint,
            String lifecycleMaterialFingerprint,
            String revocationRegistryRef,
            long revocationRegistryRevision,
            String revocationSnapshotFingerprint,
            long fencingSequence,
            Instant committedAt,
            String requestFingerprint) {
        /**
         * Creates an atomic lifecycle receipt with its canonical fingerprint.
         *
         * @param deploymentAdmissionAuthorityMaterialFingerprint deployment authority configuration
         * @param lifecycleMaterialFingerprint exact lifecycle material fingerprint
         * @param revocationRegistryRef current revocation registry coordinate
         * @param revocationRegistryRevision current revocation registry revision
         * @param revocationSnapshotFingerprint current revocation snapshot fingerprint
         * @param fencingSequence monotonic durable fencing sequence
         * @param committedAt durable atomic commit time
         * @param requestFingerprint stable execution-lease commit identity fingerprint
         */
        public AtomicAdmissionLifecycleCommitReceipt(
                String deploymentAdmissionAuthorityMaterialFingerprint,
                String lifecycleMaterialFingerprint,
                String revocationRegistryRef,
                long revocationRegistryRevision,
                String revocationSnapshotFingerprint,
                long fencingSequence,
                Instant committedAt,
                String requestFingerprint) {
            this(receiptFingerprint(deploymentAdmissionAuthorityMaterialFingerprint,
                            lifecycleMaterialFingerprint, revocationRegistryRef,
                            revocationRegistryRevision, revocationSnapshotFingerprint,
                            fencingSequence, committedAt, requestFingerprint),
                    deploymentAdmissionAuthorityMaterialFingerprint,
                    lifecycleMaterialFingerprint, revocationRegistryRef,
                    revocationRegistryRevision, revocationSnapshotFingerprint,
                    fencingSequence, committedAt, requestFingerprint);
        }

        /** Validates the immutable reproducible atomic lifecycle receipt. */
        public AtomicAdmissionLifecycleCommitReceipt {
            validateFingerprint(deploymentAdmissionAuthorityMaterialFingerprint,
                    "deploymentAdmissionAuthorityMaterialFingerprint");
            validateFingerprint(lifecycleMaterialFingerprint,
                    "lifecycleMaterialFingerprint");
            requireRef(revocationRegistryRef, "revocationRegistryRef");
            if (revocationRegistryRevision < 1 || fencingSequence < 1) {
                throw new IllegalArgumentException("atomic lifecycle receipt sequence is invalid");
            }
            validateFingerprint(revocationSnapshotFingerprint,
                    "revocationSnapshotFingerprint");
            committedAt = Objects.requireNonNull(committedAt, "committedAt is required");
            validateFingerprint(requestFingerprint, "requestFingerprint");
            String expected = receiptFingerprint(
                    deploymentAdmissionAuthorityMaterialFingerprint,
                    lifecycleMaterialFingerprint, revocationRegistryRef,
                    revocationRegistryRevision, revocationSnapshotFingerprint,
                    fencingSequence, committedAt, requestFingerprint);
            if (!expected.equals(fingerprint)) {
                throw new IllegalArgumentException(
                        "atomic lifecycle commit receipt fingerprint is invalid");
            }
        }

        /**
         * Computes the reproducible atomic lifecycle receipt fingerprint.
         *
         * @param deploymentAdmissionAuthorityMaterialFingerprint deployment authority configuration
         * @param lifecycleMaterialFingerprint exact lifecycle material fingerprint
         * @param revocationRegistryRef current revocation registry coordinate
         * @param revocationRegistryRevision current revocation registry revision
         * @param revocationSnapshotFingerprint current revocation snapshot fingerprint
         * @param fencingSequence monotonic durable fencing sequence
         * @param committedAt durable atomic commit time
         * @param requestFingerprint stable execution-lease commit identity fingerprint
         * @return lowercase {@code sha256:} atomic lifecycle receipt fingerprint
         */
        public static String receiptFingerprint(
                String deploymentAdmissionAuthorityMaterialFingerprint,
                String lifecycleMaterialFingerprint,
                String revocationRegistryRef,
                long revocationRegistryRevision,
                String revocationSnapshotFingerprint,
                long fencingSequence,
                Instant committedAt,
                String requestFingerprint) {
            validateFingerprint(deploymentAdmissionAuthorityMaterialFingerprint,
                    "deploymentAdmissionAuthorityMaterialFingerprint");
            validateFingerprint(lifecycleMaterialFingerprint,
                    "lifecycleMaterialFingerprint");
            requireRef(revocationRegistryRef, "revocationRegistryRef");
            if (revocationRegistryRevision < 1 || fencingSequence < 1) {
                throw new IllegalArgumentException("atomic lifecycle receipt sequence is invalid");
            }
            validateFingerprint(revocationSnapshotFingerprint,
                    "revocationSnapshotFingerprint");
            Objects.requireNonNull(committedAt, "committedAt is required");
            validateFingerprint(requestFingerprint, "requestFingerprint");
            String message = "{\"messageVersion\":\""
                    + ATOMIC_ADMISSION_LIFECYCLE_COMMIT_RECEIPT_MESSAGE_VERSION
                    + "\",\"deploymentAdmissionAuthorityMaterialFingerprint\":\""
                    + deploymentAdmissionAuthorityMaterialFingerprint
                    + "\",\"lifecycleMaterialFingerprint\":\""
                    + lifecycleMaterialFingerprint
                    + "\",\"revocationRegistryRef\":\"" + revocationRegistryRef
                    + "\",\"revocationRegistryRevision\":" + revocationRegistryRevision
                    + ",\"revocationSnapshotFingerprint\":\""
                    + revocationSnapshotFingerprint
                    + "\",\"fencingSequence\":" + fencingSequence
                    + ",\"committedAt\":\"" + committedAt
                    + "\",\"requestFingerprint\":\"" + requestFingerprint + "\"}";
            return sha256(message.getBytes(StandardCharsets.UTF_8));
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "AtomicAdmissionLifecycleCommitReceipt[material=REDACTED]";
        }
    }

    /**
     * Durable immutable receipt for one stable execution-lease commit identity.
     *
     * @param fingerprint reproducible lease receipt fingerprint
     * @param requestFingerprint stable committed request identity fingerprint
     * @param lifecycleMaterial exact lifecycle and revocation material committed atomically
     * @param lifecycleCommitReceipt independently issued atomic lifecycle commit receipt
     */
    record ExecutionLeaseReceipt(
            String fingerprint,
            String requestFingerprint,
            AdmissionLifecycleMaterial lifecycleMaterial,
            AtomicAdmissionLifecycleCommitReceipt lifecycleCommitReceipt) {
        /**
         * Creates a lease receipt with its canonical fingerprint.
         *
         * @param requestFingerprint stable committed request identity fingerprint
         * @param lifecycleMaterial exact lifecycle and revocation material
         * @param lifecycleCommitReceipt independently issued atomic lifecycle commit receipt
         */
        public ExecutionLeaseReceipt(
                String requestFingerprint,
                AdmissionLifecycleMaterial lifecycleMaterial,
                AtomicAdmissionLifecycleCommitReceipt lifecycleCommitReceipt) {
            this(receiptFingerprint(requestFingerprint, lifecycleMaterial,
                            lifecycleCommitReceipt),
                    requestFingerprint, lifecycleMaterial, lifecycleCommitReceipt);
        }

        /** Validates the immutable reproducible lease receipt and its atomic lifecycle evidence. */
        public ExecutionLeaseReceipt {
            validateFingerprint(requestFingerprint, "requestFingerprint");
            lifecycleMaterial = Objects.requireNonNull(
                    lifecycleMaterial, "lifecycleMaterial is required");
            lifecycleCommitReceipt = Objects.requireNonNull(
                    lifecycleCommitReceipt, "lifecycleCommitReceipt is required");
            RevocationAuthoritySnapshot revocation = lifecycleMaterial.revocationAuthority();
            if (!requestFingerprint.equals(lifecycleCommitReceipt.requestFingerprint())
                    || !lifecycleMaterial.fingerprint().equals(
                    lifecycleCommitReceipt.lifecycleMaterialFingerprint())
                    || !revocation.registryRef().equals(
                    lifecycleCommitReceipt.revocationRegistryRef())
                    || revocation.revision()
                    != lifecycleCommitReceipt.revocationRegistryRevision()
                    || !revocation.snapshotFingerprint().equals(
                    lifecycleCommitReceipt.revocationSnapshotFingerprint())
                    || lifecycleCommitReceipt.committedAt().isBefore(revocation.observedAt())
                    || !revocation.expiresAt().isAfter(
                    lifecycleCommitReceipt.committedAt())) {
                throw new IllegalArgumentException(
                        "execution lease lifecycle commit receipt is invalid");
            }
            String expected = receiptFingerprint(
                    requestFingerprint, lifecycleMaterial, lifecycleCommitReceipt);
            if (!expected.equals(fingerprint)) {
                throw new IllegalArgumentException("execution lease receipt fingerprint is invalid");
            }
        }

        /**
         * Computes a reproducible lease receipt fingerprint.
         *
         * @param requestFingerprint stable committed request identity fingerprint
         * @param lifecycleMaterial exact lifecycle and revocation material
         * @param lifecycleCommitReceipt independently issued atomic lifecycle commit receipt
         * @return lowercase {@code sha256:} lease receipt fingerprint
         */
        public static String receiptFingerprint(
                String requestFingerprint,
                AdmissionLifecycleMaterial lifecycleMaterial,
                AtomicAdmissionLifecycleCommitReceipt lifecycleCommitReceipt) {
            validateFingerprint(requestFingerprint, "requestFingerprint");
            Objects.requireNonNull(lifecycleMaterial, "lifecycleMaterial is required");
            Objects.requireNonNull(lifecycleCommitReceipt,
                    "lifecycleCommitReceipt is required");
            String message = "{\"messageVersion\":\""
                    + EXECUTION_LEASE_RECEIPT_MESSAGE_VERSION
                    + "\",\"requestFingerprint\":\"" + requestFingerprint
                    + "\",\"lifecycleMaterialFingerprint\":\""
                    + lifecycleMaterial.fingerprint()
                    + "\",\"atomicLifecycleCommitReceiptFingerprint\":\""
                    + lifecycleCommitReceipt.fingerprint() + "\"}";
            return sha256(message.getBytes(StandardCharsets.UTF_8));
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "ExecutionLeaseReceipt[material=REDACTED]";
        }
    }

    /** Closed durable execution-lease commit outcome. */
    enum ExecutionLeaseCommitStatus {
        /** This invocation durably created the one lease commit. */
        COMMITTED,
        /** An exact retry recovered the immutable existing receipt. */
        RECOVERED,
        /** The lease was already bound to a different request or policy rejected it. */
        REJECTED,
        /** The durable commit store or required lifecycle authority was unavailable. */
        UNAVAILABLE
    }

    /**
     * Durable execution-lease commit result.
     *
     * @param status closed commit outcome
     * @param receipt immutable receipt for committed or recovered success, otherwise null
     * @param reasonCode stable payload-free Provider-internal reason code; the formal CLI never
     *                   emits it
     */
    record ExecutionLeaseCommitResult(
            ExecutionLeaseCommitStatus status,
            ExecutionLeaseReceipt receipt,
            String reasonCode) {
        /** Validates the closed result shape. */
        public ExecutionLeaseCommitResult {
            status = Objects.requireNonNull(status, "status is required");
            if (reasonCode == null
                    || !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("reasonCode is invalid");
            }
            boolean success = status == ExecutionLeaseCommitStatus.COMMITTED
                    || status == ExecutionLeaseCommitStatus.RECOVERED;
            if (success != (receipt != null)) {
                throw new IllegalArgumentException("execution lease commit result is invalid");
            }
        }

        /**
         * Returns a newly committed result.
         *
         * @param receipt newly durable receipt
         * @param reasonCode stable payload-free reason code
         * @return newly committed result
         */
        public static ExecutionLeaseCommitResult committed(
                ExecutionLeaseReceipt receipt, String reasonCode) {
            return new ExecutionLeaseCommitResult(
                    ExecutionLeaseCommitStatus.COMMITTED, receipt, reasonCode);
        }

        /**
         * Returns an exact-retry recovered result.
         *
         * @param receipt previously durable receipt
         * @param reasonCode stable payload-free reason code
         * @return recovered result
         */
        public static ExecutionLeaseCommitResult recovered(
                ExecutionLeaseReceipt receipt, String reasonCode) {
            return new ExecutionLeaseCommitResult(
                    ExecutionLeaseCommitStatus.RECOVERED, receipt, reasonCode);
        }

        /**
         * Returns a rejected result without a receipt.
         *
         * @param reasonCode stable payload-free reason code
         * @return rejected result
         */
        public static ExecutionLeaseCommitResult rejected(String reasonCode) {
            return new ExecutionLeaseCommitResult(
                    ExecutionLeaseCommitStatus.REJECTED, null, reasonCode);
        }

        /**
         * Returns an unavailable result without a receipt.
         *
         * @param reasonCode stable payload-free reason code
         * @return unavailable result
         */
        public static ExecutionLeaseCommitResult unavailable(String reasonCode) {
            return new ExecutionLeaseCommitResult(
                    ExecutionLeaseCommitStatus.UNAVAILABLE, null, reasonCode);
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "ExecutionLeaseCommitResult[status=" + status + ", material=REDACTED]";
        }
    }

    /**
     * External atomic execution-lease commit authority for formal admission.
     *
     * <p>The authority MUST atomically revalidate the exact lifecycle revision, predecessor,
     * active state, and revocation snapshot in the request and consume or fence the lease in the
     * same durable transaction. The first exact request returns {@code COMMITTED}; an exact retry
     * returns the same receipt with {@code RECOVERED}, including when only the attempt-time trusted
     * verification time changes; the same lease with any different stable commit identity returns
     * {@code REJECTED}; store outage returns {@code UNAVAILABLE}. Only this final commit, not the
     * earlier lifecycle preflight, authorizes formal acceptance. A successful result must contain
     * an {@link AtomicAdmissionLifecycleCommitReceipt} issued from that same atomic transaction
     * and bound by the returned {@link ExecutionLeaseReceipt}.</p>
     *
     * <p>The callback is synchronous. Providers MUST NOT spawn background threads or write
     * asynchronously.</p>
     */
    @FunctionalInterface
    interface ExecutionLeaseAuthority {
        /**
         * Atomically revalidates lifecycle material and durably commits the lease.
         *
         * @param request payload-free exact commit request
         * @return durable commit result
         */
        ExecutionLeaseCommitResult commit(ExecutionLeaseRequest request);
    }

    /**
     * Identity-bearing trusted clock descriptor.
     *
     * @param fingerprint strict coordinate for the deployed clock configuration and trust source
     * @param trustedClock synchronous trusted clock callback, or null during an outage
     */
    record TrustedVerificationClockBinding(
            String fingerprint,
            TrustedVerificationClock trustedClock) {
        /** Validates the deployment-owned clock coordinate. */
        public TrustedVerificationClockBinding {
            validateFingerprint(fingerprint, "trustedClockMaterialFingerprint");
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "TrustedVerificationClockBinding[material=REDACTED]";
        }
    }

    /**
     * Identity-bearing lifecycle/revocation authority descriptor.
     *
     * @param fingerprint strict coordinate for the lifecycle/revocation policy and authority
     * @param lifecycleAuthority synchronous lifecycle callback, or null during an outage
     */
    record AdmissionLifecycleAuthorityBinding(
            String fingerprint,
            AdmissionLifecycleAuthority lifecycleAuthority) {
        /** Validates the deployment-owned lifecycle authority coordinate. */
        public AdmissionLifecycleAuthorityBinding {
            validateFingerprint(fingerprint, "admissionLifecycleAuthorityMaterialFingerprint");
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "AdmissionLifecycleAuthorityBinding[material=REDACTED]";
        }
    }

    /**
     * Identity-bearing durable execution-lease authority descriptor.
     *
     * @param fingerprint strict durable store, policy, and configuration coordinate
     * @param executionLeaseAuthority synchronous atomic commit callback, or null during an outage
     */
    record ExecutionLeaseAuthorityBinding(
            String fingerprint,
            ExecutionLeaseAuthority executionLeaseAuthority) {
        /** Validates the deployment-owned durable lease authority coordinate. */
        public ExecutionLeaseAuthorityBinding {
            validateFingerprint(fingerprint, "executionLeaseAuthorityMaterialFingerprint");
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "ExecutionLeaseAuthorityBinding[material=REDACTED]";
        }
    }

    /**
     * Atomic deployment admission authority configuration derived from three strict coordinates.
     *
     * <p>The fingerprint is a domain-separated declaration computed from the trusted clock,
     * lifecycle/revocation authority, and durable lease commit store/policy/configuration
     * coordinates. The canonical constructor rejects any unrelated aggregate. The external formal
     * outer pin authenticates this recomputed declaration; this record does not make a standalone
     * cryptographic authenticity claim. Java cannot introspect whether a callback actually uses
     * the declared configuration, so a deployment must build each descriptor and callback from
     * the same controlled snapshot and pin the resulting formal outer fingerprint.</p>
     *
     * @param fingerprint deployment admission authority material fingerprint
     * @param trustedClockBinding identity-bearing trusted clock descriptor
     * @param lifecycleAuthorityBinding identity-bearing lifecycle authority descriptor
     * @param executionLeaseAuthorityBinding identity-bearing durable lease authority descriptor
     */
    record DeploymentAdmissionAuthorityBinding(
            String fingerprint,
            TrustedVerificationClockBinding trustedClockBinding,
            AdmissionLifecycleAuthorityBinding lifecycleAuthorityBinding,
            ExecutionLeaseAuthorityBinding executionLeaseAuthorityBinding) {
        /**
         * Creates a deployment binding with its canonical aggregate fingerprint.
         *
         * @param trustedClockBinding identity-bearing trusted clock descriptor
         * @param lifecycleAuthorityBinding identity-bearing lifecycle authority descriptor
         * @param executionLeaseAuthorityBinding identity-bearing durable lease descriptor
         */
        public DeploymentAdmissionAuthorityBinding(
                TrustedVerificationClockBinding trustedClockBinding,
                AdmissionLifecycleAuthorityBinding lifecycleAuthorityBinding,
                ExecutionLeaseAuthorityBinding executionLeaseAuthorityBinding) {
            this(aggregateFingerprint(trustedClockBinding, lifecycleAuthorityBinding,
                            executionLeaseAuthorityBinding),
                    trustedClockBinding, lifecycleAuthorityBinding,
                    executionLeaseAuthorityBinding);
        }

        /** Validates every component coordinate and the deterministic aggregate. */
        public DeploymentAdmissionAuthorityBinding {
            trustedClockBinding = Objects.requireNonNull(
                    trustedClockBinding, "trustedClockBinding is required");
            lifecycleAuthorityBinding = Objects.requireNonNull(
                    lifecycleAuthorityBinding, "lifecycleAuthorityBinding is required");
            executionLeaseAuthorityBinding = Objects.requireNonNull(
                    executionLeaseAuthorityBinding, "executionLeaseAuthorityBinding is required");
            String expected = aggregateFingerprint(trustedClockBinding,
                    lifecycleAuthorityBinding, executionLeaseAuthorityBinding);
            if (!expected.equals(fingerprint)) {
                throw new IllegalArgumentException(
                        "deployment admission authority binding fingerprint is invalid");
            }
        }

        /**
         * Returns the synchronous trusted clock callback.
         *
         * @return trusted clock, or null during an outage
         */
        public TrustedVerificationClock trustedClock() {
            return trustedClockBinding.trustedClock();
        }

        /**
         * Returns the synchronous lifecycle/revocation authority callback.
         *
         * @return lifecycle authority, or null during an outage
         */
        public AdmissionLifecycleAuthority lifecycleAuthority() {
            return lifecycleAuthorityBinding.lifecycleAuthority();
        }

        /**
         * Returns the synchronous durable lease authority callback.
         *
         * @return execution lease authority, or null during an outage
         */
        public ExecutionLeaseAuthority executionLeaseAuthority() {
            return executionLeaseAuthorityBinding.executionLeaseAuthority();
        }

        /**
         * Builds the fixed-field canonical deployment authority declaration.
         *
         * @param trustedClockMaterialFingerprint trusted clock coordinate
         * @param admissionLifecycleAuthorityMaterialFingerprint lifecycle authority coordinate
         * @param executionLeaseAuthorityMaterialFingerprint durable lease authority coordinate
         * @return canonical declaration message
         */
        public static String aggregateCanonicalMessage(
                String trustedClockMaterialFingerprint,
                String admissionLifecycleAuthorityMaterialFingerprint,
                String executionLeaseAuthorityMaterialFingerprint) {
            validateFingerprint(trustedClockMaterialFingerprint,
                    "trustedClockMaterialFingerprint");
            validateFingerprint(admissionLifecycleAuthorityMaterialFingerprint,
                    "admissionLifecycleAuthorityMaterialFingerprint");
            validateFingerprint(executionLeaseAuthorityMaterialFingerprint,
                    "executionLeaseAuthorityMaterialFingerprint");
            return "{\"messageVersion\":\""
                    + DEPLOYMENT_ADMISSION_AUTHORITY_BINDING_MESSAGE_VERSION
                    + "\",\"trustedClockMaterialFingerprint\":\""
                    + trustedClockMaterialFingerprint
                    + "\",\"admissionLifecycleAuthorityMaterialFingerprint\":\""
                    + admissionLifecycleAuthorityMaterialFingerprint
                    + "\",\"executionLeaseAuthorityMaterialFingerprint\":\""
                    + executionLeaseAuthorityMaterialFingerprint + "\"}";
        }

        /**
         * Computes the deterministic deployment authority declaration fingerprint.
         *
         * @param trustedClockMaterialFingerprint trusted clock coordinate
         * @param admissionLifecycleAuthorityMaterialFingerprint lifecycle authority coordinate
         * @param executionLeaseAuthorityMaterialFingerprint durable lease authority coordinate
         * @return lowercase {@code sha256:} declaration fingerprint
         */
        public static String aggregateFingerprint(
                String trustedClockMaterialFingerprint,
                String admissionLifecycleAuthorityMaterialFingerprint,
                String executionLeaseAuthorityMaterialFingerprint) {
            return sha256(aggregateCanonicalMessage(trustedClockMaterialFingerprint,
                    admissionLifecycleAuthorityMaterialFingerprint,
                    executionLeaseAuthorityMaterialFingerprint)
                    .getBytes(StandardCharsets.UTF_8));
        }

        private static String aggregateFingerprint(
                TrustedVerificationClockBinding trustedClockBinding,
                AdmissionLifecycleAuthorityBinding lifecycleAuthorityBinding,
                ExecutionLeaseAuthorityBinding executionLeaseAuthorityBinding) {
            return aggregateFingerprint(
                    Objects.requireNonNull(trustedClockBinding,
                            "trustedClockBinding is required").fingerprint(),
                    Objects.requireNonNull(lifecycleAuthorityBinding,
                            "lifecycleAuthorityBinding is required").fingerprint(),
                    Objects.requireNonNull(executionLeaseAuthorityBinding,
                            "executionLeaseAuthorityBinding is required").fingerprint());
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "DeploymentAdmissionAuthorityBinding[material=REDACTED]";
        }
    }

    /**
     * Complete formal v2 target-admission material and external deployment authorities.
     *
     * <p>The mounted bundle fingerprint is an integrity coordinate. Authenticity comes only from
     * an out-of-band deployment pin of the complete formal outer aggregate. Raw documents are
     * copied at construction and on every access.</p>
     *
     * @param targetAdmissionMaterialFingerprint complete mounted bundle material fingerprint
     * @param targetRawFingerprint exact Target Binding bytes fingerprint
     * @param targetCanonicalFingerprint canonical Target Binding fingerprint
     * @param targetBindingBytes raw Stage Acceptance Target Binding v1 bytes
     * @param candidateAttestationBytes raw Candidate Attestation v1 bytes
     * @param environmentAttestationBytes raw Environment Attestation v1 bytes
     * @param verificationContext target verification context sourced only from the manifest
     * @param candidateAuthority pinned Candidate Authority callback
     * @param environmentAuthority pinned Environment Authority callback
     * @param lifecycleMaterial locally bound lifecycle and revocation coordinates
     * @param deploymentAuthorityBinding independently fingerprinted atomic deployment authority
     *                                   configuration
     */
    record FormalTargetAdmissionBinding(
            String targetAdmissionMaterialFingerprint,
            String targetRawFingerprint,
            String targetCanonicalFingerprint,
            byte[] targetBindingBytes,
            byte[] candidateAttestationBytes,
            byte[] environmentAttestationBytes,
            CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext
                    verificationContext,
            CapabilityStudioStageAcceptanceTargetBindingVerifier.CandidateAuthority
                    candidateAuthority,
            CapabilityStudioStageAcceptanceTargetBindingVerifier.EnvironmentAuthority
                    environmentAuthority,
            AdmissionLifecycleMaterial lifecycleMaterial,
            DeploymentAdmissionAuthorityBinding deploymentAuthorityBinding) {
        /** Validates and snapshots the formal target admission boundary. */
        public FormalTargetAdmissionBinding {
            validateFingerprint(targetAdmissionMaterialFingerprint,
                    "targetAdmissionMaterialFingerprint");
            validateFingerprint(targetRawFingerprint, "targetRawFingerprint");
            validateFingerprint(targetCanonicalFingerprint, "targetCanonicalFingerprint");
            targetBindingBytes = boundedCopy(targetBindingBytes,
                    CapabilityStudioStageAcceptanceTargetBindingVerifier.MAXIMUM_TARGET_BINDING_BYTES,
                    "targetBindingBytes");
            if (!targetRawFingerprint.equals(sha256(targetBindingBytes))) {
                throw new IllegalArgumentException("targetRawFingerprint is invalid");
            }
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
            lifecycleMaterial = Objects.requireNonNull(
                    lifecycleMaterial, "lifecycleMaterial is required");
            deploymentAuthorityBinding = Objects.requireNonNull(
                    deploymentAuthorityBinding, "deploymentAuthorityBinding is required");
            if (!targetCanonicalFingerprint.equals(
                    verificationContext.expectedTargetBindingFingerprint())
                    || !targetAdmissionMaterialFingerprint.equals(
                    lifecycleMaterial.bundleFingerprint())) {
                throw new IllegalArgumentException("formal target admission binding is invalid");
            }
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

        /** Redacted representation that excludes raw bytes and callbacks. */
        @Override
        public String toString() {
            return "FormalTargetAdmissionBinding[material=REDACTED, target=REDACTED, "
                    + "authorities=REDACTED]";
        }
    }

    /**
     * Atomic formal v2 Provider snapshot for one acceptance attempt.
     *
     * @param fingerprint complete out-of-band-pinnable aggregate fingerprint
     * @param authorityBinding post-run authority-material snapshot
     * @param targetAdmissionBinding complete formal target-admission snapshot
     */
    record FormalTargetBoundAuthorityBinding(
            String fingerprint,
            AuthorityBinding authorityBinding,
            FormalTargetAdmissionBinding targetAdmissionBinding) {
        /** Canonical formal v2 aggregate message version. */
        public static final String MESSAGE_VERSION = FORMAL_TARGET_BOUND_BINDING_MESSAGE_VERSION;

        /**
         * Creates a formal snapshot with its canonical aggregate fingerprint.
         *
         * @param authorityBinding post-run authority-material snapshot
         * @param targetAdmissionBinding complete formal target-admission snapshot
         */
        public FormalTargetBoundAuthorityBinding(
                AuthorityBinding authorityBinding,
                FormalTargetAdmissionBinding targetAdmissionBinding) {
            this(aggregateFingerprint(MESSAGE_VERSION,
                            authorityBinding == null ? null : authorityBinding.fingerprint(),
                            targetAdmissionBinding == null ? null
                                    : targetAdmissionBinding.deploymentAuthorityBinding()
                                    .fingerprint(),
                            targetAdmissionBinding == null ? null
                                    : targetAdmissionBinding.targetAdmissionMaterialFingerprint(),
                            targetAdmissionBinding == null ? null
                                    : targetAdmissionBinding.targetRawFingerprint(),
                            targetAdmissionBinding == null ? null
                                    : targetAdmissionBinding.targetCanonicalFingerprint()),
                    authorityBinding, targetAdmissionBinding);
        }

        /** Validates that the supplied outer fingerprint binds every formal material coordinate. */
        public FormalTargetBoundAuthorityBinding {
            authorityBinding = Objects.requireNonNull(
                    authorityBinding, "authorityBinding is required");
            targetAdmissionBinding = Objects.requireNonNull(
                    targetAdmissionBinding, "targetAdmissionBinding is required");
            String expected = aggregateFingerprint(MESSAGE_VERSION,
                    authorityBinding.fingerprint(),
                    targetAdmissionBinding.deploymentAuthorityBinding().fingerprint(),
                    targetAdmissionBinding.targetAdmissionMaterialFingerprint(),
                    targetAdmissionBinding.targetRawFingerprint(),
                    targetAdmissionBinding.targetCanonicalFingerprint());
            if (!expected.equals(fingerprint)) {
                throw new IllegalArgumentException(
                        "formal target-bound authority binding fingerprint is invalid");
            }
        }

        /**
         * Builds the fixed-field domain-separated formal v2 aggregate message.
         *
         * @param messageVersion formal aggregate message version
         * @param authorityMaterialFingerprint post-run authority material fingerprint
         * @param deploymentAdmissionAuthorityMaterialFingerprint deployment admission authority
         *                                                        configuration fingerprint
         * @param targetAdmissionMaterialFingerprint mounted target-admission material fingerprint
         * @param targetRawFingerprint exact Target Binding bytes fingerprint
         * @param targetCanonicalFingerprint canonical Target Binding fingerprint
         * @return canonical formal aggregate message
         */
        public static String aggregateCanonicalMessage(
                String messageVersion,
                String authorityMaterialFingerprint,
                String deploymentAdmissionAuthorityMaterialFingerprint,
                String targetAdmissionMaterialFingerprint,
                String targetRawFingerprint,
                String targetCanonicalFingerprint) {
            requireMessageVersion(messageVersion);
            validateFingerprint(authorityMaterialFingerprint,
                    "authorityMaterialFingerprint");
            validateFingerprint(deploymentAdmissionAuthorityMaterialFingerprint,
                    "deploymentAdmissionAuthorityMaterialFingerprint");
            validateFingerprint(targetAdmissionMaterialFingerprint,
                    "targetAdmissionMaterialFingerprint");
            validateFingerprint(targetRawFingerprint, "targetRawFingerprint");
            validateFingerprint(targetCanonicalFingerprint, "targetCanonicalFingerprint");
            return "{\"messageVersion\":\"" + messageVersion
                    + "\",\"authorityMaterialFingerprint\":\""
                    + authorityMaterialFingerprint
                    + "\",\"deploymentAdmissionAuthorityMaterialFingerprint\":\""
                    + deploymentAdmissionAuthorityMaterialFingerprint
                    + "\",\"targetAdmissionMaterialFingerprint\":\""
                    + targetAdmissionMaterialFingerprint
                    + "\",\"targetRawFingerprint\":\""
                    + targetRawFingerprint
                    + "\",\"targetCanonicalFingerprint\":\""
                    + targetCanonicalFingerprint + "\"}";
        }

        /**
         * Computes the deterministic formal v2 aggregate Provider fingerprint.
         *
         * @param messageVersion formal aggregate message version
         * @param authorityMaterialFingerprint post-run authority material fingerprint
         * @param deploymentAdmissionAuthorityMaterialFingerprint deployment admission authority
         *                                                        configuration fingerprint
         * @param targetAdmissionMaterialFingerprint mounted target-admission material fingerprint
         * @param targetRawFingerprint exact Target Binding bytes fingerprint
         * @param targetCanonicalFingerprint canonical Target Binding fingerprint
         * @return lowercase {@code sha256:} aggregate fingerprint
         */
        public static String aggregateFingerprint(
                String messageVersion,
                String authorityMaterialFingerprint,
                String deploymentAdmissionAuthorityMaterialFingerprint,
                String targetAdmissionMaterialFingerprint,
                String targetRawFingerprint,
                String targetCanonicalFingerprint) {
            return sha256(aggregateCanonicalMessage(messageVersion,
                    authorityMaterialFingerprint,
                    deploymentAdmissionAuthorityMaterialFingerprint,
                    targetAdmissionMaterialFingerprint, targetRawFingerprint,
                    targetCanonicalFingerprint)
                    .getBytes(StandardCharsets.UTF_8));
        }

        /** Redacted representation that excludes all authority material. */
        @Override
        public String toString() {
            return "FormalTargetBoundAuthorityBinding[fingerprint=REDACTED, "
                    + "material=REDACTED]";
        }
    }

    /**
     * Computes the deterministic formal v2 aggregate Provider fingerprint.
     *
     * @param messageVersion formal aggregate message version
     * @param authorityMaterialFingerprint post-run authority material fingerprint
     * @param deploymentAdmissionAuthorityMaterialFingerprint deployment admission authority
     *                                                        configuration fingerprint
     * @param targetAdmissionMaterialFingerprint mounted target-admission material fingerprint
     * @param targetRawFingerprint exact Target Binding bytes fingerprint
     * @param targetCanonicalFingerprint canonical Target Binding fingerprint
     * @return lowercase {@code sha256:} aggregate fingerprint
     */
    public static String formalAggregateFingerprint(
            String messageVersion,
            String authorityMaterialFingerprint,
            String deploymentAdmissionAuthorityMaterialFingerprint,
            String targetAdmissionMaterialFingerprint,
            String targetRawFingerprint,
            String targetCanonicalFingerprint) {
        return FormalTargetBoundAuthorityBinding.aggregateFingerprint(messageVersion,
                authorityMaterialFingerprint, deploymentAdmissionAuthorityMaterialFingerprint,
                targetAdmissionMaterialFingerprint, targetRawFingerprint,
                targetCanonicalFingerprint);
    }

    private static void requireMessageVersion(String value) {
        if (value == null
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,127}")) {
            throw new IllegalArgumentException("messageVersion is invalid");
        }
    }

    private static void requireRef(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,511}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void validateFingerprint(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
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

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    /**
     * Returns one complete atomic formal v2 snapshot for formal Stage Acceptance.
     *
     * <p>The default preserves phase-2 and older Provider compatibility. Formal consumers treat
     * a missing snapshot as blocked and MUST NOT fall back to
     * {@link #targetBoundAuthorityBinding()} or reconstruct it from legacy accessors.</p>
     *
     * @return one formal v2 snapshot, or {@code null} when the Provider lacks the capability
     * @throws DeploymentUnavailableException when a configured deployment dependency is down
     */
    default FormalTargetBoundAuthorityBinding formalTargetBoundAuthorityBinding() {
        return null;
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
