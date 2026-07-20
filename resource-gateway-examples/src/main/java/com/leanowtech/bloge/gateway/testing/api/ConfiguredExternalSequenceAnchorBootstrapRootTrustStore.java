package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Replays and verifies one complete cross-signed external-anchor bootstrap-root chain.
 *
 * <p>Verification starts from an exact deployment-pinned genesis. Every transition must be the
 * next sequence, name the exact predecessor, match the deployment binding and accepted policy,
 * remain inside bounded lifecycle rules, carry an authorizing quorum from the preceding roots,
 * and carry proof-of-possession from the incoming roots. Only the current valid head advances the
 * durable floor and becomes observable.</p>
 */
public final class ConfiguredExternalSequenceAnchorBootstrapRootTrustStore
        implements ExternalSequenceAnchorBootstrapRootTrustStore {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ExpectedBinding binding;
    private final ExternalSequenceAnchorBootstrapRootBundle bundle;
    private final ExternalSequenceAnchorBootstrapRootTransition.Material head;
    private final String headFingerprint;
    private final Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            headKeys;
    private final Set<String> headAuthorityIds;
    private final int activeAuthorityCount;

    /**
     * Verifies a complete genesis-to-head transition bundle and advances its durable floor.
     *
     * @param objectMapper canonical material mapper
     * @param clock verification clock
     * @param binding exact local root-chain binding and lifecycle policy
     * @param acceptedPolicyFingerprints accepted ceremony policies
     * @param genesis deployment-pinned finite trust anchor
     * @param floor durable monotonic head floor
     * @param bundle untrusted complete transition bundle
     */
    public ConfiguredExternalSequenceAnchorBootstrapRootTrustStore(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding binding,
            Set<String> acceptedPolicyFingerprints,
            ExternalSequenceAnchorBootstrapRootGenesis genesis,
            ExternalSequenceAnchorBootstrapRootPublicationFloor floor,
            ExternalSequenceAnchorBootstrapRootBundle bundle) {
        ObjectMapper canonical = Objects.requireNonNull(objectMapper, "objectMapper");
        this.objectMapper = canonical;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.binding = Objects.requireNonNull(binding, "binding");
        Set<String> policies = acceptedPolicies(acceptedPolicyFingerprints);
        ExternalSequenceAnchorBootstrapRootGenesis pinned = Objects.requireNonNull(
                genesis, "genesis");
        verifyGenesisBinding(pinned);
        ExternalSequenceAnchorBootstrapRootPublicationFloor durable =
                Objects.requireNonNull(floor, "floor");
        if (!durable.durable()) {
            throw new IllegalArgumentException(
                    "Managed bootstrap-root chain requires a durable floor");
        }
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        String genesisFingerprint = pinned.materialFingerprint(canonical);
        if (!genesisFingerprint.equals(bundle.genesisMaterialFingerprint())
                || bundle.transitions().size() > binding.maximumTransitionCount()) {
            throw new IllegalArgumentException(
                    "Bootstrap-root bundle does not match the pinned genesis");
        }

        Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                precedingKeys = parseKeys(pinned.rootKeys(), pinned.signatureThreshold());
        ExternalSequenceAnchorBootstrapRootTransition.Material precedingMaterial = null;
        String expectedPredecessor = genesisFingerprint;
        Instant now = clock.instant();
        for (int index = 0; index < bundle.transitions().size(); index++) {
            ExternalSequenceAnchorBootstrapRootTransition transition =
                    bundle.transitions().get(index);
            long expectedSequence = index + 1L;
            verifyTransitionIdentity(
                    canonical, policies, transition, expectedSequence,
                    expectedPredecessor, precedingMaterial, now);
            verifyCeremonySignatures(precedingKeys,
                    transition.authorizingRootSignatures(), transition,
                    now, "Bootstrap-root authorizing");
            Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    incomingKeys = parseKeys(
                    transition.material().rootKeys(), binding.signatureThreshold());
            verifyCeremonySignatures(incomingKeys,
                    transition.incomingRootSignatures(), transition,
                    now, "Bootstrap-root incoming proof");
            precedingKeys = incomingKeys;
            precedingMaterial = transition.material();
            expectedPredecessor = transition.materialFingerprint();
        }

        ExternalSequenceAnchorBootstrapRootTransition finalTransition =
                bundle.transitions().getLast();
        this.head = finalTransition.material();
        this.headFingerprint = finalTransition.materialFingerprint();
        this.headKeys = precedingKeys;
        this.headAuthorityIds = this.headKeys.values().stream()
                .map(ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey
                        ::authorityId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.activeAuthorityCount = activeAuthorityCount(now);
        verifyCurrentHead(now);
        durable.accept(floorChain(bundle));
    }

    /** Strictly parses a bundle and constructs one fully verified root snapshot. */
    public static ConfiguredExternalSequenceAnchorBootstrapRootTrustStore fromJson(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding binding,
            Set<String> acceptedPolicyFingerprints,
            ExternalSequenceAnchorBootstrapRootGenesis genesis,
            ExternalSequenceAnchorBootstrapRootPublicationFloor floor,
            String bundleJson) {
        try {
            ObjectMapper strict = strict(objectMapper);
            ExternalSequenceAnchorBootstrapRootBundle bundle = strict.readValue(
                    normalized(bundleJson), ExternalSequenceAnchorBootstrapRootBundle.class);
            return new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore(
                    strict, clock, binding, acceptedPolicyFingerprints,
                    genesis, floor, bundle);
        } catch (RuntimeException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "External sequence-anchor bootstrap-root bundle is invalid", invalid);
        }
    }

    /** Verifies a managed notary publication against this exact immutable root head. */
    @Override
    public void verify(ExternalSequenceAnchorTrustPublication publication, Instant observedAt) {
        ExternalSequenceAnchorTrustPublication candidate = Objects.requireNonNull(
                publication, "publication");
        Instant now = observedAt == null ? clock.instant() : observedAt;
        if (!availableAt(now)) {
            throw new TrustException(TrustException.Reason.UNAVAILABLE);
        }
        if (!candidate.fingerprintVerified(objectMapper)
                || !matchesBinding(candidate.material().scopeId(),
                candidate.material().trustRootSetId(),
                candidate.material().bootstrapTrustDomain())) {
            throw new TrustException(TrustException.Reason.INVALID_SIGNATURE);
        }
        try {
            verifySignatures(headKeys, binding.signatureThreshold(),
                    candidate.bootstrapSignatures(), candidate.materialFingerprint(),
                    candidate.material().issuedAt(), candidate.material().expiresAt(), now,
                    "Managed notary trust publication");
        } catch (UnknownKeyException unknown) {
            throw new TrustException(TrustException.Reason.UNKNOWN_KEY);
        } catch (IllegalArgumentException invalid) {
            throw new TrustException(TrustException.Reason.INVALID_SIGNATURE);
        }
    }

    /** Enforces complete trust-domain independence by authority and encoded public key. */
    @Override
    public void requireIndependentFrom(
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> notaryKeys) {
        Set<String> encodedRoots = new HashSet<>();
        headKeys.values().forEach(key -> encodedRoots.add(
                Base64.getEncoder().encodeToString(key.publicKey().getEncoded())));
        for (ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey key
                : notaryKeys == null
                ? List.<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>of()
                : notaryKeys) {
            if (key == null || headAuthorityIds.contains(key.authorityId())
                    || encodedRoots.contains(Base64.getEncoder().encodeToString(
                    key.publicKey().getEncoded()))) {
                throw new IllegalArgumentException(
                        "Bootstrap-root and external notary authorities must be independent");
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean matchesBinding(String scopeId, String rootSetId, String trustDomain) {
        return binding.scopeId().equals(normalized(scopeId))
                && binding.rootSetId().equals(normalized(rootSetId))
                && binding.trustDomain().equals(normalized(trustDomain));
    }

    /** {@inheritDoc} */
    @Override
    public Descriptor descriptor() {
        int active = activeAuthorityCount(clock.instant());
        return new Descriptor(Descriptor.SCHEMA_VERSION,
                availableAt(clock.instant()), true, false, true, true,
                headAuthorityIds.size(), active, binding.signatureThreshold());
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot() {
        Instant now = clock.instant();
        boolean available = availableAt(now);
        String status = now.isBefore(head.notBefore()) ? "NOT_YET_VALID"
                : !now.isBefore(head.expiresAt()) ? "EXPIRED"
                : activeAuthorityCount(now) < binding.signatureThreshold()
                ? "QUORUM_UNAVAILABLE" : "HEALTHY";
        return new Snapshot(Snapshot.SCHEMA_VERSION, available, status,
                head.sequence(), bundle.transitions().size(), headAuthorityIds.size(),
                activeAuthorityCount(now), head.expiresAt(), null, 0, 0);
    }

    private void verifyGenesisBinding(ExternalSequenceAnchorBootstrapRootGenesis genesis) {
        if (!binding.scopeId().equals(genesis.scopeId())
                || !binding.rootSetId().equals(genesis.rootSetId())
                || !binding.trustDomain().equals(genesis.trustDomain())
                || binding.signatureThreshold() != genesis.signatureThreshold()
                || binding.maximumFaults() != genesis.maximumFaults()) {
            throw new IllegalArgumentException(
                    "Bootstrap-root genesis does not match the local binding");
        }
    }

    private void verifyTransitionIdentity(
            ObjectMapper objectMapper,
            Set<String> policies,
            ExternalSequenceAnchorBootstrapRootTransition transition,
            long expectedSequence,
            String expectedPredecessor,
            ExternalSequenceAnchorBootstrapRootTransition.Material preceding,
            Instant observedAt) {
        ExternalSequenceAnchorBootstrapRootTransition.Material material =
                transition.material();
        Duration lifetime = Duration.between(material.issuedAt(), material.expiresAt());
        if (material.sequence() != expectedSequence
                || !expectedPredecessor.equals(material.previousMaterialFingerprint())
                || !binding.scopeId().equals(material.scopeId())
                || !binding.rootSetId().equals(material.rootSetId())
                || !binding.trustDomain().equals(material.trustDomain())
                || binding.signatureThreshold() != material.signatureThreshold()
                || binding.maximumFaults() != material.maximumFaults()
                || !policies.contains(material.policyFingerprint())
                || !transition.fingerprintVerified(objectMapper)
                || lifetime.isZero() || lifetime.isNegative()
                || lifetime.compareTo(binding.maximumRootLifetime()) > 0
                || material.issuedAt().isAfter(observedAt.plus(binding.clockSkew()))
                || preceding != null && (material.issuedAt().isBefore(preceding.notBefore())
                || !material.issuedAt().isBefore(preceding.expiresAt()))) {
            throw new IllegalArgumentException(
                    "Bootstrap-root transition identity or lifecycle is invalid");
        }
    }

    private void verifyCurrentHead(Instant now) {
        if (now.isBefore(head.notBefore()) || !now.isBefore(head.expiresAt())
                || Duration.between(now, head.expiresAt())
                .compareTo(binding.minimumRemainingValidity()) < 0
                || activeAuthorityCount < binding.signatureThreshold()) {
            throw new IllegalArgumentException(
                    "Bootstrap-root transition head is not currently usable");
        }
    }

    private void verifyCeremonySignatures(
            Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> keys,
            List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures,
            ExternalSequenceAnchorBootstrapRootTransition transition,
            Instant observedAt,
            String label) {
        try {
            verifySignatures(keys, binding.signatureThreshold(), signatures,
                    transition.materialFingerprint(), transition.material().issuedAt(),
                    transition.material().expiresAt(), observedAt, label);
        } catch (UnknownKeyException unknown) {
            throw new IllegalArgumentException(label + " authority threshold is not met");
        }
    }

    private boolean availableAt(Instant now) {
        return now != null && !now.isBefore(head.notBefore()) && now.isBefore(head.expiresAt())
                && activeAuthorityCount(now) >= binding.signatureThreshold();
    }

    private int activeAuthorityCount(Instant when) {
        return (int) headKeys.values().stream()
                .filter(key -> key.activeAt(when))
                .map(ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey
                        ::authorityId)
                .distinct().count();
    }

    private void verifySignatures(
            Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> keys,
            int threshold,
            List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures,
            String materialFingerprint,
            Instant issuedAt,
            Instant expiresAt,
            Instant observedAt,
            String label) {
        int valid = 0;
        boolean unknown = false;
        Set<String> authorities = new HashSet<>();
        for (TestSuiteStabilityServingInventory.AuthoritySignature signed : signatures) {
            if (!authorities.add(signed.authorityId())
                    || signed.signedAt().isBefore(issuedAt.minus(binding.clockSkew()))
                    || !signed.signedAt().isBefore(expiresAt)
                    || signed.signedAt().isAfter(observedAt.plus(binding.clockSkew()))) {
                throw new IllegalArgumentException(label + " signature time is invalid");
            }
            ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey key = keys.get(
                    signed.authorityId() + '\u0000' + signed.keyId());
            if (key == null) {
                unknown = true;
                continue;
            }
            if (!key.activeAt(signed.signedAt())) {
                continue;
            }
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key.publicKey());
                verifier.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
                if (!verifier.verify(Base64.getDecoder().decode(signed.signature()))) {
                    throw new IllegalArgumentException(
                            label + " signature verification failed");
                }
                valid++;
            } catch (IllegalArgumentException | GeneralSecurityException invalid) {
                throw new IllegalArgumentException(
                        label + " signature verification failed", invalid);
            }
        }
        if (valid < threshold) {
            if (unknown) {
                throw new UnknownKeyException();
            }
            throw new IllegalArgumentException(label + " authority threshold is not met");
        }
    }

    private static Map<String,
            ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> parseKeys(
            List<ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial> materials,
            int threshold) {
        try {
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> keys =
                    new ArrayList<>();
            for (ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial material
                    : materials) {
                PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(
                                material.publicKeyBase64())));
                keys.add(new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                        material.authorityId(), material.keyId(), publicKey,
                        material.notBefore(), material.expiresAt(),
                        material.enabled(), material.revoked()));
            }
            return ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                    keys, threshold);
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Bootstrap-root public key material is invalid", invalid);
        }
    }

    private static Set<String> acceptedPolicies(Set<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            String normalized = normalized(value);
            if (!FINGERPRINT.matcher(normalized).matches() || !result.add(normalized)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root accepted ceremony policy is invalid");
            }
        }
        if (result.isEmpty() || result.size() > 32) {
            throw new IllegalArgumentException(
                    "One through 32 bootstrap-root ceremony policies are required");
        }
        return Set.copyOf(result);
    }

    private static ExternalSequenceAnchorBootstrapRootPublicationFloor.VerifiedChain floorChain(
            ExternalSequenceAnchorBootstrapRootBundle bundle) {
        List<ExternalSequenceAnchorBootstrapRootPublicationFloor.Generation> generations =
                new ArrayList<>();
        for (ExternalSequenceAnchorBootstrapRootTransition transition : bundle.transitions()) {
            generations.add(new ExternalSequenceAnchorBootstrapRootPublicationFloor.Generation(
                    ExternalSequenceAnchorBootstrapRootPublicationFloor.Generation.SCHEMA_VERSION,
                    transition.material().scopeId(), transition.material().rootSetId(),
                    transition.material().sequence(), transition.materialFingerprint(),
                    transition.material().sequence() == 1
                            ? "" : transition.material().previousMaterialFingerprint()));
        }
        ExternalSequenceAnchorBootstrapRootTransition.Material head =
                bundle.transitions().getLast().material();
        return new ExternalSequenceAnchorBootstrapRootPublicationFloor.VerifiedChain(
                ExternalSequenceAnchorBootstrapRootPublicationFloor.VerifiedChain.SCHEMA_VERSION,
                head.scopeId(), head.rootSetId(), generations);
    }

    private static ObjectMapper strict(ObjectMapper source) {
        return Objects.requireNonNull(source, "objectMapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Exact deployment binding and bounded root-ceremony policy.
     *
     * @param scopeId stable fleet scope
     * @param rootSetId stable managed root-chain identity
     * @param trustDomain independent bootstrap-root trust domain
     * @param signatureThreshold required distinct root-authority quorum
     * @param maximumFaults declared Byzantine fault bound
     * @param maximumRootLifetime hard maximum lifetime of one root generation
     * @param clockSkew maximum signer clock skew
     * @param minimumRemainingValidity required head validity at acceptance
     * @param maximumTransitionCount local bound no greater than the wire maximum
     */
    public record ExpectedBinding(
            String scopeId,
            String rootSetId,
            String trustDomain,
            int signatureThreshold,
            int maximumFaults,
            Duration maximumRootLifetime,
            Duration clockSkew,
            Duration minimumRemainingValidity,
            int maximumTransitionCount) {

        /** Enforces canonical identity, Byzantine quorum policy, and bounded lifecycle. */
        public ExpectedBinding {
            scopeId = normalized(scopeId);
            rootSetId = normalized(rootSetId);
            trustDomain = normalized(trustDomain);
            if (!IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(rootSetId).matches()
                    || !IDENTIFIER.matcher(trustDomain).matches()
                    || signatureThreshold < 1
                    || maximumFaults < 0 || maximumFaults > 10
                    || signatureThreshold < 2 * maximumFaults + 1
                    || maximumRootLifetime == null
                    || maximumRootLifetime.compareTo(Duration.ofHours(1)) < 0
                    || maximumRootLifetime.compareTo(Duration.ofDays(366)) > 0
                    || clockSkew == null || clockSkew.isNegative()
                    || clockSkew.compareTo(Duration.ofSeconds(30)) > 0
                    || minimumRemainingValidity == null
                    || minimumRemainingValidity.isNegative()
                    || minimumRemainingValidity.compareTo(Duration.ofDays(7)) > 0
                    || minimumRemainingValidity.compareTo(maximumRootLifetime) >= 0
                    || maximumTransitionCount < 1
                    || maximumTransitionCount
                    > ExternalSequenceAnchorBootstrapRootBundle.MAXIMUM_TRANSITIONS) {
                throw new IllegalArgumentException(
                        "Invalid external sequence-anchor bootstrap-root binding");
            }
        }
    }

    private static final class UnknownKeyException extends RuntimeException {
    }
}
