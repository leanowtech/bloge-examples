package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Deterministic payload-free ANEKE workbook seed for one read/write Session run.
 *
 * <p>The seed is a governance projection, not a replacement for the signed v4 evidence bundle.
 * It exposes the exact initial and final Session heads, read outcome counts, and one bounded write
 * assertion per observed virtual mutation. A write assertion retains only invocation coordinates,
 * state and receipt fingerprints, replay status, and payload-free event summaries. Command inputs,
 * responses, entity ids, entity values, raw idempotency keys, and encryption material are never
 * copied into this artifact.</p>
 *
 * @param schemaVersion transition-workbook seed protocol version
 * @param seedFingerprint canonical fingerprint with this field blanked
 * @param runId exact terminal mirror run
 * @param planFingerprint exact sealed mirror plan
 * @param evidenceBundleFingerprint exact signed source bundle
 * @param stateEvidenceRef exact read/write state-evidence artifact
 * @param initialSessionStateRef exact Session head admitted before execution
 * @param finalSessionStateRef exact Session head visible after execution
 * @param stateModelRef exact state model used by the run
 * @param initialStateRevision initial committed Session revision
 * @param finalStateRevision final committed Session revision
 * @param initialWorldFingerprint initial business-world identity
 * @param finalWorldFingerprint final business-world identity
 * @param initialLogicalClock initial deterministic business time
 * @param finalLogicalClock final deterministic business time
 * @param mode observed read/write state semantics
 * @param runStatus terminal run status
 * @param evidenceClass exploratory or certifiable evidence class
 * @param bindingCount number of state-backed invocation sites
 * @param accessCount number of observed state reads
 * @param liveEntityCount number of live state reads
 * @param absentCount number of absent state reads
 * @param tombstonedCount number of terminal tombstone reads
 * @param transitionCount number of observed virtual writes
 * @param committedTransitionCount number of newly committed writes
 * @param replayedTransitionCount number of exact idempotent replays
 * @param eventCount number of payload-free receipt events
 * @param stateAdvanced whether at least one new revision committed
 * @param writeAssertions ordered payload-free write assertions
 * @param gateReady whether no conservative publication blocker remains
 * @param blockers deterministic bounded publication blockers
 */
public record MirrorStateTransitionWorkbookSeed(
        String schemaVersion,
        String seedFingerprint,
        String runId,
        String planFingerprint,
        String evidenceBundleFingerprint,
        MirrorArtifactRef stateEvidenceRef,
        MirrorArtifactRef initialSessionStateRef,
        MirrorArtifactRef finalSessionStateRef,
        MirrorArtifactRef stateModelRef,
        long initialStateRevision,
        long finalStateRevision,
        String initialWorldFingerprint,
        String finalWorldFingerprint,
        Instant initialLogicalClock,
        Instant finalLogicalClock,
        MirrorStateTransitionRunEvidence.Mode mode,
        MirrorRunEvidence.Status runStatus,
        MirrorRunEvidence.EvidenceClass evidenceClass,
        int bindingCount,
        int accessCount,
        int liveEntityCount,
        int absentCount,
        int tombstonedCount,
        int transitionCount,
        int committedTransitionCount,
        int replayedTransitionCount,
        int eventCount,
        boolean stateAdvanced,
        List<WriteAssertion> writeAssertions,
        boolean gateReady,
        List<String> blockers
) {
    /** Current read/write state-workbook seed version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorStateTransitionWorkbookSeed.v1";
    /** Maximum canonical seed bytes admitted to fingerprinting. */
    public static final int MAXIMUM_CANONICAL_BYTES = 64 * 1024 * 1024;
    /** Maximum state-backed sites represented by one seed. */
    public static final int MAXIMUM_BINDINGS =
            MirrorStateTransitionRunEvidence.MAXIMUM_BINDINGS;
    /** Maximum state reads or writes represented by one seed. */
    public static final int MAXIMUM_INTERACTIONS =
            MirrorStateTransitionRunEvidence.MAXIMUM_INTERACTIONS;
    /** Maximum receipt events represented by one seed. */
    public static final int MAXIMUM_EVENTS =
            Math.multiplyExact(MAXIMUM_INTERACTIONS, 128);
    /** Maximum deterministic governance blockers represented by one seed. */
    public static final int MAXIMUM_BLOCKERS = 16;
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Comparator<WriteAssertion> WRITE_ORDER =
            Comparator.comparing(WriteAssertion::invocationSiteId)
                    .thenComparing(WriteAssertion::correlationKey)
                    .thenComparingInt(WriteAssertion::occurrence)
                    .thenComparingInt(WriteAssertion::attempt);

    /** Validates state-head arithmetic, write closure, counts, and conservative readiness. */
    public MirrorStateTransitionWorkbookSeed {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported mirror state transition workbook seed version");
        }
        seedFingerprint = optionalFingerprint(
                seedFingerprint, "seedFingerprint");
        runId = required(runId, "runId", 512);
        planFingerprint = fingerprint(
                planFingerprint, "planFingerprint");
        evidenceBundleFingerprint = fingerprint(
                evidenceBundleFingerprint,
                "evidenceBundleFingerprint");
        stateEvidenceRef = requireKind(
                stateEvidenceRef, "MIRROR_STATE_RUN_EVIDENCE",
                "stateEvidenceRef");
        initialSessionStateRef = requireKind(
                initialSessionStateRef, "SESSION_STATE",
                "initialSessionStateRef");
        finalSessionStateRef = requireKind(
                finalSessionStateRef, "SESSION_STATE",
                "finalSessionStateRef");
        stateModelRef = requireKind(
                stateModelRef, "STATE_MODEL", "stateModelRef");
        if (stateEvidenceRef.revision() != 2
                || initialStateRevision < 0
                || finalStateRevision < initialStateRevision
                || initialSessionStateRef.revision()
                != Math.addExact(initialStateRevision, 1)
                || finalSessionStateRef.revision()
                != Math.addExact(finalStateRevision, 1)
                || !initialSessionStateRef.id().equals(
                finalSessionStateRef.id())) {
            throw new IllegalArgumentException(
                    "transition workbook state references are inconsistent");
        }
        initialWorldFingerprint = fingerprint(
                initialWorldFingerprint,
                "initialWorldFingerprint");
        finalWorldFingerprint = fingerprint(
                finalWorldFingerprint,
                "finalWorldFingerprint");
        initialLogicalClock = Objects.requireNonNull(
                initialLogicalClock, "initialLogicalClock");
        finalLogicalClock = Objects.requireNonNull(
                finalLogicalClock, "finalLogicalClock");
        if (finalLogicalClock.isBefore(initialLogicalClock)) {
            throw new IllegalArgumentException(
                    "transition workbook logical time moved backward");
        }
        mode = Objects.requireNonNull(mode, "mode");
        runStatus = Objects.requireNonNull(
                runStatus, "runStatus");
        evidenceClass = Objects.requireNonNull(
                evidenceClass, "evidenceClass");
        writeAssertions = orderedWrites(writeAssertions);
        blockers = orderedBlockers(blockers);
        validateCounts(
                bindingCount, accessCount, liveEntityCount,
                absentCount, tombstonedCount, transitionCount,
                committedTransitionCount, replayedTransitionCount,
                eventCount, writeAssertions);
        validateWriteClosure(
                writeAssertions, initialSessionStateRef,
                finalSessionStateRef, initialStateRevision,
                finalStateRevision);
        if (stateAdvanced != (finalStateRevision > initialStateRevision)
                || stateAdvanced != (committedTransitionCount > 0)) {
            throw new IllegalArgumentException(
                    "transition workbook state-advance claim is inconsistent");
        }
        if (gateReady != blockers.isEmpty()
                || gateReady && (transitionCount == 0
                || runStatus != MirrorRunEvidence.Status.PASSED
                || evidenceClass
                != MirrorRunEvidence.EvidenceClass.CERTIFIABLE)) {
            throw new IllegalArgumentException(
                    "transition workbook gate readiness is inconsistent");
        }
    }

    /**
     * Projects one verified v4 bundle into a deterministic governance seed.
     *
     * <p>The caller must obtain the bundle from a repository that has already verified its
     * detached signature. Projection independently verifies the nested transition fingerprint,
     * complete receipt/event closure, and all cross-object identities before exposing workbook
     * coordinates.</p>
     *
     * @param mapper canonical protocol mapper
     * @param bundle verified read/write evidence bundle
     * @return sealed payload-free transition-workbook seed
     */
    public static MirrorStateTransitionWorkbookSeed project(
            ObjectMapper mapper, MirrorEvidenceBundle bundle) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(bundle, "bundle");
        MirrorRunEvidence run = bundle.evidence();
        MirrorStateEvidence nestedState = run.stateEvidence();
        if (!MirrorEvidenceBundle.READ_WRITE_SCHEMA_VERSION.equals(
                bundle.schemaVersion())
                || !MirrorEvidenceAttestation.READ_WRITE_SCHEMA_VERSION.equals(
                bundle.attestation().schemaVersion())
                || !MirrorRunEvidence.READ_WRITE_SCHEMA_VERSION.equals(
                run.schemaVersion())
                || !(nestedState
                instanceof MirrorStateTransitionRunEvidence state)
                || !bundle.attestation().independentlyVerifiable()
                || !run.runId().equals(state.runId())
                || !run.planFingerprint().equals(
                state.planFingerprint())) {
            throw new IllegalArgumentException(
                    "transition workbook seed requires one verified v4 bundle");
        }
        MirrorStateTransitionRunEvidenceIntegrity.verify(
                mapper, state);

        int live = 0;
        int absent = 0;
        int tombstoned = 0;
        for (MirrorStateTransitionRunEvidence.StateAccess access
                : state.accesses()) {
            switch (access.outcome()) {
                case LIVE_ENTITY -> live++;
                case ABSENT -> absent++;
                case TOMBSTONED -> tombstoned++;
            }
        }
        List<WriteAssertion> writes = state.transitions()
                .stream().map(WriteAssertion::from).toList();
        int committed = Math.toIntExact(
                writes.stream().filter(
                        value -> !value.replayed()).count());
        int replayed = Math.toIntExact(
                writes.stream().filter(
                        WriteAssertion::replayed).count());
        int events = writes.stream().mapToInt(
                value -> value.events().size()).sum();
        TreeSet<String> blockers = blockers(run, state);
        if (writes.isEmpty()) {
            blockers.add("NO_STATE_TRANSITION_OBSERVED");
        }
        MirrorStateTransitionWorkbookSeed unsealed =
                new MirrorStateTransitionWorkbookSeed(
                        SCHEMA_VERSION, "", run.runId(),
                        run.planFingerprint(),
                        bundle.bundleFingerprint(),
                        MirrorStateTransitionRunEvidenceIntegrity
                                .reference(state),
                        state.sessionStateRef(),
                        state.finalSessionStateRef(),
                        state.stateModelRef(),
                        state.stateRevision(),
                        state.finalStateRevision(),
                        state.worldFingerprint(),
                        state.finalWorldFingerprint(),
                        state.logicalClock(),
                        state.finalLogicalClock(),
                        state.mode(), run.status(),
                        run.evidenceClass(),
                        state.statefulBindings().size(),
                        state.accesses().size(),
                        live, absent, tombstoned,
                        writes.size(), committed, replayed,
                        events, state.finalStateRevision()
                        > state.stateRevision(),
                        writes, blockers.isEmpty(),
                        List.copyOf(blockers));
        return unsealed.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        mapper, unsealed,
                        MAXIMUM_CANONICAL_BYTES));
    }

    /**
     * Recomputes this seed's canonical fingerprint.
     *
     * @param mapper canonical protocol mapper
     * @throws IllegalArgumentException when the seed changed after projection
     */
    public void verify(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (!ProtocolFingerprint.ofBounded(
                mapper, withFingerprint(""),
                MAXIMUM_CANONICAL_BYTES)
                .equals(seedFingerprint)) {
            throw new IllegalArgumentException(
                    "mirror transition workbook seed fingerprint mismatch");
        }
    }

    /**
     * Creates a copy carrying a replacement self-fingerprint.
     *
     * @param fingerprint replacement canonical fingerprint
     * @return seed copy with the supplied fingerprint
     */
    public MirrorStateTransitionWorkbookSeed withFingerprint(
            String fingerprint) {
        return new MirrorStateTransitionWorkbookSeed(
                schemaVersion, fingerprint, runId,
                planFingerprint, evidenceBundleFingerprint,
                stateEvidenceRef, initialSessionStateRef,
                finalSessionStateRef, stateModelRef,
                initialStateRevision, finalStateRevision,
                initialWorldFingerprint, finalWorldFingerprint,
                initialLogicalClock, finalLogicalClock,
                mode, runStatus, evidenceClass, bindingCount,
                accessCount, liveEntityCount, absentCount,
                tombstonedCount, transitionCount,
                committedTransitionCount,
                replayedTransitionCount, eventCount,
                stateAdvanced, writeAssertions,
                gateReady, blockers);
    }

    /**
     * One exact payload-free virtual-write assertion for a correctness workbook.
     *
     * @param invocationSiteId stable BLOGE invocation site
     * @param graphPath exact graph path
     * @param correlationKey foreach, loop, or business correlation coordinate
     * @param occurrence one-based invocation occurrence
     * @param attempt one-based delegate attempt
     * @param capabilityRef exact virtual-write capability
     * @param writeEffectRef exact lowering effect
     * @param initialStateRef state head before the command
     * @param finalStateRef state head visible after the command
     * @param revisionBefore revision before the command
     * @param revisionAfter revision visible after the command
     * @param initialWorldFingerprint world before the command
     * @param finalWorldFingerprint world after the command
     * @param initialLogicalClock logical time before the command
     * @param finalLogicalClock logical time after the command
     * @param requestFingerprint canonical invocation input identity
     * @param idempotencyKeyFingerprint hash of the raw command key
     * @param commandFingerprint exact Session command identity
     * @param receiptFingerprint exact committed receipt
     * @param responseFingerprint exact command response identity
     * @param resultingWorldFingerprint world claimed by the receipt
     * @param committedAt governed logical commit time
     * @param replayed whether an existing receipt was returned
     * @param events exact payload-free receipt-event assertions
     */
    public record WriteAssertion(
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
            List<EventAssertion> events
    ) {
        /** Validates one exact committed or replayed write assertion. */
        public WriteAssertion {
            invocationSiteId = required(
                    invocationSiteId, "invocationSiteId", 2_048);
            graphPath = required(
                    graphPath, "graphPath", 4_096);
            correlationKey = bounded(
                    correlationKey, "correlationKey", 1_024);
            if (occurrence < 1 || attempt < 1) {
                throw new IllegalArgumentException(
                        "write assertion coordinates must be positive");
            }
            capabilityRef = requireKind(
                    capabilityRef, "CAPABILITY",
                    "capabilityRef");
            writeEffectRef = requireKind(
                    writeEffectRef, "WRITE_EFFECT",
                    "writeEffectRef");
            initialStateRef = requireKind(
                    initialStateRef, "SESSION_STATE",
                    "initialStateRef");
            finalStateRef = requireKind(
                    finalStateRef, "SESSION_STATE",
                    "finalStateRef");
            if (revisionBefore < 0
                    || revisionAfter < revisionBefore
                    || initialStateRef.revision()
                    != Math.addExact(revisionBefore, 1)
                    || finalStateRef.revision()
                    != Math.addExact(revisionAfter, 1)
                    || !initialStateRef.id().equals(
                    finalStateRef.id())) {
                throw new IllegalArgumentException(
                        "write assertion state coordinates are inconsistent");
            }
            initialWorldFingerprint = fingerprint(
                    initialWorldFingerprint,
                    "initialWorldFingerprint");
            finalWorldFingerprint = fingerprint(
                    finalWorldFingerprint,
                    "finalWorldFingerprint");
            initialLogicalClock = Objects.requireNonNull(
                    initialLogicalClock, "initialLogicalClock");
            finalLogicalClock = Objects.requireNonNull(
                    finalLogicalClock, "finalLogicalClock");
            requestFingerprint = fingerprint(
                    requestFingerprint, "requestFingerprint");
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
            events = orderedEvents(events);
            if (events.isEmpty()) {
                throw new IllegalArgumentException(
                        "write assertion requires receipt events");
            }
            if (replayed) {
                if (!initialStateRef.equals(finalStateRef)
                        || revisionBefore != revisionAfter
                        || !initialWorldFingerprint.equals(
                        finalWorldFingerprint)
                        || !initialLogicalClock.equals(
                        finalLogicalClock)) {
                    throw new IllegalArgumentException(
                            "replayed write assertion changed the state head");
                }
            } else if (revisionAfter
                    != Math.addExact(revisionBefore, 1)
                    || !resultingWorldFingerprint.equals(
                    finalWorldFingerprint)
                    || events.stream().anyMatch(
                    event -> event.stateRevision()
                            != revisionAfter)) {
                throw new IllegalArgumentException(
                        "committed write assertion does not advance one revision");
            }
        }

        /**
         * Projects one verified transition into its payload-free workbook assertion.
         *
         * @param source exact committed or replayed state transition
         * @return bounded assertion that omits command and entity payloads
         */
        public static WriteAssertion from(
                MirrorStateTransitionRunEvidence.StateTransition source) {
            Objects.requireNonNull(source, "source");
            return new WriteAssertion(
                    source.invocationSiteId(),
                    source.graphPath(),
                    source.correlationKey(),
                    source.occurrence(), source.attempt(),
                    source.capabilityRef(),
                    source.writeEffectRef(),
                    source.initialStateRef(),
                    source.finalStateRef(),
                    source.revisionBefore(),
                    source.revisionAfter(),
                    source.initialWorldFingerprint(),
                    source.finalWorldFingerprint(),
                    source.initialLogicalClock(),
                    source.finalLogicalClock(),
                    source.requestFingerprint(),
                    source.idempotencyKeyFingerprint(),
                    source.commandFingerprint(),
                    source.receiptFingerprint(),
                    source.responseFingerprint(),
                    source.resultingWorldFingerprint(),
                    source.committedAt(), source.replayed(),
                    source.events().stream()
                            .map(EventAssertion::from).toList());
        }

        private String coordinate() {
            return invocationSiteId + '\0' + correlationKey
                    + '\0' + occurrence + '\0' + attempt;
        }
    }

    /**
     * One payload-free entity event assertion nested under a write receipt.
     *
     * @param eventIdFingerprint hash of the internal event id
     * @param stateRevision committed transaction revision
     * @param mutationId owner-governed mutation alias
     * @param operation transition operation
     * @param entityType owner-governed entity type
     * @param entityIdentityFingerprint hash of entity type and raw entity id
     * @param beforeFingerprint previous entity fingerprint, or blank
     * @param afterFingerprint resulting entity fingerprint, or blank
     * @param occurredAt governed logical event time
     * @param eventFingerprint exact sealed event identity
     */
    public record EventAssertion(
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
        /** Validates one payload-free event assertion. */
        public EventAssertion {
            eventIdFingerprint = fingerprint(
                    eventIdFingerprint, "eventIdFingerprint");
            if (stateRevision < 1) {
                throw new IllegalArgumentException(
                        "event assertion revision must be positive");
            }
            mutationId = required(
                    mutationId, "mutationId", 512);
            operation = Objects.requireNonNull(
                    operation, "operation");
            entityType = required(
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

        /**
         * Projects one verified transition event into its payload-free workbook assertion.
         *
         * @param source exact receipt event
         * @return bounded event assertion without raw entity identity or values
         */
        public static EventAssertion from(
                MirrorStateTransitionRunEvidence.TransitionEvent source) {
            Objects.requireNonNull(source, "source");
            return new EventAssertion(
                    source.eventIdFingerprint(),
                    source.stateRevision(),
                    source.mutationId(), source.operation(),
                    source.entityType(),
                    source.entityIdentityFingerprint(),
                    source.beforeFingerprint(),
                    source.afterFingerprint(),
                    source.occurredAt(),
                    source.eventFingerprint());
        }
    }

    /** Keeps exact transaction and state fingerprints out of generic logs. */
    @Override
    public String toString() {
        return "MirrorStateTransitionWorkbookSeed[runId="
                + runId + ", initialStateRevision="
                + initialStateRevision + ", finalStateRevision="
                + finalStateRevision + ", transitionCount="
                + transitionCount + ", eventCount=" + eventCount
                + ", gateReady=" + gateReady + "]";
    }

    private static TreeSet<String> blockers(
            MirrorRunEvidence run,
            MirrorStateTransitionRunEvidence state) {
        TreeSet<String> blockers = new TreeSet<>();
        if (run.status() != MirrorRunEvidence.Status.PASSED) {
            blockers.add("RUN_NOT_PASSED");
        }
        if (run.evidenceClass()
                != MirrorRunEvidence.EvidenceClass.CERTIFIABLE) {
            blockers.add("EVIDENCE_NOT_CERTIFIABLE");
        }
        if (!run.limitations().isEmpty()
                || !run.isolation().limitations().isEmpty()) {
            blockers.add("RUN_EVIDENCE_LIMITED");
        }
        if (!state.limitations().isEmpty()) {
            blockers.add("STATE_EVIDENCE_LIMITED");
        }
        return blockers;
    }

    private static void validateCounts(
            int bindingCount,
            int accessCount,
            int liveEntityCount,
            int absentCount,
            int tombstonedCount,
            int transitionCount,
            int committedTransitionCount,
            int replayedTransitionCount,
            int eventCount,
            List<WriteAssertion> writes) {
        if (bindingCount < 1 || bindingCount > MAXIMUM_BINDINGS
                || accessCount < 0
                || accessCount > MAXIMUM_INTERACTIONS
                || liveEntityCount < 0 || absentCount < 0
                || tombstonedCount < 0
                || transitionCount < 0
                || transitionCount > MAXIMUM_INTERACTIONS
                || committedTransitionCount < 0
                || replayedTransitionCount < 0
                || eventCount < 0 || eventCount > MAXIMUM_EVENTS
                || accessCount != Math.addExact(
                Math.addExact(liveEntityCount, absentCount),
                tombstonedCount)
                || transitionCount != writes.size()
                || transitionCount != Math.addExact(
                committedTransitionCount,
                replayedTransitionCount)
                || committedTransitionCount != writes.stream()
                .filter(value -> !value.replayed()).count()
                || replayedTransitionCount != writes.stream()
                .filter(WriteAssertion::replayed).count()
                || eventCount != writes.stream().mapToInt(
                value -> value.events().size()).sum()) {
            throw new IllegalArgumentException(
                    "transition workbook counts are inconsistent");
        }
    }

    private static void validateWriteClosure(
            List<WriteAssertion> writes,
            MirrorArtifactRef initial,
            MirrorArtifactRef terminal,
            long initialRevision,
            long finalRevision) {
        List<WriteAssertion> committed = writes.stream()
                .filter(value -> !value.replayed())
                .sorted(Comparator.comparingLong(
                        WriteAssertion::revisionAfter))
                .toList();
        long expectedRevision = initialRevision;
        MirrorArtifactRef expectedHead = initial;
        for (WriteAssertion write : writes) {
            if (!initial.id().equals(
                    write.initialStateRef().id())
                    || write.revisionBefore() < initialRevision
                    || write.revisionAfter() > finalRevision) {
                throw new IllegalArgumentException(
                        "write assertion falls outside the run state range");
            }
        }
        for (WriteAssertion write : committed) {
            if (write.revisionBefore() != expectedRevision
                    || !write.initialStateRef().equals(
                    expectedHead)) {
                throw new IllegalArgumentException(
                        "committed workbook writes do not form one head chain");
            }
            expectedRevision = write.revisionAfter();
            expectedHead = write.finalStateRef();
        }
        if (expectedRevision != finalRevision
                || !expectedHead.equals(terminal)) {
            throw new IllegalArgumentException(
                    "workbook write closure does not reach the final head");
        }
    }

    private static List<WriteAssertion> orderedWrites(
            List<WriteAssertion> values) {
        List<WriteAssertion> result = values == null
                ? List.of() : List.copyOf(values);
        if (result.size() > MAXIMUM_INTERACTIONS) {
            throw new IllegalArgumentException(
                    "transition workbook write assertions exceed the protocol bound");
        }
        HashSet<String> coordinates = new HashSet<>();
        WriteAssertion previous = null;
        for (WriteAssertion value : result) {
            Objects.requireNonNull(value, "writeAssertion");
            if (!coordinates.add(value.coordinate())
                    || previous != null
                    && WRITE_ORDER.compare(previous, value) > 0) {
                throw new IllegalArgumentException(
                        "transition workbook write assertions are unordered or duplicated");
            }
            previous = value;
        }
        return result;
    }

    private static List<EventAssertion> orderedEvents(
            List<EventAssertion> values) {
        List<EventAssertion> result = values == null
                ? List.of() : List.copyOf(values);
        if (result.size() > 128) {
            throw new IllegalArgumentException(
                    "write assertion events exceed the protocol bound");
        }
        HashSet<String> identities = new HashSet<>();
        EventAssertion previous = null;
        for (EventAssertion value : result) {
            Objects.requireNonNull(value, "eventAssertion");
            if (!identities.add(value.eventIdFingerprint())
                    || previous != null
                    && previous.eventIdFingerprint().compareTo(
                    value.eventIdFingerprint()) > 0) {
                throw new IllegalArgumentException(
                        "write assertion events are unordered or duplicated");
            }
            previous = value;
        }
        return result;
    }

    private static List<String> orderedBlockers(
            List<String> values) {
        TreeSet<String> result = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = required(
                        value, "blocker", 256);
                if (!normalized.matches(
                        "[A-Z][A-Z0-9_.-]{0,255}")
                        || !result.add(normalized)) {
                    throw new IllegalArgumentException(
                            "transition workbook blockers are invalid");
                }
            }
        }
        if (result.size() > MAXIMUM_BLOCKERS) {
            throw new IllegalArgumentException(
                    "transition workbook blockers exceed the protocol bound");
        }
        return List.copyOf(result);
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value, String kind,
            String field) {
        MirrorArtifactRef required =
                Objects.requireNonNull(value, field);
        if (!kind.equals(required.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return required;
    }

    private static String fingerprint(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
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

    private static String required(
            String value, String field, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()
                || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must be non-blank and bounded");
        }
        return normalized;
    }

    private static String bounded(
            String value, String field, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " exceeds the protocol bound");
        }
        return normalized;
    }
}
