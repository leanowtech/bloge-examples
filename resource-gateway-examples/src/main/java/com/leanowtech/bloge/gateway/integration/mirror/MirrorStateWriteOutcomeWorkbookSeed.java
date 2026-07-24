package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * Deterministic payload-free ANEKE workbook seed for failure-aware Session writes.
 *
 * <p>The seed projects a verified v5 mirror bundle into one assertion per executed state-write
 * delegate attempt. It preserves exact DAG coordinates, state heads, outcome, last trustworthy
 * stage, retryability, stable error identity, and successful receipt/event closure. Raw command
 * inputs, responses, idempotency keys, entity ids, entity values, and provider diagnostics are
 * deliberately omitted.</p>
 *
 * <p>A rejected write can be a valid negative test, but this artifact cannot infer that business
 * expectation. Rejected, pre-commit-failed, and unknown-commit attempts therefore remain
 * conservative publication blockers until a correctness workbook binds an explicit expected
 * outcome. An unknown durable commit result always requires reconciliation before state
 * continuity can be certified.</p>
 *
 * @param schemaVersion write-outcome workbook seed protocol version
 * @param seedFingerprint canonical fingerprint with this field blanked
 * @param runId exact terminal mirror run
 * @param planFingerprint exact sealed mirror plan
 * @param evidenceBundleFingerprint exact signed source bundle
 * @param stateEvidenceRef exact failure-aware state-evidence artifact
 * @param initialSessionStateRef Session head admitted before graph execution
 * @param finalSessionStateRef final Session head verified inside the graph process
 * @param stateModelRef exact state model used by every interaction
 * @param initialStateRevision initial committed Session revision
 * @param finalStateRevision final committed revision verified inside the process
 * @param initialWorldFingerprint initial simulated business-world identity
 * @param finalWorldFingerprint final in-process simulated business-world identity
 * @param initialLogicalClock initial deterministic business time
 * @param finalLogicalClock final deterministic business time verified in process
 * @param mode observed failure-aware state semantics
 * @param runStatus terminal graph status
 * @param evidenceClass exploratory or certifiable evidence class
 * @param bindingCount number of state-backed invocation sites
 * @param accessCount number of observed state reads
 * @param writeAttemptCount number of terminal state-write attempts
 * @param committedCount number of newly committed writes
 * @param replayedCount number of exact idempotent replays
 * @param rejectedCount number of governed business or admission rejections
 * @param preCommitFailedCount number of failures proven not to have committed
 * @param commitOutcomeUnknownCount number of attempts whose commit result requires reconciliation
 * @param eventCount number of payload-free successful receipt events
 * @param stateAdvanced whether at least one new Session revision committed
 * @param writeAttemptAssertions ordered payload-free attempt assertions
 * @param gateReady whether no conservative publication blocker remains
 * @param blockers deterministic bounded publication blockers
 */
public record MirrorStateWriteOutcomeWorkbookSeed(
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
        MirrorStateWriteOutcomeRunEvidence.Mode mode,
        MirrorRunEvidence.Status runStatus,
        MirrorRunEvidence.EvidenceClass evidenceClass,
        int bindingCount,
        int accessCount,
        int writeAttemptCount,
        int committedCount,
        int replayedCount,
        int rejectedCount,
        int preCommitFailedCount,
        int commitOutcomeUnknownCount,
        int eventCount,
        boolean stateAdvanced,
        List<WriteAttemptAssertion> writeAttemptAssertions,
        boolean gateReady,
        List<String> blockers
) {
    /** Current failure-aware state-write workbook seed version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorStateWriteOutcomeWorkbookSeed.v1";
    /** Maximum canonical seed bytes admitted to fingerprinting. */
    public static final int MAXIMUM_CANONICAL_BYTES = 64 * 1024 * 1024;
    /** Maximum attempt assertions admitted to one workbook seed. */
    public static final int MAXIMUM_ATTEMPTS =
            MirrorStateWriteOutcomeRunEvidence.MAXIMUM_INTERACTIONS;
    /** Maximum deterministic governance blockers admitted to one seed. */
    public static final int MAXIMUM_BLOCKERS = 16;

    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Comparator<WriteAttemptAssertion> ATTEMPT_ORDER =
            Comparator.comparing(
                    WriteAttemptAssertion::invocationSiteId)
                    .thenComparing(
                            WriteAttemptAssertion::graphPath)
                    .thenComparing(
                            WriteAttemptAssertion::correlationKey)
                    .thenComparingInt(
                            WriteAttemptAssertion::occurrence)
                    .thenComparingInt(
                            WriteAttemptAssertion::attempt);

    /** Validates exact counts, state-head closure, and conservative gate readiness. */
    public MirrorStateWriteOutcomeWorkbookSeed {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported mirror state write-outcome workbook seed version");
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
                stateEvidenceRef,
                "MIRROR_STATE_RUN_EVIDENCE",
                "stateEvidenceRef");
        initialSessionStateRef = requireKind(
                initialSessionStateRef,
                "SESSION_STATE",
                "initialSessionStateRef");
        finalSessionStateRef = requireKind(
                finalSessionStateRef,
                "SESSION_STATE",
                "finalSessionStateRef");
        stateModelRef = requireKind(
                stateModelRef, "STATE_MODEL",
                "stateModelRef");
        if (stateEvidenceRef.revision() != 3
                || initialStateRevision < 0
                || finalStateRevision < initialStateRevision
                || initialSessionStateRef.revision()
                != Math.addExact(initialStateRevision, 1)
                || finalSessionStateRef.revision()
                != Math.addExact(finalStateRevision, 1)
                || !initialSessionStateRef.id().equals(
                finalSessionStateRef.id())) {
            throw new IllegalArgumentException(
                    "write-outcome workbook state references are inconsistent");
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
                    "write-outcome workbook logical time moved backward");
        }
        mode = Objects.requireNonNull(mode, "mode");
        runStatus = Objects.requireNonNull(
                runStatus, "runStatus");
        evidenceClass = Objects.requireNonNull(
                evidenceClass, "evidenceClass");
        writeAttemptAssertions = orderedAttempts(
                writeAttemptAssertions);
        blockers = orderedBlockers(blockers);
        validateCounts(
                bindingCount, accessCount, writeAttemptCount,
                committedCount, replayedCount, rejectedCount,
                preCommitFailedCount, commitOutcomeUnknownCount,
                eventCount, writeAttemptAssertions);
        validateClosure(
                writeAttemptAssertions,
                initialSessionStateRef,
                finalSessionStateRef,
                initialStateRevision,
                finalStateRevision);
        if (stateAdvanced
                != (finalStateRevision > initialStateRevision)
                || stateAdvanced != (committedCount > 0)) {
            throw new IllegalArgumentException(
                    "write-outcome workbook state-advance claim is inconsistent");
        }
        if (gateReady != blockers.isEmpty()
                || gateReady && (writeAttemptCount == 0
                || runStatus
                != MirrorRunEvidence.Status.PASSED
                || evidenceClass
                != MirrorRunEvidence.EvidenceClass.CERTIFIABLE
                || rejectedCount > 0
                || preCommitFailedCount > 0
                || commitOutcomeUnknownCount > 0)) {
            throw new IllegalArgumentException(
                    "write-outcome workbook gate readiness is inconsistent");
        }
    }

    /**
     * Projects one verified v5 bundle into a deterministic governance seed.
     *
     * <p>The caller must obtain the bundle from a repository that already verified the detached
     * signature. Projection independently verifies the nested state fingerprint, every successful
     * receipt/event closure, every failure fingerprint, and all cross-object identities.</p>
     *
     * @param mapper canonical protocol mapper
     * @param bundle verified failure-aware evidence bundle
     * @return sealed payload-free write-outcome workbook seed
     */
    public static MirrorStateWriteOutcomeWorkbookSeed project(
            ObjectMapper mapper, MirrorEvidenceBundle bundle) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(bundle, "bundle");
        MirrorRunEvidence run = bundle.evidence();
        MirrorStateEvidence nestedState = run.stateEvidence();
        if (!MirrorEvidenceBundle.WRITE_OUTCOME_SCHEMA_VERSION
                .equals(bundle.schemaVersion())
                || !MirrorEvidenceAttestation
                .WRITE_OUTCOME_SCHEMA_VERSION
                .equals(bundle.attestation().schemaVersion())
                || !MirrorRunEvidence.WRITE_OUTCOME_SCHEMA_VERSION
                .equals(run.schemaVersion())
                || !(nestedState
                instanceof MirrorStateWriteOutcomeRunEvidence state)
                || !bundle.attestation().independentlyVerifiable()
                || !run.runId().equals(state.runId())
                || !run.planFingerprint().equals(
                state.planFingerprint())) {
            throw new IllegalArgumentException(
                    "write-outcome workbook seed requires one verified v5 bundle");
        }
        MirrorStateWriteOutcomeRunEvidenceIntegrity.verify(
                mapper, state);

        List<WriteAttemptAssertion> attempts =
                state.writeAttempts().stream()
                        .map(WriteAttemptAssertion::from)
                        .toList();
        int committed = count(
                attempts,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.COMMITTED);
        int replayed = count(
                attempts,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.REPLAYED);
        int rejected = count(
                attempts,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.REJECTED);
        int preCommitFailed = count(
                attempts,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.PRE_COMMIT_FAILED);
        int unknown = count(
                attempts,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.COMMIT_OUTCOME_UNKNOWN);
        int events = attempts.stream()
                .filter(value -> value.transition() != null)
                .map(WriteAttemptAssertion::transition)
                .mapToInt(value -> value.events().size())
                .sum();
        TreeSet<String> blockers = blockers(run, state);
        if (attempts.isEmpty()) {
            blockers.add("NO_STATE_WRITE_ATTEMPT_OBSERVED");
        }
        if (rejected > 0) {
            blockers.add(
                    "STATE_WRITE_REJECTION_REQUIRES_EXPECTATION");
        }
        if (preCommitFailed > 0) {
            blockers.add(
                    "STATE_WRITE_PRE_COMMIT_FAILURE");
        }
        if (unknown > 0) {
            blockers.add(
                    "STATE_WRITE_COMMIT_OUTCOME_UNKNOWN");
        }
        MirrorStateWriteOutcomeWorkbookSeed unsealed =
                new MirrorStateWriteOutcomeWorkbookSeed(
                        SCHEMA_VERSION, "", run.runId(),
                        run.planFingerprint(),
                        bundle.bundleFingerprint(),
                        MirrorStateWriteOutcomeRunEvidenceIntegrity
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
                        attempts.size(), committed, replayed,
                        rejected, preCommitFailed, unknown,
                        events,
                        state.finalStateRevision()
                                > state.stateRevision(),
                        attempts, blockers.isEmpty(),
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
                    "mirror state write-outcome workbook seed fingerprint mismatch");
        }
    }

    /**
     * Creates a copy carrying a replacement self-fingerprint.
     *
     * @param value replacement canonical fingerprint
     * @return seed copy with the supplied fingerprint
     */
    public MirrorStateWriteOutcomeWorkbookSeed
            withFingerprint(String value) {
        return new MirrorStateWriteOutcomeWorkbookSeed(
                schemaVersion, value, runId,
                planFingerprint,
                evidenceBundleFingerprint,
                stateEvidenceRef,
                initialSessionStateRef,
                finalSessionStateRef, stateModelRef,
                initialStateRevision, finalStateRevision,
                initialWorldFingerprint,
                finalWorldFingerprint,
                initialLogicalClock, finalLogicalClock,
                mode, runStatus, evidenceClass,
                bindingCount, accessCount,
                writeAttemptCount, committedCount,
                replayedCount, rejectedCount,
                preCommitFailedCount,
                commitOutcomeUnknownCount, eventCount,
                stateAdvanced, writeAttemptAssertions,
                gateReady, blockers);
    }

    /**
     * One exact payload-free state-write attempt assertion.
     *
     * @param invocationSiteId stable BLOGE invocation site
     * @param graphPath exact graph path
     * @param correlationKey foreach, loop, or business coordinate
     * @param occurrence one-based invocation occurrence
     * @param attempt one-based delegate attempt
     * @param capabilityRef exact virtual-write capability
     * @param writeEffectRef exact lowering effect
     * @param observedStateRef Session head observed before the attempt
     * @param observedStateRevision committed revision observed before the attempt
     * @param observedWorldFingerprint world observed before the attempt
     * @param observedLogicalClock deterministic time observed before the attempt
     * @param requestFingerprint canonical invocation input identity
     * @param outcome conservative terminal outcome
     * @param stage last trustworthy processing stage
     * @param stateDisposition proven state-head effect
     * @param retryable whether governed retry progression was authorized
     * @param errorCode stable failure code; blank on success
     * @param errorType normalized failure family; blank on success
     * @param failureFingerprint canonical failure identity; blank on success
     * @param transition successful receipt/event closure, otherwise null
     */
    public record WriteAttemptAssertion(
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
            MirrorStateWriteOutcomeRunEvidence.WriteOutcome
                    outcome,
            MirrorStateWriteOutcomeRunEvidence.WriteStage stage,
            MirrorStateWriteOutcomeRunEvidence.StateDisposition
                    stateDisposition,
            boolean retryable,
            String errorCode,
            String errorType,
            String failureFingerprint,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            MirrorStateTransitionWorkbookSeed.WriteAssertion
                    transition
    ) {
        /** Validates one exact successful or failed write-attempt projection. */
        public WriteAttemptAssertion {
            invocationSiteId = required(
                    invocationSiteId,
                    "invocationSiteId", 2_048);
            graphPath = required(
                    graphPath, "graphPath", 4_096);
            correlationKey = bounded(
                    correlationKey,
                    "correlationKey", 1_024);
            if (occurrence < 1 || attempt < 1) {
                throw new IllegalArgumentException(
                        "write-attempt assertion coordinates must be positive");
            }
            capabilityRef = requireKind(
                    capabilityRef, "CAPABILITY",
                    "capabilityRef");
            writeEffectRef = requireKind(
                    writeEffectRef, "WRITE_EFFECT",
                    "writeEffectRef");
            observedStateRef = requireKind(
                    observedStateRef, "SESSION_STATE",
                    "observedStateRef");
            if (observedStateRevision < 0
                    || observedStateRef.revision()
                    != Math.addExact(
                    observedStateRevision, 1)) {
                throw new IllegalArgumentException(
                        "write-attempt observed state is inconsistent");
            }
            observedWorldFingerprint = fingerprint(
                    observedWorldFingerprint,
                    "observedWorldFingerprint");
            observedLogicalClock = Objects.requireNonNull(
                    observedLogicalClock,
                    "observedLogicalClock");
            requestFingerprint = fingerprint(
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
            failureFingerprint = optionalFingerprint(
                    failureFingerprint,
                    "failureFingerprint");
            validateOutcome(
                    invocationSiteId, graphPath,
                    correlationKey, occurrence, attempt,
                    capabilityRef, writeEffectRef,
                    observedStateRef,
                    observedStateRevision,
                    observedWorldFingerprint,
                    observedLogicalClock,
                    requestFingerprint, outcome, stage,
                    stateDisposition, retryable,
                    errorCode, errorType,
                    failureFingerprint, transition);
        }

        /**
         * Projects one verified state write attempt.
         *
         * @param source exact successful or failed attempt
         * @return payload-free workbook assertion
         */
        public static WriteAttemptAssertion from(
                MirrorStateWriteOutcomeRunEvidence
                        .StateWriteAttempt source) {
            Objects.requireNonNull(source, "source");
            return new WriteAttemptAssertion(
                    source.invocationSiteId(),
                    source.graphPath(),
                    source.correlationKey(),
                    source.occurrence(), source.attempt(),
                    source.capabilityRef(),
                    source.writeEffectRef(),
                    source.observedStateRef(),
                    source.observedStateRevision(),
                    source.observedWorldFingerprint(),
                    source.observedLogicalClock(),
                    source.requestFingerprint(),
                    source.outcome(), source.stage(),
                    source.stateDisposition(),
                    source.retryable(),
                    source.errorCode(),
                    source.errorType(),
                    source.failureFingerprint(),
                    source.transition() == null ? null
                            : MirrorStateTransitionWorkbookSeed
                            .WriteAssertion.from(
                                    source.transition()));
        }

        private String coordinate() {
            return invocationSiteId + '\0' + graphPath
                    + '\0' + correlationKey + '\0'
                    + occurrence + '\0' + attempt;
        }
    }

    /** Keeps state, request, receipt, and failure fingerprints out of generic logs. */
    @Override
    public String toString() {
        return "MirrorStateWriteOutcomeWorkbookSeed[runId="
                + runId + ", initialStateRevision="
                + initialStateRevision
                + ", finalStateRevision="
                + finalStateRevision
                + ", writeAttemptCount="
                + writeAttemptCount
                + ", commitOutcomeUnknownCount="
                + commitOutcomeUnknownCount
                + ", gateReady=" + gateReady + "]";
    }

    private static void validateOutcome(
            String site,
            String graphPath,
            String correlationKey,
            int occurrence,
            int attempt,
            MirrorArtifactRef capability,
            MirrorArtifactRef effect,
            MirrorArtifactRef observedState,
            long observedRevision,
            String observedWorld,
            Instant observedClock,
            String requestFingerprint,
            MirrorStateWriteOutcomeRunEvidence.WriteOutcome
                    outcome,
            MirrorStateWriteOutcomeRunEvidence.WriteStage stage,
            MirrorStateWriteOutcomeRunEvidence.StateDisposition
                    disposition,
            boolean retryable,
            String errorCode,
            String errorType,
            String failureFingerprint,
            MirrorStateTransitionWorkbookSeed.WriteAssertion
                    transition) {
        boolean successful =
                outcome
                        == MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.COMMITTED
                        || outcome
                        == MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.REPLAYED;
        if (successful) {
            if (stage
                    != MirrorStateWriteOutcomeRunEvidence
                    .WriteStage.COMPLETED
                    || retryable
                    || !errorCode.isBlank()
                    || !errorType.isBlank()
                    || !failureFingerprint.isBlank()
                    || transition == null
                    || !site.equals(
                    transition.invocationSiteId())
                    || !graphPath.equals(
                    transition.graphPath())
                    || !correlationKey.equals(
                    transition.correlationKey())
                    || occurrence != transition.occurrence()
                    || attempt != transition.attempt()
                    || !capability.equals(
                    transition.capabilityRef())
                    || !effect.equals(
                    transition.writeEffectRef())
                    || !observedState.equals(
                    transition.initialStateRef())
                    || observedRevision
                    != transition.revisionBefore()
                    || !observedWorld.equals(
                    transition.initialWorldFingerprint())
                    || !observedClock.equals(
                    transition.initialLogicalClock())
                    || !requestFingerprint.equals(
                    transition.requestFingerprint())
                    || outcome
                    == MirrorStateWriteOutcomeRunEvidence
                    .WriteOutcome.COMMITTED
                    && (transition.replayed()
                    || disposition
                    != MirrorStateWriteOutcomeRunEvidence
                    .StateDisposition.ADVANCED)
                    || outcome
                    == MirrorStateWriteOutcomeRunEvidence
                    .WriteOutcome.REPLAYED
                    && (!transition.replayed()
                    || disposition
                    != MirrorStateWriteOutcomeRunEvidence
                    .StateDisposition.UNCHANGED)) {
                throw new IllegalArgumentException(
                        "successful write-attempt assertion differs from its transition");
            }
            return;
        }
        if (transition != null
                || errorCode.isBlank()
                || errorType.isBlank()
                || failureFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "failed write-attempt assertion requires failure identity");
        }
        switch (outcome) {
            case REJECTED -> {
                if (disposition
                        != MirrorStateWriteOutcomeRunEvidence
                        .StateDisposition.UNCHANGED
                        || stage
                        != MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.RESOLVER_ADMISSION
                        && stage
                        != MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.COMMAND_ADMISSION
                        && stage
                        != MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.COMMAND_EVALUATION) {
                    throw new IllegalArgumentException(
                            "rejected write-attempt assertion is inconsistent");
                }
            }
            case PRE_COMMIT_FAILED -> {
                if (disposition
                        != MirrorStateWriteOutcomeRunEvidence
                        .StateDisposition.UNCHANGED
                        || stage
                        != MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.COMMAND_ADMISSION
                        && stage
                        != MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.COMMAND_EVALUATION
                        && stage
                        != MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.COMMIT) {
                    throw new IllegalArgumentException(
                            "pre-commit write-attempt assertion is inconsistent");
                }
            }
            case COMMIT_OUTCOME_UNKNOWN -> {
                if (disposition
                        != MirrorStateWriteOutcomeRunEvidence
                        .StateDisposition.UNKNOWN
                        || stage
                        != MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.COMMIT
                        && stage
                        != MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.RESULT_VERIFICATION
                        && stage
                        != MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.PROCESS_INTERRUPTION) {
                    throw new IllegalArgumentException(
                            "unknown write-attempt assertion is inconsistent");
                }
            }
            default -> throw new IllegalArgumentException(
                    "unsupported failed write-attempt assertion");
        }
    }

    private static void validateCounts(
            int bindings,
            int accesses,
            int attempts,
            int committed,
            int replayed,
            int rejected,
            int preCommitFailed,
            int unknown,
            int events,
            List<WriteAttemptAssertion> assertions) {
        if (bindings < 1
                || bindings
                > MirrorStateWriteOutcomeRunEvidence
                .MAXIMUM_BINDINGS
                || accesses < 0
                || accesses > MAXIMUM_ATTEMPTS
                || attempts < 0
                || attempts > MAXIMUM_ATTEMPTS
                || committed < 0 || replayed < 0
                || rejected < 0 || preCommitFailed < 0
                || unknown < 0 || events < 0
                || attempts != assertions.size()
                || attempts != Math.addExact(
                Math.addExact(
                        Math.addExact(committed, replayed),
                        Math.addExact(
                                rejected, preCommitFailed)),
                unknown)
                || committed != count(
                assertions,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.COMMITTED)
                || replayed != count(
                assertions,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.REPLAYED)
                || rejected != count(
                assertions,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.REJECTED)
                || preCommitFailed != count(
                assertions,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.PRE_COMMIT_FAILED)
                || unknown != count(
                assertions,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.COMMIT_OUTCOME_UNKNOWN)
                || events != assertions.stream()
                .filter(value -> value.transition() != null)
                .map(WriteAttemptAssertion::transition)
                .mapToInt(value -> value.events().size())
                .sum()) {
            throw new IllegalArgumentException(
                    "write-outcome workbook counts are inconsistent");
        }
    }

    private static void validateClosure(
            List<WriteAttemptAssertion> attempts,
            MirrorArtifactRef initial,
            MirrorArtifactRef terminal,
            long initialRevision,
            long finalRevision) {
        List<MirrorStateTransitionWorkbookSeed.WriteAssertion>
                committed = attempts.stream()
                .filter(value -> value.outcome()
                        == MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.COMMITTED)
                .map(WriteAttemptAssertion::transition)
                .sorted(Comparator.comparingLong(
                        MirrorStateTransitionWorkbookSeed
                                .WriteAssertion
                                ::revisionAfter))
                .toList();
        long expectedRevision = initialRevision;
        MirrorArtifactRef expectedHead = initial;
        HashSet<MirrorArtifactRef> knownHeads =
                new HashSet<>();
        knownHeads.add(initial);
        for (MirrorStateTransitionWorkbookSeed
                .WriteAssertion transition : committed) {
            if (transition.revisionBefore()
                    != expectedRevision
                    || !transition.initialStateRef()
                    .equals(expectedHead)) {
                throw new IllegalArgumentException(
                        "committed write-outcome assertions do not form a contiguous chain");
            }
            expectedRevision = transition.revisionAfter();
            expectedHead = transition.finalStateRef();
            knownHeads.add(expectedHead);
        }
        if (expectedRevision != finalRevision
                || !expectedHead.equals(terminal)) {
            throw new IllegalArgumentException(
                    "committed write-outcome assertions do not reach the final head");
        }
        HashSet<String> coordinates = new HashSet<>();
        for (WriteAttemptAssertion attempt : attempts) {
            if (!initial.id().equals(
                    attempt.observedStateRef().id())
                    || !knownHeads.contains(
                    attempt.observedStateRef())
                    || !coordinates.add(
                    attempt.coordinate())) {
                throw new IllegalArgumentException(
                        "write-outcome assertion coordinates or state heads are invalid");
            }
        }
    }

    private static TreeSet<String> blockers(
            MirrorRunEvidence run,
            MirrorStateWriteOutcomeRunEvidence state) {
        TreeSet<String> result = new TreeSet<>();
        if (run.status() != MirrorRunEvidence.Status.PASSED) {
            result.add("RUN_NOT_PASSED");
        }
        if (run.evidenceClass()
                != MirrorRunEvidence.EvidenceClass.CERTIFIABLE) {
            result.add("EVIDENCE_NOT_CERTIFIABLE");
        }
        if (!run.limitations().isEmpty()
                || !run.isolation().limitations().isEmpty()) {
            result.add("RUN_EVIDENCE_LIMITED");
        }
        if (!state.limitations().isEmpty()) {
            result.add("STATE_EVIDENCE_LIMITED");
        }
        return result;
    }

    private static int count(
            List<WriteAttemptAssertion> attempts,
            MirrorStateWriteOutcomeRunEvidence.WriteOutcome
                    outcome) {
        return Math.toIntExact(
                attempts.stream()
                        .filter(value -> value.outcome()
                                == outcome)
                        .count());
    }

    private static List<WriteAttemptAssertion>
            orderedAttempts(
            List<WriteAttemptAssertion> values) {
        List<WriteAttemptAssertion> result =
                values == null ? List.of()
                        : values.stream()
                        .map(value -> Objects.requireNonNull(
                                value,
                                "writeAttemptAssertion"))
                        .sorted(ATTEMPT_ORDER)
                        .toList();
        if (result.size() > MAXIMUM_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "write-attempt assertions exceed the protocol bound");
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
                            "write-outcome workbook blockers are invalid");
                }
            }
        }
        if (result.size() > MAXIMUM_BLOCKERS) {
            throw new IllegalArgumentException(
                    "write-outcome workbook blockers exceed the protocol bound");
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
        String normalized =
                value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field
                            + " must be a canonical SHA-256 value");
        }
        return normalized;
    }

    private static String optionalFingerprint(
            String value, String field) {
        String normalized =
                value == null ? "" : value.trim();
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(
                normalized).matches()) {
            throw new IllegalArgumentException(
                    field
                            + " must be blank or a canonical SHA-256 value");
        }
        return normalized;
    }

    private static String required(
            String value, String field, int maximum) {
        String normalized =
                value == null ? "" : value.trim();
        if (normalized.isBlank()
                || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " is required and bounded");
        }
        return normalized;
    }

    private static String bounded(
            String value, String field, int maximum) {
        String normalized =
                value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " exceeds the protocol bound");
        }
        return normalized;
    }
}
