package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Failure-aware, payload-free Session state evidence for one read/write mirror DAG run.
 *
 * <p>Unlike v2 transition evidence, this protocol closes every executed write delegate attempt,
 * including attempts that were rejected before mutation, failed before a known commit point, or
 * ended with an unknown durable commit outcome. Successful attempts retain the complete v2
 * receipt and event closure. Failed attempts retain only exact state coordinates, stable error
 * facts, and canonical fingerprints; command inputs, responses, entity identities, raw
 * idempotency keys, and provider diagnostics are never included.</p>
 *
 * <p>The final Session head is the last head verified inside this graph process. When any attempt
 * has {@link WriteOutcome#COMMIT_OUTCOME_UNKNOWN}, it must not be interpreted as the
 * database-authoritative terminal head. The mandatory
 * {@value #UNKNOWN_OUTCOME_LIMITATION} limitation forces downstream certification and publication
 * gates to reconcile that attempt before trusting state continuity.</p>
 *
 * @param schemaVersion failure-aware state-evidence protocol version
 * @param stateEvidenceFingerprint canonical fingerprint with this field blanked
 * @param runId exact terminal mirror run
 * @param planFingerprint exact sealed mirror plan
 * @param sessionStateRef initial Session head admitted before scheduling
 * @param finalSessionStateRef final Session head verified inside this run
 * @param stateModelRef exact state model used by every interaction
 * @param stateRevision initial committed state revision
 * @param finalStateRevision final in-run committed state revision
 * @param worldFingerprint initial business-world fingerprint
 * @param finalWorldFingerprint final in-run business-world fingerprint
 * @param logicalClock initial deterministic Session logical time
 * @param finalLogicalClock final in-run deterministic Session logical time
 * @param mode state interaction mode
 * @param statefulBindings complete read/write binding closure
 * @param accesses ordered payload-free read observations
 * @param writeAttempts ordered terminal write-attempt outcomes
 * @param limitations bounded state-evidence limitations
 */
public record MirrorStateWriteOutcomeRunEvidence(
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
        List<MirrorStateTransitionRunEvidence.StatefulBinding>
                statefulBindings,
        List<MirrorStateTransitionRunEvidence.StateAccess> accesses,
        List<StateWriteAttempt> writeAttempts,
        List<String> limitations
) implements MirrorStateEvidence {
    /** Current failure-aware Session state-evidence version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorStateRunEvidence.v3";
    /** Limitation required whenever a durable commit result is unknown. */
    public static final String UNKNOWN_OUTCOME_LIMITATION =
            "WRITE_COMMIT_OUTCOME_UNKNOWN";
    /** Maximum state-backed invocation sites admitted to one run. */
    public static final int MAXIMUM_BINDINGS =
            MirrorPlan.MAXIMUM_EXTERNAL_BINDINGS;
    /** Maximum read or write attempts admitted to one run. */
    public static final int MAXIMUM_INTERACTIONS =
            MirrorRunEvidence.MAXIMUM_RESOLUTIONS;
    /** Maximum bounded state-evidence limitations. */
    public static final int MAXIMUM_LIMITATIONS = 64;

    /** Stateful DAG execution semantics represented by v3. */
    public enum Mode {
        SERIALIZABLE_READ_WRITE_OUTCOMES
    }

    /** Terminal state of one graph-embedded Session write delegate attempt. */
    public enum WriteOutcome {
        /** A new receipt and one new Session revision were durably returned. */
        COMMITTED,
        /** An exact idempotency receipt was returned without changing the Session head. */
        REPLAYED,
        /** Admission, validation, precondition, or invariant rejected the command. */
        REJECTED,
        /** The command failed before a commit was attempted or while non-commit was provable. */
        PRE_COMMIT_FAILED,
        /** The process cannot prove whether the durable commit occurred. */
        COMMIT_OUTCOME_UNKNOWN
    }

    /** Last trustworthy stage reached by one write delegate attempt. */
    public enum WriteStage {
        RESOLVER_ADMISSION,
        COMMAND_ADMISSION,
        COMMAND_EVALUATION,
        COMMIT,
        RESULT_VERIFICATION,
        PROCESS_INTERRUPTION,
        COMPLETED
    }

    /** What the attempt can prove about the Session head it observed. */
    public enum StateDisposition {
        ADVANCED,
        UNCHANGED,
        UNKNOWN
    }

    /** Validates heads, deterministic ordering, and exact read/write-attempt closure. */
    public MirrorStateWriteOutcomeRunEvidence {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported mirror state write-outcome evidence version");
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
                    "write-outcome evidence has inconsistent Session revisions");
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
                        MirrorStateTransitionRunEvidence.StatefulBinding
                                ::invocationSiteId)
                        .thenComparing(
                                MirrorStateTransitionRunEvidence
                                        .StatefulBinding::graphPath),
                "statefulBindings", MAXIMUM_BINDINGS);
        if (statefulBindings.isEmpty()
                || unique(statefulBindings.stream()
                .map(MirrorStateTransitionRunEvidence
                        .StatefulBinding::invocationSiteId)
                .toList()).size() != statefulBindings.size()) {
            throw new IllegalArgumentException(
                    "stateful bindings must be non-empty and site-unique");
        }
        accesses = ordered(
                accesses,
                stateAccessOrder(),
                "accesses", MAXIMUM_INTERACTIONS);
        writeAttempts = ordered(
                writeAttempts, StateWriteAttempt.ORDER,
                "writeAttempts", MAXIMUM_INTERACTIONS);
        Set<String> coordinates = new HashSet<>();
        accesses.forEach(access -> {
            if (!coordinates.add(coordinate(
                    access.invocationSiteId(),
                    access.correlationKey(),
                    access.occurrence(), access.attempt()))) {
                throw new IllegalArgumentException(
                        "state interaction coordinates must be unique");
            }
        });
        writeAttempts.forEach(attempt -> {
            if (!coordinates.add(attempt.coordinate())) {
                throw new IllegalArgumentException(
                        "state interaction coordinates must be unique");
            }
        });
        limitations = strings(
                limitations, "limitations",
                MAXIMUM_LIMITATIONS, 512);
        boolean unknown = writeAttempts.stream().anyMatch(
                attempt -> attempt.outcome()
                        == WriteOutcome.COMMIT_OUTCOME_UNKNOWN);
        if (unknown != limitations.contains(
                UNKNOWN_OUTCOME_LIMITATION)) {
            throw new IllegalArgumentException(
                    "unknown write outcomes require one exact limitation");
        }
        validateClosure(
                statefulBindings, accesses, writeAttempts,
                sessionStateRef, finalSessionStateRef,
                stateRevision, finalStateRevision);
    }

    /** @return a copy carrying a replacement canonical fingerprint */
    @Override
    public MirrorStateWriteOutcomeRunEvidence withFingerprint(
            String value) {
        return new MirrorStateWriteOutcomeRunEvidence(
                schemaVersion, value, runId, planFingerprint,
                sessionStateRef, finalSessionStateRef,
                stateModelRef, stateRevision,
                finalStateRevision, worldFingerprint,
                finalWorldFingerprint, logicalClock,
                finalLogicalClock, mode, statefulBindings,
                accesses, writeAttempts, limitations);
    }

    /**
     * Complete terminal outcome for one write delegate attempt.
     *
     * @param invocationSiteId stable BLOGE invocation site
     * @param graphPath exact graph owning the invocation
     * @param correlationKey foreach, loop, or business coordinate
     * @param occurrence one-based invocation occurrence
     * @param attempt one-based delegate attempt
     * @param capabilityRef exact virtual-write capability
     * @param writeEffectRef exact lowering effect
     * @param observedStateRef in-run Session head observed before the attempt
     * @param observedStateRevision committed revision observed before the attempt
     * @param observedWorldFingerprint exact world observed before the attempt
     * @param observedLogicalClock exact deterministic time observed before the attempt
     * @param requestFingerprint canonical invocation input identity
     * @param outcome conservative terminal write outcome
     * @param stage last trustworthy processing stage
     * @param stateDisposition proven state-head effect
     * @param retryable whether governed retry progression was authorized
     * @param errorCode stable failure code; blank only for successful outcomes
     * @param errorType normalized failure family; blank only for successful outcomes
     * @param failureFingerprint canonical stable failure identity; blank only for success
     * @param transition exact receipt/event closure for committed or replayed outcomes
     */
    public record StateWriteAttempt(
            String invocationSiteId,
            String graphPath,
            String correlationKey,
            int occurrence,
            int attempt,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef writeEffectRef,
            MirrorArtifactRef observedStateRef,
            long observedStateRevision,
            String observedWorldFingerprint,
            Instant observedLogicalClock,
            String requestFingerprint,
            WriteOutcome outcome,
            WriteStage stage,
            StateDisposition stateDisposition,
            boolean retryable,
            String errorCode,
            String errorType,
            String failureFingerprint,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            MirrorStateTransitionRunEvidence.StateTransition transition
    ) {
        private static final Comparator<StateWriteAttempt> ORDER =
                Comparator.comparing(
                        StateWriteAttempt::invocationSiteId)
                        .thenComparing(
                                StateWriteAttempt::graphPath)
                        .thenComparing(
                                StateWriteAttempt::correlationKey)
                        .thenComparingInt(
                                StateWriteAttempt::occurrence)
                        .thenComparingInt(
                                StateWriteAttempt::attempt);

        /** Validates one success or failure outcome without inferring an unknown commit result. */
        public StateWriteAttempt {
            invocationSiteId = required(
                    invocationSiteId,
                    "invocationSiteId", 2_048);
            graphPath = MirrorStateWriteOutcomeRunEvidence
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
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            stage = Objects.requireNonNull(stage, "stage");
            stateDisposition = Objects.requireNonNull(
                    stateDisposition, "stateDisposition");
            errorCode = bounded(
                    errorCode, "errorCode", 192);
            errorType = bounded(
                    errorType, "errorType", 128);
            failureFingerprint =
                    MirrorStateProtocolSupport
                            .optionalFingerprint(
                                    failureFingerprint,
                                    "failureFingerprint");
            validateOutcome(
                    invocationSiteId, graphPath,
                    correlationKey, occurrence, attempt,
                    capabilityRef, writeEffectRef,
                    observedStateRef, observedStateRevision,
                    observedWorldFingerprint,
                    observedLogicalClock, requestFingerprint,
                    outcome, stage, stateDisposition,
                    retryable, errorCode, errorType,
                    failureFingerprint, transition);
        }

        private String coordinate() {
            return MirrorStateWriteOutcomeRunEvidence.coordinate(
                    invocationSiteId, correlationKey,
                    occurrence, attempt);
        }
    }

    private static void validateOutcome(
            String invocationSiteId,
            String graphPath,
            String correlationKey,
            int occurrence,
            int attempt,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef writeEffectRef,
            MirrorArtifactRef observedStateRef,
            long observedStateRevision,
            String observedWorldFingerprint,
            Instant observedLogicalClock,
            String requestFingerprint,
            WriteOutcome outcome,
            WriteStage stage,
            StateDisposition disposition,
            boolean retryable,
            String errorCode,
            String errorType,
            String failureFingerprint,
            MirrorStateTransitionRunEvidence.StateTransition transition) {
        boolean successful = outcome == WriteOutcome.COMMITTED
                || outcome == WriteOutcome.REPLAYED;
        if (successful) {
            if (stage != WriteStage.COMPLETED
                    || retryable
                    || !errorCode.isBlank()
                    || !errorType.isBlank()
                    || !failureFingerprint.isBlank()
                    || transition == null
                    || !invocationSiteId.equals(
                    transition.invocationSiteId())
                    || !graphPath.equals(
                    transition.graphPath())
                    || !correlationKey.equals(
                    transition.correlationKey())
                    || occurrence != transition.occurrence()
                    || attempt != transition.attempt()
                    || !capabilityRef.equals(
                    transition.capabilityRef())
                    || !writeEffectRef.equals(
                    transition.writeEffectRef())
                    || !observedStateRef.equals(
                    transition.initialStateRef())
                    || observedStateRevision
                    != transition.revisionBefore()
                    || !observedWorldFingerprint.equals(
                    transition.initialWorldFingerprint())
                    || !observedLogicalClock.equals(
                    transition.initialLogicalClock())
                    || !requestFingerprint.equals(
                    transition.requestFingerprint())) {
                throw new IllegalArgumentException(
                        "successful write attempt differs from its transition closure");
            }
            if (outcome == WriteOutcome.COMMITTED
                    && (transition.replayed()
                    || disposition != StateDisposition.ADVANCED)
                    || outcome == WriteOutcome.REPLAYED
                    && (!transition.replayed()
                    || disposition
                    != StateDisposition.UNCHANGED)) {
                throw new IllegalArgumentException(
                        "successful write outcome has inconsistent state disposition");
            }
            return;
        }
        if (transition != null
                || errorCode.isBlank()
                || errorType.isBlank()
                || failureFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "failed write attempt requires error identity and no transition");
        }
        switch (outcome) {
            case REJECTED -> {
                if (disposition != StateDisposition.UNCHANGED
                        || stage != WriteStage.RESOLVER_ADMISSION
                        && stage != WriteStage.COMMAND_ADMISSION
                        && stage != WriteStage.COMMAND_EVALUATION) {
                    throw new IllegalArgumentException(
                            "rejected write has inconsistent stage or state disposition");
                }
            }
            case PRE_COMMIT_FAILED -> {
                if (disposition != StateDisposition.UNCHANGED
                        || stage != WriteStage.COMMAND_ADMISSION
                        && stage != WriteStage.COMMAND_EVALUATION
                        && stage != WriteStage.COMMIT) {
                    throw new IllegalArgumentException(
                            "pre-commit failure has inconsistent stage or state disposition");
                }
            }
            case COMMIT_OUTCOME_UNKNOWN -> {
                if (disposition != StateDisposition.UNKNOWN
                        || stage != WriteStage.COMMIT
                        && stage != WriteStage.RESULT_VERIFICATION
                        && stage != WriteStage.PROCESS_INTERRUPTION) {
                    throw new IllegalArgumentException(
                            "unknown commit outcome has inconsistent stage or disposition");
                }
            }
            default -> throw new IllegalArgumentException(
                    "unsupported failed write outcome");
        }
    }

    private static void validateClosure(
            List<MirrorStateTransitionRunEvidence.StatefulBinding>
                    bindings,
            List<MirrorStateTransitionRunEvidence.StateAccess>
                    accesses,
            List<StateWriteAttempt> attempts,
            MirrorArtifactRef initial,
            MirrorArtifactRef terminal,
            long initialRevision,
            long finalRevision) {
        Map<String, MirrorStateTransitionRunEvidence.StatefulBinding>
                bySite = bindings.stream().collect(
                java.util.stream.Collectors
                        .toUnmodifiableMap(
                                MirrorStateTransitionRunEvidence
                                        .StatefulBinding
                                        ::invocationSiteId,
                                value -> value));
        for (MirrorStateTransitionRunEvidence.StateAccess
                access : accesses) {
            MirrorStateTransitionRunEvidence.StatefulBinding
                    binding = bySite.get(
                    access.invocationSiteId());
            if (binding == null
                    || binding.interaction()
                    != MirrorStateTransitionRunEvidence
                    .Interaction.READ
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
        for (StateWriteAttempt attempt : attempts) {
            MirrorStateTransitionRunEvidence.StatefulBinding
                    binding = bySite.get(
                    attempt.invocationSiteId());
            if (binding == null
                    || binding.interaction()
                    != MirrorStateTransitionRunEvidence
                    .Interaction.WRITE
                    || !binding.graphPath().equals(
                    attempt.graphPath())
                    || !binding.capabilityRef().equals(
                    attempt.capabilityRef())
                    || !binding.writeEffectRef().equals(
                    attempt.writeEffectRef())
                    || !initial.id().equals(
                    attempt.observedStateRef().id())
                    || attempt.observedStateRevision()
                    < initialRevision
                    || attempt.observedStateRevision()
                    > finalRevision) {
                throw new IllegalArgumentException(
                        "write attempt differs from its binding or run revision range");
            }
        }
        List<MirrorStateTransitionRunEvidence.StateTransition>
                committed = attempts.stream()
                .filter(value -> value.outcome()
                        == WriteOutcome.COMMITTED)
                .map(StateWriteAttempt::transition)
                .sorted(Comparator.comparingLong(
                        MirrorStateTransitionRunEvidence
                                .StateTransition::revisionAfter))
                .toList();
        long expected = initialRevision;
        MirrorArtifactRef previous = initial;
        Set<MirrorArtifactRef> knownHeads =
                new LinkedHashSet<>();
        knownHeads.add(initial);
        for (MirrorStateTransitionRunEvidence.StateTransition
                transition : committed) {
            if (transition.revisionBefore() != expected
                    || !transition.initialStateRef()
                    .equals(previous)) {
                throw new IllegalArgumentException(
                        "committed write attempts must form a contiguous head chain");
            }
            expected = transition.revisionAfter();
            previous = transition.finalStateRef();
            knownHeads.add(previous);
        }
        if (expected != finalRevision
                || !previous.equals(terminal)) {
            throw new IllegalArgumentException(
                    "committed write-attempt closure does not reach the final in-run head");
        }
        for (StateWriteAttempt attempt : attempts) {
            if (!knownHeads.contains(
                    attempt.observedStateRef())) {
                throw new IllegalArgumentException(
                        "write attempt observed a state head outside the committed run chain");
            }
        }
    }

    private static Comparator<MirrorStateTransitionRunEvidence.StateAccess>
            stateAccessOrder() {
        return Comparator.comparing(
                MirrorStateTransitionRunEvidence.StateAccess
                        ::invocationSiteId)
                .thenComparing(
                        MirrorStateTransitionRunEvidence
                                .StateAccess::graphPath)
                .thenComparing(
                        MirrorStateTransitionRunEvidence
                                .StateAccess::correlationKey)
                .thenComparingInt(
                        MirrorStateTransitionRunEvidence
                                .StateAccess::occurrence)
                .thenComparingInt(
                        MirrorStateTransitionRunEvidence
                                .StateAccess::attempt);
    }

    /** Prevents state and failure fingerprints from entering generic logs. */
    @Override
    public String toString() {
        long failed = writeAttempts.stream()
                .filter(attempt -> attempt.outcome()
                        != WriteOutcome.COMMITTED
                        && attempt.outcome()
                        != WriteOutcome.REPLAYED)
                .count();
        return "MirrorStateWriteOutcomeRunEvidence[runId="
                + runId + ", stateRevision="
                + stateRevision + ", finalStateRevision="
                + finalStateRevision + ", bindingCount="
                + statefulBindings.size()
                + ", accessCount=" + accesses.size()
                + ", writeAttemptCount="
                + writeAttempts.size()
                + ", failedWriteAttemptCount="
                + failed + "]";
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
