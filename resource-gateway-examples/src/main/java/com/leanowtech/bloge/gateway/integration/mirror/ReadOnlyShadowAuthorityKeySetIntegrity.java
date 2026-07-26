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
import java.util.Set;

/**
 * Canonical threshold sealing and verification for Shadow authority key-set publications.
 *
 * <p>Verification combines exact local binding, independently pinned bootstrap roots, current
 * root lifecycle, short publication freshness, and a durable monotonic floor. Every supplied
 * signature must be recognized and valid; unknown extra signatures fail closed.</p>
 */
public final class ReadOnlyShadowAuthorityKeySetIntegrity {
    /** Root-signature domain separator. */
    public static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_READ_ONLY_SHADOW_AUTHORITY_KEY_SET_V1";
    /** Maximum canonical signed material size. */
    public static final int MAXIMUM_MATERIAL_BYTES = 1024 * 1024;
    /** Maximum canonical complete publication size. */
    public static final int MAXIMUM_PUBLICATION_BYTES = 2 * 1024 * 1024;

    private final ObjectMapper mapper;

    /**
     * Creates the canonical key-set integrity boundary.
     *
     * @param mapper canonical protocol mapper
     */
    public ReadOnlyShadowAuthorityKeySetIntegrity(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Produces a threshold-signed publication using independent bootstrap-root signers.
     *
     * @param material immutable authority key-set material
     * @param signers distinct named bootstrap-root signers
     * @return content-addressed threshold-signed publication
     */
    public ReadOnlyShadowAuthorityKeySetPublication seal(
            ReadOnlyShadowAuthorityKeySetPublication.Material material,
            List<NamedRootSigner> signers) {
        Objects.requireNonNull(material, "material");
        if (signers == null || signers.size() < material.rootThreshold()
                || signers.size()
                > ReadOnlyShadowAuthorityKeySetPublication.MAXIMUM_ROOT_SIGNATURES) {
            throw new IllegalArgumentException("bootstrap-root signers do not satisfy threshold");
        }
        String materialFingerprint = materialFingerprint(material);
        List<ReadOnlyShadowAuthorityKeySetPublication.RootSignature> signatures =
                new ArrayList<>();
        Set<String> authorities = new HashSet<>();
        Set<String> cryptographicKeys = new HashSet<>();
        for (NamedRootSigner named : signers) {
            NamedRootSigner exact = Objects.requireNonNull(named, "namedRootSigner");
            if (!authorities.add(exact.authorityId()) || !exact.signer().available()) {
                throw new IllegalArgumentException(
                        "bootstrap-root signers must be available and distinct");
            }
            VisualRunEvidenceSeal seal = exact.signer().seal(materialFingerprint);
            if (!cryptographicKeys.add(seal.algorithm() + '\0' + seal.keyId())) {
                throw new IllegalArgumentException(
                        "bootstrap-root signers must use distinct key material");
            }
            signatures.add(new ReadOnlyShadowAuthorityKeySetPublication.RootSignature(
                    exact.authorityId(), seal.keyId(), seal.algorithm(), seal.signedAt(),
                    seal.signature()));
        }
        signatures.sort(Comparator
                .comparing(ReadOnlyShadowAuthorityKeySetPublication.RootSignature::authorityId)
                .thenComparing(ReadOnlyShadowAuthorityKeySetPublication.RootSignature::keyId));
        String publicationFingerprint = publicationFingerprint(
                materialFingerprint, material, signatures);
        return new ReadOnlyShadowAuthorityKeySetPublication(
                "", publicationFingerprint, materialFingerprint, material, signatures);
    }

    /**
     * Recomputes canonical fingerprints without establishing authority or freshness.
     *
     * @param publication untrusted decoded publication
     * @return true only when both content addresses are exact
     */
    public boolean canonicalFingerprintVerified(
            ReadOnlyShadowAuthorityKeySetPublication publication) {
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
     * Verifies one untrusted key-set publication against local policy and a durable floor.
     *
     * @param publication untrusted decoded publication
     * @param binding exact locally governed stream identity and policy
     * @param roots independently pinned bootstrap-root keys
     * @param floor last durably accepted floor, or null only before genesis
     * @param verificationTime trusted current time
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            ReadOnlyShadowAuthorityKeySetPublication publication,
            ExpectedBinding binding,
            List<RootVerificationKey> roots,
            TrustedFloor floor,
            Instant verificationTime) {
        Coordinates coordinates = Coordinates.from(publication);
        if (!canonicalFingerprintVerified(publication)) {
            return result(Outcome.INVALID, "PUBLICATION_FINGERPRINT_INVALID", coordinates);
        }
        if (binding == null) {
            return result(Outcome.POLICY_REJECTED, "EXPECTED_BINDING_UNAVAILABLE", coordinates);
        }
        if (!binding.matches(publication.material())) {
            return result(Outcome.IDENTITY_MISMATCH, "PUBLICATION_BINDING_MISMATCH", coordinates);
        }
        if (verificationTime == null
                || verificationTime.isBefore(publication.material().notBefore())
                || !verificationTime.isBefore(publication.material().expiresAt())) {
            return result(Outcome.WINDOW_REJECTED,
                    "PUBLICATION_OUTSIDE_VALIDITY_WINDOW", coordinates);
        }
        String chainFailure = chainFailure(publication, floor);
        if (!chainFailure.isBlank()) {
            return result(Outcome.CHAIN_REJECTED, chainFailure, coordinates);
        }
        if (roots == null || roots.isEmpty()) {
            return result(Outcome.ROOTS_UNAVAILABLE, "BOOTSTRAP_ROOTS_UNAVAILABLE", coordinates);
        }
        Map<RootCoordinate, RootVerificationKey> byCoordinate = new HashMap<>();
        Set<String> publicKeys = new HashSet<>();
        for (RootVerificationKey root : roots) {
            RootVerificationKey exact = Objects.requireNonNull(root, "rootVerificationKey");
            if (byCoordinate.put(
                    new RootCoordinate(exact.authorityId(), exact.keyId()), exact) != null
                    || !publicKeys.add(exact.algorithm() + '\0' + exact.encodedPublicKey())) {
                return result(Outcome.POLICY_REJECTED,
                        "BOOTSTRAP_ROOTS_AMBIGUOUS", coordinates);
            }
        }
        for (ReadOnlyShadowAuthorityKeySetPublication.RootSignature signed
                : publication.signatures()) {
            RootVerificationKey root = byCoordinate.get(
                    new RootCoordinate(signed.authorityId(), signed.keyId()));
            if (root == null) {
                return result(Outcome.POLICY_REJECTED,
                        "BOOTSTRAP_ROOT_UNKNOWN", coordinates);
            }
            if (!root.verificationAllowed()
                    || !root.algorithm().equals(signed.algorithm())
                    || signed.signedAt().isBefore(root.notBefore())
                    || !signed.signedAt().isBefore(root.notAfter())) {
                return result(Outcome.POLICY_REJECTED,
                        "BOOTSTRAP_ROOT_POLICY_REJECTED", coordinates);
            }
            try {
                if (!verifySignature(publication.materialFingerprint(),
                        signed.signature(), root.encodedPublicKey())) {
                    return result(Outcome.INVALID,
                            "BOOTSTRAP_ROOT_SIGNATURE_INVALID", coordinates);
                }
            } catch (RuntimeException invalid) {
                return result(Outcome.INVALID,
                        "BOOTSTRAP_ROOT_SIGNATURE_MATERIAL_INVALID", coordinates);
            }
        }
        if (publication.signatures().size() < binding.rootThreshold()) {
            return result(Outcome.POLICY_REJECTED,
                    "BOOTSTRAP_ROOT_THRESHOLD_NOT_MET", coordinates);
        }
        return result(Outcome.VERIFIED, "VERIFIED", coordinates);
    }

    private String chainFailure(
            ReadOnlyShadowAuthorityKeySetPublication publication,
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

    private String materialFingerprint(
            ReadOnlyShadowAuthorityKeySetPublication.Material material) {
        return VisualBundleFingerprint.fromCanonicalValue(
                mapper,
                new SignedMaterial(
                        SIGNATURE_DOMAIN,
                        ReadOnlyShadowAuthorityKeySetPublication.SCHEMA_VERSION,
                        material),
                MAXIMUM_MATERIAL_BYTES);
    }

    private String publicationFingerprint(
            String materialFingerprint,
            ReadOnlyShadowAuthorityKeySetPublication.Material material,
            List<ReadOnlyShadowAuthorityKeySetPublication.RootSignature> signatures) {
        return VisualBundleFingerprint.fromCanonicalValue(
                mapper,
                new PublicationMaterial(
                        ReadOnlyShadowAuthorityKeySetPublication.SCHEMA_VERSION,
                        "",
                        materialFingerprint,
                        material,
                        signatures),
                MAXIMUM_PUBLICATION_BYTES);
    }

    private static boolean verifySignature(
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

    /** One named external bootstrap-root signer. */
    public record NamedRootSigner(String authorityId, VisualEvidenceSigner signer) {
        /** Validates named signer identity. */
        public NamedRootSigner {
            authorityId = ReadOnlyShadowAuthoritySeal.identifier(authorityId, "authorityId");
            signer = Objects.requireNonNull(signer, "signer");
        }
    }

    /**
     * Exact local binding for one authority key-set stream.
     *
     * @param scope complete enterprise scope
     * @param publicationKind exact authority protocol
     * @param issuer exact delegated authority
     * @param keySetId stable key-set stream
     * @param rootTrustDomain independently configured root trust domain
     * @param rootThreshold locally required root threshold
     * @param acceptedPolicyFingerprints non-empty policy-generation allowlist
     */
    public record ExpectedBinding(
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowAuthorityIntegrity.PublicationKind publicationKind,
            String issuer,
            String keySetId,
            String rootTrustDomain,
            int rootThreshold,
            Set<String> acceptedPolicyFingerprints
    ) {
        /** Validates detached local policy input. */
        public ExpectedBinding {
            scope = ReadOnlyShadowAuthoritySeal.scope(scope, "scope");
            publicationKind = Objects.requireNonNull(publicationKind, "publicationKind");
            issuer = ReadOnlyShadowAuthoritySeal.identifier(issuer, "issuer");
            keySetId = ReadOnlyShadowAuthoritySeal.identifier(keySetId, "keySetId");
            rootTrustDomain = ReadOnlyShadowAuthoritySeal.identifier(
                    rootTrustDomain, "rootTrustDomain");
            if (rootThreshold < 1
                    || rootThreshold
                    > ReadOnlyShadowAuthorityKeySetPublication.MAXIMUM_ROOT_SIGNATURES) {
                throw new IllegalArgumentException("rootThreshold is outside protocol bounds");
            }
            acceptedPolicyFingerprints = acceptedPolicyFingerprints == null
                    ? Set.of() : Set.copyOf(acceptedPolicyFingerprints);
            if (acceptedPolicyFingerprints.isEmpty()
                    || acceptedPolicyFingerprints.stream().anyMatch(
                    value -> value == null || !value.matches("sha256:[a-f0-9]{64}"))) {
                throw new IllegalArgumentException(
                        "acceptedPolicyFingerprints must be a non-empty fingerprint set");
            }
        }

        /** Reports exact binding equality without accepting request-selected policy. */
        public boolean matches(ReadOnlyShadowAuthorityKeySetPublication.Material material) {
            return material != null
                    && scope.equals(material.scope())
                    && publicationKind == material.publicationKind()
                    && issuer.equals(material.issuer())
                    && keySetId.equals(material.keySetId())
                    && rootTrustDomain.equals(material.rootTrustDomain())
                    && rootThreshold == material.rootThreshold()
                    && acceptedPolicyFingerprints.contains(material.policyFingerprint());
        }
    }

    /**
     * Independently pinned bootstrap-root key.
     *
     * @param authorityId root authority identity
     * @param keyId exact root key identity
     * @param algorithm fixed signature algorithm
     * @param encodedPublicKey canonical base64 public key
     * @param notBefore inclusive signing-time bound
     * @param notAfter exclusive signing-time bound
     * @param verificationAllowed false after root revocation
     */
    public record RootVerificationKey(
            String authorityId,
            String keyId,
            String algorithm,
            String encodedPublicKey,
            Instant notBefore,
            Instant notAfter,
            boolean verificationAllowed
    ) {
        /** Validates pinned public key policy. */
        public RootVerificationKey {
            authorityId = ReadOnlyShadowAuthoritySeal.identifier(authorityId, "authorityId");
            keyId = ReadOnlyShadowAuthoritySeal.identifier(keyId, "keyId");
            algorithm = ReadOnlyShadowAuthoritySeal.required(algorithm, "algorithm", 32);
            encodedPublicKey = ReadOnlyShadowAuthoritySeal.canonicalBase64(
                    encodedPublicKey, "encodedPublicKey", 16_384);
            notBefore = ReadOnlyShadowAuthoritySeal.time(notBefore, "notBefore");
            notAfter = ReadOnlyShadowAuthoritySeal.time(notAfter, "notAfter");
            if (!"Ed25519".equals(algorithm) || !notAfter.isAfter(notBefore)) {
                throw new IllegalArgumentException("bootstrap-root key is invalid");
            }
        }
    }

    /**
     * Durable anti-rollback floor.
     *
     * @param keySetId stable stream identity
     * @param generation current accepted generation
     * @param publicationFingerprint current accepted publication fingerprint
     */
    public record TrustedFloor(
            String keySetId, long generation, String publicationFingerprint) {
        /** Validates persisted monotonic floor coordinates. */
        public TrustedFloor {
            keySetId = ReadOnlyShadowAuthoritySeal.identifier(keySetId, "keySetId");
            if (generation < 1 || publicationFingerprint == null
                    || !publicationFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException("trusted authority key-set floor is invalid");
            }
        }
    }

    /** Closed verification outcome. */
    public enum Outcome {
        /** Canonical content, roots, binding, freshness, and chain all passed. */
        VERIFIED,
        /** Canonical material or a cryptographic signature is invalid. */
        INVALID,
        /** Bootstrap roots are unavailable. */
        ROOTS_UNAVAILABLE,
        /** Local trust policy rejected roots, threshold, or policy generation. */
        POLICY_REJECTED,
        /** Scope, authority kind, issuer, or stream identity drifted. */
        IDENTITY_MISMATCH,
        /** Publication is not current at the trusted verification time. */
        WINDOW_REJECTED,
        /** Publication conflicts with the durable monotonic floor. */
        CHAIN_REJECTED
    }

    /**
     * Payload-free verification result.
     *
     * @param outcome closed verification outcome
     * @param reasonCode stable machine-readable reason
     * @param keySetId stream identity when available
     * @param generation publication generation when available
     * @param publicationFingerprint content address when available
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String keySetId,
            long generation,
            String publicationFingerprint
    ) {
        /** Validates bounded result coordinates. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = ReadOnlyShadowAuthoritySeal.required(
                    reasonCode, "reasonCode", 255);
            keySetId = ReadOnlyShadowAuthoritySeal.normalized(keySetId);
            publicationFingerprint =
                    ReadOnlyShadowAuthoritySeal.normalized(publicationFingerprint);
            if (generation < 0 || !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("key-set verification result is invalid");
            }
        }

        /** @return true only for a fully trusted publication */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    private static VerificationResult result(
            Outcome outcome, String reason, Coordinates coordinates) {
        return new VerificationResult(outcome, reason, coordinates.keySetId(),
                coordinates.generation(), coordinates.publicationFingerprint());
    }

    private record RootCoordinate(String authorityId, String keyId) {
    }

    private record Coordinates(
            String keySetId, long generation, String publicationFingerprint) {
        private static Coordinates from(ReadOnlyShadowAuthorityKeySetPublication publication) {
            if (publication == null) {
                return new Coordinates("", 0, "");
            }
            return new Coordinates(publication.material().keySetId(),
                    publication.material().generation(), publication.publicationFingerprint());
        }
    }

    private record SignedMaterial(
            String domain,
            String schemaVersion,
            ReadOnlyShadowAuthorityKeySetPublication.Material material) {
    }

    private record PublicationMaterial(
            String schemaVersion,
            String publicationFingerprint,
            String materialFingerprint,
            ReadOnlyShadowAuthorityKeySetPublication.Material material,
            List<ReadOnlyShadowAuthorityKeySetPublication.RootSignature> signatures) {
    }
}
