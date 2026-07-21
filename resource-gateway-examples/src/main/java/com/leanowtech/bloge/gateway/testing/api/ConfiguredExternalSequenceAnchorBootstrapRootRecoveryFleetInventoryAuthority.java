package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Lane;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneDescriptor;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.Material;

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
 * Static M-of-N Ed25519 authority for one signed bootstrap-root recovery fleet inventory.
 *
 * <p>Construction verifies the signed envelope before resolving any local runtime lane. Resolution
 * is by signed public lane key only; the independently reviewed local result must reproduce the
 * complete signed descriptor exactly. The resolved snapshot is immutable and process-local.
 * Expiration is re-evaluated for every observation and snapshot read, so stale authorization closes
 * admission without a restart.</p>
 *
 * <p>This implementation is intentionally static. It does not claim remote refresh, revocation,
 * witnessed publication chaining, or a durable generation floor. Those controls can replace the
 * atomically published authority while preserving this protocol and runtime binding contract.</p>
 */
public final class ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
        implements ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority {

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_INVENTORY_LIFETIME = Duration.ofDays(30);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    private final Clock clock;
    private final Material material;
    private final int validSignatureCount;
    private final int signatureThreshold;
    private final VerifiedBinding verifiedBinding;
    private final Snapshot snapshot;

    /**
     * Resolves one signed public lane key from a reviewed in-memory runtime catalog.
     *
     * <p>The resolver must be bounded and non-blocking. It must not perform remote discovery,
     * credential lookup, signature verification, or database I/O.</p>
     */
    @FunctionalInterface
    public interface LaneResolver {

        /**
         * Resolves one exact local runtime lane.
         *
         * @param key signed public scope/root-set key
         * @return independently configured runtime lane, or {@code null} when absent
         */
        Lane resolve(LaneKey key);
    }

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

        /** Validates public-key identity, algorithm, and lifecycle ordering. */
        public AuthorityKey {
            var validated =
                    new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                            authorityId, keyId, publicKey, notBefore, expiresAt,
                            enabled, revoked);
            authorityId = validated.authorityId();
            keyId = validated.keyId();
            publicKey = validated.publicKey();
            notBefore = validated.notBefore();
            expiresAt = validated.expiresAt();
        }

        ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey delegate() {
            return new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                    authorityId, keyId, publicKey, notBefore, expiresAt, enabled, revoked);
        }
    }

    /**
     * Creates and immediately verifies one immutable signed fleet inventory.
     *
     * @param objectMapper canonical material mapper
     * @param clock current freshness authority
     * @param trustDomain exact independent fleet-inventory trust domain
     * @param acceptedPolicyFingerprints accepted external policy revisions
     * @param signatureThreshold required distinct authority signatures
     * @param authorityKeys public verification keys
     * @param attestation untrusted signed inventory envelope
     * @param expectedBinding exact local deployment artifact and topology binding
     * @param laneResolver reviewed non-blocking local runtime catalog
     */
    public ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
            ObjectMapper objectMapper,
            Clock clock,
            String trustDomain,
            Set<String> acceptedPolicyFingerprints,
            int signatureThreshold,
            List<AuthorityKey> authorityKeys,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation attestation,
            VerifiedBinding expectedBinding,
            LaneResolver laneResolver) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        String expectedDomain = normalized(trustDomain);
        if (!IDENTIFIER.matcher(expectedDomain).matches()) {
            throw new IllegalArgumentException(
                    "Bootstrap-root recovery fleet inventory trust domain is invalid");
        }
        Set<String> policies =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.acceptedPolicies(
                        acceptedPolicyFingerprints);
        Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> keys =
                ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                        authorityKeys == null ? List.of()
                                : authorityKeys.stream().map(AuthorityKey::delegate).toList(),
                        signatureThreshold);
        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation envelope =
                Objects.requireNonNull(attestation, "attestation");
        this.verifiedBinding = Objects.requireNonNull(expectedBinding, "expectedBinding");
        this.material = envelope.material();
        this.signatureThreshold = signatureThreshold;
        this.validSignatureCount = verifyEnvelope(mapper, expectedDomain, policies, keys,
                signatureThreshold, envelope, expectedBinding, clock.instant());
        this.snapshot = resolveSnapshot(material, Objects.requireNonNull(
                laneResolver, "laneResolver"));
    }

    /** Returns the signed snapshot only while its hard validity window remains active. */
    @Override
    public Snapshot snapshot() {
        Observation observed = observation();
        if (!observed.available()) {
            throw new IllegalStateException(
                    "Bootstrap-root recovery fleet inventory authority is "
                            + observed.status());
        }
        return snapshot;
    }

    /** Re-evaluates hard freshness without remote, database, or runtime-catalog I/O. */
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
        return new Observation(Observation.SCHEMA_VERSION, available, status,
                "STATIC_SIGNED_ED25519_M_OF_N", material.generation(),
                material.laneDescriptors().size(), material.expiresAt(),
                validSignatureCount, signatureThreshold);
    }

    /** Returns the exact signed deployment and fixed-partition topology binding. */
    @Override
    public VerifiedBinding verifiedBinding() {
        return verifiedBinding;
    }

    /**
     * Parses strict public-key and signed-inventory JSON deployment configuration.
     *
     * @param objectMapper application JSON mapper
     * @param trustDomain exact independent inventory trust domain
     * @param acceptedPolicies comma-separated policy fingerprints
     * @param signatureThreshold distinct authority threshold
     * @param authorityKeysJson public Ed25519 key array
     * @param attestationJson signed inventory envelope
     * @param expectedBinding exact local deployment artifact and topology binding
     * @param laneResolver reviewed non-blocking local runtime catalog
     * @return verified static fleet inventory authority
     */
    public static ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
            fromJson(
            ObjectMapper objectMapper,
            String trustDomain,
            String acceptedPolicies,
            int signatureThreshold,
            String authorityKeysJson,
            String attestationJson,
            VerifiedBinding expectedBinding,
            LaneResolver laneResolver) {
        return fromJson(objectMapper, Clock.systemUTC(), trustDomain, acceptedPolicies,
                signatureThreshold, authorityKeysJson, attestationJson,
                expectedBinding, laneResolver);
    }

    static ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
            fromJson(
            ObjectMapper objectMapper,
            Clock clock,
            String trustDomain,
            String acceptedPolicies,
            int signatureThreshold,
            String authorityKeysJson,
            String attestationJson,
            VerifiedBinding expectedBinding,
            LaneResolver laneResolver) {
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
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation attestation =
                    strict.readValue(normalized(attestationJson),
                            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                                    .class);
            return new ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
                    objectMapper, clock, trustDomain,
                    ConfiguredTestSuiteStabilityServingInventoryAuthority.parsePolicies(
                            acceptedPolicies),
                    signatureThreshold, keys, attestation, expectedBinding, laneResolver);
        } catch (java.io.IOException | java.security.GeneralSecurityException
                 | RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Bootstrap-root recovery fleet inventory trust configuration is invalid",
                    invalid);
        }
    }

    static int verifyEnvelope(
            ObjectMapper objectMapper,
            String trustDomain,
            Set<String> acceptedPolicies,
            Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> keys,
            int signatureThreshold,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation attestation,
            VerifiedBinding expected,
            Instant observedAt) {
        Material material = attestation.material();
        if (!trustDomain.equals(material.trustDomain())
                || !expected.deploymentScopeId().equals(material.deploymentScopeId())
                || !expected.fleetId().equals(material.fleetId())
                || !expected.artifactFingerprint().equals(material.artifactFingerprint())
                || expected.partitionCount() != material.partitionCount()
                || !acceptedPolicies.contains(material.policyFingerprint())) {
            throw new IllegalArgumentException(
                    "Bootstrap-root recovery fleet inventory does not match the local binding");
        }
        Duration lifetime = Duration.between(material.issuedAt(), material.expiresAt());
        if (lifetime.isZero() || lifetime.isNegative()
                || lifetime.compareTo(MAXIMUM_INVENTORY_LIFETIME) > 0
                || material.issuedAt().isAfter(observedAt.plus(CLOCK_SKEW))
                || observedAt.isBefore(material.notBefore())
                || !observedAt.isBefore(material.expiresAt())
                || !attestation.fingerprintVerified(objectMapper)) {
            throw new IllegalArgumentException(
                    "Bootstrap-root recovery fleet inventory freshness or identity is invalid");
        }
        List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures =
                attestation.signatures().stream().map(signed ->
                        new TestSuiteStabilityServingInventory.AuthoritySignature(
                                signed.authorityId(), signed.keyId(), signed.algorithm(),
                                signed.signedAt(), signed.signature())).toList();
        return ConfiguredTestSuiteStabilityServingInventoryAuthority
                .verifyDetachedSignatures(keys, signatureThreshold, signatures,
                        attestation.materialFingerprint(), material.issuedAt(),
                        material.expiresAt(), observedAt,
                        "Bootstrap-root recovery fleet inventory");
    }

    static Snapshot resolveSnapshot(Material material, LaneResolver resolver) {
        try {
            List<Lane> lanes = material.laneDescriptors().stream().map(descriptor ->
                    exactLane(descriptor, resolver)).toList();
            return new Snapshot(Snapshot.SCHEMA_VERSION, material.generation(), lanes);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Bootstrap-root recovery fleet runtime catalog does not match the signed inventory",
                    invalid);
        }
    }

    private static Lane exactLane(LaneDescriptor descriptor, LaneResolver resolver) {
        Lane lane = Objects.requireNonNull(resolver.resolve(descriptor.key()), "resolved lane");
        if (!descriptor.equals(lane.descriptor())) {
            throw new IllegalArgumentException("Resolved recovery fleet lane descriptor drifted");
        }
        return lane;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
