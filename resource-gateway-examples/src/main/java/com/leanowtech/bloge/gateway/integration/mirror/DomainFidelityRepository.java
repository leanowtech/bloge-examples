package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.Optional;

/**
 * Full-enterprise-scope append-only store for Fidelity denominators and signed profiles.
 *
 * <p>Inventory lineages use optimistic predecessor fingerprints. A profile coordinate is unique
 * for one domain, inventory fingerprint, and measurement cut, preventing late evidence from
 * silently rewriting an already published cut. Implementations must revalidate canonical JSON and
 * duplicated indexes on every read.</p>
 */
public interface DomainFidelityRepository {
    /**
     * Appends one owner-approved inventory revision or recovers an exact retry.
     *
     * @param inventory server-sealed inventory
     * @param expectedPredecessorFingerprint blank for revision one, current head otherwise
     * @return committed or idempotently recovered inventory
     */
    DomainFidelityInventory appendInventory(
            DomainFidelityInventory inventory,
            String expectedPredecessorFingerprint);

    /** Reads one exact inventory revision inside one complete scope. */
    Optional<DomainFidelityInventory> findInventory(
            CapabilitySnapshot.Scope scope,
            String inventoryId,
            long revision);

    /** Reads the current inventory revision inside one complete scope. */
    Optional<DomainFidelityInventory> findLatestInventory(
            CapabilitySnapshot.Scope scope,
            String inventoryId);

    /**
     * Appends one signed profile or recovers an exact retry.
     *
     * @param profile independently projected and signed profile
     * @return committed or idempotently recovered profile
     */
    DomainFidelityProfile appendProfile(
            DomainFidelityProfile profile);

    /** Reads one exact signed profile by content address. */
    Optional<DomainFidelityProfile> findProfile(
            CapabilitySnapshot.Scope scope,
            String profileFingerprint);

    /** Reads the newest signed profile for one domain. */
    Optional<DomainFidelityProfile> findLatestProfile(
            CapabilitySnapshot.Scope scope,
            String domainId);

    /** Closed persistence rejection vocabulary. */
    enum Reason {
        CANONICAL_INVALID,
        LINEAGE_CONFLICT,
        CONTENT_CONFLICT,
        INVENTORY_NOT_FOUND,
        INVENTORY_MISMATCH,
        PROFILE_COORDINATE_CONFLICT,
        SIGNATURE_UNAVAILABLE,
        STORED_STATE_CORRUPT
    }

    /** Payload-free repository failure carrying only a stable reason. */
    final class Violation extends RuntimeException {
        private final Reason reason;

        /** Creates one stable repository failure. */
        public Violation(Reason reason) {
            super("Domain Fidelity repository rejected: "
                    + Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable repository rejection reason */
        public Reason reason() {
            return reason;
        }
    }
}
