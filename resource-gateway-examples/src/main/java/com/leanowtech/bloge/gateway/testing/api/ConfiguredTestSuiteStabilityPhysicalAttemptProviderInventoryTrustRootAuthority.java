package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Verifies one atomic dual-quorum physical provider-inventory trust-root publication.
 *
 * <p>Both independent bootstrap-root quorums must approve the same canonical material. Local
 * scope, protocol, root trust domains, rotation policy, validity, key algorithms, and runtime-key
 * independence are checked before the durable floor advances. The resulting runtime key snapshot
 * is immutable and contains public verification material only.</p>
 */
public final class ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority {

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_PUBLICATION_LIFETIME = Duration.ofDays(1);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final Clock clock;
    private final TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootPublication publication;
    private final VerifiedKeySet verifiedKeySet;

    /**
     * Exact local identity a signed dual key-set publication must bind.
     *
     * @param scopeId stable fleet scope
     * @param trustRootSetId stable managed dual key-set identity
     * @param protocolVersion exact Resource Gateway integration protocol
     * @param deploymentRootTrustDomain deployment bootstrap-root domain
     * @param witnessRootTrustDomain independent witness bootstrap-root domain
     */
    public record ExpectedBinding(
            String scopeId,
            String trustRootSetId,
            String protocolVersion,
            String deploymentRootTrustDomain,
            String witnessRootTrustDomain) {

        /** Rejects malformed or non-independent local root identity. */
        public ExpectedBinding {
            scopeId = normalized(scopeId);
            trustRootSetId = normalized(trustRootSetId);
            protocolVersion = normalized(protocolVersion);
            deploymentRootTrustDomain = normalized(deploymentRootTrustDomain);
            witnessRootTrustDomain = normalized(witnessRootTrustDomain);
            if (!IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(trustRootSetId).matches()
                    || !IDENTIFIER.matcher(protocolVersion).matches()
                    || !IDENTIFIER.matcher(deploymentRootTrustDomain).matches()
                    || !IDENTIFIER.matcher(witnessRootTrustDomain).matches()
                    || deploymentRootTrustDomain.equals(witnessRootTrustDomain)) {
                throw new IllegalArgumentException(
                        "Physical provider-inventory trust-root binding is invalid");
            }
        }
    }

    /**
     * Verifies one supplied trust-root publication and advances its durable floor.
     *
     * @param objectMapper canonical protocol mapper
     * @param clock current time authority
     * @param binding exact local identity
     * @param acceptedPolicyFingerprints accepted rotation policy revisions
     * @param deploymentRootSignatureThreshold deployment bootstrap-root threshold
     * @param deploymentRootKeys deployment bootstrap public keys
     * @param witnessRootSignatureThreshold independent witness bootstrap-root threshold
     * @param witnessRootKeys witness bootstrap public keys
     * @param floor durable monotonic publication floor
     * @param publication untrusted signed dual key-set envelope
     */
    public ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding binding,
            Set<String> acceptedPolicyFingerprints,
            int deploymentRootSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    deploymentRootKeys,
            int witnessRootSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    witnessRootKeys,
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor floor,
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootPublication publication) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        ExpectedBinding expected = Objects.requireNonNull(binding, "binding");
        Set<String> policies = acceptedPolicies(acceptedPolicyFingerprints);
        Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                indexedDeploymentRoots =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                        deploymentRootKeys, deploymentRootSignatureThreshold);
        Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                indexedWitnessRoots =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                        witnessRootKeys, witnessRootSignatureThreshold);
        if (!independentAuthorities(deploymentRootKeys, witnessRootKeys)) {
            throw new IllegalArgumentException(
                    "Physical provider-inventory bootstrap-root authorities must be independent");
        }
        TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor durableFloor =
                Objects.requireNonNull(floor, "floor");
        if (!durableFloor.durable()) {
            throw new IllegalArgumentException(
                    "Physical provider-inventory trust-root publication requires a durable floor");
        }
        this.publication = Objects.requireNonNull(publication, "publication");
        TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootPublication.Material material =
                this.publication.material();
        Instant now = clock.instant();
        if (!this.publication.fingerprintVerified(mapper)
                || !expected.scopeId().equals(material.scopeId())
                || !expected.trustRootSetId().equals(material.trustRootSetId())
                || !expected.protocolVersion().equals(material.protocolVersion())
                || !expected.deploymentRootTrustDomain().equals(
                material.deploymentRootTrustDomain())
                || !expected.witnessRootTrustDomain().equals(material.witnessRootTrustDomain())
                || !policies.contains(material.policyFingerprint())) {
            throw new IllegalArgumentException(
                    "Physical provider-inventory trust-root publication binding is invalid");
        }
        Duration lifetime = Duration.between(material.issuedAt(), material.expiresAt());
        if (lifetime.isZero() || lifetime.isNegative()
                || lifetime.compareTo(MAXIMUM_PUBLICATION_LIFETIME) > 0
                || material.issuedAt().isAfter(now.plus(CLOCK_SKEW))
                || now.isBefore(material.notBefore())
                || !now.isBefore(material.expiresAt())) {
            throw new IllegalArgumentException(
                    "Physical provider-inventory trust-root publication time is invalid");
        }
        ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                indexedDeploymentRoots, deploymentRootSignatureThreshold,
                this.publication.deploymentRootSignatures(),
                this.publication.materialFingerprint(), material.issuedAt(),
                material.expiresAt(), now, "Physical provider-inventory deployment root");
        ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                indexedWitnessRoots, witnessRootSignatureThreshold,
                this.publication.witnessRootSignatures(),
                this.publication.materialFingerprint(), material.issuedAt(),
                material.expiresAt(), now, "Physical provider-inventory witness root");
        this.verifiedKeySet = verifiedKeySet(material);
        durableFloor.accept(new TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.Generation(
                TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.Generation.SCHEMA_VERSION,
                material.scopeId(), material.trustRootSetId(), material.sequence(),
                this.publication.materialFingerprint(), material.previousMaterialFingerprint()));
    }

    /**
     * Strictly parses a signed publication before applying the same verification path.
     *
     * @param objectMapper canonical protocol mapper
     * @param clock current time authority
     * @param binding exact local identity
     * @param acceptedPolicyFingerprints accepted rotation policy revisions
     * @param deploymentRootSignatureThreshold deployment bootstrap-root threshold
     * @param deploymentRootKeys deployment bootstrap public keys
     * @param witnessRootSignatureThreshold independent witness bootstrap-root threshold
     * @param witnessRootKeys witness bootstrap public keys
     * @param floor durable monotonic publication floor
     * @param publicationJson untrusted signed dual key-set JSON envelope
     * @return verified immutable authority
     */
    public static ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority fromJson(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding binding,
            Set<String> acceptedPolicyFingerprints,
            int deploymentRootSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    deploymentRootKeys,
            int witnessRootSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    witnessRootKeys,
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor floor,
            String publicationJson) {
        try {
            ObjectMapper strict = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootPublication publication =
                    parsePublication(strict, publicationJson);
            return new ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootAuthority(
                    objectMapper, clock, binding, acceptedPolicyFingerprints,
                    deploymentRootSignatureThreshold, deploymentRootKeys,
                    witnessRootSignatureThreshold, witnessRootKeys, floor, publication);
        } catch (java.io.IOException | RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Physical provider-inventory trust-root configuration is invalid", invalid);
        }
    }

    static TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootPublication parsePublication(
            ObjectMapper strict, String publicationJson) throws java.io.IOException {
        return strict.readValue(normalized(publicationJson),
                TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootPublication.class);
    }

    /**
     * Projects aggregate local verification status without key ids or public material.
     *
     * @return aggregate local status
     */
    public Snapshot snapshot() {
        Instant now = clock.instant();
        long activeDeploymentAuthorities = verifiedKeySet.deploymentKeys().values().stream()
                .filter(key -> key.activeAt(now)).map(
                        ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey
                                ::authorityId)
                .distinct().count();
        long activeWitnessAuthorities = verifiedKeySet.witnessKeys().values().stream()
                .filter(key -> key.activeAt(now)).map(
                        ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey
                                ::authorityId)
                .distinct().count();
        boolean current = !now.isBefore(publication.material().notBefore())
                && now.isBefore(publication.material().expiresAt());
        boolean available = current
                && activeDeploymentAuthorities >= verifiedKeySet.deploymentSignatureThreshold()
                && activeWitnessAuthorities >= verifiedKeySet.witnessSignatureThreshold();
        String status = !current ? "EXPIRED"
                : activeDeploymentAuthorities < verifiedKeySet.deploymentSignatureThreshold()
                ? "DEPLOYMENT_THRESHOLD_UNAVAILABLE"
                : activeWitnessAuthorities < verifiedKeySet.witnessSignatureThreshold()
                ? "WITNESS_THRESHOLD_UNAVAILABLE" : "VERIFIED";
        return new Snapshot(Snapshot.SCHEMA_VERSION, available, status,
                publication.material().sequence(), publication.materialFingerprint(),
                publication.material().expiresAt(), verifiedKeySet.deploymentSignatureThreshold(),
                verifiedKeySet.witnessSignatureThreshold(), activeDeploymentAuthorities,
                activeWitnessAuthorities, true);
    }

    VerifiedKeySet verifiedKeySet() {
        if (!snapshot().available()) {
            throw new IllegalStateException(
                    "Physical provider-inventory trust-root key set is unavailable");
        }
        return verifiedKeySet;
    }

    private static VerifiedKeySet verifiedKeySet(
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootPublication.Material material) {
        List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> deployment =
                parseKeys(material.deploymentKeys());
        List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> witness =
                parseKeys(material.witnessKeys());
        Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                indexedDeployment =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                        deployment, material.deploymentSignatureThreshold());
        Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                indexedWitness =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                        witness, material.witnessSignatureThreshold());
        if (!independentAuthorities(deployment, witness)) {
            throw new IllegalArgumentException(
                    "Physical provider-inventory runtime authorities must be independent");
        }
        return new VerifiedKeySet(material.deploymentTrustDomain(),
                material.witnessTrustDomain(), material.deploymentSignatureThreshold(),
                material.witnessSignatureThreshold(), indexedDeployment, indexedWitness,
                material.sequence(), material.policyFingerprint());
    }

    private static List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            parseKeys(List<TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootPublication.AuthorityKeyMaterial>
            materials) {
        List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> result =
                new ArrayList<>();
        for (TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootPublication.AuthorityKeyMaterial material
                : materials) {
            try {
                byte[] encoded = Base64.getDecoder().decode(material.publicKeyBase64());
                PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(encoded));
                if (!java.util.Arrays.equals(encoded, publicKey.getEncoded())) {
                    throw new GeneralSecurityException("Non-canonical public key");
                }
                result.add(new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                        material.authorityId(), material.keyId(), publicKey,
                        material.notBefore(), material.expiresAt(),
                        material.enabled(), material.revoked()));
            } catch (GeneralSecurityException | IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "Physical provider-inventory trust-root public key is invalid", invalid);
            }
        }
        return List.copyOf(result);
    }

    private static Set<String> acceptedPolicies(Set<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            String normalized = normalized(value);
            if (!FINGERPRINT.matcher(normalized).matches() || !result.add(normalized)) {
                throw new IllegalArgumentException(
                        "Physical provider-inventory trust-root policy is invalid");
            }
        }
        if (result.isEmpty() || result.size() > 32) {
            throw new IllegalArgumentException(
                    "One through 32 physical provider-inventory trust-root policies are required");
        }
        return Set.copyOf(result);
    }

    static boolean independentAuthorities(
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> left,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> right) {
        Set<String> authorityIds = new HashSet<>();
        Set<String> publicKeys = new HashSet<>();
        for (ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey key
                : left == null
                ? List.<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>of()
                : left) {
            authorityIds.add(key.authorityId());
            publicKeys.add(Base64.getEncoder().encodeToString(key.publicKey().getEncoded()));
        }
        for (ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey key
                : right == null
                ? List.<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>of()
                : right) {
            if (authorityIds.contains(key.authorityId())
                    || publicKeys.contains(Base64.getEncoder().encodeToString(
                    key.publicKey().getEncoded()))) {
                return false;
            }
        }
        return !authorityIds.isEmpty() && right != null && !right.isEmpty();
    }

    record VerifiedKeySet(
            String deploymentTrustDomain,
            String witnessTrustDomain,
            int deploymentSignatureThreshold,
            int witnessSignatureThreshold,
            Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    deploymentKeys,
            Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    witnessKeys,
            long sequence,
            String policyFingerprint) {
    }

    /**
     * Aggregate key-free status used by dynamic wrappers, health, and capability projections.
     *
     * @param schemaVersion snapshot protocol generation
     * @param available whether both runtime thresholds are currently usable
     * @param status fixed readiness status
     * @param sequence current managed key-set sequence
     * @param generationFingerprint private generation identity for cohort convergence
     * @param expiresAt hard signed publication deadline
     * @param deploymentSignatureThreshold current deployment threshold
     * @param witnessSignatureThreshold current witness threshold
     * @param activeDeploymentAuthorityCount current active deployment authority count
     * @param activeWitnessAuthorityCount current active witness authority count
     * @param durableFloor whether sequence survives complete fleet restart
     */
    public record Snapshot(
            String schemaVersion,
            boolean available,
            String status,
            long sequence,
            String generationFingerprint,
            Instant expiresAt,
            int deploymentSignatureThreshold,
            int witnessSignatureThreshold,
            long activeDeploymentAuthorityCount,
            long activeWitnessAuthorityCount,
            boolean durableFloor) {

        /** Current aggregate snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryTrustRootSnapshot.v1";

        /** Enforces a bounded, key-free operational projection. */
        public Snapshot {
            status = normalized(status);
            generationFingerprint = normalized(generationFingerprint);
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isBlank() || sequence < 1
                    || !FINGERPRINT.matcher(generationFingerprint).matches()
                    || expiresAt == null
                    || deploymentSignatureThreshold < 1
                    || witnessSignatureThreshold < 1
                    || activeDeploymentAuthorityCount < 0
                    || activeWitnessAuthorityCount < 0 || !durableFloor) {
                throw new IllegalArgumentException(
                        "Physical provider-inventory trust-root snapshot is invalid");
            }
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
