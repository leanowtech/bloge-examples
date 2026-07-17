package com.leanowtech.bloge.gateway.testing.api;

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
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static M-of-N Ed25519 trust policy for external quarantine change authorizations.
 *
 * <p>Only public verification keys and exact accepted policy fingerprints are configured. An
 * enterprise may replace this implementation with a dynamic JWKS/KMS adapter while preserving the
 * same fail-closed interface.</p>
 */
public final class ConfiguredWorkerQuarantineChangeAuthorizationTrustStore
        implements WorkerQuarantineChangeAuthorizationTrustStore {

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_AUTHORIZATION_LIFETIME = Duration.ofHours(24);
    private static final int MAXIMUM_AUTHORITIES = 32;
    private static final int MAXIMUM_KEYS = 64;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /**
     * One externally provisioned public governance key.
     *
     * @param authorityId stable external approval authority
     * @param keyId stable rotation-aware key identity within the authority
     * @param publicKey Ed25519 public verification key
     * @param notBefore inclusive key activation time
     * @param expiresAt exclusive key expiry time
     * @param enabled administrative enablement flag
     * @param revoked compromise or withdrawal flag
     */
    public record AuthorityKey(
            String authorityId,
            String keyId,
            PublicKey publicKey,
            Instant notBefore,
            Instant expiresAt,
            boolean enabled,
            boolean revoked) {

        /** Validates key identity, type, and lifecycle ordering. */
        public AuthorityKey {
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            notBefore = notBefore == null ? Instant.MIN : notBefore;
            expiresAt = expiresAt == null ? Instant.MAX : expiresAt;
            String algorithm = publicKey == null ? "" : publicKey.getAlgorithm();
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(keyId).matches() || publicKey == null
                    || !(algorithm.equalsIgnoreCase("EdDSA")
                    || algorithm.equalsIgnoreCase("Ed25519"))
                    || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "External change-authorization authority key is invalid");
            }
        }

        /** @return true when the key may authorize a signature at the supplied time */
        public boolean activeAt(Instant signedAt) {
            return enabled && !revoked && signedAt != null && !signedAt.isBefore(notBefore)
                    && signedAt.isBefore(expiresAt);
        }

        private String indexKey() {
            return authorityId + '\u0000' + keyId;
        }
    }

    private final ObjectMapper objectMapper;
    private final String trustDomain;
    private final Set<String> acceptedPolicyFingerprints;
    private final int signatureThreshold;
    private final Map<String, AuthorityKey> keys;
    private final int authorityCount;

    /**
     * Creates an immutable deployment-owned governance trust policy.
     *
     * @param objectMapper canonical JSON mapper
     * @param trustDomain expected external governance trust domain
     * @param acceptedPolicyFingerprints exact accepted external policy revisions
     * @param signatureThreshold required distinct external authority signatures
     * @param authorityKeys public Ed25519 verification keys
     */
    public ConfiguredWorkerQuarantineChangeAuthorizationTrustStore(
            ObjectMapper objectMapper,
            String trustDomain,
            Set<String> acceptedPolicyFingerprints,
            int signatureThreshold,
            List<AuthorityKey> authorityKeys) {
        this.objectMapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules() : objectMapper;
        this.trustDomain = normalized(trustDomain);
        if (!IDENTIFIER.matcher(this.trustDomain).matches()) {
            throw new IllegalArgumentException(
                    "External change-authorization trust domain is invalid");
        }
        Set<String> policies = new HashSet<>();
        for (String policy : acceptedPolicyFingerprints == null
                ? Set.<String>of() : acceptedPolicyFingerprints) {
            String normalized = normalized(policy);
            if (!FINGERPRINT.matcher(normalized).matches() || !policies.add(normalized)) {
                throw new IllegalArgumentException(
                        "Accepted external change-authorization policy is invalid");
            }
        }
        if (policies.isEmpty() || policies.size() > 32) {
            throw new IllegalArgumentException(
                    "One through 32 external change-authorization policies are required");
        }
        this.acceptedPolicyFingerprints = Set.copyOf(policies);

        LinkedHashMap<String, AuthorityKey> indexed = new LinkedHashMap<>();
        Set<String> authorities = new HashSet<>();
        for (AuthorityKey key : authorityKeys == null ? List.<AuthorityKey>of() : authorityKeys) {
            if (key == null || indexed.putIfAbsent(key.indexKey(), key) != null) {
                throw new IllegalArgumentException(
                        "External change-authorization authority keys must be unique");
            }
            authorities.add(key.authorityId());
        }
        if (indexed.isEmpty() || indexed.size() > MAXIMUM_KEYS
                || authorities.size() > MAXIMUM_AUTHORITIES || signatureThreshold < 1
                || signatureThreshold > authorities.size()) {
            throw new IllegalArgumentException(
                    "External change-authorization trust policy is invalid");
        }
        this.keys = Map.copyOf(indexed);
        this.authorityCount = authorities.size();
        this.signatureThreshold = signatureThreshold;
    }

    @Override
    public Verification verify(
            WorkerQuarantineChangeAuthorization authorization,
            ExpectedBinding expected,
            Instant observedAt) {
        if (authorization == null || expected == null || observedAt == null) {
            return result(VerificationStatus.MATERIAL_INVALID,
                    "CHANGE_AUTHORIZATION_MATERIAL_INVALID", "", "", 0);
        }
        WorkerQuarantineChangeAuthorization.Material material = authorization.material();
        if (!trustDomain.equals(material.trustDomain())
                || !WorkerQuarantineChangeAuthorization.Material.DISCARD_ACTION
                .equals(material.action())
                || !expected.scopeFingerprint().equals(material.scopeFingerprint())
                || !expected.subjectFingerprint().equals(material.subjectFingerprint())) {
            return result(VerificationStatus.BINDING_MISMATCH,
                    "CHANGE_AUTHORIZATION_BINDING_MISMATCH", "", "", 0);
        }
        if (!acceptedPolicyFingerprints.contains(material.policyFingerprint())) {
            return result(VerificationStatus.POLICY_REJECTED,
                    "CHANGE_AUTHORIZATION_POLICY_REJECTED", "", "", 0);
        }
        if (!validTime(material, authorization.signatures(), observedAt)) {
            return result(VerificationStatus.TIME_INVALID,
                    "CHANGE_AUTHORIZATION_TIME_INVALID", "", "", 0);
        }
        if (!authorization.fingerprintVerified(objectMapper)) {
            return result(VerificationStatus.MATERIAL_INVALID,
                    "CHANGE_AUTHORIZATION_MATERIAL_INVALID", "", "", 0);
        }

        int valid = 0;
        Set<String> countedAuthorities = new HashSet<>();
        for (WorkerQuarantineChangeAuthorization.AuthoritySignature authoritySignature
                : authorization.signatures()) {
            AuthorityKey key = keys.get(authoritySignature.authorityId()
                    + '\u0000' + authoritySignature.keyId());
            if (key == null || !key.activeAt(authoritySignature.signedAt())) {
                continue;
            }
            try {
                if (!verifySignature(key.publicKey(), authorization.materialFingerprint(),
                        authoritySignature.signature())) {
                    return result(VerificationStatus.SIGNATURE_INVALID,
                            "CHANGE_AUTHORIZATION_SIGNATURE_INVALID", "", "", valid);
                }
                if (!countedAuthorities.add(authoritySignature.authorityId())) {
                    return result(VerificationStatus.SIGNATURE_INVALID,
                            "CHANGE_AUTHORIZATION_AUTHORITY_DUPLICATE", "", "", valid);
                }
                valid++;
            } catch (GeneralSecurityException | IllegalArgumentException invalid) {
                return result(VerificationStatus.SIGNATURE_INVALID,
                        "CHANGE_AUTHORIZATION_SIGNATURE_INVALID", "", "", valid);
            }
        }
        if (valid < signatureThreshold) {
            return result(VerificationStatus.QUORUM_NOT_MET,
                    "CHANGE_AUTHORIZATION_QUORUM_NOT_MET", "", "", valid);
        }
        return result(VerificationStatus.VERIFIED, "VERIFIED", material.authorizationId(),
                authorization.materialFingerprint(), valid);
    }

    @Override
    public Descriptor descriptor() {
        Instant now = Instant.now();
        long activeAuthorities = keys.values().stream()
                .filter(key -> key.activeAt(now))
                .map(AuthorityKey::authorityId)
                .distinct()
                .count();
        return new Descriptor("", activeAuthorities >= signatureThreshold,
                trustDomain, authorityCount, keys.size(), signatureThreshold,
                acceptedPolicyFingerprints.size(), Map.of(
                "algorithm", "Ed25519",
                "sourceType", "STATIC_EXTERNAL",
                "privateMaterialPresent", false,
                "activeAuthorityCount", activeAuthorities,
                "maximumAuthorizationLifetimeSeconds",
                MAXIMUM_AUTHORIZATION_LIFETIME.toSeconds()));
    }

    /**
     * Parses bounded deployment configuration containing public keys only.
     *
     * @param objectMapper JSON decoder and canonical mapper
     * @param trustDomain expected external governance trust domain
     * @param acceptedPolicies comma-separated exact policy fingerprints
     * @param threshold required distinct authority signatures
     * @param authorityKeysJson bounded JSON array of public Ed25519 authority keys
     * @return immutable configured trust store
     */
    public static ConfiguredWorkerQuarantineChangeAuthorizationTrustStore fromJson(
            ObjectMapper objectMapper,
            String trustDomain,
            String acceptedPolicies,
            int threshold,
            String authorityKeysJson) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        try {
            JsonNode root = objectMapper.readTree(normalized(authorityKeysJson));
            if (root == null || !root.isArray() || root.isEmpty()
                    || root.size() > MAXIMUM_KEYS) {
                throw new IllegalArgumentException(
                        "External change-authorization keys must be a non-empty JSON array");
            }
            List<AuthorityKey> parsed = new ArrayList<>();
            for (JsonNode item : root) {
                String authorityId = requiredText(item, "authorityId");
                String keyId = requiredText(item, "keyId");
                String encoded = requiredText(item, "publicKeyBase64");
                PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
                parsed.add(new AuthorityKey(authorityId, keyId, key,
                        instant(item, "notBefore", Instant.MIN),
                        instant(item, "expiresAt", Instant.MAX),
                        !item.has("enabled") || item.path("enabled").asBoolean(false),
                        item.path("revoked").asBoolean(false)));
            }
            Set<String> policies = new HashSet<>();
            for (String policy : normalized(acceptedPolicies).split(",", -1)) {
                if (!normalized(policy).isBlank()) {
                    policies.add(normalized(policy));
                }
            }
            return new ConfiguredWorkerQuarantineChangeAuthorizationTrustStore(
                    objectMapper, trustDomain, policies, threshold, parsed);
        } catch (GeneralSecurityException | java.io.IOException | RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "External change-authorization trust configuration is invalid", invalid);
        }
    }

    private static boolean validTime(
            WorkerQuarantineChangeAuthorization.Material material,
            List<WorkerQuarantineChangeAuthorization.AuthoritySignature> signatures,
            Instant observedAt) {
        Duration lifetime = Duration.between(material.issuedAt(), material.expiresAt());
        if (lifetime.isNegative() || lifetime.isZero()
                || lifetime.compareTo(MAXIMUM_AUTHORIZATION_LIFETIME) > 0
                || material.issuedAt().isAfter(observedAt.plus(CLOCK_SKEW))
                || observedAt.isBefore(material.notBefore())
                || !observedAt.isBefore(material.expiresAt())) {
            return false;
        }
        return signatures.stream().allMatch(signature ->
                !signature.signedAt().isBefore(material.issuedAt().minus(CLOCK_SKEW))
                && signature.signedAt().isBefore(material.expiresAt())
                && !signature.signedAt().isAfter(observedAt.plus(CLOCK_SKEW)));
    }

    private Verification result(
            VerificationStatus status,
            String reason,
            String authorizationId,
            String materialFingerprint,
            int validSignatures) {
        return new Verification(status, reason, authorizationId, materialFingerprint,
                validSignatures, signatureThreshold);
    }

    private static boolean verifySignature(
            PublicKey publicKey,
            String materialFingerprint,
            String encodedSignature) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(encodedSignature));
    }

    private static String requiredText(JsonNode item, String field) {
        String value = item.path(field).isTextual()
                ? normalized(item.path(field).textValue()) : "";
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(
                    "External change-authorization key field is invalid: " + field);
        }
        return value;
    }

    private static Instant instant(JsonNode item, String field, Instant fallback) {
        if (!item.has(field)) {
            return fallback;
        }
        if (!item.path(field).isTextual()) {
            throw new IllegalArgumentException(
                    "External change-authorization key time is invalid: " + field);
        }
        return Instant.parse(item.path(field).textValue());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
