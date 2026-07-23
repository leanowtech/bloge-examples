package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical M-of-N sealing and independent verification for isolation-authority publications.
 *
 * <p>{@link #seal} is compatibility and external-authority tooling, not a runtime trust shortcut.
 * Verification requires exact local binding policy, pinned bootstrap-root public keys, a trusted
 * monotonic floor, and current time. Every supplied signature must be recognized and valid; extra
 * unknown signatures therefore cannot be used to smuggle a weaker root policy into a publication.</p>
 */
public final class MirrorDeploymentIsolationAuthorityKeySetIntegrity {
    /** Maximum canonical signed material size. */
    public static final int MAXIMUM_MATERIAL_BYTES = 1024 * 1024;
    /** Maximum canonical complete publication size. */
    public static final int MAXIMUM_PUBLICATION_BYTES = 2 * 1024 * 1024;
    /** Root-signature domain separator. */
    public static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_V1";

    private final ObjectMapper mapper;

    /**
     * Creates a canonical publication integrity boundary.
     *
     * @param mapper canonical protocol mapper
     */
    public MirrorDeploymentIsolationAuthorityKeySetIntegrity(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Produces a threshold-signed publication using external bootstrap-root signers.
     *
     * @param material immutable authority key-set material
     * @param signers independent named bootstrap-root signers
     * @return content-addressed threshold-signed publication
     */
    public MirrorDeploymentIsolationAuthorityKeySetPublication seal(
            MirrorDeploymentIsolationAuthorityKeySetPublication.Material material,
            List<NamedRootSigner> signers) {
        Objects.requireNonNull(material, "material");
        if (signers == null || signers.size() < material.rootThreshold()
                || signers.size()
                > MirrorDeploymentIsolationAuthorityKeySetPublication.MAXIMUM_ROOT_SIGNATURES) {
            throw new IllegalArgumentException("bootstrap-root signers do not satisfy threshold");
        }
        String materialFingerprint = materialFingerprint(material);
        List<MirrorDeploymentIsolationAuthorityKeySetPublication.RootSignature> signatures =
                new ArrayList<>();
        Set<String> authorities = new HashSet<>();
        Set<String> cryptographicSignatures = new HashSet<>();
        for (NamedRootSigner named : signers) {
            NamedRootSigner exact = Objects.requireNonNull(named, "namedRootSigner");
            if (!authorities.add(exact.authorityId())) {
                throw new IllegalArgumentException("bootstrap-root authorities must be distinct");
            }
            if (!exact.signer().available()) {
                throw new IllegalArgumentException("bootstrap-root signer is unavailable");
            }
            VisualRunEvidenceSeal seal = exact.signer().seal(materialFingerprint);
            if (!cryptographicSignatures.add(seal.algorithm() + '\0' + seal.signature())) {
                throw new IllegalArgumentException(
                        "bootstrap-root signers must use distinct key material");
            }
            signatures.add(new MirrorDeploymentIsolationAuthorityKeySetPublication.RootSignature(
                    exact.authorityId(), seal.keyId(), seal.algorithm(), seal.signedAt(),
                    seal.signature()));
        }
        signatures.sort(Comparator.comparing(
                MirrorDeploymentIsolationAuthorityKeySetPublication.RootSignature::authorityId)
                .thenComparing(
                        MirrorDeploymentIsolationAuthorityKeySetPublication.RootSignature::keyId));
        String publicationFingerprint = publicationFingerprint(
                materialFingerprint, material, signatures);
        return new MirrorDeploymentIsolationAuthorityKeySetPublication("",
                publicationFingerprint, materialFingerprint, material, signatures);
    }

    /**
     * Recomputes both canonical fingerprints without consulting any caller-selected trust input.
     *
     * <p>This check proves content addressing only. It does not establish scope authority,
     * bootstrap-root trust, validity time, or monotonic freshness; callers admitting a
     * publication must still invoke {@link #verify} with local binding, roots, and floor.</p>
     *
     * @param publication untrusted decoded publication
     * @return true only when material and complete-publication fingerprints are exact
     */
    public boolean canonicalFingerprintVerified(
            MirrorDeploymentIsolationAuthorityKeySetPublication publication) {
        if (publication == null) {
            return false;
        }
        try {
            return publication.materialFingerprint().equals(
                    materialFingerprint(publication.material()))
                    && publication.publicationFingerprint().equals(publicationFingerprint(
                    publication.materialFingerprint(), publication.material(),
                    publication.signatures()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Verifies one publication before exposing any advertised isolation-attestation key.
     *
     * @param publication untrusted decoded publication
     * @param binding exact locally configured scope, deployment, and policy binding
     * @param roots locally pinned bootstrap-root public keys
     * @param floor last durably accepted generation, or {@code null} only for bootstrap
     * @param verificationTime current trusted time
     * @return bounded result containing public attestation keys only after full verification
     */
    public VerificationResult verify(
            MirrorDeploymentIsolationAuthorityKeySetPublication publication,
            ExpectedBinding binding,
            List<RootVerificationKey> roots,
            TrustedFloor floor,
            Instant verificationTime) {
        Coordinates coordinates = Coordinates.from(publication);
        if (publication == null) {
            return result(Outcome.INVALID, "PUBLICATION_MISSING", coordinates, List.of(), "");
        }
        try {
            if (!publication.materialFingerprint().equals(
                    materialFingerprint(publication.material()))
                    || !publication.publicationFingerprint().equals(publicationFingerprint(
                    publication.materialFingerprint(), publication.material(),
                    publication.signatures()))) {
                return result(Outcome.INVALID, "PUBLICATION_FINGERPRINT_INVALID",
                        coordinates, List.of(), "");
            }
        } catch (RuntimeException invalid) {
            return result(Outcome.INVALID, "PUBLICATION_MATERIAL_INVALID",
                    coordinates, List.of(), "");
        }
        if (binding == null) {
            return result(Outcome.POLICY_REJECTED, "EXPECTED_BINDING_UNAVAILABLE",
                    coordinates, List.of(), "");
        }
        if (!binding.matches(publication.material())) {
            return result(Outcome.IDENTITY_MISMATCH, "PUBLICATION_BINDING_MISMATCH",
                    coordinates, List.of(), "");
        }
        if (verificationTime == null
                || verificationTime.isBefore(publication.material().notBefore())
                || !verificationTime.isBefore(publication.material().expiresAt())) {
            return result(Outcome.WINDOW_REJECTED, "PUBLICATION_OUTSIDE_VALIDITY_WINDOW",
                    coordinates, List.of(), "");
        }
        String chainFailure = chainFailure(publication, floor);
        if (!chainFailure.isBlank()) {
            return result(Outcome.CHAIN_REJECTED, chainFailure, coordinates, List.of(), "");
        }
        if (roots == null || roots.isEmpty()) {
            return result(Outcome.ROOTS_UNAVAILABLE, "BOOTSTRAP_ROOTS_UNAVAILABLE",
                    coordinates, List.of(), "");
        }
        Map<RootCoordinate, RootVerificationKey> rootsByCoordinate = new HashMap<>();
        Set<String> rootPublicKeys = new HashSet<>();
        for (RootVerificationKey root : roots) {
            RootVerificationKey exact = Objects.requireNonNull(root, "rootVerificationKey");
            RootVerificationKey duplicate = rootsByCoordinate.put(
                    new RootCoordinate(exact.authorityId(), exact.keyId()), exact);
            if (duplicate != null || !rootPublicKeys.add(
                    exact.algorithm() + '\0' + exact.encodedPublicKey())) {
                return result(Outcome.POLICY_REJECTED, "BOOTSTRAP_ROOTS_AMBIGUOUS",
                        coordinates, List.of(), "");
            }
        }
        for (MirrorDeploymentIsolationAuthorityKeySetPublication.RootSignature signature
                : publication.signatures()) {
            RootVerificationKey root = rootsByCoordinate.get(
                    new RootCoordinate(signature.authorityId(), signature.keyId()));
            if (root == null) {
                return result(Outcome.POLICY_REJECTED, "BOOTSTRAP_ROOT_UNKNOWN",
                        coordinates, List.of(), "");
            }
            if (!root.verificationAllowed()
                    || !root.algorithm().equals(signature.algorithm())
                    || signature.signedAt().isBefore(root.notBefore())
                    || !signature.signedAt().isBefore(root.notAfter())) {
                return result(Outcome.POLICY_REJECTED, "BOOTSTRAP_ROOT_POLICY_REJECTED",
                        coordinates, List.of(), "");
            }
            try {
                if (!verifySignature(publication.materialFingerprint(), signature.signature(),
                        root.encodedPublicKey())) {
                    return result(Outcome.INVALID, "BOOTSTRAP_ROOT_SIGNATURE_INVALID",
                            coordinates, List.of(), "");
                }
            } catch (RuntimeException invalid) {
                return result(Outcome.INVALID, "BOOTSTRAP_ROOT_SIGNATURE_MATERIAL_INVALID",
                        coordinates, List.of(), "");
            }
        }
        if (publication.signatures().size() < binding.rootThreshold()) {
            return result(Outcome.POLICY_REJECTED, "BOOTSTRAP_ROOT_THRESHOLD_NOT_MET",
                    coordinates, List.of(), "");
        }
        return result(Outcome.VERIFIED, "VERIFIED", coordinates,
                publication.material().authorityKeys(), publication.material().attestationIssuer());
    }

    private String chainFailure(
            MirrorDeploymentIsolationAuthorityKeySetPublication publication,
            TrustedFloor floor) {
        long generation = publication.material().generation();
        if (floor == null) {
            return generation == 1 ? "" : "PUBLICATION_BOOTSTRAP_GENERATION_INVALID";
        }
        if (!floor.keySetId().equals(publication.material().keySetId())) {
            return "PUBLICATION_FLOOR_KEY_SET_MISMATCH";
        }
        if (generation == floor.generation()) {
            return publication.publicationFingerprint().equals(floor.publicationFingerprint())
                    ? "" : "PUBLICATION_GENERATION_FORK";
        }
        if (generation < floor.generation()) {
            return "PUBLICATION_GENERATION_ROLLBACK";
        }
        if (generation > floor.generation() + 1) {
            return "PUBLICATION_GENERATION_GAP";
        }
        return publication.material().previousPublicationFingerprint()
                .equals(floor.publicationFingerprint())
                ? "" : "PUBLICATION_PREDECESSOR_MISMATCH";
    }

    private boolean verifySignature(
            String materialFingerprint, String encodedSignature, String encodedPublicKey) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(encodedPublicKey))));
            verifier.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(encodedSignature));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("bootstrap-root signature is invalid", invalid);
        }
    }

    private String materialFingerprint(
            MirrorDeploymentIsolationAuthorityKeySetPublication.Material material) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper,
                new SignatureMaterial(SIGNATURE_DOMAIN,
                        MirrorDeploymentIsolationAuthorityKeySetPublication.SCHEMA_VERSION,
                        material), MAXIMUM_MATERIAL_BYTES);
    }

    private String publicationFingerprint(
            String materialFingerprint,
            MirrorDeploymentIsolationAuthorityKeySetPublication.Material material,
            List<MirrorDeploymentIsolationAuthorityKeySetPublication.RootSignature> signatures) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper,
                new PublicationMaterial(
                        MirrorDeploymentIsolationAuthorityKeySetPublication.SCHEMA_VERSION,
                        "", materialFingerprint, material, signatures),
                MAXIMUM_PUBLICATION_BYTES);
    }

    /** Root-key lifecycle policy. */
    public enum RootKeyState {
        /** Key may sign and verify current publications. */
        ACTIVE,
        /** Key may verify already signed publications but must not sign new ones. */
        RETIRED,
        /** Key must not verify any publication. */
        REVOKED
    }

    /**
     * One locally pinned bootstrap-root public key.
     *
     * @param authorityId independent root-authority identity
     * @param keyId exact root key identity
     * @param algorithm fixed signature algorithm
     * @param encodedPublicKey canonical base64 X.509 SubjectPublicKeyInfo bytes
     * @param notBefore inclusive root-signing validity bound
     * @param notAfter exclusive root-signing validity bound
     * @param state current local root lifecycle state
     */
    public record RootVerificationKey(
            String authorityId,
            String keyId,
            String algorithm,
            String encodedPublicKey,
            Instant notBefore,
            Instant notAfter,
            RootKeyState state
    ) {
        /** Validates local bootstrap-root policy material. */
        public RootVerificationKey {
            authorityId = identifier(authorityId, "root.authorityId");
            keyId = identifier(keyId, "root.keyId");
            algorithm = required(algorithm, "root.algorithm");
            encodedPublicKey = canonicalBase64(encodedPublicKey, "root.encodedPublicKey");
            notBefore = Objects.requireNonNull(notBefore, "root.notBefore");
            notAfter = Objects.requireNonNull(notAfter, "root.notAfter");
            state = Objects.requireNonNull(state, "root.state");
            if (!"Ed25519".equals(algorithm) || !notAfter.isAfter(notBefore)) {
                throw new IllegalArgumentException("bootstrap-root key policy is invalid");
            }
        }

        /**
         * Reports whether local policy permits historical verification.
         *
         * @return true for active or retired roots
         */
        public boolean verificationAllowed() {
            return state == RootKeyState.ACTIVE || state == RootKeyState.RETIRED;
        }
    }

    /**
     * Exact local policy binding that an untrusted publication cannot choose for itself.
     *
     * @param scope complete expected enterprise scope
     * @param deployment exact expected mirror deployment generation
     * @param attestationIssuer expected isolation-attestation issuer
     * @param keySetId expected stable key-set stream
     * @param rootTrustDomain expected bootstrap-root trust domain
     * @param rootThreshold exact locally required M-of-N threshold
     * @param acceptedPolicyFingerprints canonical non-empty allowlist of policy generations
     */
    public record ExpectedBinding(
            CapabilitySnapshot.Scope scope,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            String attestationIssuer,
            String keySetId,
            String rootTrustDomain,
            int rootThreshold,
            List<String> acceptedPolicyFingerprints
    ) {
        /** Validates local binding policy and deterministic policy-fingerprint order. */
        public ExpectedBinding {
            scope = Objects.requireNonNull(scope, "scope");
            deployment = Objects.requireNonNull(deployment, "deployment");
            attestationIssuer = identifier(attestationIssuer, "attestationIssuer");
            keySetId = identifier(keySetId, "keySetId");
            rootTrustDomain = identifier(rootTrustDomain, "rootTrustDomain");
            if (rootThreshold < 1
                    || rootThreshold
                    > MirrorDeploymentIsolationAuthorityKeySetPublication.MAXIMUM_ROOT_SIGNATURES) {
                throw new IllegalArgumentException("rootThreshold is outside protocol bounds");
            }
            acceptedPolicyFingerprints = canonicalFingerprints(acceptedPolicyFingerprints);
        }

        private boolean matches(
                MirrorDeploymentIsolationAuthorityKeySetPublication.Material material) {
            return scope.equals(material.scope()) && deployment.equals(material.deployment())
                    && attestationIssuer.equals(material.attestationIssuer())
                    && keySetId.equals(material.keySetId())
                    && rootTrustDomain.equals(material.rootTrustDomain())
                    && rootThreshold == material.rootThreshold()
                    && acceptedPolicyFingerprints.contains(material.policyFingerprint());
        }
    }

    /**
     * Last durably accepted monotonic publication coordinate.
     *
     * @param keySetId stable key-set stream identity
     * @param generation positive accepted generation
     * @param publicationFingerprint exact accepted publication fingerprint
     */
    public record TrustedFloor(
            String keySetId,
            long generation,
            String publicationFingerprint
    ) {
        /** Validates one exact local anti-rollback floor. */
        public TrustedFloor {
            keySetId = identifier(keySetId, "floor.keySetId");
            if (generation < 1) {
                throw new IllegalArgumentException("floor generation must be positive");
            }
            publicationFingerprint = fingerprint(publicationFingerprint,
                    "floor.publicationFingerprint");
        }
    }

    /**
     * Named external root signer used only by publication tooling.
     *
     * @param authorityId independent bootstrap-root authority identity
     * @param signer external signer implementation
     */
    public record NamedRootSigner(String authorityId, VisualEvidenceSigner signer) {
        /** Validates signer identity and presence. */
        public NamedRootSigner {
            authorityId = identifier(authorityId, "authorityId");
            signer = Objects.requireNonNull(signer, "signer");
        }
    }

    /** Bounded publication-verification outcome. */
    public enum Outcome {
        /** All structure, identity, chain, time, policy, threshold, and signature checks passed. */
        VERIFIED,
        /** Canonical material, fingerprint, or a root signature is invalid. */
        INVALID,
        /** Locally pinned bootstrap roots are unavailable. */
        ROOTS_UNAVAILABLE,
        /** Local trust, threshold, or policy requirements rejected the publication. */
        POLICY_REJECTED,
        /** Enterprise scope, deployment, issuer, key-set, or trust-domain binding drifted. */
        IDENTITY_MISMATCH,
        /** Publication is not active at the trusted verification time. */
        WINDOW_REJECTED,
        /** Monotonic generation, predecessor, or durable floor checks failed. */
        CHAIN_REJECTED
    }

    /**
     * Payload-free verification coordinates plus verified public authority keys.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param keySetId publication stream identity, or blank when unavailable
     * @param generation publication generation, or zero when unavailable
     * @param publicationFingerprint publication fingerprint, or blank when unavailable
     * @param authorityKeys verified public keys; empty on every failure
     * @param attestationIssuer verified issuer; blank on every failure
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String keySetId,
            long generation,
            String publicationFingerprint,
            List<MirrorDeploymentIsolationAuthorityKeySetPublication.AuthorityKey> authorityKeys,
            String attestationIssuer
    ) {
        /** Validates a bounded result and prevents failed verification from exposing keys. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = required(reasonCode, "reasonCode");
            keySetId = normalized(keySetId);
            publicationFingerprint = normalized(publicationFingerprint);
            authorityKeys = authorityKeys == null ? List.of() : List.copyOf(authorityKeys);
            attestationIssuer = normalized(attestationIssuer);
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")
                    || outcome != Outcome.VERIFIED
                    && (!authorityKeys.isEmpty() || !attestationIssuer.isBlank())) {
                throw new IllegalArgumentException("authority key-set verification result is invalid");
            }
        }

        /**
         * Reports whether every verification step passed.
         *
         * @return true only for a fully verified publication
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }

        /**
         * Resolves one advertised key into the existing isolation-attestation verifier policy.
         *
         * @param keyId exact advertised key identity
         * @return verified key policy, or empty when verification failed or the key is absent
         */
        public Optional<MirrorDeploymentIsolationAttestationIntegrity.AuthorityKey>
        attestationKey(String keyId) {
            if (!verified()) {
                return Optional.empty();
            }
            return authorityKeys.stream().filter(key -> key.keyId().equals(keyId)).findFirst()
                    .map(key -> new MirrorDeploymentIsolationAttestationIntegrity.AuthorityKey(
                            key.keyId(), key.algorithm(), key.encodedPublicKey(),
                            attestationIssuer, key.notBefore(), key.notAfter(),
                            MirrorDeploymentIsolationAttestationIntegrity.KeyState.valueOf(
                                    key.state().name())));
        }
    }

    private static VerificationResult result(
            Outcome outcome, String reason, Coordinates coordinates,
            List<MirrorDeploymentIsolationAuthorityKeySetPublication.AuthorityKey> keys,
            String issuer) {
        return new VerificationResult(outcome, reason, coordinates.keySetId(),
                coordinates.generation(), coordinates.publicationFingerprint(), keys, issuer);
    }

    private static List<String> canonicalFingerprints(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > 16) {
            throw new IllegalArgumentException("acceptedPolicyFingerprints are outside bounds");
        }
        List<String> copy = values.stream()
                .map(value -> fingerprint(value, "acceptedPolicyFingerprint")).toList();
        List<String> sorted = copy.stream().sorted().toList();
        if (!copy.equals(sorted) || new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(
                    "acceptedPolicyFingerprints must be canonical and unique");
        }
        return List.copyOf(copy);
    }

    private static String canonicalBase64(String value, String field) {
        String exact = required(value, field);
        try {
            byte[] decoded = Base64.getDecoder().decode(exact);
            if (decoded.length == 0 || !exact.equals(Base64.getEncoder().encodeToString(decoded))) {
                throw new IllegalArgumentException(field + " must be canonical base64");
            }
            return exact;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " must be canonical base64", invalid);
        }
    }

    private static String fingerprint(String value, String field) {
        String exact = required(value, field);
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = required(value, field);
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return exact;
    }

    private static String required(String value, String field) {
        String exact = normalized(value);
        if (exact.isEmpty() || exact.length() > 512) {
            throw new IllegalArgumentException(field + " must be bounded and non-blank");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record SignatureMaterial(
            String domain,
            String schemaVersion,
            MirrorDeploymentIsolationAuthorityKeySetPublication.Material material
    ) {
    }

    private record PublicationMaterial(
            String schemaVersion,
            String publicationFingerprint,
            String materialFingerprint,
            MirrorDeploymentIsolationAuthorityKeySetPublication.Material material,
            List<MirrorDeploymentIsolationAuthorityKeySetPublication.RootSignature> signatures
    ) {
    }

    private record RootCoordinate(String authorityId, String keyId) {
    }

    private record Coordinates(
            String keySetId,
            long generation,
            String publicationFingerprint
    ) {
        private static Coordinates from(
                MirrorDeploymentIsolationAuthorityKeySetPublication value) {
            return value == null ? new Coordinates("", 0, "")
                    : new Coordinates(value.material().keySetId(),
                    value.material().generation(), value.publicationFingerprint());
        }
    }
}
