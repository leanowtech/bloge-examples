package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Payload-free read/write state evidence for one Session-backed mirror DAG run.
 *
 * <p>The artifact binds the initial and final durable Session heads, every state-backed plan site,
 * each read to the exact revision it observed, and each virtual write to its exact effect,
 * idempotency-safe receipt, and transition-event closure. Business payloads, entity identifiers,
 * business-key components, command inputs, responses, and raw idempotency keys are never retained.
 * Read-only v1 evidence remains a separate immutable protocol type.</p>
 *
 * @param schemaVersion read/write state-evidence protocol version
 * @param stateEvidenceFingerprint canonical fingerprint with this field blanked
 * @param runId exact terminal mirror run
 * @param planFingerprint exact sealed mirror plan
 * @param sessionStateRef initial Session state head admitted before scheduling
 * @param finalSessionStateRef final Session state head visible after execution
 * @param stateModelRef exact state model used by every interaction
 * @param stateRevision initial committed state revision
 * @param finalStateRevision final committed state revision
 * @param worldFingerprint initial business-world fingerprint
 * @param finalWorldFingerprint final business-world fingerprint
 * @param logicalClock initial deterministic Session logical time
 * @param finalLogicalClock final deterministic Session logical time
 * @param mode state interaction mode
 * @param statefulBindings complete read/write binding closure
 * @param accesses ordered payload-free read observations
 * @param transitions ordered payload-free write observations
 * @param limitations bounded state-evidence limitations
 */
public record MirrorStateTransitionRunEvidence(
        String schemaVersion,
        String stateEvidenceFingerprint,
        String runId,
        String planFingerprint,
        MirrorArtifactRef sessionStateRef,
        MirrorArtifactRef finalSessionStateRef,
        MirrorArtifactRef stateModelRef,
        long stateRevision,
        long finalStateRevision,
        String worldFingerprint,
        String finalWorldFingerprint,
        Instant logicalClock,
        Instant finalLogicalClock,
        Mode mode,
        List<StatefulBinding> statefulBindings,
        List<StateAccess> accesses,
        List<StateTransition> transitions,
        List<String> limitations
) implements MirrorStateEvidence {
    /** Current payload-free read/write state-evidence version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorStateRunEvidence.v2";
    /** Maximum state-backed invocation sites admitted to one run. */
    public static final int MAXIMUM_BINDINGS =
            MirrorPlan.MAXIMUM_EXTERNAL_BINDINGS;
    /** Maximum read or write observations admitted to one run. */
    public static final int MAXIMUM_INTERACTIONS =
            MirrorRunEvidence.MAXIMUM_RESOLUTIONS;
    /** Maximum bounded state-evidence limitations. */
    public static final int MAXIMUM_LIMITATIONS = 64;

    /** Stateful DAG execution semantics represented by v2. */
    public enum Mode {
        SERIALIZABLE_READ_WRITE
    }

    /** Exact lowering kind attached to one state-backed invocation site. */
    public enum Interaction {
        READ,
        WRITE
    }

    /** Result of consulting the current Session state head. */
    public enum AccessOutcome {
        LIVE_ENTITY,
        ABSENT,
        TOMBSTONED
    }

    /** Validates initial/final heads, deterministic ordering, and complete interaction closure. */
    public MirrorStateTransitionRunEvidence {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported mirror state transition evidence version");
        }
        stateEvidenceFingerprint =
                MirrorStateProtocolSupport.optionalFingerprint(
                        stateEvidenceFingerprint,
                        "stateEvidenceFingerprint");
        runId = required(runId, "runId", 512);
        planFingerprint = MirrorStateProtocolSupport.fingerprint(
                planFingerprint, "planFingerprint");
        sessionStateRef = kind(
                sessionStateRef, "SESSION_STATE",
                "sessionStateRef");
        finalSessionStateRef = kind(
                finalSessionStateRef, "SESSION_STATE",
                "finalSessionStateRef");
        stateModelRef = kind(
                stateModelRef, "STATE_MODEL",
                "stateModelRef");
        if (!sessionStateRef.id().equals(
                finalSessionStateRef.id())
                || stateRevision < 0
                || finalStateRevision < stateRevision
                || sessionStateRef.revision()
                != Math.addExact(stateRevision, 1)
                || finalSessionStateRef.revision()
                != Math.addExact(finalStateRevision, 1)) {
            throw new IllegalArgumentException(
                    "state transition evidence has inconsistent Session revisions");
        }
        worldFingerprint = MirrorStateProtocolSupport.fingerprint(
                worldFingerprint, "worldFingerprint");
        finalWorldFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        finalWorldFingerprint,
                        "finalWorldFingerprint");
        logicalClock = Objects.requireNonNull(
                logicalClock, "logicalClock");
        finalLogicalClock = Objects.requireNonNull(
                finalLogicalClock, "finalLogicalClock");
        if (finalLogicalClock.isBefore(logicalClock)) {
            throw new IllegalArgumentException(
                    "final logical clock must not precede the initial clock");
        }
        mode = Objects.requireNonNull(mode, "mode");
        statefulBindings = ordered(
                statefulBindings,
                Comparator.comparing(
                        StatefulBinding::invocationSiteId)
                        .thenComparing(
                                StatefulBinding::graphPath),
                "statefulBindings", MAXIMUM_BINDINGS);
        if (statefulBindings.isEmpty()
                || unique(statefulBindings.stream()
                .map(StatefulBinding::invocationSiteId)
                .toList()).size()
                != statefulBindings.size()) {
            throw new IllegalArgumentException(
                    "stateful bindings must be non-empty and site-unique");
        }
        accesses = ordered(
                accesses, StateAccess.ORDER,
                "accesses", MAXIMUM_INTERACTIONS);
        transitions = ordered(
                transitions, StateTransition.ORDER,
                "transitions", MAXIMUM_INTERACTIONS);
        if (unique(accesses.stream()
                .map(StateAccess::coordinate).toList()).size()
                != accesses.size()
                || unique(transitions.stream()
                .map(StateTransition::coordinate).toList()).size()
                != transitions.size()) {
            throw new IllegalArgumentException(
                    "state interaction coordinates must be unique by kind");
        }
        limitations = strings(
                limitations, "limitations",
                MAXIMUM_LIMITATIONS, 512);
        validateClosure(
                statefulBindings, accesses, transitions,
                sessionStateRef, finalSessionStateRef,
                stateRevision, finalStateRevision);
    }

    /** @return a copy carrying a replacement canonical fingerprint */
    public MirrorStateTransitionRunEvidence withFingerprint(
            String value) {
        return new MirrorStateTransitionRunEvidence(
                schemaVersion, value, runId, planFingerprint,
                sessionStateRef, finalSessionStateRef,
                stateModelRef, stateRevision,
                finalStateRevision, worldFingerprint,
                finalWorldFingerprint, logicalClock,
                finalLogicalClock, mode, statefulBindings,
                accesses, transitions, limitations);
    }

    /**
     * Exact read or write lowering attached to one plan site.
     *
     * @param invocationSiteId stable BLOGE invocation site
     * @param graphPath exact graph owning the invocation
     * @param capabilityRef exact state-backed capability
     * @param interaction read or write lowering kind
     * @param stateReadSpecRef exact read spec; present only for reads
     * @param writeEffectRef exact write effect; present only for writes
     */
    public record StatefulBinding(
            String invocationSiteId,
            String graphPath,
            MirrorArtifactRef capabilityRef,
            Interaction interaction,
            MirrorArtifactRef stateReadSpecRef,
            MirrorArtifactRef writeEffectRef
    ) {
        /** Enforces exactly one lowering artifact for the declared interaction. */
        public StatefulBinding {
            invocationSiteId = required(
                    invocationSiteId,
                    "invocationSiteId", 2_048);
            graphPath = MirrorStateTransitionRunEvidence
                    .graphPath(graphPath);
            capabilityRef = kind(
                    capabilityRef, "CAPABILITY",
                    "capabilityRef");
            interaction = Objects.requireNonNull(
                    interaction, "interaction");
            if (interaction == Interaction.READ) {
                stateReadSpecRef = kind(
                        stateReadSpecRef, "STATE_READ_SPEC",
                        "stateReadSpecRef");
                if (writeEffectRef != null) {
                    throw new IllegalArgumentException(
                            "read binding cannot carry a write effect");
                }
            } else {
                writeEffectRef = kind(
                        writeEffectRef, "WRITE_EFFECT",
                        "writeEffectRef");
                if (stateReadSpecRef != null) {
                    throw new IllegalArgumentException(
                            "write binding cannot carry a read spec");
                }
            }
        }
    }

    /**
     * One payload-free state read at an exact in-run state head.
     *
     * @param invocationSiteId stable BLOGE invocation site
     * @param graphPath exact graph owning the invocation
     * @param correlationKey foreach, loop, or business coordinate
     * @param occurrence one-based invocation occurrence
     * @param attempt one-based delegate attempt
     * @param capabilityRef exact state-backed read capability
     * @param stateReadSpecRef exact query-to-state lowering
     * @param observedStateRef exact Session head read by this invocation
     * @param observedStateRevision exact state revision read
     * @param observedWorldFingerprint exact world read
     * @param observedLogicalClock exact deterministic time read
     * @param requestFingerprint canonical invocation input identity
     * @param businessKeyFingerprint hash of ordered business-key components
     * @param outcome live, absent, or tombstoned
     * @param stateRecordFingerprint entity or tombstone fingerprint; blank for absent
     * @param projectedOutputFingerprint output fingerprint; present only for live
     * @param errorCode terminal tombstone code; blank otherwise
     */
    public record StateAccess(
            String invocationSiteId,
            String graphPath,
            String correlationKey,
            int occurrence,
            int attempt,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef stateReadSpecRef,
            MirrorArtifactRef observedStateRef,
            long observedStateRevision,
            String observedWorldFingerprint,
            Instant observedLogicalClock,
            String requestFingerprint,
            String businessKeyFingerprint,
            AccessOutcome outcome,
            String stateRecordFingerprint,
            String projectedOutputFingerprint,
            String errorCode
    ) {
        private static final Comparator<StateAccess> ORDER =
                Comparator.comparing(
                        StateAccess::invocationSiteId)
                        .thenComparing(StateAccess::graphPath)
                        .thenComparing(
                                StateAccess::correlationKey)
                        .thenComparingInt(
                                StateAccess::occurrence)
                        .thenComparingInt(
                                StateAccess::attempt);

        /** Validates one payload-free state read observation. */
        public StateAccess {
            invocationSiteId = required(
                    invocationSiteId,
                    "invocationSiteId", 2_048);
            graphPath = MirrorStateTransitionRunEvidence
                    .graphPath(graphPath);
            correlationKey = bounded(
                    correlationKey,
                    "correlationKey", 1_024);
            positive(occurrence, attempt);
            capabilityRef = kind(
                    capabilityRef, "CAPABILITY",
                    "capabilityRef");
            stateReadSpecRef = kind(
                    stateReadSpecRef, "STATE_READ_SPEC",
                    "stateReadSpecRef");
            observedStateRef = kind(
                    observedStateRef, "SESSION_STATE",
                    "observedStateRef");
            if (observedStateRevision < 0
                    || observedStateRef.revision()
                    != Math.addExact(
                    observedStateRevision, 1)) {
                throw new IllegalArgumentException(
                        "observed state reference revision is inconsistent");
            }
            observedWorldFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            observedWorldFingerprint,
                            "observedWorldFingerprint");
            observedLogicalClock =
                    Objects.requireNonNull(
                            observedLogicalClock,
                            "observedLogicalClock");
            requestFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            requestFingerprint,
                            "requestFingerprint");
            businessKeyFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            businessKeyFingerprint,
                            "businessKeyFingerprint");
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            stateRecordFingerprint =
                    MirrorStateProtocolSupport
                            .optionalFingerprint(
                                    stateRecordFingerprint,
                                    "stateRecordFingerprint");
            projectedOutputFingerprint =
                    MirrorStateProtocolSupport
                            .optionalFingerprint(
                                    projectedOutputFingerprint,
                                    "projectedOutputFingerprint");
            errorCode = bounded(
                    errorCode, "errorCode", 256);
            switch (outcome) {
                case LIVE_ENTITY -> {
                    if (stateRecordFingerprint.isBlank()
                            || projectedOutputFingerprint.isBlank()
                            || !errorCode.isBlank()) {
                        throw new IllegalArgumentException(
                                "live access requires record and output fingerprints");
                    }
                }
                case ABSENT -> {
                    if (!stateRecordFingerprint.isBlank()
                            || !projectedOutputFingerprint.isBlank()
                            || !errorCode.isBlank()) {
                        throw new IllegalArgumentException(
                                "absent access cannot claim record, output, or error");
                    }
                }
                case TOMBSTONED -> {
                    if (stateRecordFingerprint.isBlank()
                            || !projectedOutputFingerprint.isBlank()
                            || !MirrorStateRunEvidence
                            .MirrorSessionStateError
                            .ENTITY_TOMBSTONED
                            .equals(errorCode)) {
                        throw new IllegalArgumentException(
                                "tombstone access requires record and terminal error");
                    }
                }
            }
        }

        private String coordinate() {
            return MirrorStateTransitionRunEvidence.coordinate(
                    invocationSiteId, correlationKey,
                    occurrence, attempt);
        }
    }

    /**
     * One graph virtual-write invocation and exact durable receipt closure.
     *
     * @param invocationSiteId stable BLOGE invocation site
     * @param graphPath exact graph owning the invocation
     * @param correlationKey foreach, loop, or business coordinate
     * @param occurrence one-based invocation occurrence
     * @param attempt one-based delegate attempt
     * @param capabilityRef exact virtual-write capability
     * @param writeEffectRef exact lowering effect
     * @param initialStateRef state head observed before the command
     * @param finalStateRef state head visible after the command
     * @param revisionBefore state revision before the command
     * @param revisionAfter state revision after the command
     * @param initialWorldFingerprint world before the command
     * @param finalWorldFingerprint world after the command
     * @param initialLogicalClock logical time before the command
     * @param finalLogicalClock logical time after the command
     * @param requestFingerprint canonical invocation input identity
     * @param idempotencyKeyFingerprint hash of the raw command key
     * @param commandFingerprint exact Session command identity
     * @param receiptFingerprint exact committed receipt
     * @param responseFingerprint exact command output identity
     * @param resultingWorldFingerprint world claimed by the receipt
     * @param committedAt governed logical commit time
     * @param replayed whether an existing receipt was returned
     * @param events exact payload-free receipt event closure
     */
    public record StateTransition(
            String invocationSiteId,
            String graphPath,
            String correlationKey,
            int occurrence,
            int attempt,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef writeEffectRef,
            MirrorArtifactRef initialStateRef,
            MirrorArtifactRef finalStateRef,
            long revisionBefore,
            long revisionAfter,
            String initialWorldFingerprint,
            String finalWorldFingerprint,
            Instant initialLogicalClock,
            Instant finalLogicalClock,
            String requestFingerprint,
            String idempotencyKeyFingerprint,
            String commandFingerprint,
            String receiptFingerprint,
            String responseFingerprint,
            String resultingWorldFingerprint,
            Instant committedAt,
            boolean replayed,
            List<TransitionEvent> events
    ) {
        private static final Comparator<StateTransition> ORDER =
                Comparator.comparing(
                        StateTransition::invocationSiteId)
                        .thenComparing(
                                StateTransition::graphPath)
                        .thenComparing(
                                StateTransition::correlationKey)
                        .thenComparingInt(
                                StateTransition::occurrence)
                        .thenComparingInt(
                                StateTransition::attempt);

        /** Validates exact state progression and receipt-event closure. */
        public StateTransition {
            invocationSiteId = required(
                    invocationSiteId,
                    "invocationSiteId", 2_048);
            graphPath = MirrorStateTransitionRunEvidence
                    .graphPath(graphPath);
            correlationKey = bounded(
                    correlationKey,
                    "correlationKey", 1_024);
            positive(occurrence, attempt);
            capabilityRef = kind(
                    capabilityRef, "CAPABILITY",
                    "capabilityRef");
            writeEffectRef = kind(
                    writeEffectRef, "WRITE_EFFECT",
                    "writeEffectRef");
            initialStateRef = kind(
                    initialStateRef, "SESSION_STATE",
                    "initialStateRef");
            finalStateRef = kind(
                    finalStateRef, "SESSION_STATE",
                    "finalStateRef");
            if (!initialStateRef.id().equals(
                    finalStateRef.id())
                    || revisionBefore < 0
                    || revisionAfter < revisionBefore
                    || initialStateRef.revision()
                    != Math.addExact(revisionBefore, 1)
                    || finalStateRef.revision()
                    != Math.addExact(revisionAfter, 1)) {
                throw new IllegalArgumentException(
                        "transition state references are inconsistent");
            }
            initialWorldFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            initialWorldFingerprint,
                            "initialWorldFingerprint");
            finalWorldFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            finalWorldFingerprint,
                            "finalWorldFingerprint");
            initialLogicalClock =
                    Objects.requireNonNull(
                            initialLogicalClock,
                            "initialLogicalClock");
            finalLogicalClock =
                    Objects.requireNonNull(
                            finalLogicalClock,
                            "finalLogicalClock");
            requestFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            requestFingerprint,
                            "requestFingerprint");
            idempotencyKeyFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            idempotencyKeyFingerprint,
                            "idempotencyKeyFingerprint");
            commandFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            commandFingerprint,
                            "commandFingerprint");
            receiptFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            receiptFingerprint,
                            "receiptFingerprint");
            responseFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            responseFingerprint,
                            "responseFingerprint");
            resultingWorldFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            resultingWorldFingerprint,
                            "resultingWorldFingerprint");
            committedAt = Objects.requireNonNull(
                    committedAt, "committedAt");
            events = ordered(
                    events,
                    Comparator.comparing(
                            TransitionEvent::eventIdFingerprint),
                    "transition events", 128);
            if (events.isEmpty()
                    || unique(events.stream()
                    .map(TransitionEvent::eventIdFingerprint)
                    .toList()).size() != events.size()
                    || !replayed && events.stream().anyMatch(
                    event -> event.stateRevision()
                            != revisionAfter)) {
                throw new IllegalArgumentException(
                        "transition requires a unique bounded event closure");
            }
            if (replayed) {
                if (!initialStateRef.equals(finalStateRef)
                        || revisionBefore != revisionAfter
                        || !initialWorldFingerprint.equals(
                        finalWorldFingerprint)
                        || !initialLogicalClock.equals(
                        finalLogicalClock)) {
                    throw new IllegalArgumentException(
                            "replayed transition must not change the state head");
                }
            } else if (revisionAfter
                    != Math.addExact(revisionBefore, 1)
                    || !resultingWorldFingerprint.equals(
                    finalWorldFingerprint)
                    || events.stream().anyMatch(
                    event -> event.stateRevision()
                            != revisionAfter)) {
                throw new IllegalArgumentException(
                        "new transition must advance one exact revision");
            }
        }

        private String coordinate() {
            return MirrorStateTransitionRunEvidence.coordinate(
                    invocationSiteId, correlationKey,
                    occurrence, attempt);
        }
    }

    /**
     * Payload-free entity transition nested under one receipt.
     *
     * @param eventIdFingerprint hash of the internal event id
     * @param stateRevision committed transaction revision
     * @param mutationId owner-governed mutation alias
     * @param operation transition operation
     * @param entityType owner-governed entity type
     * @param entityIdentityFingerprint hash of type and raw entity id
     * @param beforeFingerprint previous entity fingerprint, or blank
     * @param afterFingerprint resulting entity fingerprint, or blank
     * @param occurredAt governed logical transition time
     * @param eventFingerprint exact sealed event identity
     */
    public record TransitionEvent(
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
        /** Validates one payload-free event without accepting an entity id or value. */
        public TransitionEvent {
            eventIdFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            eventIdFingerprint,
                            "eventIdFingerprint");
            if (stateRevision < 1) {
                throw new IllegalArgumentException(
                        "event stateRevision must be positive");
            }
            mutationId = required(
                    mutationId, "mutationId", 512);
            operation = Objects.requireNonNull(
                    operation, "operation");
            entityType = required(
                    entityType, "entityType", 512);
            entityIdentityFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            entityIdentityFingerprint,
                            "entityIdentityFingerprint");
            beforeFingerprint =
                    MirrorStateProtocolSupport
                            .optionalFingerprint(
                                    beforeFingerprint,
                                    "beforeFingerprint");
            afterFingerprint =
                    MirrorStateProtocolSupport
                            .optionalFingerprint(
                                    afterFingerprint,
                                    "afterFingerprint");
            occurredAt = Objects.requireNonNull(
                    occurredAt, "occurredAt");
            eventFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            eventFingerprint,
                            "eventFingerprint");
        }
    }

    /** Prevents exact state and transaction fingerprints from entering generic logs. */
    @Override
    public String toString() {
        return "MirrorStateTransitionRunEvidence[runId="
                + runId + ", stateRevision="
                + stateRevision + ", finalStateRevision="
                + finalStateRevision + ", bindingCount="
                + statefulBindings.size()
                + ", accessCount=" + accesses.size()
                + ", transitionCount="
                + transitions.size() + "]";
    }

    private static void validateClosure(
            List<StatefulBinding> bindings,
            List<StateAccess> accesses,
            List<StateTransition> transitions,
            MirrorArtifactRef initial,
            MirrorArtifactRef terminal,
            long initialRevision,
            long finalRevision) {
        Map<String, StatefulBinding> bySite =
                bindings.stream().collect(
                        java.util.stream.Collectors
                                .toUnmodifiableMap(
                                        StatefulBinding
                                                ::invocationSiteId,
                                        value -> value));
        for (StateAccess access : accesses) {
            StatefulBinding binding =
                    bySite.get(access.invocationSiteId());
            if (binding == null
                    || binding.interaction()
                    != Interaction.READ
                    || !binding.graphPath().equals(
                    access.graphPath())
                    || !binding.capabilityRef().equals(
                    access.capabilityRef())
                    || !binding.stateReadSpecRef().equals(
                    access.stateReadSpecRef())
                    || !initial.id().equals(
                    access.observedStateRef().id())
                    || access.observedStateRevision()
                    < initialRevision
                    || access.observedStateRevision()
                    > finalRevision) {
                throw new IllegalArgumentException(
                        "state access differs from its binding or run revision range");
            }
        }
        for (StateTransition transition : transitions) {
            StatefulBinding binding =
                    bySite.get(
                            transition.invocationSiteId());
            if (binding == null
                    || binding.interaction()
                    != Interaction.WRITE
                    || !binding.graphPath().equals(
                    transition.graphPath())
                    || !binding.capabilityRef().equals(
                    transition.capabilityRef())
                    || !binding.writeEffectRef().equals(
                    transition.writeEffectRef())
                    || !initial.id().equals(
                    transition.initialStateRef().id())
                    || transition.revisionBefore()
                    < initialRevision
                    || transition.revisionAfter()
                    > finalRevision) {
                throw new IllegalArgumentException(
                        "state transition differs from its binding or run revision range");
            }
        }
        List<StateTransition> committed =
                transitions.stream()
                        .filter(value -> !value.replayed())
                        .sorted(Comparator.comparingLong(
                                StateTransition::revisionAfter))
                        .toList();
        long expected = initialRevision;
        MirrorArtifactRef previous = initial;
        for (StateTransition transition : committed) {
            if (transition.revisionBefore() != expected
                    || !transition.initialStateRef()
                    .equals(previous)) {
                throw new IllegalArgumentException(
                        "committed state transitions must form a contiguous head chain");
            }
            expected = transition.revisionAfter();
            previous = transition.finalStateRef();
        }
        if (expected != finalRevision
                || !previous.equals(terminal)) {
            throw new IllegalArgumentException(
                    "state transition closure does not reach the final Session head");
        }
    }

    private static <T> List<T> ordered(
            List<T> values,
            Comparator<? super T> comparator,
            String field,
            int maximum) {
        List<T> result = values == null
                ? List.of() : values.stream()
                .map(value -> Objects.requireNonNull(
                        value, field + " item"))
                .sorted(comparator).toList();
        if (result.size() > maximum) {
            throw new IllegalArgumentException(
                    field + " exceeds its item limit");
        }
        return result;
    }

    private static List<String> strings(
            List<String> values,
            String field,
            int maximumItems,
            int maximumLength) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > maximumItems) {
            throw new IllegalArgumentException(
                    field + " exceeds its item limit");
        }
        Set<String> result = new TreeSet<>();
        for (String value : values) {
            if (!result.add(required(
                    value, field + " item",
                    maximumLength))) {
                throw new IllegalArgumentException(
                        field + " must be unique");
            }
        }
        return List.copyOf(result);
    }

    private static <T> Set<T> unique(List<T> values) {
        return new HashSet<>(values);
    }

    private static MirrorArtifactRef kind(
            MirrorArtifactRef value,
            String expected,
            String field) {
        MirrorArtifactRef ref =
                Objects.requireNonNull(value, field);
        if (!expected.equals(ref.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + expected);
        }
        return ref;
    }

    private static void positive(
            int occurrence, int attempt) {
        if (occurrence < 1 || attempt < 1) {
            throw new IllegalArgumentException(
                    "occurrence and attempt must be positive");
        }
    }

    private static String coordinate(
            String invocationSiteId,
            String correlationKey,
            int occurrence,
            int attempt) {
        return invocationSiteId + '\0'
                + correlationKey + '\0'
                + occurrence + '\0' + attempt;
    }

    private static String graphPath(String value) {
        String normalized = required(
                value, "graphPath", 2_048);
        if (!normalized.startsWith("/")) {
            throw new IllegalArgumentException(
                    "graphPath must start with /");
        }
        return normalized;
    }

    private static String required(
            String value, String field,
            int maximumLength) {
        String normalized = value == null
                ? "" : value.trim();
        if (normalized.isBlank()
                || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must be non-blank and bounded");
        }
        return normalized;
    }

    private static String bounded(
            String value, String field,
            int maximumLength) {
        String normalized = value == null
                ? "" : value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " exceeds its length limit");
        }
        return normalized;
    }
}
