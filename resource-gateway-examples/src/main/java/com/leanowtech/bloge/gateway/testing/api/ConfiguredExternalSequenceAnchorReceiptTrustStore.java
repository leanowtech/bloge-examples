package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
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
 * Verifies one bootstrap-signed managed external-notary trust publication.
 *
 * <p>Validation is deliberately ordered: canonical identity and exact deployment binding,
 * publication freshness, bootstrap-root quorum, complete notary authority coverage, active receipt
 * quorum, then durable monotonic floor. No caller can observe the new key snapshot before every
 * stage succeeds.</p>
 */
public final class ConfiguredExternalSequenceAnchorReceiptTrustStore
        implements ExternalSequenceAnchorReceiptTrustStore {

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final Clock clock;
    private final ExpectedBinding binding;
    private final ExternalSequenceAnchorBootstrapRootTrustStore rootTrustStore;
    private final Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            keys;
    private final Set<String> authorityIds;
    private final ExternalSequenceAnchorTrustPublication publication;
    private final boolean durableFloor;

    /**
     * Strictly parses and verifies one managed notary trust publication.
     *
     * @param objectMapper canonical JSON baseline
     * @param clock verification clock
     * @param binding exact deployment binding and freshness policy
     * @param acceptedPolicyFingerprints accepted governance policies
     * @param bootstrapSignatureThreshold required root signature quorum
     * @param bootstrapRootKeys independent bootstrap verification keys
     * @param floor durable monotonic publication floor
     * @param publication candidate signed publication
     */
    public ConfiguredExternalSequenceAnchorReceiptTrustStore(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding binding,
            Set<String> acceptedPolicyFingerprints,
            int bootstrapSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    bootstrapRootKeys,
            ExternalSequenceAnchorTrustPublicationFloor floor,
            ExternalSequenceAnchorTrustPublication publication) {
        this(objectMapper, clock, binding, acceptedPolicyFingerprints,
                new StaticExternalSequenceAnchorBootstrapRootTrustStore(
                        objectMapper, clock, binding.scopeId(), binding.trustRootSetId(),
                        binding.bootstrapTrustDomain(), bootstrapSignatureThreshold,
                        bootstrapRootKeys),
                floor, publication);
    }

    /**
     * Verifies one managed notary publication through an atomic bootstrap-root trust port.
     *
     * @param objectMapper canonical JSON baseline
     * @param clock verification clock
     * @param binding exact deployment binding and freshness policy
     * @param acceptedPolicyFingerprints accepted notary-key governance policies
     * @param rootTrustStore static or complete-chain managed bootstrap-root authority
     * @param floor durable monotonic notary publication floor
     * @param publication candidate signed notary trust publication
     */
    public ConfiguredExternalSequenceAnchorReceiptTrustStore(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding binding,
            Set<String> acceptedPolicyFingerprints,
            ExternalSequenceAnchorBootstrapRootTrustStore rootTrustStore,
            ExternalSequenceAnchorTrustPublicationFloor floor,
            ExternalSequenceAnchorTrustPublication publication) {
        ObjectMapper canonical = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.rootTrustStore = Objects.requireNonNull(rootTrustStore, "rootTrustStore");
        Set<String> policies = acceptedPolicies(acceptedPolicyFingerprints);
        ExternalSequenceAnchorTrustPublicationFloor durable =
                Objects.requireNonNull(floor, "floor");
        if (!durable.durable()) {
            throw new IllegalArgumentException(
                    "Managed external sequence-anchor trust requires a durable floor");
        }
        this.publication = Objects.requireNonNull(publication, "publication");
        ExternalSequenceAnchorTrustPublication.Material material = publication.material();
        Instant now = clock.instant();
        verifyBinding(canonical, material, policies, publication, now);
        if (!rootTrustStore.matchesBinding(material.scopeId(),
                material.trustRootSetId(), material.bootstrapTrustDomain())) {
            throw new IllegalArgumentException(
                    "Managed external sequence-anchor bootstrap-root binding is invalid");
        }
        try {
            rootTrustStore.verify(publication, now);
        } catch (ExternalSequenceAnchorBootstrapRootTrustStore.TrustException invalid) {
            throw new IllegalArgumentException(
                    "External sequence-anchor trust publication signature verification failed",
                    invalid);
        }
        this.keys = parseKeys(material.notaryKeys());
        this.authorityIds = this.keys.values().stream()
                .map(ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey::authorityId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        rootTrustStore.requireIndependentFrom(List.copyOf(this.keys.values()));
        if (activeAuthorityCount(now) < binding.receiptSignatureThreshold()) {
            throw new IllegalArgumentException(
                    "Managed external sequence-anchor trust active threshold is unavailable");
        }
        durable.accept(new ExternalSequenceAnchorTrustPublicationFloor.Generation(
                ExternalSequenceAnchorTrustPublicationFloor.Generation.SCHEMA_VERSION,
                material.scopeId(), material.trustRootSetId(), material.sequence(),
                publication.materialFingerprint(), material.previousMaterialFingerprint()));
        this.durableFloor = true;
    }

    /**
     * Parses strict publication JSON and constructs a verified immutable snapshot.
     *
     * @return verified managed receipt trust store
     */
    public static ConfiguredExternalSequenceAnchorReceiptTrustStore fromJson(
            ObjectMapper objectMapper,
            Clock clock,
            ExpectedBinding binding,
            Set<String> acceptedPolicyFingerprints,
            int bootstrapSignatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    bootstrapRootKeys,
            ExternalSequenceAnchorTrustPublicationFloor floor,
            String publicationJson) {
        try {
            ObjectMapper strict = strict(objectMapper);
            ExternalSequenceAnchorTrustPublication publication = strict.readValue(
                    normalized(publicationJson), ExternalSequenceAnchorTrustPublication.class);
            return new ConfiguredExternalSequenceAnchorReceiptTrustStore(
                    strict, clock, binding, acceptedPolicyFingerprints,
                    bootstrapSignatureThreshold, bootstrapRootKeys, floor, publication);
        } catch (RuntimeException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    "Managed external sequence-anchor trust configuration is invalid", invalid);
        }
    }

    /** Verifies exact key identity, complete key/publication lifetime, and Ed25519 signature. */
    @Override
    public void verify(
            TestSuiteStabilityExternalSequenceCheckpointReceipt receipt,
            Instant observedAt) {
        Objects.requireNonNull(receipt, "receipt");
        Instant now = observedAt == null ? clock.instant() : observedAt;
        rootTrustStore.verify(publication, now);
        ExternalSequenceAnchorTrustPublication.Material material = publication.material();
        if (now.isBefore(material.notBefore()) || !now.isBefore(material.expiresAt())) {
            throw new TrustException(TrustException.Reason.UNAVAILABLE);
        }
        ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey key = keys.get(
                receipt.authorityId() + '\u0000' + receipt.keyId());
        if (key == null) {
            throw new TrustException(TrustException.Reason.UNKNOWN_KEY);
        }
        if (!key.activeAt(receipt.issuedAt())
                || receipt.expiresAt().isAfter(key.expiresAt())
                || receipt.expiresAt().isAfter(material.expiresAt())) {
            throw new TrustException(TrustException.Reason.KEY_INACTIVE);
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key.publicKey());
            verifier.update(receipt.receiptFingerprint().getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(receipt.signature()))) {
                throw new TrustException(TrustException.Reason.INVALID_SIGNATURE);
            }
        } catch (IllegalArgumentException | GeneralSecurityException invalid) {
            throw new TrustException(TrustException.Reason.INVALID_SIGNATURE);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean coversAuthorities(Set<String> expected) {
        return rootTrustStore.descriptor().available()
                && expected != null && authorityIds.equals(Set.copyOf(expected));
    }

    /** {@inheritDoc} */
    @Override
    public Descriptor descriptor() {
        Instant now = clock.instant();
        int active = activeAuthorityCount(now);
        boolean available = usableAt(now, active)
                && rootTrustStore.descriptor().available();
        return new Descriptor(Descriptor.SCHEMA_VERSION, available, true, false,
                durableFloor, authorityIds.size(), active);
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot() {
        Instant now = clock.instant();
        int active = activeAuthorityCount(now);
        boolean rootsAvailable = rootTrustStore.descriptor().available();
        boolean available = usableAt(now, active) && rootsAvailable;
        return new Snapshot(Snapshot.SCHEMA_VERSION, available,
                available ? "VERIFIED" : rootsAvailable ? "EXPIRED" : "ROOT_UNAVAILABLE",
                publication.material().sequence(), authorityIds.size(),
                active, null, 0, 0);
    }

    /** {@inheritDoc} */
    @Override
    public ExternalSequenceAnchorBootstrapRootTrustStore.Descriptor
            bootstrapRootDescriptor() {
        return rootTrustStore.descriptor();
    }

    /** {@inheritDoc} */
    @Override
    public ExternalSequenceAnchorBootstrapRootTrustStore.Snapshot bootstrapRootSnapshot() {
        return rootTrustStore.snapshot();
    }

    ExternalSequenceAnchorTrustPublication publication() {
        return publication;
    }

    private void verifyBinding(
            ObjectMapper objectMapper,
            ExternalSequenceAnchorTrustPublication.Material material,
            Set<String> acceptedPolicies,
            ExternalSequenceAnchorTrustPublication candidate,
            Instant now) {
        Duration lifetime = Duration.between(material.issuedAt(), material.expiresAt());
        if (!candidate.fingerprintVerified(objectMapper)
                || !binding.scopeId().equals(material.scopeId())
                || !binding.trustRootSetId().equals(material.trustRootSetId())
                || !binding.anchorSetId().equals(material.anchorSetId())
                || !binding.notaryTrustDomain().equals(material.notaryTrustDomain())
                || !binding.bootstrapTrustDomain().equals(material.bootstrapTrustDomain())
                || binding.receiptSignatureThreshold()
                != material.receiptSignatureThreshold()
                || binding.maximumFaults() != material.maximumFaults()
                || !acceptedPolicies.contains(material.policyFingerprint())
                || lifetime.isZero() || lifetime.isNegative()
                || lifetime.compareTo(binding.maximumPublicationLifetime()) > 0
                || material.issuedAt().isAfter(now.plus(binding.clockSkew()))
                || now.isBefore(material.notBefore())
                || !now.plus(binding.minimumRemainingValidity()).isBefore(material.expiresAt())) {
            throw new IllegalArgumentException(
                    "Managed external sequence-anchor trust binding or freshness is invalid");
        }
    }

    private boolean usableAt(Instant now, int active) {
        ExternalSequenceAnchorTrustPublication.Material material = publication.material();
        return !now.isBefore(material.notBefore()) && now.isBefore(material.expiresAt())
                && active >= binding.receiptSignatureThreshold();
    }

    private int activeAuthorityCount(Instant now) {
        return (int) authorityIds.stream()
                .filter(authority -> keys.values().stream()
                        .anyMatch(key -> key.authorityId().equals(authority)
                                && key.activeAt(now)))
                .count();
    }

    private static Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            parseKeys(List<ExternalSequenceAnchorTrustPublication.AuthorityKeyMaterial> materials) {
        List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> result =
                new ArrayList<>();
        try {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            for (ExternalSequenceAnchorTrustPublication.AuthorityKeyMaterial material : materials) {
                PublicKey key = factory.generatePublic(new X509EncodedKeySpec(
                        Base64.getDecoder().decode(material.publicKeyBase64())));
                result.add(new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                        material.authorityId(), material.keyId(), key,
                        material.notBefore(), material.expiresAt(),
                        material.enabled(), material.revoked()));
            }
        } catch (IllegalArgumentException | GeneralSecurityException invalid) {
            throw new IllegalArgumentException(
                    "Managed external sequence-anchor notary key is invalid", invalid);
        }
        return ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(result, 1);
    }

    private static Set<String> acceptedPolicies(Set<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            String policy = normalized(value);
            if (!FINGERPRINT.matcher(policy).matches() || !result.add(policy)) {
                throw new IllegalArgumentException(
                        "Managed external sequence-anchor trust policy is invalid");
            }
        }
        if (result.isEmpty() || result.size() > 32) {
            throw new IllegalArgumentException(
                    "One through 32 managed external sequence-anchor policies are required");
        }
        return Set.copyOf(result);
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
     * Exact deployment binding and bounded publication freshness policy.
     *
     * @param scopeId stable Resource Gateway fleet scope
     * @param trustRootSetId stable managed notary trust-set identity
     * @param anchorSetId exact external notary-set identity
     * @param notaryTrustDomain receipt signer trust domain
     * @param bootstrapTrustDomain independent publication signer trust domain
     * @param receiptSignatureThreshold accepted receipt quorum
     * @param maximumFaults Byzantine fault bound
     * @param maximumPublicationLifetime hard maximum signed publication lifetime
     * @param clockSkew maximum root signer clock skew
     * @param minimumRemainingValidity required validity at publication acceptance
     */
    public record ExpectedBinding(
            String scopeId,
            String trustRootSetId,
            String anchorSetId,
            String notaryTrustDomain,
            String bootstrapTrustDomain,
            int receiptSignatureThreshold,
            int maximumFaults,
            Duration maximumPublicationLifetime,
            Duration clockSkew,
            Duration minimumRemainingValidity) {

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Enforces independent trust domains, Byzantine quorum math, and bounded freshness. */
        public ExpectedBinding {
            scopeId = normalized(scopeId);
            trustRootSetId = normalized(trustRootSetId);
            anchorSetId = normalized(anchorSetId);
            notaryTrustDomain = normalized(notaryTrustDomain);
            bootstrapTrustDomain = normalized(bootstrapTrustDomain);
            if (!IDENTIFIER.matcher(scopeId).matches()
                    || !IDENTIFIER.matcher(trustRootSetId).matches()
                    || !IDENTIFIER.matcher(anchorSetId).matches()
                    || !IDENTIFIER.matcher(notaryTrustDomain).matches()
                    || !IDENTIFIER.matcher(bootstrapTrustDomain).matches()
                    || notaryTrustDomain.equals(bootstrapTrustDomain)
                    || maximumFaults < 0 || maximumFaults > 10
                    || receiptSignatureThreshold < 2 * maximumFaults + 1
                    || maximumPublicationLifetime == null
                    || maximumPublicationLifetime.compareTo(Duration.ofMinutes(1)) < 0
                    || maximumPublicationLifetime.compareTo(Duration.ofDays(7)) > 0
                    || clockSkew == null || clockSkew.isNegative()
                    || clockSkew.compareTo(Duration.ofSeconds(30)) > 0
                    || minimumRemainingValidity == null
                    || minimumRemainingValidity.isNegative()
                    || minimumRemainingValidity.compareTo(Duration.ofHours(1)) > 0
                    || minimumRemainingValidity.compareTo(maximumPublicationLifetime) >= 0) {
                throw new IllegalArgumentException(
                        "Invalid managed external sequence-anchor trust binding");
            }
        }
    }
}
