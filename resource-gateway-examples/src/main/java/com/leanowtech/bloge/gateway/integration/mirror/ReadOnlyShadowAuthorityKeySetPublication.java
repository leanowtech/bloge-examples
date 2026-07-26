package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Short-lived, root-signed distribution snapshot for read-only Shadow authority keys.
 *
 * <p>Each publication stream is bound to one complete enterprise scope, one authority protocol,
 * and one issuer. Generations are append-only and retain every previously advertised key so a
 * revoked identity cannot disappear and later be reintroduced as active. The publication carries
 * public verification material only; signing keys never cross this boundary.</p>
 *
 * @param schemaVersion key-set publication protocol version
 * @param publicationFingerprint canonical fingerprint of the complete publication
 * @param materialFingerprint canonical fingerprint signed by every bootstrap root
 * @param material immutable stream identity, validity, policy, and key material
 * @param signatures canonically ordered signatures from independent bootstrap roots
 */
public record ReadOnlyShadowAuthorityKeySetPublication(
        String schemaVersion,
        String publicationFingerprint,
        String materialFingerprint,
        Material material,
        List<RootSignature> signatures
) {
    /** Current key-set publication protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowAuthorityKeySetPublication.v1";
    /** Artifact kind used by trusted-distribution envelopes. */
    public static final String ARTIFACT_KIND = "READ_ONLY_SHADOW_AUTHORITY_KEY_SET";
    /** Maximum lifetime of one current online key-set publication. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofHours(24);
    /** Maximum issuance-to-activation delay. */
    public static final Duration MAXIMUM_ACTIVATION_DELAY = Duration.ofMinutes(5);
    /** Maximum retained keys in one authority stream. */
    public static final int MAXIMUM_KEYS = 128;
    /** Maximum independent bootstrap-root signatures. */
    public static final int MAXIMUM_ROOT_SIGNATURES = 16;

    /** Validates deterministic envelope syntax before trust evaluation. */
    public ReadOnlyShadowAuthorityKeySetPublication {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : required(schemaVersion, "schemaVersion", 128);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported read-only Shadow authority key-set schemaVersion");
        }
        publicationFingerprint = fingerprint(
                publicationFingerprint, "publicationFingerprint");
        materialFingerprint = fingerprint(materialFingerprint, "materialFingerprint");
        material = Objects.requireNonNull(material, "material");
        signatures = orderedSignatures(signatures, material);
    }

    /**
     * Scope and authority-kind bound key-set generation.
     *
     * @param keySetId stable stream identity
     * @param generation positive monotonic stream generation and revocation cursor
     * @param previousPublicationFingerprint blank only for generation one
     * @param scope complete governed enterprise scope
     * @param publicationKind exact authority protocol delegated to every key
     * @param issuer exact authority identity delegated to every key
     * @param rootTrustDomain locally pinned bootstrap-root trust domain
     * @param rootThreshold required number of distinct root authorities
     * @param policyFingerprint exact distribution-policy generation
     * @param issuedAt issuance request time
     * @param notBefore inclusive activation time
     * @param expiresAt exclusive publication freshness bound
     * @param keys canonically ordered retained authority keys
     */
    public record Material(
            String keySetId,
            long generation,
            String previousPublicationFingerprint,
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowAuthorityIntegrity.PublicationKind publicationKind,
            String issuer,
            String rootTrustDomain,
            int rootThreshold,
            String policyFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt,
            List<AuthorityKey> keys
    ) {
        /** Enforces exact stream identity, bounded freshness, and canonical key ordering. */
        public Material {
            keySetId = identifier(keySetId, "keySetId");
            if (generation < 1) {
                throw new IllegalArgumentException("authority key-set generation must be positive");
            }
            previousPublicationFingerprint = normalized(previousPublicationFingerprint);
            if (generation == 1 && !previousPublicationFingerprint.isBlank()
                    || generation > 1 && !isFingerprint(previousPublicationFingerprint)) {
                throw new IllegalArgumentException(
                        "authority key-set predecessor does not match generation semantics");
            }
            scope = ReadOnlyShadowAuthoritySeal.scope(scope, "scope");
            publicationKind = Objects.requireNonNull(publicationKind, "publicationKind");
            issuer = identifier(issuer, "issuer");
            rootTrustDomain = identifier(rootTrustDomain, "rootTrustDomain");
            if (rootThreshold < 1 || rootThreshold > MAXIMUM_ROOT_SIGNATURES) {
                throw new IllegalArgumentException("rootThreshold is outside protocol bounds");
            }
            policyFingerprint = fingerprint(policyFingerprint, "policyFingerprint");
            issuedAt = time(issuedAt, "issuedAt");
            notBefore = time(notBefore, "notBefore");
            expiresAt = time(expiresAt, "expiresAt");
            if (notBefore.isBefore(issuedAt)
                    || Duration.between(issuedAt, notBefore)
                    .compareTo(MAXIMUM_ACTIVATION_DELAY) > 0
                    || !expiresAt.isAfter(notBefore)
                    || Duration.between(issuedAt, expiresAt)
                    .compareTo(MAXIMUM_LIFETIME) > 0) {
                throw new IllegalArgumentException("authority key-set publication window is invalid");
            }
            keys = orderedKeys(keys);
        }
    }

    /**
     * One retained Shadow authority verification key.
     *
     * @param keyId stable identity that may never be rebound to different key material
     * @param algorithm fixed signature algorithm
     * @param encodedPublicKey canonical base64 X.509 SubjectPublicKeyInfo bytes
     * @param notBefore inclusive signing-time validity bound
     * @param notAfter exclusive signing-time validity bound
     * @param retiredAt exclusive retirement boundary; required only for RETIRED
     * @param state current lifecycle state
     */
    public record AuthorityKey(
            String keyId,
            String algorithm,
            String encodedPublicKey,
            Instant notBefore,
            Instant notAfter,
            Instant retiredAt,
            ReadOnlyShadowAuthorityIntegrity.KeyState state
    ) {
        /** Validates public key material and lifecycle state. */
        public AuthorityKey {
            keyId = identifier(keyId, "authorityKey.keyId");
            algorithm = required(algorithm, "authorityKey.algorithm", 32);
            encodedPublicKey = canonicalBase64(
                    encodedPublicKey, "authorityKey.encodedPublicKey", 16_384);
            notBefore = time(notBefore, "authorityKey.notBefore");
            notAfter = time(notAfter, "authorityKey.notAfter");
            state = Objects.requireNonNull(state, "authorityKey.state");
            if (state == ReadOnlyShadowAuthorityIntegrity.KeyState.RETIRED) {
                retiredAt = time(retiredAt, "authorityKey.retiredAt");
            } else if (retiredAt != null) {
                throw new IllegalArgumentException(
                        "retiredAt is valid only for a retired authority key");
            }
            if (!"Ed25519".equals(algorithm) || !notAfter.isAfter(notBefore)
                    || retiredAt != null && (retiredAt.isBefore(notBefore)
                    || retiredAt.isAfter(notAfter))) {
                throw new IllegalArgumentException("authority key lifecycle is invalid");
            }
        }

        /** Maps distributed public material into the runtime authority-verification contract. */
        public ReadOnlyShadowAuthorityIntegrity.AuthorityKey runtimeKey(Material owner) {
            Objects.requireNonNull(owner, "owner");
            return new ReadOnlyShadowAuthorityIntegrity.AuthorityKey(
                    keyId, algorithm, encodedPublicKey, owner.issuer(), owner.scope(),
                    owner.publicationKind(), notBefore, notAfter, retiredAt, state);
        }
    }

    /**
     * One detached bootstrap-root signature over the material fingerprint.
     *
     * @param authorityId independent bootstrap-root authority identity
     * @param keyId exact locally pinned root key
     * @param algorithm fixed signature algorithm
     * @param signedAt signature time inside the pre-activation collection window
     * @param signature canonical base64 detached signature
     */
    public record RootSignature(
            String authorityId,
            String keyId,
            String algorithm,
            Instant signedAt,
            String signature
    ) {
        /** Validates bounded root-signature syntax. */
        public RootSignature {
            authorityId = identifier(authorityId, "rootSignature.authorityId");
            keyId = identifier(keyId, "rootSignature.keyId");
            algorithm = required(algorithm, "rootSignature.algorithm", 32);
            signedAt = time(signedAt, "rootSignature.signedAt");
            signature = canonicalBase64(signature, "rootSignature.signature", 4_096);
            if (!"Ed25519".equals(algorithm)) {
                throw new IllegalArgumentException("root signatures require Ed25519");
            }
        }
    }

    private static List<AuthorityKey> orderedKeys(List<AuthorityKey> values) {
        if (values == null || values.isEmpty() || values.size() > MAXIMUM_KEYS) {
            throw new IllegalArgumentException("authority keys are outside protocol bounds");
        }
        List<AuthorityKey> copy = new ArrayList<>(values);
        copy.replaceAll(value -> Objects.requireNonNull(value, "authorityKey"));
        List<AuthorityKey> sorted = copy.stream()
                .sorted(Comparator.comparing(AuthorityKey::keyId)).toList();
        if (!copy.equals(sorted)) {
            throw new IllegalArgumentException("authority keys must use canonical order");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).keyId().equals(copy.get(index).keyId())) {
                throw new IllegalArgumentException("authority keys must use unique keyId values");
            }
        }
        return List.copyOf(copy);
    }

    private static List<RootSignature> orderedSignatures(
            List<RootSignature> values, Material material) {
        if (values == null || values.size() < material.rootThreshold()
                || values.size() > MAXIMUM_ROOT_SIGNATURES) {
            throw new IllegalArgumentException("root signatures do not satisfy protocol bounds");
        }
        List<RootSignature> copy = new ArrayList<>(values);
        copy.replaceAll(value -> Objects.requireNonNull(value, "rootSignature"));
        List<RootSignature> sorted = copy.stream()
                .sorted(Comparator.comparing(RootSignature::authorityId)
                        .thenComparing(RootSignature::keyId)).toList();
        if (!copy.equals(sorted)) {
            throw new IllegalArgumentException("root signatures must use canonical order");
        }
        for (int index = 0; index < copy.size(); index++) {
            RootSignature signature = copy.get(index);
            if (signature.signedAt().isBefore(material.issuedAt())
                    || signature.signedAt().isAfter(material.notBefore())
                    || index > 0 && copy.get(index - 1).authorityId()
                    .equals(signature.authorityId())) {
                throw new IllegalArgumentException("root signatures violate authority policy");
            }
        }
        return List.copyOf(copy);
    }

    private static String fingerprint(String value, String field) {
        String exact = normalized(value);
        if (!isFingerprint(exact)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static boolean isFingerprint(String value) {
        return value != null && value.matches("sha256:[a-f0-9]{64}");
    }

    private static String identifier(String value, String field) {
        return ReadOnlyShadowAuthoritySeal.identifier(value, field);
    }

    private static String required(String value, String field, int maximumLength) {
        return ReadOnlyShadowAuthoritySeal.required(value, field, maximumLength);
    }

    private static Instant time(Instant value, String field) {
        return ReadOnlyShadowAuthoritySeal.time(value, field);
    }

    private static String canonicalBase64(String value, String field, int maximumLength) {
        String exact = ReadOnlyShadowAuthoritySeal.canonicalBase64(
                value, field, maximumLength);
        if (!Base64.getEncoder().encodeToString(Base64.getDecoder().decode(exact)).equals(exact)) {
            throw new IllegalArgumentException(field + " must use canonical padded base64");
        }
        return exact;
    }

    private static String normalized(String value) {
        return ReadOnlyShadowAuthoritySeal.normalized(value);
    }
}
