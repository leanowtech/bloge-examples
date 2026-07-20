package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deployment-signed exact serving inventory for one test-secret authority trust cohort.
 *
 * <p>The deployment control plane, rather than an individual Resource Gateway replica, owns the
 * complete expected member set. The signed material also binds the exact test-secret authority
 * identity, preventing a locally altered authority configuration from being accepted merely
 * because all compromised replicas agree with each other. This protocol contains public
 * attestation metadata only; it never carries secret references, payloads, credentials or private
 * key material.</p>
 *
 * @param schemaVersion signed-envelope protocol generation
 * @param material exact deployment-owned inventory statement
 * @param materialFingerprint canonical SHA-256 identity of {@code material}
 * @param signatures canonical distinct-authority Ed25519 signatures
 */
public record TestSecretAuthorityServingInventory(
        String schemaVersion,
        Material material,
        String materialFingerprint,
        List<AuthoritySignature> signatures) {

    /** Current signed test-secret serving-inventory envelope generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSecretAuthorityServingInventory.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates the bounded canonical envelope before any trust decision. */
    public TestSecretAuthorityServingInventory {
        schemaVersion = normalized(schemaVersion);
        materialFingerprint = normalized(materialFingerprint);
        signatures = signatures == null ? List.of() : List.copyOf(signatures);
        List<AuthoritySignature> ordered = signatures.stream()
                .sorted(Comparator.comparing(AuthoritySignature::authorityId)
                        .thenComparing(AuthoritySignature::keyId))
                .toList();
        Set<String> authorities = new HashSet<>();
        if (!SCHEMA_VERSION.equals(schemaVersion) || material == null
                || !FINGERPRINT.matcher(materialFingerprint).matches()
                || signatures.isEmpty() || signatures.size() > 32
                || !ordered.equals(signatures)
                || signatures.stream().anyMatch(signature ->
                !authorities.add(signature.authorityId()))) {
            throw new IllegalArgumentException(
                    "Test-secret serving inventory envelope is invalid");
        }
    }

    /**
     * Recomputes the signed material identity without trusting the envelope fingerprint.
     *
     * @param objectMapper canonical protocol mapper
     * @return true only when material and supplied fingerprint are identical
     */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.of(objectMapper, material));
    }

    /**
     * Canonical deployment statement covered by every detached signature.
     *
     * @param schemaVersion material protocol generation
     * @param trustDomain independent deployment-inventory trust domain
     * @param inventoryId unique attestation identity for audit correlation
     * @param revision monotonic revision within the stable fleet scope
     * @param scopeId stable fleet scope across deployment generations
     * @param cohortId immutable deployment generation
     * @param artifactFingerprint exact image or application SHA-256
     * @param protocolVersion exact signed-response protocol generation
     * @param authorityId exact signed test-secret authority identity
     * @param expectedInstanceIds sorted complete serving-slot inventory
     * @param policyFingerprint accepted external inventory-policy revision
     * @param issuedAt inventory issuance time
     * @param notBefore inclusive activation time
     * @param expiresAt exclusive validity deadline
     */
    public record Material(
            String schemaVersion,
            String trustDomain,
            String inventoryId,
            long revision,
            String scopeId,
            String cohortId,
            String artifactFingerprint,
            String protocolVersion,
            String authorityId,
            List<String> expectedInstanceIds,
            String policyFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {

        /** Current signed test-secret serving-inventory material generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSecretAuthorityServingInventoryMaterial.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Rejects non-canonical, incomplete, duplicated or unbounded material. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            inventoryId = normalized(inventoryId);
            scopeId = normalized(scopeId);
            cohortId = normalized(cohortId);
            artifactFingerprint = normalized(artifactFingerprint);
            protocolVersion = normalized(protocolVersion);
            authorityId = normalized(authorityId);
            policyFingerprint = normalized(policyFingerprint);
            expectedInstanceIds = expectedInstanceIds == null
                    ? List.of() : List.copyOf(expectedInstanceIds);
            List<String> sorted = expectedInstanceIds.stream().sorted().toList();
            Set<String> unique = new HashSet<>(expectedInstanceIds);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(trustDomain).matches()
                    || !IDENTIFIER.matcher(inventoryId).matches()
                    || revision < 1
                    || !IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(cohortId).matches()
                    || !FINGERPRINT.matcher(artifactFingerprint).matches()
                    || !IDENTIFIER.matcher(protocolVersion).matches()
                    || !IDENTIFIER.matcher(authorityId).matches()
                    || expectedInstanceIds.isEmpty() || expectedInstanceIds.size() > 256
                    || unique.size() != expectedInstanceIds.size()
                    || !sorted.equals(expectedInstanceIds)
                    || expectedInstanceIds.stream().anyMatch(value ->
                    !IDENTIFIER.matcher(normalized(value)).matches()
                            || !value.equals(normalized(value)))
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !wholeSecond(issuedAt) || !wholeSecond(notBefore)
                    || !wholeSecond(expiresAt)
                    || notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Test-secret serving inventory material is invalid");
            }
        }
    }

    /**
     * One detached signature from a distinct deployment-inventory authority.
     *
     * @param authorityId stable independent signing authority identity
     * @param keyId rotation-aware public-key identity
     * @param algorithm fixed {@code Ed25519} algorithm
     * @param signedAt signature creation time
     * @param signature base64-encoded 64-byte Ed25519 signature
     */
    public record AuthoritySignature(
            String authorityId,
            String keyId,
            String algorithm,
            Instant signedAt,
            String signature) {

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Validates bounded detached-signature shape before cryptographic verification. */
        public AuthoritySignature {
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signature = normalized(signature);
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(keyId).matches()
                    || !"Ed25519".equals(algorithm) || !wholeSecond(signedAt)
                    || !validSignature(signature)) {
                throw new IllegalArgumentException(
                        "Test-secret serving inventory signature is invalid");
            }
        }

        private static boolean validSignature(String encoded) {
            try {
                return Base64.getDecoder().decode(encoded).length == 64;
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
    }

    private static boolean wholeSecond(Instant value) {
        return value != null && value.getNano() == 0;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
