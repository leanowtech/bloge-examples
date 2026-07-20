package com.leanowtech.bloge.gateway.testing.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable startup-configured external sequence-anchor receipt trust store.
 *
 * <p>This compatibility mode verifies the same exact key identity, lifecycle and Ed25519 receipt
 * signature as managed trust, but it cannot rotate without restart and has no durable publication
 * floor. Staging may therefore reject it when restart-free managed trust is required.</p>
 */
public final class StaticExternalSequenceAnchorReceiptTrustStore
        implements ExternalSequenceAnchorReceiptTrustStore {

    private final Clock clock;
    private final Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            keys;
    private final Set<String> authorityIds;

    /**
     * Creates one bounded immutable public-key snapshot.
     *
     * @param clock key-lifecycle verification clock
     * @param authorityKeys one or more Ed25519 verification keys per notary authority
     */
    public StaticExternalSequenceAnchorReceiptTrustStore(
            Clock clock,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
                    authorityKeys) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Map<String, ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> indexed =
                new HashMap<>();
        Set<String> authorities = new HashSet<>();
        for (ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey key
                : authorityKeys == null
                ? List.<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>of()
                : authorityKeys) {
            if (key == null || indexed.putIfAbsent(
                    key.authorityId() + '\u0000' + key.keyId(), key) != null) {
                throw new IllegalArgumentException(
                        "External notary keys must be unique");
            }
            authorities.add(key.authorityId());
        }
        if (indexed.isEmpty() || indexed.size() > 64 || authorities.size() > 32) {
            throw new IllegalArgumentException(
                    "External notary keys must be non-empty and bounded");
        }
        this.keys = Map.copyOf(indexed);
        this.authorityIds = Set.copyOf(authorities);
    }

    /** Verifies exact key identity, full receipt lifetime, and Ed25519 signature. */
    @Override
    public void verify(
            TestSuiteStabilityExternalSequenceCheckpointReceipt receipt,
            Instant observedAt) {
        Objects.requireNonNull(receipt, "receipt");
        ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey key = keys.get(
                receipt.authorityId() + '\u0000' + receipt.keyId());
        if (key == null) {
            throw new TrustException(TrustException.Reason.UNKNOWN_KEY);
        }
        if (!key.activeAt(receipt.issuedAt())
                || receipt.expiresAt().isAfter(key.expiresAt())) {
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
        return expected != null && authorityIds.equals(Set.copyOf(expected));
    }

    /** {@inheritDoc} */
    @Override
    public Descriptor descriptor() {
        int active = activeAuthorityCount(clock.instant());
        return new Descriptor(Descriptor.SCHEMA_VERSION, active > 0,
                false, false, false, authorityIds.size(), active);
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot() {
        int active = activeAuthorityCount(clock.instant());
        return new Snapshot(Snapshot.SCHEMA_VERSION, active > 0,
                active > 0 ? "STATIC" : "EXPIRED", 0,
                authorityIds.size(), active, null, 0, 0);
    }

    private int activeAuthorityCount(Instant now) {
        return (int) authorityIds.stream()
                .filter(authority -> keys.values().stream()
                        .anyMatch(key -> key.authorityId().equals(authority)
                                && key.activeAt(now)))
                .count();
    }
}
