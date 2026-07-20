package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable startup-configured bootstrap-root trust adapter for test compatibility.
 *
 * <p>This adapter exposes the same exact root port as the managed ceremony chain, preventing the
 * notary verifier from carrying two cryptographic implementations. It cannot rotate without
 * restart, replay genesis history, or provide a durable root floor and is therefore unsuitable for
 * staging or production policy.</p>
 */
public final class StaticExternalSequenceAnchorBootstrapRootTrustStore
        implements ExternalSequenceAnchorBootstrapRootTrustStore {

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String scopeId;
    private final String rootSetId;
    private final String trustDomain;
    private final int signatureThreshold;
    private final Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            keys;
    private final Set<String> authorityIds;

    /** Creates one exact immutable root snapshot from public Ed25519 keys. */
    public StaticExternalSequenceAnchorBootstrapRootTrustStore(
            ObjectMapper objectMapper,
            Clock clock,
            String scopeId,
            String rootSetId,
            String trustDomain,
            int signatureThreshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> rootKeys) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scopeId = normalized(scopeId);
        this.rootSetId = normalized(rootSetId);
        this.trustDomain = normalized(trustDomain);
        this.signatureThreshold = signatureThreshold;
        this.keys = ConfiguredTestSuiteStabilityServingInventoryAuthority.indexedKeys(
                rootKeys, signatureThreshold);
        this.authorityIds = this.keys.values().stream()
                .map(ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey
                        ::authorityId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Verifies canonical binding and signatures against the immutable startup root set. */
    @Override
    public void verify(ExternalSequenceAnchorTrustPublication publication, Instant observedAt) {
        ExternalSequenceAnchorTrustPublication candidate = Objects.requireNonNull(
                publication, "publication");
        Instant now = observedAt == null ? clock.instant() : observedAt;
        if (activeAuthorityCount(now) < signatureThreshold) {
            throw new TrustException(TrustException.Reason.UNAVAILABLE);
        }
        if (!candidate.fingerprintVerified(objectMapper)
                || !matchesBinding(candidate.material().scopeId(),
                candidate.material().trustRootSetId(),
                candidate.material().bootstrapTrustDomain())) {
            throw new TrustException(TrustException.Reason.INVALID_SIGNATURE);
        }
        boolean unknown = candidate.bootstrapSignatures().stream().anyMatch(signature ->
                !keys.containsKey(signature.authorityId() + '\u0000' + signature.keyId()));
        try {
            ConfiguredTestSuiteStabilityServingInventoryAuthority.verifyDetachedSignatures(
                    keys, signatureThreshold, candidate.bootstrapSignatures(),
                    candidate.materialFingerprint(), candidate.material().issuedAt(),
                    candidate.material().expiresAt(), now,
                    "External sequence-anchor trust publication");
        } catch (IllegalArgumentException invalid) {
            throw new TrustException(unknown
                    ? TrustException.Reason.UNKNOWN_KEY
                    : TrustException.Reason.INVALID_SIGNATURE);
        }
    }

    /** Enforces authority and encoded-public-key independence from notary receipt signers. */
    @Override
    public void requireIndependentFrom(
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> notaryKeys) {
        Set<String> encodedRoots = new HashSet<>();
        keys.values().forEach(key -> encodedRoots.add(
                Base64.getEncoder().encodeToString(key.publicKey().getEncoded())));
        for (ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey key
                : notaryKeys == null
                ? List.<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>of()
                : notaryKeys) {
            if (key == null || authorityIds.contains(key.authorityId())
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
        return this.scopeId.equals(normalized(scopeId))
                && this.rootSetId.equals(normalized(rootSetId))
                && this.trustDomain.equals(normalized(trustDomain));
    }

    /** {@inheritDoc} */
    @Override
    public Descriptor descriptor() {
        int active = activeAuthorityCount(clock.instant());
        return new Descriptor(Descriptor.SCHEMA_VERSION, active >= signatureThreshold,
                false, false, false, false, authorityIds.size(), active,
                signatureThreshold);
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot() {
        int active = activeAuthorityCount(clock.instant());
        boolean available = active >= signatureThreshold;
        return new Snapshot(Snapshot.SCHEMA_VERSION, available,
                available ? "STATIC" : "QUORUM_UNAVAILABLE", 0, 0,
                authorityIds.size(), active, null, null, 0, 0);
    }

    private int activeAuthorityCount(Instant now) {
        return (int) authorityIds.stream()
                .filter(authority -> keys.values().stream()
                        .anyMatch(key -> key.authorityId().equals(authority)
                                && key.activeAt(now)))
                .count();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
