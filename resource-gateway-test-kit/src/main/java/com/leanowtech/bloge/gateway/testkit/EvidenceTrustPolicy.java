package com.leanowtech.bloge.gateway.testkit;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Caller-owned trust anchors and quorum policy for evidence key-set pin publications.
 *
 * <p>This value must come from CI, a security-owned registry, or another channel independent of
 * Resource Gateway. It is never decoded from the trust-bundle response.</p>
 *
 * @param trustDomain expected governance trust domain
 * @param logId expected append-only log identity
 * @param signatureThreshold required distinct valid authority signatures
 * @param authorities externally trusted Ed25519 verification keys
 */
public record EvidenceTrustPolicy(
        String trustDomain,
        String logId,
        int signatureThreshold,
        List<AuthorityKey> authorities
) {
    /**
     * One externally provisioned governance authority key.
     *
     * @param authorityId stable authority id used by detached signatures
     * @param algorithm signature algorithm, fixed to Ed25519 in v1
     * @param encodedPublicKey base64 X.509 SubjectPublicKeyInfo bytes
     * @param notBefore inclusive authority-key activation time
     * @param expiresAt exclusive authority-key expiry time
     * @param enabled administrative enablement
     * @param revoked compromise or withdrawal state
     */
    public record AuthorityKey(
            String authorityId,
            String algorithm,
            String encodedPublicKey,
            Instant notBefore,
            Instant expiresAt,
            boolean enabled,
            boolean revoked
    ) {
        /** Normalizes and cryptographically validates public authority material. */
        public AuthorityKey {
            authorityId = normalized(authorityId);
            algorithm = normalized(algorithm);
            encodedPublicKey = normalized(encodedPublicKey);
            notBefore = notBefore == null ? Instant.MIN : notBefore;
            expiresAt = expiresAt == null ? Instant.MAX : expiresAt;
            if (authorityId.isBlank() || authorityId.length() > 255
                    || !"Ed25519".equals(algorithm) || encodedPublicKey.isBlank()
                    || encodedPublicKey.length() > 2048 || !expiresAt.isAfter(notBefore)
                    || !validPublicKey(encodedPublicKey)) {
                throw new IllegalArgumentException("Evidence trust authority key is invalid");
            }
        }

        /**
         * Tests whether this authority key may authorize a publication time.
         *
         * @param publishedAt publication authorization time
         * @return true when the key is enabled, unrevoked, and temporally valid
         */
        public boolean activeAt(Instant publishedAt) {
            return enabled && !revoked && publishedAt != null && !publishedAt.isBefore(notBefore)
                    && publishedAt.isBefore(expiresAt);
        }
    }

    /** Normalizes order and rejects ambiguous authority/quorum configuration. */
    public EvidenceTrustPolicy {
        trustDomain = normalized(trustDomain);
        logId = normalized(logId);
        authorities = authorities == null ? List.of() : authorities.stream()
                .sorted(Comparator.comparing(AuthorityKey::authorityId)).toList();
        Set<String> ids = new HashSet<>();
        if (trustDomain.isBlank() || logId.isBlank() || authorities.isEmpty()
                || authorities.size() > 32 || signatureThreshold < 1
                || signatureThreshold > authorities.size()
                || authorities.stream().anyMatch(authority -> !ids.add(authority.authorityId()))) {
            throw new IllegalArgumentException("Evidence trust policy is invalid");
        }
    }

    private static boolean validPublicKey(String encodedPublicKey) {
        try {
            KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(encodedPublicKey)));
            return true;
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            return false;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
