package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Signed, payload-free authority token for one governed corpus serving generation.
 *
 * <p>The token names one monotonic stream generation, its exact predecessor, complete enterprise
 * scope, authorized purpose, payload-free dependency closure, revocation cursor, hard expiry, and
 * maximum floor-cache staleness. Resource Gateway binds the complete token into a mirror plan and
 * rechecks the authority's current floor before every new run and after each bounded cache window.
 * The token does not contain observation requests, responses, business identities, or secrets.</p>
 *
 * @param schemaVersion token protocol version
 * @param tokenFingerprint canonical fingerprint of the complete signed token
 * @param materialFingerprint canonical fingerprint of the immutable material
 * @param material immutable generation coordinates signed by the authority
 * @param seal detached Ed25519 authority signature
 */
public record MirrorServingGenerationToken(
        String schemaVersion,
        String tokenFingerprint,
        String materialFingerprint,
        Material material,
        Seal seal
) {
    /** Current signed serving-generation token protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorServingGenerationToken.v1";
    /** Domain artifact kind used by plan and evidence tooling. */
    public static final String ARTIFACT_KIND = "MIRROR_SERVING_GENERATION";
    /** Maximum online lifetime of one generation token. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofHours(24);
    /** Maximum permitted delay before a replica must re-read the authority floor. */
    public static final Duration MAXIMUM_STALENESS = Duration.ofMinutes(5);
    /** Maximum tolerated signing-clock skew around issuance. */
    public static final Duration MAXIMUM_SIGNING_SKEW = Duration.ofMinutes(1);

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates bounded wire syntax without treating the signature as trusted. */
    public MirrorServingGenerationToken {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : required(schemaVersion, "schemaVersion", 128);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported mirror serving-generation schemaVersion");
        }
        tokenFingerprint = optionalFingerprint(
                tokenFingerprint, "tokenFingerprint");
        materialFingerprint = fingerprint(
                materialFingerprint, "materialFingerprint");
        material = Objects.requireNonNull(material, "material");
        seal = Objects.requireNonNull(seal, "seal");
        if (seal.signedAt().isBefore(
                material.issuedAt().minus(MAXIMUM_SIGNING_SKEW))
                || seal.signedAt().isAfter(
                material.issuedAt().plus(MAXIMUM_SIGNING_SKEW))
                || !seal.signedAt().isBefore(material.expiresAt())) {
            throw new IllegalArgumentException(
                    "serving-generation signature time is outside its issuance window");
        }
    }

    /**
     * Returns the same token material with a replacement complete fingerprint.
     *
     * @param fingerprint canonical complete fingerprint or blank sealing placeholder
     * @return copied token
     */
    public MirrorServingGenerationToken withTokenFingerprint(String fingerprint) {
        return new MirrorServingGenerationToken(
                schemaVersion, fingerprint, materialFingerprint, material, seal);
    }

    /**
     * Returns the payload-free content-addressed artifact reference.
     *
     * @return exact serving generation reference
     */
    public MirrorArtifactRef artifactRef() {
        if (tokenFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "serving-generation token must be sealed before referencing it");
        }
        return new MirrorArtifactRef(
                ARTIFACT_KIND, material.streamId(), material.generation(),
                tokenFingerprint);
    }

    /** Prevents the detached signature from expanding logs. */
    @Override
    public String toString() {
        return "MirrorServingGenerationToken[streamId=" + material.streamId()
                + ", generation=" + material.generation()
                + ", revocationCursor=" + material.revocationCursor()
                + ", tokenFingerprint=" + tokenFingerprint
                + ", expiresAt=" + material.expiresAt()
                + ", authorityId=" + seal.authorityId()
                + ", keyId=" + seal.keyId() + "]";
    }

    /**
     * Immutable authority decision material.
     *
     * @param streamId stable authority stream identity
     * @param generation positive monotonic generation
     * @param previousTokenFingerprint blank only for generation one
     * @param scope exact enterprise scope
     * @param authorizedPurpose exact non-production mirror purpose
     * @param dependencyClosureFingerprint exact payload-free corpus dependency closure
     * @param revocationCursor monotonic authority revocation cursor observed by this generation
     * @param issuedAt authority issuance time
     * @param expiresAt exclusive generation expiry
     * @param maximumStaleness positive signed current-floor cache bound
     */
    public record Material(
            String streamId,
            long generation,
            String previousTokenFingerprint,
            CapabilitySnapshot.Scope scope,
            String authorizedPurpose,
            String dependencyClosureFingerprint,
            long revocationCursor,
            Instant issuedAt,
            Instant expiresAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Duration maximumStaleness
    ) {
        /** Enforces monotonic coordinates and bounded validity windows. */
        public Material {
            streamId = identifier(streamId, "streamId");
            if (generation < 1) {
                throw new IllegalArgumentException(
                        "serving-generation generation must be positive");
            }
            previousTokenFingerprint = normalized(previousTokenFingerprint);
            if (generation == 1 && !previousTokenFingerprint.isBlank()
                    || generation > 1
                    && !FINGERPRINT.matcher(previousTokenFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "serving-generation predecessor does not match generation semantics");
            }
            scope = Objects.requireNonNull(scope, "scope");
            authorizedPurpose = required(
                    authorizedPurpose, "authorizedPurpose", 256);
            if (authorizedPurpose.toUpperCase(java.util.Locale.ROOT)
                    .contains("PRODUCTION")) {
                throw new IllegalArgumentException(
                        "serving-generation purpose must be non-production");
            }
            dependencyClosureFingerprint = fingerprint(
                    dependencyClosureFingerprint,
                    "dependencyClosureFingerprint");
            if (revocationCursor < 0) {
                throw new IllegalArgumentException(
                        "serving-generation revocationCursor must be non-negative");
            }
            issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(issuedAt)
                    || Duration.between(issuedAt, expiresAt)
                    .compareTo(MAXIMUM_LIFETIME) > 0) {
                throw new IllegalArgumentException(
                        "serving-generation validity window is invalid");
            }
            if (maximumStaleness == null || maximumStaleness.isZero()
                    || maximumStaleness.isNegative()
                    || maximumStaleness.compareTo(MAXIMUM_STALENESS) > 0
                    || maximumStaleness.compareTo(
                    Duration.between(issuedAt, expiresAt)) >= 0) {
                throw new IllegalArgumentException(
                        "serving-generation maximum staleness is invalid");
            }
        }
    }

    /**
     * Detached authority signature over domain-separated material.
     *
     * @param authorityId pinned serving-generation authority identity
     * @param keyId exact verification key
     * @param algorithm fixed signature algorithm
     * @param signedAt signature time
     * @param signature canonical base64 Ed25519 signature
     */
    public record Seal(
            String authorityId,
            String keyId,
            String algorithm,
            Instant signedAt,
            String signature
    ) {
        /** Validates bounded detached-signature syntax. */
        public Seal {
            authorityId = identifier(authorityId, "authorityId");
            keyId = identifier(keyId, "keyId");
            algorithm = required(algorithm, "algorithm", 32);
            signedAt = Objects.requireNonNull(signedAt, "signedAt");
            signature = canonicalBase64(signature, "signature");
            if (!"Ed25519".equals(algorithm)) {
                throw new IllegalArgumentException(
                        "serving-generation tokens require Ed25519");
            }
            if (Base64.getDecoder().decode(signature).length != 64) {
                throw new IllegalArgumentException(
                        "serving-generation signature must be 64 bytes");
            }
        }
    }

    private static String identifier(String value, String field) {
        String exact = required(value, field, 512);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " contains unsupported characters");
        }
        return exact;
    }

    private static String fingerprint(String value, String field) {
        String exact = required(value, field, 71);
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 fingerprint");
        }
        return exact;
    }

    private static String optionalFingerprint(String value, String field) {
        String exact = normalized(value);
        if (!exact.isBlank() && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or a canonical SHA-256 fingerprint");
        }
        return exact;
    }

    private static String canonicalBase64(String value, String field) {
        String exact = required(value, field, 4_096);
        try {
            byte[] decoded = Base64.getDecoder().decode(exact);
            if (decoded.length == 0 || !exact.equals(
                    Base64.getEncoder().encodeToString(decoded))) {
                throw new IllegalArgumentException(
                        field + " must use canonical base64");
            }
            return exact;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    field + " must use canonical base64", invalid);
        }
    }

    private static String required(
            String value, String field, int maximumLength) {
        String exact = normalized(value);
        if (exact.isBlank() || exact.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must be non-blank and bounded");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
