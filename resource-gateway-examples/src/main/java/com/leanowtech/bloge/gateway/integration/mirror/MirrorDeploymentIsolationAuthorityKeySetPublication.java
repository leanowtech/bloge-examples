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
 * Short-lived, threshold-signed publication of deployment-isolation authority keys.
 *
 * <p>The publication is bound to one complete enterprise scope and one immutable deployment
 * generation. It is signed by independent bootstrap-root authorities rather than by Resource
 * Gateway or the isolation-attestation authority itself. Its monotonic generation and predecessor
 * fingerprint let consumers reject rollback, fork, and skipped-publication attacks before trusting
 * any advertised attestation key.</p>
 *
 * @param schemaVersion authority key-set publication protocol version
 * @param publicationFingerprint canonical fingerprint of the complete publication
 * @param materialFingerprint canonical fingerprint signed by every bootstrap root
 * @param material immutable scope, identity, policy, lifetime, and key material
 * @param signatures canonically ordered signatures from distinct bootstrap-root authorities
 */
public record MirrorDeploymentIsolationAuthorityKeySetPublication(
        String schemaVersion,
        String publicationFingerprint,
        String materialFingerprint,
        Material material,
        List<RootSignature> signatures
) {
    /** Current authority key-set publication protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorDeploymentIsolationAuthorityKeySetPublication.v1";
    /** Artifact kind used when another mirror protocol references this publication. */
    public static final String ARTIFACT_KIND = "DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET";
    /** Maximum lifetime of one online publication. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofHours(24);
    /** Maximum delay between publication issuance and activation. */
    public static final Duration MAXIMUM_ACTIVATION_DELAY = Duration.ofMinutes(5);
    /** Maximum number of advertised isolation-attestation keys. */
    public static final int MAXIMUM_AUTHORITY_KEYS = 32;
    /** Maximum number of independent bootstrap-root signatures. */
    public static final int MAXIMUM_ROOT_SIGNATURES = 16;

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}");

    /** Validates deterministic envelope syntax without treating any signature as trusted. */
    public MirrorDeploymentIsolationAuthorityKeySetPublication {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : required(schemaVersion, "schemaVersion", 128);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported deployment isolation authority key-set schemaVersion");
        }
        publicationFingerprint = fingerprint(publicationFingerprint,
                "publicationFingerprint");
        materialFingerprint = fingerprint(materialFingerprint, "materialFingerprint");
        material = Objects.requireNonNull(material, "material");
        signatures = orderedSignatures(signatures, material);
    }

    /**
     * Creates the content-addressed reference used by later trusted-distribution protocols.
     *
     * @return exact immutable authority key-set publication reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(ARTIFACT_KIND, material.keySetId(), material.generation(),
                publicationFingerprint);
    }

    /**
     * Scope-bound immutable authority key-set material.
     *
     * @param keySetId stable key-set stream identity
     * @param generation positive monotonic stream generation
     * @param previousPublicationFingerprint blank for generation one, otherwise exact predecessor
     * @param scope complete owning enterprise scope
     * @param deployment exact immutable mirror workload generation
     * @param attestationIssuer exact SRE/security issuer advertised by every contained key
     * @param rootTrustDomain locally pinned bootstrap-root trust domain
     * @param rootThreshold required number of distinct root authorities
     * @param policyFingerprint exact authority publication policy generation
     * @param issuedAt issuance request time
     * @param notBefore inclusive activation time after threshold signing completes
     * @param expiresAt exclusive online-publication validity bound
     * @param authorityKeys canonically ordered public isolation-attestation keys
     */
    public record Material(
            String keySetId,
            long generation,
            String previousPublicationFingerprint,
            CapabilitySnapshot.Scope scope,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            String attestationIssuer,
            String rootTrustDomain,
            int rootThreshold,
            String policyFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt,
            List<AuthorityKey> authorityKeys
    ) {
        /** Enforces one short-lived, exact-scope, monotonic key-set generation. */
        public Material {
            keySetId = identifier(keySetId, "keySetId");
            if (generation < 1) {
                throw new IllegalArgumentException("authority key-set generation must be positive");
            }
            previousPublicationFingerprint = normalized(previousPublicationFingerprint);
            if (generation == 1 && !previousPublicationFingerprint.isBlank()
                    || generation > 1 && !FINGERPRINT.matcher(
                    previousPublicationFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "authority key-set predecessor does not match generation semantics");
            }
            scope = Objects.requireNonNull(scope, "scope");
            identifier(scope.tenantId(), "scope.tenantId");
            identifier(scope.organizationId(), "scope.organizationId");
            optionalIdentifier(scope.projectId(), "scope.projectId");
            identifier(scope.environmentId(), "scope.environmentId");
            optionalIdentifier(scope.region(), "scope.region");
            deployment = Objects.requireNonNull(deployment, "deployment");
            attestationIssuer = identifier(attestationIssuer, "attestationIssuer");
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
                throw new IllegalArgumentException(
                        "authority key-set publication window is invalid");
            }
            authorityKeys = orderedAuthorityKeys(authorityKeys, notBefore, expiresAt);
        }
    }

    /** Isolation-attestation authority-key lifecycle. */
    public enum AuthorityKeyState {
        /** Key may sign new isolation attestations and verify historical attestations. */
        ACTIVE,
        /** Key may only verify historical isolation attestations. */
        RETIRED,
        /** Key must not be trusted for any verification. */
        REVOKED
    }

    /**
     * One public isolation-attestation authority key.
     *
     * @param keyId stable key identity unique within the publication
     * @param algorithm fixed signature algorithm
     * @param encodedPublicKey canonical base64 X.509 SubjectPublicKeyInfo bytes
     * @param notBefore inclusive attestation-signing validity bound
     * @param notAfter exclusive attestation-signing validity bound
     * @param state current lifecycle state
     */
    public record AuthorityKey(
            String keyId,
            String algorithm,
            String encodedPublicKey,
            Instant notBefore,
            Instant notAfter,
            AuthorityKeyState state
    ) {
        /** Validates bounded public key material and lifecycle bounds. */
        public AuthorityKey {
            keyId = identifier(keyId, "authorityKey.keyId");
            algorithm = required(algorithm, "authorityKey.algorithm", 32);
            encodedPublicKey = canonicalBase64(encodedPublicKey,
                    "authorityKey.encodedPublicKey", 16_384);
            notBefore = time(notBefore, "authorityKey.notBefore");
            notAfter = time(notAfter, "authorityKey.notAfter");
            state = Objects.requireNonNull(state, "authorityKey.state");
            if (!"Ed25519".equals(algorithm) || !notAfter.isAfter(notBefore)) {
                throw new IllegalArgumentException("isolation authority key is invalid");
            }
        }
    }

    /**
     * One detached bootstrap-root signature over {@link #materialFingerprint()}.
     *
     * @param authorityId stable independent root-authority identity
     * @param keyId exact locally pinned root key
     * @param algorithm fixed signature algorithm
     * @param signedAt signature time within the pre-activation collection window
     * @param signature canonical base64 detached signature
     */
    public record RootSignature(
            String authorityId,
            String keyId,
            String algorithm,
            Instant signedAt,
            String signature
    ) {
        /** Validates bounded detached-signature syntax. */
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

    private static List<AuthorityKey> orderedAuthorityKeys(
            List<AuthorityKey> values, Instant publicationNotBefore, Instant publicationExpiresAt) {
        if (values == null || values.isEmpty() || values.size() > MAXIMUM_AUTHORITY_KEYS) {
            throw new IllegalArgumentException("authorityKeys are outside protocol bounds");
        }
        List<AuthorityKey> copy = new ArrayList<>(values);
        copy.replaceAll(value -> Objects.requireNonNull(value, "authorityKey"));
        List<AuthorityKey> sorted = copy.stream()
                .sorted(Comparator.comparing(AuthorityKey::keyId)).toList();
        if (!copy.equals(sorted)) {
            throw new IllegalArgumentException("authorityKeys must use canonical order");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).keyId().equals(copy.get(index).keyId())) {
                throw new IllegalArgumentException("authorityKeys must use unique keyId values");
            }
        }
        boolean usableActiveKey = copy.stream().anyMatch(key ->
                key.state() == AuthorityKeyState.ACTIVE
                        && !key.notBefore().isAfter(publicationNotBefore)
                        && !key.notAfter().isBefore(publicationExpiresAt));
        if (!usableActiveKey) {
            throw new IllegalArgumentException(
                    "authorityKeys require an ACTIVE key covering the publication window");
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
        List<RootSignature> sorted = copy.stream().sorted(
                Comparator.comparing(RootSignature::authorityId)
                        .thenComparing(RootSignature::keyId)).toList();
        if (!copy.equals(sorted)) {
            throw new IllegalArgumentException("root signatures must use canonical order");
        }
        for (int index = 0; index < copy.size(); index++) {
            RootSignature current = copy.get(index);
            if (current.signedAt().isBefore(material.issuedAt())
                    || current.signedAt().isAfter(material.notBefore())) {
                throw new IllegalArgumentException(
                        "root signature is outside the pre-activation collection window");
            }
            if (index > 0 && copy.get(index - 1).authorityId()
                    .equals(current.authorityId())) {
                throw new IllegalArgumentException(
                        "root signatures require distinct authority identities");
            }
        }
        return List.copyOf(copy);
    }

    private static String fingerprint(String value, String field) {
        String exact = required(value, field, 71);
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = required(value, field, 512);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return exact;
    }

    private static String optionalIdentifier(String value, String field) {
        String exact = normalized(value);
        if (!exact.isEmpty() && !IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return exact;
    }

    private static String canonicalBase64(String value, String field, int maximum) {
        String exact = required(value, field, maximum);
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

    private static Instant time(Instant value, String field) {
        Instant exact = Objects.requireNonNull(value, field);
        if (Instant.EPOCH.equals(exact)) {
            throw new IllegalArgumentException(field + " must not be epoch");
        }
        return exact;
    }

    private static String required(String value, String field, int maximum) {
        String exact = normalized(value);
        if (exact.isEmpty() || exact.length() > maximum) {
            throw new IllegalArgumentException(field + " must be bounded and non-blank");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
