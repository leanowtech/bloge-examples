package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
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
 * Bounded static Ed25519 trust policy for suite-stability current-authority decisions.
 *
 * <p>The configuration accepts X.509-encoded public keys only. Key lifecycle, expected authority,
 * response lifetime, local clock skew and minimum remaining validity are all checked before a
 * decision can reach the worker. A deployment needing dynamic rotation may replace this bean with
 * another {@link TestSuiteStabilityAuthorityTrustStore} implementation.</p>
 */
public final class ConfiguredTestSuiteStabilityAuthorityTrustStore
        implements TestSuiteStabilityAuthorityTrustStore {

    private static final int MAXIMUM_KEYS = 64;
    private static final Set<String> KEY_FIELDS = Set.of(
            "keyId", "algorithm", "publicKeyBase64", "notBefore", "expiresAt",
            "enabled", "revoked");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    /**
     * One rotation-aware public verification key.
     *
     * @param keyId stable key identity within the configured authority
     * @param publicKey Ed25519 public key
     * @param notBefore inclusive key activation time
     * @param expiresAt exclusive key expiry time
     * @param enabled administrative enablement
     * @param revoked compromise or withdrawal marker
     */
    public record AuthorityKey(
            String keyId,
            PublicKey publicKey,
            Instant notBefore,
            Instant expiresAt,
            boolean enabled,
            boolean revoked) {

        /** Validates key identity, algorithm and lifecycle ordering. */
        public AuthorityKey {
            keyId = normalized(keyId);
            notBefore = notBefore == null ? Instant.MIN : notBefore;
            expiresAt = expiresAt == null ? Instant.MAX : expiresAt;
            String algorithm = publicKey == null ? "" : publicKey.getAlgorithm();
            if (!IDENTIFIER.matcher(keyId).matches() || publicKey == null
                    || !("Ed25519".equalsIgnoreCase(algorithm)
                    || "EdDSA".equalsIgnoreCase(algorithm))
                    || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException("Invalid stability authority public key");
            }
        }

        /** @return whether this key may verify a decision issued at the supplied time */
        public boolean activeAt(Instant issuedAt) {
            return enabled && !revoked && issuedAt != null && !issuedAt.isBefore(notBefore)
                    && issuedAt.isBefore(expiresAt);
        }
    }

    private final ObjectMapper objectMapper;
    private final String expectedAuthorityId;
    private final Duration maximumDecisionLifetime;
    private final Duration clockSkew;
    private final Duration minimumRemainingValidity;
    private final Map<String, AuthorityKey> keys;

    /**
     * Creates an immutable static current-authority trust policy.
     *
     * @param objectMapper canonical JSON mapper
     * @param expectedAuthorityId exact authority identity accepted by this deployment
     * @param maximumDecisionLifetime maximum signed decision lifetime
     * @param clockSkew maximum tolerated caller/authority clock skew
     * @param minimumRemainingValidity minimum validity required at local verification time
     * @param authorityKeys bounded Ed25519 public-key inventory
     */
    public ConfiguredTestSuiteStabilityAuthorityTrustStore(
            ObjectMapper objectMapper,
            String expectedAuthorityId,
            Duration maximumDecisionLifetime,
            Duration clockSkew,
            Duration minimumRemainingValidity,
            List<AuthorityKey> authorityKeys) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.expectedAuthorityId = normalized(expectedAuthorityId);
        this.maximumDecisionLifetime = boundedDuration(maximumDecisionLifetime,
                Duration.ofSeconds(1), Duration.ofMinutes(5), "maximum decision lifetime");
        this.clockSkew = boundedDuration(clockSkew, Duration.ZERO, Duration.ofMinutes(5),
                "clock skew");
        this.minimumRemainingValidity = boundedDuration(minimumRemainingValidity,
                Duration.ZERO, Duration.ofSeconds(30), "minimum remaining validity");
        if (!IDENTIFIER.matcher(this.expectedAuthorityId).matches()
                || this.minimumRemainingValidity.compareTo(this.maximumDecisionLifetime) >= 0) {
            throw new IllegalArgumentException("Invalid stability authority trust policy");
        }
        LinkedHashMap<String, AuthorityKey> indexed = new LinkedHashMap<>();
        for (AuthorityKey key : authorityKeys == null ? List.<AuthorityKey>of() : authorityKeys) {
            if (key == null || indexed.putIfAbsent(key.keyId(), key) != null) {
                throw new IllegalArgumentException(
                        "Stability authority public keys must have unique key ids");
            }
        }
        if (indexed.isEmpty() || indexed.size() > MAXIMUM_KEYS) {
            throw new IllegalArgumentException(
                    "One through 64 stability authority public keys are required");
        }
        this.keys = Map.copyOf(indexed);
    }

    @Override
    public Verification verify(
            TestSuiteStabilityAuthorityResponse response,
            TestSuiteStabilityAuthorityRequest request,
            Instant observedAt) {
        if (response == null || request == null || observedAt == null) {
            return result(VerificationStatus.MATERIAL_INVALID,
                    "RG.TEST.STABILITY_JOB_AUTHORITY_MATERIAL_INVALID");
        }
        if (!response.requestId().equals(request.requestId())
                || !response.challenge().equals(request.challenge())
                || !response.jobId().equals(request.jobId())
                || !response.authorizationRequestFingerprint()
                .equals(request.authorizationRequestFingerprint())
                || !response.principalFingerprint().equals(request.principalFingerprint())) {
            return result(VerificationStatus.BINDING_MISMATCH,
                    "RG.TEST.STABILITY_JOB_AUTHORITY_BINDING_MISMATCH");
        }
        if (!expectedAuthorityId.equals(response.authorityId())) {
            return result(VerificationStatus.AUTHORITY_MISMATCH,
                    "RG.TEST.STABILITY_JOB_AUTHORITY_ID_MISMATCH");
        }
        if (!request.fingerprintsVerified(objectMapper)
                || !response.fingerprintVerified(objectMapper)) {
            return result(VerificationStatus.MATERIAL_INVALID,
                    "RG.TEST.STABILITY_JOB_AUTHORITY_MATERIAL_INVALID");
        }
        if (!validTime(response, request, observedAt)) {
            return result(VerificationStatus.TIME_INVALID,
                    "RG.TEST.STABILITY_JOB_AUTHORITY_TIME_INVALID");
        }
        AuthorityKey key = keys.get(response.signature().keyId());
        if (key == null || !key.activeAt(response.issuedAt()) || !key.activeAt(observedAt)
                || response.expiresAt().isAfter(key.expiresAt())) {
            return result(VerificationStatus.KEY_UNAVAILABLE,
                    "RG.TEST.STABILITY_JOB_AUTHORITY_KEY_UNAVAILABLE");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key.publicKey());
            verifier.update(response.materialFingerprint().getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(response.signature().signature()))) {
                return result(VerificationStatus.SIGNATURE_INVALID,
                        "RG.TEST.STABILITY_JOB_AUTHORITY_SIGNATURE_INVALID");
            }
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            return result(VerificationStatus.SIGNATURE_INVALID,
                    "RG.TEST.STABILITY_JOB_AUTHORITY_SIGNATURE_INVALID");
        }
        return result(VerificationStatus.VERIFIED, "VERIFIED");
    }

    @Override
    public Descriptor descriptor() {
        Instant now = Instant.now();
        long activeKeys = keys.values().stream().filter(key -> key.activeAt(now)).count();
        return new Descriptor("", activeKeys > 0, "STATIC_ED25519", expectedAuthorityId,
                keys.size(), Map.of(
                "algorithm", "Ed25519",
                "signedDecisions", true,
                "challengeBound", true,
                "privateMaterialPresent", false,
                "activeKeyCount", activeKeys,
                "maximumDecisionLifetimeSeconds", maximumDecisionLifetime.toSeconds(),
                "clockSkewSeconds", clockSkew.toSeconds(),
                "minimumRemainingValidityMillis", minimumRemainingValidity.toMillis()));
    }

    /**
     * Parses a strict bounded JSON array containing public verification keys only.
     *
     * @param objectMapper application JSON mapper
     * @param expectedAuthorityId exact accepted authority identity
     * @param maximumDecisionLifetime maximum signed decision lifetime
     * @param clockSkew tolerated local/authority clock skew
     * @param minimumRemainingValidity minimum remaining decision validity
     * @param authorityKeysJson strict public-key JSON array
     * @return immutable static trust policy
     */
    public static ConfiguredTestSuiteStabilityAuthorityTrustStore fromJson(
            ObjectMapper objectMapper,
            String expectedAuthorityId,
            Duration maximumDecisionLifetime,
            Duration clockSkew,
            Duration minimumRemainingValidity,
            String authorityKeysJson) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        ObjectMapper strictMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        try {
            String encoded = normalized(authorityKeysJson);
            if (encoded.length() > 256 * 1024) {
                throw new IllegalArgumentException("Authority key configuration is too large");
            }
            JsonNode root = strictMapper.readTree(encoded);
            if (root == null || !root.isArray() || root.isEmpty()
                    || root.size() > MAXIMUM_KEYS) {
                throw new IllegalArgumentException(
                        "Authority keys must be a non-empty bounded JSON array");
            }
            List<AuthorityKey> parsed = new ArrayList<>();
            for (JsonNode item : root) {
                requireExactFields(item);
                if (!"Ed25519".equals(requiredText(item, "algorithm"))) {
                    throw new IllegalArgumentException("Authority key algorithm is invalid");
                }
                byte[] encodedKey = Base64.getDecoder().decode(
                        requiredText(item, "publicKeyBase64"));
                PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                        new X509EncodedKeySpec(encodedKey));
                parsed.add(new AuthorityKey(requiredText(item, "keyId"), key,
                        instant(item, "notBefore", Instant.MIN),
                        instant(item, "expiresAt", Instant.MAX),
                        !item.has("enabled") || booleanValue(item, "enabled"),
                        item.has("revoked") && booleanValue(item, "revoked")));
            }
            return new ConfiguredTestSuiteStabilityAuthorityTrustStore(
                    objectMapper, expectedAuthorityId, maximumDecisionLifetime, clockSkew,
                    minimumRemainingValidity, parsed);
        } catch (GeneralSecurityException | java.io.IOException | RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Stability authority trust configuration is invalid", invalid);
        }
    }

    private boolean validTime(
            TestSuiteStabilityAuthorityResponse response,
            TestSuiteStabilityAuthorityRequest request,
            Instant observedAt) {
        Duration lifetime = Duration.between(response.issuedAt(), response.expiresAt());
        return !lifetime.isZero() && !lifetime.isNegative()
                && lifetime.compareTo(maximumDecisionLifetime) <= 0
                && !response.issuedAt().isAfter(observedAt.plus(clockSkew))
                && !response.issuedAt().isBefore(request.requestedAt().minus(clockSkew))
                && !observedAt.plus(minimumRemainingValidity).isAfter(response.expiresAt());
    }

    private static Verification result(VerificationStatus status, String code) {
        return new Verification(status, code);
    }

    private static void requireExactFields(JsonNode item) {
        if (item == null || !item.isObject()) {
            throw new IllegalArgumentException("Authority key entry must be an object");
        }
        Set<String> names = new HashSet<>();
        item.fieldNames().forEachRemaining(names::add);
        if (!KEY_FIELDS.containsAll(names)) {
            throw new IllegalArgumentException("Authority key entry contains an unknown field");
        }
    }

    private static String requiredText(JsonNode item, String field) {
        JsonNode value = item.get(field);
        String result = value != null && value.isTextual() ? normalized(value.textValue()) : "";
        if (result.isBlank() || result.length() > 16_384) {
            throw new IllegalArgumentException("Authority key field is invalid: " + field);
        }
        return result;
    }

    private static boolean booleanValue(JsonNode item, String field) {
        if (!item.path(field).isBoolean()) {
            throw new IllegalArgumentException("Authority key flag is invalid: " + field);
        }
        return item.path(field).booleanValue();
    }

    private static Instant instant(JsonNode item, String field, Instant fallback) {
        if (!item.has(field)) {
            return fallback;
        }
        return Instant.parse(requiredText(item, field));
    }

    private static Duration boundedDuration(
            Duration value, Duration minimum, Duration maximum, String label) {
        Duration duration = Objects.requireNonNull(value, label);
        if (duration.compareTo(minimum) < 0 || duration.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Invalid stability authority " + label);
        }
        return duration;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
