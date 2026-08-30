package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

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
 * @param coordinate exact connection revision whose bindings were finalized
 * @param slots exact legal slots written or retained at that coordinate
 */
public record FinalizedSecretSlots(ConnectionRevisionCoordinate coordinate, Set<String> slots) {
    public FinalizedSecretSlots {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(slots, "slots");
        if (slots.isEmpty()) throw new IllegalArgumentException("slots are required");
        TreeSet<String> sorted = new TreeSet<>();
        for (String slot : slots) {
            PendingSecretOperation.SlotRules.require(slot);
            sorted.add(slot);
        }
        slots = Collections.unmodifiableSet(sorted);
    }
}
