package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable payload-bearing snapshot of one isolated virtual business world.
 *
 * <p>This artifact belongs in the encrypted mirror data plane, never in the Resource Gateway
 * control database. Maps from the design are represented as sorted lists on the wire so duplicate
 * keys cannot disappear during JSON decoding. {@code worldFingerprint} covers current business
 * state; {@code fingerprint} additionally covers the append-only event and idempotency journals.</p>
 *
 * @param schemaVersion session-state protocol version
 * @param sessionId stable isolated session identity
 * @param scope exact enterprise namespace
 * @param planFingerprint exact mirror-plan generation
 * @param stateModelRef exact state model
 * @param writeEffectRefs exact admitted write effects
 * @param stateRevision monotonic committed transaction revision
 * @param logicalClock deterministic current logical instant
 * @param randomSeed deterministic session seed
 * @param entities sorted live entities
 * @param tombstones sorted deleted identities
 * @param businessKeyIndex sorted unique business-key bindings
 * @param committedEvents append-only transition journal
 * @param processedCommands append-only idempotency receipts
 * @param expiresAt hard session expiry
 * @param worldFingerprint canonical current-world fingerprint
 * @param fingerprint canonical complete session fingerprint
 */
public record SessionStateSpace(
        String schemaVersion,
        String sessionId,
        CapabilitySnapshot.Scope scope,
        String planFingerprint,
        MirrorArtifactRef stateModelRef,
        List<MirrorArtifactRef> writeEffectRefs,
        long stateRevision,
        Instant logicalClock,
        long randomSeed,
        List<EntitySnapshot> entities,
        List<EntityTombstone> tombstones,
        List<BusinessKeyBinding> businessKeyIndex,
        List<StateTransitionEvent> committedEvents,
        List<TransactionReceipt> processedCommands,
        Instant expiresAt,
        String worldFingerprint,
        String fingerprint
) {
    /** Current session-state wire version. */
    public static final String SCHEMA_VERSION = "resourceGateway.sessionStateSpace.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Normalizes deterministic ordering and freezes every embedded JSON value. */
    public SessionStateSpace {
        schemaVersion = version(schemaVersion);
        sessionId = identifier(sessionId, "sessionId");
        scope = Objects.requireNonNull(scope, "scope");
        planFingerprint = MirrorStateProtocolSupport.fingerprint(
                planFingerprint, "planFingerprint");
        stateModelRef = exactKind(stateModelRef, "STATE_MODEL", "stateModelRef");
        writeEffectRefs = writeEffectRefs == null ? List.of() : writeEffectRefs.stream()
                .map(ref -> exactKind(ref, "WRITE_EFFECT", "writeEffectRef"))
                .sorted(Comparator.comparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision))
                .toList();
        if (writeEffectRefs.isEmpty() || writeEffectRefs.size() > 256) {
            throw new IllegalArgumentException(
                    "session requires between 1 and 256 write-effect refs");
        }
        if (stateRevision < 0) {
            throw new IllegalArgumentException("stateRevision must not be negative");
        }
        logicalClock = Objects.requireNonNull(logicalClock, "logicalClock");
        entities = sorted(entities, Comparator.comparing(EntitySnapshot::key));
        tombstones = sorted(tombstones, Comparator.comparing(EntityTombstone::key));
        businessKeyIndex = sorted(businessKeyIndex,
                Comparator.comparing(BusinessKeyBinding::keyName)
                        .thenComparing(BusinessKeyBinding::valueFingerprint));
        committedEvents = committedEvents == null ? List.of() : List.copyOf(committedEvents);
        processedCommands = processedCommands == null ? List.of() : processedCommands.stream()
                .sorted(Comparator.comparingLong(TransactionReceipt::revisionAfter))
                .toList();
        if (entities.size() > 100_000 || tombstones.size() > 100_000
                || businessKeyIndex.size() > 500_000
                || committedEvents.size() > 1_000_000
                || processedCommands.size() > 1_000_000) {
            throw new IllegalArgumentException("session state exceeds a protocol collection bound");
        }
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        worldFingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                worldFingerprint, "worldFingerprint");
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(fingerprint, "fingerprint");
    }

    /**
     * Stable entity identity within one session.
     *
     * @param entityType state-model type
     * @param entityId evaluated entity identity
     */
    public record EntityKey(String entityType, String entityId)
            implements Comparable<EntityKey> {
        /** Validates identity coordinates. */
        public EntityKey {
            entityType = identifier(entityType, "entityType");
            entityId = identifier(entityId, "entityId");
        }

        @Override
        public int compareTo(EntityKey other) {
            int type = entityType.compareTo(other.entityType);
            return type != 0 ? type : entityId.compareTo(other.entityId);
        }
    }

    /**
     * One immutable live entity revision.
     *
     * @param key entity identity
     * @param version positive entity-local version
     * @param value detached JSON object
     * @param fingerprint canonical entity fingerprint
     */
    public record EntitySnapshot(
            EntityKey key,
            long version,
            Map<String, Object> value,
            String fingerprint
    ) {
        /** Freezes entity JSON and validates identity. */
        public EntitySnapshot {
            key = Objects.requireNonNull(key, "key");
            if (version < 1) {
                throw new IllegalArgumentException("entity version must be positive");
            }
            value = ProtocolJsonValue.freezeMap(value);
            fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                    fingerprint, "entity fingerprint");
        }

        /** @return a copy carrying a replacement fingerprint */
        public EntitySnapshot withFingerprint(String value) {
            return new EntitySnapshot(key, version, this.value, value);
        }
    }

    /**
     * Irreversible in-session identity tombstone.
     *
     * @param key deleted entity identity
     * @param deletedRevision transaction revision that deleted the entity
     * @param previousFingerprint last live entity fingerprint
     * @param deletedAt governed logical deletion time
     * @param fingerprint canonical tombstone fingerprint
     */
    public record EntityTombstone(
            EntityKey key,
            long deletedRevision,
            String previousFingerprint,
            Instant deletedAt,
            String fingerprint
    ) {
        /** Validates one deletion fact. */
        public EntityTombstone {
            key = Objects.requireNonNull(key, "key");
            if (deletedRevision < 1) {
                throw new IllegalArgumentException("deletedRevision must be positive");
            }
            previousFingerprint = MirrorStateProtocolSupport.fingerprint(
                    previousFingerprint, "previousFingerprint");
            deletedAt = Objects.requireNonNull(deletedAt, "deletedAt");
            fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                    fingerprint, "tombstone fingerprint");
        }

        /** @return a copy carrying a replacement fingerprint */
        public EntityTombstone withFingerprint(String value) {
            return new EntityTombstone(
                    key, deletedRevision, previousFingerprint, deletedAt, value);
        }
    }

    /**
     * Unique composite business-key lookup.
     *
     * @param keyName exact state-model key name
     * @param components detached ordered raw key components
     * @param valueFingerprint canonical component fingerprint
     * @param entityKey bound entity
     */
    public record BusinessKeyBinding(
            String keyName,
            List<Object> components,
            String valueFingerprint,
            EntityKey entityKey
    ) {
        /** Freezes a bounded business-key value. */
        public BusinessKeyBinding {
            keyName = identifier(keyName, "business key name");
            components = freezeList(components);
            if (components.isEmpty() || components.size() > 16) {
                throw new IllegalArgumentException(
                        "business key requires between 1 and 16 components");
            }
            valueFingerprint = MirrorStateProtocolSupport.fingerprint(
                    valueFingerprint, "business key valueFingerprint");
            entityKey = Objects.requireNonNull(entityKey, "entityKey");
        }
    }

    /**
     * Payload-free transition event for one entity mutation.
     *
     * @param eventId deterministic event identity
     * @param stateRevision committed transaction revision
     * @param mutationId source mutation alias
     * @param operation transition operation, including baseline copy-in
     * @param entityKey affected identity
     * @param beforeFingerprint previous entity fingerprint, blank for create/copy-in
     * @param afterFingerprint resulting entity fingerprint, blank for delete
     * @param occurredAt governed logical time
     * @param fingerprint canonical event fingerprint
     */
    public record StateTransitionEvent(
            String eventId,
            long stateRevision,
            String mutationId,
            TransitionOperation operation,
            EntityKey entityKey,
            String beforeFingerprint,
            String afterFingerprint,
            Instant occurredAt,
            String fingerprint
    ) {
        /** Validates one payload-free transition fact. */
        public StateTransitionEvent {
            eventId = identifier(eventId, "eventId");
            if (stateRevision < 1) {
                throw new IllegalArgumentException("event stateRevision must be positive");
            }
            mutationId = identifier(mutationId, "mutationId");
            operation = Objects.requireNonNull(operation, "operation");
            entityKey = Objects.requireNonNull(entityKey, "entityKey");
            beforeFingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                    beforeFingerprint, "beforeFingerprint");
            afterFingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                    afterFingerprint, "afterFingerprint");
            if ((operation == TransitionOperation.CREATE
                    || operation == TransitionOperation.COPY_IN)
                    && !beforeFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        operation + " transition must not have a before fingerprint");
            }
            if (operation == TransitionOperation.DELETE && !afterFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "DELETE transition must not have an after fingerprint");
            }
            if ((operation == TransitionOperation.UPDATE
                    || operation == TransitionOperation.DELETE)
                    && beforeFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        operation + " transition requires a before fingerprint");
            }
            if (operation != TransitionOperation.DELETE && afterFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        operation + " transition requires an after fingerprint");
            }
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                    fingerprint, "event fingerprint");
        }

        /** @return a copy carrying a replacement fingerprint */
        public StateTransitionEvent withFingerprint(String value) {
            return new StateTransitionEvent(eventId, stateRevision, mutationId, operation,
                    entityKey, beforeFingerprint, afterFingerprint, occurredAt, value);
        }
    }

    /** Entity transition kinds represented in the append-only state journal. */
    public enum TransitionOperation {
        COPY_IN,
        CREATE,
        UPDATE,
        DELETE,
        UPSERT
    }

    /**
     * Exact idempotency receipt for one committed transaction.
     *
     * @param idempotencyKey caller command identity
     * @param commandFingerprint exact write effect and input fingerprint
     * @param revisionBefore world revision observed by the transaction
     * @param revisionAfter committed world revision
     * @param eventIds exact event closure
     * @param response detached response payload
     * @param responseFingerprint canonical response fingerprint
     * @param resultingWorldFingerprint committed world fingerprint
     * @param committedAt governed logical commit time
     * @param fingerprint canonical receipt fingerprint
     */
    public record TransactionReceipt(
            String idempotencyKey,
            String commandFingerprint,
            long revisionBefore,
            long revisionAfter,
            List<String> eventIds,
            Object response,
            String responseFingerprint,
            String resultingWorldFingerprint,
            Instant committedAt,
            String fingerprint
    ) {
        /** Freezes response and validates revision closure. */
        public TransactionReceipt {
            idempotencyKey = identifier(idempotencyKey, "idempotencyKey");
            commandFingerprint = MirrorStateProtocolSupport.fingerprint(
                    commandFingerprint, "commandFingerprint");
            if (revisionBefore < 0 || revisionAfter != revisionBefore + 1) {
                throw new IllegalArgumentException(
                        "transaction receipt must advance exactly one state revision");
            }
            eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
            if (eventIds.isEmpty() || eventIds.size() > 128) {
                throw new IllegalArgumentException(
                        "transaction receipt requires between 1 and 128 events");
            }
            response = ProtocolJsonValue.freeze(response);
            responseFingerprint = MirrorStateProtocolSupport.fingerprint(
                    responseFingerprint, "responseFingerprint");
            resultingWorldFingerprint = MirrorStateProtocolSupport.fingerprint(
                    resultingWorldFingerprint, "resultingWorldFingerprint");
            committedAt = Objects.requireNonNull(committedAt, "committedAt");
            fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                    fingerprint, "receipt fingerprint");
        }

        /** @return a copy carrying a replacement fingerprint */
        public TransactionReceipt withFingerprint(String value) {
            return new TransactionReceipt(idempotencyKey, commandFingerprint, revisionBefore,
                    revisionAfter, eventIds, response, responseFingerprint,
                    resultingWorldFingerprint, committedAt, value);
        }
    }

    /**
     * Replaces the mutable-world and journal portions while clearing top-level fingerprints.
     *
     * @param revision new committed revision
     * @param clock new logical clock
     * @param liveEntities live entities
     * @param deletedEntities tombstones
     * @param keyIndex business-key bindings
     * @param events committed event journal
     * @param commands processed-command journal
     * @return unsealed copy
     */
    public SessionStateSpace withWorld(
            long revision,
            Instant clock,
            List<EntitySnapshot> liveEntities,
            List<EntityTombstone> deletedEntities,
            List<BusinessKeyBinding> keyIndex,
            List<StateTransitionEvent> events,
            List<TransactionReceipt> commands) {
        return new SessionStateSpace(schemaVersion, sessionId, scope, planFingerprint,
                stateModelRef, writeEffectRefs, revision, clock, randomSeed, liveEntities,
                deletedEntities, keyIndex, events, commands, expiresAt, "", "");
    }

    /** @return an unsealed copy carrying a new hard expiry */
    public SessionStateSpace withExpiry(Instant value) {
        return new SessionStateSpace(schemaVersion, sessionId, scope, planFingerprint,
                stateModelRef, writeEffectRefs, stateRevision, logicalClock, randomSeed, entities,
                tombstones, businessKeyIndex, committedEvents, processedCommands, value, "", "");
    }

    /** @return a copy carrying replacement top-level fingerprints */
    public SessionStateSpace withFingerprints(String world, String complete) {
        return new SessionStateSpace(schemaVersion, sessionId, scope, planFingerprint,
                stateModelRef, writeEffectRefs, stateRevision, logicalClock, randomSeed, entities,
                tombstones, businessKeyIndex, committedEvents, processedCommands, expiresAt,
                world, complete);
    }

    private static MirrorArtifactRef exactKind(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef ref = Objects.requireNonNull(value, field);
        if (!kind.equals(ref.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return ref;
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank() ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + normalized);
        }
        return normalized;
    }

    private static String identifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static <T> List<T> sorted(List<T> values, Comparator<T> comparator) {
        return values == null ? List.of() : values.stream().sorted(comparator).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> freezeList(List<?> values) {
        if (values == null) {
            return List.of();
        }
        Object frozen = ProtocolJsonValue.freeze(values);
        return (List<Object>) frozen;
    }
}
