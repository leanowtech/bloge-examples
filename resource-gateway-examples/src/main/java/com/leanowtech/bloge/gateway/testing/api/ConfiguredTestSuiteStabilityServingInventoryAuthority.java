package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static M-of-N Ed25519 authority for deployment-signed suite-stability serving inventory.
 *
 * <p>The signed inventory is immutable for one Resource Gateway process. Its validity deadline is
 * re-evaluated on every observation, so expiration closes admission without a restart. Only public
 * keys are accepted. Dynamic inventory refresh is deliberately a separate future adapter behind
 * {@link TestSuiteStabilityServingInventoryAuthority}.</p>
 */
public final class ConfiguredTestSuiteStabilityServingInventoryAuthority
        implements TestSuiteStabilityServingInventoryAuthority {

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_INVENTORY_LIFETIME = Duration.ofDays(30);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<String> KEY_FIELDS = Set.of(
            "authorityId", "keyId", "publicKeyBase64", "notBefore", "expiresAt",
            "enabled", "revoked");

    private final Clock clock;
    private final TestSuiteStabilityServingInventory.Material material;
    private final String materialFingerprint;
    private final int validSignatureCount;
    private final int signatureThreshold;

    /**
     * One deployment-owned public verification key.
     *
     * @param authorityId stable independent inventory authority
     * @param keyId rotation-aware key identity
     * @param publicKey public Ed25519 verification key
     * @param notBefore inclusive signing activation time
     * @param expiresAt exclusive signing expiry time
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

        /** Validates public-key identity and lifecycle ordering. */
        public AuthorityKey {
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            notBefore = notBefore == null ? Instant.MIN : notBefore;
            expiresAt = expiresAt == null ? Instant.MAX : expiresAt;
            String algorithm = publicKey == null ? "" : publicKey.getAlgorithm();
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(keyId).matches() || publicKey == null
                    || !("EdDSA".equalsIgnoreCase(algorithm)
                    || "Ed25519".equalsIgnoreCase(algorithm))
                    || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Serving-inventory authority key is invalid");
            }
        }

        /** @return true when this key may verify a signature created at {@code signedAt} */
        public boolean activeAt(Instant signedAt) {
            return enabled && !revoked && signedAt != null
                    && !signedAt.isBefore(notBefore) && signedAt.isBefore(expiresAt);
        }

        private String indexKey() {
            return authorityId + '\u0000' + keyId;
        }
    }

    /**
     * Local facts that an externally signed inventory must bind exactly.
     *
     * @param scopeId stable serving-fleet scope
     * @param cohortId immutable deployment generation
     * @param artifactFingerprint exact local image or JAR SHA-256
     * @param protocolVersion exact Resource Gateway integration protocol
     * @param localInstanceId serving slot represented by this process
     */
    public record ExpectedBinding(
            String scopeId,
            String cohortId,
            String artifactFingerprint,
            String protocolVersion,
            String localInstanceId) {

        /** Rejects malformed local binding before any signature verification. */
        public ExpectedBinding {
            scopeId = normalized(scopeId);
            cohortId = normalized(cohortId);
            artifactFingerprint = normalized(artifactFingerprint);
            protocolVersion = normalized(protocolVersion);
            localInstanceId = normalized(localInstanceId);
            if (!IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(cohortId).matches()
                    || !FINGERPRINT.matcher(artifactFingerprint).matches()
                    || !IDENTIFIER.matcher(protocolVersion).matches()
                    || !IDENTIFIER.matcher(localInstanceId).matches()) {
                throw new IllegalArgumentException(
                        "Serving-inventory expected binding is invalid");
            }
        }
    }

    /**
     * Creates and immediately verifies one immutable signed inventory.
     *
     * @param objectMapper canonical material mapper
     * @param clock current freshness authority
     * @param trustDomain exact independent trust domain
     * @param acceptedPolicyFingerprints accepted external policy revisions
     * @param signatureThreshold required distinct authority signatures
     * @param authorityKeys public verification keys
     * @param inventory untrusted signed inventory envelope
     * @param expectedBinding exact local deployment binding
     */
    public ConfiguredTestSuiteStabilityServingInventoryAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            String trustDomain,
            Set<String> acceptedPolicyFingerprints,
            int signatureThreshold,
            List<AuthorityKey> authorityKeys,
            TestSuiteStabilityServingInventory inventory,
            ExpectedBinding expectedBinding) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        String expectedDomain = normalized(trustDomain);
        if (!IDENTIFIER.matcher(expectedDomain).matches()) {
            throw new IllegalArgumentException("Serving-inventory trust domain is invalid");
        }
        Set<String> policies = acceptedPolicies(acceptedPolicyFingerprints);
        Map<String, AuthorityKey> keys = indexedKeys(authorityKeys, signatureThreshold);
        this.signatureThreshold = signatureThreshold;
        TestSuiteStabilityServingInventory envelope = Objects.requireNonNull(
                inventory, "inventory");
        ExpectedBinding binding = Objects.requireNonNull(expectedBinding, "expectedBinding");
        this.material = envelope.material();
        this.materialFingerprint = envelope.materialFingerprint();
        this.validSignatureCount = verifyEnvelope(
                mapper, expectedDomain, policies, keys, signatureThreshold,
                envelope, binding, clock.instant());
    }

    /** Returns the verified inventory while re-evaluating its hard validity deadline. */
    @Override
    public Observation observation() {
        Instant now = clock.instant();
        String status;
        boolean available;
        if (now.isBefore(material.notBefore())) {
            status = "NOT_YET_VALID";
            available = false;
        } else if (!now.isBefore(material.expiresAt())) {
            status = "EXPIRED";
            available = false;
        } else {
            status = "VERIFIED";
            available = true;
        }
        return new Observation(Observation.SCHEMA_VERSION, true, true, available,
                status, "STATIC_SIGNED_ED25519_M_OF_N", material.revision(),
                materialFingerprint, material.revision(),
                materialFingerprint, material.policyFingerprint(),
                material.expectedInstanceIds(), material.expiresAt(),
                validSignatureCount, signatureThreshold);
    }

    /**
     * Parses strict public-key and signed-inventory JSON deployment configuration.
     *
     * @param objectMapper application JSON mapper
     * @param trustDomain exact independent trust domain
     * @param acceptedPolicies comma-separated policy fingerprints
     * @param signatureThreshold distinct authority threshold
     * @param authorityKeysJson public Ed25519 key array
     * @param inventoryJson signed inventory envelope
     * @param expectedBinding exact local deployment binding
     * @return verified static serving-inventory authority
     */
    public static ConfiguredTestSuiteStabilityServingInventoryAuthority fromJson(
            ObjectMapper objectMapper,
            String trustDomain,
            String acceptedPolicies,
            int signatureThreshold,
            String authorityKeysJson,
            String inventoryJson,
            ExpectedBinding expectedBinding) {
        return fromJson(objectMapper, Clock.systemUTC(), trustDomain, acceptedPolicies,
                signatureThreshold, authorityKeysJson, inventoryJson, expectedBinding);
    }

    static ConfiguredTestSuiteStabilityServingInventoryAuthority fromJson(
            ObjectMapper objectMapper,
            Clock clock,
            String trustDomain,
            String acceptedPolicies,
            int signatureThreshold,
            String authorityKeysJson,
            String inventoryJson,
            ExpectedBinding expectedBinding) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        try {
            ObjectMapper strict = objectMapper.copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            List<AuthorityKey> keys = parseKeys(strict, authorityKeysJson);
            TestSuiteStabilityServingInventory inventory = strict.readValue(
                    normalized(inventoryJson), TestSuiteStabilityServingInventory.class);
            return new ConfiguredTestSuiteStabilityServingInventoryAuthority(
                    objectMapper, clock, trustDomain, parsePolicies(acceptedPolicies),
                    signatureThreshold, keys, inventory, expectedBinding);
        } catch (GeneralSecurityException | java.io.IOException | RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Serving-inventory trust configuration is invalid", invalid);
        }
    }

    private static int verifyEnvelope(
            ObjectMapper objectMapper,
            String trustDomain,
            Set<String> acceptedPolicies,
            Map<String, AuthorityKey> keys,
            int signatureThreshold,
            TestSuiteStabilityServingInventory inventory,
            ExpectedBinding expected,
            Instant observedAt) {
        TestSuiteStabilityServingInventory.Material material = inventory.material();
        if (!trustDomain.equals(material.trustDomain())
                || !expected.scopeId().equals(material.scopeId())
                || !expected.cohortId().equals(material.cohortId())
                || !expected.artifactFingerprint().equals(material.artifactFingerprint())
                || !expected.protocolVersion().equals(material.protocolVersion())
                || !material.expectedInstanceIds().contains(expected.localInstanceId())
                || !acceptedPolicies.contains(material.policyFingerprint())) {
            throw new IllegalArgumentException(
                    "Serving inventory does not match the local deployment binding");
        }
        Duration lifetime = Duration.between(material.issuedAt(), material.expiresAt());
        if (lifetime.isZero() || lifetime.isNegative()
                || lifetime.compareTo(MAXIMUM_INVENTORY_LIFETIME) > 0
                || material.issuedAt().isAfter(observedAt.plus(CLOCK_SKEW))
                || observedAt.isBefore(material.notBefore())
                || !observedAt.isBefore(material.expiresAt())
                || !inventory.fingerprintVerified(objectMapper)) {
            throw new IllegalArgumentException(
                    "Serving inventory freshness or material identity is invalid");
        }
        return verifyDetachedSignatures(keys, signatureThreshold,
                inventory.signatures(), inventory.materialFingerprint(),
                material.issuedAt(), material.expiresAt(), observedAt,
                "Serving inventory");
    }

    static int verifyDetachedSignatures(
            Map<String, AuthorityKey> keys,
            int signatureThreshold,
            List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures,
            String materialFingerprint,
            Instant issuedAt,
            Instant expiresAt,
            Instant observedAt,
            String label) {
        int valid = 0;
        Set<String> authorities = new HashSet<>();
        for (TestSuiteStabilityServingInventory.AuthoritySignature signed
                : signatures == null
                ? List.<TestSuiteStabilityServingInventory.AuthoritySignature>of()
                : signatures) {
            if (!authorities.add(signed.authorityId())
                    || signed.signedAt().isBefore(issuedAt.minus(CLOCK_SKEW))
                    || !signed.signedAt().isBefore(expiresAt)
                    || signed.signedAt().isAfter(observedAt.plus(CLOCK_SKEW))) {
                throw new IllegalArgumentException(label + " signature time is invalid");
            }
            AuthorityKey key = keys.get(signed.authorityId() + '\u0000' + signed.keyId());
            if (key == null || !key.activeAt(signed.signedAt())) {
                continue;
            }
            try {
                if (!verifySignature(key.publicKey(), materialFingerprint,
                        signed.signature())) {
                    throw new IllegalArgumentException(
                            label + " signature verification failed");
                }
                valid++;
            } catch (GeneralSecurityException invalid) {
                throw new IllegalArgumentException(
                        label + " signature verification failed", invalid);
            }
        }
        if (valid < signatureThreshold) {
            throw new IllegalArgumentException(label + " authority threshold is not met");
        }
        return valid;
    }

    static Map<String, AuthorityKey> indexedKeys(
            List<AuthorityKey> authorityKeys, int threshold) {
        Map<String, AuthorityKey> indexed = new HashMap<>();
        Set<String> authorities = new HashSet<>();
        for (AuthorityKey key : authorityKeys == null ? List.<AuthorityKey>of() : authorityKeys) {
            if (key == null || indexed.putIfAbsent(key.indexKey(), key) != null) {
                throw new IllegalArgumentException(
                        "Serving-inventory authority keys must be unique");
            }
            authorities.add(key.authorityId());
        }
        if (indexed.isEmpty() || indexed.size() > 64 || authorities.size() > 32
                || threshold < 1 || threshold > authorities.size()) {
            throw new IllegalArgumentException(
                    "Serving-inventory authority policy is invalid");
        }
        return Map.copyOf(indexed);
    }

    static Set<String> acceptedPolicies(Set<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            String normalized = normalized(value);
            if (!FINGERPRINT.matcher(normalized).matches() || !result.add(normalized)) {
                throw new IllegalArgumentException(
                        "Serving-inventory accepted policy is invalid");
            }
        }
        if (result.isEmpty() || result.size() > 32) {
            throw new IllegalArgumentException(
                    "One through 32 serving-inventory policies are required");
        }
        return Set.copyOf(result);
    }

    static Set<String> parsePolicies(String values) {
        Set<String> result = new HashSet<>();
        for (String value : normalized(values).split(",", -1)) {
            String normalized = normalized(value);
            if (normalized.isBlank() || !result.add(normalized)) {
                throw new IllegalArgumentException(
                        "Serving-inventory policy list is invalid");
            }
        }
        return result;
    }

    static List<AuthorityKey> parseKeys(
            ObjectMapper objectMapper, String authorityKeysJson)
            throws GeneralSecurityException, java.io.IOException {
        JsonNode root = objectMapper.readTree(normalized(authorityKeysJson));
        if (root == null || !root.isArray() || root.isEmpty() || root.size() > 64) {
            throw new IllegalArgumentException(
                    "Serving-inventory keys must be a non-empty bounded array");
        }
        List<AuthorityKey> keys = new ArrayList<>();
        for (JsonNode item : root) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("Serving-inventory key must be an object");
            }
            Set<String> fields = new HashSet<>();
            item.fieldNames().forEachRemaining(fields::add);
            if (!KEY_FIELDS.containsAll(fields)) {
                throw new IllegalArgumentException(
                        "Serving-inventory key contains an unknown field");
            }
            String authorityId = requiredText(item, "authorityId");
            String keyId = requiredText(item, "keyId");
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(
                            requiredText(item, "publicKeyBase64"))));
            keys.add(new AuthorityKey(authorityId, keyId, publicKey,
                    instant(item, "notBefore", Instant.MIN),
                    instant(item, "expiresAt", Instant.MAX),
                    booleanValue(item, "enabled", true),
                    booleanValue(item, "revoked", false)));
        }
        return List.copyOf(keys);
    }

    private static String requiredText(JsonNode item, String field) {
        JsonNode value = item.path(field);
        String result = value.isTextual() ? normalized(value.textValue()) : "";
        if (result.isBlank() || result.length() > 16_384) {
            throw new IllegalArgumentException(
                    "Serving-inventory key field is invalid: " + field);
        }
        return result;
    }

    private static Instant instant(JsonNode item, String field, Instant fallback) {
        if (!item.has(field)) {
            return fallback;
        }
        if (!item.path(field).isTextual()) {
            throw new IllegalArgumentException(
                    "Serving-inventory key time is invalid: " + field);
        }
        return Instant.parse(item.path(field).textValue());
    }

    private static boolean booleanValue(JsonNode item, String field, boolean fallback) {
        if (!item.has(field)) {
            return fallback;
        }
        if (!item.path(field).isBoolean()) {
            throw new IllegalArgumentException(
                    "Serving-inventory key flag is invalid: " + field);
        }
        return item.path(field).booleanValue();
    }

    private static boolean verifySignature(
            PublicKey publicKey, String fingerprint, String encodedSignature)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(encodedSignature));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
