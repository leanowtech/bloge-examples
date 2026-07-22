package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Short-lived, externally signed proof of one mirror deployment's data-plane isolation.
 *
 * <p>The attestation is deliberately issued outside Resource Gateway. It binds an exact workload
 * identity and image to fail-closed egress, credential, and service-identity controls, while
 * exposing only fingerprints and payload-free proof references. A run may cite the artifact only
 * after independently checking its authority key, signature, identity, and complete execution
 * window.</p>
 *
 * @param schemaVersion deployment-isolation attestation protocol version
 * @param attestationFingerprint canonical fingerprint of the complete signed artifact
 * @param material externally observed deployment and enforcement statement
 * @param seal detached authority signature over the domain-separated material fingerprint
 */
public record MirrorDeploymentIsolationAttestation(
        String schemaVersion,
        String attestationFingerprint,
        Material material,
        Seal seal
) {
    /** Current deployment-isolation attestation protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorDeploymentIsolationAttestation.v1";
    /** Maximum lifetime of one v1 isolation observation. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(15);
    /** Maximum delay from policy observation to authority signature. */
    public static final Duration MAXIMUM_ISSUANCE_DELAY = Duration.ofMinutes(5);
    /** Artifact kind used by {@link MirrorRunEvidence.IsolationFacts}. */
    public static final String ARTIFACT_KIND = "DEPLOYMENT_ISOLATION_ATTESTATION";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}");

    /** Validates the signed artifact envelope without treating it as trusted. */
    public MirrorDeploymentIsolationAttestation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : required(schemaVersion, "schemaVersion", 128);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported deployment isolation schemaVersion");
        }
        attestationFingerprint = fingerprint(attestationFingerprint,
                "attestationFingerprint");
        material = Objects.requireNonNull(material, "material");
        seal = Objects.requireNonNull(seal, "seal");
        if (seal.signedAt().isBefore(material.observedAt())
                || !seal.signedAt().isBefore(material.expiresAt())
                || Duration.between(material.observedAt(), seal.signedAt())
                .compareTo(MAXIMUM_ISSUANCE_DELAY) > 0) {
            throw new IllegalArgumentException(
                    "deployment isolation signature is outside its issuance window");
        }
    }

    /**
     * Creates the exact reference embedded in one mirror run.
     *
     * @return content-addressed deployment-isolation artifact reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(ARTIFACT_KIND, material.attestationId(),
                material.revision(), attestationFingerprint);
    }

    /**
     * Externally observed statement signed by the deployment isolation authority.
     *
     * @param attestationId stable attestation stream identity
     * @param revision positive immutable stream revision
     * @param deployment exact runtime workload identity
     * @param enforcement exact fail-closed policy facts
     * @param observedAt time the authority observed the deployed controls
     * @param validFrom earliest execution time covered by the observation
     * @param expiresAt exclusive validity bound
     * @param issuer external SRE or security authority identity
     */
    public record Material(
            String attestationId,
            long revision,
            DeploymentIdentity deployment,
            EnforcementFacts enforcement,
            Instant observedAt,
            Instant validFrom,
            Instant expiresAt,
            String issuer
    ) {
        /** Normalizes bounded coordinates and enforces the short-lived observation window. */
        public Material {
            attestationId = identifier(attestationId, "attestationId");
            if (revision < 1) {
                throw new IllegalArgumentException("attestation revision must be positive");
            }
            deployment = Objects.requireNonNull(deployment, "deployment");
            enforcement = Objects.requireNonNull(enforcement, "enforcement");
            observedAt = time(observedAt, "observedAt");
            validFrom = time(validFrom, "validFrom");
            expiresAt = time(expiresAt, "expiresAt");
            issuer = identifier(issuer, "issuer");
            if (validFrom.isBefore(observedAt) || !expiresAt.isAfter(validFrom)
                    || Duration.between(observedAt, expiresAt)
                    .compareTo(MAXIMUM_LIFETIME) > 0) {
                throw new IllegalArgumentException(
                        "deployment isolation observation window is invalid");
            }
        }
    }

    /**
     * Exact deployment generation that must match local immutable runtime coordinates.
     *
     * @param deploymentScopeId operator-owned deployment scope
     * @param clusterId exact cluster or equivalent scheduler identity
     * @param namespace exact namespace or workload isolation domain
     * @param workloadName exact deployment/workload name
     * @param serviceAccount exact non-production workload identity
     * @param imageDigest immutable container or executable image digest
     */
    public record DeploymentIdentity(
            String deploymentScopeId,
            String clusterId,
            String namespace,
            String workloadName,
            String serviceAccount,
            String imageDigest
    ) {
        /** Rejects incomplete or mutable deployment coordinates. */
        public DeploymentIdentity {
            deploymentScopeId = identifier(deploymentScopeId, "deploymentScopeId");
            clusterId = identifier(clusterId, "clusterId");
            namespace = identifier(namespace, "namespace");
            workloadName = identifier(workloadName, "workloadName");
            serviceAccount = identifier(serviceAccount, "serviceAccount");
            imageDigest = fingerprint(imageDigest, "imageDigest");
        }
    }

    /**
     * Payload-free facts proving that business data-plane escape remains denied.
     *
     * @param enforcementLayers ordered independent policy enforcement layers
     * @param failClosed whether policy evaluation or control loss denies traffic
     * @param defaultDenyEgress whether undeclared egress is denied
     * @param externalBusinessEgressDenied whether business providers and public data planes are denied
     * @param productionCredentialsDenied whether production credentials cannot be mounted or resolved
     * @param productionIdentityDenied whether production service identity cannot be assumed
     * @param continuousEnforcement whether controls remain active for the full attestation window
     * @param networkPolicyFingerprint exact effective network policy generation
     * @param credentialPolicyFingerprint exact effective secret/identity policy generation
     * @param allowedDestinationsFingerprint exact canonical allowlist generation
     * @param allowedEgressClasses bounded non-business destination classes
     * @param proofRefs ordered payload-free policy evaluation or deployment proof references
     */
    public record EnforcementFacts(
            List<EnforcementLayer> enforcementLayers,
            boolean failClosed,
            boolean defaultDenyEgress,
            boolean externalBusinessEgressDenied,
            boolean productionCredentialsDenied,
            boolean productionIdentityDenied,
            boolean continuousEnforcement,
            String networkPolicyFingerprint,
            String credentialPolicyFingerprint,
            String allowedDestinationsFingerprint,
            List<AllowedEgressClass> allowedEgressClasses,
            List<MirrorArtifactRef> proofRefs
    ) {
        /** Enforces deterministic ordering and mandatory deny controls. */
        public EnforcementFacts {
            enforcementLayers = orderedEnums(enforcementLayers, "enforcementLayers", 8, true);
            allowedEgressClasses = orderedEnums(
                    allowedEgressClasses, "allowedEgressClasses", 8, false);
            proofRefs = orderedProofs(proofRefs);
            networkPolicyFingerprint = fingerprint(
                    networkPolicyFingerprint, "networkPolicyFingerprint");
            credentialPolicyFingerprint = fingerprint(
                    credentialPolicyFingerprint, "credentialPolicyFingerprint");
            allowedDestinationsFingerprint = fingerprint(
                    allowedDestinationsFingerprint, "allowedDestinationsFingerprint");
            if (!failClosed || !defaultDenyEgress || !externalBusinessEgressDenied
                    || !productionCredentialsDenied || !productionIdentityDenied
                    || !continuousEnforcement) {
                throw new IllegalArgumentException(
                        "deployment isolation controls must all fail closed");
            }
        }
    }

    /** Supported out-of-process enforcement layers. */
    public enum EnforcementLayer {
        /** Cloud virtual-network policy evaluated outside the workload process. */
        CLOUD_NETWORK_POLICY,
        /** Host-level firewall independent of the workload process. */
        HOST_FIREWALL,
        /** Kubernetes namespace or pod network policy. */
        KUBERNETES_NETWORK_POLICY,
        /** Service-mesh authorization enforced by a separate data plane. */
        SERVICE_MESH_AUTHORIZATION,
        /** Workload sandbox restricting process capabilities and network access. */
        WORKLOAD_SANDBOX
    }

    /** Non-business destination classes that a mirror deployment may explicitly retain. */
    public enum AllowedEgressClass {
        /** Name resolution required by approved infrastructure destinations. */
        DNS,
        /** Payload-free detached evidence-signing service. */
        EVIDENCE_SIGNER,
        /** Mirror orchestration control plane without business payload access. */
        MIRROR_CONTROL_PLANE,
        /** Payload-free metrics, traces, and health reporting. */
        OBSERVABILITY,
        /** Database constrained to payload-free mirror control-plane records. */
        PAYLOAD_FREE_DATABASE
    }

    /**
     * Detached Ed25519 authority signature.
     *
     * @param materialFingerprint canonical domain-separated material fingerprint
     * @param algorithm fixed signature algorithm
     * @param keyId externally pinned authority key id
     * @param signedAt authority signing time
     * @param signature base64 detached signature over the material fingerprint text
     */
    public record Seal(
            String materialFingerprint,
            String algorithm,
            String keyId,
            Instant signedAt,
            String signature
    ) {
        /** Validates detached signature syntax without claiming cryptographic validity. */
        public Seal {
            materialFingerprint = fingerprint(materialFingerprint, "materialFingerprint");
            algorithm = required(algorithm, "algorithm", 32);
            keyId = identifier(keyId, "keyId");
            signedAt = time(signedAt, "signedAt");
            signature = canonicalBase64(required(signature, "signature", 4_096),
                    "signature");
            if (!"Ed25519".equals(algorithm)) {
                throw new IllegalArgumentException("deployment isolation signatures require Ed25519");
            }
        }
    }

    private static List<MirrorArtifactRef> orderedProofs(List<MirrorArtifactRef> values) {
        if (values == null || values.isEmpty() || values.size() > 32) {
            throw new IllegalArgumentException("proofRefs must contain between 1 and 32 values");
        }
        List<MirrorArtifactRef> copy = new ArrayList<>(values);
        copy.replaceAll(value -> Objects.requireNonNull(value, "proofRef"));
        List<MirrorArtifactRef> sorted = copy.stream().sorted(
                Comparator.comparing(MirrorArtifactRef::kind)
                .thenComparing(MirrorArtifactRef::id)
                .thenComparingLong(MirrorArtifactRef::revision)
                .thenComparing(MirrorArtifactRef::fingerprint)).toList();
        if (!copy.equals(sorted)) {
            throw new IllegalArgumentException("proofRefs must use canonical order");
        }
        if (copy.stream().anyMatch(ref -> !"DEPLOYMENT_POLICY_PROOF".equals(ref.kind()))) {
            throw new IllegalArgumentException(
                    "proofRefs must reference DEPLOYMENT_POLICY_PROOF artifacts");
        }
        for (int index = 1; index < copy.size(); index++) {
            MirrorArtifactRef previous = copy.get(index - 1);
            MirrorArtifactRef current = copy.get(index);
            if (previous.kind().equals(current.kind())
                    && previous.id().equals(current.id())
                    && previous.revision() == current.revision()) {
                throw new IllegalArgumentException(
                        "proofRefs must use unique artifact coordinates");
            }
        }
        return List.copyOf(copy);
    }

    private static <E extends Enum<E>> List<E> orderedEnums(
            List<E> values, String field, int maximum, boolean required) {
        if (values == null || values.size() > maximum || required && values.isEmpty()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        List<E> copy = new ArrayList<>(values);
        copy.replaceAll(value -> Objects.requireNonNull(value, field + " value"));
        List<E> sorted = copy.stream().sorted(Comparator.comparing(Enum::name)).toList();
        if (!copy.equals(sorted)) {
            throw new IllegalArgumentException(field + " must use canonical order");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1) == copy.get(index)) {
                throw new IllegalArgumentException(field + " must be unique");
            }
        }
        return List.copyOf(copy);
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field, 71);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }

    private static String identifier(String value, String field) {
        String normalized = required(value, field, 512);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }

    private static String required(String value, String field, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " must be bounded and non-blank");
        }
        return normalized;
    }

    private static String canonicalBase64(String value, String field) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 0
                    || !value.equals(Base64.getEncoder().encodeToString(decoded))) {
                throw new IllegalArgumentException(field + " must be canonical base64");
            }
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " must be canonical base64", invalid);
        }
    }

    private static Instant time(Instant value, String field) {
        Instant exact = Objects.requireNonNull(value, field);
        if (Instant.EPOCH.equals(exact)) {
            throw new IllegalArgumentException(field + " must not be epoch");
        }
        return exact;
    }
}
