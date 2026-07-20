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
 * Bootstrap-quorum-signed publication of external sequence-notary verification keys.
 *
 * <p>The canonical material binds one exact external anchor deployment, its Byzantine quorum
 * policy, every accepted public key and key lifecycle, a monotonic predecessor chain, and a hard
 * validity window. The envelope contains public verification material only. Bootstrap private
 * keys, notary endpoint locations, sequence heads, and business payloads are forbidden.</p>
 *
 * @param schemaVersion envelope protocol generation
 * @param material exact managed notary trust statement
 * @param materialFingerprint canonical SHA-256 identity of {@code material}
 * @param bootstrapSignatures independent bootstrap-root M-of-N signatures
 */
public record ExternalSequenceAnchorTrustPublication(
        String schemaVersion,
        Material material,
        String materialFingerprint,
        List<TestSuiteStabilityServingInventory.AuthoritySignature> bootstrapSignatures) {

    /** Current managed external-sequence trust publication generation. */
    public static final String SCHEMA_VERSION =
            "bloge.externalSequenceAnchorTrustPublication.v1";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects partial, unbounded, non-canonical, or authority-duplicated envelopes. */
    public ExternalSequenceAnchorTrustPublication {
        schemaVersion = normalized(schemaVersion);
        materialFingerprint = normalized(materialFingerprint);
        bootstrapSignatures = immutableSignatures(bootstrapSignatures);
        if (!SCHEMA_VERSION.equals(schemaVersion) || material == null
                || !FINGERPRINT.matcher(materialFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "External sequence-anchor trust publication envelope is invalid");
        }
    }

    /** @return true only when the material fingerprint is canonical and exact */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.of(objectMapper, material));
    }

    /**
     * Canonical statement approved by the configured bootstrap-root quorum.
     *
     * @param schemaVersion material protocol generation
     * @param trustRootSetId stable managed notary trust-set identity
     * @param sequence contiguous one-based publication generation
     * @param previousMaterialFingerprint exact predecessor, blank only at sequence one
     * @param scopeId stable Resource Gateway fleet scope
     * @param anchorSetId exact external notary-set identity
     * @param notaryTrustDomain receipt signer trust domain
     * @param bootstrapTrustDomain independent publication signer trust domain
     * @param receiptSignatureThreshold accepted notary receipt quorum
     * @param maximumFaults declared Byzantine fault bound
     * @param notaryKeys canonical managed receipt verification keys
     * @param policyFingerprint exact externally governed key-rotation policy
     * @param issuedAt publication issuance time
     * @param notBefore inclusive activation time
     * @param expiresAt exclusive hard validity deadline
     */
    public record Material(
            String schemaVersion,
            String trustRootSetId,
            long sequence,
            String previousMaterialFingerprint,
            String scopeId,
            String anchorSetId,
            String notaryTrustDomain,
            String bootstrapTrustDomain,
            int receiptSignatureThreshold,
            int maximumFaults,
            List<AuthorityKeyMaterial> notaryKeys,
            String policyFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {

        /** Current canonical managed-notary material generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorTrustMaterial.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Enforces one canonical, complete, quorum-consistent notary key snapshot. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            trustRootSetId = normalized(trustRootSetId);
            previousMaterialFingerprint = normalized(previousMaterialFingerprint);
            scopeId = normalized(scopeId);
            anchorSetId = normalized(anchorSetId);
            notaryTrustDomain = normalized(notaryTrustDomain);
            bootstrapTrustDomain = normalized(bootstrapTrustDomain);
            policyFingerprint = normalized(policyFingerprint);
            notaryKeys = immutableKeys(notaryKeys);
            int authorityCount = distinctAuthorities(notaryKeys);
            boolean predecessorShape = sequence == 1 && previousMaterialFingerprint.isEmpty()
                    || sequence > 1
                    && FINGERPRINT.matcher(previousMaterialFingerprint).matches();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(trustRootSetId).matches()
                    || sequence < 1 || !predecessorShape
                    || !IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(anchorSetId).matches()
                    || !IDENTIFIER.matcher(notaryTrustDomain).matches()
                    || !IDENTIFIER.matcher(bootstrapTrustDomain).matches()
                    || notaryTrustDomain.equals(bootstrapTrustDomain)
                    || maximumFaults < 0 || maximumFaults > 10
                    || authorityCount < 3 * maximumFaults + 1
                    || receiptSignatureThreshold < 2 * maximumFaults + 1
                    || receiptSignatureThreshold > authorityCount
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !wholeSecond(issuedAt) || !wholeSecond(notBefore)
                    || !wholeSecond(expiresAt)
                    || notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "External sequence-anchor trust publication material is invalid");
            }
        }
    }

    /**
     * One managed Ed25519 receipt verification key.
     *
     * @param authorityId stable independent notary authority
     * @param keyId rotation-aware key identity
     * @param publicKeyBase64 X.509-encoded public Ed25519 key
     * @param notBefore inclusive signing activation time
     * @param expiresAt exclusive signing expiry time
     * @param enabled administrative enablement
     * @param revoked compromise or withdrawal state
     */
    public record AuthorityKeyMaterial(
            String authorityId,
            String keyId,
            String publicKeyBase64,
            Instant notBefore,
            Instant expiresAt,
            boolean enabled,
            boolean revoked) {

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Rejects malformed identity, lifecycle, or non-Ed25519-sized public material. */
        public AuthorityKeyMaterial {
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            publicKeyBase64 = normalized(publicKeyBase64);
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(keyId).matches()
                    || !wholeSecond(notBefore) || !wholeSecond(expiresAt)
                    || !expiresAt.isAfter(notBefore)
                    || !validPublicKeyShape(publicKeyBase64)) {
                throw new IllegalArgumentException(
                        "External sequence-anchor notary key is invalid");
            }
        }

        String canonicalIdentity() {
            return authorityId + '\u0000' + keyId;
        }
    }

    private static List<TestSuiteStabilityServingInventory.AuthoritySignature>
            immutableSignatures(
            List<TestSuiteStabilityServingInventory.AuthoritySignature> values) {
        List<TestSuiteStabilityServingInventory.AuthoritySignature> result =
                values == null ? List.of() : List.copyOf(values);
        List<TestSuiteStabilityServingInventory.AuthoritySignature> sorted = result.stream()
                .sorted(Comparator.comparing(
                        TestSuiteStabilityServingInventory.AuthoritySignature::authorityId)
                        .thenComparing(
                                TestSuiteStabilityServingInventory.AuthoritySignature::keyId))
                .toList();
        Set<String> authorities = new HashSet<>();
        if (result.isEmpty() || result.size() > 32 || !result.equals(sorted)
                || result.stream().anyMatch(signature ->
                !authorities.add(signature.authorityId()))) {
            throw new IllegalArgumentException(
                    "External sequence-anchor bootstrap signatures are invalid");
        }
        return result;
    }

    private static List<AuthorityKeyMaterial> immutableKeys(List<AuthorityKeyMaterial> values) {
        List<AuthorityKeyMaterial> result = values == null ? List.of() : List.copyOf(values);
        List<AuthorityKeyMaterial> sorted = result.stream()
                .sorted(Comparator.comparing(AuthorityKeyMaterial::authorityId)
                        .thenComparing(AuthorityKeyMaterial::keyId))
                .toList();
        Set<String> identities = new HashSet<>();
        if (result.isEmpty() || result.size() > 64 || !result.equals(sorted)
                || result.stream().anyMatch(key -> !identities.add(key.canonicalIdentity()))) {
            throw new IllegalArgumentException(
                    "External sequence-anchor notary key set is invalid");
        }
        return result;
    }

    private static int distinctAuthorities(List<AuthorityKeyMaterial> keys) {
        return (int) keys.stream().map(AuthorityKeyMaterial::authorityId).distinct().count();
    }

    private static boolean validPublicKeyShape(String encoded) {
        try {
            return encoded.length() <= 512 && Base64.getDecoder().decode(encoded).length == 44;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean wholeSecond(Instant value) {
        return value != null && value.getNano() == 0;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
