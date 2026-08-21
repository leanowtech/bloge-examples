package com.leanowtech.bloge.gateway.testkit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
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

    /** Canonical message version for persistent atomic lease transition witnesses. */
    String EXECUTION_LEASE_TRANSITION_WITNESS_MESSAGE_VERSION =
            "resource-gateway.capability-studio.execution-lease-transition-witness.v2";

    /** Canonical message version for the non-circular witness material commitment. */
    String EXECUTION_LEASE_TRANSITION_WITNESS_MATERIAL_MESSAGE_VERSION =
            "resource-gateway.capability-studio.execution-lease-transition-witness-material.v1";

    /** Canonical message version for deployment admission authority binding fingerprints. */
    String DEPLOYMENT_ADMISSION_AUTHORITY_BINDING_MESSAGE_VERSION =
            "resource-gateway.capability-studio.deployment-admission-authority-binding.v1";

    /** Shared monotonic lock budget for one in-process full-evidence transaction. */
    final class EvidenceLeaseBudget {
        /** Maximum production budget shared by wrapper and store lock acquisition. */
        public static final long MAXIMUM_NANOS = TimeUnit.SECONDS.toNanos(5);

        private long lastTick;
        private long remainingNanos;

        private EvidenceLeaseBudget(long now, long remainingNanos) {
            this.lastTick = now;
            this.remainingNanos = remainingNanos;
        }

        /**
         * Starts one production-budget transaction using the JVM monotonic clock.
         *
         * @return fresh shared budget
         */
        public static EvidenceLeaseBudget start() {
            return new EvidenceLeaseBudget(System.nanoTime(), MAXIMUM_NANOS);
        }

        /**
         * Returns and consumes elapsed budget; time never replenishes.
         *
         * @return remaining nanoseconds, or zero when exhausted or invalid
         */
        public synchronized long remainingNanos() {
            long current = System.nanoTime();
            long elapsed = current - lastTick;
            if (elapsed < 0) {
                remainingNanos = 0;
                return 0;
            }
            lastTick = current;
            remainingNanos = elapsed >= remainingNanos ? 0 : remainingNanos - elapsed;
            return remainingNanos;
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "EvidenceLeaseBudget[material=REDACTED]";
        }
    }

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

    /** Payload-free closed failure category shared by the formal evidence SPIs. */
    enum EvidenceFailureKind {
        /** Input, schema, durable structure, or governed coordinate material is invalid. */
        INVALID,
        /** A required filesystem, lock, metadata, I/O, or Provider dependency is unavailable. */
        UNAVAILABLE,
        /** A deployment governance authority rejected otherwise well-formed material. */
        REJECTED
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

        /**
         * Returns the closed failure category without exposing the Provider reason.
         *
         * @return empty for verified, otherwise rejected or unavailable
         */
        public Optional<EvidenceFailureKind> failureKind() {
            return switch (status) {
                case VERIFIED -> Optional.empty();
                case REJECTED -> Optional.of(EvidenceFailureKind.REJECTED);
                case UNAVAILABLE -> Optional.of(EvidenceFailureKind.UNAVAILABLE);
            };
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

    /**
     * Persistent evidence of the exact state transition that atomically committed one lease.
     *
     * <p>The witness is stored with the immutable lease entry and recovered verbatim on an exact
     * retry. It is not inferred from external before/after observations. Its reproducible
     * fingerprint proves internal consistency only; deployment durability and authenticity come
     * from the externally pinned evidence authority implementation and store.</p>
     *
     * @param fingerprint reproducible final witness fingerprint
     * @param materialFingerprint non-circular transition material commitment
     * @param storeDescriptorFingerprint immutable store descriptor coordinate
     * @param requestFingerprint stable execution-lease request identity
     * @param receiptFingerprint immutable execution-lease receipt fingerprint
     * @param preStateFingerprint state fingerprint immediately before the commit
     * @param preGeneration state generation immediately before the commit
     * @param preFencingSequence fencing sequence immediately before the commit
     * @param preCheckpointFingerprint checkpoint fingerprint immediately before the commit
     * @param preRevocationHeadSequence revocation-head sequence revalidated by the commit
     * @param preRevocationHeadFingerprint revocation-head fingerprint revalidated by the commit
     * @param postStateCoreFingerprint post-state core commitment before witness anchoring
     * @param postStateFingerprint state fingerprint immediately after the commit
     * @param postGeneration state generation immediately after the commit
     * @param postFencingSequence fencing sequence issued by the commit
     * @param postCheckpointFingerprint checkpoint fingerprint atomically persisted after state
     * @param postRevocationHeadSequence revocation-head sequence committed with the lease
     * @param postRevocationHeadFingerprint revocation-head fingerprint committed with the lease
     */
    record ExecutionLeaseTransitionWitness(
            String fingerprint,
            String materialFingerprint,
            String storeDescriptorFingerprint,
            String requestFingerprint,
            String receiptFingerprint,
            String preStateFingerprint,
            long preGeneration,
            long preFencingSequence,
            String preCheckpointFingerprint,
            long preRevocationHeadSequence,
            String preRevocationHeadFingerprint,
            String postStateCoreFingerprint,
            String postStateFingerprint,
            long postGeneration,
            long postFencingSequence,
            String postCheckpointFingerprint,
            long postRevocationHeadSequence,
            String postRevocationHeadFingerprint) {
        /**
         * Creates a witness with its fixed canonical fingerprint.
         *
         * @param storeDescriptorFingerprint immutable store descriptor coordinate
         * @param requestFingerprint stable request identity
         * @param receiptFingerprint immutable receipt fingerprint
         * @param preStateFingerprint pre-commit state fingerprint
         * @param preGeneration pre-commit generation
         * @param preFencingSequence pre-commit fencing sequence
         * @param preCheckpointFingerprint pre-commit checkpoint fingerprint
         * @param preRevocationHeadSequence pre-commit revocation-head sequence
         * @param preRevocationHeadFingerprint pre-commit revocation-head fingerprint
         * @param postStateCoreFingerprint post-state core commitment
         * @param postStateFingerprint post-commit state fingerprint
         * @param postGeneration post-commit generation
         * @param postFencingSequence post-commit fencing sequence
         * @param postCheckpointFingerprint post-commit checkpoint fingerprint
         * @param postRevocationHeadSequence post-commit revocation-head sequence
         * @param postRevocationHeadFingerprint post-commit revocation-head fingerprint
         */
        public ExecutionLeaseTransitionWitness(
                String storeDescriptorFingerprint,
                String requestFingerprint,
                String receiptFingerprint,
                String preStateFingerprint,
                long preGeneration,
                long preFencingSequence,
                String preCheckpointFingerprint,
                long preRevocationHeadSequence,
                String preRevocationHeadFingerprint,
                String postStateCoreFingerprint,
                String postStateFingerprint,
                long postGeneration,
                long postFencingSequence,
                String postCheckpointFingerprint,
                long postRevocationHeadSequence,
                String postRevocationHeadFingerprint) {
            this(witnessFingerprint(materialFingerprint(storeDescriptorFingerprint,
                                    requestFingerprint, receiptFingerprint,
                                    preStateFingerprint, preGeneration,
                                    preFencingSequence, preCheckpointFingerprint,
                                    preRevocationHeadSequence,
                                    preRevocationHeadFingerprint,
                                    postStateCoreFingerprint, postGeneration,
                                    postFencingSequence, postRevocationHeadSequence,
                                    postRevocationHeadFingerprint),
                            postStateFingerprint, postCheckpointFingerprint),
                    materialFingerprint(storeDescriptorFingerprint, requestFingerprint,
                            receiptFingerprint, preStateFingerprint, preGeneration,
                            preFencingSequence, preCheckpointFingerprint,
                            preRevocationHeadSequence, preRevocationHeadFingerprint,
                            postStateCoreFingerprint, postGeneration,
                            postFencingSequence, postRevocationHeadSequence,
                            postRevocationHeadFingerprint),
                    storeDescriptorFingerprint, requestFingerprint, receiptFingerprint,
                    preStateFingerprint, preGeneration, preFencingSequence,
                    preCheckpointFingerprint, preRevocationHeadSequence,
                    preRevocationHeadFingerprint, postStateCoreFingerprint,
                    postStateFingerprint, postGeneration, postFencingSequence,
                    postCheckpointFingerprint, postRevocationHeadSequence,
                    postRevocationHeadFingerprint);
        }

        /** Validates the exact one-generation, one-fence transition and fingerprint. */
        public ExecutionLeaseTransitionWitness {
            validateFingerprint(storeDescriptorFingerprint, "storeDescriptorFingerprint");
            validateFingerprint(requestFingerprint, "requestFingerprint");
            validateFingerprint(receiptFingerprint, "receiptFingerprint");
            validateFingerprint(preStateFingerprint, "preStateFingerprint");
            validateFingerprint(preCheckpointFingerprint, "preCheckpointFingerprint");
            if (preRevocationHeadSequence < 0) {
                throw new IllegalArgumentException("execution lease transition is invalid");
            }
            validateFingerprint(preRevocationHeadFingerprint,
                    "preRevocationHeadFingerprint");
            validateFingerprint(postStateCoreFingerprint, "postStateCoreFingerprint");
            validateFingerprint(postStateFingerprint, "postStateFingerprint");
            validateFingerprint(postCheckpointFingerprint, "postCheckpointFingerprint");
            if (postRevocationHeadSequence < 0) {
                throw new IllegalArgumentException("execution lease transition is invalid");
            }
            validateFingerprint(postRevocationHeadFingerprint,
                    "postRevocationHeadFingerprint");
            if (preGeneration < 0 || preFencingSequence < 0
                    || preGeneration == Long.MAX_VALUE
                    || preFencingSequence == Long.MAX_VALUE
                    || postGeneration != preGeneration + 1
                    || postFencingSequence != preFencingSequence + 1
                    || postRevocationHeadSequence != preRevocationHeadSequence
                    || !preRevocationHeadFingerprint.equals(
                    postRevocationHeadFingerprint)) {
                throw new IllegalArgumentException("execution lease transition is invalid");
            }
            String expectedMaterial = materialFingerprint(storeDescriptorFingerprint,
                    requestFingerprint, receiptFingerprint, preStateFingerprint,
                    preGeneration, preFencingSequence, preCheckpointFingerprint,
                    preRevocationHeadSequence, preRevocationHeadFingerprint,
                    postStateCoreFingerprint, postGeneration, postFencingSequence,
                    postRevocationHeadSequence,
                    postRevocationHeadFingerprint);
            if (!expectedMaterial.equals(materialFingerprint)
                    || !witnessFingerprint(materialFingerprint, postStateFingerprint,
                    postCheckpointFingerprint).equals(fingerprint)) {
                throw new IllegalArgumentException(
                        "execution lease transition witness fingerprint is invalid");
            }
        }

        /**
         * Computes the non-circular fixed-field transition material fingerprint.
         *
         * @param storeDescriptorFingerprint immutable store descriptor coordinate
         * @param requestFingerprint stable request identity
         * @param receiptFingerprint immutable receipt fingerprint
         * @param preStateFingerprint pre-commit state fingerprint
         * @param preGeneration pre-commit generation
         * @param preFencingSequence pre-commit fencing sequence
         * @param preCheckpointFingerprint pre-commit checkpoint fingerprint
         * @param preRevocationHeadSequence pre-commit revocation-head sequence
         * @param preRevocationHeadFingerprint pre-commit revocation-head fingerprint
         * @param postStateCoreFingerprint post-commit state core fingerprint
         * @param postGeneration post-commit generation
         * @param postFencingSequence post-commit fencing sequence
         * @param postRevocationHeadSequence post-commit revocation-head sequence
         * @param postRevocationHeadFingerprint post-commit revocation-head fingerprint
         * @return lowercase SHA-256 transition material fingerprint
         */
        public static String materialFingerprint(
                String storeDescriptorFingerprint,
                String requestFingerprint,
                String receiptFingerprint,
                String preStateFingerprint,
                long preGeneration,
                long preFencingSequence,
                String preCheckpointFingerprint,
                long preRevocationHeadSequence,
                String preRevocationHeadFingerprint,
                String postStateCoreFingerprint,
                long postGeneration,
                long postFencingSequence,
                long postRevocationHeadSequence,
                String postRevocationHeadFingerprint) {
            validateFingerprint(storeDescriptorFingerprint, "storeDescriptorFingerprint");
            validateFingerprint(requestFingerprint, "requestFingerprint");
            validateFingerprint(receiptFingerprint, "receiptFingerprint");
            validateFingerprint(preStateFingerprint, "preStateFingerprint");
            validateFingerprint(preCheckpointFingerprint, "preCheckpointFingerprint");
            validateFingerprint(preRevocationHeadFingerprint,
                    "preRevocationHeadFingerprint");
            validateFingerprint(postStateCoreFingerprint, "postStateCoreFingerprint");
            validateFingerprint(postRevocationHeadFingerprint,
                    "postRevocationHeadFingerprint");
            if (preGeneration < 0 || preFencingSequence < 0
                    || preRevocationHeadSequence < 0 || postGeneration < 1
                    || postFencingSequence < 1 || postRevocationHeadSequence < 0) {
                throw new IllegalArgumentException("execution lease transition is invalid");
            }
            String message = "{\"messageVersion\":\""
                    + EXECUTION_LEASE_TRANSITION_WITNESS_MATERIAL_MESSAGE_VERSION
                    + "\",\"storeDescriptorFingerprint\":\""
                    + storeDescriptorFingerprint
                    + "\",\"requestFingerprint\":\"" + requestFingerprint
                    + "\",\"receiptFingerprint\":\"" + receiptFingerprint
                    + "\",\"preStateFingerprint\":\"" + preStateFingerprint
                    + "\",\"preGeneration\":" + preGeneration
                    + ",\"preFencingSequence\":" + preFencingSequence
                    + ",\"preCheckpointFingerprint\":\"" + preCheckpointFingerprint
                    + "\",\"preRevocationHeadSequence\":"
                    + preRevocationHeadSequence
                    + "\",\"preRevocationHeadFingerprint\":\""
                    + preRevocationHeadFingerprint
                    + "\",\"postStateCoreFingerprint\":\""
                    + postStateCoreFingerprint
                    + "\",\"postGeneration\":" + postGeneration
                    + ",\"postFencingSequence\":" + postFencingSequence
                    + ",\"postRevocationHeadSequence\":"
                    + postRevocationHeadSequence
                    + "\",\"postRevocationHeadFingerprint\":\""
                    + postRevocationHeadFingerprint + "\"}";
            return sha256(message.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Computes the final witness fingerprint after state and checkpoint persistence.
         *
         * @param materialFingerprint non-circular transition material commitment
         * @param postStateFingerprint final post-state commitment
         * @param postCheckpointFingerprint final post-checkpoint commitment
         * @return lowercase SHA-256 final witness fingerprint
         */
        public static String witnessFingerprint(
                String materialFingerprint,
                String postStateFingerprint,
                String postCheckpointFingerprint) {
            validateFingerprint(materialFingerprint, "materialFingerprint");
            validateFingerprint(postStateFingerprint, "postStateFingerprint");
            validateFingerprint(postCheckpointFingerprint, "postCheckpointFingerprint");
            String message = "{\"messageVersion\":\""
                    + EXECUTION_LEASE_TRANSITION_WITNESS_MESSAGE_VERSION
                    + "\",\"materialFingerprint\":\"" + materialFingerprint
                    + "\",\"postStateFingerprint\":\"" + postStateFingerprint
                    + "\",\"postCheckpointFingerprint\":\""
                    + postCheckpointFingerprint + "\"}";
            return sha256(message.getBytes(StandardCharsets.UTF_8));
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "ExecutionLeaseTransitionWitness[material=REDACTED]";
        }
    }

    /** Closed durable execution-lease commit outcome. */
    enum ExecutionLeaseCommitStatus {
        /** This invocation durably created the one lease commit. */
        COMMITTED,
        /** An exact retry recovered the immutable existing receipt. */
        RECOVERED,
        /** The request, journal, or durable evidence structure was invalid. */
        INVALID,
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

        /**
         * Returns the closed failure category without exposing the Provider reason.
         *
         * @return empty for committed or recovered, otherwise rejected or unavailable
         */
        public Optional<EvidenceFailureKind> failureKind() {
            return switch (status) {
                case COMMITTED, RECOVERED -> Optional.empty();
                case INVALID -> Optional.of(EvidenceFailureKind.INVALID);
                case REJECTED -> Optional.of(EvidenceFailureKind.REJECTED);
                case UNAVAILABLE -> Optional.of(EvidenceFailureKind.UNAVAILABLE);
            };
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "ExecutionLeaseCommitResult[status=" + status + ", material=REDACTED]";
        }
    }

    /**
     * Successful full-evidence lease result with its persistent transition witness.
     *
     * @param status closed commit status
     * @param receipt immutable receipt for success, otherwise null
     * @param transitionWitness persistent witness for success, otherwise null
     * @param reasonCode payload-free Provider-internal reason
     */
    record EvidenceExecutionLeaseCommitResult(
            ExecutionLeaseCommitStatus status,
            ExecutionLeaseReceipt receipt,
            ExecutionLeaseTransitionWitness transitionWitness,
            String reasonCode) {
        /** Validates the closed full-evidence result shape and cross-bindings. */
        public EvidenceExecutionLeaseCommitResult {
            status = Objects.requireNonNull(status, "status is required");
            if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("reasonCode is invalid");
            }
            boolean success = status == ExecutionLeaseCommitStatus.COMMITTED
                    || status == ExecutionLeaseCommitStatus.RECOVERED;
            if (success != (receipt != null && transitionWitness != null)) {
                throw new IllegalArgumentException("evidence lease result is invalid");
            }
            if (success && (!receipt.fingerprint().equals(
                    transitionWitness.receiptFingerprint())
                    || !receipt.requestFingerprint().equals(
                    transitionWitness.requestFingerprint()))) {
                throw new IllegalArgumentException("evidence lease witness is invalid");
            }
        }

        /**
         * Returns the closed failure category without exposing the Provider reason.
         *
         * @return empty for committed or recovered, otherwise rejected or unavailable
         */
        public Optional<EvidenceFailureKind> failureKind() {
            return switch (status) {
                case COMMITTED, RECOVERED -> Optional.empty();
                case INVALID -> Optional.of(EvidenceFailureKind.INVALID);
                case REJECTED -> Optional.of(EvidenceFailureKind.REJECTED);
                case UNAVAILABLE -> Optional.of(EvidenceFailureKind.UNAVAILABLE);
            };
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "EvidenceExecutionLeaseCommitResult[status=" + status
                    + ", material=REDACTED]";
        }
    }

    /**
     * One full-evidence transaction attempt passed to the mounted coordinator.
     *
     * @param request exact validated lease request
     * @param evidenceTransactionId stable evidence transaction identity
     * @param semanticVerificationTime Stage semantic verification time for the first attempt
     * @param evidencePublicationParent authenticated publication parent outside the state store
     * @param attemptGeneration monotonic evidence attempt generation, starting at one
     * @param previousAttemptClosureFingerprint prior immutable attempt closure, or null
     * @param lockBudget shared monotonic wrapper/store lock budget
     */
    record EvidenceExecutionLeaseAttempt(
            ExecutionLeaseRequest request,
            String evidenceTransactionId,
            Instant semanticVerificationTime,
            Path evidencePublicationParent,
            long attemptGeneration,
            String previousAttemptClosureFingerprint,
            EvidenceLeaseBudget lockBudget) {
        /**
         * Compatibility constructor for the first evidence attempt.
         *
         * @param request exact validated lease request
         * @param evidenceTransactionId stable evidence transaction identity
         * @param semanticVerificationTime Stage semantic verification time
         * @param evidencePublicationParent authenticated publication parent
         */
        public EvidenceExecutionLeaseAttempt(
                ExecutionLeaseRequest request,
                String evidenceTransactionId,
                Instant semanticVerificationTime,
                Path evidencePublicationParent) {
            this(request, evidenceTransactionId, semanticVerificationTime,
                    evidencePublicationParent, 1, null, EvidenceLeaseBudget.start());
        }

        /**
         * Compatibility constructor using one fresh bounded lock budget.
         *
         * @param request exact validated lease request
         * @param evidenceTransactionId stable evidence transaction identity
         * @param semanticVerificationTime Stage semantic verification time
         * @param evidencePublicationParent authenticated publication parent
         * @param attemptGeneration monotonic evidence attempt generation
         * @param previousAttemptClosureFingerprint prior attempt closure, or null
         */
        public EvidenceExecutionLeaseAttempt(
                ExecutionLeaseRequest request,
                String evidenceTransactionId,
                Instant semanticVerificationTime,
                Path evidencePublicationParent,
                long attemptGeneration,
                String previousAttemptClosureFingerprint) {
            this(request, evidenceTransactionId, semanticVerificationTime,
                    evidencePublicationParent, attemptGeneration,
                    previousAttemptClosureFingerprint, EvidenceLeaseBudget.start());
        }

        /** Validates the exact attempt coordinates without reading the filesystem. */
        public EvidenceExecutionLeaseAttempt {
            request = Objects.requireNonNull(request, "request is required");
            validateFingerprint(evidenceTransactionId, "evidenceTransactionId");
            semanticVerificationTime = Objects.requireNonNull(
                    semanticVerificationTime, "semanticVerificationTime is required");
            evidencePublicationParent = Objects.requireNonNull(
                    evidencePublicationParent, "evidencePublicationParent is required");
            lockBudget = Objects.requireNonNull(lockBudget, "lockBudget is required");
            if (!evidencePublicationParent.isAbsolute()
                    || !evidencePublicationParent.equals(
                    evidencePublicationParent.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("evidence publication parent is invalid");
            }
            if (attemptGeneration < 1
                    || (attemptGeneration == 1) != (previousAttemptClosureFingerprint == null)) {
                throw new IllegalArgumentException("evidence attempt generation is invalid");
            }
            if (previousAttemptClosureFingerprint != null) {
                validateFingerprint(previousAttemptClosureFingerprint,
                        "previousAttemptClosureFingerprint");
            }
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "EvidenceExecutionLeaseAttempt[material=REDACTED]";
        }
    }

    /** Closed outcome for one synchronous evidence journal callback. */
    enum EvidenceJournalStatus {
        /** The callback completed durably. */
        COMPLETED,
        /** The callback found invalid structure or governed coordinates. */
        INVALID,
        /** The callback dependency, lock, metadata, or I/O was unavailable. */
        UNAVAILABLE
    }

    /**
     * Payload-free typed result from one synchronous evidence journal callback.
     *
     * @param status closed callback status
     * @param value callback value only for {@link EvidenceJournalStatus#COMPLETED}
     * @param <T> callback value type
     */
    record EvidenceJournalResult<T>(EvidenceJournalStatus status, T value) {
        /** Validates that failures never carry callback material. */
        public EvidenceJournalResult {
            status = Objects.requireNonNull(status, "status is required");
            if (status != EvidenceJournalStatus.COMPLETED && value != null) {
                throw new IllegalArgumentException("failed journal result must be empty");
            }
        }

        /**
         * Returns a completed callback result.
         *
         * @param value callback value
         * @param <T> callback value type
         * @return completed result
         */
        public static <T> EvidenceJournalResult<T> completed(T value) {
            return new EvidenceJournalResult<>(EvidenceJournalStatus.COMPLETED, value);
        }

        /**
         * Returns a completed callback result without a value.
         *
         * @return completed result without a value
         */
        public static EvidenceJournalResult<Void> completed() {
            return completed(null);
        }

        /**
         * Returns an invalid callback result.
         *
         * @param <T> callback value type
         * @return invalid result
         */
        public static <T> EvidenceJournalResult<T> invalid() {
            return new EvidenceJournalResult<>(EvidenceJournalStatus.INVALID, null);
        }

        /**
         * Returns an unavailable callback result.
         *
         * @param <T> callback value type
         * @return unavailable result
         */
        public static <T> EvidenceJournalResult<T> unavailable() {
            return new EvidenceJournalResult<>(EvidenceJournalStatus.UNAVAILABLE, null);
        }

        /**
         * Returns the closed failure category.
         *
         * @return the closed failure category, or empty for completion
         */
        public Optional<EvidenceFailureKind> failureKind() {
            return switch (status) {
                case COMPLETED -> Optional.empty();
                case INVALID -> Optional.of(EvidenceFailureKind.INVALID);
                case UNAVAILABLE -> Optional.of(EvidenceFailureKind.UNAVAILABLE);
            };
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "EvidenceJournalResult[status=" + status + ", material=REDACTED]";
        }
    }

    /** Durable journal callbacks invoked synchronously while the store transaction lock is held. */
    interface EvidenceTransactionJournal {
        /**
         * Persists the authenticated pre-commit observation before any lease is created.
         *
         * @param attempt exact evidence transaction attempt
         * @param current current exact observation captured under the store transaction lock
         * @return the newly persisted or exact previously persisted pre-commit observation
         */
        CapabilityStudioDeploymentStateObservation.Observation prepareBefore(
                EvidenceExecutionLeaseAttempt attempt,
                CapabilityStudioDeploymentStateObservation.Observation current);

        /**
         * Typed additive form of {@link #prepareBefore}.
         *
         * <p>The default preserves existing implementations. An untyped runtime failure from a
         * legacy callback is treated as unavailable; implementations that can prove invalid
         * structure should override this method and return {@link EvidenceJournalStatus#INVALID}.
         * The result carries no Provider reason, path, or payload.</p>
         *
         * @param attempt exact evidence transaction attempt
         * @param current current exact observation
         * @return completed, invalid, or unavailable result
         */
        default EvidenceJournalResult<CapabilityStudioDeploymentStateObservation.Observation>
                prepareBeforeResult(
                EvidenceExecutionLeaseAttempt attempt,
                CapabilityStudioDeploymentStateObservation.Observation current) {
            try {
                return EvidenceJournalResult.completed(prepareBefore(attempt, current));
            } catch (RuntimeException unavailable) {
                return EvidenceJournalResult.unavailable();
            }
        }

        /**
         * Persists the exact committed result and post-state before the store lock is released.
         *
         * @param attempt exact evidence transaction attempt
         * @param before exact pre-state observation
         * @param after exact post-state observation
         * @param result durable receipt and witness
         */
        void persistCommitted(
                EvidenceExecutionLeaseAttempt attempt,
                CapabilityStudioDeploymentStateObservation.Observation before,
                CapabilityStudioDeploymentStateObservation.Observation after,
                EvidenceExecutionLeaseCommitResult result);

        /**
         * Typed additive form of {@link #persistCommitted}.
         *
         * <p>The compatibility default maps an untyped legacy runtime failure to unavailable.
         * Implementations should override when they can distinguish invalid durable structure.</p>
         *
         * @param attempt exact evidence transaction attempt
         * @param before exact pre-state observation
         * @param after exact post-state observation
         * @param result durable receipt and witness
         * @return completed, invalid, or unavailable result
         */
        default EvidenceJournalResult<Void> persistCommittedResult(
                EvidenceExecutionLeaseAttempt attempt,
                CapabilityStudioDeploymentStateObservation.Observation before,
                CapabilityStudioDeploymentStateObservation.Observation after,
                EvidenceExecutionLeaseCommitResult result) {
            try {
                persistCommitted(attempt, before, after, result);
                return EvidenceJournalResult.completed();
            } catch (RuntimeException unavailable) {
                return EvidenceJournalResult.unavailable();
            }
        }
    }

    /**
     * Exact atomic full-evidence transaction result.
     *
     * @param beforeObservation exact persisted pre-state for success, otherwise null
     * @param afterObservation exact durable post-state for success, otherwise null
     * @param leaseResult closed lease result
     */
    record EvidenceExecutionLeaseTransactionResult(
            CapabilityStudioDeploymentStateObservation.Observation beforeObservation,
            CapabilityStudioDeploymentStateObservation.Observation afterObservation,
            EvidenceExecutionLeaseCommitResult leaseResult) {
        /** Validates exact descriptor and witness attribution. */
        public EvidenceExecutionLeaseTransactionResult {
            leaseResult = Objects.requireNonNull(leaseResult, "leaseResult is required");
            boolean success = leaseResult.status() == ExecutionLeaseCommitStatus.COMMITTED
                    || leaseResult.status() == ExecutionLeaseCommitStatus.RECOVERED;
            if (!success) {
                if (beforeObservation != null || afterObservation != null) {
                    throw new IllegalArgumentException(
                            "failed evidence transaction must not expose observations");
                }
            } else {
                beforeObservation = Objects.requireNonNull(
                        beforeObservation, "beforeObservation is required");
                afterObservation = Objects.requireNonNull(
                        afterObservation, "afterObservation is required");
                if (beforeObservation.phase()
                        != CapabilityStudioDeploymentStateObservation.Phase.BEFORE
                        || afterObservation.phase()
                        != CapabilityStudioDeploymentStateObservation.Phase.AFTER
                        || !beforeObservation.evidenceTransactionId().equals(
                        afterObservation.evidenceTransactionId())) {
                    throw new IllegalArgumentException(
                            "evidence transaction result is invalid");
                }
            }
            if (success) {
                ExecutionLeaseTransitionWitness witness = leaseResult.transitionWitness();
                if (!beforeObservation.storeDescriptorFingerprint().equals(
                        witness.storeDescriptorFingerprint())
                        || !afterObservation.storeDescriptorFingerprint().equals(
                        witness.storeDescriptorFingerprint())
                        || beforeObservation.generation() != witness.preGeneration()
                        || beforeObservation.fencingSequence()
                        != witness.preFencingSequence()
                        || beforeObservation.revocationHeadSequence()
                        != witness.preRevocationHeadSequence()
                        || !beforeObservation.stateFingerprint().equals(
                        witness.preStateFingerprint())
                        || !beforeObservation.checkpointFingerprint().equals(
                        witness.preCheckpointFingerprint())
                        || !beforeObservation.revocationHeadFingerprint().equals(
                        witness.preRevocationHeadFingerprint())
                        || afterObservation.generation() != witness.postGeneration()
                        || afterObservation.fencingSequence()
                        != witness.postFencingSequence()
                        || afterObservation.revocationHeadSequence()
                        != witness.postRevocationHeadSequence()
                        || !afterObservation.stateFingerprint().equals(
                        witness.postStateFingerprint())
                        || !afterObservation.checkpointFingerprint().equals(
                        witness.postCheckpointFingerprint())
                        || !afterObservation.revocationHeadFingerprint().equals(
                        witness.postRevocationHeadFingerprint())) {
                    throw new IllegalArgumentException(
                            "evidence transaction witness is invalid");
                }
            }
        }

        /**
         * Returns the closed transaction failure category.
         *
         * @return the lease failure, or empty for committed/recovered success
         */
        public Optional<EvidenceFailureKind> failureKind() {
            return leaseResult.failureKind();
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "EvidenceExecutionLeaseTransactionResult[material=REDACTED]";
        }
    }

    /**
     * Descriptor-bound full-evidence transaction authority.
     *
     * <p>{@link #commit(EvidenceExecutionLeaseAttempt, EvidenceTransactionJournal)} captures both
     * observations and commits under one exclusive store transaction lock. The journal callbacks
     * run synchronously under that lock. {@link #recoverExisting(ExecutionLeaseRequest)} may return
     * only an already durable exact receipt/witness and MUST NOT create a lease, run current
     * lifecycle policy, repair state, or mutate store material.</p>
     */
    interface EvidenceExecutionLeaseTransactionAuthority {
        /**
         * Commits one exact transaction and journals its exact observations.
         *
         * @param attempt exact evidence attempt
         * @param journal synchronous durable evidence journal
         * @return exact success observations or a closed failed result
         */
        EvidenceExecutionLeaseTransactionResult commit(
                EvidenceExecutionLeaseAttempt attempt,
                EvidenceTransactionJournal journal);

        /**
         * Recovers only an already durable exact receipt and witness.
         *
         * @param request exact previously committed request
         * @return recovered receipt and witness, or a closed failed result
         */
        EvidenceExecutionLeaseCommitResult recoverExisting(ExecutionLeaseRequest request);
    }

    /** Closed result status for a strictly existing-only evidence lease lookup. */
    enum ExistingEvidenceRecoveryStatus {
        /** The exact durable receipt and witness were found. */
        FOUND,
        /** The store is valid but contains no lease for the requested lease identity. */
        ABSENT,
        /** The lease identity exists with different governed coordinates. */
        CONFLICT,
        /** The existing store cannot be read or proved consistent without mutation. */
        UNAVAILABLE
    }

    /**
     * Result of one strictly existing-only evidence recovery lookup.
     *
     * @param status closed lookup status
     * @param receipt exact durable receipt only for {@link ExistingEvidenceRecoveryStatus#FOUND}
     * @param transitionWitness exact durable witness only for
     *                          {@link ExistingEvidenceRecoveryStatus#FOUND}
     * @param beforeObservation exact historical pre-state only for FOUND
     * @param afterObservation exact historical post-state only for FOUND
     * @param reasonCode payload-free Provider-internal reason
     */
    record ExistingEvidenceRecoveryResult(
            ExistingEvidenceRecoveryStatus status,
            ExecutionLeaseReceipt receipt,
            ExecutionLeaseTransitionWitness transitionWitness,
            CapabilityStudioDeploymentStateObservation.Observation beforeObservation,
            CapabilityStudioDeploymentStateObservation.Observation afterObservation,
            String reasonCode) {
        /**
         * Compatibility constructor for non-FOUND closed outcomes.
         *
         * @param status closed lookup status
         * @param receipt exact receipt, normally absent for this compatibility shape
         * @param transitionWitness exact witness, normally absent for this compatibility shape
         * @param reasonCode payload-free Provider-internal reason
         */
        public ExistingEvidenceRecoveryResult(
                ExistingEvidenceRecoveryStatus status,
                ExecutionLeaseReceipt receipt,
                ExecutionLeaseTransitionWitness transitionWitness,
                String reasonCode) {
            this(status, receipt, transitionWitness, null, null, reasonCode);
        }

        /** Validates the closed recovery shape and exact receipt/witness binding. */
        public ExistingEvidenceRecoveryResult {
            status = Objects.requireNonNull(status, "status is required");
            if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("reasonCode is invalid");
            }
            boolean found = status == ExistingEvidenceRecoveryStatus.FOUND;
            if (found != (receipt != null && transitionWitness != null
                    && beforeObservation != null && afterObservation != null)) {
                throw new IllegalArgumentException("existing evidence recovery is invalid");
            }
            if (!found && (beforeObservation != null || afterObservation != null)) {
                throw new IllegalArgumentException("existing evidence recovery is invalid");
            }
            if (found && (!receipt.fingerprint().equals(
                    transitionWitness.receiptFingerprint())
                    || !receipt.requestFingerprint().equals(
                    transitionWitness.requestFingerprint())
                    || beforeObservation.phase()
                    != CapabilityStudioDeploymentStateObservation.Phase.BEFORE
                    || afterObservation.phase()
                    != CapabilityStudioDeploymentStateObservation.Phase.AFTER
                    || !beforeObservation.evidenceTransactionId().equals(
                    afterObservation.evidenceTransactionId())
                    || !beforeObservation.storeDescriptorFingerprint().equals(
                    transitionWitness.storeDescriptorFingerprint())
                    || !afterObservation.storeDescriptorFingerprint().equals(
                    transitionWitness.storeDescriptorFingerprint())
                    || beforeObservation.generation() != transitionWitness.preGeneration()
                    || beforeObservation.fencingSequence()
                    != transitionWitness.preFencingSequence()
                    || !beforeObservation.stateFingerprint().equals(
                    transitionWitness.preStateFingerprint())
                    || !beforeObservation.checkpointFingerprint().equals(
                    transitionWitness.preCheckpointFingerprint())
                    || afterObservation.generation() != transitionWitness.postGeneration()
                    || afterObservation.fencingSequence()
                    != transitionWitness.postFencingSequence()
                    || !afterObservation.stateFingerprint().equals(
                    transitionWitness.postStateFingerprint())
                    || !afterObservation.checkpointFingerprint().equals(
                    transitionWitness.postCheckpointFingerprint()))) {
                throw new IllegalArgumentException("existing evidence witness is invalid");
            }
        }

        /**
         * Returns the closed recovery failure category without exposing the Provider reason.
         *
         * <p>{@code CONFLICT} is an invalid durable/request structure. {@code ABSENT} is a valid
         * negative lookup and therefore is not a failure.</p>
         *
         * @return invalid, unavailable, or empty for found/absent
         */
        public Optional<EvidenceFailureKind> failureKind() {
            return switch (status) {
                case FOUND, ABSENT -> Optional.empty();
                case CONFLICT -> Optional.of(EvidenceFailureKind.INVALID);
                case UNAVAILABLE -> Optional.of(EvidenceFailureKind.UNAVAILABLE);
            };
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "ExistingEvidenceRecoveryResult[status=" + status
                    + ", material=REDACTED]";
        }
    }

    /**
     * Durable journal callback used only while an existing-store recovery transaction owns the
     * deployment store's exclusive transaction lock.
     */
    @FunctionalInterface
    interface ExistingEvidenceRecoveryJournal {
        /**
         * Immutably closes an attempt for which the locked store contains no lease.
         *
         * <p>The callback is synchronous and MUST finish its durable no-replace publication before
         * returning. It MUST NOT call Provider or deployment-store callbacks and therefore cannot
         * invert the wrapper-lock then store-lock ordering.</p>
         *
         * @param attempt exact pending evidence attempt
         */
        void closeAbsent(EvidenceExecutionLeaseAttempt attempt);

        /**
         * Typed additive form of {@link #closeAbsent}.
         *
         * <p>The compatibility default maps an untyped legacy runtime failure to unavailable.
         * Implementations should override when they can prove an invalid journal structure.</p>
         *
         * @param attempt exact pending evidence attempt
         * @return completed, invalid, or unavailable result
         */
        default EvidenceJournalResult<Void> closeAbsentResult(
                EvidenceExecutionLeaseAttempt attempt) {
            try {
                closeAbsent(attempt);
                return EvidenceJournalResult.completed();
            } catch (RuntimeException unavailable) {
                return EvidenceJournalResult.unavailable();
            }
        }
    }

    /**
     * Strictly existing-only receipt/witness authority.
     *
     * <p>The callback MUST NOT create, initialize, repair, rewrite, force, chmod, move, delete,
     * or otherwise mutate deployment store material. Missing or inconsistent existing material
     * is reported through the closed result and never repaired.</p>
     */
    @FunctionalInterface
    interface ExistingEvidenceExecutionLeaseRecovery {
        /**
         * Looks up one exact already durable receipt and witness.
         *
         * <p>The Provider performs the lookup under one exclusive existing-store transaction. A
         * {@link ExistingEvidenceRecoveryStatus#FOUND FOUND} result contains the historical
         * observations persisted with that lease; it is never reconstructed from current state.
         * For {@link ExistingEvidenceRecoveryStatus#ABSENT ABSENT}, the Provider invokes
         * {@code journal} before releasing the store lock.</p>
         *
         * @param attempt exact prior pending attempt
         * @param journal synchronous immutable absent-attempt closure
         * @return found, absent, conflict, or unavailable
         */
        ExistingEvidenceRecoveryResult recoverExisting(
                EvidenceExecutionLeaseAttempt attempt,
                ExistingEvidenceRecoveryJournal journal);
    }

    /**
     * Formal-writer recovery for one interrupted evidence lease transaction.
     *
     * <p>Unlike {@link ExistingEvidenceExecutionLeaseRecovery}, this callback may execute the
     * deployment store's fixed crash-recovery protocol under its exclusive transaction lock. It
     * MUST only reconcile an already durable predecessor/successor intermediate, MUST NOT admit a
     * new lease, and invokes the absent-attempt journal before releasing that lock when recovery
     * proves that the interrupted request was never committed.</p>
     */
    @FunctionalInterface
    interface InterruptedEvidenceExecutionLeaseRecovery {
        /**
         * Reconciles one exact interrupted writer transaction without current admission checks.
         *
         * @param attempt exact durable pending attempt
         * @param journal synchronous immutable absent-attempt closure
         * @return found, absent, conflict, or unavailable after fixed writer recovery
         */
        ExistingEvidenceRecoveryResult recoverInterrupted(
                EvidenceExecutionLeaseAttempt attempt,
                ExistingEvidenceRecoveryJournal journal);
    }

    /** Synchronous existing-only observer that performs no explicit store write or repair. */
    @FunctionalInterface
    interface ExistingDeploymentStateObserver {
        /**
         * Captures one strict observation under the store's shared read lock.
         *
         * @param phase before or after phase
         * @param evidenceTransactionId stable evidence transaction identity
         * @return verified existing-only observation
         */
        CapabilityStudioDeploymentStateObservation.Observation observe(
                CapabilityStudioDeploymentStateObservation.Phase phase,
                String evidenceTransactionId);
    }

    /**
     * Additive full-evidence Provider capability.
     *
     * <p>The formal binding must bind a v4 evidence lease authority material coordinate. A v2
     * store or Provider returns no binding and the evidence path blocks without falling back to
     * ordinary formal admission.</p>
     *
     * @param formalBinding exact formal snapshot used by the evidence acceptance attempt
     * @param stateObserver existing-only observer for cross-check observations
     * @param storeDescriptorFingerprint descriptor shared by observer and transaction authority
     * @param transactionAuthority witness-producing atomic transaction authority
     */
    record FormalEvidenceAuthorityBinding(
            FormalTargetBoundAuthorityBinding formalBinding,
            String storeDescriptorFingerprint,
            ExistingDeploymentStateObserver stateObserver,
            EvidenceExecutionLeaseTransactionAuthority transactionAuthority) {
        /** Validates the complete synchronous evidence capability. */
        public FormalEvidenceAuthorityBinding {
            formalBinding = Objects.requireNonNull(formalBinding,
                    "formalBinding is required");
            validateFingerprint(storeDescriptorFingerprint, "storeDescriptorFingerprint");
            stateObserver = Objects.requireNonNull(stateObserver,
                    "stateObserver is required");
            transactionAuthority = Objects.requireNonNull(transactionAuthority,
                    "transactionAuthority is required");
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "FormalEvidenceAuthorityBinding[material=REDACTED]";
        }
    }

    /**
     * Additive strictly existing-only recovery capability.
     *
     * <p>Constructing or invoking this binding MUST NOT initialize or repair formal state. The
     * descriptor fingerprint lets a recovery consumer prove that the callback addresses the
     * same mounted store as its durable BEFORE journal. The exact prior request and its
     * out-of-band Provider outer pin are validated against the durable receipt and witness.</p>
     *
     * @param storeDescriptorFingerprint immutable store descriptor fingerprint
     * @param stateObserver strictly existing-only standalone state observer
     * @param recovery atomic existing-store receipt/witness/history lookup
     * @param interruptedRecovery fixed formal-writer crash recovery, never new admission
     */
    record FormalEvidenceRecoveryBinding(
            String storeDescriptorFingerprint,
            ExistingDeploymentStateObserver stateObserver,
            ExistingEvidenceExecutionLeaseRecovery recovery,
            InterruptedEvidenceExecutionLeaseRecovery interruptedRecovery) {
        /**
         * Compatibility constructor for Providers that expose only strictly existing lookup.
         *
         * @param storeDescriptorFingerprint immutable store descriptor fingerprint
         * @param stateObserver strictly existing-only state observer
         * @param recovery strictly existing-only recovery callback
         */
        public FormalEvidenceRecoveryBinding(
                String storeDescriptorFingerprint,
                ExistingDeploymentStateObserver stateObserver,
                ExistingEvidenceExecutionLeaseRecovery recovery) {
            this(storeDescriptorFingerprint, stateObserver, recovery,
                    (attempt, journal) -> new ExistingEvidenceRecoveryResult(
                            ExistingEvidenceRecoveryStatus.UNAVAILABLE,
                            null, null, null, null, "INTERRUPTED_RECOVERY_UNAVAILABLE"));
        }

        /** Validates the immutable existing-only binding. */
        public FormalEvidenceRecoveryBinding {
            validateFingerprint(storeDescriptorFingerprint, "storeDescriptorFingerprint");
            stateObserver = Objects.requireNonNull(stateObserver, "stateObserver is required");
            recovery = Objects.requireNonNull(recovery, "recovery is required");
            interruptedRecovery = Objects.requireNonNull(interruptedRecovery,
                    "interruptedRecovery is required");
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "FormalEvidenceRecoveryBinding[material=REDACTED]";
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
     * Returns one atomic v3 full-evidence capability snapshot.
     *
     * <p>The default preserves all existing Provider compatibility. Evidence consumers block on
     * a missing value and never fall back to the ordinary v2 formal binding.</p>
     *
     * @return full-evidence snapshot, or null when unsupported
     * @throws DeploymentUnavailableException when the configured evidence store is unavailable
     */
    default FormalEvidenceAuthorityBinding formalEvidenceAuthorityBinding() {
        return null;
    }

    /**
     * Returns one strictly existing-only full-evidence recovery binding.
     *
     * <p>The default preserves Provider compatibility. Implementations MUST NOT initialize,
     * create, repair, or otherwise mutate deployment state while constructing or invoking this
     * binding.</p>
     *
     * @return existing-only recovery binding, or null when unsupported
     * @throws DeploymentUnavailableException when existing recovery dependencies are unavailable
     */
    default FormalEvidenceRecoveryBinding formalEvidenceRecoveryBinding() {
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
