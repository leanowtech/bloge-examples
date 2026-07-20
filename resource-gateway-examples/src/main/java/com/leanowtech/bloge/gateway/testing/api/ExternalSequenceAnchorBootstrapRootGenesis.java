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
 * Deployment-pinned genesis material for one managed external-anchor bootstrap-root chain.
 *
 * <p>The genesis contains public verification keys only. It is the finite trust anchor from which
 * every restart-free bootstrap-root transition is replayed. Changing this material is an explicit
 * out-of-band re-bootstrap operation; normal root rotation must use a cross-signed transition.</p>
 *
 * @param schemaVersion genesis protocol generation
 * @param scopeId stable Resource Gateway fleet scope
 * @param rootSetId stable managed bootstrap-root set identity
 * @param trustDomain independent bootstrap-root trust domain
 * @param signatureThreshold required distinct root-authority quorum
 * @param maximumFaults declared Byzantine fault bound
 * @param rootKeys canonical genesis verification keys
 * @param policyFingerprint exact genesis ceremony policy
 */
public record ExternalSequenceAnchorBootstrapRootGenesis(
        String schemaVersion,
        String scopeId,
        String rootSetId,
        String trustDomain,
        int signatureThreshold,
        int maximumFaults,
        List<RootKeyMaterial> rootKeys,
        String policyFingerprint) {

    /** Current canonical genesis generation. */
    public static final String SCHEMA_VERSION =
            "bloge.externalSequenceAnchorBootstrapRootGenesis.v1";

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces canonical identity, Byzantine quorum math, and bounded public key material. */
    public ExternalSequenceAnchorBootstrapRootGenesis {
        schemaVersion = normalized(schemaVersion);
        scopeId = normalized(scopeId);
        rootSetId = normalized(rootSetId);
        trustDomain = normalized(trustDomain);
        policyFingerprint = normalized(policyFingerprint);
        rootKeys = immutableKeys(rootKeys);
        int authorityCount = distinctAuthorities(rootKeys);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !IDENTIFIER.matcher(scopeId).matches()
                || !IDENTIFIER.matcher(rootSetId).matches()
                || !IDENTIFIER.matcher(trustDomain).matches()
                || maximumFaults < 0 || maximumFaults > 10
                || authorityCount < 3 * maximumFaults + 1
                || signatureThreshold < 2 * maximumFaults + 1
                || signatureThreshold > authorityCount
                || !FINGERPRINT.matcher(policyFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "External sequence-anchor bootstrap-root genesis is invalid");
        }
    }

    /** @return canonical SHA-256 identity signed into the first transition predecessor */
    public String materialFingerprint(ObjectMapper objectMapper) {
        return ProtocolFingerprint.of(objectMapper, this);
    }

    /**
     * One Ed25519 bootstrap-root verification key.
     *
     * @param authorityId stable independent ceremony authority
     * @param keyId rotation-aware key identity
     * @param publicKeyBase64 X.509-encoded public Ed25519 key
     * @param notBefore inclusive signing activation time
     * @param expiresAt exclusive signing expiry time
     * @param enabled administrative enablement
     * @param revoked compromise or withdrawal state
     */
    public record RootKeyMaterial(
            String authorityId,
            String keyId,
            String publicKeyBase64,
            Instant notBefore,
            Instant expiresAt,
            boolean enabled,
            boolean revoked) {

        /** Rejects malformed identity, lifecycle, or non-Ed25519 public material. */
        public RootKeyMaterial {
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            publicKeyBase64 = normalized(publicKeyBase64);
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(keyId).matches()
                    || !wholeSecond(notBefore) || !wholeSecond(expiresAt)
                    || !expiresAt.isAfter(notBefore)
                    || !validPublicKeyShape(publicKeyBase64)) {
                throw new IllegalArgumentException(
                        "External sequence-anchor bootstrap-root key is invalid");
            }
        }

        String canonicalIdentity() {
            return authorityId + '\u0000' + keyId;
        }
    }

    static List<RootKeyMaterial> immutableKeys(List<RootKeyMaterial> values) {
        List<RootKeyMaterial> result = values == null ? List.of() : List.copyOf(values);
        List<RootKeyMaterial> sorted = result.stream()
                .sorted(Comparator.comparing(RootKeyMaterial::authorityId)
                        .thenComparing(RootKeyMaterial::keyId))
                .toList();
        Set<String> identities = new HashSet<>();
        if (result.isEmpty() || result.size() > 64 || !result.equals(sorted)
                || result.stream().anyMatch(key -> !identities.add(key.canonicalIdentity()))) {
            throw new IllegalArgumentException(
                    "External sequence-anchor bootstrap-root key set is invalid");
        }
        return result;
    }

    static int distinctAuthorities(List<RootKeyMaterial> keys) {
        return (int) keys.stream().map(RootKeyMaterial::authorityId).distinct().count();
    }

    private static boolean validPublicKeyShape(String encoded) {
        try {
            return encoded.length() <= 512 && Base64.getDecoder().decode(encoded).length == 44;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static boolean wholeSecond(Instant value) {
        return value != null && value.getNano() == 0;
    }

    static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
