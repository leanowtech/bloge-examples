package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.AdmissionWindow;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.AuthorityDecision;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.CandidateAttestationFacts;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.CandidateCoordinate;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.EnvironmentAttestationFacts;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.EnvironmentCoordinate;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.ExactReference;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceTargetBindingVerifier.VerificationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads one immutable mounted snapshot of complete target-admission authority material.
 *
 * <p>A successful load means only that every local file hash, canonical fingerprint, detached
 * proof, and complete key lifecycle was verified. The manifest self-hash provides integrity, not
 * authenticity. Formal authenticity is established only when deployment configuration pins the
 * complete outer
 * {@link CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding}
 * aggregate. Lifecycle currentness and revocation status require an external deployment
 * Authority and cannot be established by the manifest's own assertions.</p>
 *
 * <p>The loader performs no network access and accepts no private key. It snapshots every listed
 * file once. The loader never consumes an execution lease and makes no replay-prevention claim.
 * The deployment {@link CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseAuthority}
 * is responsible for atomically revalidating lifecycle and revocation material, consuming or
 * fencing the lease, and returning the durable receipt required for formal acceptance.</p>
 */
public final class CapabilityStudioMountedTargetAdmissionBundle {
    /** Fixed direct-child manifest filename. */
    public static final String MANIFEST_FILE = "target-admission-bundle-v1.json";
    /** Candidate detached-proof message domain. */
    public static final String CANDIDATE_PROOF_VERSION =
            "resource-gateway.capability-studio.candidate-target-admission-proof.v1";
    /** Environment detached-proof message domain. */
    public static final String ENVIRONMENT_PROOF_VERSION =
            "resource-gateway.capability-studio.environment-target-admission-proof.v1";

    private static final String SCHEMA_VERSION =
            "resource-gateway.capability-studio.mounted-target-admission-bundle.v1";
    private static final String CODE = "RG.CAPABILITY_STUDIO.TARGET_ADMISSION_BUNDLE.";
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_PROOF_BYTES = 64 * 1024;
    private static final int MAX_KEY_SET_BYTES = 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024;
    private static final Pattern SAFE_FILE = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,122}\\.json");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory().rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    private final CapabilityStudioStageAcceptanceAuthorityProvider.TargetAdmissionBinding binding;
    private final String bundleFingerprint;
    private final String targetRawFingerprint;
    private final String targetCanonicalFingerprint;
    private final CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial
            lifecycleMaterial;
    private final Instant generatedAt;
    private final Instant expiresAt;

    private CapabilityStudioMountedTargetAdmissionBundle(
            CapabilityStudioStageAcceptanceAuthorityProvider.TargetAdmissionBinding binding,
            String bundleFingerprint,
            String targetRawFingerprint,
            String targetCanonicalFingerprint,
            CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial
                    lifecycleMaterial,
            Instant generatedAt,
            Instant expiresAt) {
        this.binding = binding;
        this.bundleFingerprint = bundleFingerprint;
        this.targetRawFingerprint = targetRawFingerprint;
        this.targetCanonicalFingerprint = targetCanonicalFingerprint;
        this.lifecycleMaterial = lifecycleMaterial;
        this.generatedAt = generatedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * Immutable cross-target coordinates bound into both detached proof domains.
     *
     * @param targetRawFingerprint exact Target Binding file fingerprint
     * @param targetCanonicalFingerprint canonical Target Binding fingerprint
     * @param candidateCoordinate exact Candidate Attestation coordinate
     * @param environmentCoordinate exact Environment Attestation coordinate
     * @param executionLeaseId deployment-owned execution lease
     * @param trustedTargetIdentities deployment-owned target identities
     */
    public record ProofBindingContext(
            String targetRawFingerprint,
            String targetCanonicalFingerprint,
            CandidateCoordinate candidateCoordinate,
            EnvironmentCoordinate environmentCoordinate,
            String executionLeaseId,
            Set<String> trustedTargetIdentities) {
        /** Validates and snapshots the proof replay boundary. */
        public ProofBindingContext {
            requireFingerprint(targetRawFingerprint, "targetRawFingerprint");
            requireFingerprint(targetCanonicalFingerprint, "targetCanonicalFingerprint");
            candidateCoordinate = Objects.requireNonNull(
                    candidateCoordinate, "candidateCoordinate is required");
            environmentCoordinate = Objects.requireNonNull(
                    environmentCoordinate, "environmentCoordinate is required");
            requireRef(executionLeaseId, "executionLeaseId");
            if (trustedTargetIdentities == null || trustedTargetIdentities.isEmpty()
                    || trustedTargetIdentities.stream().anyMatch(value -> !validRef(value))) {
                throw new IllegalArgumentException("trustedTargetIdentities are invalid");
            }
            trustedTargetIdentities = Set.copyOf(trustedTargetIdentities);
        }

        /** Redacted description of the proof replay boundary. */
        @Override
        public String toString() {
            return "ProofBindingContext[target=REDACTED, coordinates=REDACTED, "
                    + "lease=REDACTED, identities=REDACTED]";
        }
    }

    /**
     * Loads and completely verifies one mounted target-admission snapshot.
     *
     * @param root deployment-owned bundle directory
     * @param clock trusted verification clock
     * @return immutable locally verified bundle
     * @throws IllegalStateException with a stable redacted reason when any local material fails
     */
    public static CapabilityStudioMountedTargetAdmissionBundle load(Path root, Clock clock) {
        if (root == null || clock == null) {
            throw failure("INPUT_INVALID");
        }
        try {
            Path mountedRoot = root.toAbsolutePath().normalize();
            RootIdentity rootIdentity = requireRoot(mountedRoot);
            Map<Object, String> fileKeys = new HashMap<>();
            Map<Path, FileSnapshot> snapshots = new LinkedHashMap<>();
            FileSnapshot manifestFile = readBounded(mountedRoot,
                    directChild(mountedRoot, MANIFEST_FILE), MAX_MANIFEST_BYTES,
                    rootIdentity);
            registerFileKey(fileKeys, manifestFile, MANIFEST_FILE);
            snapshots.put(mountedRoot.resolve(MANIFEST_FILE), manifestFile);
            ObjectNode manifest = object(parseSingle(manifestFile.bytes()), "MANIFEST_INVALID");
            requireManifestSchema(manifest);

            Instant generatedAt = instant(manifest, "generatedAt");
            Instant expiresAt = instant(manifest, "expiresAt");
            Instant now = trustedInstant(clock);
            if (generatedAt.isAfter(now)) {
                throw failure("MANIFEST_NOT_YET_VALID");
            }
            if (!expiresAt.isAfter(generatedAt) || !expiresAt.isAfter(now)) {
                throw failure("MANIFEST_EXPIRED");
            }
            String bundleFingerprint = text(manifest, "bundleFingerprint");
            if (!bundleFingerprint.equals(canonicalManifestFingerprint(manifest))) {
                throw failure("MANIFEST_FINGERPRINT_INVALID");
            }
            ObjectNode revocationNode = object(
                    manifest.path("revocationAuthority"), "FIELD_INVALID");
            var revocationSnapshot = new CapabilityStudioStageAcceptanceAuthorityProvider
                    .RevocationAuthoritySnapshot(text(revocationNode, "registryRef"),
                    revocationNode.path("revision").longValue(),
                    text(revocationNode, "snapshotFingerprint"),
                    instant(revocationNode, "observedAt"),
                    instant(revocationNode, "expiresAt"));
            if (revocationSnapshot.observedAt().isAfter(now)
                    || !revocationSnapshot.expiresAt().isAfter(now)) {
                throw failure("REVOCATION_SNAPSHOT_NOT_CURRENT");
            }
            var lifecycleMaterial = new CapabilityStudioStageAcceptanceAuthorityProvider
                    .AdmissionLifecycleMaterial(bundleFingerprint, text(manifest, "bundleId"),
                    manifest.path("revision").longValue(), text(manifest, "lifecycleState"),
                    nullableFingerprint(manifest.get("predecessorBundleFingerprint")),
                    revocationSnapshot);

            ObjectNode targetSpec = object(manifest.path("targetBinding"), "FIELD_INVALID");
            ObjectNode candidateSpec = object(manifest.path("candidate"), "FIELD_INVALID");
            ObjectNode environmentSpec = object(manifest.path("environment"), "FIELD_INVALID");
            ObjectNode candidateCoordinateSpec = object(
                    candidateSpec.path("attestation"), "FIELD_INVALID");
            ObjectNode environmentCoordinateSpec = object(
                    environmentSpec.path("attestation"), "FIELD_INVALID");
            ObjectNode candidatePolicyNode = object(
                    candidateSpec.path("policy"), "FIELD_INVALID");
            ObjectNode environmentPolicyNode = object(
                    environmentSpec.path("policy"), "FIELD_INVALID");

            Map<String, FileDeclaration> declarations = new LinkedHashMap<>();
            declare(declarations, targetSpec, "file", "fileFingerprint", FileKind.TARGET);
            declare(declarations, candidateCoordinateSpec, "file", "fileFingerprint",
                    FileKind.CANDIDATE_ATTESTATION);
            declare(declarations, environmentCoordinateSpec, "file", "fileFingerprint",
                    FileKind.ENVIRONMENT_ATTESTATION);
            declare(declarations, candidatePolicyNode, "keySetFile",
                    "keySetFileFingerprint", FileKind.CANDIDATE_KEY_SET);
            declare(declarations, environmentPolicyNode, "keySetFile",
                    "keySetFileFingerprint", FileKind.ENVIRONMENT_KEY_SET);
            declare(declarations, candidatePolicyNode, "proofFile", "proofFileFingerprint",
                    FileKind.CANDIDATE_PROOF);
            declare(declarations, environmentPolicyNode, "proofFile", "proofFileFingerprint",
                    FileKind.ENVIRONMENT_PROOF);

            long totalBytes = manifestFile.bytes().length;
            for (Map.Entry<String, FileDeclaration> entry : declarations.entrySet()) {
                FileDeclaration declaration = entry.getValue();
                FileSnapshot snapshot = readBounded(mountedRoot,
                        directChild(mountedRoot, entry.getKey()), maximum(declaration.kind()),
                        rootIdentity);
                registerFileKey(fileKeys, snapshot, entry.getKey());
                if (!declaration.fingerprint().equals(sha256(snapshot.bytes()))) {
                    throw failure("FILE_FINGERPRINT_MISMATCH");
                }
                totalBytes = addBytes(totalBytes, snapshot.bytes().length);
                snapshots.put(mountedRoot.resolve(entry.getKey()), snapshot);
            }
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw failure("TOTAL_SIZE_LIMIT");
            }
            requireOnlyListedFiles(mountedRoot, declarations.keySet());

            byte[] targetBytes = bytes(snapshots, mountedRoot, text(targetSpec, "file"));
            byte[] candidateBytes = bytes(
                    snapshots, mountedRoot, text(candidateCoordinateSpec, "file"));
            byte[] environmentBytes = bytes(
                    snapshots, mountedRoot, text(environmentCoordinateSpec, "file"));
            JsonNode target = parseAndValidate(targetBytes,
                    CapabilityStudioSchemaSupport.STAGE_ACCEPTANCE_TARGET_BINDING_V1_RESOURCE,
                    "TARGET_BINDING_INVALID");
            JsonNode candidate = parseAndValidate(candidateBytes,
                    CapabilityStudioSchemaSupport.CANDIDATE_ATTESTATION_V1_RESOURCE,
                    "CANDIDATE_ATTESTATION_INVALID");
            JsonNode environment = parseAndValidate(environmentBytes,
                    CapabilityStudioSchemaSupport.ENVIRONMENT_ATTESTATION_V1_RESOURCE,
                    "ENVIRONMENT_ATTESTATION_INVALID");

            String targetRawFingerprint = text(targetSpec, "fileFingerprint");
            String targetCanonicalFingerprint = text(targetSpec, "canonicalFingerprint");
            if (!targetCanonicalFingerprint.equals(
                    CapabilityStudioStageAcceptanceTargetBindingVerifier
                            .targetBindingFingerprint(target))
                    || !targetCanonicalFingerprint.equals(target.path("fingerprint").textValue())) {
                throw failure("TARGET_CANONICAL_FINGERPRINT_MISMATCH");
            }

            CandidateCoordinate candidateCoordinate = candidateCoordinate(
                    candidateCoordinateSpec, candidate);
            EnvironmentCoordinate environmentCoordinate = environmentCoordinate(
                    environmentCoordinateSpec, environment);
            String executionLeaseId = text(manifest, "executionLeaseId");
            Set<String> identities = stringSet(manifest.path("trustedTargetIdentities"));
            if (!executionLeaseId.equals(target.path("executionLeaseId").textValue())
                    || !identities.equals(stringSet(target.path("trustedTargetIdentities")))) {
                throw failure("TARGET_CONTEXT_MISMATCH");
            }
            ProofBindingContext proofContext = new ProofBindingContext(
                    targetRawFingerprint, targetCanonicalFingerprint, candidateCoordinate,
                    environmentCoordinate, executionLeaseId, identities);

            CandidateAttestationFacts candidateFacts = candidateFacts(
                    candidate, candidateCoordinate);
            EnvironmentAttestationFacts environmentFacts = environmentFacts(
                    environment, environmentCoordinate);
            if (!candidateCoordinate.equals(environmentFacts.candidateAttestation())
                    || !candidateCoordinate.equals(candidateCoordinate(
                    target.path("candidateAttestation")))
                    || !environmentCoordinate.equals(environmentCoordinate(
                    target.path("environmentAttestation")))
                    || !candidateFacts.scope().equals(environmentFacts.scope())
                    || candidateFacts.role().equals(environmentFacts.role())
                    || candidateFacts.issuer().equals(environmentFacts.issuer())) {
                throw failure("ATTESTATION_COORDINATE_MISMATCH");
            }

            AuthorityPolicy candidatePolicy = policy(candidatePolicyNode,
                    CANDIDATE_PROOF_VERSION, "CANDIDATE_AUTHORITY", declarations,
                    snapshots, mountedRoot, clock);
            AuthorityPolicy environmentPolicy = policy(environmentPolicyNode,
                    ENVIRONMENT_PROOF_VERSION, "ENVIRONMENT_AUTHORITY", declarations,
                    snapshots, mountedRoot, clock);
            rejectPolicyCollapse(candidatePolicy, environmentPolicy);

            AuthorityDecision candidateDecision = verifyCandidate(
                    candidateFacts, proofContext, candidatePolicy,
                    generatedAt, expiresAt, clock);
            AuthorityDecision environmentDecision = verifyEnvironment(
                    environmentFacts, proofContext, environmentPolicy,
                    generatedAt, expiresAt, clock);
            if (candidateDecision.status() == AuthorityDecision.Decision.UNAVAILABLE
                    || environmentDecision.status() == AuthorityDecision.Decision.UNAVAILABLE) {
                throw unavailable();
            }
            if (candidateDecision.status() != AuthorityDecision.Decision.VERIFIED
                    || environmentDecision.status() != AuthorityDecision.Decision.VERIFIED) {
                throw failure("DETACHED_PROOF_INVALID");
            }

            ensureFilesStable(snapshots);
            ensureRootStable(mountedRoot, rootIdentity);
            VerificationContext verificationContext = new VerificationContext(
                    executionLeaseId, identities, targetCanonicalFingerprint);
            var binding = new CapabilityStudioStageAcceptanceAuthorityProvider
                    .TargetAdmissionBinding(targetBytes, candidateBytes, environmentBytes,
                    verificationContext,
                    facts -> verifyCandidate(facts, proofContext, candidatePolicy,
                            generatedAt, expiresAt, clock),
                    facts -> verifyEnvironment(facts, proofContext, environmentPolicy,
                            generatedAt, expiresAt, clock));
            return new CapabilityStudioMountedTargetAdmissionBundle(
                    binding, bundleFingerprint, targetRawFingerprint,
                    targetCanonicalFingerprint, lifecycleMaterial, generatedAt, expiresAt);
        } catch (CapabilityStudioStageAcceptanceAuthorityProvider
                 .DeploymentUnavailableException unavailable) {
            throw unavailable;
        } catch (NoSuchFileException unavailable) {
            throw unavailable();
        } catch (IllegalStateException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith(CODE)) {
                throw failure;
            }
            throw failure("BUNDLE_INVALID");
        } catch (IOException failure) {
            throw unavailable();
        } catch (RuntimeException failure) {
            throw failure("BUNDLE_INVALID");
        }
    }

    /**
     * Computes the canonical manifest self-hash with {@code bundleFingerprint} set to null.
     *
     * <p>This value is an integrity coordinate only. It is not an authenticity decision.</p>
     *
     * @param manifest strict mounted target-admission manifest
     * @return lowercase canonical SHA-256 fingerprint
     */
    public static String canonicalManifestFingerprint(JsonNode manifest) {
        if (manifest == null || !manifest.isObject()
                || !SCHEMA_VERSION.equals(manifest.path("schemaVersion").asText())
                || !CapabilityStudioSchemaSupport.validate(manifest,
                CapabilityStudioSchemaSupport.MOUNTED_TARGET_ADMISSION_BUNDLE_V1_RESOURCE)
                .isEmpty()) {
            throw new IllegalArgumentException("target admission manifest is invalid");
        }
        ObjectNode material = manifest.deepCopy();
        ArrayNode sorted = JSON.createArrayNode();
        stringSet(material.path("trustedTargetIdentities")).stream()
                .sorted().forEach(sorted::add);
        material.set("trustedTargetIdentities", sorted);
        material.putNull("bundleFingerprint");
        return EvidenceVerificationSupport.sha256(material);
    }

    /**
     * Computes the domain-separated Candidate detached-proof material fingerprint.
     *
     * @param facts exact typed Candidate facts
     * @param context cross-target and execution replay boundary
     * @param policyRef deployment Candidate policy identity
     * @param pinnedKeySetFingerprint independently pinned Candidate key-set fingerprint
     * @param keyId exact detached-proof key id
     * @param signedAt detached-proof signing time
     * @param expiresAt detached-proof expiry
     * @return lowercase canonical SHA-256 fingerprint
     */
    public static String candidateProofFingerprint(
            CandidateAttestationFacts facts,
            ProofBindingContext context,
            String policyRef,
            String pinnedKeySetFingerprint,
            String keyId,
            Instant signedAt,
            Instant expiresAt) {
        return proofFingerprint(CANDIDATE_PROOF_VERSION, "CANDIDATE_AUTHORITY",
                candidateFactsNode(Objects.requireNonNull(facts, "facts are required")),
                context, policyRef, facts.issuer(), facts.scope(), pinnedKeySetFingerprint,
                keyId, signedAt, expiresAt);
    }

    /**
     * Computes the domain-separated Environment detached-proof material fingerprint.
     *
     * @param facts exact typed Environment facts
     * @param context cross-target and execution replay boundary
     * @param policyRef deployment Environment policy identity
     * @param pinnedKeySetFingerprint independently pinned Environment key-set fingerprint
     * @param keyId exact detached-proof key id
     * @param signedAt detached-proof signing time
     * @param expiresAt detached-proof expiry
     * @return lowercase canonical SHA-256 fingerprint
     */
    public static String environmentProofFingerprint(
            EnvironmentAttestationFacts facts,
            ProofBindingContext context,
            String policyRef,
            String pinnedKeySetFingerprint,
            String keyId,
            Instant signedAt,
            Instant expiresAt) {
        return proofFingerprint(ENVIRONMENT_PROOF_VERSION, "ENVIRONMENT_AUTHORITY",
                environmentFactsNode(Objects.requireNonNull(facts, "facts are required")),
                context, policyRef, facts.issuer(), facts.scope(), pinnedKeySetFingerprint,
                keyId, signedAt, expiresAt);
    }

    /**
     * Returns the immutable target-admission binding backed by this snapshot.
     *
     * @return immutable target-admission binding
     */
    public CapabilityStudioStageAcceptanceAuthorityProvider.TargetAdmissionBinding
            targetAdmissionBinding() {
        return binding;
    }

    /**
     * Creates the complete formal v2 binding using deployment-owned external authorities.
     *
     * <p>The loader supplies only immutable locally verified bytes, proof callbacks, and bound
     * lifecycle coordinates. The trusted clock, lifecycle/revocation preflight, and atomic durable
     * lease commit remain external deployment authority dependencies.</p>
     *
     * @param deploymentAuthorityBinding independently fingerprinted deployment authority binding
     * @return complete formal v2 target-admission binding
     */
    public CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding
            formalTargetAdmissionBinding(
            CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentAdmissionAuthorityBinding
                    deploymentAuthorityBinding) {
        return new CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetAdmissionBinding(
                bundleFingerprint, targetRawFingerprint, targetCanonicalFingerprint,
                binding.targetBindingBytes(), binding.candidateAttestationBytes(),
                binding.environmentAttestationBytes(), binding.verificationContext(),
                binding.candidateAuthority(), binding.environmentAuthority(), lifecycleMaterial,
                deploymentAuthorityBinding);
    }

    /**
     * Returns the complete target-admission material fingerprint.
     *
     * @return canonical manifest integrity fingerprint
     */
    public String bundleFingerprint() {
        return bundleFingerprint;
    }

    /**
     * Returns the exact Target Binding file fingerprint.
     *
     * @return lowercase raw {@code sha256:} fingerprint
     */
    public String targetRawFingerprint() {
        return targetRawFingerprint;
    }

    /**
     * Returns the canonical Target Binding document fingerprint.
     *
     * @return lowercase canonical {@code sha256:} fingerprint
     */
    public String targetCanonicalFingerprint() {
        return targetCanonicalFingerprint;
    }

    /**
     * Returns the locally bound lifecycle and revocation coordinates.
     *
     * @return immutable lifecycle material that still requires external currentness verification
     */
    public CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial
            lifecycleMaterial() {
        return lifecycleMaterial;
    }

    /**
     * Returns the manifest generation time.
     *
     * @return manifest generation time
     */
    public Instant generatedAt() {
        return generatedAt;
    }

    /**
     * Returns the manifest expiry time.
     *
     * @return manifest expiry time
     */
    public Instant expiresAt() {
        return expiresAt;
    }

    /** Redacted description that contains no paths, coordinates, proofs, or key material. */
    @Override
    public String toString() {
        return "CapabilityStudioMountedTargetAdmissionBundle[material=REDACTED, "
                + "target=REDACTED, authorities=REDACTED]";
    }

    private static AuthorityPolicy policy(
            ObjectNode node,
            String proofVersion,
            String role,
            Map<String, FileDeclaration> declarations,
            Map<Path, FileSnapshot> snapshots,
            Path root,
            Clock clock) throws IOException {
        String keySetFile = text(node, "keySetFile");
        String proofFile = text(node, "proofFile");
        if (declarations.get(keySetFile).kind() != keySetKind(role)
                || declarations.get(proofFile).kind() != proofKind(role)) {
            throw failure("ROLE_CONFUSION");
        }
        JsonNode keySetNode = parseSingle(bytes(snapshots, root, keySetFile));
        EvidenceVerificationKeySet keySet;
        try {
            keySet = EvidenceVerificationKeySet.fromPayload(keySetNode);
        } catch (RuntimeException invalid) {
            throw failure("KEY_SET_INVALID");
        }
        String keySetPin = text(node, "pinnedKeySetFingerprint");
        Instant keySetVerificationTime = trustedInstant(clock);
        TestSuiteEvidenceVerifier.KeySetVerificationResult admission;
        try {
            admission = new TestSuiteEvidenceVerifier(
                    Clock.fixed(keySetVerificationTime, clock.getZone()))
                    .verifyKeySet(keySet, keySetPin);
        } catch (RuntimeException invalid) {
            throw failure("KEY_SET_ADMISSION_REJECTED");
        }
        if (!admission.verified()) {
            throw failure("KEY_SET_ADMISSION_REJECTED");
        }
        String proofSchema = "CANDIDATE_AUTHORITY".equals(role)
                ? CapabilityStudioSchemaSupport.CANDIDATE_TARGET_ADMISSION_PROOF_V1_RESOURCE
                : CapabilityStudioSchemaSupport.ENVIRONMENT_TARGET_ADMISSION_PROOF_V1_RESOURCE;
        Proof proof = proof(parseAndValidate(bytes(snapshots, root, proofFile),
                proofSchema, "PROOF_INVALID"), proofVersion, role);
        return new AuthorityPolicy(text(node, "policyRef"), role, text(node, "issuer"),
                text(node, "scope"), keySetFile, text(node, "keySetFileFingerprint"),
                keySetPin, Duration.ofSeconds(node.path("maximumProofTtlSeconds").longValue()),
                proofFile, text(node, "proofFileFingerprint"), keySet, proof);
    }

    private static AuthorityDecision verifyCandidate(
            CandidateAttestationFacts facts,
            ProofBindingContext context,
            AuthorityPolicy policy,
            Instant generatedAt,
            Instant expiresAt,
            Clock clock) {
        try {
            if (facts == null || !"CANDIDATE_AUTHORITY".equals(policy.role())
                    || !facts.coordinate().equals(context.candidateCoordinate())) {
                return AuthorityDecision.rejected();
            }
            String fingerprint = candidateProofFingerprint(facts, context,
                    policy.policyRef(), policy.keySetPin(), policy.proof().keyId(),
                    policy.proof().signedAt(), policy.proof().expiresAt());
            return verifyProof(facts.role(), facts.issuer(), facts.scope(), facts.issuedAt(),
                    facts.expiresAt(), fingerprint, policy, generatedAt, expiresAt, clock);
        } catch (CapabilityStudioStageAcceptanceAuthorityProvider
                 .DeploymentUnavailableException unavailable) {
            return AuthorityDecision.unavailable();
        } catch (RuntimeException failure) {
            return AuthorityDecision.rejected();
        }
    }

    private static AuthorityDecision verifyEnvironment(
            EnvironmentAttestationFacts facts,
            ProofBindingContext context,
            AuthorityPolicy policy,
            Instant generatedAt,
            Instant expiresAt,
            Clock clock) {
        try {
            if (facts == null || !"ENVIRONMENT_AUTHORITY".equals(policy.role())
                    || !facts.coordinate().equals(context.environmentCoordinate())
                    || !facts.candidateAttestation().equals(context.candidateCoordinate())
                    || !facts.executionLeaseId().equals(context.executionLeaseId())
                    || !facts.trustedTargetIdentities().equals(
                    context.trustedTargetIdentities())) {
                return AuthorityDecision.rejected();
            }
            String fingerprint = environmentProofFingerprint(facts, context,
                    policy.policyRef(), policy.keySetPin(), policy.proof().keyId(),
                    policy.proof().signedAt(), policy.proof().expiresAt());
            return verifyProof(facts.role(), facts.issuer(), facts.scope(), facts.issuedAt(),
                    facts.expiresAt(), fingerprint, policy, generatedAt, expiresAt, clock);
        } catch (CapabilityStudioStageAcceptanceAuthorityProvider
                 .DeploymentUnavailableException unavailable) {
            return AuthorityDecision.unavailable();
        } catch (RuntimeException failure) {
            return AuthorityDecision.rejected();
        }
    }

    private static AuthorityDecision verifyProof(
            String role,
            String issuer,
            String scope,
            Instant issuedAt,
            Instant attestationExpiresAt,
            String expectedFactsFingerprint,
            AuthorityPolicy policy,
            Instant generatedAt,
            Instant expiresAt,
            Clock clock) {
        try {
            Proof proof = policy.proof();
            Instant now = trustedInstant(clock);
            if (!role.equals(policy.role()) || !issuer.equals(policy.issuer())
                    || !scope.equals(policy.scope())
                    || !issuedAt.equals(proof.signedAt())
                    || !attestationExpiresAt.equals(proof.expiresAt())
                    || proof.signedAt().isAfter(now)
                    || !proof.expiresAt().isAfter(now)
                    || !proof.expiresAt().isAfter(proof.signedAt())
                    || Duration.between(proof.signedAt(), proof.expiresAt())
                    .compareTo(policy.maximumTtl()) > 0
                    || generatedAt.isAfter(now) || !expiresAt.isAfter(now)
                    || !"Ed25519".equals(proof.algorithm())
                    || !expectedFactsFingerprint.equals(proof.signedFactsFingerprint())) {
                return AuthorityDecision.rejected();
            }
            TestSuiteEvidenceVerifier.KeySetVerificationResult keySetAdmission =
                    new TestSuiteEvidenceVerifier(Clock.fixed(now, clock.getZone())).verifyKeySet(
                            policy.keySet(), policy.keySetPin());
            if (!keySetAdmission.verified()) {
                return AuthorityDecision.rejected();
            }
            EvidenceVerificationKeySet.KeyPolicy key = policy.keySet().keys().stream()
                    .filter(candidate -> candidate.keyId().equals(proof.keyId()))
                    .findFirst().orElse(null);
            if (key == null || !proof.keyId().equals(key.keyId())
                    || !proof.algorithm().equals(key.algorithm())
                    || !EvidenceVerificationSupport.signingTimePolicyReason(
                    policy.keySet(), proof.keyId(), proof.signedAt()).isBlank()
                    || !EvidenceVerificationSupport.verifyEd25519(
                    expectedFactsFingerprint, proof.signature(), key.encodedPublicKey())) {
                return AuthorityDecision.rejected();
            }
            return AuthorityDecision.verified();
        } catch (CapabilityStudioStageAcceptanceAuthorityProvider
                 .DeploymentUnavailableException unavailable) {
            return AuthorityDecision.unavailable();
        } catch (GeneralSecurityException | RuntimeException failure) {
            return AuthorityDecision.rejected();
        }
    }

    private static String proofFingerprint(
            String version,
            String role,
            ObjectNode typedFacts,
            ProofBindingContext context,
            String policyRef,
            String issuer,
            String scope,
            String keySetPin,
            String keyId,
            Instant signedAt,
            Instant expiresAt) {
        Objects.requireNonNull(context, "context is required");
        requireRef(policyRef, "policyRef");
        requireRef(issuer, "issuer");
        requireRef(scope, "scope");
        requireFingerprint(keySetPin, "pinnedKeySetFingerprint");
        requireRef(keyId, "keyId");
        Objects.requireNonNull(signedAt, "signedAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        ObjectNode material = JSON.createObjectNode();
        material.put("messageVersion", version);
        material.put("proofRole", role);
        material.put("targetRawFingerprint", context.targetRawFingerprint());
        material.put("targetCanonicalFingerprint", context.targetCanonicalFingerprint());
        material.set("candidateCoordinate", candidateCoordinateNode(
                context.candidateCoordinate()));
        material.set("environmentCoordinate", environmentCoordinateNode(
                context.environmentCoordinate()));
        material.put("executionLeaseId", context.executionLeaseId());
        ArrayNode identities = material.putArray("trustedTargetIdentities");
        context.trustedTargetIdentities().stream().sorted().forEach(identities::add);
        material.put("policyRef", policyRef);
        material.put("issuer", issuer);
        material.put("scope", scope);
        material.put("pinnedKeySetFingerprint", keySetPin);
        material.put("keyId", keyId);
        material.put("signedAt", signedAt.toString());
        material.put("expiresAt", expiresAt.toString());
        material.set("typedFacts", typedFacts);
        return EvidenceVerificationSupport.sha256(material);
    }

    private static ObjectNode candidateFactsNode(CandidateAttestationFacts facts) {
        ObjectNode node = JSON.createObjectNode();
        node.set("coordinate", candidateCoordinateNode(facts.coordinate()));
        node.put("buildRef", facts.buildRef());
        node.put("revision", facts.revision());
        node.put("sourceCommit", facts.sourceCommit());
        node.put("sourceTreeStatus", facts.sourceTreeStatus());
        node.put("artifactDigest", facts.artifactDigest());
        node.set("baselineRef", exactReferenceNode(facts.baselineRef()));
        node.set("demoPackRef", exactReferenceNode(facts.demoPackRef()));
        node.put("executionIntentFingerprint", facts.executionIntentFingerprint());
        node.put("scope", facts.scope());
        node.put("role", facts.role());
        node.put("issuer", facts.issuer());
        node.put("issuedAt", facts.issuedAt().toString());
        node.put("expiresAt", facts.expiresAt().toString());
        return node;
    }

    private static ObjectNode environmentFactsNode(EnvironmentAttestationFacts facts) {
        ObjectNode node = JSON.createObjectNode();
        node.set("coordinate", environmentCoordinateNode(facts.coordinate()));
        node.put("executionLeaseId", facts.executionLeaseId());
        node.set("candidateAttestation", candidateCoordinateNode(
                facts.candidateAttestation()));
        node.put("environmentFingerprint", facts.environmentFingerprint());
        node.put("targetProfile", facts.targetProfile());
        node.put("scope", facts.scope());
        node.put("region", facts.region());
        node.put("runtimeIdentity", facts.runtimeIdentity());
        node.put("networkPolicy", facts.networkPolicy());
        node.set("featureFlagsRef", exactReferenceNode(facts.featureFlagsRef()));
        node.put("logicalClock", facts.logicalClock().toString());
        ObjectNode window = node.putObject("admissionWindow");
        window.put("from", facts.admissionWindow().from().toString());
        window.put("through", facts.admissionWindow().through().toString());
        ArrayNode identities = node.putArray("trustedTargetIdentities");
        facts.trustedTargetIdentities().stream().sorted().forEach(identities::add);
        node.put("role", facts.role());
        node.put("issuer", facts.issuer());
        node.put("issuedAt", facts.issuedAt().toString());
        node.put("expiresAt", facts.expiresAt().toString());
        return node;
    }

    private static ObjectNode candidateCoordinateNode(CandidateCoordinate coordinate) {
        return JSON.createObjectNode().put("candidateRef", coordinate.candidateRef())
                .put("attestationRevision", coordinate.attestationRevision())
                .put("fingerprint", coordinate.fingerprint());
    }

    private static ObjectNode environmentCoordinateNode(EnvironmentCoordinate coordinate) {
        return JSON.createObjectNode().put("environmentRef", coordinate.environmentRef())
                .put("attestationRevision", coordinate.attestationRevision())
                .put("fingerprint", coordinate.fingerprint());
    }

    private static ObjectNode exactReferenceNode(ExactReference reference) {
        return JSON.createObjectNode().put("exactRef", reference.exactRef())
                .put("fingerprint", reference.fingerprint());
    }

    private static CandidateAttestationFacts candidateFacts(
            JsonNode value, CandidateCoordinate coordinate) {
        return new CandidateAttestationFacts(coordinate, text(value, "buildRef"),
                text(value, "revision"), text(value, "sourceCommit"),
                text(value, "sourceTreeStatus"), text(value, "artifactDigest"),
                exactReference(value.path("baselineRef")), exactReference(value.path("demoPackRef")),
                text(value, "executionIntentFingerprint"), text(value, "scope"),
                text(value, "role"), text(value, "issuer"), instant(value, "issuedAt"),
                instant(value, "expiresAt"));
    }

    private static EnvironmentAttestationFacts environmentFacts(
            JsonNode value, EnvironmentCoordinate coordinate) {
        JsonNode window = value.path("admissionWindow");
        return new EnvironmentAttestationFacts(coordinate, text(value, "executionLeaseId"),
                new CandidateCoordinate(text(value.path("candidateAttestation"), "candidateRef"),
                        value.path("candidateAttestation").path("attestationRevision").longValue(),
                        text(value.path("candidateAttestation"), "fingerprint")),
                text(value, "environmentFingerprint"), text(value, "targetProfile"),
                text(value, "scope"), text(value, "region"), text(value, "runtimeIdentity"),
                text(value, "networkPolicy"), exactReference(value.path("featureFlagsRef")),
                instant(value, "logicalClock"), new AdmissionWindow(
                instant(window, "from"), instant(window, "through")),
                stringSet(value.path("trustedTargetIdentities")), text(value, "role"),
                text(value, "issuer"), instant(value, "issuedAt"), instant(value, "expiresAt"));
    }

    private static CandidateCoordinate candidateCoordinate(JsonNode spec, JsonNode attestation) {
        CandidateCoordinate expected = new CandidateCoordinate(text(spec, "reference"),
                spec.path("revision").longValue(), text(spec, "fileFingerprint"));
        if (!expected.candidateRef().equals(attestation.path("candidateRef").textValue())
                || expected.attestationRevision()
                != attestation.path("attestationRevision").longValue()) {
            throw failure("CANDIDATE_COORDINATE_MISMATCH");
        }
        return expected;
    }

    private static EnvironmentCoordinate environmentCoordinate(
            JsonNode spec, JsonNode attestation) {
        EnvironmentCoordinate expected = new EnvironmentCoordinate(text(spec, "reference"),
                spec.path("revision").longValue(), text(spec, "fileFingerprint"));
        if (!expected.environmentRef().equals(attestation.path("environmentRef").textValue())
                || expected.attestationRevision()
                != attestation.path("attestationRevision").longValue()) {
            throw failure("ENVIRONMENT_COORDINATE_MISMATCH");
        }
        return expected;
    }

    private static CandidateCoordinate candidateCoordinate(JsonNode value) {
        return new CandidateCoordinate(text(value, "candidateRef"),
                value.path("attestationRevision").longValue(), text(value, "fingerprint"));
    }

    private static EnvironmentCoordinate environmentCoordinate(JsonNode value) {
        return new EnvironmentCoordinate(text(value, "environmentRef"),
                value.path("attestationRevision").longValue(), text(value, "fingerprint"));
    }

    private static ExactReference exactReference(JsonNode value) {
        return new ExactReference(text(value, "exactRef"), text(value, "fingerprint"));
    }

    private static Proof proof(JsonNode value, String version, String role) {
        if (value == null || !value.isObject()) {
            throw failure("PROOF_INVALID");
        }
        if (!version.equals(value.path("schemaVersion").textValue())
                || !role.equals(value.path("role").textValue())
                || !"Ed25519".equals(value.path("algorithm").textValue())) {
            throw failure("PROOF_INVALID");
        }
        String signature = text(value, "signature");
        try {
            if (Base64.getDecoder().decode(signature).length != 64) {
                throw failure("PROOF_INVALID");
            }
        } catch (IllegalArgumentException invalid) {
            throw failure("PROOF_INVALID");
        }
        return new Proof(version, role, "Ed25519", text(value, "keyId"),
                instant(value, "signedAt"), instant(value, "expiresAt"),
                text(value, "signedFactsFingerprint"), signature);
    }

    private static void rejectPolicyCollapse(AuthorityPolicy candidate, AuthorityPolicy environment) {
        if (candidate.policyRef().equals(environment.policyRef())
                || candidate.role().equals(environment.role())
                || candidate.issuer().equals(environment.issuer())
                || candidate.keySetFile().equals(environment.keySetFile())
                || candidate.keySetFileFingerprint().equals(
                environment.keySetFileFingerprint())
                || candidate.keySetPin().equals(environment.keySetPin())
                || candidate.proofFile().equals(environment.proofFile())
                || candidate.proofFileFingerprint().equals(
                environment.proofFileFingerprint())
                || candidate.proof().keyId().equals(environment.proof().keyId())
                || candidate.proof().signature().equals(environment.proof().signature())) {
            throw failure("AUTHORITY_POLICY_COLLAPSE");
        }
    }

    private static JsonNode parseAndValidate(byte[] bytes, String schema, String code)
            throws IOException {
        JsonNode value = parseSingle(bytes);
        if (value == null || !value.isObject()
                || !CapabilityStudioSchemaSupport.validate(value, schema).isEmpty()) {
            throw failure(code);
        }
        return value;
    }

    private static JsonNode parseSingle(byte[] bytes) throws IOException {
        try (JsonParser parser = JSON.getFactory().createParser(bytes)) {
            JsonNode value = JSON.readTree(parser);
            if (value == null || parser.nextToken() != null) {
                throw failure("JSON_INVALID");
            }
            return value;
        } catch (IllegalStateException stable) {
            if (stable.getMessage() != null && stable.getMessage().startsWith(CODE)) {
                throw stable;
            }
            throw failure("JSON_INVALID");
        } catch (IOException | RuntimeException invalid) {
            throw failure("JSON_INVALID");
        }
    }

    private static void requireManifestSchema(ObjectNode manifest) {
        if (!SCHEMA_VERSION.equals(manifest.path("schemaVersion").asText())
                || !CapabilityStudioSchemaSupport.validate(manifest,
                CapabilityStudioSchemaSupport.MOUNTED_TARGET_ADMISSION_BUNDLE_V1_RESOURCE)
                .isEmpty()) {
            throw failure("MANIFEST_SCHEMA_INVALID");
        }
    }

    private static void declare(
            Map<String, FileDeclaration> declarations,
            JsonNode node,
            String fileField,
            String fingerprintField,
            FileKind kind) {
        String file = text(node, fileField);
        String fingerprint = text(node, fingerprintField);
        if (!SAFE_FILE.matcher(file).matches() || !FINGERPRINT.matcher(fingerprint).matches()
                || declarations.putIfAbsent(file, new FileDeclaration(kind, fingerprint)) != null) {
            throw failure("DUPLICATE_FILE_BINDING");
        }
    }

    private static int maximum(FileKind kind) {
        return switch (kind) {
            case TARGET -> CapabilityStudioStageAcceptanceTargetBindingVerifier
                    .MAXIMUM_TARGET_BINDING_BYTES;
            case CANDIDATE_ATTESTATION -> CapabilityStudioStageAcceptanceTargetBindingVerifier
                    .MAXIMUM_CANDIDATE_ATTESTATION_BYTES;
            case ENVIRONMENT_ATTESTATION -> CapabilityStudioStageAcceptanceTargetBindingVerifier
                    .MAXIMUM_ENVIRONMENT_ATTESTATION_BYTES;
            case CANDIDATE_KEY_SET, ENVIRONMENT_KEY_SET -> MAX_KEY_SET_BYTES;
            case CANDIDATE_PROOF, ENVIRONMENT_PROOF -> MAX_PROOF_BYTES;
        };
    }

    private static FileKind keySetKind(String role) {
        return "CANDIDATE_AUTHORITY".equals(role)
                ? FileKind.CANDIDATE_KEY_SET : FileKind.ENVIRONMENT_KEY_SET;
    }

    private static FileKind proofKind(String role) {
        return "CANDIDATE_AUTHORITY".equals(role)
                ? FileKind.CANDIDATE_PROOF : FileKind.ENVIRONMENT_PROOF;
    }

    private static Path directChild(Path root, String name) {
        if (!SAFE_FILE.matcher(name).matches()) {
            throw failure("PATH_INVALID");
        }
        Path child = root.resolve(name).normalize();
        if (!root.equals(child.getParent())) {
            throw failure("PATH_INVALID");
        }
        return child;
    }

    private static FileSnapshot readBounded(
            Path root, Path file, int maximumBytes, RootIdentity rootIdentity) throws IOException {
        ensureRootStable(root, rootIdentity);
        BasicFileAttributes before = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.isSymbolicLink() || !before.isRegularFile() || before.fileKey() == null
                || before.size() < 1 || before.size() > maximumBytes) {
            throw failure("FILE_INVALID");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) before.size());
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        try (SeekableByteChannel channel = Files.newByteChannel(
                file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            int count = 0;
            while (true) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                count += read;
                if (count > maximumBytes) {
                    throw failure("FILE_SIZE_LIMIT");
                }
                output.write(buffer.array(), 0, read);
                buffer.clear();
            }
        }
        BasicFileAttributes after = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!sameFile(before, after) || output.size() != after.size()) {
            throw failure("FILE_CHANGED");
        }
        ensureRootStable(root, rootIdentity);
        return new FileSnapshot(output.toByteArray(), after.fileKey(), after.size(),
                after.lastModifiedTime().toMillis());
    }

    private static boolean sameFile(BasicFileAttributes first, BasicFileAttributes second) {
        return second.isRegularFile() && !second.isSymbolicLink() && second.fileKey() != null
                && first.fileKey().equals(second.fileKey()) && first.size() == second.size()
                && first.lastModifiedTime().equals(second.lastModifiedTime());
    }

    private static RootIdentity requireRoot(Path root) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()
                || attributes.fileKey() == null) {
            throw failure("ROOT_INVALID");
        }
        return new RootIdentity(attributes.fileKey());
    }

    private static void ensureRootStable(Path root, RootIdentity expected) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()
                || attributes.fileKey() == null
                || !expected.fileKey().equals(attributes.fileKey())) {
            throw failure("ROOT_CHANGED");
        }
    }

    private static void ensureFilesStable(Map<Path, FileSnapshot> snapshots) throws IOException {
        for (Map.Entry<Path, FileSnapshot> entry : snapshots.entrySet()) {
            BasicFileAttributes attributes = Files.readAttributes(
                    entry.getKey(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            FileSnapshot expected = entry.getValue();
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.fileKey() == null
                    || !expected.fileKey().equals(attributes.fileKey())
                    || expected.size() != attributes.size()
                    || expected.lastModifiedMillis()
                    != attributes.lastModifiedTime().toMillis()) {
                throw failure("FILE_CHANGED");
            }
        }
    }

    private static void registerFileKey(
            Map<Object, String> fileKeys, FileSnapshot snapshot, String name) {
        if (fileKeys.putIfAbsent(snapshot.fileKey(), name) != null) {
            throw failure("FILE_IDENTITY_COLLISION");
        }
    }

    private static void requireOnlyListedFiles(Path root, Set<String> declarations)
            throws IOException {
        Set<String> expected = new HashSet<>(declarations);
        expected.add(MANIFEST_FILE);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                BasicFileAttributes attributes = Files.readAttributes(
                        child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()
                        || !expected.remove(name)) {
                    throw failure("UNLISTED_FILE");
                }
            }
        }
        if (!expected.isEmpty()) {
            throw failure("FILE_INVALID");
        }
    }

    private static byte[] bytes(Map<Path, FileSnapshot> snapshots, Path root, String file) {
        FileSnapshot snapshot = snapshots.get(root.resolve(file));
        if (snapshot == null) {
            throw failure("FILE_INVALID");
        }
        return snapshot.bytes();
    }

    private static long addBytes(long total, long bytes) {
        if (bytes < 0 || total > MAX_TOTAL_BYTES - bytes) {
            throw failure("TOTAL_SIZE_LIMIT");
        }
        return total + bytes;
    }

    private static ObjectNode object(JsonNode value, String code) {
        if (value == null || !value.isObject()) {
            throw failure(code);
        }
        return (ObjectNode) value;
    }

    private static String text(JsonNode value, String field) {
        String text = value.path(field).textValue();
        if (text == null || text.isBlank()) {
            throw failure("FIELD_INVALID");
        }
        return text;
    }

    private static Instant instant(JsonNode value, String field) {
        try {
            return Instant.parse(text(value, field));
        } catch (DateTimeParseException failure) {
            throw failure("TIME_INVALID");
        }
    }

    private static String nullableFingerprint(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        String fingerprint = value.textValue();
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw failure("FIELD_INVALID");
        }
        return fingerprint;
    }

    private static Instant trustedInstant(Clock clock) {
        try {
            Instant instant = clock.instant();
            if (instant == null) {
                throw new IllegalStateException("clock returned null");
            }
            return instant;
        } catch (CapabilityStudioStageAcceptanceAuthorityProvider
                 .DeploymentUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    private static Set<String> stringSet(JsonNode value) {
        if (value == null || !value.isArray()) {
            throw failure("FIELD_INVALID");
        }
        Set<String> values = new HashSet<>();
        value.forEach(item -> {
            String text = item.textValue();
            if (!validRef(text) || !values.add(text)) {
                throw failure("FIELD_INVALID");
            }
        });
        return Set.copyOf(values);
    }

    private static void requireFingerprint(String value, String field) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireRef(String value, String field) {
        if (!validRef(value)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static boolean validRef(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,511}");
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static IllegalStateException failure(String suffix) {
        return new IllegalStateException(CODE + suffix);
    }

    private static CapabilityStudioStageAcceptanceAuthorityProvider
            .DeploymentUnavailableException unavailable() {
        return new CapabilityStudioStageAcceptanceAuthorityProvider
                .DeploymentUnavailableException();
    }

    private enum FileKind {
        TARGET,
        CANDIDATE_ATTESTATION,
        ENVIRONMENT_ATTESTATION,
        CANDIDATE_KEY_SET,
        ENVIRONMENT_KEY_SET,
        CANDIDATE_PROOF,
        ENVIRONMENT_PROOF
    }

    private record FileDeclaration(FileKind kind, String fingerprint) { }

    private record FileSnapshot(
            byte[] bytes, Object fileKey, long size, long lastModifiedMillis) {
        private FileSnapshot {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record RootIdentity(Object fileKey) { }

    private record Proof(
            String version,
            String role,
            String algorithm,
            String keyId,
            Instant signedAt,
            Instant expiresAt,
            String signedFactsFingerprint,
            String signature) { }

    private record AuthorityPolicy(
            String policyRef,
            String role,
            String issuer,
            String scope,
            String keySetFile,
            String keySetFileFingerprint,
            String keySetPin,
            Duration maximumTtl,
            String proofFile,
            String proofFileFingerprint,
            EvidenceVerificationKeySet keySet,
            Proof proof) { }
}
