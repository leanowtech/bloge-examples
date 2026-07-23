package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonical nested sealing and semantic verification for {@link SessionStateSpace}. */
public final class SessionStateSpaceIntegrity {
    /** Maximum canonical session snapshot size accepted by the in-process v1 kernel. */
    public static final int MAXIMUM_CANONICAL_BYTES = 256 * 1024 * 1024;
    /** Maximum canonical entity payload size. */
    public static final int MAXIMUM_ENTITY_BYTES = 16 * 1024 * 1024;
    /** Maximum canonical transaction response size. */
    public static final int MAXIMUM_RESPONSE_BYTES = 16 * 1024 * 1024;

    private SessionStateSpaceIntegrity() {
    }

    /**
     * Seals every nested entity, tombstone, event, receipt, then both state fingerprints.
     *
     * @param mapper canonical protocol mapper
     * @param state unsealed or resealed state
     * @return complete immutable state snapshot
     */
    public static SessionStateSpace seal(ObjectMapper mapper, SessionStateSpace state) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(state, "state");
        List<SessionStateSpace.EntitySnapshot> entities = state.entities().stream()
                .map(entity -> sealEntity(mapper, entity)).toList();
        List<SessionStateSpace.EntityTombstone> tombstones = state.tombstones().stream()
                .map(tombstone -> sealTombstone(mapper, tombstone)).toList();
        List<SessionStateSpace.StateTransitionEvent> events = state.committedEvents().stream()
                .map(event -> sealEvent(mapper, event)).toList();
        List<SessionStateSpace.TransactionReceipt> receipts = state.processedCommands().stream()
                .map(receipt -> sealReceipt(mapper, receipt)).toList();
        SessionStateSpace normalized = new SessionStateSpace(
                state.schemaVersion(), state.sessionId(), state.scope(), state.planFingerprint(),
                state.stateModelRef(), state.writeEffectRefs(), state.stateRevision(),
                state.logicalClock(), state.randomSeed(), entities, tombstones,
                state.businessKeyIndex(), events, receipts, state.expiresAt(), "", "");
        validateWorld(mapper, normalized);
        validateJournal(normalized);
        String world = ProtocolFingerprint.ofBounded(
                mapper, worldMaterial(normalized), MAXIMUM_CANONICAL_BYTES);
        if (!receipts.isEmpty()
                && !receipts.getLast().resultingWorldFingerprint().equals(world)) {
            throw new IllegalArgumentException(
                    "latest transaction receipt does not close the current world");
        }
        SessionStateSpace worldSealed = normalized.withFingerprints(world, "");
        String complete = ProtocolFingerprint.ofBounded(
                mapper, worldSealed, MAXIMUM_CANONICAL_BYTES);
        return worldSealed.withFingerprints(world, complete);
    }

    /**
     * Recomputes all nested and top-level fingerprints.
     *
     * @param mapper canonical protocol mapper
     * @param state sealed state snapshot
     */
    public static void verify(ObjectMapper mapper, SessionStateSpace state) {
        Objects.requireNonNull(state, "state");
        if (state.worldFingerprint().isBlank() || state.fingerprint().isBlank()) {
            throw new IllegalArgumentException("session state is not sealed");
        }
        verifyNested(mapper, state);
        SessionStateSpace expected = seal(mapper, state);
        if (!state.worldFingerprint().equals(expected.worldFingerprint())) {
            throw new IllegalArgumentException("session world fingerprint mismatch");
        }
        if (!state.fingerprint().equals(expected.fingerprint())) {
            throw new IllegalArgumentException("session state fingerprint mismatch");
        }
    }

    /**
     * Computes the exact current-world fingerprint before a receipt is created.
     *
     * <p>This method verifies sealed entities, tombstones, and business-key bindings, but does not
     * admit an incomplete event/receipt journal. The transaction kernel uses it only for the
     * circular boundary where a receipt must name the resulting world before the complete session
     * can be sealed.</p>
     *
     * @param mapper canonical protocol mapper
     * @param state candidate state carrying the complete post-mutation world
     * @return canonical current-world fingerprint
     */
    public static String fingerprintWorld(ObjectMapper mapper, SessionStateSpace state) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(state, "state");
        verifyWorldNested(mapper, state);
        validateWorld(mapper, state);
        return ProtocolFingerprint.ofBounded(
                mapper, worldMaterial(state), MAXIMUM_CANONICAL_BYTES);
    }

    /**
     * Seals one entity independently.
     *
     * @param mapper canonical protocol mapper
     * @param entity entity snapshot
     * @return sealed entity
     */
    public static SessionStateSpace.EntitySnapshot sealEntity(
            ObjectMapper mapper, SessionStateSpace.EntitySnapshot entity) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(entity, "entity");
        SessionStateSpace.EntitySnapshot material = entity.withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_ENTITY_BYTES));
    }

    /**
     * Creates an exact business-key binding.
     *
     * @param mapper canonical protocol mapper
     * @param keyName state-model key name
     * @param components ordered key values
     * @param entityKey target entity
     * @return immutable indexed binding
     */
    public static SessionStateSpace.BusinessKeyBinding businessKey(
            ObjectMapper mapper,
            String keyName,
            List<?> components,
            SessionStateSpace.EntityKey entityKey) {
        String fingerprint = businessKeyFingerprint(mapper, components);
        return new SessionStateSpace.BusinessKeyBinding(
                keyName, new ArrayList<>(components), fingerprint, entityKey);
    }

    /**
     * Computes the exact index coordinate for ordered business-key components.
     *
     * @param mapper canonical protocol mapper
     * @param components bounded ordered scalar components
     * @return canonical component fingerprint used by the session index
     */
    public static String businessKeyFingerprint(
            ObjectMapper mapper, List<?> components) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                Objects.requireNonNull(components, "components"),
                64 * 1024);
    }

    /**
     * Seals one tombstone independently.
     *
     * @param mapper canonical protocol mapper
     * @param tombstone deletion fact
     * @return sealed tombstone
     */
    public static SessionStateSpace.EntityTombstone sealTombstone(
            ObjectMapper mapper, SessionStateSpace.EntityTombstone tombstone) {
        SessionStateSpace.EntityTombstone material =
                Objects.requireNonNull(tombstone, "tombstone").withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, 64 * 1024));
    }

    /**
     * Seals one transition event independently.
     *
     * @param mapper canonical protocol mapper
     * @param event transition event
     * @return sealed event
     */
    public static SessionStateSpace.StateTransitionEvent sealEvent(
            ObjectMapper mapper, SessionStateSpace.StateTransitionEvent event) {
        SessionStateSpace.StateTransitionEvent material =
                Objects.requireNonNull(event, "event").withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, 64 * 1024));
    }

    /**
     * Seals one transaction receipt independently.
     *
     * @param mapper canonical protocol mapper
     * @param receipt transaction receipt
     * @return sealed receipt
     */
    public static SessionStateSpace.TransactionReceipt sealReceipt(
            ObjectMapper mapper, SessionStateSpace.TransactionReceipt receipt) {
        SessionStateSpace.TransactionReceipt material =
                Objects.requireNonNull(receipt, "receipt").withFingerprint("");
        ProtocolFingerprint.ofBounded(mapper, material.response(), MAXIMUM_RESPONSE_BYTES);
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_RESPONSE_BYTES + 64 * 1024));
    }

    private static void validateWorld(ObjectMapper mapper, SessionStateSpace state) {
        Set<MirrorArtifactRef> writeEffects = new HashSet<>(state.writeEffectRefs());
        if (writeEffects.size() != state.writeEffectRefs().size()) {
            throw new IllegalArgumentException("session write-effect refs must be unique");
        }
        Map<SessionStateSpace.EntityKey, SessionStateSpace.EntitySnapshot> entities =
                new HashMap<>();
        for (SessionStateSpace.EntitySnapshot entity : state.entities()) {
            if (entities.put(entity.key(), entity) != null) {
                throw new IllegalArgumentException("session contains duplicate entity identities");
            }
        }
        Set<SessionStateSpace.EntityKey> tombstones = new HashSet<>();
        for (SessionStateSpace.EntityTombstone tombstone : state.tombstones()) {
            if (!tombstones.add(tombstone.key())) {
                throw new IllegalArgumentException("session contains duplicate tombstones");
            }
            if (entities.containsKey(tombstone.key())) {
                throw new IllegalArgumentException(
                        "session identity cannot be both live and tombstoned");
            }
            if (tombstone.deletedRevision() > state.stateRevision()) {
                throw new IllegalArgumentException(
                        "tombstone revision exceeds current state revision");
            }
        }
        Set<String> businessCoordinates = new HashSet<>();
        for (SessionStateSpace.BusinessKeyBinding binding : state.businessKeyIndex()) {
            String expectedValueFingerprint = ProtocolFingerprint.ofBounded(
                    mapper, binding.components(), 64 * 1024);
            if (!expectedValueFingerprint.equals(binding.valueFingerprint())) {
                throw new IllegalArgumentException(
                        "business-key component fingerprint mismatch");
            }
            if (!entities.containsKey(binding.entityKey())
                    && !tombstones.contains(binding.entityKey())) {
                throw new IllegalArgumentException(
                        "business-key binding targets neither a live entity nor a tombstone");
            }
            String coordinate = binding.keyName() + "\0" + binding.valueFingerprint();
            if (!businessCoordinates.add(coordinate)) {
                throw new IllegalArgumentException(
                        "session contains duplicate unique business-key values");
            }
        }
    }

    private static void validateJournal(SessionStateSpace state) {
        Map<String, SessionStateSpace.StateTransitionEvent> events = new LinkedHashMap<>();
        long previousRevision = 0;
        for (SessionStateSpace.StateTransitionEvent event : state.committedEvents()) {
            if (event.stateRevision() > state.stateRevision()
                    || event.stateRevision() < previousRevision) {
                throw new IllegalArgumentException("state event revision order is invalid");
            }
            previousRevision = event.stateRevision();
            if (events.put(event.eventId(), event) != null) {
                throw new IllegalArgumentException("session contains duplicate event ids");
            }
        }
        Set<String> idempotencyKeys = new HashSet<>();
        Set<String> coveredEvents = new HashSet<>();
        long expectedRevision = 1;
        for (SessionStateSpace.TransactionReceipt receipt : state.processedCommands()) {
            if (!idempotencyKeys.add(receipt.idempotencyKey())) {
                throw new IllegalArgumentException(
                        "session contains duplicate idempotency keys");
            }
            if (receipt.revisionBefore() != expectedRevision - 1
                    || receipt.revisionAfter() != expectedRevision) {
                throw new IllegalArgumentException(
                        "transaction receipt revisions must be contiguous");
            }
            expectedRevision++;
            Set<String> receiptEvents = new HashSet<>();
            for (String eventId : receipt.eventIds()) {
                if (!receiptEvents.add(eventId) || !coveredEvents.add(eventId)) {
                    throw new IllegalArgumentException(
                            "receipt event ids must be globally unique");
                }
                SessionStateSpace.StateTransitionEvent event = events.get(eventId);
                if (event == null || event.stateRevision() != receipt.revisionAfter()) {
                    throw new IllegalArgumentException(
                            "receipt event closure is incomplete or cross-revision");
                }
            }
        }
        if (state.processedCommands().size() != state.stateRevision()
                || coveredEvents.size() != events.size()
                || !coveredEvents.equals(events.keySet())) {
            throw new IllegalArgumentException(
                    "transaction receipt journal does not exactly close state revisions and events");
        }
    }

    private static void verifyNested(ObjectMapper mapper, SessionStateSpace state) {
        verifyWorldNested(mapper, state);
        for (SessionStateSpace.StateTransitionEvent event : state.committedEvents()) {
            if (event.fingerprint().isBlank()
                    || !event.fingerprint().equals(
                    sealEvent(mapper, event).fingerprint())) {
                throw new IllegalArgumentException("session event fingerprint mismatch");
            }
        }
        for (SessionStateSpace.TransactionReceipt receipt : state.processedCommands()) {
            if (receipt.fingerprint().isBlank()
                    || !receipt.fingerprint().equals(
                    sealReceipt(mapper, receipt).fingerprint())) {
                throw new IllegalArgumentException("session receipt fingerprint mismatch");
            }
            String response = ProtocolFingerprint.ofBounded(
                    mapper, receipt.response(), MAXIMUM_RESPONSE_BYTES);
            if (!response.equals(receipt.responseFingerprint())) {
                throw new IllegalArgumentException(
                        "session receipt response fingerprint mismatch");
            }
        }
    }

    private static void verifyWorldNested(ObjectMapper mapper, SessionStateSpace state) {
        for (SessionStateSpace.EntitySnapshot entity : state.entities()) {
            if (entity.fingerprint().isBlank()
                    || !entity.fingerprint().equals(
                    sealEntity(mapper, entity).fingerprint())) {
                throw new IllegalArgumentException("session entity fingerprint mismatch");
            }
        }
        for (SessionStateSpace.EntityTombstone tombstone : state.tombstones()) {
            if (tombstone.fingerprint().isBlank()
                    || !tombstone.fingerprint().equals(
                    sealTombstone(mapper, tombstone).fingerprint())) {
                throw new IllegalArgumentException("session tombstone fingerprint mismatch");
            }
        }
        for (SessionStateSpace.BusinessKeyBinding binding : state.businessKeyIndex()) {
            String components = ProtocolFingerprint.ofBounded(
                    mapper, binding.components(), 64 * 1024);
            if (!components.equals(binding.valueFingerprint())) {
                throw new IllegalArgumentException(
                        "business-key component fingerprint mismatch");
            }
        }
    }

    private static Map<String, Object> worldMaterial(SessionStateSpace state) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", state.schemaVersion());
        material.put("sessionId", state.sessionId());
        material.put("scope", state.scope());
        material.put("planFingerprint", state.planFingerprint());
        material.put("stateModelRef", state.stateModelRef());
        material.put("writeEffectRefs", state.writeEffectRefs());
        material.put("stateRevision", state.stateRevision());
        material.put("logicalClock", state.logicalClock());
        material.put("randomSeed", state.randomSeed());
        material.put("entities", state.entities());
        material.put("tombstones", state.tombstones());
        material.put("businessKeyIndex", state.businessKeyIndex());
        material.put("expiresAt", state.expiresAt());
        return Map.copyOf(material);
    }

}
