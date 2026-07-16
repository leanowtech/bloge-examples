package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static startup-configured M-of-N Ed25519 trust policy for governance publications. */
public final class ConfiguredEvidenceKeySetTrustStore implements EvidenceKeySetTrustStore {
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final int MAX_AUTHORITIES = 32;

    /**
     * One externally provisioned governance verification key.
     *
     * @param authorityId stable signature authority id
     * @param publicKey Ed25519 public key
     * @param notBefore inclusive key activation time
     * @param expiresAt exclusive key expiry time
     * @param enabled administrative enablement flag
     * @param revoked compromise or withdrawal flag
     */
    public record AuthorityKey(
            String authorityId,
            PublicKey publicKey,
            Instant notBefore,
            Instant expiresAt,
            boolean enabled,
            boolean revoked
    ) {
        /** Validates key type and bounded authority identity. */
        public AuthorityKey {
            authorityId = normalize(authorityId);
            notBefore = notBefore == null ? Instant.MIN : notBefore;
            expiresAt = expiresAt == null ? Instant.MAX : expiresAt;
            String algorithm = publicKey == null ? "" : publicKey.getAlgorithm();
            if (authorityId.isBlank() || authorityId.length() > 255 || publicKey == null
                    || !(algorithm.equalsIgnoreCase("EdDSA") || algorithm.equalsIgnoreCase("Ed25519"))
                    || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException("Evidence trust authority key is invalid");
            }
        }

        /** @return true when this authority may authorize the publication time */
        public boolean activeAt(Instant publishedAt) {
            return enabled && !revoked && publishedAt != null && !publishedAt.isBefore(notBefore)
                    && publishedAt.isBefore(expiresAt);
        }
    }

    private final ObjectMapper objectMapper;
    private final String trustDomain;
    private final String logId;
    private final int signatureThreshold;
    private final Map<String, AuthorityKey> authorities;

    /**
     * Creates an immutable externally provisioned trust policy.
     *
     * @param objectMapper canonical JSON mapper
     * @param trustDomain expected governance trust domain
     * @param logId expected transparency log identity
     * @param signatureThreshold required distinct valid signatures
     * @param authorities trusted governance verification keys
     */
    public ConfiguredEvidenceKeySetTrustStore(
            ObjectMapper objectMapper, String trustDomain, String logId,
            int signatureThreshold, List<AuthorityKey> authorities) {
        this.objectMapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules() : objectMapper;
        this.trustDomain = normalize(trustDomain);
        this.logId = normalize(logId);
        this.signatureThreshold = signatureThreshold;
        LinkedHashMap<String, AuthorityKey> indexed = new LinkedHashMap<>();
        for (AuthorityKey key : authorities == null ? List.<AuthorityKey>of() : authorities) {
            if (key == null || indexed.putIfAbsent(key.authorityId(), key) != null) {
                throw new IllegalArgumentException("Evidence trust authority ids must be unique");
            }
        }
        if (this.trustDomain.isBlank() || this.logId.isBlank() || indexed.isEmpty()
                || indexed.size() > MAX_AUTHORITIES || signatureThreshold < 1
                || signatureThreshold > indexed.size()) {
            throw new IllegalArgumentException("Evidence key-set trust policy is invalid");
        }
        this.authorities = Map.copyOf(indexed);
    }

    @Override
    public Verification verify(EvidenceKeySetTrustPublication publication, Instant observedAt) {
        if (publication == null) {
            return result(VerificationStatus.MATERIAL_INVALID, "PUBLICATION_MISSING", 0);
        }
        if (!trustDomain.equals(publication.trustDomain()) || !logId.equals(publication.logId())) {
            return result(VerificationStatus.IDENTITY_MISMATCH, "TRUST_LOG_IDENTITY_MISMATCH", 0);
        }
        Instant now = observedAt == null ? Instant.now() : observedAt;
        if (publication.publishedAt().isAfter(now.plus(CLOCK_SKEW))
                || !publication.expiresAt().isAfter(now)) {
            return result(VerificationStatus.TIME_INVALID, "TRUST_PUBLICATION_TIME_INVALID", 0);
        }
        if (!publication.fingerprintVerified(objectMapper)) {
            return result(VerificationStatus.MATERIAL_INVALID, "TRUST_PUBLICATION_MATERIAL_INVALID", 0);
        }
        int valid = 0;
        Set<String> observed = new HashSet<>();
        for (EvidenceKeySetTrustPublication.AuthoritySignature authoritySignature
                : publication.signatures()) {
            if (!observed.add(authoritySignature.authorityId())) {
                return result(VerificationStatus.SIGNATURE_INVALID,
                        "TRUST_AUTHORITY_SIGNATURE_DUPLICATE", valid);
            }
            AuthorityKey key = authorities.get(authoritySignature.authorityId());
            if (key == null || !key.activeAt(publication.publishedAt())) {
                continue;
            }
            try {
                if (!verifySignature(key.publicKey(), publication.publicationFingerprint(),
                        authoritySignature.signature())) {
                    return result(VerificationStatus.SIGNATURE_INVALID,
                            "TRUST_AUTHORITY_SIGNATURE_INVALID", valid);
                }
                valid++;
            } catch (GeneralSecurityException | IllegalArgumentException failure) {
                return result(VerificationStatus.SIGNATURE_INVALID,
                        "TRUST_AUTHORITY_SIGNATURE_INVALID", valid);
            }
        }
        if (valid < signatureThreshold) {
            return result(VerificationStatus.QUORUM_NOT_MET, "TRUST_AUTHORITY_QUORUM_NOT_MET", valid);
        }
        return result(VerificationStatus.VERIFIED, "VERIFIED", valid);
    }

    @Override
    public Descriptor descriptor() {
        long activeAuthorityCount = authorities.values().stream()
                .filter(authority -> authority.activeAt(Instant.now()))
                .count();
        return new Descriptor("", activeAuthorityCount >= signatureThreshold,
                trustDomain, logId, authorities.size(), signatureThreshold,
                Map.of("algorithm", "Ed25519", "sourceType", "STATIC_EXTERNAL",
                        "privateMaterialPresent", false,
                        "activeAuthorityCount", activeAuthorityCount));
    }

    /**
     * Parses the bounded public-key JSON used by deployment configuration.
     *
     * @param objectMapper JSON decoder
     * @param trustDomain expected trust domain
     * @param logId expected log identity
     * @param threshold required signature count
     * @param authoritiesJson array of public Ed25519 authority keys
     * @return configured immutable trust store
     */
    public static ConfiguredEvidenceKeySetTrustStore fromJson(
            ObjectMapper objectMapper, String trustDomain, String logId,
            int threshold, String authoritiesJson) {
        try {
            JsonNode root = objectMapper.readTree(normalize(authoritiesJson));
            if (root == null || !root.isArray() || root.isEmpty() || root.size() > MAX_AUTHORITIES) {
                throw new IllegalArgumentException("Evidence trust authorities must be a non-empty JSON array");
            }
            List<AuthorityKey> keys = new ArrayList<>();
            for (JsonNode item : root) {
                String authorityId = requiredText(item, "authorityId");
                String encoded = requiredText(item, "publicKeyBase64");
                PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
                keys.add(new AuthorityKey(authorityId, key,
                        instant(item, "notBefore", Instant.MIN),
                        instant(item, "expiresAt", Instant.MAX),
                        !item.has("enabled") || item.path("enabled").asBoolean(false),
                        item.path("revoked").asBoolean(false)));
            }
            return new ConfiguredEvidenceKeySetTrustStore(objectMapper, trustDomain, logId,
                    threshold, keys);
        } catch (GeneralSecurityException | java.io.IOException | RuntimeException failure) {
            throw new IllegalArgumentException("Evidence trust authority configuration is invalid", failure);
        }
    }

    private Verification result(VerificationStatus status, String reason, int valid) {
        return new Verification(status, reason, valid, signatureThreshold);
    }

    private static boolean verifySignature(PublicKey key, String fingerprint, String encodedSignature)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(encodedSignature));
    }

    private static String requiredText(JsonNode value, String field) {
        String result = value.path(field).isTextual() ? normalize(value.path(field).textValue()) : "";
        if (result.isBlank() || result.length() > 16_384) {
            throw new IllegalArgumentException("Evidence trust authority field is invalid: " + field);
        }
        return result;
    }

    private static Instant instant(JsonNode value, String field, Instant fallback) {
        if (!value.has(field)) {
            return fallback;
        }
        return Instant.parse(requiredText(value, field));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
