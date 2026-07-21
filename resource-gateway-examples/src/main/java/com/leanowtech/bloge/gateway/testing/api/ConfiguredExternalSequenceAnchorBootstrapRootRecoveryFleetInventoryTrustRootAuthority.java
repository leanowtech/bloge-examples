package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.AuthorityKey;

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
 * Verifies one atomic dual-quorum recovery-fleet inventory trust-root publication.
 *
 * <p>Both independent bootstrap-root quorums must approve the same canonical material. Exact
 * deployment scope, fleet, protocol, root trust domains, rotation policy, validity, runtime-key
 * lifecycle, and deployment/witness independence are checked before the durable floor advances.
 * The resulting immutable key set couples both runtime verifier domains to one signed generation,
 * preventing partial rotation and key-generation time-of-check-to-time-of-use mixing.</p>
 */
public final class
        ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority {

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_PUBLICATION_LIFETIME = Duration.ofDays(1);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final Clock clock;
    private final ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
            publication;
    private final VerifiedKeySet verifiedKeySet;

    /**
     * Exact local identity a signed dual key-set publication must bind.
     *
     * @param deploymentScopeId stable tenant and environment deployment scope
     * @param fleetId stable recovery-fleet identity
     * @param trustRootSetId stable managed dual key-set identity
     * @param protocolVersion exact recovery-fleet inventory-publication protocol
     * @param deploymentRootTrustDomain deployment bootstrap-root domain
     * @param witnessRootTrustDomain independent witness bootstrap-root domain
     */
    public record ExpectedBinding(
            String deploymentScopeId,
            String fleetId,
            String trustRootSetId,
            String protocolVersion,
            String deploymentRootTrustDomain,
            String witnessRootTrustDomain) {

        /** Rejects malformed or non-independent local root identity. */
        public ExpectedBinding {
            deploymentScopeId = normalized(deploymentScopeId);
            fleetId = normalized(fleetId);
            trustRootSetId = normalized(trustRootSetId);
            protocolVersion = normalized(protocolVersion);
            deploymentRootTrustDomain = normalized(deploymentRootTrustDomain);
            witnessRootTrustDomain = normalized(witnessRootTrustDomain);
            if (!IDENTIFIER.matcher(deploymentScopeId).matches()
                    || !IDENTIFIER.matcher(fleetId).matches()
                    || !IDENTIFIER.matcher(trustRootSetId).matches()
                    || !ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                    .SCHEMA_VERSION.equals(protocolVersion)
                    || !IDENTIFIER.matcher(deploymentRootTrustDomain).matches()
                    || !IDENTIFIER.matcher(witnessRootTrustDomain).matches()
                    || deploymentRootTrustDomain.equals(witnessRootTrustDomain)) {
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory trust-root binding is invalid");
            }
        }
    }

    /**
     * Verifies one supplied trust-root publication and advances its durable floor.
     *
     * @param objectMapper canonical protocol mapper
     * @param clock current time authority
     * @param binding exact local root-set identity
     * @param acceptedPolicyFingerprints accepted rotation policy revisions
     * @param deploymentRootSignatureThreshold deployment bootstrap-root threshold
     * @param deploymentRootKeys deployment bootstrap public keys
     * @param witnessRootSignatureThreshold independent witness bootstrap-root threshold
     * @param witnessRootKeys witness bootstrap public keys
     * @param floor durable monotonic publication floor
     * @param publication untrusted signed atomic dual key-set envelope
     */
    public ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding binding,
            Set<String> acceptedPolicyFingerprints,
            int deploymentRootSignatureThreshold,
            List<AuthorityKey> deploymentRootKeys,
            int witnessRootSignatureThreshold,
            List<AuthorityKey> witnessRootKeys,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor floor,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                    publication) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        ExpectedBinding expected = Objects.requireNonNull(binding, "binding");
        Set<String> policies =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.acceptedPolicies(
                        acceptedPolicyFingerprints);
        Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                indexedDeploymentRoots = ConfiguredTestSuiteStabilityServingInventoryAuthority
                .indexedKeys(delegates(deploymentRootKeys), deploymentRootSignatureThreshold);
        Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                indexedWitnessRoots = ConfiguredTestSuiteStabilityServingInventoryAuthority
                .indexedKeys(delegates(witnessRootKeys), witnessRootSignatureThreshold);
        if (!independentAuthorities(deploymentRootKeys, witnessRootKeys)) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory bootstrap-root authorities must be independent");
        }
        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor durableFloor =
                Objects.requireNonNull(floor, "floor");
        if (!durableFloor.durable()) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory trust-root publication requires a durable floor");
        }
        this.publication = Objects.requireNonNull(publication, "publication");
        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication.Material
                material = this.publication.material();
        Instant now = clock.instant();
        if (!this.publication.fingerprintVerified(mapper)
                || !expected.deploymentScopeId().equals(material.deploymentScopeId())
                || !expected.fleetId().equals(material.fleetId())
                || !expected.trustRootSetId().equals(material.trustRootSetId())
                || !expected.protocolVersion().equals(material.protocolVersion())
                || !expected.deploymentRootTrustDomain().equals(
                material.deploymentRootTrustDomain())
                || !expected.witnessRootTrustDomain().equals(
                material.witnessRootTrustDomain())
                || !policies.contains(material.policyFingerprint())) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory trust-root publication binding is invalid");
        }
        Duration lifetime = Duration.between(material.issuedAt(), material.expiresAt());
        if (lifetime.isZero() || lifetime.isNegative()
                || lifetime.compareTo(MAXIMUM_PUBLICATION_LIFETIME) > 0
                || material.issuedAt().isAfter(now.plus(CLOCK_SKEW))
                || now.isBefore(material.notBefore())
                || !now.isBefore(material.expiresAt())) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory trust-root publication time is invalid");
        }
        ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                indexedDeploymentRoots, deploymentRootSignatureThreshold,
                translatedSignatures(this.publication.deploymentRootSignatures()),
                this.publication.materialFingerprint(), material.issuedAt(),
                material.expiresAt(), now, "Recovery-fleet inventory deployment root");
        ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                indexedWitnessRoots, witnessRootSignatureThreshold,
                translatedSignatures(this.publication.witnessRootSignatures()),
                this.publication.materialFingerprint(), material.issuedAt(),
                material.expiresAt(), now, "Recovery-fleet inventory witness root");
        this.verifiedKeySet = verifiedKeySet(material, this.publication.materialFingerprint());
        durableFloor.accept(
                new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor
                        .Generation(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor
                                .Generation.SCHEMA_VERSION,
                        material.deploymentScopeId(), material.fleetId(),
                        material.trustRootSetId(), material.sequence(),
                        this.publication.materialFingerprint(),
                        material.previousMaterialFingerprint()));
    }

    /**
     * Strictly parses and verifies one signed atomic dual-root publication.
     *
     * @param objectMapper canonical protocol mapper
     * @param clock current time authority
     * @param binding exact local root-set identity
     * @param acceptedPolicyFingerprints accepted rotation policy revisions
     * @param deploymentRootSignatureThreshold deployment bootstrap-root threshold
     * @param deploymentRootKeys deployment bootstrap public keys
     * @param witnessRootSignatureThreshold independent witness bootstrap-root threshold
     * @param witnessRootKeys witness bootstrap public keys
     * @param floor durable monotonic publication floor
     * @param publicationJson untrusted strict JSON publication
     * @return verified immutable dual-root authority
     */
    public static
            ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
            fromJson(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding binding,
            Set<String> acceptedPolicyFingerprints,
            int deploymentRootSignatureThreshold,
            List<AuthorityKey> deploymentRootKeys,
            int witnessRootSignatureThreshold,
            List<AuthorityKey> witnessRootKeys,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor floor,
            String publicationJson) {
        try {
            ObjectMapper strict = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                    publication = parsePublication(strict, publicationJson);
            return new
                    ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority(
                    objectMapper, clock, binding, acceptedPolicyFingerprints,
                    deploymentRootSignatureThreshold, deploymentRootKeys,
                    witnessRootSignatureThreshold, witnessRootKeys, floor, publication);
        } catch (java.io.IOException | RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory trust-root configuration is invalid", invalid);
        }
    }

    static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
            parsePublication(ObjectMapper strict, String publicationJson)
            throws java.io.IOException {
        return strict.readValue(normalized(publicationJson),
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        .class);
    }

    /**
     * Returns aggregate local status without key ids, public material, or generation fingerprint.
     *
     * @return current key-free verifier readiness
     */
    public Snapshot snapshot() {
        Instant now = clock.instant();
        long activeDeploymentAuthorities = activeAuthorities(
                verifiedKeySet.deploymentKeys().values(), now);
        long activeWitnessAuthorities = activeAuthorities(
                verifiedKeySet.witnessKeys().values(), now);
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
                publication.material().sequence(), publication.material().expiresAt(),
                verifiedKeySet.deploymentSignatureThreshold(),
                verifiedKeySet.witnessSignatureThreshold(), activeDeploymentAuthorities,
                activeWitnessAuthorities, true);
    }

    VerifiedKeySet verifiedKeySet() {
        if (!snapshot().available()) {
            throw new IllegalStateException(
                    "Recovery-fleet inventory trust-root key set is unavailable");
        }
        return verifiedKeySet;
    }

    private static VerifiedKeySet verifiedKeySet(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication.Material
                    material,
            String generationFingerprint) {
        List<AuthorityKey> deployment = parseKeys(material.deploymentKeys());
        List<AuthorityKey> witness = parseKeys(material.witnessKeys());
        Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                indexedDeployment = ConfiguredTestSuiteStabilityServingInventoryAuthority
                .indexedKeys(delegates(deployment), material.deploymentSignatureThreshold());
        Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                indexedWitness = ConfiguredTestSuiteStabilityServingInventoryAuthority
                .indexedKeys(delegates(witness), material.witnessSignatureThreshold());
        if (!independentAuthorities(deployment, witness)) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory runtime authorities must be independent");
        }
        return new VerifiedKeySet(material.deploymentTrustDomain(),
                material.witnessTrustDomain(), material.deploymentSignatureThreshold(),
                material.witnessSignatureThreshold(), indexedDeployment, indexedWitness,
                material.sequence(), material.policyFingerprint(), generationFingerprint);
    }

    private static List<AuthorityKey> parseKeys(
            List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                    .AuthorityKeyMaterial> materials) {
        List<AuthorityKey> result = new ArrayList<>();
        for (ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                .AuthorityKeyMaterial material : materials) {
            try {
                byte[] encoded = Base64.getDecoder().decode(material.publicKeyBase64());
                PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(encoded));
                if (!java.util.Arrays.equals(encoded, publicKey.getEncoded())) {
                    throw new GeneralSecurityException("Non-canonical public key");
                }
                result.add(new AuthorityKey(material.authorityId(), material.keyId(), publicKey,
                        material.notBefore(), material.expiresAt(), material.enabled(),
                        material.revoked()));
            } catch (GeneralSecurityException | IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory trust-root public key is invalid", invalid);
            }
        }
        return List.copyOf(result);
    }

    private static List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            delegates(List<AuthorityKey> keys) {
        return keys == null ? List.of() : keys.stream().map(AuthorityKey::delegate).toList();
    }

    static boolean independentAuthorities(
            List<AuthorityKey> left,
            List<AuthorityKey> right) {
        Set<String> authorityIds = new HashSet<>();
        Set<String> publicKeys = new HashSet<>();
        for (AuthorityKey key : left == null ? List.<AuthorityKey>of() : left) {
            authorityIds.add(key.authorityId());
            publicKeys.add(Base64.getEncoder().encodeToString(key.publicKey().getEncoded()));
        }
        for (AuthorityKey key : right == null ? List.<AuthorityKey>of() : right) {
            if (authorityIds.contains(key.authorityId())
                    || publicKeys.contains(Base64.getEncoder().encodeToString(
                    key.publicKey().getEncoded()))) {
                return false;
            }
        }
        return !authorityIds.isEmpty() && right != null && !right.isEmpty();
    }

    private static List<TestSuiteStabilityServingInventory.AuthoritySignature>
            translatedSignatures(
            List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                    .AuthoritySignature> signatures) {
        return signatures.stream().map(signature ->
                new TestSuiteStabilityServingInventory.AuthoritySignature(
                        signature.authorityId(), signature.keyId(), signature.algorithm(),
                        signature.signedAt(), signature.signature())).toList();
    }

    private static long activeAuthorities(
            java.util.Collection<ConfiguredTestSuiteStabilityServingInventoryAuthority
                    .AuthorityKey> keys,
            Instant now) {
        return keys.stream().filter(key -> key.activeAt(now))
                .map(ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey
                        ::authorityId)
                .distinct().count();
    }

    /**
     * Immutable runtime verifier keys coupled to the exact signed root generation.
     *
     * <p>This package-private interface is the only seam the later dynamic inventory consumer
     * needs; bootstrap-root signatures, publication parsing, and floor ordering stay hidden.</p>
     */
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
            String policyFingerprint,
            String generationFingerprint) {

        /** Defensively freezes the exact, independently governed signed generation. */
        VerifiedKeySet {
            deploymentTrustDomain = normalized(deploymentTrustDomain);
            witnessTrustDomain = normalized(witnessTrustDomain);
            policyFingerprint = normalized(policyFingerprint);
            generationFingerprint = normalized(generationFingerprint);
            deploymentKeys = Map.copyOf(Objects.requireNonNull(
                    deploymentKeys, "deploymentKeys"));
            witnessKeys = Map.copyOf(Objects.requireNonNull(witnessKeys, "witnessKeys"));
            long deploymentAuthorities = deploymentKeys.values().stream()
                    .map(ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey
                            ::authorityId)
                    .distinct().count();
            long witnessAuthorities = witnessKeys.values().stream()
                    .map(ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey
                            ::authorityId)
                    .distinct().count();
            if (!IDENTIFIER.matcher(deploymentTrustDomain).matches()
                    || !IDENTIFIER.matcher(witnessTrustDomain).matches()
                    || deploymentTrustDomain.equals(witnessTrustDomain)
                    || deploymentSignatureThreshold < 1
                    || deploymentSignatureThreshold > deploymentAuthorities
                    || witnessSignatureThreshold < 1
                    || witnessSignatureThreshold > witnessAuthorities
                    || sequence < 1 || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !FINGERPRINT.matcher(generationFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory verified key set is invalid");
            }
        }
    }

    /**
     * Aggregate key-free status for health and capability projection.
     *
     * @param schemaVersion snapshot protocol generation
     * @param available whether both runtime thresholds are currently usable
     * @param status fixed readiness status
     * @param sequence current managed key-set sequence
     * @param expiresAt hard signed publication deadline
     * @param deploymentSignatureThreshold current deployment threshold
     * @param witnessSignatureThreshold current witness threshold
     * @param activeDeploymentAuthorityCount current active deployment authorities
     * @param activeWitnessAuthorityCount current active witness authorities
     * @param durableFloor whether sequence survives complete fleet restart
     */
    public record Snapshot(
            String schemaVersion,
            boolean available,
            String status,
            long sequence,
            Instant expiresAt,
            int deploymentSignatureThreshold,
            int witnessSignatureThreshold,
            long activeDeploymentAuthorityCount,
            long activeWitnessAuthorityCount,
            boolean durableFloor) {

        /** Current aggregate recovery-fleet inventory trust-root snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootSnapshot.v1";

        /** Enforces a bounded, key-free operational projection. */
        public Snapshot {
            status = normalized(status);
            boolean statusConsistent = switch (status) {
                case "VERIFIED" -> available
                        && activeDeploymentAuthorityCount >= deploymentSignatureThreshold
                        && activeWitnessAuthorityCount >= witnessSignatureThreshold;
                case "DEPLOYMENT_THRESHOLD_UNAVAILABLE" -> !available
                        && activeDeploymentAuthorityCount < deploymentSignatureThreshold;
                case "WITNESS_THRESHOLD_UNAVAILABLE" -> !available
                        && activeDeploymentAuthorityCount >= deploymentSignatureThreshold
                        && activeWitnessAuthorityCount < witnessSignatureThreshold;
                case "EXPIRED" -> !available;
                default -> false;
            };
            if (!SCHEMA_VERSION.equals(schemaVersion) || status.isBlank() || sequence < 1
                    || expiresAt == null || expiresAt.getNano() != 0
                    || deploymentSignatureThreshold < 1
                    || witnessSignatureThreshold < 1
                    || activeDeploymentAuthorityCount < 0
                    || activeWitnessAuthorityCount < 0 || !durableFloor
                    || !statusConsistent) {
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory trust-root snapshot is invalid");
            }
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
