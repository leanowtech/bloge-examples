package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Payload-free proof returned after one pending-secret batch is finalized.
 *
 * <p>The coordinate and sorted slot names identify exactly what became active;
 * provider leases, locators, and secret values are deliberately not part of this
 * proof. The defensive copy also makes the proof safe to pass across transaction
 * and response boundaries.</p>
 *
 * <p>The constructor and factory are package-private so only the pending-secret
 * store can mint this capability. The full lease remains part of equality and
 * identity, while JSON and diagnostics expose only the safe coordinate and
 * sorted slot names.</p>
 */
public final class FinalizedSecretSlots {
    private final PendingSecretLease lease;
    private final Set<String> slots;

    FinalizedSecretSlots(PendingSecretLease lease, Set<String> slots) {
        this.lease = Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(slots, "slots");
        if (slots.isEmpty()) throw new IllegalArgumentException("slots are required");
        TreeSet<String> sorted = new TreeSet<>();
        for (String slot : slots) {
            PendingSecretOperation.SlotRules.require(slot);
            sorted.add(slot);
        }
        this.slots = Collections.unmodifiableSet(sorted);
    }

    /** Mints a proof only inside the pending-secret persistence boundary. */
    static FinalizedSecretSlots from(PendingSecretLease lease, Set<String> slots) {
        return new FinalizedSecretSlots(lease, slots);
    }

    /** Exact command attempt fence; never a JSON property. */
    @JsonIgnore
    public PendingSecretLease lease() {
        return lease;
    }

    /** Safe coordinate derived from the exact lease, never independently supplied. */
    @JsonProperty
    public ConnectionRevisionCoordinate coordinate() {
        return lease.coordinate();
    }

    /** Immutable, lexicographically sorted finalized slot names. */
    @JsonProperty
    public Set<String> slots() {
        return slots;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FinalizedSecretSlots that)) return false;
        return lease.equals(that.lease) && slots.equals(that.slots);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lease, slots);
    }

    @Override
    public String toString() {
        return "FinalizedSecretSlots[coordinate=" + coordinate() + ", slots=" + slots + "]";
    }
}
