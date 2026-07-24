package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpace;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpec;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpecIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free projection of one durable graph-embedded Session transaction.
 *
 * <p>The projection is built at the resolver boundary while before/after payloads are ephemeral.
 * It retains no command input, response, entity value, entity id, or idempotency key. Exact
 * fingerprints and bounded transition metadata are sufficient for later receipt/event closure
 * verification against the signed Session evidence.</p>
 *
 * @param writeEffectRef exact effect executed by the node
 * @param initialStateRef exact state head observed before the command
 * @param finalStateRef exact state head visible after the command
 * @param revisionBefore state revision observed before the command
 * @param revisionAfter state revision visible after the command
 * @param initialWorldFingerprint business-world fingerprint before the command
 * @param finalWorldFingerprint business-world fingerprint after the command
 * @param initialLogicalClock logical time before the command
 * @param finalLogicalClock logical time after the command
 * @param idempotencyKeyFingerprint hash of the raw command key
 * @param commandFingerprint exact command identity sealed by the Session kernel
 * @param receiptFingerprint exact committed receipt
 * @param responseFingerprint exact command response identity
 * @param resultingWorldFingerprint world identity claimed by the receipt
 * @param committedAt governed logical commit time
 * @param replayed whether an existing receipt was returned without a new revision
 * @param events exact payload-free event closure of the receipt
 */
public record MirrorStateTransitionObservation(
        MirrorArtifactRef writeEffectRef,
        MirrorArtifactRef initialStateRef,
        MirrorArtifactRef finalStateRef,
        long revisionBefore,
        long revisionAfter,
        String initialWorldFingerprint,
        String finalWorldFingerprint,
        Instant initialLogicalClock,
        Instant finalLogicalClock,
        String idempotencyKeyFingerprint,
        String commandFingerprint,
        String receiptFingerprint,
        String responseFingerprint,
        String resultingWorldFingerprint,
        Instant committedAt,
        boolean replayed,
        List<Event> events
) {
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates complete revision, state-head, receipt, and event closure coordinates. */
    public MirrorStateTransitionObservation {
        writeEffectRef = kind(
                writeEffectRef, "WRITE_EFFECT", "writeEffectRef");
        initialStateRef = kind(
                initialStateRef, "SESSION_STATE", "initialStateRef");
        finalStateRef = kind(
                finalStateRef, "SESSION_STATE", "finalStateRef");
        if (revisionBefore < 0 || revisionAfter < revisionBefore
                || initialStateRef.revision()
                != Math.addExact(revisionBefore, 1)
                || finalStateRef.revision()
                != Math.addExact(revisionAfter, 1)) {
            throw new IllegalArgumentException(
                    "transition observation has inconsistent state revisions");
        }
        initialWorldFingerprint = fingerprint(
                initialWorldFingerprint, "initialWorldFingerprint");
        finalWorldFingerprint = fingerprint(
                finalWorldFingerprint, "finalWorldFingerprint");
        initialLogicalClock = Objects.requireNonNull(
                initialLogicalClock, "initialLogicalClock");
        finalLogicalClock = Objects.requireNonNull(
                finalLogicalClock, "finalLogicalClock");
        idempotencyKeyFingerprint = fingerprint(
                idempotencyKeyFingerprint,
                "idempotencyKeyFingerprint");
        commandFingerprint = fingerprint(
                commandFingerprint, "commandFingerprint");
        receiptFingerprint = fingerprint(
                receiptFingerprint, "receiptFingerprint");
        responseFingerprint = fingerprint(
                responseFingerprint, "responseFingerprint");
        resultingWorldFingerprint = fingerprint(
                resultingWorldFingerprint,
                "resultingWorldFingerprint");
        committedAt = Objects.requireNonNull(
                committedAt, "committedAt");
        events = events == null ? List.of() : List.copyOf(events);
        if (events.isEmpty() || events.size() > 128
                || events.stream().map(Event::eventIdFingerprint)
                .distinct().count() != events.size()) {
            throw new IllegalArgumentException(
                    "transition observation requires unique bounded events");
        }
        if (replayed) {
            if (!initialStateRef.equals(finalStateRef)
                    || revisionBefore != revisionAfter
                    || !initialWorldFingerprint.equals(
                    finalWorldFingerprint)
                    || !initialLogicalClock.equals(
                    finalLogicalClock)) {
                throw new IllegalArgumentException(
                        "replayed transition must not change the run state head");
            }
        } else if (revisionAfter
                != Math.addExact(revisionBefore, 1)
                || !resultingWorldFingerprint.equals(
                finalWorldFingerprint)) {
            throw new IllegalArgumentException(
                    "new transition must advance one revision to its receipt world");
        }
    }

    /**
     * Projects ephemeral before/after state into a payload-free observation.
     *
     * @param mapper canonical protocol mapper
     * @param effect exact executed write effect
     * @param execution verified durable progression
     * @param responseFingerprint independently calculated node-output fingerprint
     * @return payload-free transition observation
     */
    public static MirrorStateTransitionObservation project(
            ObjectMapper mapper,
            WriteEffectSpec effect,
            MirrorStateRunSession.Execution execution,
            String responseFingerprint) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(execution, "execution");
        SessionStateSpace before = execution.before().state();
        SessionStateSpace after = execution.after().state();
        SessionStateSpace.TransactionReceipt receipt =
                execution.receipt();
        List<SessionStateSpace.StateTransitionEvent> receiptEvents =
                execution.after().state().committedEvents().stream()
                        .filter(event -> receipt.eventIds()
                                .contains(event.eventId()))
                        .toList();
        if (receiptEvents.size() != receipt.eventIds().size()) {
            throw new IllegalArgumentException(
                    "transaction receipt event closure is incomplete");
        }
        return new MirrorStateTransitionObservation(
                WriteEffectSpecIntegrity.reference(effect),
                stateRef(before), stateRef(after),
                before.stateRevision(), after.stateRevision(),
                before.worldFingerprint(),
                after.worldFingerprint(),
                before.logicalClock(), after.logicalClock(),
                ProtocolFingerprint.of(
                        mapper, receipt.idempotencyKey()),
                receipt.commandFingerprint(),
                receipt.fingerprint(),
                responseFingerprint,
                receipt.resultingWorldFingerprint(),
                receipt.committedAt(), execution.replayed(),
                receiptEvents.stream().map(event ->
                        new Event(
                                ProtocolFingerprint.of(
                                        mapper, event.eventId()),
                                event.stateRevision(),
                                event.mutationId(),
                                event.operation(),
                                event.entityKey().entityType(),
                                ProtocolFingerprint.of(
                                        mapper,
                                        List.of(
                                                event.entityKey()
                                                        .entityType(),
                                                event.entityKey()
                                                        .entityId())),
                                event.beforeFingerprint(),
                                event.afterFingerprint(),
                                event.occurredAt(),
                                event.fingerprint()))
                        .toList());
    }

    private static MirrorArtifactRef stateRef(
            SessionStateSpace state) {
        return new MirrorArtifactRef(
                "SESSION_STATE", state.sessionId(),
                Math.addExact(state.stateRevision(), 1),
                state.fingerprint());
    }

    /**
     * Payload-free projection of one entity transition in a committed receipt.
     *
     * @param eventIdFingerprint hash of the internal event identity
     * @param stateRevision committed transaction revision
     * @param mutationId owner-governed mutation alias
     * @param operation transition operation
     * @param entityType owner-governed entity type
     * @param entityIdentityFingerprint hash of entity type and raw entity id
     * @param beforeFingerprint previous entity fingerprint, or blank
     * @param afterFingerprint resulting entity fingerprint, or blank
     * @param occurredAt governed logical transition time
     * @param eventFingerprint exact sealed event fingerprint
     */
    public record Event(
            String eventIdFingerprint,
            long stateRevision,
            String mutationId,
            SessionStateSpace.TransitionOperation operation,
            String entityType,
            String entityIdentityFingerprint,
            String beforeFingerprint,
            String afterFingerprint,
            Instant occurredAt,
            String eventFingerprint
    ) {
        /** Validates one payload-free event projection. */
        public Event {
            eventIdFingerprint = fingerprint(
                    eventIdFingerprint, "eventIdFingerprint");
            if (stateRevision < 1) {
                throw new IllegalArgumentException(
                        "event stateRevision must be positive");
            }
            mutationId = bounded(
                    mutationId, "mutationId", 512);
            operation = Objects.requireNonNull(
                    operation, "operation");
            entityType = bounded(
                    entityType, "entityType", 512);
            entityIdentityFingerprint = fingerprint(
                    entityIdentityFingerprint,
                    "entityIdentityFingerprint");
            beforeFingerprint = optionalFingerprint(
                    beforeFingerprint, "beforeFingerprint");
            afterFingerprint = optionalFingerprint(
                    afterFingerprint, "afterFingerprint");
            occurredAt = Objects.requireNonNull(
                    occurredAt, "occurredAt");
            eventFingerprint = fingerprint(
                    eventFingerprint, "eventFingerprint");
        }
    }

    private static MirrorArtifactRef kind(
            MirrorArtifactRef value,
            String expected,
            String field) {
        MirrorArtifactRef ref = Objects.requireNonNull(value, field);
        if (!expected.equals(ref.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + expected);
        }
        return ref;
    }

    private static String fingerprint(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }

    private static String optionalFingerprint(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or canonical SHA-256");
        }
        return normalized;
    }

    private static String bounded(
            String value, String field, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()
                || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must be non-blank and bounded");
        }
        return normalized;
    }
}
