package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static M-of-N Ed25519 authority for a deployment-signed test-secret serving inventory.
 *
 * <p>The signed envelope is immutable for one process, while its deadline is evaluated on every
 * observation. Expiration therefore closes secret resolution without a restart. The verifier
 * accepts public keys only and binds the exact test-secret authority identity in addition to the
 * deployment topology.</p>
 */
public final class ConfiguredTestSecretAuthorityServingInventoryAuthority
        implements TestSecretAuthorityServingInventoryAuthority {

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_INVENTORY_LIFETIME = Duration.ofDays(30);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final Clock clock;
    private final TestSecretAuthorityServingInventory.Material material;
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
            var validated = new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                    authorityId, keyId, publicKey, notBefore, expiresAt, enabled, revoked);
            authorityId = validated.authorityId();
            keyId = validated.keyId();
            publicKey = validated.publicKey();
            notBefore = validated.notBefore();
            expiresAt = validated.expiresAt();
        }

        private ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey delegate() {
            return new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                    authorityId, keyId, publicKey, notBefore, expiresAt, enabled, revoked);
        }
    }

    /**
     * Local facts that the deployment-signed inventory must bind exactly.
     *
     * @param scopeId stable serving-fleet scope
     * @param cohortId immutable deployment generation
     * @param artifactFingerprint exact local image or application SHA-256
     * @param protocolVersion exact signed-response protocol generation
     * @param authorityId exact test-secret authority identity
     * @param localInstanceId serving slot represented by this process
     */
    public record ExpectedBinding(
            String scopeId,
            String cohortId,
            String artifactFingerprint,
            String protocolVersion,
            String authorityId,
            String localInstanceId) {

        /** Rejects malformed local binding before any signature verification. */
        public ExpectedBinding {
            scopeId = normalized(scopeId);
            cohortId = normalized(cohortId);
            artifactFingerprint = normalized(artifactFingerprint);
            protocolVersion = normalized(protocolVersion);
            authorityId = normalized(authorityId);
            localInstanceId = normalized(localInstanceId);
            if (!IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(cohortId).matches()
                    || !FINGERPRINT.matcher(artifactFingerprint).matches()
                    || !IDENTIFIER.matcher(protocolVersion).matches()
                    || !IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(localInstanceId).matches()) {
                throw new IllegalArgumentException(
                        "Test-secret serving inventory expected binding is invalid");
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
     * @param expectedBinding exact local deployment and authority binding
     */
    public ConfiguredTestSecretAuthorityServingInventoryAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            String trustDomain,
            Set<String> acceptedPolicyFingerprints,
            int signatureThreshold,
            List<AuthorityKey> authorityKeys,
            TestSecretAuthorityServingInventory inventory,
            ExpectedBinding expectedBinding) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        String expectedDomain = normalized(trustDomain);
        if (!IDENTIFIER.matcher(expectedDomain).matches()) {
            throw new IllegalArgumentException(
                    "Test-secret serving inventory trust domain is invalid");
        }
        Set<String> policies =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.acceptedPolicies(
                        acceptedPolicyFingerprints);
        Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> keys =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                        authorityKeys == null ? List.of()
                                : authorityKeys.stream().map(AuthorityKey::delegate).toList(),
                        signatureThreshold);
        this.signatureThreshold = signatureThreshold;
        TestSecretAuthorityServingInventory envelope = Objects.requireNonNull(
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
                materialFingerprint, material.revision(), materialFingerprint,
                material.policyFingerprint(), material.expectedInstanceIds(),
                material.expiresAt(), validSignatureCount, signatureThreshold);
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
     * @param expectedBinding exact local deployment and authority binding
     * @return verified static serving-inventory authority
     */
    public static ConfiguredTestSecretAuthorityServingInventoryAuthority fromJson(
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

    static ConfiguredTestSecretAuthorityServingInventoryAuthority fromJson(
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
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> parsedKeys =
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parseKeys(
                            strict, authorityKeysJson);
            List<AuthorityKey> keys = parsedKeys.stream().map(key -> new AuthorityKey(
                    key.authorityId(), key.keyId(), key.publicKey(), key.notBefore(),
                    key.expiresAt(), key.enabled(), key.revoked())).toList();
            TestSecretAuthorityServingInventory inventory = strict.readValue(
                    normalized(inventoryJson), TestSecretAuthorityServingInventory.class);
            return new ConfiguredTestSecretAuthorityServingInventoryAuthority(
                    objectMapper, clock, trustDomain,
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parsePolicies(
                            acceptedPolicies),
                    signatureThreshold, keys, inventory, expectedBinding);
        } catch (java.io.IOException | java.security.GeneralSecurityException
                 | RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Test-secret serving inventory trust configuration is invalid", invalid);
        }
    }

    private static int verifyEnvelope(
            ObjectMapper objectMapper,
            String trustDomain,
            Set<String> acceptedPolicies,
            Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> keys,
            int signatureThreshold,
            TestSecretAuthorityServingInventory inventory,
            ExpectedBinding expected,
            Instant observedAt) {
        TestSecretAuthorityServingInventory.Material material = inventory.material();
        if (!trustDomain.equals(material.trustDomain())
                || !expected.scopeId().equals(material.scopeId())
                || !expected.cohortId().equals(material.cohortId())
                || !expected.artifactFingerprint().equals(material.artifactFingerprint())
                || !expected.protocolVersion().equals(material.protocolVersion())
                || !expected.authorityId().equals(material.authorityId())
                || !material.expectedInstanceIds().contains(expected.localInstanceId())
                || !acceptedPolicies.contains(material.policyFingerprint())) {
            throw new IllegalArgumentException(
                    "Test-secret serving inventory does not match the local binding");
        }
        Duration lifetime = Duration.between(material.issuedAt(), material.expiresAt());
        if (lifetime.isZero() || lifetime.isNegative()
                || lifetime.compareTo(MAXIMUM_INVENTORY_LIFETIME) > 0
                || material.issuedAt().isAfter(observedAt.plus(CLOCK_SKEW))
                || observedAt.isBefore(material.notBefore())
                || !observedAt.isBefore(material.expiresAt())
                || !inventory.fingerprintVerified(objectMapper)) {
            throw new IllegalArgumentException(
                    "Test-secret serving inventory freshness or identity is invalid");
        }
        List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures =
                inventory.signatures().stream().map(signed ->
                        new TestSuiteStabilityServingInventory.AuthoritySignature(
                                signed.authorityId(), signed.keyId(), signed.algorithm(),
                                signed.signedAt(), signed.signature())).toList();
        return ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                keys, signatureThreshold, signatures, inventory.materialFingerprint(),
                material.issuedAt(), material.expiresAt(), observedAt,
                "Test-secret serving inventory");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
